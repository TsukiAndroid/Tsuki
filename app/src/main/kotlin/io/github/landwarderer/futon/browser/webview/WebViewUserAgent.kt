package io.github.landwarderer.futon.browser.webview

import android.os.Build
import android.webkit.WebSettings

enum class WebViewUserAgent(val label: String) {
    DEFAULT_ANDROID("Default Android"),
    CHROME_DESKTOP("Chrome Desktop"),
    FIREFOX_DESKTOP("Firefox Desktop"),
    SAFARI_IOS("Safari iOS"),
    CUSTOM("Custom"),
    ;

    fun resolve(customUa: String?): String? = when (this) {
        DEFAULT_ANDROID -> null
        CHROME_DESKTOP -> "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Safari/537.36"
        FIREFOX_DESKTOP -> "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:126.0) " +
            "Gecko/20100101 Firefox/126.0"
        SAFARI_IOS -> "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) " +
            "AppleWebKit/605.1.15 (KHTML, like Gecko) " +
            "Version/17.5 Mobile/15E148 Safari/604.1"
        CUSTOM -> customUa?.takeIf { it.isNotBlank() }
    }
}
