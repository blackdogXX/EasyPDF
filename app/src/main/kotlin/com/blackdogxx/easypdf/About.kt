package com.blackdogxx.easypdf

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder

private const val AUTHOR_EMAIL = "ivan.a@iipa-constructing.org"

/** Окно «О программе»: версия, дата сборки и контакты автора. */
fun showAbout(activity: Activity) {
    val text = activity.getString(
        R.string.about_text, BuildConfig.VERSION_NAME, BuildConfig.BUILD_DATE, AUTHOR_EMAIL
    )
    MaterialAlertDialogBuilder(activity)
        .setTitle(R.string.app_name)
        .setMessage(text)
        .setPositiveButton(R.string.close, null)
        .setNeutralButton(R.string.write_email) { _, _ ->
            try {
                activity.startActivity(
                    Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$AUTHOR_EMAIL"))
                        .putExtra(Intent.EXTRA_SUBJECT, "EasyPDF ${BuildConfig.VERSION_NAME}")
                )
            } catch (_: Exception) {
                Toast.makeText(activity, AUTHOR_EMAIL, Toast.LENGTH_LONG).show()
            }
        }
        .show()
}
