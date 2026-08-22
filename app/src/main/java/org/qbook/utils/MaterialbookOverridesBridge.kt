package org.qbook.utils

import android.webkit.JavascriptInterface

/** Live state bridge for opt-in Materialbook visual overrides. */
class MaterialbookOverridesBridge(
    private val desktopMode: () -> Boolean,
    private val desktopCleanup: () -> Boolean,
    private val transparentProgress: () -> Boolean,
    private val greyTap: () -> Boolean
) {
    @JavascriptInterface
    @Suppress("unused")
    fun isDesktopModeEnabled(): Boolean = desktopMode()

    @JavascriptInterface
    @Suppress("unused")
    fun isDesktopCleanupEnabled(): Boolean = desktopCleanup()

    @JavascriptInterface
    @Suppress("unused")
    fun isTransparentProgressEnabled(): Boolean = transparentProgress()

    @JavascriptInterface
    @Suppress("unused")
    fun isGreyTapEnabled(): Boolean = greyTap()
}
