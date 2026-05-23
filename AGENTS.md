# AGENTS.md — Tsuki Android Manga Reader

Reference for AI agents working on this repo. Documents every significant change
made by each agent session so future agents can pick up exactly where we left off.

---

## Repository

| Field | Value |
|---|---|
| Repo | https://github.com/Space4414/Tsuki |
| Branch | `devel` |
| Language | Kotlin / Jetpack Compose |
| Build | Gradle (Android) |
| CI | GitHub Actions (`.github/workflows/`) |

---

## Session 1 (May 23 2026) — Universal Source Beta fixes + multi-selector support

### Problem reported
User created a **Manhwaread** source via "Universal Source Beta". The source showed
genre filter chips (Shounen, Psychological) but the manga list was always
**"Nothing found"**. This happened for any site created from Universal Source Beta
whose listing page uses WordPress path-based pagination (`/manhwa/page/2/` etc.).

### Root causes identified

1. **`TemplateHtmlParser.getList()` always appended `?page=1`**  
   For page 1 it generated `https://manhwaread.com/manhwa/?page=1`.
   WordPress archive pages reject or ignore the `?page=1` query string and return
   a redirect or empty response. The fix: page 1 now uses the bare endpoint
   (`/manhwa/`); page 2+ uses the pagination strategy stored in the template JSON.

2. **No WordPress path-pagination support in template JSON**  
   `UniversalSourceViewModel.buildJson()` never wrote a `"pagination"` key, so
   `TemplateHtmlParser` defaulted to `?page=N` query-param style — wrong for all
   WordPress sites. The fix: `buildJson()` now writes `"pagination": "path"` for
   any slug-only listing path (e.g. `/manhwa/`, `/manga/`). `SiteAutoDetector`
   also now emits a `paginationType` field that is stored in the ViewModel before
   `create()` is called.

3. **`SiteAutoDetector` returned only one CSS selector**  
   If the primary auto-detected card selector was wrong, the entire source failed
   with no fallbacks. The fix: after finding the best selector, `buildMultiSelector()`
   tests all common patterns on the same page and joins every match into a single
   comma-separated CSS selector string (e.g. `"div.page-item-detail, .c-image-hover,
   article.type-manga"`). Jsoup's `select()` natively handles comma-separated
   selectors, so the parser automatically falls back to the next one if the first
   no longer matches.

4. **`CreateExtensionViewModel.maDaraTemplate()` hardcoded `/manga/?page=`**  
   For sites like manhwaread.com that use `/manhwa/` the wrong URL was generated.
   The fix: a new `detectMadaraListingPath(html)` function scans nav-menu `href`
   values for known slugs (`manhwa`, `manhua`, `manga`, `comics`, etc.) in
   priority order and returns the first match. The detected path is then used in
   the generated JS template. Path-based pagination (`/page/N/`) is used in the
   template instead of query params.

### Files changed

| File | Change |
|---|---|
| `app/src/main/kotlin/.../customsource/data/TemplateHtmlParser.kt` | `getList()`: page 1 → bare endpoint; page 2+ → path (`/page/N/`) or query param based on `pagination` key. `GENERIC_ITEM_SELECTORS` expanded with 10+ additional common patterns (MangaThemesia, article type-* variants, .manga-card, .series-card, .story-item, etc.). |
| `app/src/main/kotlin/.../customsource/data/SiteAutoDetector.kt` | Added `paginationType` field to `DetectedFields`. Added `buildMultiSelector()` that returns comma-joined fallback selectors. `detect()` now sets `paginationType = "path"` for all WordPress/Madara/MangaThemesia/MangaStream sites. |
| `app/src/main/kotlin/.../customsource/ui/UniversalSourceViewModel.kt` | Stores `lastDetectedPaginationType` from auto-detect result. `buildJson()` takes `paginationType` param and writes `"pagination"` key to template JSON. Heuristic fallback: any slug-only listPath without `?` gets `"path"` pagination. |
| `app/src/main/kotlin/.../extensions/ui/CreateExtensionViewModel.kt` | Added `detectMadaraListingPath(html)` to scan HTML for archive slug. `identifyCms()` passes detected path to `maDaraTemplate()`. `maDaraTemplate()` rewritten with 3-strategy `getMangaList()`, correct path pagination, and improved `getMangaDetails()` / `getChapterPages()`. |

