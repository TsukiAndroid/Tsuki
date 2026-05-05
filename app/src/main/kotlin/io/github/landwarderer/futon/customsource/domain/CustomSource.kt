package io.github.landwarderer.futon.customsource.domain

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Represents a user-defined manga source.
 * Supports:
 *  - MangaDex-compatible REST APIs (type = MANGADEX_COMPATIBLE)
 *  - WordPress Madara theme sites (type = MADARA) — auto-parsed like built-in sources
 *  - Generic websites opened in a WebView browser (type = WEBVIEW)
 */
@Parcelize
data class CustomSource(
    val id: Long,
    val name: String,
    val baseUrl: String,
    val type: CustomSourceType,
    val iconUrl: String? = null,
    val description: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
) : Parcelable {

    val displayName: String
        get() = name.ifBlank { baseUrl }

    val cleanBaseUrl: String
        get() = baseUrl.trimEnd('/')
}

enum class CustomSourceType(val label: String) {
    /** Sites with a MangaDex-compatible REST API */
    MANGADEX_COMPATIBLE("MangaDex-compatible"),

    /**
     * Sites built on the WordPress Madara manga theme (the most common manga CMS).
     * Fully auto-parsed — shows manga list, chapters and pages inside the app just
     * like any built-in source, with no manual selector configuration required.
     */
    MADARA("WordPress Madara (Auto)"),

    /** Any website opened inside a WebView — user navigates manually */
    WEBVIEW("Web Browser"),
}
