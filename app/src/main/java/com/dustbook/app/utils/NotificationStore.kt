package com.dustbook.app.utils

import android.content.Context

/**
 * Remembers which notifications have already been shown.
 *
 * The scraper re-reads the whole page every pass, so without this the same
 * rows would be posted again every fifteen minutes. Only ids are kept, capped,
 * and nothing about the content is stored.
 */
object NotificationStore {

    private const val FILE = "notif_seen"
    private const val KEY_IDS = "ids"
    private const val KEY_FIRST_RUN = "first_run_done"

    /** Plenty to cover a page of rows without growing without bound. */
    private const val MAX_KEPT = 300

    private fun sp(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * The first pass after the feature is switched on must stay silent.
     *
     * Otherwise the user is handed a burst of notifications for everything
     * already sitting on their notifications page, none of which is new to
     * them. The first pass only records what is there.
     */
    fun isFirstRun(context: Context): Boolean =
        !sp(context).getBoolean(KEY_FIRST_RUN, false)

    fun markFirstRunDone(context: Context) {
        sp(context).edit().putBoolean(KEY_FIRST_RUN, true).apply()
    }

    fun seen(context: Context): Set<String> =
        sp(context).getStringSet(KEY_IDS, emptySet()) ?: emptySet()

    /** Add [ids] to the seen set, trimming the oldest when it gets long. */
    fun remember(context: Context, ids: Collection<String>) {
        if (ids.isEmpty()) return
        val current = seen(context).toMutableList()
        for (id in ids) {
            current.remove(id)
            current.add(id)
        }
        while (current.size > MAX_KEPT) current.removeAt(0)
        sp(context).edit()
            .putStringSet(KEY_IDS, current.toSet())
            .apply()
    }

    fun clear(context: Context) {
        sp(context).edit().clear().apply()
    }
}