### Follow-up fix (same session) — Kotlin string interpolation

The first push caused a Kotlin compilation error:

```
CreateExtensionViewModel.kt:219:72 Syntax error: Expecting an expression.
```

**Root cause:** The JavaScript template string embedded inside a Kotlin triple-quoted
string contained `'\\$&'` (standard JS regex replacement).  In Kotlin, `$` inside a
string literal starts interpolation, and `$&` is not a valid identifier, causing a parse
error.

**Fix:** Replaced the one-liner `BASE_URL.replace(/…/g, '\\$&')` with two simpler
replacements that escape only `.` and `/` — the only characters in a URL that are
meaningful in regex — thereby avoiding any `$` character in the Kotlin source:

```javascript
var escapedBase = BASE_URL.replace(/\./g, '\\.').replace(/\//g, '\\/');
var linkRe = new RegExp('<a[^>]+href="(' + escapedBase + '/[^/"#?]+/)"[^>]*>', 'gi');
```

**Lesson for future agents:** Any `$` character inside a JavaScript template string
that lives inside a Kotlin `"""…"""` block must either be absent or escaped as
`${'$'}`. Prefer removing the `$` from JS logic entirely when possible.

CI re-run: **success** (commit `3c65420`).

---

### What still exists (no action needed)

- `app/src/main/assets/extensions/manhwaread.js` — separate bundled JS extension
  for manhwaread.com. Uses the contract: `getMangaListUrl` / `getMangaList` /
  `getMangaDetails` / `getChapterPages`. Already correct for the site; the
  "Nothing found" bug was in the CUSTOM_TEMPLATE parser, not this file.

- `app/src/main/kotlin/.../extensions/data/BuiltinExtensionSeeder.kt` — seeds
  all `.js` files under `assets/extensions/` on every `versionCode` bump. Runs
  at app cold-start via `seedIfNeeded()`. No changes needed.

---

## Session 2 (May 23 2026) — Fix site-logo showing as cover & chapter pages (manhwaread.com)

### Problems reported

1. **Bug 1 — Cover shows site logo:** Tapping a manhwa from the Panhwaread
   (manhwaread.com) custom source shows the ManhwaRead site logo as the cover
   instead of the real cover image.

2. **Bug 2 — Reader shows site logo:** Reading a chapter shows only the site
   logo instead of actual manga pages.

### Root causes

**Bug 2 (pages):**  
`TemplateHtmlParser.getPages()` used a bare `"img"` CSS selector.
manhwaread.com (Mangomic-core theme) includes the site logo as a `<img>` in the
header/navigation. The bare selector picks this up, returning only the logo.
Furthermore, Mangomic-core does **not** embed chapter page images in the DOM at
all — they are encoded as a base64 JSON blob inside an inline `<script>` block:

```javascript
var chapterData = {"data":"<base64>","base":"https://cdn/postId"};
```

Each decoded entry is `{"src":"chapterId\/mr_001.jpg","w":800,"h":5000}`.
No DOM-based selector will ever find these images.

**Bug 1 (cover):**  
`TemplateHtmlParser.extractDetailCover()` fell through to the `og:image` meta
tag, which on manhwaread.com is set to the site logo URL (WordPress default when
a page-specific OG image is not configured). Additionally,
`TemplateHtmlParser.USER_AGENT` was `"Tsuki/1.0 (Android)"` — some WordPress
CDNs reject bot-like UAs or return placeholder images. Also,
`PageLoader.createPageRequest()` never set a `Referer` header for
`CustomMangaSource` OkHttp requests, so CDNs with hotlink-protection served
the logo instead of the real image.

### Fixes applied

