package io.github.landwarderer.futon.customsource.domain

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Represents a user-defined manga source.
 * Supports multiple CMS/theme types — URLs are auto-parsed like built-in sources.
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
    MANGADEX_COMPATIBLE("MangaDex-compatible API"),

    /**
     * Sites built on the WordPress Madara manga theme — the most common manga CMS.
     * Powers MangaKakalot clones, ReadManga, ManhuaScan, and hundreds more.
     */
    MADARA("WordPress Madara (Auto)"),

    /**
     * Sites built on the WordPress MangaThemesia theme — the most popular *active*
     * WP manga theme. Powers Reaper Scans, Asura Scans, Luminous Scans, Flame Scans, etc.
     */
    MANGATHEMESIA("WordPress MangaThemesia (Auto)"),

    /**
     * Sites built on the WordPress MangaStream / WPMangaStream theme.
     * Widely used for manhwa/manhua: Toonily, Manhwa18, Komikindo, etc.
     */
    MANGASTREAM("WordPress MangaStream (Auto)"),

    /**
     * Sites built on the Genkan open-source scanlation CMS.
     * Used by Leviatan Scans, Hatigarm Scans, and other scanlation groups.
     */
    GENKAN("Genkan / Scanlator CMS"),

    /**
     * Sites built on FoolSlide2 — a popular open-source scanlation CMS.
     * Used by Fallen Angels Scans, Helvetica Scans, and many more.
     */
    FOOLSLIDE2("FoolSlide2 Scanlation CMS"),

    /**
     * Sites using the MangaKakalot / Manganelo / Chapmanganelo layout.
     * A widely cloned custom PHP CMS powering dozens of mirror sites.
     */
    MANGANELO("MangaKakalot / Manganelo Style"),

    /**
     * Sites exposing a clean JSON REST API in the Zeroscans / ComicK style.
     * Includes api.zeroscans.com, api.comick.io-compatible hosts.
     */
    ZEROSCANS_API("Zeroscans / JSON API"),

    /**
     * Sites using the MangaDNA / LHTranslation / ReadComicOnline PHP CMS layout.
     * A widely reused PHP template with a common reading + chapter list structure.
     */
    LHTRANSLATION("MangaDNA / LHTranslation Style"),

    /** Any website opened inside a WebView — user navigates manually */
    WEBVIEW("Web Browser"),
}
