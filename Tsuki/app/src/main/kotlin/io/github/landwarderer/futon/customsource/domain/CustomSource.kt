package io.github.landwarderer.futon.customsource.domain

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Represents a user-defined manga source.
 * Supports:
 *  - MangaDex-compatible REST APIs (type = MANGADEX_COMPATIBLE)
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

    /** Any website opened inside a WebView — user navigates manually */
    WEBVIEW("Web Browser"),
}
