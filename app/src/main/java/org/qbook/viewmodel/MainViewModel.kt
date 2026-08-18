package org.qbook.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

/**
 * Transient UI state only. Navigation is NOT driven from LiveData - the old
 * design caused an infinite reload loop whenever Facebook redirected.
 */
class MainViewModel : ViewModel() {

    companion object {
        /** Set by SettingsActivity, consumed by MainActivity.onResume. */
        @Volatile
        var pendingSettingsChange: Boolean = false

        /**
         * True when the change requires a full page reload (user agent, ad
         * blocker toggle). Cosmetic-only changes are applied live instead, so
         * the user does not lose their scroll position.
         */
        @Volatile
        var pendingReload: Boolean = false

        /** Set by SettingsActivity when the user taps Download. */
        @Volatile
        var pendingUpdateCheck: Boolean = false
    }

    private val _progress = MutableLiveData(0)
    val progress: LiveData<Int> = _progress

    private val _blockedCount = MutableLiveData(0)
    val blockedCount: LiveData<Int> = _blockedCount

    var settingsDirty: Boolean
        get() = pendingSettingsChange
        set(v) { pendingSettingsChange = v }

    var needsReload: Boolean
        get() = pendingReload
        set(v) { pendingReload = v }

    fun setProgress(value: Int) {
        if (_progress.value != value) _progress.value = value
    }

    /** Called from the WebView worker thread. */
    fun incrementBlocked() {
        _blockedCount.postValue((_blockedCount.value ?: 0) + 1)
    }
}
