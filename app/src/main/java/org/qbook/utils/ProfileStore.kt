package org.qbook.utils

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import androidx.preference.PreferenceManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Local profile registry. Each profile stores only a WebView cookie header and
 * metadata; passwords are never collected or persisted by QBooK.
 */
object ProfileStore {
    private const val FILE_NAME = "qbook_profiles.json"
    private const val VERSION = 1
    private const val FACEBOOK_COOKIE_URL = "https://www.facebook.com/"
    private const val PROFILE_ID_PREFIX = "profile_"
    const val KIND_PERSONAL = "personal"
    const val KIND_BUSINESS = "business"
    const val KIND_PAGE = "page"

    data class Profile(
        val id: String,
        val name: String,
        val cookies: String,
        val kind: String = KIND_PERSONAL
    ) {
        val isActive: Boolean get() = cookies.isNotBlank()
        val initial: String get() = name.trim().firstOrNull()?.uppercase() ?: "?"
    }

    data class State(
        val profiles: List<Profile>,
        val defaultProfileId: String,
        val activeProfileId: String
    ) {
        val defaultProfile: Profile get() = profiles.first { it.id == defaultProfileId }
        val activeProfile: Profile get() = profiles.first { it.id == activeProfileId }
    }

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    @Synchronized
    fun load(context: Context): State {
        val f = file(context)
        val state = if (f.exists()) {
            runCatching { decode(f.readText()) }.getOrNull()
        } else null
        if (state != null && state.profiles.isNotEmpty()) return normalize(state)

        val seeded = State(
            profiles = listOf(
                Profile(
                    id = "personal",
                    name = "Personal",
                    cookies = currentCookies(),
                    kind = KIND_PERSONAL
                )
            ),
            defaultProfileId = "personal",
            activeProfileId = "personal"
        )
        save(context, seeded)
        return seeded
    }

    @Synchronized
    fun captureCurrentSession(context: Context): State {
        val current = load(context)
        val cookieHeader = currentCookies()
        val activeId = current.activeProfileId.ifBlank { current.defaultProfileId }
        val updated = current.copy(
            profiles = current.profiles.map {
                if (it.id == activeId && cookieHeader.isNotBlank()) it.copy(cookies = cookieHeader) else it
            },
            activeProfileId = activeId
        )
        save(context, updated)
        return updated
    }

    @Synchronized
    fun create(context: Context, name: String, cookies: String = "", kind: String = KIND_PERSONAL): State {
        val current = captureCurrentSession(context)
        val cleanName = name.trim().ifBlank { "New profile" }
        val id = PROFILE_ID_PREFIX + UUID.randomUUID().toString().replace("-", "").take(12)
        val updated = current.copy(
            profiles = current.profiles + Profile(id, cleanName, cookies.trim(), normalizeKind(kind))
        )
        save(context, updated)
        return updated
    }

    @Synchronized
    fun rename(context: Context, id: String, name: String): State {
        val current = load(context)
        val cleanName = name.trim().ifBlank { return current }
        val updated = current.copy(
            profiles = current.profiles.map { if (it.id == id) it.copy(name = cleanName) else it }
        )
        save(context, updated)
        return updated
    }

    @Synchronized
    fun delete(context: Context, id: String): State {
        val current = captureCurrentSession(context)
        if (current.profiles.size <= 1) return current
        val remaining = current.profiles.filterNot { it.id == id }
        val fallback = remaining.first()
        val updated = current.copy(
            profiles = remaining,
            defaultProfileId = if (current.defaultProfileId == id) fallback.id else current.defaultProfileId,
            activeProfileId = if (current.activeProfileId == id) fallback.id else current.activeProfileId
        )
        save(context, updated)
        return updated
    }

    @Synchronized
    fun setDefault(context: Context, id: String): State {
        val current = load(context)
        if (current.profiles.none { it.id == id }) return current
        val updated = current.copy(defaultProfileId = id)
        save(context, updated)
        return updated
    }

    /** Installs a profile's cookies into the shared WebView cookie jar. */
    fun activate(context: Context, id: String, onComplete: (State) -> Unit) {
        val current = captureCurrentSession(context)
        val target = current.profiles.firstOrNull { it.id == id } ?: current.defaultProfile
        clearCookies {
            installCookies(target.cookies)
            val updated = current.copy(activeProfileId = target.id)
            save(context, updated)
            onComplete(updated)
        }
    }

    fun activateDefault(context: Context, onComplete: (State) -> Unit) {
        val state = load(context)
        activate(context, state.defaultProfileId, onComplete)
    }

    fun clearCookies(onComplete: () -> Unit) {
        CookieManager.getInstance().removeAllCookies { onComplete() }
    }

    fun backupJson(context: Context): String {
        val state = captureCurrentSession(context)
        val root = JSONObject()
            .put("format", "QBooK Accounts & Sessions")
            .put("version", VERSION)
            .put("defaultProfileId", state.defaultProfileId)
            .put("activeProfileId", state.activeProfileId)
            .put("profiles", JSONArray().apply {
                state.profiles.forEach { profile ->
                    put(JSONObject()
                        .put("id", profile.id)
                        .put("name", profile.name)
                        .put("cookies", profile.cookies)
                        .put("kind", profile.kind))
                }
            })

        val preferences = PreferenceManager.getDefaultSharedPreferences(context).all
        val settings = JSONObject()
        val filters = JSONObject()
        preferences.forEach { (key, value) ->
            val jsonValue = value.toJsonValue() ?: return@forEach
            settings.put(key, jsonValue)
            if (key.contains("block", ignoreCase = true) ||
                key.contains("hide_", ignoreCase = true) ||
                key.contains("cosmetic", ignoreCase = true)
            ) {
                filters.put(key, jsonValue)
            }
        }
        root.put("settings", settings)
        root.put("filters", filters)
        return root.toString(2)
    }

