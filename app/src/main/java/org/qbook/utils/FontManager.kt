package org.qbook.utils

import android.content.Context
import android.net.Uri
import android.graphics.Typeface
import android.webkit.WebResourceResponse
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.Locale

/**
 * Owns QBooK's typography assets and the CSS bridge used by the Facebook
 * WebView. Custom fonts are copied into app-private storage immediately, so
 * the original document provider file can be removed without affecting the
 * selected font.
 */
object FontManager {
    const val SYSTEM_VALUE = "system"
    const val CUSTOM_VALUE = "custom"
    private const val CUSTOM_FILE = "custom-selected-font.bin"
    private const val CUSTOM_DIR = "fonts"
    private const val CUSTOM_FAMILY = "QBooK Custom Font"
    private const val URL_PREFIX = "https://appassets.androidplatform.net/qbook-fonts/"
    private const val MAX_CUSTOM_FONT_BYTES = 32L * 1024L * 1024L

    private fun customDir(context: Context): File =
        File(context.applicationContext.filesDir, CUSTOM_DIR)

    private fun customFile(context: Context): File =
        File(customDir(context), CUSTOM_FILE)

    fun hasCustomFont(context: Context): Boolean = customFile(context).isFile &&
        customFile(context).length() > 0L

    fun customFontDisplayName(context: Context): String? {
        val prefs = Prefs(context)
        return prefs.customFontName.takeIf { hasCustomFont(context) }
    }

    /** Copies a user-selected font into files/fonts and makes it the active font. */
    fun importCustomFont(context: Context, uri: Uri): String {
        val appContext = context.applicationContext
        val resolver = appContext.contentResolver
        val size = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
            ?: -1L
        if (size > MAX_CUSTOM_FONT_BYTES) {
            throw IllegalArgumentException("Font file is larger than 32 MB")
        }
        val destinationDir = customDir(appContext)
        if (!destinationDir.exists() && !destinationDir.mkdirs()) {
            throw IllegalStateException("Unable to create private font storage")
        }
        val destination = customFile(appContext)
        val temporary = File(destinationDir, "$CUSTOM_FILE.tmp")
        resolver.openInputStream(uri)?.use { input ->
            temporary.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_CUSTOM_FONT_BYTES) {
                        throw IllegalArgumentException("Font file is larger than 32 MB")
                    }
                    output.write(buffer, 0, read)
                }
            }
        } ?: throw IllegalArgumentException("Unable to read the selected font file")
        destination.delete()
        if (!temporary.renameTo(destination)) {
            temporary.delete()
            throw IllegalStateException("Unable to save the selected font")
        }
        val displayName = displayNameFor(context, uri)
        val mime = resolver.getType(uri)?.lowercase(Locale.ROOT)
            ?.takeIf { it in setOf("font/ttf", "font/otf", "font/woff", "font/woff2") }
            ?: mimeFromName(uri.lastPathSegment ?: displayName)
        Prefs(appContext).apply {
            customFontName = displayName
            customFontMime = mime
            fontFamily = CUSTOM_VALUE
        }
        return displayName
    }

    fun clearCustomFont(context: Context) {
        customFile(context).delete()
        Prefs(context).customFontName = ""
        if (Prefs(context).fontFamily == CUSTOM_VALUE) Prefs(context).fontFamily = SYSTEM_VALUE
    }

    /** Resolve the active font for native Android TextViews. */
    fun nativeTypeface(context: Context, family: String): Typeface? {
        return when {
            family == CUSTOM_VALUE && hasCustomFont(context) ->
                runCatching { Typeface.createFromFile(customFile(context)) }.getOrNull()
            family == SYSTEM_VALUE -> null
            else -> PredefinedFonts.all.firstOrNull { it.asset == family }?.let { definition ->
                runCatching {
                    Typeface.createFromAsset(context.applicationContext.assets, "fonts-ttf/${definition.asset.removeSuffix(".woff2")}.ttf")
                }.getOrNull()
            }
        }
    }

    /** Returns a local response for the URL emitted by [cssScript], or null. */
    fun intercept(context: Context, url: String): WebResourceResponse? {
        if (!url.startsWith(URL_PREFIX)) return null
        val relative = url.removePrefix(URL_PREFIX)
        val stream: InputStream = when {
            relative == "custom" && hasCustomFont(context) -> FileInputStream(customFile(context))
            relative.startsWith("predefined/") -> {
                val asset = relative.removePrefix("predefined/")
                if (!PredefinedFonts.all.any { it.asset == asset }) return null
                context.applicationContext.assets.open("fonts/$asset")
            }
            else -> return null
        }
        val mime = if (relative == "custom") Prefs(context).customFontMime else "font/woff2"
        return WebResourceResponse(
            mime,
            "identity",
            200,
            "OK",
            mapOf(
                "Access-Control-Allow-Origin" to "*",
                "Cache-Control" to "public, max-age=31536000",
                "Access-Control-Allow-Headers" to "Origin, Accept, Content-Type"
            ),
            stream
        )
    }

    /** CSS is intentionally broad: this is an app-level Facebook/QBooK override. */
    fun cssScript(context: Context): String {
        val prefs = Prefs(context)
        val definition = PredefinedFonts.all.firstOrNull { it.asset == prefs.fontFamily }
        val isCustom = prefs.fontFamily == CUSTOM_VALUE && hasCustomFont(context)
        val fontUrl = when {
            isCustom -> URL_PREFIX + "custom"
            definition != null -> URL_PREFIX + "predefined/" + definition.asset
            else -> null
        } ?: return "(function(){var s=document.getElementById('qbook-font-override');if(s)s.remove();})();"
        val cssFamily = "'$CUSTOM_FAMILY'"
        val format = if (isCustom) cssFormat(prefs.customFontMime) else "woff2"
        val css = "@font-face{font-family:$cssFamily;src:url(\"$fontUrl\") format(\"$format\");font-display:swap;}" +
            "html,body,body *:not(svg):not(path):not([role=img]){" +
            "font-family:$cssFamily,sans-serif !important;}"
        return "(function(){var id='qbook-font-override';var old=document.getElementById(id);" +
            "if(old)old.remove();var s=document.createElement('style');s.id=id;" +
            "s.textContent=${jsQuote(css)};" +
            "(document.head||document.documentElement).appendChild(s);})();"
    }

    private fun cssFormat(mime: String): String = when (mime) {
        "font/otf" -> "opentype"
        "font/woff" -> "woff"
        "font/woff2" -> "woff2"
        else -> "truetype"
    }

    private fun mimeFromName(name: String): String = when {
        name.endsWith(".otf", true) -> "font/otf"
        name.endsWith(".woff2", true) -> "font/woff2"
        name.endsWith(".woff", true) -> "font/woff"
        else -> "font/ttf"
    }

    private fun jsQuote(value: String): String {
        return "\"" + value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r") + "\""
    }

    private fun displayNameFor(context: Context, uri: Uri): String {
        val resolver = context.contentResolver
        val queried = runCatching {
            resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
        }.getOrNull()
        return (queried ?: uri.lastPathSegment ?: "Custom font")
            .substringAfterLast('/')
            .substringBeforeLast('.', missingDelimiterValue = "Custom font")
            .trim()
            .takeIf { it.isNotBlank() }
            ?: "Custom font"
    }
}