#### `TemplateHtmlParser.kt`
1. **`getPages()` — cascading selector strategy:**
   - Step 1: Use configured `imageSelector` from template JSON (if present and
     not the generic `"img"`).
   - Step 2: Try Madara/WordPress-specific reader selectors
     (`div.page-break img`, `div.reading-content img`,
     `.wp-manga-chapter-img img`, `#reader img`, etc.) — these never match
     header/nav logo images.
   - Step 3: Parse `chapterData` inline `<script>` block (Mangomic-core /
     manhwaread.com). Base64-decodes `data`, resolves each `src` against `base`.
   - Step 4: Last-resort generic `"img"` selector, filtered through `isLogoUrl()`.
   - Removed the `pageListSection ?: return emptyList()` guard — sources created
     without an explicit `pageList` section now still attempt page extraction.

2. **`parseChapterDataScript()` private method** — ported from `MadaraHtmlParser`.
   Parses the base64 `chapterData` JS variable from an inline `<script>`.

3. **`isLogoUrl()` extension on `String`** — returns `true` if the URL path
   contains `/logo`, `favicon`, `site-icon`, `/brand`, or `header-logo`.

4. **`extractDetailCover()`** — now skips `og:image` when `isLogoUrl()` is true.

5. **`USER_AGENT`** — changed from `"Tsuki/1.0 (Android)"` to a full Chrome
   Android browser UA so WordPress CDN checks pass during static HTML fetches.

#### `PageLoader.kt`
6. **`createPageRequest()`** — when `mangaSource is CustomMangaSource`, injects:
   - `Referer: <source.cleanBaseUrl>/` — satisfies WordPress/Madara CDN
     hotlink-protection checks.
   - `User-Agent: <BROWSER_UA>` — prevents CDN bot-rejection.

   `CommonHeadersInterceptor` only auto-adds these headers for built-in
   `MangaParserSource` instances; custom sources were never covered.

### Files changed

| File | Change |
|---|---|
| `customsource/data/TemplateHtmlParser.kt` | `getPages()`, `parseChapterDataScript()`, `isLogoUrl()`, `extractDetailCover()`, `USER_AGENT` |
| `reader/domain/PageLoader.kt` | `createPageRequest()` — Referer + UA for CustomMangaSource |

### Commit & CI

- Commit: `8ca6fd3d4dded2cbca1d56b6649ad49ae9f4453a`
- CI build #251: **success**
- Branch `devel` HEAD is now `8ca6fd3`.

---

## Session 3 (May 23 2026) — Universal Source Beta: route to proven theme parsers

### Problem

Sessions 1 and 2 patched symptoms (selector guessing, chapterData JS parsing,
Referer headers) but left the root cause untouched: `UniversalSourceViewModel`
always created a `CustomSource` with `type = CUSTOM_TEMPLATE`, sending every
user-added site through the fragile `TemplateHtmlParser` — regardless of whether
a battle-tested theme parser already existed for that CMS.

The codebase already had **40+ proven theme parsers** (Madara, MangaThemesia,
MangaStream, Keyoapp, MadTheme, Mmrcms, …) and `SiteAutoDetector` already
correctly fingerprinted the CMS theme. The two were just never wired together.

### Root cause

`UniversalSourceViewModel.create()` hardcoded `CustomSourceType.CUSTOM_TEMPLATE`
regardless of what `SiteAutoDetector` detected.

### Fix

**3 file changes, zero new parser code needed.**

#### `SiteAutoDetector.kt`
- Changed `private enum class CmsType` → `enum class CmsType` (made public)
- Added `cmsType: CmsType = CmsType.UNKNOWN` field to `DetectedFields`
- Propagated detected `cmsType` into the return value

#### `UniversalSourceViewModel.kt`
- Added `lastDetectedCmsType: SiteAutoDetector.CmsType` property
- `autoDetect()` now stores `fields.cmsType` from the detector
- New private `cmsTypeToSourceType()` maps fingerprinted CMS → proven parser type:

