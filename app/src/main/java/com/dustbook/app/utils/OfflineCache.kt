package com.dustbook.app.utils

import android.content.Context
import android.webkit.MimeTypeMap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.Locale

/**
 * Offline cache, so the feed still shows content with no connection - the way
 * the real Facebook app does.
 *
 * The WebView's own HTTP cache is dropped aggressively and does not survive
 * being offline, so we keep our own copy of the static assets that make the
 * feed renderable: images, video, CSS, JS and fonts.
 *
 * Design notes
 *  - Only GET requests for cacheable media are stored.
 *  - GraphQL and HTML are never cached: replaying a stale API response would
 *    corrupt the session and show the wrong logged-in user.
 *  - Writes happen on the WebView's background thread, which is where
 *    shouldInterceptRequest already runs.
 *  - The store is a flat directory of SHA-256 named files plus a ".mime"
 *    sidecar, trimmed to [MAX_BYTES] on a least-recently-used basis.
 */
object OfflineCache {

    // Bumped when the storage format changes. Anything written by an older
    // build is discarded, which clears the gzip-corrupted entries the
    // previous version could produce.
    private const val CACHE_VERSION = 2
    private const val DIR = "offline_v2"
    // Sized for the V4 targets. OfflineManager aims for 200 reels, and a reel
    // is commonly 5-12 MB, so a 220 MB ceiling meant the LRU trim was deleting
    // reels as fast as the sync fetched them - the target could never be met
    // and the two fought each other indefinitely.
    private const val MAX_BYTES = 2200L * 1024 * 1024   // 2.2 GB
    private const val TRIM_TO = 1800L * 1024 * 1024     // 1.8 GB

    /** Leave at least this much free on the device, whatever the ceiling. */
    private const val MIN_FREE_BYTES = 1500L * 1024 * 1024   // 1.5 GB
    // A reel is commonly 5-20 MB; the old 25 MB ceiling silently dropped the
    // longer ones, so they were listed as saved but had no bytes behind them.
    private const val MAX_ENTRY = 60L * 1024 * 1024

    /** Extensions worth keeping for offline rendering. */
    private val cacheableExt = setOf(
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "ico",
        "mp4", "webm", "m4v", "mov",
        "css", "js", "woff", "woff2", "ttf", "otf"
    )

    private val cacheableMime = listOf(
        "image/", "video/", "font/", "text/css", "application/javascript",
        "text/javascript", "application/x-javascript", "application/font"
    )

    @Volatile private var root: File? = null
    @Volatile var enabled: Boolean = true

    /**
     * Whether new content may be *written*.
     *
     * [enabled] used to gate reading and writing together, so switching
     * saving off also hid content already on disk. Reading is now always
     * allowed; only collecting new content follows the user's switches.
     */
    @Volatile var writeEnabled: Boolean = true


    /** Bytes served from cache while offline, shown in the hidden settings. */
    @Volatile var offlineHits: Int = 0
        private set

    fun init(context: Context) {
        if (root != null) return
        synchronized(this) {
            if (root != null) return
            val d = File(context.cacheDir, DIR)
            if (!d.exists()) d.mkdirs()
            root = d
        }
    }

