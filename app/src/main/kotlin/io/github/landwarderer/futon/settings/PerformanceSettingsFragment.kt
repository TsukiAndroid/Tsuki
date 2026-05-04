package io.github.landwarderer.futon.settings

import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import androidx.preference.Preference
import dagger.hilt.android.AndroidEntryPoint
import io.github.landwarderer.futon.R
import io.github.landwarderer.futon.core.prefs.AppSettings
import io.github.landwarderer.futon.core.ui.BasePreferenceFragment
import io.github.landwarderer.futon.settings.utils.PercentSummaryProvider
import io.github.landwarderer.futon.settings.utils.SliderPreference

@AndroidEntryPoint
class PerformanceSettingsFragment :
    BasePreferenceFragment(R.string.perf_performance),
    SharedPreferences.OnSharedPreferenceChangeListener {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.pref_performance)

        // Show current value as "X fps" for the update-rate slider
        findPreference<SliderPreference>(AppSettings.KEY_BLUR_FPS)?.summaryProvider =
            Preference.SummaryProvider<SliderPreference> { pref ->
                "${pref.value.toInt()} fps"
            }

        // Show current value as a percentage for the capture-resolution slider
        findPreference<SliderPreference>(AppSettings.KEY_BLUR_CAPTURE_QUALITY)?.summaryProvider =
            PercentSummaryProvider()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        settings.subscribe(this)
    }

    override fun onDestroyView() {
        settings.unsubscribe(this)
        super.onDestroyView()
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
        // MainActivity re-applies all blur config on onResume (which fires when
        // the user navigates back from here), so no extra wiring is needed here.
    }
}
