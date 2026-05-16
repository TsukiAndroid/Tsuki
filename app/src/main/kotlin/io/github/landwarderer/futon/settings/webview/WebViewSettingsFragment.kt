package io.github.landwarderer.futon.settings.webview

import android.os.Bundle
import android.text.InputType
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import dagger.hilt.android.AndroidEntryPoint
import io.github.landwarderer.futon.R
import io.github.landwarderer.futon.browser.webview.WebViewSettingsManager
import io.github.landwarderer.futon.core.ui.BasePreferenceFragment
import javax.inject.Inject

@AndroidEntryPoint
class WebViewSettingsFragment : BasePreferenceFragment(R.string.webview_settings) {

    @Inject
    lateinit var webViewSettings: WebViewSettingsManager

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.pref_webview)
        bindPreferences()
    }

    private fun bindPreferences() {
        // ── General ───────────────────────────────────────────────────────────
        bindSwitch(WebViewSettingsManager.KEY_JS_ENABLED) { webViewSettings.isJavaScriptEnabled = it }
        bindSwitch(WebViewSettingsManager.KEY_DESKTOP_MODE) { webViewSettings.isDesktopMode = it }

        // ── AI Parser Learning ────────────────────────────────────────────────
        bindSwitch(WebViewSettingsManager.KEY_AI_LEARNING) { webViewSettings.isAiParserLearningEnabled = it }
        bindSwitch(WebViewSettingsManager.KEY_LEARNING_BANNER) { webViewSettings.isLearningBannerVisible = it }

        // Gemini API Key
        findPreference<EditTextPreference>(WebViewSettingsManager.KEY_GEMINI_API_KEY)?.apply {
            setOnBindEditTextListener { editText ->
                editText.inputType =
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            text = webViewSettings.geminiApiKey
            setOnPreferenceChangeListener { _, newValue ->
                webViewSettings.geminiApiKey = newValue.toString()
                summary = if (newValue.toString().isNotBlank())
                    getString(R.string.webview_gemini_key_set)
                else
                    getString(R.string.webview_gemini_key_hint)
                true
            }
            summary = if (webViewSettings.geminiApiKey.isNotBlank())
                getString(R.string.webview_gemini_key_set)
            else
                getString(R.string.webview_gemini_key_hint)
        }

        // Clear learning cache
        findPreference<Preference>("wv_clear_learning_cache")?.setOnPreferenceClickListener {
            Toast.makeText(requireContext(), R.string.webview_learning_cache_cleared, Toast.LENGTH_SHORT).show()
            true
        }

        // ── Ad Blocker ────────────────────────────────────────────────────────
        bindSwitch(WebViewSettingsManager.KEY_ADBLOCK) { webViewSettings.isAdBlockEnabled = it }

        findPreference<Preference>("wv_blocked_count")?.apply {
            summary = getString(R.string.webview_blocked_count_d, webViewSettings.blockedRequestCount)
        }

        findPreference<Preference>("wv_reset_blocked_count")?.setOnPreferenceClickListener {
            webViewSettings.resetBlockedCount()
            findPreference<Preference>("wv_blocked_count")?.summary =
                getString(R.string.webview_blocked_count_d, 0)
            true
        }

        // ── Privacy ───────────────────────────────────────────────────────────
        bindSwitch(WebViewSettingsManager.KEY_WEBVIEW_DOH) { webViewSettings.isWebViewDohEnabled = it }

        // ── Reader Integration ────────────────────────────────────────────────
        bindSwitch(WebViewSettingsManager.KEY_AUTO_DETECT_CHAPTER) { webViewSettings.isAutoDetectChapterEnabled = it }
        bindSwitch(WebViewSettingsManager.KEY_AUTO_DETECT_DETAIL) { webViewSettings.isAutoDetectDetailEnabled = it }
        bindSwitch(WebViewSettingsManager.KEY_OPEN_IN_READER_PROMPT) { webViewSettings.isOpenInReaderPromptEnabled = it }
        bindSwitch(WebViewSettingsManager.KEY_ADD_TO_LIBRARY_PROMPT) { webViewSettings.isAddToLibraryPromptEnabled = it }
    }

    private fun bindSwitch(key: String, setter: (Boolean) -> Unit) {
        findPreference<SwitchPreferenceCompat>(key)?.setOnPreferenceChangeListener { _, newValue ->
            setter(newValue as Boolean)
            true
        }
    }
}
