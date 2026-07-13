package io.github.landwarderer.futon.settings.privacy

import android.app.Dialog
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import io.github.landwarderer.futon.R
import io.github.landwarderer.futon.core.prefs.AppSettings
import javax.inject.Inject

/**
 * One-shot opt-in dialog shown on the very first cold launch.
 *
 * Privacy rules:
 * - Sentry is NEVER initialized until the user explicitly taps "Allow".
 * - The dialog is shown exactly once; both Allow and Decline record
 *   [AppSettings.isCrashConsentShown] = true so it never appears again.
 * - Declining leaves [AppSettings.isCrashAnalyticsEnabled] at its default (false).
 * - Opting in sets [AppSettings.isCrashAnalyticsEnabled] = true; Sentry
 *   initializes on the next cold start (see BaseApp.initializeSentry).
 */
@AndroidEntryPoint
class CrashReportingConsentDialog : DialogFragment() {

    @Inject
    lateinit var settings: AppSettings

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.crash_consent_title)
            .setMessage(R.string.crash_consent_message)
            .setPositiveButton(R.string.crash_consent_allow) { _, _ ->
                settings.isCrashAnalyticsEnabled = true
                settings.isCrashConsentShown = true
            }
            .setNegativeButton(R.string.crash_consent_decline) { _, _ ->
                settings.isCrashConsentShown = true
            }
            .create()
    }

    companion object {
        private const val TAG = "CrashReportingConsentDialog"

        fun show(fm: FragmentManager) {
            if (fm.findFragmentByTag(TAG) == null) {
                CrashReportingConsentDialog().show(fm, TAG)
            }
        }
    }
}
