package com.dustbook.app.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Whether offline saving is allowed to use the connection that is up.
 *
 * A full pass fetches feed pages, reels and their video — easily hundreds of
 * megabytes. On a mobile plan that is the user's month gone, silently, in the
 * background. So it is restricted to unmetered networks unless they say
 * otherwise.
 *
 * Metered is the right test rather than "is this Wi-Fi". A tethered hotspot
 * and a metered Wi-Fi network both bill the user exactly like mobile data, and
 * Android already tracks that: the system honours the "Data usage → unmetered"
 * marking the user sets per network. Asking about the transport instead would
 * happily burn a tethered phone's allowance.
 */
object NetworkPolicy {

    /**
     * True when a download may start or continue right now.
     *
     * @param context any context; the application one is used internally.
     */
    fun canDownload(context: Context, prefs: Prefs): Boolean {
        if (!prefs.offlineWifiOnly) return isConnected(context)
        return isUnmetered(context)
    }

    /** True when some network is up, whatever it costs. */
    fun isConnected(context: Context): Boolean = caps(context)?.hasCapability(
        NetworkCapabilities.NET_CAPABILITY_INTERNET
    ) == true

    /**
     * True when the active network is free to use.
     *
     * NOT_METERED covers Wi-Fi and Ethernet that the user has not flagged as
     * metered. A missing reading is treated as metered: refusing to download
     * costs the user nothing, guessing wrong costs them money.
     */
    fun isUnmetered(context: Context): Boolean {
        val c = caps(context) ?: return false
        if (!c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        return c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /** True when a connection exists but the setting is holding downloads back. */
    fun blockedByMetered(context: Context, prefs: Prefs): Boolean =
        prefs.offlineWifiOnly && isConnected(context) && !isUnmetered(context)

    private fun caps(context: Context): NetworkCapabilities? = try {
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        cm?.getNetworkCapabilities(cm.activeNetwork)
    } catch (e: Exception) {
        null
    }
}
