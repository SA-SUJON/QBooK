package org.qbook.utils

import android.webkit.JavascriptInterface

/** Small WebView bridge for the optional in-page settings shortcut. */
class SettingsBridge(private val onToggle: () -> Unit) {
    @JavascriptInterface
    @Suppress("unused")
    fun onSettingsToggle() {
        onToggle()
    }
}
