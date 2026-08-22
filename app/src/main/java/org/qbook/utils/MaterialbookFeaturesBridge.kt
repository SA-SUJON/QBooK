package org.qbook.utils

import android.webkit.JavascriptInterface

/** Exposes live Materialbook-style feature preferences to page JavaScript. */
class MaterialbookFeaturesBridge(
    private val stickyNavbar: () -> Boolean,
    private val inPageSettings: () -> Boolean,
    private val selectableCaptions: () -> Boolean
) {
    @JavascriptInterface
    @Suppress("unused")
    fun isStickyNavbarEnabled(): Boolean = stickyNavbar()

    @JavascriptInterface
    @Suppress("unused")
    fun isInPageSettingsEnabled(): Boolean = inPageSettings()

    @JavascriptInterface
    @Suppress("unused")
    fun isSelectableCaptionsEnabled(): Boolean = selectableCaptions()
}