| Detected CmsType | CustomSourceType | Parser used |
|---|---|---|
| `MADARA` | `MADARA` | `MadaraHtmlParser` |
| `MANGA_THEMESIA` | `MANGATHEMESIA` | `MangaThemesiaHtmlParser` |
| `MANGA_STREAM` | `MANGASTREAM` | `MangaStreamHtmlParser` |
| `KEYOAPP` | `KEYOAPP` | `KeyoappHtmlParser` |
| `MAD_THEME` | `MADTHEME` | `MadthemeHtmlParser` |
| `MMRCMS` | `MMRCMS` | `MmrcmsHtmlParser` |
| `WORDPRESS_GENERIC` / `UNKNOWN` | `CUSTOM_TEMPLATE` | `TemplateHtmlParser` (fallback) |

- Proven parsers skip `ParserTemplate` JSON save (they never read it)
- `pageImageSelector` validation only enforced for `CUSTOM_TEMPLATE` fallback
- `Result.Success` now carries `parserLabel` (the `CustomSourceType.label`) for UI feedback

#### `UniversalSourceActivity.kt`
- Auto-detect status card shows "✓ WordPress Madara theme detected — proven parser selected."
  for known CMS; generic message for unknown CMS
- Success toast shows which parser was selected, e.g. `"ManhwaRead" added · WordPress Madara`
- Comment updated to describe the new routing behaviour

### Effect for manhwaread.com / all Madara sites

```
User taps Auto-detect on manhwaread.com URL
  → SiteAutoDetector sees "wp-manga" / "madara" in HTML → CmsType.MADARA
  → Status card: "✓ WordPress Madara theme detected — proven parser selected."
User taps Create
  → CustomSource(type = CUSTOM_TEMPLATE) NO LONGER CREATED
  → CustomSource(type = MADARA) saved in CustomSourcesRepository
  → CustomMangaRepository routes all calls to MadaraHtmlParser
     • getList()  → correct manga cards, pagination, search — all work
     • getDetails() → correct cover (.summary_image), description, tags
     • getPages() → parseChapterDataScript() handles chapterData JS variable
                    (Mangomic-core) natively — real chapter pages load
```

### Files changed

| File | Change |
|---|---|
| `customsource/data/SiteAutoDetector.kt` | Made `CmsType` public; added `cmsType` to `DetectedFields` return |
| `customsource/ui/UniversalSourceViewModel.kt` | `cmsTypeToSourceType()` mapping; route to proven parsers |
| `customsource/ui/UniversalSourceActivity.kt` | Status card + success toast show detected parser |

### Commit & CI

- Commit: `9eb9006ffcc2ae16ee057f18cd8b19d6f6052514`
- CI build #252: **success**
- Branch `devel` HEAD is now `9eb9006`.

---

## Architecture notes (for future agents)

### Universal Source Beta — revised flow (Session 3+)

| Path | Entry point | Result |
|---|---|---|
| **Known CMS detected** | `UniversalSourceActivity` → `UniversalSourceViewModel` → `SiteAutoDetector` (CmsType ≠ UNKNOWN) | `CustomSource(type = MADARA / MANGATHEMESIA / …)` → routes to proven parser in `CustomMangaRepository` |
| **Unknown CMS** | Same flow, CmsType = UNKNOWN or WORDPRESS_GENERIC | `CustomSource(type = CUSTOM_TEMPLATE)` → `TemplateHtmlParser` (improved fallback) |
| **Create Extension (JS/Dart)** | `CreateExtensionActivity` → `CreateExtensionViewModel` | JS engine (QuickJS or similar) calling exported functions |

### CMS detection fingerprints (`SiteAutoDetector.detectCmsType`)

| CmsType | HTML fingerprint |
|---|---|
| `MADARA` | `wp-manga`, `WpMangaReader`, `madara`, `wp-manga-chapter` |
| `MANGA_THEMESIA` | `ts_reader.run`, `.bsx` + `anilist` |
| `MANGA_STREAM` | `WPMangaStream`, `readerarea` |
| `KEYOAPP` | `series-card` + `series_tags_page` |
| `MAD_THEME` | `book-item` + `wp-content` + `/search/` |
| `MMRCMS` | `filterList` + `media-body` |
| `WORDPRESS_GENERIC` | `wp-content`, `/wp-json/` |
| `UNKNOWN` | None of the above |

### `CustomMangaRepository` parser routing (for future agents)

