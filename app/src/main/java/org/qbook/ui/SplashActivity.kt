package org.qbook.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import org.qbook.R
import org.qbook.utils.Prefs

@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // The launcher starts this target through one of the icon aliases. The
        // persisted index is the same source of truth used to enable that alias;
        // apply its splash theme before AndroidX reads the starting-window icon.
        setTheme(
            when (Prefs(this).appIcon) {
                1 -> R.style.Theme_QBooK_Splash_Icon1
                2 -> R.style.Theme_QBooK_Splash_Icon2
                3 -> R.style.Theme_QBooK_Splash_Icon3
                4 -> R.style.Theme_QBooK_Splash_Icon4
                5 -> R.style.Theme_QBooK_Splash_Icon5
                6 -> R.style.Theme_QBooK_Splash_Icon6
                7 -> R.style.Theme_QBooK_Splash_Icon7
                8 -> R.style.Theme_QBooK_Splash_Icon8
                9 -> R.style.Theme_QBooK_Splash_Icon9
                10 -> R.style.Theme_QBooK_Splash_Icon10
                11 -> R.style.Theme_QBooK_Splash_Icon11
                12 -> R.style.Theme_QBooK_Splash_Icon12
                13 -> R.style.Theme_QBooK_Splash_Icon13
                14 -> R.style.Theme_QBooK_Splash_Icon14
                15 -> R.style.Theme_QBooK_Splash_Icon15
                else -> R.style.Theme_QBooK_Splash_Icon0
            }
        )
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)

        // Hold the splash for one frame so there is no black flash between
        // the splash and MainActivity's first draw.
        var ready = false
        splash.setKeepOnScreenCondition { !ready }

        window.decorView.post {
            ready = true
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    intent?.data?.let { data = it; action = Intent.ACTION_VIEW }
                }
            )
            overridePendingTransition(0, 0)
            finish()
        }
    }
}
