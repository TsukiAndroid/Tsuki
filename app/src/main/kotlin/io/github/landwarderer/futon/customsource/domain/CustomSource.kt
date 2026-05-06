package io.github.landwarderer.futon.customsource.domain

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Represents a user-defined manga source.
 *
 * Every type except WEBVIEW is fully auto-parsed — manga list, chapters and
 * pages all work inside the app just like built-in sources.
 * All types also participate in the CMS auto-detection feature.
 */
@Parcelize
data class CustomSource(
    val id: Long,
    val name: String,
    val baseUrl: String,
    val type: CustomSourceType,
    val iconUrl: String? = null,
    val description: String? = null,
    /**
     * When [type] == [CustomSourceType.KOTATSU_PARSER], this holds the
     * [MangaParserSource.name] of the matched built-in parser so the
     * repository factory can look it up and route to [ParserMangaRepository].
     */
    val parserSourceName: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    /**
     * Whether this source is active. Disabled sources are shown in the
     * management list but skipped everywhere else (explore, search, etc.)
     * so the user can temporarily pause a source without losing its config.
     */
    val isEnabled: Boolean = true,
) : Parcelable {
    val displayName: String get() = name.ifBlank { baseUrl }
    val cleanBaseUrl: String get() = baseUrl.trimEnd('/')
}

enum class CustomSourceType(val label: String) {

    /** Sites with a MangaDex-compatible REST API (e.g. MangaDex itself) */
    MANGADEX_COMPATIBLE("MangaDex-compatible API"),

    /** WordPress Madara theme — MangaKakalot clones, ReadManga, ManhuaScan, etc. */
    MADARA("WordPress Madara"),

    /** WordPress MangaThemesia — Reaper Scans, Asura Scans, Luminous Scans, Flame Scans, etc. */
    MANGATHEMESIA("WordPress MangaThemesia"),

    /** WordPress MangaStream / WPMangaStream — Toonily, Manhwa18, Komikindo, etc. */
    MANGASTREAM("WordPress MangaStream"),

    /** Genkan open-source scanlation CMS — Leviatan Scans, Hatigarm Scans, etc. */
    GENKAN("Genkan Scanlation CMS"),

    /** FoolSlide2 open-source scanlation CMS — Fallen Angels Scans, Helvetica Scans, etc. */
    FOOLSLIDE2("FoolSlide2 Scanlation CMS"),

    /** MangaKakalot / Manganelo / Chapmanganelo style — dozens of popular mirror sites */
    MANGANELO("MangaKakalot / Manganelo"),

    /** Zeroscans / ComicK-compatible JSON REST API */
    ZEROSCANS_API("Zeroscans / JSON API"),

    /** MangaDNA / LHTranslation / ReadComicOnline-style PHP CMS */
    LHTRANSLATION("LHTranslation / MangaDNA"),

    /**
     * MangaSee / MangaLife CMS — stores catalogue and chapters as JavaScript
     * variables (vm.Directory, vm.Chapters) rather than in the DOM.
     */
    MANGASEE("MangaSee / MangaLife"),

    /**
     * Guya reader — open-source fan-translation platform used by Guya.moe,
     * Danke fürs Lesen, Mahoushoujo.moe, TritiniaScans, and many more.
     * Exposes a clean JSON API at /api/series/.
     */
    GUYA("Guya / Fan-TL Reader"),

    /**
     * MangaFire / MangaRead style — fast-growing aggregator sites that use
     * a card-grid layout with lazy-loaded chapter lists.
     */
    MANGAFIRE("MangaFire Style"),

    /**
     * MangaPark v3/v4 — uses Next.js with all data in a __NEXT_DATA__ JSON
     * blob, making it more reliable than pure HTML scraping.
     */
    MANGAPARK("MangaPark / Next.js"),

    /** Any website opened in a WebView — user navigates manually (no parsing) */
    WEBVIEW("Web Browser (Manual)"),

    /**
     * A site matched to a built-in Kotatsu parser from kotatsu-parsers-redo.
     * [CustomSource.parserSourceName] stores the [MangaParserSource.name] to
     * look up. This gives the source full inbuilt-source quality: genre
     * filters, chapter lists, page reader — everything the Kotatsu parser supports.
     */
    KOTATSU_PARSER("Built-in Parser (Auto-matched)"),
}
