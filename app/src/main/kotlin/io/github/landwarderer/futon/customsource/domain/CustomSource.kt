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
     *
     * @deprecated MangaPark is marked @Broken in kotatsu-parsers-redo; kept for backward
     * compatibility with existing persisted sources only — not shown in the add-source picker.
     */
    MANGAPARK("MangaPark / Next.js"),

    // ── New parsers added below ───────────────────────────────────────────────

    /**
     * Comix.to and sites sharing its PHP-based manga/comic CMS layout.
     * Recognisable by .list-story-item cards and lstImages JS variable.
     */
    COMIXTO("Comix.to Style"),

    /**
     * ComicK.io — large aggregator with a public REST API at api.comick.io.
     * Very fast and reliable; chapters available per language.
     */
    COMICK_API("ComicK API"),

    /**
     * Bato.to (Batocomic / Comiko) — huge community manga host.
     * Uses server-side HTML with embedded JSON for chapter pages.
     *
     * @deprecated Bato.to is marked @Broken in kotatsu-parsers-redo; kept for backward
     * compatibility with existing persisted sources only — not shown in the add-source picker.
     */
    BATO("Bato.to"),

    /**
     * NineManga CMS — distinctive PHP site with per-language subdomains
     * (en/es/de.ninemanga.com). Paginated per-image chapter reader.
     */
    NINEMANGA("NineManga Style"),

    /**
     * MangaHost / Leitor.net — Brazilian/Portuguese manga aggregators.
     * Recognisable by .manga-card grid and PT-BR status labels.
     */
    MANGAHOST("MangaHost / Leitor.net"),

    /**
     * MangaReader.to and sites sharing its card-grid + DS-image reader.
     * Related to the Zoro/9anime CMS family adapted for manga.
     */
    MANGAREADER("MangaReader Style"),

    /**
     * FanFox.net (MangaFox) and clones using the classic .listing
     * chapter table with per-page reader pagination.
     */
    MANGAFOX("FanFox / MangaFox"),

    /**
     * TCBScans.me and similar scanlation group static sites (Hugo/Jekyll).
     * Serves a small curated catalogue; no server-side search.
     */
    TCBSCANS("TCBScans / Static Scanlation"),

    /**
     * MangaNato.com and MangaBat/MangaBuddy derivatives — the spiritual
     * successor to Manganelo with different URL structure and selectors.
     */
    MANGANATO("MangaNato / MangaBat"),

    /**
     * ReaderFront — open-source scanlation group CMS with a GraphQL API.
     * Used by JManga, ManhwaSmut, and other scanlation groups.
     */
    READERFRONT("ReaderFront GraphQL"),

    /**
     * KissManga / MangaKiss family — classic CMS with .listing chapter
     * table, lstImagesUrl JS variable, and .barContent layout.
     */
    KISSMANGA("KissManga Style"),

    /**
     * Cubari.moe — proxy reader that hosts content from GitHub Gists,
     * Imgur, and other backends via a clean JSON API.
     */
    CUBARI("Cubari / Gist Reader"),

    // ── Second batch of new parsers ───────────────────────────────────────────

    /**
     * MangaPill (mangapill.com) — fast Next.js-based English aggregator.
     * Distinctive chapter reader: every page image is img.js-page[data-src].
     */
    MANGAPILL("MangaPill"),

    /**
     * MangaHub (mangahub.io) — one of the largest English manga aggregators.
     * Bootstrap + custom PHP layout; chapter images served in img.manga-page.
     */
    MANGAHUB("MangaHub"),

    /**
     * MangaHere / Foxaholic CMS (mangahere.cc) — classic large aggregator.
     * Distinct from FanFox: uses .manga-list + .detail-main-list selectors
     * and a paginated per-image chapter reader (select.m).
     */
    MANGAHERE("MangaHere / Foxaholic"),

    /**
     * MangaLib (mangalib.me / mangalib.org / ranobelib.me) — the largest
     * Russian-language manga and light novel platform. Uses a clean REST API
     * at api.lib.social (with fallback to api.mangalib.me).
     */
    MANGALIB("MangaLib (Russian API)"),

    /**
     * Mangago (mangago.me) — long-running site popular for BL/GL titles.
     * Classic PHP CMS; chapter reader is paginated with select.nav_select.
     */
    MANGAGO("Mangago"),

    /**
     * MangaFreak (mangafreak.net) — bespoke PHP CMS with /Manga/{slug} URLs.
     * Distinctive: .manga_search_item cards, .reader_images chapter reader.
     */
    MANGAFREAK("MangaFreak"),

    /**
     * MangaOwl (mangaowl.net / mangaowl.io) — high-traffic English aggregator.
     * Uses .comic-item card grid and #images for chapter page images.
     */
    MANGAOWL("MangaOwl"),

    /**
     * NetTruyen (nettruyenvn.com and mirror network) — the largest Vietnamese
     * manga platform. Custom PHP CMS with .ModuleContent wrapper and
     * .reading-detail chapter image container.
     */
    NETTRUYEN("NetTruyen (Vietnamese)"),

    /**
     * TruyenQQ (truyenqq.com.vn and mirrors) — second-largest Vietnamese manga
     * site with a completely different CMS from NetTruyen.
     * Distinctive: .book_avatar cards, .listChapters chapter table, .html URLs.
     */
    TRUYENQQ("TruyenQQ (Vietnamese)"),

    /**
     * MangaKatana (mangakatana.com) — clean English aggregator with stable PHP
     * layout. Chapters in #chapters table; reader uses img.chapter-img[data-src].
     */
    MANGAKATANA("MangaKatana"),

    // ── Third batch of new parsers (kotatsu-parsers-redo families) ───────────

    /**
     * ZeistManga — Blogger/Blogspot-hosted manga sites using the Atom JSON feed
     * at /feeds/posts/default/-/{category}?alt=json. 51 sites worldwide including
     * MangaSoul, LonerTL, and Indonesian/Spanish/Arabic scanlation blogs.
     */
    ZEISTMANGA("ZeistManga (Blogger-based)"),

    /**
     * Keyoapp CMS — modern Tailwind-based scanlation platform used by AsuraScans,
     * FingerScans, Luminous Scans, and 12+ English scanlation groups. All series
     * on /series/ page; chapters at #chapters > a.
     */
    KEYOAPP("Keyoapp Scanlation CMS"),

    /**
     * HeanCms — JSON REST API manga platform used by ReaperScans, FlameScans,
     * VoidScans, and others. API hosted at api.{domain}; endpoints /query, /series/{slug}.
     */
    HEANCMS("HeanCms (JSON API)"),

    /**
     * WpComics — Vietnamese WordPress manga CMS. Used by WpComics.vn and 17 other
     * Vietnamese/Asian aggregators. Distinctive /tim-truyen listing with div.items grid.
     */
    WPCOMICS("WpComics (Vietnamese WP)"),

    /**
     * Mmrcms — Classic PHP manga CMS powering 17 sites including IsekaiScan
     * and other long-running aggregators. /filterList + /latest-release endpoints;
     * div.media Bootstrap cards.
     */
    MMRCMS("Mmrcms (Classic PHP CMS)"),

    /**
     * Madtheme — Modern Bootstrap manga theme powering 12 sites including MangaKomi
     * and Zinmanga. /search/ endpoint with div.book-item cards. Tag + status filters.
     */
    MADTHEME("Madtheme (Modern WP Theme)"),

    /**
     * Mangabox — Mangakakalot successor platform used by Mangakakalot.to and 6 other
     * sites. Distinct /manga-list?type=latest|topview URL structure; .content-genres-item cards.
     */
    MANGABOX("Mangabox / Mangakakalot.to"),

    /**
     * Liliana CMS — modern PHP manga platform with tag exclusion and multi-language
     * support. Used by KulManga, Manga-Raw.club, and 5 other sites. /filter/{page}/ URLs.
     */
    LILIANA("Liliana Manga CMS"),

    /**
     * Iken CMS — JSON API-first platform with optional api.{domain} subdomain.
     * Used by Mangaclash, Mangagreat, and 5 other sites. /api/query endpoint.
     */
    IKEN("Iken (JSON API CMS)"),

    /**
     * Scan CMS — PHP scanlation group platform used by Sushiscan.net, Lelscans,
     * and 6 other French/Spanish scanlation teams. /manga listing with /page/{n} URLs.
     */
    SCAN("Scan Scanlation CMS"),

    /**
     * PizzaReader — open-source Italian scanlation platform with JSON REST API.
     * Used by PizzaReader.it, SushiScan.fr, and 7 other European scanlation groups.
     * /api/comics lists all series; /api/search/{q} for search.
     */
    PIZZAREADER("PizzaReader (Italian API)"),

    /**
     * FmReader — PHP manga aggregator CMS used by FmReader.com, KissManga.in,
     * and 3 other sites. /?page={n}&search={q} URL pattern; .manga-list-4-list cards.
     */
    FMREADER("FmReader PHP CMS"),

    /**
     * Gattsu CMS — WordPress-derived manga platform with /page/{n}/ URL pagination
     * and CSS background-image covers. Used by 5 sites.
     */
    GATTSU("Gattsu Manga CMS"),

    /**
     * AnimeBootstrap — Bootstrap-based PHP manga theme primarily serving Turkish
     * and Middle Eastern communities. /manga?page={n}&search={q} URL pattern.
     * Used by AnimeBootstrap.net and 4 other sites.
     */
    ANIMEBOOTSTRAP("AnimeBootstrap Theme"),

    /** Any website opened in a WebView — user navigates manually (no parsing) */
    WEBVIEW("Web Browser (Manual)"),

    /**
     * A site matched to a built-in Kotatsu parser from kotatsu-parsers-redo.
     * [CustomSource.parserSourceName] stores the [MangaParserSource.name] to
     * look up. This gives the source full inbuilt-source quality: genre
     * filters, chapter lists, page reader — everything the Kotatsu parser supports.
     */
    KOTATSU_PARSER("Built-in Parser (Auto-matched)"),

    /**
     * A source driven by a user-imported parser template (.json file).
     * [CustomSource.parserSourceName] stores the template name so the
     * repository factory can look up the saved [ParserTemplate] and apply
     * its scraping rules at runtime.
     */
    CUSTOM_TEMPLATE("Custom Template"),
}