    /** Restores profiles and preferences from a QBooK JSON backup. */
    @Synchronized
    fun restoreJson(context: Context, json: String): State {
        val root = JSONObject(json)
        val profilesJson = root.optJSONArray("profiles") ?: JSONArray()
        val profiles = buildList {
            for (index in 0 until profilesJson.length()) {
                val item = profilesJson.optJSONObject(index) ?: continue
                val id = item.optString("id").trim()
                val name = item.optString("name").trim()
                if (id.isNotBlank() && name.isNotBlank()) {
                    add(Profile(id, name, item.optString("cookies"), normalizeKind(item.optString("kind"))))
                }
            }
        }.ifEmpty { listOf(Profile("personal", "Personal", "")) }

        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val editor = preferences.edit()
        root.optJSONObject("settings")?.let { settings ->
            settings.keys().forEach { key -> applyTo(editor, key, settings.opt(key)) }
        }
        editor.apply()

        val requestedDefault = root.optString("defaultProfileId")
        val requestedActive = root.optString("activeProfileId")
        val state = normalize(
            State(
                profiles = profiles,
                defaultProfileId = requestedDefault,
                activeProfileId = requestedActive
            )
        )
        save(context, state)
        return state
    }

    fun importCookies(text: String): String {
        val compact = text.trim()
        if (compact.startsWith("{") && compact.contains("cookies", ignoreCase = true)) {
            return runCatching {
                JSONObject(compact).optJSONArray("profiles")
                    ?.optJSONObject(0)?.optString("cookies").orEmpty()
            }.getOrDefault(compact)
        }

        val pairs = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .flatMap { line ->
                if (line.contains('\t')) {
                    val fields = line.split('\t')
                    if (fields.size >= 7 && fields[5].isNotBlank()) {
                        sequenceOf("${fields[5]}=${fields[6]}")
                    } else sequenceOf(line)
                } else {
                    line.split(';').asSequence().map { it.trim() }
                }
            }
            .filter { it.contains('=') }
            .toList()
        return pairs.joinToString("; ")
    }

    private fun normalize(raw: State): State {
        val profiles = raw.profiles.distinctBy { it.id }.ifEmpty {
            listOf(Profile("personal", "Personal", ""))
        }
        val defaultId = profiles.firstOrNull { it.id == raw.defaultProfileId }?.id ?: profiles.first().id
        val activeId = profiles.firstOrNull { it.id == raw.activeProfileId }?.id ?: defaultId
        return State(profiles, defaultId, activeId)
    }

    private fun decode(json: String): State {
        val root = JSONObject(json)
        val array = root.optJSONArray("profiles") ?: JSONArray()
        val profiles = buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(Profile(item.optString("id"), item.optString("name"), item.optString("cookies"), normalizeKind(item.optString("kind"))))
            }
        }
        return State(
            profiles = profiles,
            defaultProfileId = root.optString("defaultProfileId"),
            activeProfileId = root.optString("activeProfileId")
        )
    }

    @Synchronized
    private fun save(context: Context, state: State) {
        val root = JSONObject()
            .put("version", VERSION)
            .put("defaultProfileId", state.defaultProfileId)
            .put("activeProfileId", state.activeProfileId)
            .put("profiles", JSONArray().apply {
                state.profiles.forEach { profile ->
                    put(JSONObject()
                        .put("id", profile.id)
                        .put("name", profile.name)
                        .put("cookies", profile.cookies)
                        .put("kind", profile.kind))
                }
            })
        val destination = file(context)
        val temporary = File(destination.parentFile, "$FILE_NAME.part")
        temporary.writeText(root.toString())
        if (!temporary.renameTo(destination)) temporary.delete()
    }

    /** Stable WebView suffix selected before the first WebView is created. */
    fun storageSuffix(context: Context): String {
        val activeId = runCatching {
            if (!file(context).exists()) "personal"
            else JSONObject(file(context).readText()).optString("activeProfileId")
        }.getOrDefault("personal").ifBlank { "personal" }
        return "profile_" + activeId.replace(Regex("[^A-Za-z0-9_]"), "_").take(48)
    }

    private fun normalizeKind(value: String): String = when (value.lowercase()) {
        KIND_BUSINESS -> KIND_BUSINESS
        KIND_PAGE -> KIND_PAGE
        else -> KIND_PERSONAL
    }

    private fun currentCookies(): String =
        runCatching { CookieManager.getInstance().getCookie(FACEBOOK_COOKIE_URL).orEmpty() }.getOrDefault("")

    private fun installCookies(header: String) {
        if (header.isBlank()) return
        val urls = listOf(
            "https://www.facebook.com/",
            "https://facebook.com/",
            "https://m.facebook.com/",
            "https://messenger.com/"
        )
        header.split(';')
            .map { it.trim() }
            .filter { it.contains('=') }
            .forEach { pair ->
                urls.forEach { url -> CookieManager.getInstance().setCookie(url, "$pair; Path=/") }
            }
        CookieManager.getInstance().flush()
    }

    private fun Any?.toJsonValue(): Any? = when (this) {
        is String, is Boolean, is Int, is Long, is Float, is Double -> this
        is Set<*> -> JSONArray(this.filterIsInstance<String>())
        else -> null
    }

    private fun applyTo(
        editor: android.content.SharedPreferences.Editor,
        key: String,
        value: Any?
    ) {
        when (value) {
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Double -> editor.putFloat(key, value.toFloat())
            is Number -> editor.putFloat(key, value.toFloat())
            is JSONArray -> editor.putStringSet(
                key,
                buildSet { for (i in 0 until value.length()) add(value.optString(i)) }
            )
            is String -> editor.putString(key, value)
        }
    }
}
