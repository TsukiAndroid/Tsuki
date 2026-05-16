package io.github.landwarderer.futon.browser.webview

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WebViewSettingsManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── General ───────────────────────────────────────────────────────────────

    var isJavaScriptEnabled: Boolean
        get() = prefs.getBoolean(KEY_JS_ENABLED, true)
        set(v) = prefs.edit().putBoolean(KEY_JS_ENABLED, v).apply()

    var isDesktopMode: Boolean
        get() = prefs.getBoolean(KEY_DESKTOP_MODE, false)
        set(v) = prefs.edit().putBoolean(KEY_DESKTOP_MODE, v).apply()

    var userAgentType: WebViewUserAgent
        get() = runCatching {
            WebViewUserAgent.valueOf(prefs.getString(KEY_USER_AGENT_TYPE, null) ?: "")
        }.getOrElse { WebViewUserAgent.DEFAULT_ANDROID }
        set(v) = prefs.edit().putString(KEY_USER_AGENT_TYPE, v.name).apply()

    var customUserAgent: String
        get() = prefs.getString(KEY_CUSTOM_UA, "") ?: ""
        set(v) = prefs.edit().putString(KEY_CUSTOM_UA, v).apply()

    fun resolvedUserAgent(): String? {
        val ua = userAgentType.resolve(customUserAgent)
        return if (isDesktopMode && ua == null) CHROME_DESKTOP_UA else ua
    }

    // ── AI Parser Learning ────────────────────────────────────────────────────

    var isAiParserLearningEnabled: Boolean
        get() = prefs.getBoolean(KEY_AI_LEARNING, true)
        set(v) = prefs.edit().putBoolean(KEY_AI_LEARNING, v).apply()

    var geminiApiKey: String
        get() = prefs.getString(KEY_GEMINI_API_KEY, "") ?: ""
        set(v) = prefs.edit().putString(KEY_GEMINI_API_KEY, v).apply()

    var isLearningBannerVisible: Boolean
        get() = prefs.getBoolean(KEY_LEARNING_BANNER, true)
        set(v) = prefs.edit().putBoolean(KEY_LEARNING_BANNER, v).apply()

    // ── Ad Blocker ────────────────────────────────────────────────────────────

    var isAdBlockEnabled: Boolean
        get() = prefs.getBoolean(KEY_ADBLOCK, true)
        set(v) = prefs.edit().putBoolean(KEY_ADBLOCK, v).apply()

    var customBlockedDomains: Set<String>
        get() = prefs.getStringSet(KEY_CUSTOM_BLOCKED, emptySet()) ?: emptySet()
        set(v) = prefs.edit().putStringSet(KEY_CUSTOM_BLOCKED, v).apply()

    var whitelistedDomains: Set<String>
        get() = prefs.getStringSet(KEY_WHITELISTED, emptySet()) ?: emptySet()
        set(v) = prefs.edit().putStringSet(KEY_WHITELISTED, v).apply()

    var blockedRequestCount: Int
        get() = prefs.getInt(KEY_BLOCKED_COUNT, 0)
        set(v) = prefs.edit().putInt(KEY_BLOCKED_COUNT, v).apply()

    fun incrementBlockedCount() {
        blockedRequestCount++
    }

    fun resetBlockedCount() {
        blockedRequestCount = 0
    }

    // ── Privacy ───────────────────────────────────────────────────────────────

    var isWebViewDohEnabled: Boolean
        get() = prefs.getBoolean(KEY_WEBVIEW_DOH, false)
        set(v) = prefs.edit().putBoolean(KEY_WEBVIEW_DOH, v).apply()

    var webViewDohProvider: String
        get() = prefs.getString(KEY_WEBVIEW_DOH_PROVIDER, "cloudflare") ?: "cloudflare"
        set(v) = prefs.edit().putString(KEY_WEBVIEW_DOH_PROVIDER, v).apply()

    // ── Native Reader Integration ─────────────────────────────────────────────

    var isAutoDetectChapterEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_DETECT_CHAPTER, true)
        set(v) = prefs.edit().putBoolean(KEY_AUTO_DETECT_CHAPTER, v).apply()

    var isAutoDetectDetailEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_DETECT_DETAIL, true)
        set(v) = prefs.edit().putBoolean(KEY_AUTO_DETECT_DETAIL, v).apply()

    var isOpenInReaderPromptEnabled: Boolean
        get() = prefs.getBoolean(KEY_OPEN_IN_READER_PROMPT, true)
        set(v) = prefs.edit().putBoolean(KEY_OPEN_IN_READER_PROMPT, v).apply()

    var isAddToLibraryPromptEnabled: Boolean
        get() = prefs.getBoolean(KEY_ADD_TO_LIBRARY_PROMPT, true)
        set(v) = prefs.edit().putBoolean(KEY_ADD_TO_LIBRARY_PROMPT, v).apply()

    // ── Custom CSS ────────────────────────────────────────────────────────────

    var globalCustomCss: String
        get() = prefs.getString(KEY_GLOBAL_CSS, "") ?: ""
        set(v) = prefs.edit().putString(KEY_GLOBAL_CSS, v).apply()

    fun getSiteCustomCss(domain: String): String =
        prefs.getString("$KEY_SITE_CSS_PREFIX$domain", "") ?: ""

    fun setSiteCustomCss(domain: String, css: String) =
        prefs.edit().putString("$KEY_SITE_CSS_PREFIX$domain", css).apply()

    // ── Auto-scroll ───────────────────────────────────────────────────────────

    var autoScrollSpeed: Int
        get() = prefs.getInt(KEY_AUTOSCROLL_SPEED, 3)
        set(v) = prefs.edit().putInt(KEY_AUTOSCROLL_SPEED, v).apply()

    companion object {
        private const val PREFS_NAME = "tsuki_webview_settings"
        const val KEY_JS_ENABLED = "wv_js"
        const val KEY_DESKTOP_MODE = "wv_desktop"
        const val KEY_USER_AGENT_TYPE = "wv_ua_type"
        const val KEY_CUSTOM_UA = "wv_custom_ua"
        const val KEY_AI_LEARNING = "wv_ai_learning"
        const val KEY_GEMINI_API_KEY = "wv_gemini_key"
        const val KEY_LEARNING_BANNER = "wv_learning_banner"
        const val KEY_ADBLOCK = "wv_adblock"
        const val KEY_CUSTOM_BLOCKED = "wv_blocked_domains"
        const val KEY_WHITELISTED = "wv_whitelisted_domains"
        const val KEY_BLOCKED_COUNT = "wv_blocked_count"
        const val KEY_WEBVIEW_DOH = "wv_doh"
        const val KEY_WEBVIEW_DOH_PROVIDER = "wv_doh_provider"
        const val KEY_AUTO_DETECT_CHAPTER = "wv_auto_chapter"
        const val KEY_AUTO_DETECT_DETAIL = "wv_auto_detail"
        const val KEY_OPEN_IN_READER_PROMPT = "wv_reader_prompt"
        const val KEY_ADD_TO_LIBRARY_PROMPT = "wv_library_prompt"
        const val KEY_GLOBAL_CSS = "wv_global_css"
        const val KEY_SITE_CSS_PREFIX = "wv_site_css_"
        const val KEY_AUTOSCROLL_SPEED = "wv_autoscroll_speed"

        private const val CHROME_DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Safari/537.36"
    }
}
