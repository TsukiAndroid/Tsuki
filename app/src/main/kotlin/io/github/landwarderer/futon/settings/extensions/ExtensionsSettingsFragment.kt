package io.github.landwarderer.futon.settings.extensions

import android.content.Intent
import android.os.Bundle
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import dagger.hilt.android.AndroidEntryPoint
import io.github.landwarderer.futon.R
import io.github.landwarderer.futon.extensions.ui.ExtensionRepoActivity
import io.github.landwarderer.futon.extensions.ui.ExtensionsActivity

/**
 * Settings fragment for the multi-language extension system.
 *
 * Accessible from Settings → Extensions. Provides quick links to the extension
 * manager and the repo manager without duplicating any state.
 */
@AndroidEntryPoint
class ExtensionsSettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.pref_extensions, rootKey)
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        return when (preference.key) {
            "pref_extensions_manager" -> {
                startActivity(Intent(requireContext(), ExtensionsActivity::class.java))
                true
            }

            "pref_extension_repos" -> {
                startActivity(Intent(requireContext(), ExtensionRepoActivity::class.java))
                true
            }

            else -> super.onPreferenceTreeClick(preference)
        }
    }
}
