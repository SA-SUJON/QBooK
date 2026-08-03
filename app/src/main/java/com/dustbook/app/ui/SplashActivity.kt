package com.dustbook.app.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
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
