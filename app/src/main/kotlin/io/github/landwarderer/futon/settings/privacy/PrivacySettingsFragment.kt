package io.github.landwarderer.futon.settings.privacy

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import dagger.hilt.android.AndroidEntryPoint
import io.github.landwarderer.futon.R
import io.github.landwarderer.futon.core.prefs.AppSettings
import io.github.landwarderer.futon.core.ui.BasePreferenceFragment

@AndroidEntryPoint
class PrivacySettingsFragment :
    BasePreferenceFragment(R.string.privacy),
    SharedPreferences.OnSharedPreferenceChangeListener {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.pref_privacy)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        settings.subscribe(this)

        findPreference<Preference>(KEY_PRIVACY_POLICY)?.setOnPreferenceClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_POLICY_URL))
            startActivity(intent)
            true
        }
    }

    override fun onDestroyView() {
        settings.unsubscribe(this)
        super.onDestroyView()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            AppSettings.KEY_CRASH_ANALYTICS_ENABLED -> {
                findPreference<SwitchPreferenceCompat>(AppSettings.KEY_CRASH_ANALYTICS_ENABLED)
                    ?.isChecked = settings.isCrashAnalyticsEnabled
            }
        }
    }

    companion object {
        private const val KEY_PRIVACY_POLICY = "privacy_policy"
        private const val PRIVACY_POLICY_URL = "https://tsukiapp.vercel.app/privacy"
    }
}
