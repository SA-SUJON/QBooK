package org.qbook.utils

import android.webkit.JavascriptInterface

/** Exposes the already-resolved app theme colors to injected page JavaScript. */
class QBookMaterialYouBridge(
    private val primary: Int,
    private val onPrimary: Int,
    private val extended: () -> Boolean = { false }
) {
    @JavascriptInterface
    fun getMaterialYouPrimaryRgb(): String = colorToJson(primary)

    @JavascriptInterface
    fun getMaterialYouOnPrimaryRgb(): String = colorToJson(onPrimary)

    @JavascriptInterface
    fun getMaterialYouPrimaryRgbString(): String = colorToCss(primary)

    @JavascriptInterface
    fun getMaterialYouOnPrimaryRgbString(): String = colorToCss(onPrimary)

    @JavascriptInterface
    fun isExtendedMaterialYouEnabled(): Boolean = extended()

    private fun colorToJson(color: Int): String {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return "{\"r\":$r,\"g\":$g,\"b\":$b}"
    }

    private fun colorToCss(color: Int): String {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return "rgb($r, $g, $b)"
    }
}