All parser types are in `customsource/data/`. Each parser takes `CustomMangaSource`
as its only constructor argument and implements `getList()`, `getDetails()`,
`getGenres()`, `getPages()`. `CustomMangaRepository` lazy-initialises one instance
per type and delegates based on `customSource.source.type`.

For `CUSTOM_TEMPLATE` (unknown sites), `TemplateHtmlParser` is used. As of Session 2,
it now handles:
- Specific Madara reader selectors before generic `"img"`
- `parseChapterDataScript()` for Mangomic-core `chapterData` JS variable
- `isLogoUrl()` filtering for logo-polluted `og:image` fallbacks
- Browser UA + Referer for CDN hotlink protection (via `PageLoader`)

### Template JSON schema (CUSTOM_TEMPLATE only)

The "Universal Source Beta" creates a `CustomSource` with `type = CUSTOM_TEMPLATE`
and a linked `ParserTemplate` JSON in `ParserTemplateRepository`. The template
JSON schema is:

```json
{
  "name": "...",
  "version": "1.0",
  "type": "html",
  "mangaList": {
    "endpoint": "/manhwa/",
    "pagination": "path",
    "pageParam": "page",
    "itemSelector": "div.page-item-detail, .c-image-hover",
    "titleSelector": ".post-title a, h3 a",
    "coverSelector": "img",
    "searchEndpoint": "/",
    "searchParam": "s"
  },
  "mangaDetail": {
    "titleSelector": ".post-title h1",
    "coverSelector": ".summary_image img",
    "descriptionSelector": ".summary__content p"
  },
  "chapterList": {
    "selector": ".wp-manga-chapter a",
    "titleSelector": "a",
    "linkSelector": "a"
  },
  "pageList": {
    "imageSelector": ".reading-content img"
  }
}
```

The `"pagination"` key controls how `TemplateHtmlParser.getList()` constructs
page URLs:
- `"path"` or `"wordpress"` → `<endpoint>/page/<N>/` for pages ≥ 2, bare
  `<endpoint>` for page 1 (WordPress standard)
- anything else → `<endpoint>?<pageParam>=<N>` (query-param style)

### Key selectors and CMS patterns

| CMS | Card selector | Listing path style |
|---|---|---|
| Madara (WP-Manga) | `div.page-item-detail`, `.c-image-hover` | `/manga/page/N/` or `/manhwa/page/N/` |
| MangaThemesia | `div.bsx`, `div.bs` | `/manga/page/N/` |
| MangaStream | `.utao .uta`, `.utao` | `/manga/page/N/` |
| MadTheme | `.book-item` | `?page=N` |
| MMRCMS | `.media` | `?page=N` |

---

## CI

GitHub Actions. The workflow builds a debug APK. To check status:

```bash
gh run list --repo Space4414/Tsuki --branch devel --limit 5
```

