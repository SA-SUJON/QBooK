package com.dustbook.app.utils

import android.net.Uri
import java.util.Locale

/**
 * Safe URL classification. Replaces the old `url.contains("facebook.com")`
 * check which allowed https://evil.com/?x=facebook.com to stay inside the
 * logged-in WebView (open redirect / phishing hole).
 */
object UrlHelper {

    private val internalHosts = setOf(
        "facebook.com", "fb.com", "fb.me", "fb.watch", "fb.gg",
        "messenger.com", "fbcdn.net", "fbsbx.com", "m.me"
    )

    /** Hosts required to complete a login flow - must stay in the WebView. */
    private val authHosts = setOf(
        "accounts.google.com", "appleid.apple.com", "login.microsoftonline.com",
        "l.facebook.com", "lm.facebook.com"
    )

    fun hostOf(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return try {
            Uri.parse(url).host?.lowercase(Locale.ROOT)
        } catch (e: Exception) {
            null
        }
    }

    private fun matches(host: String, set: Set<String>): Boolean =
        set.any { host == it || host.endsWith(".$it") }

    fun isInternal(url: String?): Boolean {
        val h = hostOf(url) ?: return false
        return matches(h, internalHosts) || matches(h, authHosts)
    }

    /**
     * A raw media file on Facebook's own CDN - the URL behind the ⋮ menu's
     * "Save" option for a photo or video. These hosts are also in
     * [internalHosts] (so ordinary Facebook pages stay in the WebView), but
     * a file link among them must be downloaded, not navigated to, or
     * "Save" silently opens the image in place of the feed instead of
     * putting a file in Downloads.
     */
    fun isDirectMediaLink(url: String?): Boolean {
        val h = hostOf(url) ?: return false
        // scontent*.fbcdn.net (photos/video) and video.*.fbcdn.net both
        // serve raw media, never an HTML page.
        val onMediaCdn = h == "fbcdn.net" || h.endsWith(".fbcdn.net")
        if (!onMediaCdn) return false
        val path = try { Uri.parse(url).path?.lowercase(Locale.ROOT) } catch (e: Exception) { null }
            ?: return false
        val mediaExt = setOf(
            ".jpg", ".jpeg", ".png", ".gif", ".webp", ".heic",
            ".mp4", ".mov", ".m4v", ".webm"
        )
        return mediaExt.any { path.endsWith(it) }
    }

    /** Play Store / App Store / app-install links that must never open. */
    fun isAppStoreLink(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val u = url.lowercase(Locale.ROOT)
        return u.startsWith("market://") ||
            u.startsWith("fb://") ||
            u.startsWith("intent://") ||
            u.startsWith("itms-apps://") ||
            u.contains("play.google.com/store/apps") ||
            u.contains("apps.apple.com") ||
            u.contains("itunes.apple.com") ||
            u.contains("facebook.com/mobile/download") ||
            u.contains("messenger.com/download")
    }

    /**
     * True for logged-out pages: login, signup, checkpoint, password recovery.
     * The real Facebook app shows no top bar or bottom tabs on these screens,
     * so neither should we.
     */
    fun isAuthPage(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val u = url.lowercase(Locale.ROOT)
        val path = try { Uri.parse(url).path?.lowercase(Locale.ROOT) ?: "" } catch (e: Exception) { "" }
        // Require a boundary after the segment, otherwise a real profile such
        // as /loginsmith would be treated as the login screen.
        val authSegments = listOf(
            "login", "reg", "signup", "checkpoint", "recover",
            "confirmemail", "two_step_verification", "authentication"
        )
        val first = path.trim('/').substringBefore('/')
        if (authSegments.contains(first)) return true
        if (first == "r.php" || first == "reg.php" || first == "login.php") return true
        return u.contains("/login.php") || u.contains("/checkpoint/")
    }

    /**
     * True when the Facebook session cookie is present.
     *
     * `c_user` holds the numeric user id and only exists while signed in.
     * This is the reliable signal: Facebook serves the login form at
     * https://www.facebook.com/ itself when logged out, so the URL alone
     * cannot tell the two states apart.
     */
    fun isLoggedIn(): Boolean {
        return try {
            val c = android.webkit.CookieManager.getInstance()
                .getCookie("https://www.facebook.com") ?: return false
            c.split(";").any { it.trim().startsWith("c_user=") && it.trim().length > 8 }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Facebook pushes mobile web users to install Messenger when they open a
     * thread on m.facebook.com. mbasic serves a working chat UI instead, so
     * messaging keeps functioning inside the app.
     */
    fun isMessagingUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val u = url.lowercase(Locale.ROOT)
        val h = hostOf(url) ?: return false
        val path = try { Uri.parse(url).path?.lowercase(Locale.ROOT) ?: "" } catch (e: Exception) { "" }

        if (h.endsWith("messenger.com") || h == "m.me" || h.endsWith(".m.me")) return true
        if (!h.endsWith("facebook.com")) return false
        return path.startsWith("/messages") || path.startsWith("/t/") ||
            path.startsWith("/chat") || u.contains("/messages/read") ||
            u.contains("/messages/thread")
    }

    /**
     * Rewrite a messaging URL so it opens a chat that actually works.
     *
     * Facebook serves "Chats on mobile browsers are not available" whenever it
     * sees a mobile user agent, on m.facebook.com and mbasic alike (mbasic now
     * returns 400 outright). Messenger web is fully functional for a desktop
     * browser, so messaging is routed to www.messenger.com and the WebView is
     * switched to a desktop user agent for that navigation only.
     */
    fun toInAppMessaging(url: String): String {
        return try {
            val uri = Uri.parse(url)
            val h = uri.host?.lowercase(Locale.ROOT) ?: return "https://www.messenger.com/"
            val path = uri.path ?: ""

            // m.me/<user> opens that person's thread.
            if (h == "m.me" || h.endsWith(".m.me")) {
                val who = path.trim('/')
                return if (who.isBlank()) "https://www.messenger.com/"
                       else "https://www.messenger.com/t/$who"
            }
            // Already Messenger: keep the thread path.
            if (h.endsWith("messenger.com")) {
                return "https://www.messenger.com" + (if (path.isBlank()) "/" else path)
            }
            // facebook.com/messages/... -> messenger.com equivalent
            val tid = uri.getQueryParameter("tid")
                ?: uri.getQueryParameter("thread_id")
            if (!tid.isNullOrBlank()) return "https://www.messenger.com/t/$tid"

            val m = Regex("/messages/(?:t|thread|read)/([^/?#]+)").find(path)
            if (m != null) return "https://www.messenger.com/t/${m.groupValues[1]}"

            "https://www.messenger.com/"
        } catch (e: Exception) {
            "https://www.messenger.com/"
        }
    }

    /** Desktop UA, required for Messenger web to serve a working chat. */
    const val DESKTOP_UA =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/122.0.0.0 Safari/537.36"

    fun isSpecialScheme(url: String?): Boolean {
        val u = url?.lowercase(Locale.ROOT) ?: return false
        return u.startsWith("tel:") || u.startsWith("mailto:") ||
            u.startsWith("sms:") || u.startsWith("smsto:") ||
            u.startsWith("geo:") || u.startsWith("whatsapp:")
    }
}