    /** Simple LRU cache for SHA-256 hashes, so the resource thread never
     *  computes the same hash twice. Capped small because URLs are long. */
    private val hashCache = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > 256
    }

    private fun key(url: String): String {
        synchronized(hashCache) { hashCache[url] }?.let { return it }
        val md = MessageDigest.getInstance("SHA-256")
        val b = md.digest(url.toByteArray())
        val sb = StringBuilder(64)
        for (x in b) sb.append(String.format("%02x", x))
        val k = sb.toString()
        synchronized(hashCache) { hashCache[url] = k }
        return k
    }

    private fun isCacheable(url: String, mime: String?): Boolean {
        val clean = url.substringBefore('?').lowercase(Locale.ROOT)
        // Never cache the API or documents - stale data breaks the session.
        if (clean.contains("/api/graphql") || clean.contains("/ajax/")) return false
        val ext = MimeTypeMap.getFileExtensionFromUrl(clean)
        if (ext.isNotEmpty() && cacheableExt.contains(ext)) return true
        if (mime != null) {
            val m = mime.lowercase(Locale.ROOT)
            if (m.startsWith("text/html")) return false
            return cacheableMime.any { m.startsWith(it) }
        }
        return false
    }


    /**
     * True only for requests we are allowed to take over.
     *
     * Everything else must reach the network untouched. In particular the
     * GraphQL POSTs that drive infinite scroll: re-issuing one as a GET
     * silently breaks feed pagination.
     */
    fun isInterceptable(request: WebResourceRequest): Boolean {
        if (!enabled) return false
        if (!request.method.equals("GET", true)) return false

        val url = request.url.toString()
        val clean = url.substringBefore('?').lowercase(Locale.ROOT)

        // Never touch the API, documents or navigations.
        if (clean.contains("/api/graphql")) return false
        if (clean.contains("/ajax/")) return false
        if (request.isForMainFrame) return false

        // Range requests are how video is fetched at all - the player asks
        // for bytes, never the whole file. They are answered in [range].
        // (Online we still return null further up, so seeking is untouched.)

        // If the bytes are on disk, they are servable. Full stop.
        //
        // This check used to come last, behind a file-extension test and an
        // Accept-header test, and those two between them rejected:
        //
        //   * Facebook's stylesheets - served from /rsrc.php/... with no
        //     extension at all, which is why the offline page rendered as
        //     raw unstyled markup with a giant wordmark
        //   * its icon fonts - requested with `Accept: */*`
        //   * reel and story video - /o1/v/t2/... with no extension and
        //     `Accept: */*`, so playback fell through to a dead network
        //
        // Rejecting here does not mean "fetch it later", it means "hand this
        // request to a WebView that has no connection". Asking the store what
        // it actually holds is both cheaper and correct.
        if (has(url)) return true

        val ext = MimeTypeMap.getFileExtensionFromUrl(clean)
        if (ext.isNotEmpty() && cacheableExt.contains(ext)) return true

        // Facebook serves media from scontent/video hosts without a usable
        // extension, so fall back to the accept header.
        val accept = request.requestHeaders["Accept"]?.lowercase(Locale.ROOT) ?: ""
        return accept.startsWith("image/") || accept.startsWith("video/") ||
            accept.contains("image/webp") || accept.contains("image/avif")
    }

    /**
     * Look up a cached copy. Called from shouldInterceptRequest.
     * @param offlineOnly when true (no network) we serve anything we have.
     */
    fun get(request: WebResourceRequest, offlineOnly: Boolean): WebResourceResponse? {
        if (!enabled) return null
        if (!request.method.equals("GET", true)) return null
        val dir = root ?: return null
        val url = request.url.toString()
        if (!offlineOnly && !isCacheable(url, null)) return null

        val f = File(dir, key(url))
        if (!f.exists() || f.length() == 0L) return null
        if (f.name.endsWith(".part")) return null

        val mime = try {
            File(dir, key(url) + ".mime").takeIf { it.exists() }?.readText()
        } catch (e: Exception) {
            null
        }?.takeIf { it.isNotBlank() } ?: guessMime(url)

        return try {
            // LRU touch, but never from the resource thread. A write on every
            // single asset request is what made offline navigation crawl.
            // The trim process runs in its own sweep and sorts by mtime then,
            // which is the only thing an LRU order matters for.
            if (offlineOnly) offlineHits++
            val charset = mime.substringAfter("charset=", "")
                .substringBefore(';').trim().ifBlank { "utf-8" }
            WebResourceResponse(
                mime.substringBefore(';').trim(),
                charset,
                200,
                "OK",
                mapOf(
                    "Access-Control-Allow-Origin" to "*",
                    "Cache-Control" to "max-age=604800",
                    "Accept-Ranges" to "bytes",
                    "Content-Length" to f.length().toString()
                ),
                FileInputStream(f)
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * A content type for an asset whose sidecar is missing or empty.
     *
     * `application/octet-stream` is not a safe default here: a WebView will
     * not apply a stylesheet or play a video served under it, so a cached file
     * with no recorded mime is effectively invisible. Facebook's URLs carry
     * enough structure to do better.
     */
    private fun guessMime(url: String): String {
        val clean = url.substringBefore('?').lowercase(Locale.ROOT)
        return when {
            clean.endsWith(".css") -> "text/css"
            clean.endsWith(".js") -> "application/javascript"
            clean.endsWith(".woff2") -> "font/woff2"
            clean.endsWith(".woff") -> "font/woff"
            clean.endsWith(".ttf") -> "font/ttf"
            clean.endsWith(".otf") -> "font/otf"
            clean.endsWith(".svg") -> "image/svg+xml"
            clean.endsWith(".png") -> "image/png"
            clean.endsWith(".webp") -> "image/webp"
            clean.endsWith(".gif") -> "image/gif"
            clean.endsWith(".jpg") || clean.endsWith(".jpeg") -> "image/jpeg"
            clean.endsWith(".mp4") || clean.endsWith(".m4v") -> "video/mp4"
            clean.endsWith(".webm") -> "video/webm"
            // Facebook's video files carry no extension at all.
            clean.contains("/v/t2/") || clean.contains("/o1/v/") -> "video/mp4"
            else -> "application/octet-stream"
        }
    }

    /**
     * Answer a Range request from a stored file.
     *
     * A media element will not play from a plain 200 response when it asked
     * for a range; it needs 206 with a Content-Range that matches. This is
     * what makes saved video actually play offline.
     */
    fun range(request: WebResourceRequest): WebResourceResponse? {
        val dir = root ?: return null
        val url = request.url.toString()
        val f = File(dir, key(url))
        if (!f.exists() || f.length() == 0L) return null

        val header = request.requestHeaders.entries
            .firstOrNull { it.key.equals("Range", true) }?.value ?: return null

        val total = f.length()
        val spec = header.substringAfter("bytes=", "").trim()
        if (spec.isBlank()) return null
        val start = spec.substringBefore('-').toLongOrNull() ?: 0L
        val end = spec.substringAfter('-').toLongOrNull()?.coerceAtMost(total - 1)
            ?: (total - 1)
        if (start < 0 || start > end || start >= total) return null

        val mime = try {
            File(dir, key(url) + ".mime").takeIf { it.exists() }?.readText()
        } catch (e: Exception) {
            null
        }?.takeIf { it.isNotBlank() } ?: guessMime(url)

        return try {
            offlineHits++
            val stream = FileInputStream(f)
            stream.skip(start)
            WebResourceResponse(
                mime.substringBefore(';').trim(),
                null,
                206,
                "Partial Content",
                mapOf(
                    "Accept-Ranges" to "bytes",
                    "Content-Range" to "bytes $start-$end/$total",
                    "Content-Length" to (end - start + 1).toString(),
                    "Access-Control-Allow-Origin" to "*"
                ),
                stream
            )
        } catch (e: Exception) {
            null
        }
    }

    /** Store a fetched asset. Safe to call from any background thread. */
    fun put(url: String, mime: String?, bytes: ByteArray) {
        if (!writeEnabled) return
        val dir = root ?: return
        if (bytes.isEmpty() || bytes.size > MAX_ENTRY) return
        val resolvedMime = if (mime == null || mime == "application/octet-stream")
            guessMime(url) else mime
        if (!isCacheable(url, resolvedMime)) return
        try {
            val k = key(url)
            val tmp = File(dir, "$k.part")
            FileOutputStream(tmp).use { it.write(bytes) }
            File(dir, "$k.mime").writeText(resolvedMime)
            if (!tmp.renameTo(File(dir, k))) tmp.delete()
        } catch (e: Exception) {
            // Out of space or race with trim: not fatal.
        }
    }

    /**
     * Drop the oldest files once the store grows past the limit.
     *
     * Two limits, not one. The ceiling is large enough for the V4 targets, but
     * a large ceiling can fill a small device, so the store also yields when
     * free space runs low - the phone matters more than the cache.
     */
    fun trimIfNeeded() {
        val dir = root ?: return
        try {
            val files = dir.listFiles() ?: return
            var total = 0L
            for (f in files) total += f.length()

            val free = try { dir.usableSpace } catch (e: Exception) { Long.MAX_VALUE }
            val lowOnSpace = free in 1 until MIN_FREE_BYTES
            if (total <= MAX_BYTES && !lowOnSpace) return

            // When space is short, take the store further down than usual so
            // this does not have to run again on every single write.
            val floor = if (lowOnSpace) minOf(TRIM_TO, total / 2) else TRIM_TO

            val sorted = files.filter {
                !it.name.endsWith(".mime") && !it.name.endsWith(".part")
            }
                .sortedBy { it.lastModified() }
            for (f in sorted) {
                if (total <= floor) break
                total -= f.length()
                val mimeFile = File(dir, f.name + ".mime")
                total -= mimeFile.length()
                f.delete()
                mimeFile.delete()
            }
        } catch (e: Exception) {
            // ignore
        }
    }

    /** True if we already stored this URL. */
    /**
     * The stored bytes for a URL, as text.
     *
     * Needed to read a downloaded stylesheet: Facebook's icons are font
     * glyphs, and the font's URL appears only inside the CSS, never in the
     * page markup. Without reading the CSS the font is never discovered and
     * every icon renders as a tofu box offline.
     *
     * Capped, because this is only ever used on stylesheets.
     */
    fun textOf(url: String, maxBytes: Int = 2 * 1024 * 1024): String? {
        val dir = root ?: return null
        return try {
            val f = File(dir, key(url))
            if (!f.exists() || f.length() == 0L || f.length() > maxBytes) null
            else f.readText()
        } catch (e: Exception) {
            null
        }
    }

    fun has(url: String): Boolean {
        val dir = root ?: return false
        return try { File(dir, key(url)).exists() } catch (e: Exception) { false }
    }

    /**
     * True when the asset exists AND is not trivially small.
     *
     * `has()` returns true for a 1 KB file, which is enough for an avatar
     * but not for a video. The settings count used to report "13 reels
     * saved" when only the thumbnail images were cached and the actual MP4
     * files were either missing or incomplete. A reel that is too small to
     * play is not a saved reel.
     */
    fun hasMinSize(url: String, minBytes: Long): Boolean {
        val dir = root ?: return false
        return try {
            val f = File(dir, key(url))
            f.exists() && f.length() >= minBytes
        } catch (e: Exception) { false }
    }

    fun sizeBytes(): Long {
        val dir = root ?: return 0
        return try {
            dir.listFiles()?.sumOf { it.length() } ?: 0
        } catch (e: Exception) {
            0
        }
    }

    fun itemCount(): Int {
        val dir = root ?: return 0
        return try {
            dir.listFiles()?.count { !it.name.endsWith(".mime") } ?: 0
        } catch (e: Exception) {
            0
        }
    }

    fun clear() {
        val dir = root ?: return
        try {
            dir.listFiles()?.forEach { it.delete() }
            offlineHits = 0
        } catch (e: Exception) {
            // ignore
        }
    }
}