Build #250 (`3c65420`) was the last successful build from Session 1.
Build #251 (`8ca6fd3`) is the last successful build from Session 2 (this session).

  ---

  ## Session 4 (May 23 2026) -- USB: HtmlCleaner + SmartPageFetcher + JsRenderFetcher

  ### Problems addressed
  Universal Source Beta (USB) worked well for WordPress Madara sites but failed for
  most other sites. Three root causes were identified and fixed.

  ### Root cause 1 -- HTML too large for Gemini
  Full HTML from manga sites is 500 KB-1 MB. Gemini gets overwhelmed.

  **Fix -- created `HtmlCleaner.kt`:**
  - Removes ALL <script>, <style>, <head>, HTML comments, inline style= attributes, <svg>
  - Collapses whitespace. Keeps ONLY <body> content.
  - Hard-caps at 15,000 characters, taking the **middle section** (where manga cards live)
  - Applied in SiteAutoDetector Step 9 before storing into LearningSession
  - Applied in AiParserGenerator.buildGeminiPrompt() replacing .take(HTML_BUDGET_PER_PAGE)

  ### Root cause 2 -- Wrong page fetched
  USB fetched the homepage which often has no manga cards.

  **Fix -- created `SmartPageFetcher.kt`:**
  - Probes 11 common manga-list paths (/manga, /manhwa, /manhua, /comics, /series, ...)
    via HEAD requests; fetches the first that returns HTTP 200
  - Falls back to scanning homepage nav/header links for manga keywords
  - Falls back to original URL if nothing matches
  - Also fetches ONE manga detail page (href matching /manga/*, /series/*, /title/*, ...)
  - Also fetches ONE chapter page (href matching /chapter/*, /ch/*, /read/*, ...)
  - Replaces the old findMangaListPage + individual fetchHtml calls (Steps 3-7)

  ### Root cause 3 -- JavaScript-rendered content
  Sites like comix.to render manga cards via JavaScript; raw HTTP returns empty HTML.

  **Fix -- created `JsRenderFetcher.kt`:**
  - Detection: JS-rendered when ANY of these are true:
    - Fewer than 3 <img> tags in raw HTML
    - Fewer than 200 visible body-text characters
    - Contains: __NEXT_DATA__, window.__NUXT__, ng-app, data-reactroot, div id="app/root"
  - If JS-rendered: loads URL in hidden WebView, waits onload + 2s settle, extracts DOM HTML
  - If NOT JS-rendered: plain HTTP fetch (faster)
  - SmartPageFetcher calls JsRenderFetcher.isJsRendered() on every page automatically

  ### Wiring changes
  - SiteAutoDetector: added optional context: Context? = null param; homepage also checked
    for JS rendering
  - UniversalSourceViewModel: injected @ApplicationContext Context (Hilt); passed to SiteAutoDetector
  - AiParserGenerator: replaced .take(HTML_BUDGET_PER_PAGE) with HtmlCleaner.cleanAndCap()

  ### Files changed

  | File | Change |
  |---|---|
  | customsource/data/HtmlCleaner.kt | NEW -- HTML cleaning/capping utility |
  | customsource/data/SmartPageFetcher.kt | NEW -- smart multi-page discovery |
  | customsource/data/JsRenderFetcher.kt | NEW -- JS rendering detection + WebView fetch |
  | customsource/data/SiteAutoDetector.kt | Added context param; integrated new helpers |
  | browser/learning/AiParserGenerator.kt | HtmlCleaner in Gemini prompt builder |
  | customsource/ui/UniversalSourceViewModel.kt | Inject Context; pass to SiteAutoDetector |

  ### Commit & CI

  - Branch devel HEAD updated in this session.
  - CI build: pending (see GitHub Actions).

  

  ---

  ## USB Fix Session — Part 2 (May 2026)

  ### Root Cause 4 — Gemini prompt not specific enough
  **New file: GeminiSelectorAnalyzer.kt** (`customsource.data` package)
  - Uses the exact USB-specific JSON schema (mangaList, mangaDetail, chapterList, pageList, genres, confidence, notes)
  - System instruction emphasises: only standard CSS selectors, comma-separated fallback selectors, never guess
  - Validates every returned selector via `Jsoup.parse(html).select(selector).isNotEmpty()`
  - Decodes Gemini response safely (strips markdown fences); never crashes on malformed JSON
  - Returns `null` on any Gemini failure so callers can fall through to the next layer

  ### Root Cause 5 — No user feedback during process
  **Modified: SiteAutoDetector.kt**
  - Added `onProgress: ((String) -> Unit)?` constructor parameter
  - Emits 7 real-time step messages matching the USB spec exactly:
    1. "🌙 Fetching manga list page..."
    2. "🌙 Looking for manga detail page..."
    3. "🌙 Looking for chapter page..."
    4. "🌙 Detected: [CMS name] — routing to proven parser" OR "🌙 Unknown site — analyzing with AI..."
    5. "🧠 Sending to Gemini AI for analysis..." (emitted inside GeminiSelectorAnalyzer)
    6. "✅ Verifying selectors against live HTML..." (emitted inside GeminiSelectorAnalyzer)
    7. "✓ Done! [N] fields detected. Parser ready." OR "⚠️ Done with low confidence. Please review fields."

  **Modified: UniversalSourceViewModel.kt**
  - Added `progressStep: StateFlow<String>` exposed to the Activity
  - Wires the `onProgress` callback from SiteAutoDetector to emit into `_progressStep`
  - Added `getGeminiApiKey()` helper reading from `futon_prefs` SharedPreferences (`gemini_api_key` key)

  **Modified: UniversalSourceActivity.kt**
  - Added `observeProgressStep()` coroutine that updates the status card text with each emitted step
  - Status card is now VISIBLE during Loading state (never blank/frozen screen)
  - Progress text updates live as each step completes

  ### Root Cause 6 — No fallback when Gemini fails
  **Modified: SiteAutoDetector.kt** — multi-layer fallback added to `detect()`:
  - Layer 1: Known CMS routing (already existed — Madara, MangaThemesia, MangaStream, Keyoapp, MadTheme, MMRCMS)
  - Layer 2: GeminiSelectorAnalyzer — for unknown CMS sites when Gemini API key is present
    - If Gemini succeeds, its result is returned directly (with siteName/listPath merged from CSS analysis)
    - If Gemini fails, falls through to Layer 3
  - Layer 3: Existing structural DOM analysis (buildMultiSelector + detectMangaCardsStructural)
  - Layer 4: Always returns DetectedFields — never empty form, always pre-fills best available guess

  ### Files changed in this session (Part 1 + Part 2)
  | File | Change |
  |------|--------|
  | HtmlCleaner.kt | NEW — strips scripts/styles/SVG/comments, caps at 15K chars for Gemini |
  | SmartPageFetcher.kt | NEW — HEAD-probes common paths, scans nav links, JS-render upgrade |
  | JsRenderFetcher.kt | NEW — hidden WebView renderer with 2s settle delay |
  | GeminiSelectorAnalyzer.kt | NEW — USB-specific Gemini prompt + selector validation |
  | SiteAutoDetector.kt | MOD — progress callbacks, Gemini layer, always pre-fills |
  | UniversalSourceViewModel.kt | MOD — progressStep StateFlow, geminiApiKey helper |
  | UniversalSourceActivity.kt | MOD — live progress display, no blank screen |

  ### Test sites
  1. manhwaread.com — regression (should still work via CSS detection)
  2. comix.to — JS-rendered → JsRenderFetcher → Gemini analysis
  3. mangadex.org — MANGADEX_COMPATIBLE detection
  4. toonily.com — Madara variant (Layer 1)
  5. asurascans.com — MangaThemesia variant (Layer 1)
  6. Unknown site — Layer 2 (Gemini) → Layer 3 (heuristic) → Layer 4 (pre-filled guess)
  

  ---

  ## USB Fix Session — Part 3 (May 2026)

  ### Bug: USB-created sources show "Nothing Found" after app restart

  **Symptom**: CUSTOM_TEMPLATE sources (created by USB for unknown/generic CMS sites)
  work perfectly on first use within the same app session, but after the app is
  closed and reopened the source shows "Nothing found" on every manga list load.
  MADARA / MANGATHEMESIA and other proven-parser types are NOT affected (they
  never consult ParserTemplateRepository).

  ### Root Cause Analysis

  #### Root Cause 7 — TemplateHtmlParser.template is `by lazy` (PRIMARY BUG)

  File: `TemplateHtmlParser.kt`

  ```kotlin
  // BROKEN — evaluates exactly once, caches result forever
  private val template: JSONObject? by lazy {
      val name = customSource.source.parserSourceName ?: return@lazy null
      val raw = ParserTemplateRepository.peekByName(name)?.rawJson ?: return@lazy null
      runCatching { JSONObject(raw) }.getOrNull()
  }
  ```

  **Why it fails on restart:**
  1. App restarts — Hilt begins initialising singletons lazily
  2. The source list screen loads and calls `getList()` on a CUSTOM_TEMPLATE source
  3. `getList()` → `mangaListSection` → `template` (lazy evaluated for the first time)
  4. `ParserTemplateRepository.INSTANCE` is **null** at this moment (Hilt hasn't
     injected ParserTemplateRepository into anything yet — the USB ViewModel is
     not on screen)
  5. `peekByName(name)` returns null → lazy caches `null` → **permanently null**
  6. Every subsequent call to `getList()`, `getDetails()`, `getPages()` returns
     empty list silently (via the `runCatching { }.getOrElse { emptyList() }`
     wrapper in CustomMangaRepository)

  **On first use (same session):** USB opens UniversalSourceViewModel which injects
  ParserTemplateRepository → INSTANCE is set → by the time getList() is called,
  peekByName() works → lazy caches the correct JSONObject → source works.

  **Fix:** Change `by lazy` to a plain `get()` property. The lookup is O(n) over
  a tiny in-memory list with no I/O, so performance is not a concern.

  ```kotlin
  // FIXED — re-evaluates on every access, finds template once INSTANCE is set
  private val template: JSONObject?
      get() {
          val name = customSource.source.parserSourceName ?: return null
          val raw  = ParserTemplateRepository.peekByName(name)?.rawJson ?: return null
          return runCatching { JSONObject(raw) }.getOrNull()
      }
  ```

  #### Root Cause 8 — No diagnostic logging (SECONDARY, causes silent failures)

  Without logging it is impossible to tell whether:
  - The template was correctly written to disk after USB creates a source
  - The template was correctly loaded from disk on restart
  - `parserSourceName` round-trips correctly through CustomSourcesRepository JSON
  - `ParserTemplateRepository.INSTANCE` was set before `peekByName` was called

  **Fix:** Added `android.util.Log` calls at every critical persistence checkpoint:
  - `ParserTemplateRepository.loadAll()` — logs count + names of loaded templates
  - `ParserTemplateRepository.saveAll()` — logs count + names, then immediately
    reads back from SharedPreferences to verify the write landed
  - `CustomSourcesRepository.loadAll()` — logs every source with its type and
    `parserSourceName`
  - `CustomSourcesRepository.saveAll()` — logs all CUSTOM_TEMPLATE sources with
    their `parserSourceName`
  - `TemplateHtmlParser.template` getter — logs a W-level warning if peekByName
    returns null, including INSTANCE state and template count

  Also added `ParserTemplateRepository.instanceIsReady(): Boolean` companion method
  so the W-level log in TemplateHtmlParser can report whether INSTANCE is set.

  ### Why MADARA/MANGATHEMESIA are unaffected

  Proven parsers (MadaraHtmlParser, MangaThemesiaHtmlParser, etc.) never consult
  ParserTemplateRepository. They use hardcoded selectors for their CMS family.
  CustomMangaRepository routes them directly:
  ```kotlin
  CustomSourceType.MADARA -> runCatching { madaraParser.getList(...) }.getOrElse { emptyList() }
  ```
  No lazy template lookup, no INSTANCE dependency — they work identically on first
  use and after restart.

  ### Files changed in Part 3

  | File | Change |
  |------|--------|
  | TemplateHtmlParser.kt | `by lazy` → `get()` on `template` property (PRIMARY FIX) |
  | ParserTemplateRepository.kt | Added `instanceIsReady()` + Log in `loadAll`/`saveAll` |
  | CustomSourcesRepository.kt | Added Log in `loadAll`/`saveAll` for CUSTOM_TEMPLATE sources |

  ### Logcat tags to watch

  | Tag | Purpose |
  |-----|---------|
  | `USB-PTR` | ParserTemplateRepository load/save events |
  | `USB-CSR` | CustomSourcesRepository load/save events |
  | `USB-Template` | TemplateHtmlParser.template getter warnings |

  ### Test procedure

  1. Create a USB source for a site that resolves to CUSTOM_TEMPLATE (any unknown CMS)
  2. Open the source — confirm manga list loads  
  3. Close and reopen the app
  4. Open the same source — it must load the same manga list (previously: "Nothing found")
  5. Check Logcat for `USB-PTR`, `USB-CSR`, `USB-Template` tags to confirm:
     - `saveAll` logged the template after creation
     - `loadAll` logged it back after restart
     - `USB-Template` W tag is **never emitted** (means template was found)
  