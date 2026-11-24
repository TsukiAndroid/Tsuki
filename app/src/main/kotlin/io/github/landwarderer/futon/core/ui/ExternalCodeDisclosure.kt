package io.github.landwarderer.futon.core.ui

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.landwarderer.futon.BuildConfig
import io.github.landwarderer.futon.R

/**
 * Explains the risks of installing executable code supplied outside the app.
 *
 * Consent is requested at the point of each installation/import so declining
 * remains a simple, meaningful choice and no blanket permission is inferred.
 */
object ExternalCodeDisclosure {

    fun showConsent(context: Context, onContinue: () -> Unit) {
        val message = if (BuildConfig.IS_FDROID) {
            R.string.external_code_consent_fdroid_message
        } else {
            R.string.external_code_consent_message
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.external_code_consent_title)
            .setMessage(message)
            .setPositiveButton(R.string.external_code_consent_continue) { _, _ -> onContinue() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun showInfo(context: Context) {
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.tsuki_external_code_info_title)
            .setMessage(R.string.tsuki_external_code_info_message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
