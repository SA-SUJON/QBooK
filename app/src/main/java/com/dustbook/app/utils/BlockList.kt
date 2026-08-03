package com.dustbook.app.utils

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale
import java.util.zip.GZIPInputStream

/**
 * Domain blocklist compiled from AdGuard Base, AdGuard Social, EasyList,
 * EasyPrivacy and uBlock Origin (~97k domains, shipped gzipped in assets).
 *
 * Loaded once on a background thread into a HashSet. Lookup is O(labels),
 * matching the domain and every parent suffix, so `a.b.tracker.com` is caught
 * by the entry `tracker.com`.
 */
object BlockList {

    /**
     * Domains are stored as 64-bit FNV-1a hashes rather than strings.
     *
     * The list is ~656k entries; as a HashSet<String> that is roughly 70 MB of
     * heap, enough to get the app killed on a low-memory device. As a sorted
     * LongArray it is ~5 MB and lookup is a binary search, which is fast
     * enough to run on the resource thread. A 64-bit space makes a collision
     * vanishingly unlikely, and the cost of one would be a single unwanted
     * block, never a crash.
     */
    @Volatile
    private var hashes: LongArray = LongArray(0)

    private fun hash(s: String): Long {
        var h = -0x340d631b7bdddcdbL          // FNV-1a 64 offset basis
        for (c in s) {
            h = h xor c.code.toLong()
            h *= 0x100000001b3L               // FNV prime
        }
        return h
    }

    @Volatile
    var isLoaded: Boolean = false
        private set

    /**
     * Hosts that must NEVER be blocked. Facebook's own infrastructure plus the
     * CDNs and identity providers it depends on. Checked before the blocklist,
     * so a bad third-party entry can never break the app.
     */
    private val allowList = setOf(
        // Facebook / Meta core
        "facebook.com", "fbcdn.net", "fbsbx.com", "fb.com", "fb.me", "fb.gg",
        "fb.watch", "m.me", "messenger.com", "meta.com", "oculus.com",
        "instagram.com", "cdninstagram.com", "whatsapp.com", "whatsapp.net",
        // Identity providers needed for login
        "accounts.google.com", "appleid.apple.com", "login.microsoftonline.com",
        // Generic infrastructure
        "gstatic.com", "googleusercontent.com", "googleapis.com",
        "cloudflare.com", "jsdelivr.net", "unpkg.com",
        "cloudfront.net", "amazonaws.com", "akamaized.net", "akamaihd.net",
        "fastly.net", "gravatar.com",
        // Media embeds people actually want
        "youtube.com", "youtu.be", "ytimg.com", "giphy.com", "tenor.com",
        "spotify.com", "soundcloud.com", "vimeo.com"
    )

    /**
     * Extra ad/tracking hosts always blocked even if absent from the asset.
     * Facebook's own ad and telemetry endpoints live here.
     */
    private val extraBlocked = setOf(
        "an.facebook.com",              // Facebook Audience Network
        "connect.facebook.net",         // FB pixel SDK on third-party sites
        "pixel.facebook.com",
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "google-analytics.com", "googletagmanager.com", "googletagservices.com",
        "adnxs.com", "rubiconproject.com", "pubmatic.com", "openx.net",
        "criteo.com", "criteo.net", "taboola.com", "outbrain.com",
        "amazon-adsystem.com", "adsrvr.org", "scorecardresearch.com",
        "quantserve.com", "moatads.com", "adsafeprotected.com",
        "doubleverify.com", "branch.io", "appsflyer.com", "adjust.com",
        "onesignal.com", "hotjar.com", "fullstory.com", "mixpanel.com",
        "segment.io", "segment.com", "amplitude.com", "clarity.ms"
    )

    /** Path fragments that always indicate advertising or telemetry. */
    private val blockedPaths = listOf(
        "/pagead/", "/adsbygoogle", "/adserver/", "/openrtb", "/prebid",
        "/googleads", "/advertisement", "/analytics.js", "/gtag/js",
        "/gtm.js", "/fbevents.js", "/beacon.js", "/tracking.js", "/telemetry",
        "/ad_status.php", "/tr?id=", "/collect?v="
    )

    /** Load the gzipped asset. Call from a background thread. */
    fun load(context: Context) {
        if (isLoaded) return
        synchronized(this) {
            if (isLoaded) return
            val list = ArrayList<Long>(700_000)
            try {
                // The asset ships as blocklist.txt.gz, but aapt strips the .gz
                // suffix and applies its own deflate, so at runtime the entry
                // is named blocklist.txt and is already decompressed. Try both
                // names, and sniff the gzip magic rather than trusting the
                // extension - getting this wrong silently disabled the whole
                // network blocklist.
                val names = listOf("blocklist.txt", "blocklist.txt.gz")
                var opened = false
                for (name in names) {
                    val stream = try {
                        context.assets.open(name)
                    } catch (e: Exception) {
                        null
                    } ?: continue

                    java.io.BufferedInputStream(stream, 64 * 1024).use { buf ->
                        buf.mark(2)
                        val b0 = buf.read()
                        val b1 = buf.read()
                        buf.reset()
                        val gzipped = (b0 == 0x1f && b1 == 0x8b)

                        val src: java.io.InputStream =
                            if (gzipped) GZIPInputStream(buf) else buf

                        BufferedReader(InputStreamReader(src), 64 * 1024).use { r ->
                            var line = r.readLine()
                            while (line != null) {
                                val t = line.trim()
                                if (t.isNotEmpty() && t[0] != '#') list.add(hash(t))
                                line = r.readLine()
                            }
                        }
                    }
                    opened = true
                    break
                }
                if (!opened) {
                    // Asset missing entirely: extraBlocked below still applies.
                }
            } catch (e: Exception) {
                // Asset missing or corrupt: fall back to extraBlocked only.
            }
            for (d in extraBlocked) list.add(hash(d))
            val arr = LongArray(list.size)
            for (i in list.indices) arr[i] = list[i]
            arr.sort()                       // binary search needs sorted input
            hashes = arr
            isLoaded = true
        }
    }

    fun size(): Int = hashes.size

    fun isAllowed(host: String): Boolean =
        allowList.any { host == it || host.endsWith(".$it") }

    /**
     * True if [host] or any of its parent domains is on the blocklist.
     * `ads.tracker.co.uk` checks: ads.tracker.co.uk, tracker.co.uk, co.uk.
     */
    private fun contains(s: String): Boolean {
        val arr = hashes
        if (arr.isEmpty()) return false
        return arr.binarySearch(hash(s)) >= 0
    }

    fun blocksHost(host: String): Boolean {
        if (host.isEmpty()) return false
        if (contains(host)) return true
        var i = host.indexOf('.')
        while (i in 0 until host.length - 1) {
            if (contains(host.substring(i + 1))) return true
            i = host.indexOf('.', i + 1)
        }
        return false
    }

    fun blocksPath(path: String): Boolean = blockedPaths.any { path.contains(it) }

    fun normalizeHost(host: String?): String =
        host?.lowercase(Locale.ROOT)?.removePrefix("www.") ?: ""
}
