package org.qbook.utils

import android.app.Activity
import android.app.Application
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.view.ViewCompat
import androidx.fragment.app.FragmentActivity
import org.qbook.R

/** Process-wide privacy controls shared by every QBooK activity. */
object PrivacySecurityManager {
    private const val LOCK_OVERLAY_TAG = "qbook_privacy_lock_overlay"
    private const val AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_STRONG or
        BiometricManager.Authenticators.DEVICE_CREDENTIAL

    private var startedActivities = 0
    private var authenticated = false
    private var promptShowing = false

    fun install(application: Application) {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) {
                applyWindowSecurity(activity)
            }

            override fun onActivityStarted(activity: Activity) {
                if (startedActivities++ == 0 && Prefs(activity).labsAppLock) {
                    authenticated = false
                }
                applyWindowSecurity(activity)
            }

            override fun onActivityResumed(activity: Activity) {
                applyWindowSecurity(activity)
                if (Prefs(activity).labsAppLock && !isSplash(activity) && activity is FragmentActivity) {
                    requestUnlock(activity)
                }
            }

            override fun onActivityPaused(activity: Activity) = Unit

            override fun onActivityStopped(activity: Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
                if (startedActivities == 0) {
                    authenticated = false
                    promptShowing = false
                }
            }

            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit

            override fun onActivityDestroyed(activity: Activity) {
                removeLockOverlay(activity)
            }
        })
    }

    fun canAuthenticate(activity: Activity): Boolean =
        BiometricManager.from(activity).canAuthenticate(AUTHENTICATORS) ==
            BiometricManager.BIOMETRIC_SUCCESS

    fun onPreferenceChanged(activity: Activity, enabled: Boolean) {
        if (!enabled) {
            authenticated = true
            promptShowing = false
            removeLockOverlay(activity)
        } else {
            authenticated = false
            if (activity is FragmentActivity) requestUnlock(activity)
        }
        applyWindowSecurity(activity, enabledOverride = enabled)
    }

    fun applyWindowSecurity(activity: Activity, enabledOverride: Boolean? = null) {
        val enabled = enabledOverride ?: Prefs(activity).labsFlagSecure
        if (enabled) {
            activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    private fun requestUnlock(activity: FragmentActivity) {
        if (authenticated || promptShowing || activity.isFinishing || activity.isDestroyed) return
        if (!canAuthenticate(activity)) {
            showLockOverlay(activity)
            return
        }

        showLockOverlay(activity)
        promptShowing = true
        val prompt = BiometricPrompt(
            activity,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    authenticated = true
                    promptShowing = false
                    removeLockOverlay(activity)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    authenticated = false
                    promptShowing = false
                    showLockOverlay(activity)
                }

                override fun onAuthenticationFailed() {
                    authenticated = false
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(activity.getString(R.string.app_lock_prompt_title))
            .setSubtitle(activity.getString(R.string.app_lock_prompt_subtitle))
            .setAllowedAuthenticators(AUTHENTICATORS)
            .build()
        try {
            prompt.authenticate(info)
        } catch (_: Exception) {
            promptShowing = false
            showLockOverlay(activity)
        }
    }

    private fun showLockOverlay(activity: Activity) {
        val decor = activity.window.decorView as? ViewGroup ?: return
        if (decor.findViewWithTag<View>(LOCK_OVERLAY_TAG) != null) return

        val overlay = FrameLayout(activity).apply {
            tag = LOCK_OVERLAY_TAG
            isClickable = true
            isFocusable = true
            setBackgroundColor(Color.rgb(18, 19, 23))
            setOnClickListener {
                if (activity is FragmentActivity) requestUnlock(activity)
            }
            ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                view.setPadding(0, 0, 0, 0)
                insets
            }
        }
        val message = TextView(activity).apply {
            text = activity.getString(R.string.app_lock_overlay)
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(40, 24, 40, 24)
        }
        overlay.addView(
            message,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )
        decor.addView(
            overlay,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    private fun removeLockOverlay(activity: Activity) {
        val decor = activity.window.decorView as? ViewGroup ?: return
        decor.findViewWithTag<View>(LOCK_OVERLAY_TAG)?.let { overlay ->
            decor.removeView(overlay)
        }
    }

    private fun isSplash(activity: Activity): Boolean =
        activity.javaClass.name.endsWith(".SplashActivity")
}
