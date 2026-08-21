package org.qbook.ui

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import org.qbook.R

/** Reference-style developer profile popup for Architect Credentials. */
object ArchitectCredentialsDialog {

    private const val EMAIL = "imsamsularefinsujon@gmail.com"
    private const val GITHUB_URL = "https://github.com/SA-SUJON/QBooK"
    private const val GITHUB_PACKAGE = "com.github.android"

    fun show(context: Context) {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_architect_credentials)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setCancelable(false)

        dialog.findViewById<TextView>(R.id.architect_acknowledge).setOnClickListener {
            dialog.dismiss()
        }
        dialog.findViewById<ImageButton>(R.id.architect_email).setOnClickListener {
            openEmail(context)
        }
        dialog.findViewById<ImageButton>(R.id.architect_github).setOnClickListener {
            openGitHub(context)
        }

        dialog.setOnShowListener {
            dialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                attributes = attributes.apply { dimAmount = 0.58f }
                val screenWidth = context.resources.displayMetrics.widthPixels
                val horizontalMargin = dp(context, 32f)
                val maxWidth = dp(context, 460f)
                setLayout(minOf(screenWidth - horizontalMargin, maxWidth), ViewGroup.LayoutParams.WRAP_CONTENT)
            }
        }
        dialog.show()
    }

    private fun openEmail(context: Context) {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$EMAIL")).apply {
            putExtra(Intent.EXTRA_SUBJECT, "QBooK Architect Credentials")
        }
        runCatching {
            context.startActivity(intent)
        }.onFailure {
            Toast.makeText(context, R.string.architect_email_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openGitHub(context: Context) {
        val appIntent = Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL)).apply {
            setPackage(GITHUB_PACKAGE)
        }
        val intent = if (appIntent.resolveActivity(context.packageManager) != null) {
            appIntent
        } else {
            Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL))
        }
        runCatching {
            context.startActivity(intent)
        }.onFailure {
            Toast.makeText(context, R.string.architect_github_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    private fun dp(context: Context, value: Float): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()
}
