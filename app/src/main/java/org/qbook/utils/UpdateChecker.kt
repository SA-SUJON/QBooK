package org.qbook.utils

import android.content.Context
import android.content.pm.PackageManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app update check against GitHub Releases.
 *
 * Publishing a new version is just: tag a release in the repo and attach the
 * APK. The app polls the "latest release" endpoint, compares the tag with the
 * installed versionName, and offers a download.
 *
 * No third-party service, no tracking, and it degrades silently when offline.
 */
object UpdateChecker {

    private const val API =
        "https://api.github.com/repos/SA-SUJON/QBooK/releases/latest"

    /** Minimum gap between automatic checks. */
    private const val CHECK_INTERVAL_MS = 12L * 60 * 60 * 1000   // 12 hours

    data class Release(
        val version: String,
        val notes: String,
        val apkUrl: String?,
        val pageUrl: String,
        val sizeBytes: Long
    )

    /** Outcome of a check, so the UI can say something specific. */
    sealed class Result {
        data class Update(val release: Release) : Result()
        object UpToDate : Result()
        object NoReleases : Result()
        object Offline : Result()
        data class Failed(val reason: String) : Result()
    }

    /** Single entry point used by both the manual and background checks. */
    fun check(context: Context, prefs: Prefs): Result {
        val local = currentVersion(context)
        return when (val r = fetchLatestResult()) {
            is Result.Update -> {
                prefs.lastUpdateCheck = System.currentTimeMillis()
                if (isNewer(r.release.version, local)) r else Result.UpToDate
            }
            else -> r
        }
    }

    /** Compares dotted version strings: 2.10.0 is newer than 2.9.9. */
    fun isNewer(remote: String, local: String): Boolean {
        fun parts(v: String) = v.trim()
            .removePrefix("v").removePrefix("V")
            .substringBefore('-')            // drop "-debug" and pre-release tags
            .split('.')
            .map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }

        val r = parts(remote)
        val l = parts(local)
        val n = maxOf(r.size, l.size)
        for (i in 0 until n) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    fun currentVersion(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0)
            .versionName ?: "0.0.0"
    } catch (e: PackageManager.NameNotFoundException) {
        "0.0.0"
    }

    fun shouldAutoCheck(prefs: Prefs): Boolean {
        if (!prefs.autoUpdateCheck) return false
        val last = prefs.lastUpdateCheck
        return System.currentTimeMillis() - last > CHECK_INTERVAL_MS
    }

    /**
     * Fetch the latest release. Blocking - call from a background thread.
     * Returns null on any failure, including no network.
     */
    /** Detailed variant used by [check]. */
    fun fetchLatestResult(): Result {
        return try {
            val conn = (URL(API).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 12000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "QBooK-Updater")
            }
            val code = conn.responseCode
            if (code == 404) {
                conn.disconnect()
                // Repository has no published release yet.
                return Result.NoReleases
            }
            if (code !in 200..299) {
                conn.disconnect()
                return Result.Failed("HTTP $code")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val rel = parse(body) ?: return Result.NoReleases
            Result.Update(rel)
        } catch (e: java.net.UnknownHostException) {
            Result.Offline
        } catch (e: java.net.SocketTimeoutException) {
            Result.Offline
        } catch (e: Exception) {
            Result.Failed(e.javaClass.simpleName)
        }
    }

    private fun parse(body: String): Release? {
        return try {
            val json = JSONObject(body)
            if (json.optBoolean("draft", false)) return null
            val tag = json.optString("tag_name").ifBlank { return null }
            var apk: String? = null
            var size = 0L
            val assets = json.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.optJSONObject(i) ?: continue
                    val name = a.optString("name").lowercase()
                    if (name.endsWith(".apk")) {
                        val url = a.optString("browser_download_url")
                        if (apk == null || !name.contains("debug")) {
                            apk = url
                            size = a.optLong("size", 0L)
                        }
                    }
                }
            }
            Release(tag, json.optString("body", ""), apk,
                json.optString("html_url", ""), size)
        } catch (e: Exception) {
            null
        }
    }

    fun fetchLatest(): Release? {
        return try {
            val conn = (URL(API).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 12000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "QBooK-Updater")
            }
            if (conn.responseCode !in 200..299) {
                conn.disconnect()
                return null
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val json = JSONObject(body)
            if (json.optBoolean("draft", false)) return null

            val tag = json.optString("tag_name").ifBlank { return null }
            val notes = json.optString("body", "")
            val page = json.optString("html_url", "")

            var apk: String? = null
            var size = 0L
            val assets = json.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.optJSONObject(i) ?: continue
                    val name = a.optString("name").lowercase()
                    // Prefer a release APK over a debug one.
                    if (name.endsWith(".apk")) {
                        val url = a.optString("browser_download_url")
                        if (apk == null || !name.contains("debug")) {
                            apk = url
                            size = a.optLong("size", 0L)
                        }
                    }
                }
            }
            Release(tag, notes, apk, page, size)
        } catch (e: Exception) {
            null
        }
    }
}
