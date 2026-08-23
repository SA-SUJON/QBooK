package org.qbook.utils

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Local bookmark store for the visible Facebook card captured by MainActivity.
 * Raw markup is retained so a bookmark remains useful even when the live page
 * or network is unavailable.
 */
object BookmarkStore {
    private const val FILE_NAME = "qbook_bookmarks.json"
    private const val VERSION = 1

    data class Bookmark(
        val id: String,
        val title: String,
        val url: String,
        val html: String,
        val mediaUrls: List<String>,
        val createdAt: Long
    )

    @Synchronized
    fun list(context: Context): List<Bookmark> = runCatching {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return emptyList()
        val root = JSONObject(file.readText())
        val items = root.optJSONArray("items") ?: return emptyList()
        buildList {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val id = item.optString("id").trim()
                val html = item.optString("html")
                if (id.isBlank() || html.isBlank()) continue
                val media = item.optJSONArray("mediaUrls")?.let { array ->
                    buildList { for (i in 0 until array.length()) add(array.optString(i)) }
                } ?: emptyList()
                add(Bookmark(
                    id = id,
                    title = item.optString("title").ifBlank { "Saved Facebook post" },
                    url = item.optString("url"),
                    html = html,
                    mediaUrls = media,
                    createdAt = item.optLong("createdAt", 0L)
                ))
            }
        }.sortedByDescending { it.createdAt }
    }.getOrDefault(emptyList())

    @Synchronized
    fun upsert(context: Context, bookmark: Bookmark) {
        val updated = list(context).filterNot { it.id == bookmark.id } + bookmark
        save(context, updated.sortedByDescending { it.createdAt })
    }

    @Synchronized
    fun delete(context: Context, id: String) {
        save(context, list(context).filterNot { it.id == id })
    }

    private fun save(context: Context, items: List<Bookmark>) {
        val root = JSONObject().put("version", VERSION).put("items", JSONArray().apply {
            items.forEach { item ->
                put(JSONObject()
                    .put("id", item.id)
                    .put("title", item.title)
                    .put("url", item.url)
                    .put("html", item.html)
                    .put("mediaUrls", JSONArray(item.mediaUrls))
                    .put("createdAt", item.createdAt))
            }
        })
        val destination = File(context.filesDir, FILE_NAME)
        val temporary = File(context.filesDir, "$FILE_NAME.part")
        temporary.writeText(root.toString())
        if (!temporary.renameTo(destination)) temporary.delete()
    }
}
