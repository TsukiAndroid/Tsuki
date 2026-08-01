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
  

  ---

  ## USB Fix Session — Part 4 (May 2026)

  ### Bug: "Nothing Found" after restart — STILL occurring after Part 3 lazy→get() fix

  **Root Cause 9 — ParserTemplateRepository.INSTANCE may be null at getList() time**

  Even after changing `template` from `by lazy` to a plain `get()` property, the bug
  persists. The underlying cause is an initialization-order race:

  1. App restarts; Hilt initialises singletons lazily (on first injection request).
  2. The source-list screen opens and immediately calls `CustomMangaRepository.getList()`.
  3. At this moment nothing has yet injected `ParserTemplateRepository` — INSTANCE is null.
  4. `TemplateHtmlParser.template` getter calls `peekByName(name)` → returns null.
  5. `getList()` returns `emptyList()` → "Nothing found" shown.
  6. The screen ViewMode caches the empty result; `getList()` is never retried.

  The `get()` fix in Part 3 was necessary but not sufficient: it makes the getter
  re-evaluate on every access, but the caller still caches the empty result after
  the very first call.

  **Fix: Eager injection of ParserTemplateRepository + CustomSourcesRepository in BaseApp**

  Added two `@Inject` fields to `BaseApp`:

  ```kotlin
  @Inject lateinit var parserTemplateRepository: ParserTemplateRepository
  @Inject lateinit var customSourcesRepository: CustomSourcesRepository
  ```

  Because `BaseApp.onCreate()` is called before any Activity or screen can open,
  Hilt injects these singletons at app startup. Both `.INSTANCE` fields are set
  before the source-list screen ever calls `getList()`, eliminating the race.

  **Root Cause 10 — SharedPreferences.apply() is asynchronous (write-loss risk)**

  Both repositories used `.apply()` which posts the disk write to a background
  queue. If the Android OS kills the process before the queue drains (low-memory
  pressure, ANR, crash), the template/source data is never written to disk.

  **Fix:** Changed to `.commit()` in both `ParserTemplateRepository.saveAll()`
  and `CustomSourcesRepository.saveAll()`. `.commit()` writes synchronously and
  returns a boolean confirming success — the write is durable before the method returns.

  ### TsukiDebug logging added (all 4 files)

  Per user request, added `Log.d("TsukiDebug", ...)` at every critical point in
  the persistence chain so logcat reveals exactly where the chain breaks:

  | Tag | Location | What it logs |
  |-----|----------|--------------|
  | TsukiDebug | `PTR.loadAll` | Prefs XML file path, fileExists flag, template names + IDs on load |
  | TsukiDebug | `PTR.saveAll` | Template name, prefs file path, commit() result, fileExists verify |
  | TsukiDebug | `PTR.peekByName` | Requested name, found/not-found, INSTANCE state, all known names |
  | TsukiDebug | `CSR.loadAll` | Every source: id, name, type, parserSourceName |
  | TsukiDebug | `CSR.saveAll` | Every source: id, name, type, parserSourceName, commit() result |
  | TsukiDebug | `CMR.getList` | Source name/type/parserSourceName; CUSTOM_TEMPLATE lookup result; proven-parser routing |
  | TsukiDebug | `THP.getList` | parserSourceName, templateFound, sectionFound, endpoint, pagination, itemSelector |
  | TsukiDebug | `BaseApp.onCreate` | Confirms repos are ready, total template count, total source count |

  ### How to reproduce and use the logs

  ```
  adb logcat -s TsukiDebug
  ```

  **Expected on first use (no restart):**
  ```
  PTR.saveAll: template name='Manhwaread' commitResult=true fileExistsOnDisk=true
  CSR.saveAll: id=... name='Manhwaread' type=CUSTOM_TEMPLATE parserSourceName=Manhwaread
  CMR.getList: name='Manhwaread' type=CUSTOM_TEMPLATE parserSourceName=Manhwaread
  PTR.peekByName: request='Manhwaread' found=true instanceReady=true
  THP.getList: parserSourceName='Manhwaread' templateFound=true sectionFound=true
  ```

  **If bug still occurs after restart — what to look for:**
  - `PTR.loadAll: jsonIsNull=true` → prefs file was never written (apply() lost it)
  - `PTR.peekByName: found=false instanceReady=false` → INSTANCE null (eager init failed)
  - `CMR.getList: CUSTOM_TEMPLATE lookup name='...' found=false` → name mismatch
  - `CSR.loadAll: parserSourceName=null` → parserSourceName lost during serialization
  - `THP.getList: templateFound=false sectionFound=false` → confirms nothing reached THP

  ### Files changed in Part 4

  | File | Change |
  |------|--------|
  | BaseApp.kt | Added `@Inject lateinit var parserTemplateRepository` + `customSourcesRepository` for eager init; `TsukiDebug` log in `onCreate()` |
  | ParserTemplateRepository.kt | `apply()` → `commit()`; replaced USB-PTR logs with TsukiDebug; added file-path + fileExists logging; expanded peekByName() to log all details |
  | CustomSourcesRepository.kt | `apply()` → `commit()`; replaced USB-CSR logs with TsukiDebug; logs all sources (not just CUSTOM_TEMPLATE) on load/save |
  | CustomMangaRepository.kt | Added TsukiDebug log at start of `getList()`: source name/type/parserSourceName, CUSTOM_TEMPLATE template lookup, proven-parser routing |
  | TemplateHtmlParser.kt | Added TsukiDebug log at start of `getList()`: parserSourceName, templateFound, sectionFound, endpoint, pagination, itemSelector |
  
---

## Session 3 (June 24 2026) — BROWSER_SOURCE source type

### Feature added
A new `CustomSourceType.BROWSER_SOURCE` that gives users a full in-app browser for
any manga website, with chapter detection, reading history, favicon auto-fetch, and
cookie/session persistence. Does **not** touch any existing source type.

### New files

| File | Purpose |
|---|---|
| `app/src/main/kotlin/.../browsersource/data/BrowserSourceRepository.kt` | SharedPreferences persistence for last URL, scroll position, per-source history. Cookies handled automatically by Android CookieManager. |
| `app/src/main/kotlin/.../browsersource/data/BrowserSourceChapterDetector.kt` | URL-pattern + image-count heuristics; JS snippets for image extraction, og:title/image, read-chapter CSS marking. No dependency injection needed — pure `object`. |
| `app/src/main/kotlin/.../browsersource/data/BrowserSourceHistoryTracker.kt` | Singleton Hilt service that records and marks chapters as read. Delegates persistence to BrowserSourceRepository. |
| `app/src/main/kotlin/.../browsersource/ui/BrowserSourceActivity.kt` | Full in-app browser: URL bar + back/forward/refresh/adblock toolbar, WebView with AdBlock integration, chapter-detection FAB "📖 Open in Tsuki Reader", scroll persistence, last-URL resume. |
| `app/src/main/kotlin/.../browsersource/ui/ProgressChromeClient.kt` | Drives LinearProgressIndicator from WebChromeClient.onProgressChanged. |
| `app/src/main/kotlin/.../browsersource/ui/AddBrowserSourceSheet.kt` | BottomSheetDialogFragment: URL input → favicon auto-fetch (3 strategies + letter-avatar fallback using Jsoup) → preview → save. |
| `app/src/main/res/layout/activity_browser_source.xml` | CoordinatorLayout: AppBarLayout toolbar + WebView + LinearProgressIndicator + two ExtendedFABs. |
| `app/src/main/res/layout/sheet_add_browser_source.xml` | Bottom sheet layout for AddBrowserSourceSheet. |
| `app/src/main/res/menu/opt_browser_source.xml` | Options menu for BrowserSourceActivity overflow. |
| `app/src/main/res/drawable/ic_browser_source.xml` | Globe icon for browser sources. |
| `app/src/main/res/drawable/ic_refresh.xml` | Refresh icon. |
| `app/src/main/res/drawable/ic_shield.xml` | Shield icon for ad-block toggle. |
| `app/src/main/res/drawable/ic_book_open.xml` | Book-open icon for "Open in Reader" FAB. |
| `app/src/main/res/drawable/ic_arrow_back.xml` | Back navigation arrow. |
| `app/src/main/res/drawable/bg_url_bar.xml` | Rounded rectangle background for URL bar. |

### Modified files

| File | Change |
|---|---|
| `customsource/domain/CustomSource.kt` | Added `BROWSER_SOURCE("Browser Source")` to `CustomSourceType` enum (before WEBVIEW). |
| `explore/ui/ExploreFragment.kt` | Added `BROWSER_SOURCE` branch in `onItemClick`: launches `BrowserSourceActivity`. |
| `explore/ui/ExploreMenuProvider.kt` | Added `R.id.action_add_browser_source` case: shows `AddBrowserSourceSheet`. |
| `res/menu/opt_explore.xml` | Added `action_add_browser_source` menu item. |
| `AndroidManifest.xml` | Registered `BrowserSourceActivity`. |
| `customsource/ui/AddCustomSourceSheet.kt` | Excluded `BROWSER_SOURCE` from type dropdown (same pattern as KOTATSU_PARSER). |
| `res/values/strings.xml` | Added 18 new string resources for the browser source feature. |

### Key design decisions
- **No new parser infrastructure** — BROWSER_SOURCE is explicitly excluded from `AddCustomSourceSheet` type picker. Users add browser sources exclusively via `AddBrowserSourceSheet` from the Explore overflow menu.
- **Favicon fetching** uses three strategies: `favicon.ico` direct HEAD → `<link rel="icon">` via Jsoup → Google favicon service fallback. Letter-avatar shown if all fail.
- **Cookie persistence** is automatic via Android's `CookieManager` — no serialization needed.
- **AdBlock integration** reuses existing `AdBlock.shouldLoadUrl()` from `io.github.landwarderer.futon.core.network.webview.adblock`.
- **Chapter detection** is multi-strategy: URL pattern first, then image-count heuristic from `shouldInterceptRequest`, then JavaScript image collection on page load.
- **Read chapters** are marked with a CSS `✓` overlay injected via JavaScript on manga detail pages.


---

## Session 4 (June 24 2026) — Visual Point-and-Click Rule Builder

### Feature added
A complete visual element picker that lets users tap directly on manga website elements
to auto-generate CSS selectors — no coding knowledge required. Fixes broken parsers and
adds support for unknown sites through a guided 5-step tap flow.

### New files

| File | Purpose |
|---|---|
| `app/src/main/assets/element_picker.js` | JavaScript injected into every WebView page: hover/tap highlighting, CSS-selector generation (class→data-attr→parent→tag strategies), sibling highlighting, auto parent-container detection, wrong-element warnings (nav/logo/ad), `window.TsukiPicker` JS interface bridge. |
| `app/src/main/kotlin/.../customsource/ui/visualpicker/PickerState.kt` | `PickerStep` enum (MANGA_TITLE, COVER_IMAGE, CARD_CONTAINER, CHAPTER_TITLE, PAGE_IMAGE, COMPLETE), `PickerSession` data class, `TestResult` sealed interface. |
| `app/src/main/kotlin/.../customsource/ui/visualpicker/SelectorGenerator.kt` | Validates selectors (`isUsable()`), builds `ParserTemplate`-compatible JSON from captured selectors (`buildTemplateJson()`). Same schema as USB output so existing `TemplateHtmlParser` works with no changes. |
| `app/src/main/kotlin/.../customsource/ui/visualpicker/ElementPickerWebView.kt` | `WebView` subclass — injects `element_picker.js` on every `onPageFinished`, registers `TsukiPicker` JS interface, exposes `clearHighlights()` and `highlightSelector()` helpers, uses same browser UA as other parsers, optional `AdBlock` integration via `shouldLoadUrl()`. |
| `app/src/main/kotlin/.../customsource/ui/visualpicker/VisualRuleBuilderViewModel.kt` | Manages `PickerSession` state machine: element selection → step progression, auto-fill CARD_CONTAINER from JS parent detection, undo, skip, retap, live parser test via Jsoup, save to `ParserTemplateRepository` + `CustomSourcesRepository`. |
| `app/src/main/kotlin/.../customsource/ui/visualpicker/VisualRuleBuilderActivity.kt` | Full-screen activity: `ElementPickerWebView` (80%) + collapsible `BottomSheetBehavior` (20%) with step instruction banner, progress chips, captured-selector summary chips, Undo/Skip/Test Parser/Save buttons. Two intent factories: `createIntent()` (fresh) + `createIntentForFix()` (pre-filled existing selectors). |
| `app/src/main/res/layout/activity_visual_rule_builder.xml` | CoordinatorLayout: AppBar → WebView container → instruction banner overlay → bottom sheet with chips + buttons. |
| `app/src/main/res/menu/opt_visual_rule_builder.xml` | Placeholder overflow menu for `VisualRuleBuilderActivity`. |
| `app/src/main/res/drawable/bg_bottom_sheet_handle.xml` | Pill-shaped drag handle for the bottom sheet. |

### Modified files

| File | Change |
|---|---|
| `AndroidManifest.xml` | Registered `VisualRuleBuilderActivity` (configChanges + adjustResize, not exported). |
| `customsource/ui/UniversalSourceActivity.kt` | Added `binding.btnPickElements.setOnClickListener` — launches `VisualRuleBuilderActivity.createIntent()` with the current URL and name fields. |
| `res/layout/activity_universal_source.xml` | Added `btn_pick_elements` (`OutlinedButton`) above `btn_create` — "🎯 Pick Elements Manually". |
| `explore/ui/ExploreMenuProvider.kt` | Added `R.id.action_add_source_visually` case — launches `VisualRuleBuilderActivity.createIntent()`. |
| `explore/ui/ExploreFragment.kt` | Added `R.id.action_fix_visually` action in `onActionItemClicked` (launches `createIntentForFix`) and shows the item in `onPrepareActionMode` only for single-selected CUSTOM_TEMPLATE sources. |
| `res/menu/opt_explore.xml` | Added `action_add_source_visually` menu item — "Add Source Visually". |
| `res/menu/mode_source.xml` | Added `action_fix_visually` item (hidden by default, shown only for CUSTOM_TEMPLATE in `onPrepareActionMode`). |
| `res/values/strings.xml` | 12 new strings: `visual_rule_builder_title`, `_add_source_visually`, `_pick_elements_manually`, `_fix_source_visually`, `_undo`, `_skip`, `_test`, `_save`, `_review_prompt`, `_test_success`, `_test_failure`, `_saved`. |
| `res/values/colors.xml` | Added `picker_chip_active` (#7C5CFF), `picker_chip_captured` (#3DAD77), `picker_chip_inactive` (#E0DCF0). |
| `res/values/dimens.xml` | Added `picker_bottom_sheet_peek_height` (220dp). |

### User flow summary
1. **Entry** — USB → "🎯 Pick Elements Manually" button, or Explore ⋮ → "Add Source Visually", or Explore long-press CUSTOM_TEMPLATE source → "🔧 Fix Source Visually" (action mode).
2. **Guided tapping** — 5 steps in sequence (manga title → cover → card container → chapter title → page image). Each tap: JS generates selector, highlights matched elements in purple (selected = solid, siblings = dashed), auto-detects parent card container.
3. **Smart warnings** — navigation elements, logos, and ads trigger warning toasts; only-1-match triggers "try outer container" hint.
4. **Review** — captured selectors shown as dismissible chips. Tap any chip to retap that field.
5. **Test** — "Test Parser" fetches the site with Jsoup and counts matching items. Shows "✓ Found N manga!" or error.
6. **Save** — creates `ParserTemplate` JSON + `CustomSource(type=CUSTOM_TEMPLATE)` via existing `TemplateHtmlParser` infrastructure. No new parser needed.

### DO NOT TOUCH
- `TemplateHtmlParser.kt` core logic — not modified. VRB output is consumed as a normal `ParserTemplate` JSON.
- All existing custom source types and parsers.

### Architecture notes
- JS selector generation prefers: stable classes → data attributes → parent+child → tag-only (last resort).
- `AdBlock` integration uses `shouldLoadUrl(url, baseUrl)` — matches existing `BrowserSourceActivity` pattern.
- `VisualRuleBuilderActivity` replaces its `web_view_container` FrameLayout at runtime with a fully configured `ElementPickerWebView` instance (to pass constructor-time callbacks).
- Pre-filled selectors (for fix flow) are encoded as `STEP_NAME=selector` pairs joined by `|` in the intent extras — avoids a JSON dependency in the intent.

---

## Session 5 (Jun 25 2026) — BrowserSourceActivity WebView thread crash fix

### Problem reported
`BrowserSourceActivity` crashed immediately when tapping a browser source favicon:

```
java.lang.RuntimeException: A WebView method was called on thread 'ThreadPoolForeg'.
All WebView methods must be called on the same thread.
at BrowserSourceActivity$setupWebView$2.shouldInterceptRequest
```

### Root cause
`shouldInterceptRequest()` is called by Android on a **background thread** (IO thread pool).  
Inside the override, two calls to `view.url` (i.e. `WebView.getUrl()`) were made:

```kotlin
adBlock.shouldLoadUrl(request.url.toString(), view.url)   // line 182
val pageUrl = view.url ?: ""                               // line 186
```

Calling any `WebView` method from a non-main thread throws `RuntimeException` unconditionally.

### Fix applied — `BrowserSourceActivity.kt`

1. **Added `@Volatile private var currentUrl: String = ""`** — a thread-safe snapshot field
   updated exclusively on the main thread.

2. **`onPageStarted()` (main thread)** — sets `currentUrl = it` as the very first action so
   subsequent resource requests for the new page see the correct URL immediately.

3. **`shouldInterceptRequest()` (background thread)** — replaced both `view.url` calls with
   `currentUrl`. No other WebView methods are called in this callback.

### Files changed

| File | Change |
|---|---|
| `browsersource/ui/BrowserSourceActivity.kt` | Added `@Volatile currentUrl`; `onPageStarted` updates it; `shouldInterceptRequest` reads it instead of `view.url`. |

### Rule for future agents
**Never call any `WebView` method inside `shouldInterceptRequest()`.** This callback runs on
a background thread by design. Safe pattern: use `@Volatile` fields updated in main-thread
callbacks (`onPageStarted`, `onPageFinished`, `shouldOverrideUrlLoading`) and read those
fields inside the background callbacks.

---

## Session 6 (Jul 13 2026) — Opt-in Sentry crash reporting (IzzyOnDroid compliance)

### Requirement
Add opt-in crash reporting via Sentry. Sentry must be **completely disabled by default**
and only activate after explicit user consent. Required for IzzyOnDroid distribution compliance.

### What was already in place (prior sessions)

| Item | Status |
|---|---|
| `BaseApp.kt` — Sentry only called inside `if (settings.isCrashAnalyticsEnabled) { initializeSentry() }` | Already done |
| `AppSettings.isCrashAnalyticsEnabled` — key `"crash_analytics_enabled"`, default `false` | Already done |
| `pref_services.xml` — `SwitchPreferenceCompat` for `crash_analytics_enabled` | Already done |
| Strings `crash_reporting`, `crash_reporting_summary`, `privacy` | Already done |

### What this session adds

#### New files

| File | Purpose |
|---|---|
| `settings/privacy/CrashReportingConsentDialog.kt` | `DialogFragment` shown once on first cold launch. "Allow" sets `isCrashAnalyticsEnabled=true` + `isCrashConsentShown=true`. "No Thanks" sets only `isCrashConsentShown=true`. Sentry never starts until the user taps "Allow". |
| `settings/privacy/PrivacySettingsFragment.kt` | Dedicated Privacy settings screen. Loads `pref_privacy.xml`. Has click listener to open privacy policy URL in browser. |
| `res/xml/pref_privacy.xml` | Preference XML with `SwitchPreferenceCompat` (key `crash_analytics_enabled`) and a Privacy Policy link preference. |

#### Modified files

| File | Change |
|---|---|
| `core/prefs/AppSettings.kt` | Added `isCrashConsentShown` property (key `"crash_consent_shown"`, default `false`) and `KEY_CRASH_CONSENT_SHOWN` constant. |
| `main/ui/MainViewModel.kt` | Added `onShowCrashConsent` event flow. `init {}` block checks `!settings.isCrashConsentShown` and fires the event so MainActivity can show the dialog. |
| `main/ui/MainActivity.kt` | Imported `CrashReportingConsentDialog`; observes `viewModel.onShowCrashConsent` and calls `CrashReportingConsentDialog.show(supportFragmentManager)`. |
| `res/xml/pref_root.xml` | Added Privacy entry (`ic_data_privacy` icon, `PrivacySettingsFragment`) before the About entry. |
| `settings/RootSettingsFragment.kt` | Added `bindPreferenceSummary("privacy", R.string.crash_reporting)` so the Privacy row shows a subtitle in Settings root. |
| `res/values/strings.xml` | Added `crash_consent_title`, `crash_consent_message`, `crash_consent_allow`, `crash_consent_decline`, `privacy_policy`, `privacy_policy_summary`. |

### Privacy flow

```
1. Cold start → BaseApp.onCreate()
     → settings.isCrashAnalyticsEnabled == false (default)
     → Sentry NOT initialized ✅

2. MainActivity resumes → MainViewModel.onShowCrashConsent fires (first launch only)
     → CrashReportingConsentDialog appears
     → User taps "Allow"  → isCrashAnalyticsEnabled=true, isCrashConsentShown=true
     → User taps "No Thanks" → isCrashAnalyticsEnabled stays false, isCrashConsentShown=true
     → Dialog never shown again on subsequent launches

3. On next cold start (if user allowed):
     → settings.isCrashAnalyticsEnabled == true
     → initializeSentry() called ✅

4. User can toggle any time: Settings → Privacy → Crash Reporting switch
     → Takes effect after next cold start
```

### Privacy policy URL
`https://tsukiapp.vercel.app/privacy`

### Key names
| Key | Default | Meaning |
|---|---|---|
| `crash_analytics_enabled` | `false` | Whether user opted in to crash reporting |
| `crash_consent_shown` | `false` | Whether the first-launch dialog was shown (prevents re-showing) |

---

## Session — WebView performance + Cloudflare captcha never completing (Jul 13 2026)

### Problems reported
1. WebView browsing (browser source, visual rule builder, Cloudflare challenge) felt slow:
   no ad-blocker fast path, no shared WebView performance tuning, no connection
   pre-warming, cold WebView process on first use, default progress-bar styling.
2. The Cloudflare captcha challenge (`CloudFlareActivity`) frequently never completes —
   clearance is granted by Cloudflare but the app keeps looping/retrying.

### Root causes for "captcha never completes"
- `CloudFlareClient`/`CloudFlareInterceptClient` were constructed with a non-null
  `AdBlock`, and `BrowserClient.shouldInterceptRequest` ad-blocks **every** request
  unconditionally — including Cloudflare's own challenge-platform requests. A
  false-positive block there is indistinguishable from "challenge never resolves".
- WebView's default UA carries the `wv` marker (`...Version/4.0 Chrome/... wv)...`),
  a well-known bot-detection signal; nothing stripped it when no explicit UA
  override was configured (`DEFAULT_ANDROID`).
- No anti-automation JS was ever injected into the challenge page (`navigator.webdriver`,
  empty `navigator.plugins`, etc. — all default WebView tells).
- No timeout/feedback during solving, so a genuinely stuck challenge looked
  identical to a slow-but-working one.

### Fixes applied
- **`adBlock` made nullable** on `BrowserClient` (already was), `CloudFlareClient`,
  `CloudFlareInterceptClient`. `CloudFlareActivity` now constructs both with
  `adBlock = null` — ad-blocking is off only for the Cloudflare challenge page itself.
- **UA marker stripping, not a hardcoded UA string.** Deliberate deviation from the
  fix spec's suggestion to hardcode a static Chrome desktop/mobile UA: hardcoding
  goes stale as Chrome/WebView versions ship and is itself a distinguishing signal.
  Instead, `WebViewPerformanceConfigurator.stripWebViewMarker()` regex-strips the
  `; wv` / ` wv)` token from `WebSettings.getDefaultUserAgent(context)`, preserving
  the real device/Chrome fingerprint. Wired into:
  - `Android.kt`'s `WebView.configureForParser()` (was: leave UA untouched when
    override is null; now: strip marker from the real default UA instead)
  - `WebViewSettingsManager.resolvedUserAgent()` for the `DEFAULT_ANDROID` case
    (was: return `null`/no override; now: return the stripped UA)
- **New `browser/cloudflare/CloudflareWebView.kt`** — Cloudflare-specific WebView
  settings (`javaScriptCanOpenWindowsAutomatically`, `allowContentAccess = true`,
  layered on top of `WebViewPerformanceConfigurator`) plus `injectAntiDetectionJs()`
  (masks `navigator.webdriver`, empty `navigator.plugins`/`navigator.languages`,
  missing `window.chrome`), called from `CloudFlareClient.onPageStarted` on every
  page load of the challenge.
- **New `browser/cloudflare/CloudflareCookieSyncer.kt`** — deliberately *not* a
  cookie-copying mechanism. Read `AndroidCookieJar`: it already reads/writes
  straight through `android.webkit.CookieManager`, the same store the WebView
  itself uses, so OkHttp sees `cf_clearance` the instant Cloudflare's JS sets it —
  no manual sync needed. This class is a named, documented wrapper around
  `CloudFlareHelper.getClearanceCookie` so `CloudFlareClient` doesn't inline that
  logic, and so a future agent doesn't reintroduce a redundant sync mechanism
  (the fix spec's README suggested one; it would have been dead code here).
- **Solving banner + 30s timeout watchdog** added to `CloudFlareActivity`
  (`showSolvingBanner()`, `startTimeoutWatchdog()`) — surfaces a retry action via
  Snackbar if clearance hasn't landed in time, instead of a silently "frozen" WebView.

### Performance fixes applied
- **New `core/network/webview/WebViewPerformanceConfigurator.kt`** — single place
  for `LOAD_DEFAULT` cache mode, Safe Browsing disabled (API 26+), mixed-content
  compatibility mode, legacy `RenderPriority.HIGH`, `LAYER_TYPE_HARDWARE`. Applied
  in `BaseBrowserActivity.onCreate()` (covers `BrowserActivity`/`CloudFlareActivity`),
  `BrowserSourceActivity.setupWebView()`, `ElementPickerWebView.init`, and
  `CloudflareWebView.configure()` — one call site per WebView instead of copy-paste.
- **`RulesList` (ad-block) rewritten**: plain domain rules (no modifiers) now live
  in `HashSet`s for O(1) lookup instead of a linear scan over every parsed rule;
  rules with modifiers/paths keep the linear list since they need full evaluation.
  `registrableDomain()` memoizes `HttpUrl.topPrivateDomain()` per host.
- **`AdBlock.warmUp()`** (new) — eagerly parses the blocklist on a background
  thread; **`WebViewPrewarmer.prewarm()`** (new) — spins up and tears down a
  throwaway `WebView` once at startup. Both called from `BaseApp.onCreate()` on
  `Dispatchers.IO`, off the critical path, so the first real WebView opened later
  doesn't pay the parse/process-cold-start cost inline.
- **New `core/network/ConnectionWarmer.kt`** — Hilt-injectable wrapper around the
  `@BaseHttpClient OkHttpClient`; fires a fire-and-forget HEAD request to warm
  DNS/TLS. Called from `ExploreFragment.onItemClick()` right before launching
  `BrowserSourceActivity` for a browser-source item — this is the closest real
  equivalent in this codebase to "warm the connection on tap", since there is no
  favicon-tap affordance to hook into.
- **Progress bar color** — reused the existing `picker_chip_active` (`#7C5CFF`)
  color resource, which already matches the requested neon purple, on the
  `LinearProgressIndicator` in `activity_browser.xml` and `activity_browser_source.xml`
  (`app:indicatorColor`, transparent `app:trackColor`). No new color defined.
- **`android:hardwareAccelerated="true"`** added explicitly to the 4
  WebView-hosting activities: `BrowserSourceActivity`, `VisualRuleBuilderActivity`,
  `BrowserActivity`, `CloudFlareActivity`. `UniversalSourceActivity` was **not**
  touched — confirmed it does not directly instantiate a WebView (delegates to
  `SmartPageFetcher`/`JsRenderFetcher` for JS-render detection, not a persistent
  in-activity WebView).

### Deviations from the fix spec's literal suggestions (intentional)
1. No hardcoded static Chrome UA string — UA marker stripping instead (see above).
2. No standalone `CloudflareCookieSyncer` that copies cookies between stores —
   the existing `AndroidCookieJar` already shares Cloudflare's cookies with OkHttp
   automatically via `CookieManager`; a copy mechanism would have been redundant.
3. `UniversalSourceActivity` manifest entry skipped — no direct WebView usage.

### Files changed
| File | Change |
|---|---|
| `core/network/webview/adblock/RulesList.kt` | HashSet fast path for plain domain rules |
| `core/network/webview/adblock/AdBlock.kt` | `warmUp()` |
| `core/network/webview/WebViewPerformanceConfigurator.kt` | NEW — shared WebView perf settings + UA marker stripping |
| `core/network/webview/WebViewPrewarmer.kt` | NEW — startup WebView prewarm |
| `core/network/ConnectionWarmer.kt` | NEW — DNS/TLS warm-up via HEAD request |
| `core/BaseApp.kt` | Injects `AdBlock`, calls `warmUp()` + `WebViewPrewarmer.prewarm()` at startup |
| `core/util/ext/Android.kt` | `configureForParser()` — UA marker stripping when no override |
| `browser/webview/WebViewSettingsManager.kt` | `resolvedUserAgent()` — UA marker stripping for `DEFAULT_ANDROID` |
| `browser/BaseBrowserActivity.kt` | Applies `WebViewPerformanceConfigurator` after `configureForParser` |
| `browser/cloudflare/CloudFlareClient.kt` | Nullable `adBlock`; injects anti-detection JS on every page start; uses `CloudflareCookieSyncer` |
| `browser/cloudflare/CloudFlareInterceptClient.kt` | Nullable `adBlock` |
| `browser/cloudflare/CloudFlareActivity.kt` | Passes `adBlock = null`; `CloudflareWebView.configure()`; solving banner; 30s timeout watchdog |
| `browser/cloudflare/CloudflareWebView.kt` | NEW — CF-specific WebView settings + anti-detection JS |
| `browser/cloudflare/CloudflareCookieSyncer.kt` | NEW — documented wrapper around existing automatic cookie sharing |
| `browsersource/ui/BrowserSourceActivity.kt` | Applies `WebViewPerformanceConfigurator` in `setupWebView()` |
| `customsource/ui/visualpicker/ElementPickerWebView.kt` | Applies `WebViewPerformanceConfigurator` in `init` |
| `explore/ui/ExploreFragment.kt` | Calls `ConnectionWarmer.warm()` before launching `BrowserSourceActivity` |
| `AndroidManifest.xml` | `hardwareAccelerated="true"` on 4 WebView-hosting activities |
| `res/layout/activity_browser.xml`, `activity_browser_source.xml` | Progress bar tinted with `picker_chip_active` |
| `res/values/strings.xml` | `cloudflare_solving_banner`, `cloudflare_timeout_message` |

### Commit & CI
- Commit: `b404c54` pushed to `devel` (parent `8fb0518`).
- CI: "Build Alpha APK" run for `b404c54` completed with conclusion `success` (https://github.com/Space4414/Tsuki/actions/runs/29293973528).

---

## Session 4 (Jul 14 2026) — Universal Manga Site Detection

### Goal
Passively watch WebView browsing in the in-app browser, recognise universal
manga-site patterns (list / detail / reader / search) with **no site-specific
rules**, accumulate confidence per domain across page visits, and prompt the
user to auto-add a working source once confidence is high enough. Spec
provided by the user; explicit "DO NOT TOUCH" list honoured: built-in Kotatsu
sources, `TemplateHtmlParser.kt` core logic, `applicationId`, existing
`CustomSourceType` enum entries, and templates already imported by users.

### New package: `browser/detection/`
| File | Purpose |
|---|---|
| `UniversalPatternDetector.kt` | Pure heuristics: repeated portrait-image "card" grids → manga list; hero image + heading + long paragraph + chapter links → manga detail; text input + submit → search; run of 5+ sequential large images sharing a URL path prefix → chapter reader. No CMS/domain knowledge. |
| `DetectionSession.kt` + `DetectionSessionStore` | Per-domain in-memory session (selectors found so far, confidence, captured image URLs). TTL 30 min, max 20 sessions (oldest evicted), cleared on browser close. `DetectionPromptLevel` enum: NONE(<40) / LEARNING(40-69) / HINT(70-99) / ADD_SOURCE(100+). |
| `UniversalSelectorExtractor.kt` | Converts detected DOM elements into CSS selector strings, and assembles a completed session into a generic parser-template JSON (same shape `ParserTemplateValidator` expects). |
| `MangaSiteDetector.kt` (Hilt `@Singleton`) | Orchestrator. `analyzePage(url, html)` scores a freshly-loaded page; `recordImageUrl(pageUrl, imageUrl)` feeds reader detection from `shouldInterceptRequest`. Persists a "never for this site" domain blocklist (`SharedPreferences`, `tsuki_manga_site_detector`). `createSource(domain)` re-fetches the list page fresh and requires ≥3 manga found before saving a `ParserTemplate` + `CustomSource(type = CUSTOM_TEMPLATE)` — never saves an unvalidated template. `previewSample(domain)` powers "Test First" without creating anything. When confidence is stuck at 70-99, calls `GeminiSelectorAnalyzer` to fill only the missing list/detail selectors (reuses the same Gemini key the user already set in WebView settings, `tsuki_webview_settings` / `wv_gemini_key`) rather than re-running the ai gemini flow. |
| `MangaSitePrompt.kt` | Pure View plumbing for the 3 UI tiers: pulsing "🌙" toolbar icon (Level 1), dismissible banner (Level 2), programmatically-built `BottomSheetDialog` with checklist + Add/Test First/Not now/Never buttons (Level 3). No detection logic. |

### Integration points
- **`BrowserSourceActivity`** (the primary target — `BROWSER_SOURCE` custom
  sources): `onPageFinished` grabs `document.documentElement.outerHTML` via
  `evaluateJavascript` and calls `mangaSiteDetector.analyzePage`;
  `shouldInterceptRequest` feeds image-looking request URLs into
  `recordImageUrl`. Toolbar gained a moon icon + a dismissible banner
  (`activity_browser_source.xml`); Level 3 opens the bottom sheet once per
  domain per session. `onDestroy` calls `clearAllSessions()`.
- **`UniversalSourceViewModel.autoDetect`**: if a warm, high-confidence
  passive-detection session already exists for the entered domain (e.g. the
  user just browsed it), skips the full fetch + CMS-fingerprint pipeline and
  pre-fills `DetectedFields` straight from the session — Universal Source Beta
  becomes instant for sites Tsuki has already been watching.
- **Deliberately NOT wired into `BrowserActivity`** (the legacy generic
  WebView activity): it already has its own, older, equivalent pipeline
  (`LearningSession` + `AiParserGenerator` + the learning banner) built for
  the same purpose. Running both there would double-prompt the user and
  double the detection work per page load. If the two are ever meant to
  converge, that should be its own follow-up, not silently duplicated here.
- **Not implemented this session** (scoped out for time — flagged, not
  silently dropped): auto-launching the Visual Rule Builder pre-filled with
  partial selectors when validation fails (currently just surfaces the
  failure reason in a Snackbar and suggests the Visual Rule Builder by name).

### Design notes / non-obvious decisions
- Detection reuses the WebView's already-rendered DOM (`outerHTML` via JS
  bridge) instead of a second network fetch, so it stays within the <500ms/page
  budget and has zero extra network cost; `createSource`/`previewSample` are
  the only paths that do a fresh OkHttp fetch, and only when the user
  explicitly acts on a Level 3 prompt.
- Confidence scoring intentionally never lets domain/URL keyword bonuses alone
  cross the ADD_SOURCE threshold — list+detail (60) is the minimum required
  combination before those bonuses can push a domain over 100.
- New `CustomSource`s created from detection always use
  `CustomSourceType.CUSTOM_TEMPLATE`, per the "don't add new enum values"
  constraint — matches the existing manual Universal Source Beta fallback
  path and `BrowserActivity`'s AI-learning save path.

### Commit & CI
- Commit: `73de503` pushed to `devel` (rebased onto parent `b60b92f`).
- CI: "Build Alpha APK" run for `73de503` completed with conclusion `success`
  (https://github.com/Space4414/Tsuki/actions/runs/29295688769).

---

## Android 16 Bug Fix Session — July 2026

### Problems reported

A user on Android 16 reported two related bugs in the Library/Favorites system.

---

### Bug 1 — "New Chapters" filter in Favorites tab shows nothing on Android 16

#### Root cause

`FavouritesDao.getCondition()` for `ListFilterOption.Macro.NEW_CHAPTERS` used:

```sql
(SELECT chapters_new FROM tracks WHERE tracks.manga_id = favourites.manga_id) > 0
```

When a manga is in Favorites but has no corresponding row in the `tracks` table
(e.g. the tracker hasn't initialized yet, or tracking was reset), the subquery
returns `NULL`. In SQLite, `NULL > 0` evaluates to `NULL` — not `TRUE` — so
those manga are silently excluded from the filter on every Android version,
but this is especially visible on Android 16 where background restrictions
can delay or skip initial track-record creation.

#### Fix — `FavouritesDao.kt`

Wrapped the subquery with `IFNULL(..., 0)` so a missing track row is treated
as zero new chapters (excluded from the filter) rather than `NULL` (which
silently drops the row from the result set):

```sql
IFNULL((SELECT chapters_new FROM tracks WHERE tracks.manga_id = favourites.manga_id), 0) > 0
```

Note: the `ListSortOrder.NEW_CHAPTERS` sort expression in the same file already
used `IFNULL` correctly — this fix brings the filter condition into parity.

---

### Bug 2 — Feeds tab requires manual refresh instead of auto-fetching on Android 10+

#### Root cause

`FeedFragment` never triggered a chapter check when the Feeds tab came to the
foreground. The only triggers were:
- Swipe-to-refresh (`onRefresh()` → `viewModel.update()`)
- The periodic WorkManager job (which Android 10+ aggressively restricts in
  the background)

`FeedViewModel.content` is already fully reactive via a Room `Flow`, so once
new chapters land in the database the UI updates instantly. The missing piece
was simply the fetch trigger when the user opens the tab.

#### Fix

**`FeedViewModel.kt`**
- Added `lastAutoUpdateTime: AtomicLong` to track when the last check ran.
- `update()` now records the current time in `lastAutoUpdateTime`.
- New `updateIfNeeded()` method: triggers `update()` only if ≥ 15 minutes
  have elapsed since the last check (matching the Android WorkManager minimum
  periodic interval). This prevents flooding the network when the user
  rapidly switches tabs.

**`FeedFragment.kt`**
- Added `override fun onResume()` that calls `viewModel.updateIfNeeded()`.
- This fires every time the Feeds tab becomes visible (app foreground, tab
  switch, back-navigation) and is debounced to at most once per 15 minutes.

#### Files changed

| File | Change |
|---|---|
| `favourites/data/FavouritesDao.kt` | Bug 1: `IFNULL` fix in `NEW_CHAPTERS` filter condition |
| `tracker/ui/feed/FeedViewModel.kt` | Bug 2: `lastAutoUpdateTime`, `updateIfNeeded()`, `AUTO_UPDATE_DEBOUNCE_MS` constant |
| `tracker/ui/feed/FeedFragment.kt` | Bug 2: `onResume()` override calling `updateIfNeeded()` |

### Commit & CI

- Branch: `fix/android16-library-feeds-bugs` (PR against `devel`)
- CI: pending — see GitHub Actions.

---

## Bug Fix Session — July 2026 (4-Bug Batch)

### Summary

Fixed 4 bugs in the Tsuki browser/reader stack, pushed directly to `devel`.

---

### Bug 1 — Detected source doesn't appear in Explore tab after being added

#### Root cause

`BrowserActivity.addCurrentSiteToLibrary()` had an early-return guard:

```kotlin
if (existing != null && existing.type != CustomSourceType.WEBVIEW) { return }
```

This blocked adding a `CUSTOM_TEMPLATE` source when a `BROWSER_SOURCE` already
existed for the same domain, because `BROWSER_SOURCE != WEBVIEW` → the guard
fired → the new source was never added.

Additionally, `CustomMangaSource` entries all showed the generic subtitle
"Custom Source" in the Explore tab, making same-named sources from different
domains indistinguishable.

#### Fix

**`browser/BrowserActivity.kt`**
- Changed early-return condition to `existing.type != WEBVIEW && existing.type != BROWSER_SOURCE`.
  A `BROWSER_SOURCE` entry now no longer blocks the parallel creation of a
  `CUSTOM_TEMPLATE` source for the same domain.

**`core/model/MangaSource.kt` — `getSummary()`**
- `CustomMangaSource` branch now returns `"<domain> · <type label>"` (e.g.
  `"mangadex.org · Browser Source"`) instead of the generic "Custom Source"
  string, disambiguating same-titled entries in the Explore grid/list.

---

### Bug 2 — Cloudflare captcha loops + Google OAuth blocked in WebView

#### Root cause (2A — Cloudflare)

- `CloudflareWebView.injectAntiDetectionJs()` used a minimal JS snippet that
  left the strongest Cloudflare bot-detection signals intact.
- `IntelligentBrowserClient` never injected the anti-bot JS on page start, and
  never bypassed request interception on Cloudflare challenge pages — meaning
  ad-block / domain-blocking could cancel Cloudflare's internal challenge XHRs
  and cause an infinite spinner.

#### Root cause (2B — Google OAuth)

No `shouldOverrideUrlLoading` existed in the WebView client hierarchy; OAuth
URLs (Google, Twitter, Facebook, Discord, GitHub) loaded inside the app WebView
and were always rejected by those providers' embedded-WebView restrictions.

#### Fix

**`browser/cloudflare/CloudflareWebView.kt`**
- Rewrote `ANTI_DETECTION_JS` with the full battery of anti-fingerprinting
  overrides: `navigator.webdriver`, `window.chrome`, `navigator.plugins`,
  `navigator.languages`, `navigator.platform`, `window.outerHeight/Width`,
  Selenium CDC artifact removal, and `navigator.permissions.query` override.
- Added `CLOUDFLARE_USER_AGENT` constant and `applyCloudflareUserAgent(WebView)`
  helper for switching to a Chrome-compatible UA on challenge pages.

**`browser/IntelligentBrowserClient.kt`**
- Overrides `onPageStarted` to inject anti-bot JS on every navigation (before
  page content loads).
- Overrides `onPageFinished` to evaluate `CLOUDFLARE_DETECT_JS` after load;
  if a challenge page is detected: sets `isOnCloudflarePage = true`, switches
  to the Cloudflare UA, and re-injects anti-bot JS.
- Overrides both `shouldInterceptRequest` overloads: if `isOnCloudflarePage`
  is true, returns `null` (pass-through) so none of Cloudflare's internal XHRs
  are blocked.
- Removed all AI parser learning code (see Bug 3).

**`browser/BrowserClient.kt`** (base class used by all browser WebViews)
- Added `shouldOverrideUrlLoading` that detects OAuth URL patterns
  (`accounts.google.com`, `discord.com/oauth2`, `github.com/login/oauth`, etc.)
  and opens them with `Intent.ACTION_VIEW` (system browser / Chrome Custom Tab)
  instead of loading them inside the embedded WebView.

---

### Bug 3 — Two WebView browsers, only one should remain

#### Root cause

`BrowserActivity` contained a complete AI parser learning system ("AI Parser
WebView") alongside the intended Universal Detection FAB system ("Universal
Parser WebView"). The AI system (LearningSession, AiParserGenerator, learning
banner, page classifier callbacks) was activated automatically on every page
load, cluttering the UI and adding dead code weight.

#### Fix

**`browser/BrowserActivity.kt`**
- Removed fields: `learningSession`, `aiParserGenerator`, `generatedParserJson`,
  `isBannerDismissed`.
- Removed methods: `setupLearningBanner()`, `updateLearningBanner()`,
  `onPageClassified()`, `onNewLearningData()`, `showParserCreationDialog()`,
  `saveParserAsSource()`.
- Removed `setupLearningBanner()` call from `onCreate2`.
- Simplified `IntelligentBrowserClient` constructor (no AI params).
- Removed imports: `AiParserGenerator`, `LearningSession`, `PageType`.

**`browser/IntelligentBrowserClient.kt`** (full rewrite)
- Removed constructor params: `learningSession`, `onPageClassified`,
  `onNewLearningData`.
- Removed `processPageForLearning()` and all AI HTML capture logic.
- Kept and enhanced: popup blocker, custom CSS injection, Cloudflare bypass
  (see Bug 2).

**`res/layout/activity_browser.xml`**
- Removed the entire `learningBanner` LinearLayout (including
  `learningBannerMessage`, `learningBannerDismiss`, `learningChecklist`,
  `checkList` TextViews) — ~60 lines of XML.

---

### Bug 4 — Discord RPC silent for WebView/Browser source reading

#### Root causes

1. **Null incognito mode**: `BrowserSourceActivity` built `ReaderIntent` without
   setting `EXTRA_INCOGNITO`, so `isIncognitoMode` started as `null`. Since the
   RPC gate is `isIncognitoMode.value == false`, `null == false` → `false` →
   RPC skipped until incognito resolved asynchronously.
2. **Empty cover URL crash**: Browser-source manga has `coverUrl = ""`. In
   `updateRpcAsync`, `"".toMediaProxyUrl()` calls `getMediaProxyUrl("")` which
   throws/cancels, and because `runCatchingCancellable` re-throws
   `CancellationException`, the entire `updateRpcAsync` coroutine was silently
   cancelled with no RPC update at all.

#### Fix

**`core/nav/ReaderIntent.kt`**
- Added `incognito(enabled: Boolean = true)` overload to `Builder`, replacing
  the old no-arg `incognito()` method. Old callers pass `true` by default;
  `BrowserSourceActivity` now passes `false` explicitly.

**`browsersource/ui/BrowserSourceActivity.kt`**
- Added `.incognito(false)` to the `ReaderIntent.Builder` chain so
  `EXTRA_INCOGNITO` is always set to `false` — the RPC gate fires immediately.

**`scrobbling/discord/ui/DiscordRpc.kt`**
- `largeImage = manga.coverUrl.takeIf { it.isNotBlank() } ?: appIcon` — the
  app icon is used as a fallback when `coverUrl` is blank, preventing the
  `toMediaProxyUrl("")` failure that was silently cancelling the RPC update.
- For `CUSTOM_` sources with `chaptersTotal <= 1` (browser-source reads), the
  `state` string is `"<chapter title> · via Tsuki Browser"` instead of the
  generic "Chapter N of M" which shows "Chapter 1 of 1" for every page.

---

### Files changed in this session

| File | Bug(s) | Change |
|---|---|---|
| `browser/BrowserActivity.kt` | 1, 3 | Bug 1: coexistence check; Bug 3: remove AI fields/methods |
| `core/model/MangaSource.kt` | 1 | `getSummary` shows domain + type for `CustomMangaSource` |
| `browser/cloudflare/CloudflareWebView.kt` | 2 | Full anti-bot JS; `CLOUDFLARE_USER_AGENT` + `applyCloudflareUserAgent()` |
| `browser/IntelligentBrowserClient.kt` | 2, 3 | CF bypass, anti-bot injection, OAuth passthrough; AI removed |
| `browser/BrowserClient.kt` | 2 | `shouldOverrideUrlLoading` for OAuth URL redirect |
| `res/layout/activity_browser.xml` | 3 | Removed `learningBanner` + checklist views |
| `core/nav/ReaderIntent.kt` | 4 | `incognito(Boolean)` overload |
| `browsersource/ui/BrowserSourceActivity.kt` | 4 | `.incognito(false)` in ReaderIntent builder |
| `scrobbling/discord/ui/DiscordRpc.kt` | 4 | Empty cover fallback; browser-source state string |

### Commit & CI

- Branch: `devel` (direct push)
- CI: `Build Alpha APK` — see GitHub Actions.

---

## Session 5 (July 18 2026) — Universal Detection: source not appearing in Explore tab

### Bug reported

Universally detected source doesn't appear in Explore tab after being added:

- User browses a manga site in BrowserSourceActivity
- Universal detection reaches 100% confidence
- Prompt appears: "Add [site] as source?"
- User taps "Add"
- Success/error message shows
- BUT the new source NEVER appears in Explore tab

### Root causes identified

**Root Cause 1 — `findByUrl()` in `MangaSiteDetector.createSource()` blocked BROWSER_SOURCE coexistence**

`createSource()` checked `customSourcesRepository.findByUrl(baseUrl)` and returned
`CreateResult.Error("This site is already in your sources.")` if ANY source with
that URL existed — including the BROWSER_SOURCE the user was browsing from.

Since the detection flow runs inside `BrowserSourceActivity` (which is opened for
an existing BROWSER_SOURCE entry), `findByUrl()` always found the BROWSER_SOURCE
and aborted before saving — so `customSourcesRepository.add()` was never called,
and the Explore tab was never updated.

`BrowserActivity.addCurrentSiteToLibrary()` already handled this correctly by
skipping `BROWSER_SOURCE` and `WEBVIEW` types in its coexistence check. The same
logic was missing from `MangaSiteDetector.createSource()`.

**Root Cause 2 — `parserSourceName` not set on saved `CustomSource`**

`createSource()` saved the `CustomSource` with `parserSourceName = null`. When
`CustomMangaRepository` later tried to load manga for a `CUSTOM_TEMPLATE` source,
it called `ParserTemplateRepository.peekByName(null)` → returned null → no manga
loaded. The source appeared in Explore but clicking it showed nothing.

The fix: set `parserSourceName = template.name` (same name used for the template)
so the link is valid when `CustomMangaRepository.getList()` runs.

### Fix

**`browser/detection/MangaSiteDetector.kt`**

1. Changed the `findByUrl` coexistence check to skip `BROWSER_SOURCE` and `WEBVIEW`
   types — mirrors the logic in `BrowserActivity.addCurrentSiteToLibrary()`.
2. Added `parserSourceName = templateName` to the `CustomSource` constructor so
   `CustomMangaRepository` can find the template at runtime.
3. Added `Log.d("TsukiSourceDebug", …)` at every stage of `createSource()`:
   domain+baseUrl, coexistence check result, template save, source save.

**`browsersource/ui/BrowserSourceActivity.kt`**

Added `Log.d("TsukiSourceDebug", …)` in `showAddSourceSheet()`:
- When user taps "Add" (UI → ViewModel boundary)
- Before calling `createSource()`
- For each `CreateResult` branch (Success / ValidationFailed / Error)

**`customsource/data/CustomSourcesRepository.kt`**

Added `Log.d("TsukiSourceDebug", …)` in `add()`:
- Logs id, name, type, parserSourceName, isEnabled before updating StateFlow
- Logs total source count after StateFlow is updated (confirms emission occurred)

**`core/model/MangaSource.kt`**

Updated `getSummary()` for `CustomMangaSource` to show human-readable subtitles:
- `CUSTOM_TEMPLATE` → `"domain · Auto-detected"` (was `"domain · Custom Template"`)
- `BROWSER_SOURCE` → `"domain · Browser"` (was `"domain · Browser Source"`)

This differentiates the two source types when both appear in the Explore tab for
the same site (e.g. `manhwaread.com · Auto-detected` vs `manhwaread.com · Browser`).

**`explore/ui/ExploreViewModel.kt`**

Added `Log.d("TsukiSourceDebug", …)` in `buildList()` logging `sources.size`
so logcat confirms when the Explore tab's source list is rebuilt after a save.

### Debug log usage

```
adb logcat -s TsukiSourceDebug
```

Expected sequence when "Add" is tapped for a new site:

```
TsukiSourceDebug: showAddSourceSheet.onAddSource: user tapped Add for domain=manhwaread.com
TsukiSourceDebug: showAddSourceSheet: calling mangaSiteDetector.createSource(domain=manhwaread.com)
TsukiSourceDebug: createSource: domain=manhwaread.com baseUrl=https://manhwaread.com
TsukiSourceDebug: createSource: coexistence check passed existingType=BROWSER_SOURCE
TsukiSourceDebug: createSource: saving ParserTemplate name=ManhwaRead id=...
TsukiSourceDebug: createSource: ParserTemplate saved successfully
TsukiSourceDebug: createSource: saving CustomSource name=ManhwaRead id=... parserSourceName=ManhwaRead
TsukiSourceDebug: CustomSourcesRepository.add: id=... name='ManhwaRead' type=CUSTOM_TEMPLATE parserSourceName=ManhwaRead isEnabled=true
TsukiSourceDebug: CustomSourcesRepository.add: StateFlow updated totalSources=N
TsukiSourceDebug: createSource: SUCCESS name=ManhwaRead
TsukiSourceDebug: showAddSourceSheet: createSource SUCCESS name=ManhwaRead
TsukiSourceDebug: ExploreViewModel.buildList: sources.size=N   (← confirms Explore tab rebuilt)
```

### Explore tab reactivity (confirmed correct, no changes needed)

`MangaSourcesRepository.observeEnabledSources()` already chains correctly:

1. `customSourcesRepository.sources` StateFlow emits (triggered by `_sources.value = updated`)
2. `observeExternalSources()` combine fires → calls `getExternalSources()` → includes new `CustomMangaSource`
3. `observeEnabledSources()` combine fires → produces updated `List<MangaSourceInfo>`
4. `ExploreViewModel.createContentFlow()` combine fires → `buildList()` rebuilds the UI list
5. `content` StateFlow emits → Explore tab updates instantly

### Files changed in this session

| File | Change |
|---|---|
| `browser/detection/MangaSiteDetector.kt` | Fix coexistence check; add `parserSourceName`; add debug logs |
| `browsersource/ui/BrowserSourceActivity.kt` | Add debug logs at button tap and all result branches |
| `customsource/data/CustomSourcesRepository.kt` | Add debug log in `add()` |
| `core/model/MangaSource.kt` | Better subtitles: "Auto-detected" / "Browser" |
| `explore/ui/ExploreViewModel.kt` | Add debug log in `buildList()` |

### Commit & CI

- Branch: `devel` (direct push)
- CI: `Build Alpha APK` — see GitHub Actions.

---

## Session 6 (July 18 2026) — Remove old BrowserActivity, keep only BrowserSourceActivity

### Request

User reported two duplicate WebView browser implementations. They want ONLY the
WebView that opens from Explore → ⋮ → Add Browser Source to remain. The second,
general-purpose BrowserActivity must be removed.

### Two implementations identified

| Class | File | Status |
|---|---|---|
| `BrowserSourceActivity` | `browsersource/ui/BrowserSourceActivity.kt` | **KEPT** — the Explore → ⋮ → Add Browser Source browser with URL bar, ad-blocker, chapter detection, and Universal Detection integration |
| `BrowserActivity` | `browser/BrowserActivity.kt` | **REMOVED** — generic in-app browser extending `BaseBrowserActivity`, used by `AppRouter.openBrowser()` |

### Files deleted

| File | Reason |
|---|---|
| `browser/BrowserActivity.kt` | The old generic in-app browser being removed |
| `browser/IntelligentBrowserClient.kt` | Only used by `BrowserActivity`; becomes orphan |
| `browser/learning/AiParserGenerator.kt` | AI learning removed in Session 3; already orphan |

### Files modified

**`AndroidManifest.xml`**
- Removed the `<activity android:name="...BrowserActivity" .../>` declaration.

**`core/nav/AppRouter.kt`**
- Removed `import io.github.landwarderer.futon.browser.BrowserActivity`.
- Changed `browserIntent()` from `Intent(context, BrowserActivity::class.java)` +
  extras to `Intent(Intent.ACTION_VIEW, url.toUri())` — all callers now open the
  URL in the system browser instead of the removed in-app browser.
- `openBrowser(url, source, title)` and `openBrowser(manga)` unchanged; they still
  delegate to `browserIntent()` which now targets the OS browser.

**`core/exceptions/resolve/ExceptionResolver.kt`**
- Removed `import io.github.landwarderer.futon.browser.BrowserActivity`.
- Removed `private val browserActionContract` field (was `registerForActivityResult(BrowserActivity.Contract())`).
- Removed `private suspend fun resolveBrowserAction()` method.
- `InteractiveActionRequiredException` case in `resolve()` now calls `openInBrowser(e.url)` + returns `false`, opening the interactive-action URL in the OS browser instead.

### What is kept untouched

- `BaseBrowserActivity` — still needed by `CloudFlareActivity`, `SourceAuthActivity`,
  `DiscordAuthActivity`
- `BrowserCallback`, `BrowserClient`, `WebViewBackPressedCallback` — shared utilities
  used by `BaseBrowserActivity` subclasses
- `BrowserSourceActivity` and all of `browsersource/` — completely untouched
- Gemini API Key setting in Settings → WebView — already present in `pref_webview.xml`
  / `WebViewSettingsFragment`; no migration needed

### Downstream behaviour after removal

| Previous caller | New behaviour |
|---|---|
| Explore ⋮ menu → WEBVIEW custom source tap | Opens site in OS browser |
| Manga details → "Open in Browser" | Opens manga page in OS browser |
| Error dialog → "Open in browser" | Opens error URL in OS browser |
| Source settings → browser button | Opens source URL in OS browser |
| `ExceptionResolver` `InteractiveActionRequired` | Opens interactive-action URL in OS browser |
| `ExceptionResolver` `EmptyMangaReason.RESTRICTED` | Opens manga URL in OS browser |

### Files changed in this session

| File | Change |
|---|---|
| `browser/BrowserActivity.kt` | **DELETED** |
| `browser/IntelligentBrowserClient.kt` | **DELETED** (orphan) |
| `browser/learning/AiParserGenerator.kt` | **DELETED** (orphan since Session 3) |
| `AndroidManifest.xml` | Removed BrowserActivity `<activity>` declaration |
| `core/nav/AppRouter.kt` | `browserIntent()` → OS browser; removed BrowserActivity import |
| `core/exceptions/resolve/ExceptionResolver.kt` | Removed browserActionContract + resolveBrowserAction; IAE opens OS browser |

### Commit & CI

- Branch: `devel` (direct push)
- CI: `Build Alpha APK` — see GitHub Actions.

---

## Session 6 — Cloudflare CAPTCHA + Google OAuth WebView fixes

### Problems solved

**Problem A — Cloudflare captcha loops forever:**
Cloudflare's JS challenge was being silently broken by ad-block request interception in `BrowserSourceActivity`, and the WebView was fingerprinted as a bot (no `window.chrome`, `navigator.webdriver` exposed, empty plugins list).

**Problem B — Google OAuth blocked in WebView:**
OAuth pages (Google, Twitter, Facebook, Discord, GitHub) explicitly reject embedded WebViews. The existing fallback only used the system browser and was missing several domains and all pattern-based URL matching.

### Fix A — Cloudflare (BrowserSourceActivity + CloudflareWebView)

**A1 — Disable request interception during CF challenge:**
- Added `@Volatile private var isCloudflareChallenge = false` flag to `BrowserSourceActivity`.
- In `shouldInterceptRequest()`: when flag is `true`, immediately return `null` (pass-through) so no CF sub-request is ever blocked.

**A2 — Anti-detection JS on every page load:**
- In `onPageStarted()`: call `CloudflareWebView.injectAntiDetectionJs(view)` on every page. Removes `navigator.webdriver`, adds `window.chrome`, fixes `navigator.plugins`/`languages`/`platform`, scrubs CDC automation artifacts, fixes permissions API.

**A3 — Chrome user agent:**
- Already handled by `WebViewSettingsManager.resolvedUserAgent()` which strips the `" wv"` WebView marker from the default UA. No additional change needed here.

**A4 — Cookie sync:**
- Already handled: `AndroidCookieJar` reads directly from `CookieManager`, so `cf_clearance` is visible to OkHttp the moment Cloudflare sets it — no manual copy needed.
- Added `onCloudflarePassed()` which resets the flag and shows success banner when `cf_clearance` is detected in `onPageFinished`.

**A5 — User guidance banners:**
- `onReceivedHttpError()`: detects CF via HTTP 403/503 + `cf-ray` response header → sets flag + shows banner.
- `isCloudflarePage()`: detects CF via page HTML signals ("just a moment", "checking your browser", "cloudflare", "challenge-platform", "_cf_chl", "cf-ray").
- `showCloudflareBanner()`: indefinite Snackbar — "🛡️ Cloudflare protection detected. Tap the checkbox when it appears to verify."
- `onCloudflarePassed()`: dismisses banner, shows "✓ Verified! This source is now unlocked."

### Fix B — Google OAuth (BrowserClient)

**B1 — Expanded OAuth URL detection:**
- Added `twitter.com/i/oauth`, `www.facebook.com/dialog/oauth` to `OAUTH_DOMAINS`.
- Added pattern-based `OAUTH_PATTERNS`: `/oauth`, `/oauth2`, `/auth/`, `/login/oauth`, `/connect/`, `response_type=code`, `response_type=token`.

**B2 — Chrome Custom Tab with system-browser fallback:**
- `shouldOverrideUrlLoading()`: tries `CustomTabsIntent` first; falls back to `Intent.ACTION_VIEW` if Custom Tab unavailable.

**B3 — User explanation:**
- Shows Toast: "Opening login in Chrome for security. Return to Tsuki after signing in." before opening external browser.

**B4 — CustomTabs dependency:**
- Added `browser = "1.8.0"` to `gradle/libs.versions.toml` `[versions]`.
- Added `androidx-browser` library entry to `[libraries]`.
- Added `implementation libs.androidx.browser` to `app/build.gradle`.

### New strings added

| Key | Value |
|---|---|
| `browser_source_cloudflare_detected` | 🛡️ Cloudflare protection detected. Tap the checkbox when it appears to verify. |
| `browser_source_cloudflare_verified` | ✓ Verified! This source is now unlocked. |

### Files changed

| File | Change |
|---|---|
| `browsersource/ui/BrowserSourceActivity.kt` | Add `isCloudflareChallenge` flag, CF bypass in `shouldInterceptRequest`, anti-detection JS in `onPageStarted`, `onReceivedHttpError` for 403/503 detection, CF content detection + clearance check in `onPageFinished`, helper methods `isCloudflarePage` / `showCloudflareBanner` / `onCloudflarePassed` |
| `browser/BrowserClient.kt` | Chrome Custom Tabs in `shouldOverrideUrlLoading`, expanded `OAUTH_DOMAINS`, new `OAUTH_PATTERNS` list, Toast message before opening |
| `gradle/libs.versions.toml` | Added `browser = "1.8.0"` version + `androidx-browser` library entry |
| `app/build.gradle` | Added `implementation libs.androidx.browser` |
| `app/src/main/res/values/strings.xml` | Added `browser_source_cloudflare_detected` + `browser_source_cloudflare_verified` |

### DO NOT TOUCH (still applies)
- `TemplateHtmlParser.kt`, `CloudflareCookieSyncer.kt` (already correct), `KotatsuParserMatcher.kt`, USB/Universal Source Beta, `applicationId` in `build.gradle`.

### Commit & CI

- Branch: `devel` (direct push)
- CI: `Build Alpha APK` — see GitHub Actions.

---

## Session 7 — Discord RPC missing for Browser/WebView/Custom sources

### Problem

Discord RPC showed nothing when reading manga from a Browser Source, WEBVIEW, CUSTOM_TEMPLATE, KOTATSU_PARSER, MADARA, MANGATHEMESIA, or any other custom source type. It only worked for built-in MangaParserSource.

### Root Cause

`DiscordRpc.updateRpc()` had two bugs:
1. The state text guard `isCustomOrBrowser && state.chaptersTotal <= 1` meant that custom sources with more than one chapter (CUSTOM_TEMPLATE, MADARA, KOTATSU_PARSER, etc.) fell through to the `chapter_d_of_d` format ("Chapter X of Y") with no source name shown.
2. Even for single-chapter browser sources, the state text hardcoded "via Tsuki Browser" instead of using the actual source display name.

### Fix — `DiscordRpc.kt`

Changed the state-text block:

**Before:**
```kotlin
val stateText = if (isCustomOrBrowser && state.chaptersTotal <= 1) {
    "${state.chapter.title?.ifBlank { "Chapter" } ?: "Chapter"} · via Tsuki Browser"
} else {
    context.getString(R.string.chapter_d_of_d, state.chapterNumber, state.chaptersTotal)
}
```

**After:**
```kotlin
val stateText = if (isCustomOrBrowser) {
    val chapterLabel = state.chapter.title?.takeIf { it.isNotBlank() }
        ?: run {
            val n = state.chapter.number
            "Chapter ${if (n % 1f == 0f) n.toInt() else n}"
        }
    "$chapterLabel · ${manga.source.getTitle(context)}"
} else {
    context.getString(R.string.chapter_d_of_d, state.chapterNumber, state.chaptersTotal)
}
```

### Resulting RPC format (all source types)

| Source type | Details | State |
|---|---|---|
| Built-in MangaParserSource | manga title | Chapter X of Y (unchanged) |
| BROWSER_SOURCE | manga title (source name or page og:title) | Chapter title · Source name |
| WEBVIEW | manga title | Chapter title · Source name |
| CUSTOM_TEMPLATE | manga title | Chapter title · Source name |
| KOTATSU_PARSER | manga title | Chapter title · Source name |
| MADARA / MANGATHEMESIA / etc. | manga title | Chapter title · Source name |

Chapter label priority: chapter title (if non-blank) → "Chapter N" (from chapter.number, integer-formatted).

### Files changed

| File | Change |
|---|---|
| `scrobbling/discord/ui/DiscordRpc.kt` | Removed `&& state.chaptersTotal <= 1` guard; replaced hardcoded "via Tsuki Browser" with `manga.source.getTitle(context)`; added chapter-label resolution with number fallback |

### DO NOT TOUCH (still applies)
- `TemplateHtmlParser.kt`, `CloudflareCookieSyncer.kt`, USB/Universal Source Beta, `applicationId` in `build.gradle`.

### Commit & CI

- Branch: `devel` (direct push)
- CI: `Build Alpha APK` — see GitHub Actions.

---

## Session 8 — Strict Cloudflare detection + Chrome Custom Tab bypass

### Problem

The previous session's Cloudflare detection had false positives: the `isCloudflarePage()` HTML scan triggered on any page containing "cloudflare" or "cf-ray" in its source (e.g., pages that mention Cloudflare in their footer or privacy policy). Additionally, the old approach — JS injection + banner snackbar — cannot solve Cloudflare Turnstile because Cloudflare detects WebView at the TLS/HTTP2 network layer *before* JavaScript runs. Chrome Custom Tab uses the real Chromium stack and bypasses this fingerprinting.

### Solution Overview

1. **Strict detection** (`CloudflareDetector.kt`) — requires ALL of: 403/503 HTTP status + ≥2 specific markers (from headers, HTML, cookies) + exact page title match. Eliminates false positives.
2. **Chrome Custom Tab bypass** (`CloudflareBypassManager.kt`) — shows a dialog, opens the blocked URL in real Chrome (which passes Cloudflare's TLS fingerprinting), polls for `cf_clearance` cookie, and automatically reloads the original URL in the WebView when verified.
3. **Cookie sync** — free on Android 7+: Chrome and Android WebView share the same underlying `CookieManager` cookie store, so `cf_clearance` set by Chrome is immediately visible to the WebView.

### NEW FILE: `CloudflareDetector.kt`

`io.github.landwarderer.futon.browser.cloudflare.CloudflareDetector`

Two-stage stateful detector:
- **Stage 1** `recordHttpError(statusCode, headers)` — called from `onReceivedHttpError`. Saves status 403/503 and counts header markers (cf-ray header, cloudflare in Server header).
- **Stage 2** `analyzeHtml(html, title, cookies)` — called from `onPageFinished` inside the JS callback. Counts HTML markers (cdn-cgi/challenge-platform, _cf_chl_opt, cf-turnstile, challenges.cloudflare.com) and cookie markers (__cf_bm, cf_clearance). Returns true only if: status was 403/503 AND total markers ≥ 2 AND title is one of the 5 known CF challenge titles.
- `reset()` — must be called in `onPageStarted` to prevent stale state from bleeding between pages.

### NEW FILE: `CloudflareBypassManager.kt`

`io.github.landwarderer.futon.browser.cloudflare.CloudflareBypassManager`

Full bypass orchestrator:
- `startBypass(blockedUrl)` — shows `MaterialAlertDialogBuilder` dialog with "Verify Now" / "Cancel".
- On "Verify Now": calls `findCctBrowserPackage()` (checks Chrome → Edge → Samsung Internet → Firefox → Opera), launches `CustomTabsIntent` via `CustomTabsServiceConnection` warmup, starts cookie polling.
- **Cookie polling** (`startCookiePolling`): runs in `activity.lifecycleScope` (survives `onPause` but cancelled on `onDestroy`). Checks `CookieManager.getInstance().getCookie(domain)` every 500ms. 2-minute timeout.
- On `cf_clearance` detected: calls `FLAG_ACTIVITY_REORDER_TO_FRONT` to bring activity to foreground, shows "✓ Verified! Loading page…" snackbar, invokes `onComplete(originalUrl)`.
- On timeout: shows timeout snackbar, invokes `onTimeout()`.
- If no CCT browser available: falls back to `Intent.ACTION_VIEW` + shows "Complete verification in your browser" message.
- `cancel()` — cancels polling job; call from `onDestroy`.

### MODIFIED: `BrowserSourceActivity.kt`

| Change | Detail |
|---|---|
| Added imports | `CloudflareBypassManager`, `CloudflareDetector` |
| Removed field | `cfBannerSnackbar: Snackbar?` |
| Added fields | `cloudflareDetector = CloudflareDetector()`, `cloudflareBypassManager: CloudflareBypassManager` |
| `onCreate` | Initializes `cloudflareBypassManager` with callbacks that reset `isCloudflareChallenge` and call `loadUrl()` |
| `onPageStarted` | Calls `cloudflareDetector.reset()` |
| `onReceivedHttpError` | Replaced old detection with `cloudflareDetector.recordHttpError(statusCode, headers)` |
| `onPageFinished` | Replaced `isCloudflarePage()` + `showCloudflareBanner()` block with `cloudflareDetector.analyzeHtml()` → `cloudflareBypassManager.startBypass()`. Removed `onCloudflarePassed()` call (polling handles it now). |
| `onDestroy` | Calls `cloudflareBypassManager.cancel()` |
| Removed methods | `isCloudflarePage()`, `showCloudflareBanner()`, `onCloudflarePassed()` |

### NEW STRINGS in `strings.xml`

| Key | Value |
|---|---|
| `cloudflare_bypass_title` | 🛡️ Security Verification Required |
| `cloudflare_bypass_message` | This site uses Cloudflare protection… |
| `cloudflare_bypass_verify` | Verify Now |
| `cloudflare_bypass_cancel` | Cancel |
| `cloudflare_bypass_verified` | ✓ Verified! Loading page… |
| `cloudflare_bypass_timeout` | Verification timed out. Please try again. |
| `cloudflare_bypass_fallback_message` | Complete verification in your browser and return to Tsuki when done. |

### DO NOT TOUCH (still applies)
- `TemplateHtmlParser.kt`, USB/Universal Source Beta, `applicationId` in `build.gradle`, Google OAuth fix, built-in Kotatsu sources.

### Commit & CI
- Branch: `devel` (direct push)
- CI: `Build Alpha APK` — see GitHub Actions.

---

## Session: Android blur compatibility fix (API 26-30)

### Bug fixed
Background blur (set to 70%) and navigation bar blur (set to 100%) showed no effect on Android 8-10 (API 26-29). Android 11+ worked correctly. `RenderEffect.createBlurEffect()` is API 31+ only and does nothing on older versions.

### Root cause
`GlassEffectHelper.applyBlurBackground()` sets a RenderEffect on the ImageView — a no-op below API 31. The `blurImageView()` fallback ran synchronously on the main thread and could fail silently if the bitmap wasn't yet available in drawable form, or encounter RenderScript compatibility issues on certain OEM Android 8-10 builds.

### What was changed

#### NEW FILE: `core/ui/util/BlurCompat.kt`
Multi-API blur utility (`object BlurCompat`):
- API 31+: returns source unchanged (RenderEffect handles it at the view level)
- API 26-30: `blurWithRenderScript()` — `ScriptIntrinsicBlur` via RenderScript (deprecated but functional through API 30)
- API ≤ 25: `blurWithStackBlur()` — pure-Kotlin Stack Blur; no Android API dependency

#### MODIFIED: `core/ui/util/GlassEffectHelper.kt`
- Added `blurBitmapForBackground(context, srcBitmap, intensity): Bitmap?` — thread-safe; returns null on API 31+. For API 26-30 calls `blurBitmapWithCompat()` (new private helper); for API ≤ 25 delegates to existing `gaussianBlur()` unchanged.
- Added `blurBitmapWithCompat()` — downscales to max 200×300 px → `BlurCompat.blurBitmap()` → upscales back. Much faster than blurring full resolution.
- Updated `blurImageView()` — for API 26-30 now calls `blurBitmapWithCompat()` via BlurCompat; for API ≤ 25 keeps existing `gaussianBlur()` path untouched.
- Added `applyNavigationBarBlur(window, blurRadius, tintOpacity)` — API 31+: `window.setNavigationBarBlurRadius()` + argb tint; API 26-30: edge-to-edge + dark tint (178 alpha); API < 26: dark semi-transparent color (180 alpha).
- Added imports: `android.graphics.Color`, `android.view.Window`, `androidx.core.view.WindowCompat`.

#### MODIFIED: `main/ui/MainActivity.kt`
- `setActivityBackground()`: on API < 31, blur now runs on `Dispatchers.Default` inside `lifecycleScope.launch`. Bitmap is extracted from drawable on the background thread, blurred via `GlassEffectHelper.blurBitmapForBackground()`, then `iv.setImageBitmap(blurred)` + fade animations run on the main thread. On API 31+ fade animations run immediately (RenderEffect persists).
- `applyUiTransparency()`: calls `GlassEffectHelper.applyNavigationBarBlur(window, blurRadius, tintOpacity)` when `settings.isNavBarBlurEnabled` is true.

#### MODIFIED: `res/values/strings.xml`
- `nav_bar_blur_enabled_summary`: added "(full blur on Android 12+, frosted effect on older versions)"
- `background_blur_summary`: added "Full blur requires Android 12+. Enhanced frosted effect on Android 8-11."

### DO NOT TOUCH (unchanged)
- `TemplateHtmlParser.kt`, USB/Universal Source Beta, `applicationId` in `build.gradle`, built-in Kotatsu sources, `BlurBehindView.kt`, existing blur settings UI and sliders.
- `gaussianBlur()` in `GlassEffectHelper.kt` — unchanged; still used for API ≤ 25 code path.
- Android 7 (API 25) blur behaviour is completely untouched.

### Tested API levels (expected)
- API 26 (Android 8): BlurCompat → RenderScript → frosted effect ✓
- API 28 (Android 9): BlurCompat → RenderScript → frosted effect ✓
- API 29 (Android 10): BlurCompat → RenderScript → frosted effect ✓
- API 30 (Android 11): BlurCompat → RenderScript → frosted effect ✓
- API 31 (Android 12): RenderEffect → full blur ✓
- API 33 (Android 13): RenderEffect → full blur ✓

### Commit & CI
- Branch: `devel` (direct push)
- CI: `Build Alpha APK` — see GitHub Actions.

---

## Session (Jul 24 2026) — MangaFire 403 fix + MangaReader.to + RavenScans

### Goal
Fix three broken sources:
1. **MangaFire** — 403 "Missing Token" / "Access Denied" on Android 16
2. **MangaReader.to** — not loading (anti-bot / UA rejection)
3. **RavenScans** — source broken; domain moved from `ravenscans.com` → `ravenscans.org`; misdetected as MANGAREADER type

---

### MangaFire fix — `MangaFireHtmlParser.kt` (all 5 README fixes)

**Root cause:** `MangaFireHtmlParser` was sending requests with `User-Agent: Tsuki/1.0 (Android)`.  MangaFire's Cloudflare layer rejects non-browser UAs immediately.  Additionally, MangaFire embeds a session token (`window.__config`) on the homepage that must be included in subsequent requests.

**Fix 1 — Token extraction with caching:**
- Added `cachedToken: String?` + `tokenFetchedAt: Long` + `TOKEN_TTL = 30 min`
- `getToken()` visits `baseUrl` homepage and extracts (in order):
  1. `window.__config = "..."` — MangaFire's primary token (2026)
  2. `window._token`, `window.csrf`, and other common JS variable patterns
  3. `<meta name="csrf-token" content="...">`
  4. A `Set-Cookie` header containing "token"

**Fix 2 — Token in all requests:**
- `fetchDocument()` now calls `getToken()` and injects `X-CSRF-Token` and `X-Token` headers alongside a full Chrome mobile browser header set (`Accept`, `Accept-Language`, `Referer`, `Origin`, `Sec-Fetch-*`).

**Fix 3 — Auto-retry on 403:**
- `fetchDocumentInternal(url, retry=true)` catches HTTP 403, clears `cachedToken`, and retries once with a fresh token.

**Fix 4 — VRF (not applicable):**
- `MangaFireHtmlParser` is an HTML scraper, not an API client, so there is no VRF parameter to update.

**Fix 5 — Android 16 cookie handling:**
- OkHttp's built-in cookie jar is already used; no manual cookie handling needed.

**Files changed:**
| File | Change |
|---|---|
| `MangaFireHtmlParser.kt` | Rewrote UA → `BROWSER_UA` (Chrome 124 mobile); added `getToken()` with multi-method extraction; added `buildRequest()` with full header set; added 403 auto-retry in `fetchDocumentInternal()`; added `WINDOW_CONFIG_RE`, `JS_TOKEN_PATTERNS`, `META_CSRF_RE` regex constants |

---

### MangaReader.to fix — `MangaReaderHtmlParser.kt`

**Root cause:** Same UA issue — `"Tsuki/1.0 (Android)"` was rejected by the site's anti-bot layer.

**Fix:**
- Replaced `USER_AGENT = "Tsuki/1.0 (Android)"` with `BROWSER_UA` (Chrome 124 mobile).
- Added `Accept` and `Accept-Language` headers to `fetchDocument()`.

**Files changed:**
| File | Change |
|---|---|
| `MangaReaderHtmlParser.kt` | `USER_AGENT` constant → `BROWSER_UA`; `fetchDocument()` now sends `Accept` + `Accept-Language` headers |

---

### RavenScans fix — `AndroidManifest.xml` + `CmsTypeDetector.kt`

**Root cause:**
1. `ravenscans.com` permanently redirected to `ravenscans.org`. Deep-link filter only listed `.com`, so opening `ravenscans.org` links in-app failed.
2. `CmsTypeDetector` misidentified RavenScans as `MANGAREADER` because the site's WP theme CSS path contains the string "mangareader" (`/wp-content/themes/mangareader/`). The generic `html.contains("mangareader")` check at step 18 fired before a structural MangaThemesia check could match. RavenScans actually uses the MangaThemesia WordPress theme.

**Fix 1 — AndroidManifest.xml:**
- Added `<data android:host="ravenscans.org" />` alongside the existing `ravenscans.com` entry.

**Fix 2 — CmsTypeDetector.kt:**
- Added `"ravenscans.com" to CustomSourceType.MANGATHEMESIA` and `"ravenscans.org" to CustomSourceType.MANGATHEMESIA` to `KNOWN_DOMAIN_TYPES` (step 0 fast-path), bypassing the misleading HTML string match entirely.

**Files changed:**
| File | Change |
|---|---|
| `AndroidManifest.xml` | Added `ravenscans.org` as a deep-link host |
| `CmsTypeDetector.kt` | Added `ravenscans.com` + `ravenscans.org` → `MANGATHEMESIA` in `KNOWN_DOMAIN_TYPES` |

---

### DO NOT TOUCH (unchanged)
- `TemplateHtmlParser.kt`, USB/Universal Source Beta, `applicationId` in `build.gradle`, built-in Kotatsu sources, `CloudflareCookieSyncer.kt`, `KotatsuParserMatcher.kt`.

### Commit & CI
- Branch: `devel` (direct push)
- CI: `Build Alpha APK` — see GitHub Actions.

---

## Session (Jul 26 2026) — "Open in Browser" always uses Tsuki's built-in WebView

### Goal
Every "Open in web browser" action inside Tsuki must open Tsuki's own built-in
WebView (`BrowserSourceActivity`) — never Chrome or any external browser.

Exceptions kept external (as required by README):
- Google OAuth / sign-in pages (already handled in `BrowserClient.shouldOverrideUrlLoading`)
- Cloudflare captcha verification (already handled in `CloudflareBypassManager`)
- Explicit "Share" / "Open in External Browser" user-initiated actions (`AppRouter.openExternalBrowser`)
- About / Settings "Learn more" links (intentional, already using `openExternalBrowser`)

---

### Root cause
`AppRouter.browserIntent()` created `Intent(Intent.ACTION_VIEW, url.toUri())` — this
always opened Chrome/system browser.  Every `router.openBrowser(...)` call went through
this function, so ALL "Open in browser" actions launched Chrome.

---

### Changes

**1. New file: `TsukiBrowser.kt`**
Central utility object (`TsukiBrowser.open(context, url, title?)`) that wraps
`BrowserSourceActivity.createDirectIntent()` and starts the activity.
All future in-app URL opening should go through this or `router.openBrowser()`.

**2. `AppRouter.kt` — `browserIntent()` fixed**
Changed from:
```kotlin
Intent(Intent.ACTION_VIEW, url.toUri())   // ← opened Chrome
```
To:
```kotlin
BrowserSourceActivity.createDirectIntent(context, url, title)   // ← Tsuki WebView
```
Added import for `BrowserSourceActivity`.

**3. `BrowserSourceActivity.kt` — three additions**
- New constants `KEY_DIRECT_URL` / `KEY_DIRECT_TITLE` in companion
- New `createDirectIntent(context, url, title?)` factory in companion — creates an Intent
  that opens a URL directly without requiring a saved custom-source entry (sourceId = -1)
- `onCreate()` detects the `KEY_DIRECT_URL` extra and loads the URL immediately, skipping
  the `browserSourceRepository.getLastUrl()` lookup that only applies to saved sources
- `onCreateOptionsMenu()` now inflates `opt_browser_source.xml` (was defined in res but never wired up)
- `onOptionsItemSelected()` handles all 5 menu items:
  - **Open in External Browser** → `router.openExternalBrowser(url)` (Step 6: gives users the choice)
  - **AdBlock toggle** → mirrors the toolbar AdBlock button
  - **JavaScript toggle** → toggles `webViewSettings.isJavaScriptEnabled` + reloads
  - **Desktop mode** → toggles `webViewSettings.isDesktopMode` + reloads
  - **Clear cookies** → `CookieManager.removeAllCookies` + flush

---

### Flow after this fix
```
Source error → "Open in browser" → ExceptionResolver.openInBrowser()
  → router.openBrowser(url, null, null)
  → AppRouter.openBrowser()
  → browserIntent() → BrowserSourceActivity.createDirectIntent()   ✓ WebView
```

### Files changed
| File | Change |
|---|---|
| `core/nav/TsukiBrowser.kt` | **New** — central WebView-launcher utility |
| `core/nav/AppRouter.kt` | `browserIntent()` now returns `BrowserSourceActivity.createDirectIntent()` |
| `browsersource/ui/BrowserSourceActivity.kt` | Direct-URL mode; `createDirectIntent()`; overflow menu inflated & handled |

### DO NOT TOUCH (unchanged)
- `BrowserClient.kt` — OAuth redirect to Chrome (correct, keep)
- `CloudflareBypassManager.kt` — CF captcha Custom Tab (correct, keep)
- `AppRouter.openExternalBrowser()` — deliberate external-browser launcher (keep)
- TemplateHtmlParser.kt, applicationId in build.gradle, built-in Kotatsu sources

### Commit & CI
- Branch: `devel` (direct push)
- CI: `Build Alpha APK` — see GitHub Actions.

---

## JAR Plugin System — July 2026

### Summary

Implemented a complete JAR plugin system for Tsuki, compatible with the Usagi/UMA plugin ecosystem (`com.github.UsagiApp:core-exts:1.0.3`). This is a **pure addition** — no existing sources, parsers, or `applicationId` were touched.

---

### Architecture

The plugin system lives entirely in a new package:
`app/src/main/kotlin/io/github/landwarderer/futon/plugins/`

| Layer | Files |
|---|---|
| **domain** | `Plugin.kt` — `@Serializable` data class (id, name, version, author, description, jarPath, githubRepo, isEnabled, installedAt, lastUpdated, sourceCount) |
| | `PluginMangaSource.kt` — implements `MangaSource`; name prefix `PLUGIN_<pluginId>_<sourceName>` |
| | `LoadedPlugin.kt` — in-memory representation: metadata + sources + pluginInstance (Any?) + classLoader |
| **data** | `PluginRepository.kt` — `@Singleton`; persists plugin list to SharedPreferences as JSON; manages `filesDir/plugins/` |
| | `PluginLoader.kt` — `@Singleton`; DexClassLoader wrapper; reads `META-INF/plugin.json`; reflection-based entry-class discovery (Usagi ecosystem naming) |
| | `PluginDownloader.kt` — `@Singleton`; OkHttp-based GitHub Releases downloader; `@MangaHttpClient` qualifier; `downloadFromGithub()`, `fetchLatestReleaseInfo()`, `checkForUpdate()` |
| | `PluginMangaRepository.kt` — implements `MangaRepository`; delegates all calls via reflection; every call in `runCatching` |
| | `PluginManager.kt` — `@Singleton`; coordinates loader + repository; exposes `StateFlow<List<PluginMangaSource>>`; reloads on `PluginRepository.plugins` change |
| **work** | `PluginUpdateNotificationHelper.kt` — `plugin_updates` notification channel; per-plugin update notifications |
| | `PluginUpdateWorker.kt` — `@HiltWorker`; daily `PeriodicWork`; checks each plugin GitHub repo; nested `Scheduler` class |
| **ui** | `ManagePluginsActivity.kt` — `BaseActivity<ActivityManagePluginsBinding>`; `@AndroidEntryPoint`; hosts fragment |
| | `ManagePluginsFragment.kt` — RecyclerView plugin list; FAB to add; file picker for `.jar` via `OpenDocument`; delete confirm dialog |
| | `ManagePluginsViewModel.kt` — `@HiltViewModel`; exposes plugins StateFlow; `setEnabled()`, `removePlugin()` |
| | `AddPluginSheet.kt` — `BottomSheetDialogFragment`; GitHub repo mode (pre-fills `InvalidDavid/UMA`) + file URI mode; download progress; preview step before confirm |
| | `AddPluginViewModel.kt` — `@HiltViewModel`; `previewFromUri()`, `previewFromGithub()`, `confirmInstall()`, `reset()`; sealed `AddPluginUiState` |
| | `PluginsAdapter.kt` — `ListAdapter<Plugin>`; toggle switch; three-dot popup menu (Update, Delete) via `opt_plugin_item.xml` |

---

### New resource files

| File | Purpose |
|---|---|
| `res/layout/activity_manage_plugins.xml` | `FrameLayout` container for fragment host |
| `res/layout/fragment_manage_plugins.xml` | `CoordinatorLayout` with RecyclerView + empty-state view + FAB |
| `res/layout/item_plugin.xml` | `MaterialCardView` plugin row: name/version/author/sources, enable switch, three-dot menu |
| `res/layout/sheet_add_plugin.xml` | Bottom sheet: GitHub repo input, progress bar, preview card, action buttons |
| `res/menu/opt_plugin_item.xml` | Per-item three-dot popup: Update, Delete |
| `res/drawable/ic_more_vert.xml` | Vector drawable — Material Design vertical three-dot icon |
| `res/values/strings.xml` (additions) | 20+ new strings: `manage_plugins`, `add_plugin`, `no_plugins_installed`, `plugin_install`, `plugin_update_available`, etc. |
| `res/values/plurals.xml` (addition) | `plugin_sources_count` plural |

---

### Modifications to existing files

| File | Change |
|---|---|
| `app/build.gradle` | Added `implementation("com.github.UsagiApp:core-exts:1.0.3")` |
| `res/menu/opt_explore.xml` | Added `action_manage_plugins` item ("Manage Plugins") |
| `explore/ui/ExploreMenuProvider.kt` | Handle `action_manage_plugins` → `router.openManagePlugins()` |
| `core/parser/MangaRepository.kt` Factory | Added `PluginManager?` to constructor; handles `PluginMangaSource` branch and `PLUGIN_` name prefix fallback |
| `explore/data/MangaSourcesRepository.kt` | Injected `PluginManager?`; `pluginSources` flow combined into `observeEnabledSources()`; `getExternalSources()` appends plugin sources |
| `res/xml/pref_sources.xml` | Added "Manage Plugins" preference screen + `plugin_auto_update` switch + `plugin_clear_cache` preference |
| `settings/sources/SourcesSettingsFragment.kt` | `onPreferenceTreeClick` handles `"manage_plugins"` → `router.openManagePlugins()` |
| `core/nav/AppRouter.kt` | Added `fun openManagePlugins()` |
| `AndroidManifest.xml` | Registered `ManagePluginsActivity` |

---

### Important notes for future agents

- **Reflection-based loader**: `PluginLoader` uses reflection to find the plugin entry class (tries known Usagi ecosystem class names). The core-exts interface was not directly readable at implementation time; revisit once `UsagiApp/core-exts` is accessible.
- **`PluginManager` is `Optional`**: Injected as `? = null` in `MangaRepository.Factory` and `MangaSourcesRepository` so Hilt's optional injection works without extra `@BindsOptionalOf` boilerplate. Hilt resolves `@Singleton` classes automatically.
- **OkHttpClient qualifier**: `PluginDownloader` uses `@MangaHttpClient` (defined in `core/network/HttpClients.kt`) — required for the OkHttp client bound in `NetworkModule`.
- **ic_notification drawable**: `PluginUpdateNotificationHelper` uses `R.drawable.ic_notification` which already exists in the repo.

### DO NOT TOUCH
- Existing parsers, TemplateHtmlParser, KotatsuParserMatcher, USB/Universal Source Beta, BrowserSource, Universal Parser Detection, kotatsu-parsers-redo, `applicationId`

---

## CI Repair — July 27 2026

### Failed workflows investigated

The three failed Alpha builds were consecutive runs for commits `629b9b3`, `feff01f`,
and `4a219e8`. Their logs were inspected through GitHub Actions:

1. **`629b9b3` — duplicate classes**
   Adding `com.github.UsagiApp:core-exts:1.0.3` to the application dependency graph
   bundled classes also provided by `com.github.clquwu:kotatsu-parsers-redo`, causing
   `checkAlphaReleaseDuplicateClasses` to fail with hundreds of duplicate-class errors.
   The dependency was removed in `feff01f`. The plugin loader is reflection-based and
   does not need `core-exts` at application compile time.

2. **`feff01f` — missing drawable**
   `sheet_add_plugin.xml` referenced the non-existent `@drawable/ic_content_copy`.
   This was removed in `4a219e8`; no replacement icon is required for the repository
   input.

3. **`4a219e8` — plugin screen Kotlin compilation**
   `ManagePluginsFragment` overrode `onViewCreated`, which is final in `BaseFragment`,
   and accessed nullable `viewBinding` directly. It now initializes through
   `onViewBindingCreated(binding, savedInstanceState)` and uses the non-null binding
   supplied by that callback. `ManagePluginsViewModel` also attempted to redeclare
   `BaseViewModel.onError` with an incompatible event type; it now emits failures
   through the inherited `errorEvent`, preserving the repository's standard
   `EventFlow<Throwable>` error path.

### Verification

- `git diff --check` passes.
- The matching local Gradle task was attempted, but this environment has no Android
  SDK (`ANDROID_HOME`/`sdk.dir` is unavailable). Final verification is delegated to
  the pushed `Build Alpha APK` workflow.

The first repair workflow cleared the duplicate-class, resource-linking, and plugin
screen lifecycle errors, but CI then reported that `BaseFragment` also requires its
`OnApplyWindowInsetsListener` contract. The fragment now applies the system-bar top
inset to its root view and consumes the system-bar insets, matching the behavior of
the surrounding fragment implementations.

The next workflow compiled the plugin screen and reached Hilt generation, where
`PluginUpdateNotificationHelper` failed with a missing unqualified `Context` binding.
Its constructor now uses `@ApplicationContext`, matching the other application-scoped
plugin services.

### Final CI result

- Commit `3a35ca2` — `Build Alpha APK` run `30260273395`: **success**
- Successful run: https://github.com/TsukiAndroid/Tsuki/actions/runs/30260273395


---

## Session — July 30 2026 — WebView-as-Source Phase 1 & Phase 2

### Context

Implementing the **WebView-as-Source** system: a parallel data layer that lets users add
any manga site URL as a source, read it in an embedded WebView, and get the full
meta-layer (library, progress, AniList sync, notifications) without any HTML parsing.

Full spec lives in `CONTEXT.md` and `ROADMAP.md` at the repo root.

---

### Phase 1 — Data Foundation

**Goal:** Pure data layer. No UI. Every later phase depends on this.

#### New files

| File | Purpose |
|---|---|
| `core/db/entity/WebViewSourceEntity.kt` | Room entity (`webview_sources` table) — 14 columns covering URL, title, cover, chapter pattern, progress, AniList/MAL IDs, timestamps |
| `core/db/dao/WebViewSourceDao.kt` | Full DAO: observe all/by-id, get all, insert/update/upsert/delete, `updateProgress()`, `updateLatestKnownChapter()`, `updateAnilistLink()` |
| `core/db/migrations/Migration28To29.kt` | Creates `webview_sources` table; follows the project's class-based migration pattern |
| `webviewsource/data/WebViewSourceRepository.kt` | Business-logic wrapper over the DAO; `idFromUrl()` hashes URLs to stable `Long` IDs |
| `webviewsource/data/WebViewSourceModule.kt` | Hilt `@Module` providing `WebViewSourceDao` from `MangaDatabase` |

#### Modified files

| File | Change |
|---|---|
| `core/db/MangaDatabase.kt` | `DATABASE_VERSION` 28 → 29; `WebViewSourceEntity::class` added to `@Database` entities list; `abstract fun webViewSourceDao(): WebViewSourceDao` added; `Migration28To29()` added to `getDatabaseMigrations()` |

#### Key decisions

- `WebViewSourceEntity` is a **parallel** entity — it does NOT extend or modify `MangaEntity`.
- Migration follows the class-based pattern (`class Migration28To29 : Migration(28, 29)`) in a dedicated file under `core/db/migrations/`, consistent with all prior migrations.
- DAO accessor is named `webViewSourceDao()` (no `get` prefix) to match the Phase 1 spec; all other DAOs use `get` prefix — keep this inconsistency in mind.
- `DATABASE_VERSION` constant lives at the top of `MangaDatabase.kt` as a standalone `const val`.

#### CI result

- Commit `c9a1852` — `Build Alpha APK`: **success** (~9 min)

---

### Phase 2 — Add WebView Source UI

**Goal:** Bottom sheet where the user pastes a manga URL. App auto-fetches OG title +
cover, detects the chapter URL pattern, user confirms and saves.

#### New files

| File | Purpose |
|---|---|
| `webviewsource/data/OgTagFetcher.kt` | OkHttp-based OG tag scraper; regex extracts `og:title`, `og:image`, `<title>`; returns `OgData(title, imageUrl, siteUrl)`; uses `@BaseHttpClient` qualifier; blocking `execute()` wrapped in `withContext(Dispatchers.IO)` |
| `webviewsource/data/ChapterPatternDetector.kt` | Detects chapter URL pattern from a single URL; replaces chapter number with `{N}`; also provides `buildUrl()` and `extractChapter()` helpers used by later phases |
| `webviewsource/ui/AddWebViewSourceViewModel.kt` | `@HiltViewModel` extending `BaseViewModel`; `fetchUrl()` calls `OgTagFetcher` then `ChapterPatternDetector`; `save()` builds and upserts a `WebViewSourceEntity`; state is a `StateFlow<AddSourceUiState>` |
| `webviewsource/ui/AddWebViewSourceSheet.kt` | `@AndroidEntryPoint BottomSheetDialogFragment`; ViewBinding on `SheetAddWebviewSourceBinding`; observes ViewModel state with `repeatOnLifecycle(STARTED)`; shows progress, populates title/cover/pattern fields, shows error Snackbar, dismisses on save |
| `res/layout/sheet_add_webview_source.xml` | Sheet layout: URL field + Fetch button, `LinearProgressIndicator`, collapsible details section (cover `CoilImageView` + title field + pattern field + pattern hint), Cancel + Save buttons |

#### Modified files

| File | Change |
|---|---|
| `res/menu/opt_explore.xml` | Added `action_add_webview_source` menu item ("Add WebView Source") |
| `explore/ui/ExploreMenuProvider.kt` | Import + `R.id.action_add_webview_source` handler → shows `AddWebViewSourceSheet` |
| `res/values/strings.xml` | Added 9 new strings: `add_webview_source`, `webview_source_url_hint`, `webview_source_fetch`, `webview_source_title_hint`, `webview_source_pattern_hint`, `webview_source_pattern_info`, `webview_source_fetch_error`, `webview_source_url_required`, `webview_source_cover_desc` |

#### Key decisions

- **Sheet extends `BottomSheetDialogFragment` directly** (not `BaseFragment`). The `onViewCreated` is final only in `BaseFragment`; `BottomSheetDialogFragment` has no such restriction, so `onViewCreated` is used normally here.
- **`@BaseHttpClient` qualifier** for `OgTagFetcher`'s `OkHttpClient` injection — the correct qualifier is defined in `core/network/HttpClients.kt` alongside `@MangaHttpClient` and `@ContentHttpClient`.
- **`OgTagFetcher` is `@Singleton`** and does not hold state — safe to inject everywhere.
- **`ChapterPatternDetector` is an `object`** (no injection needed) — pure utility called from both ViewModel and later phases (Phase 3 reader, Phase 6 notification worker).
- Entry point is `ExploreMenuProvider` → overflow menu item. Later phases may promote this to a FAB or dedicated button once the Library tab is integrated (Phase 4).

#### Important notes for future agents

- The `DetailSection` (`layout_details`) starts `GONE` and becomes `VISIBLE` when `state.title` is non-empty — do not remove this toggling logic.
- `etTitle` and `etPattern` are only pre-filled **once** (when empty) to allow user edits to persist across recompositions.
- `OgTagFetcher.fetch()` returns `null` on any network/parse error — the ViewModel sets `fetchError = true` and the sheet shows a Snackbar. No crash.
- `R.string.save` and `R.string.cancel` already exist — do not add duplicates.

#### DO NOT TOUCH
- `kotatsu-parsers-redo`, existing parsers, `BrowserSource` system, `core-exts` dependency

---

## Session — July 30 2026 — WebView-as-Source Phase 3

### Context

Continuing the **WebView-as-Source** system. Phase 1 (data layer) and Phase 2 (Add
Source UI) are merged and CI-green. Phase 3 delivers the full-screen WebView reader
with JavaScript-driven progress tracking and resume.

Full spec lives in `CONTEXT.md`, `ROADMAP.md`, and `Phase-3.md` at the repo root.

---

### Phase 3 — WebView Reader + Progress Tracking

**Goal:** A full-screen `Activity` that opens any URL in a WebView. A JavaScript
bridge reports scroll position back to Kotlin every 2 s. Progress is saved to Room
every 5 s and on pause. Resume: reloads `lastReadUrl` and scrolls to the saved
percentage.

---

#### New files

| File | Purpose |
|---|---|
| `webviewsource/ui/reader/WebViewReaderViewModel.kt` | `@HiltViewModel` extending `BaseViewModel`; loads source by ID from `SavedStateHandle`; tracks `currentUrl`, `currentScrollPercent`, `currentChapter` in memory; provides `onUrlChanged()`, `onScrollChanged()`, `startAutoSave()`, `stopAutoSave()`, `flushProgress()` |
| `webviewsource/ui/reader/ProgressJsBridge.kt` | `JavascriptInterface` injected as `window.TsukiBridge`; single method `reportScroll(percent: Int)` called by the JS every 2 s; also contains the `PROGRESS_JS` constant that is `evaluateJavascript()`-ed after every page load |
| `webviewsource/ui/reader/WebViewReaderActivity.kt` | `@AndroidEntryPoint AppCompatActivity`; ViewBinding on `ActivityWebviewReaderBinding`; `OnBackPressedCallback` navigates back in WebView history before closing; `WindowCompat.setDecorFitsSystemWindows(window, false)` + insets applied to toolbar; `observeSource()` loads URL once on first emission; auto-save / flush wired to `onResume` / `onPause`; `createIntent(context, sourceId)` factory method |
| `res/layout/activity_webview_reader.xml` | `FrameLayout` root; full-screen `WebView` (`@+id/webView`) + `Toolbar` (`@+id/toolbar`) anchored to top |

#### Modified files

| File | Change |
|---|---|
| `app/src/main/AndroidManifest.xml` | Registered `WebViewReaderActivity` with `Theme.Tsuki`, `configChanges="orientation|screenSize|keyboardHidden"`, `windowSoftInputMode="adjustResize"`, `exported="false"` |
| `main/ui/MainNavigationDelegate.kt` | Added import + `openWebViewReader(sourceId: Long)` method using `navBar.context.startActivity(...)` — called by the Library integration in Phase 4 |

---

#### Architecture decisions for Phase 3

- **Activity, not Fragment.** The reader is full-screen with its own back-stack management
  (WebView history). Using a Fragment inside `MainActivity` would complicate back-press
  handling with the existing `NavigationDelegate`. Matches `VisualRuleBuilderActivity`
  precedent in the same codebase.

- **`OnBackPressedCallback` for WebView back navigation.** Preferred over overriding
  `onBackPressed()` which is deprecated in API 33+. Pattern copied from
  `VisualRuleBuilderActivity`.

- **URL load guard (`urlLoaded` flag).** The `source` `StateFlow` is collected with
  `repeatOnLifecycle(STARTED)`, which re-subscribes on each resume. The flag prevents
  reloading the URL (and losing the user's position) when the Activity returns to
  foreground.

- **`scrollRestored` flag.** `restoreScrollIfNeeded()` is called from `onPageFinished`.
  Without the flag it would fire again on every in-reader navigation (chapter→chapter),
  always jumping back to the first saved position.

- **URL-change tracking via `WebViewClient.onPageStarted`.** No JavaScript needed.
  `onPageStarted` fires reliably for every new page, including SPA navigations that
  trigger a `pushState`. This is simpler and more reliable than injecting a
  `popstate` listener.

- **`PROGRESS_JS` guard (`window._tsukiTracker`).** Prevents duplicate `setInterval`
  loops if the script is re-injected on soft reloads or SPA route changes.

- **`openWebViewReader` in `MainNavigationDelegate` uses `navBar.context`.** The
  delegate has no explicit `Context` in its constructor; `navBar` is a `View` whose
  `context` is the host `Activity`. This is the same approach used by other
  delegate-level navigation calls in this codebase.

---

#### Key decisions to remember

- `WebViewReaderViewModel` reads `source_id: Long` from `SavedStateHandle` —
  matches the `Intent.putExtra("source_id", sourceId)` key in the factory method.
  Changing either one without the other breaks the reader at startup.

- `flushProgress()` is non-blocking (launches a new coroutine). It is safe to call
  from `onPause()` even after `stopAutoSave()` cancels the loop.

- The `PROGRESS_JS` constant lives in `ProgressJsBridge.kt` alongside the bridge
  class. Both are imported from the same file in the Activity — do not split them.

- `Theme.Tsuki` is used in the manifest because there is no dedicated reader theme
  yet. Phase 7 may introduce a `Theme.Tsuki.Reader` if a fully immersive experience
  (no action bar, edge-to-edge) is required.

---

#### DO NOT TOUCH

- `kotatsu-parsers-redo`, existing parsers, `BrowserSource` system, `core-exts`
- `DATABASE_VERSION` — Phase 3 adds no new columns; the version stays at 29.

---

#### Files created/modified (relative to repo root)

```
app/src/main/kotlin/io/github/landwarderer/futon/webviewsource/ui/reader/
    WebViewReaderViewModel.kt          ← NEW
    ProgressJsBridge.kt                ← NEW (includes PROGRESS_JS constant)
    WebViewReaderActivity.kt           ← NEW

app/src/main/res/layout/
    activity_webview_reader.xml        ← NEW

app/src/main/AndroidManifest.xml       ← MODIFIED (activity registration)

app/src/main/kotlin/io/github/landwarderer/futon/main/ui/
    MainNavigationDelegate.kt          ← MODIFIED (openWebViewReader + import)
```

---

## Session — July 30 2026 — WebView-as-Source Phase 4

### Context

Continuing the **WebView-as-Source** system. Phases 1–3 are merged and CI-green.
Phase 4 delivers the Library/Explore integration so users can browse all their
WebView sources and tap into the reader from a dedicated list screen.

---

### Phase 4 — Library and Recent Tab Integration

**Goal:** A dedicated list screen showing all WebView sources, sorted by recently
read, with cover/title/progress display. Tapping an item opens the reader at the
last saved position. Long-press context menu for edit and delete.

---

#### New files

| File | Purpose |
|---|---|
| `webviewsource/ui/list/WebViewSourceAdapter.kt` | `ListAdapter<WebViewSourceEntity, ViewHolder>` with `DiffUtil.ItemCallback`; renders cover (`CoilImageView.setImageAsync`), title, chapter label, progress bar, and "N chapters behind" badge; handles click and long-click callbacks |
| `webviewsource/ui/list/WebViewSourceListViewModel.kt` | `@HiltViewModel` extending `BaseViewModel`; exposes `sources: StateFlow` from `repository.observeAll()` with `SharingStarted.WhileSubscribed(5_000)`; provides `delete()`, `updateTitle()`, `updatePattern()` |
| `webviewsource/ui/list/WebViewSourceListFragment.kt` | `@AndroidEntryPoint BaseFragment<FragmentWebviewSourceListBinding>`; uses `onViewBindingCreated`; `LinearLayoutManager` RecyclerView; `repeatOnLifecycle(STARTED)` collector; item click → `startActivity(WebViewReaderActivity.createIntent(...))`; long-click → context menu with edit-title, edit-pattern, delete (each using `buildAlertDialog` + `setEditText`) |
| `webviewsource/ui/list/WebViewSourceActivity.kt` | Thin `@AndroidEntryPoint AppCompatActivity` host; inflates `activity_container.xml`; commits `WebViewSourceListFragment` into `R.id.container` on `savedInstanceState == null` |
| `res/layout/item_webview_source.xml` | `MaterialCardView` row: `CoilImageView` (56×80dp) + title + chapter label + `LinearProgressIndicator` + optional "N chapters behind" error-colour `TextView` |
| `res/layout/fragment_webview_source_list.xml` | `FrameLayout`: full-height `RecyclerView` + centred `emptyView` `TextView` |

#### Modified files

| File | Change |
|---|---|
| `core/nav/AppRouter.kt` | Added `fun openWebViewSourceList()` using `startActivity(WebViewSourceActivity::class.java)` — consistent with `openHistory()`, `openFavorites()` pattern; fully-qualified class reference avoids a new import in the large AppRouter file |
| `res/menu/opt_explore.xml` | Added `action_view_webview_sources` menu item with label `@string/webview_sources_title` |
| `explore/ui/ExploreMenuProvider.kt` | Handles `action_view_webview_sources` → `router.openWebViewSourceList()` |
| `AndroidManifest.xml` | Registered `WebViewSourceActivity` with `Theme.Tsuki`, `exported="false"` |
| `res/values/strings.xml` | Added `webview_sources_title = "My WebView Sources"` |

---

#### Architecture decisions for Phase 4

- **Standalone `WebViewSourceActivity` hosts the fragment.** The same pattern used
  by `HistoryActivity`, `FavouritesActivity`, `AllBookmarksActivity`. Avoids needing
  to know the main container ID from a `MenuProvider` context; keeps navigation
  consistent with the rest of the app.

- **Navigation entry point is `AppRouter.openWebViewSourceList()`.** Adding to
  `AppRouter` keeps all navigation in one place. Used a fully-qualified class name
  instead of an import to avoid touching unrelated import blocks in the large file.

- **Image loading via `CoilImageView.setImageAsync(url: String?)`.** `CoilImageView`
  is the project's standard lifecycle-aware image view. It handles null URLs gracefully
  (shows nothing), so no extra null check is needed in the adapter.

- **`buildAlertDialog` + `setEditText` pattern for edit dialogs.** This matches the
  existing pattern used throughout the project (e.g. `HistoryListFragment`,
  `AppRouter` dialogs). No custom dialog layouts needed.

- **`SharingStarted.WhileSubscribed(5_000)` on the `StateFlow`.** Standard Tsuki
  pattern for ViewModels that expose Room flows — the 5-second grace period survives
  configuration changes without restarting the upstream collection.

---

#### Key decisions to remember

- `WebViewSourceActivity` uses `activity_container.xml` which provides a
  `CollapsingToolbar` + `R.id.container` as `FragmentContainerView`. If a toolbar
  title is needed, set it via `supportActionBar?.title` in the Fragment's
  `onViewBindingCreated`.

- The "N chapters behind" badge is only shown when `latestKnownChapter > lastReadChapter`
  (both non-null). Phase 6 (notification worker) populates `latestKnownChapter`.

- `opt_explore.xml` now has two WebView-source entries: `action_add_webview_source`
  (opens the add sheet) and `action_view_webview_sources` (opens the list). Do not
  consolidate them — they serve different user intents.

---

#### DO NOT TOUCH

- `kotatsu-parsers-redo`, existing parsers, `BrowserSource` system, `core-exts`
- `DATABASE_VERSION` — Phase 4 adds no schema changes; version stays at 29.

---

#### Files created/modified (relative to repo root)

```
app/src/main/kotlin/io/github/landwarderer/futon/webviewsource/ui/list/
    WebViewSourceAdapter.kt              ← NEW
    WebViewSourceListViewModel.kt        ← NEW
    WebViewSourceListFragment.kt         ← NEW
    WebViewSourceActivity.kt             ← NEW

app/src/main/res/layout/
    item_webview_source.xml              ← NEW
    fragment_webview_source_list.xml     ← NEW

app/src/main/AndroidManifest.xml         ← MODIFIED (activity registration)
app/src/main/kotlin/io/github/landwarderer/futon/core/nav/AppRouter.kt   ← MODIFIED (openWebViewSourceList)
app/src/main/kotlin/io/github/landwarderer/futon/explore/ui/ExploreMenuProvider.kt  ← MODIFIED
app/src/main/res/menu/opt_explore.xml    ← MODIFIED
app/src/main/res/values/strings.xml      ← MODIFIED
```

---

## Session — Phase 4: Library and Recent Tab Integration (WebView Source List)

### What Phase 4 delivers

A dedicated list screen (`WebViewSourceListFragment`) that shows all WebView
sources the user has added. Accessible from Explore → menu → "WebView Sources".
Each row shows cover, title, last chapter, and a progress bar. Long-press shows
Edit/Delete options.

### Files created

| File | Purpose |
|---|---|
| `app/src/main/kotlin/.../webviewsource/ui/list/WebViewSourceAdapter.kt` | `ListAdapter` for WebView source rows; uses `DiffUtil` keyed on `id` |
| `app/src/main/kotlin/.../webviewsource/ui/list/WebViewSourceListViewModel.kt` | `@HiltViewModel`; exposes `sources: StateFlow` from Room; `delete`, `updateTitle`, `updatePattern` |
| `app/src/main/kotlin/.../webviewsource/ui/list/WebViewSourceListFragment.kt` | `BaseFragment` subclass; hosts the RecyclerView; context-menu dialogs via `buildAlertDialog` |
| `app/src/main/kotlin/.../webviewsource/ui/list/WebViewSourceActivity.kt` | Single-fragment `FragmentContainerActivity` wrapper to host the list |
| `app/src/main/res/layout/item_webview_source.xml` | Item layout: `ShapeableImageView` cover + title + chapter label + `LinearProgressIndicator` |
| `app/src/main/res/layout/fragment_webview_source_list.xml` | Fragment layout: `RecyclerView` + empty-state `TextView` |

### Files modified

| File | Change |
|---|---|
| `app/src/main/AndroidManifest.xml` | Registered `WebViewSourceActivity` |
| `.../core/nav/AppRouter.kt` | Added `openWebViewSourceList()` |
| `.../explore/ui/ExploreMenuProvider.kt` | Added "WebView Sources" menu item |
| `app/src/main/res/menu/opt_explore.xml` | Added `action_view_webview_sources` menu entry |
| `app/src/main/res/values/strings.xml` | Added string resources for the new screen |

### CI failure on first push (commit `188afda`) and fix

**Root cause:** `BaseFragment<B>` implements `OnApplyWindowInsetsListener`, which
has one abstract method `onApplyWindowInsets(View, WindowInsetsCompat): WindowInsetsCompat`.
The generated `WebViewSourceListFragment` did not override this method, causing a
Kotlin compiler error:

```
e: WebViewSourceListFragment.kt:23:1 Class 'WebViewSourceListFragment' is not abstract
and does not implement abstract member:
fun onApplyWindowInsets(p0: View, p1: WindowInsetsCompat): WindowInsetsCompat
```

**Fix:** Added the override. Applies the system-bar bottom inset to the RecyclerView's
bottom padding and consumes the system-bar insets so the parent does not double-apply them:

```kotlin
override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
    val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
    viewBinding?.recyclerView?.updatePadding(bottom = bars.bottom + v.paddingBottom)
    return WindowInsetsCompat.Builder(insets)
        .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.NONE)
        .build()
}
```

**Imports added** to `WebViewSourceListFragment.kt`:
- `android.view.View`
- `androidx.core.view.WindowInsetsCompat`
- `androidx.core.view.updatePadding`
- `androidx.core.graphics.Insets`

### Hard rule reminder (for future phases)

Every concrete class that extends `BaseFragment<B>` **must** override
`onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat`.
The base class provides no default implementation. Minimum safe stub:
```kotlin
override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat = insets
```
Prefer applying the bottom inset to any scrollable view and consuming it so the
activity does not apply it twice.

### Database

Phase 4 adds **no schema changes**. `DATABASE_VERSION` stays at 29.

### Navigation entry point

The list is reached via `Explore` → toolbar overflow menu → **"WebView Sources"**.
This is `action_view_webview_sources` in `opt_explore.xml`, handled by
`ExploreMenuProvider`, which calls `router.openWebViewSourceList()` → `AppRouter`
starts `WebViewSourceActivity`.


---

## Session — Phase 5: AniList / MAL Sync

### What Phase 5 delivers

Search AniList by title, link a result to a WebView source, and automatically
sync read progress when the chapter number advances in the reader.
MAL support is a clearly-marked TODO stub (AniList is fully implemented first
as specified in the Phase 5 roadmap).

### Reuse decision (Step 1 of the spec)

The project already has a full AniList integration in
`io.github.landwarderer.futon.scrobbling.anilist.data.AniListRepository`.

| Reused piece | What we use it for |
|---|---|
| `AniListRepository.findManga(query, 0)` | Search — no token required |
| `AniListRepository.isAuthorized` / `oauthUrl` | Login-state check / OAuth entry point |
| `@ScrobblerType(ANILIST) OkHttpClient` | Raw GraphQL mutations (has `AniListInterceptor` + `AniListAuthenticator` already wired) |
| `ScrobblerStorage` (via the interceptor) | Token is injected automatically by `AniListInterceptor` |

A second AniList HTTP client was **not** created. The existing scrobbling
infrastructure handles auth transparently.

### New files

| File | Purpose |
|---|---|
| `.../webviewsource/data/anilist/WebViewAniListRepository.kt` | Thin wrapper: `searchManga`, `syncProgress`, `getListEntry`. Delegates search to the existing `AniListRepository`; uses the scrobbler `OkHttpClient` for mutations. |
| `.../webviewsource/ui/anilist/AniListSearchAdapter.kt` | `ListAdapter<ScrobblerManga>` for search results; cover via `CoilImageView.setImageAsync`. |
| `.../webviewsource/ui/anilist/LinkAniListViewModel.kt` | `@HiltViewModel`; `search(query)`, `link(sourceId, media)`. |
| `.../webviewsource/ui/anilist/LinkAniListSheet.kt` | `BottomSheetDialogFragment`; search field → results → confirmation dialog → saves `anilistId` to Room. |
| `res/layout/sheet_link_anilist.xml` | Sheet layout: title, login-notice card, search row, progress indicator, RecyclerView. |
| `res/layout/item_anilist_result.xml` | Result row: `CoilImageView` cover (48×64dp) + romaji title + alt title. |

### Modified files

| File | Change |
|---|---|
| `WebViewReaderViewModel.kt` | Injected `WebViewAniListRepository`; added `lastSyncedChapter: Int = -1`; `onUrlChanged` triggers `syncProgress` when chapter number exceeds last synced value. |
| `WebViewSourceListFragment.kt` | Context menu gains "Link AniList" option (index 2); Delete shifts to index 3. Calls `LinkAniListSheet.newInstance(source.id, source.title)`. |
| `WebViewReaderActivity.kt` | Added `onCreateOptionsMenu` inflating `opt_reader.xml`; added `action_link_anilist` case in `onOptionsItemSelected`. |
| `res/menu/opt_reader.xml` | Added `action_link_anilist` overflow menu item. |
| `res/values/strings.xml` | Added 8 Phase 5 strings (`action_link_anilist`, `link_anilist_*`). |

### Progress sync logic

In `WebViewReaderViewModel.onUrlChanged`:
```kotlin
val chapterInt = currentChapter?.toInt() ?: return
val anilistId = source.anilistId ?: return
if (chapterInt > lastSyncedChapter) {
    lastSyncedChapter = chapterInt
    viewModelScope.launch { aniListRepository.syncProgress(anilistId, chapterInt) }
}
```
`lastSyncedChapter` resets to -1 each time the Activity is created, so the
first chapter read in a session always triggers a sync — even if the chapter
hasn't "advanced" relative to last session. This is intentional: it keeps
AniList in sync after the app is killed and relaunched.

### MAL stub

`WebViewSourceEntity.malId` column already exists (Phase 1). MAL sync is
**not implemented in this phase**. A full MAL implementation follows the same
pattern: wrap `MALRepository` (already in `scrobbling/mal/`) in a
`WebViewMALRepository`, add a `LinkMALSheet`, and hook into `onUrlChanged`.

### Key rules for future phases

- `WebViewSourceEntity.anilistId` is `Int?`; `ScrobblerManga.id` is `Long` — always cast `.toInt()` when saving.
- `AniListRepository.findManga(query, offset)` — `offset` is a page offset (0-based), not a page number.
- The `@ScrobblerType(ScrobblerService.ANILIST)` qualifier must appear on both the `OkHttpClient` and `ScrobblerStorage` parameters; without it Hilt will fail with a binding error.
- Do NOT add `core-exts` as a dependency (see CONTEXT.md hard rule #4).
- `DATABASE_VERSION` stays at 29 — Phase 5 adds no schema changes.

---

## Session (Jul 30 2026) — Fix Phase 5 CI failure

### Root cause

`LinkAniListViewModel` declared its own `isLoading: StateFlow<Boolean>` backed by a
`MutableStateFlow(false)`.  `BaseViewModel` already exposes a **non-open** property
with the exact same name:

```kotlin
val isLoading: StateFlow<Boolean> = loadingCounter.map { it > 0 }
    .stateIn(viewModelScope, SharingStarted.Lazily, loadingCounter.value > 0)
```

Kotlin treats this as a *hiding* error — not a warning — causing the Kotlin compiler
to emit:

```
e: LinkAniListViewModel.kt:25:9 'isLoading' hides member of supertype
   'BaseViewModel' and needs an 'override' modifier.
```

This failed the `Build Alpha APK` CI workflow on the Phase 5 commit.

### Fix

**File changed:** `webviewsource/ui/anilist/LinkAniListViewModel.kt`

| Before | After |
|---|---|
| `private val _isLoading = MutableStateFlow(false)` | *removed* |
| `val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()` | *removed* |
| `search()` sets `_isLoading.value = true/false` manually | `search()` uses `launchLoadingJob { … }` |

`launchLoadingJob` (defined in `BaseViewModel`) increments/decrements `loadingCounter`
around the block, which automatically drives the inherited `isLoading` state.
`LinkAniListSheet` already observed `viewModel.isLoading` — no UI changes needed.

### Key rule for future phases

**Never declare `isLoading` in a `BaseViewModel` subclass.** Use `launchLoadingJob`
to drive the inherited `loadingCounter` → `isLoading` path instead.
This is identical to the existing `errorEvent` / `onError` rule in CONTEXT.md.

---

## Session (Jul 30 2026) — Phase 6: New chapter notifications

### What was delivered

| Deliverable | File |
|---|---|
| Room migration 29→30 | `core/db/migrations/Migration29To30.kt` |
| `notificationsEnabled` field on entity | `core/db/entity/WebViewSourceEntity.kt` |
| `setNotificationsEnabled` DAO method | `core/db/dao/WebViewSourceDao.kt` |
| Database version bumped to 30 | `core/db/MangaDatabase.kt` |
| `fetchLatestChapterCount(mediaId)` | `webviewsource/data/anilist/WebViewAniListRepository.kt` |
| `setNotificationsEnabled` repo wrapper | `webviewsource/data/WebViewSourceRepository.kt` |
| `WebViewSourceNotificationHelper` | `webviewsource/work/WebViewSourceNotificationHelper.kt` |
| `WebViewSourceUpdateWorker` + `Scheduler` | `webviewsource/work/WebViewSourceUpdateWorker.kt` |
| `start_url` extra in reader | `webviewsource/ui/reader/WebViewReaderActivity.kt` |
| Worker scheduled on app start | `settings/work/WorkScheduleManager.kt` |
| One-time check on source save | `webviewsource/ui/AddWebViewSourceViewModel.kt` |

### Architecture decisions

**Worker pattern** — `WebViewSourceUpdateWorker` follows the `TrackWorker` reference exactly:
`@HiltWorker` + `@AssistedInject`, inner `Scheduler` class implementing `PeriodicWorkScheduler`,
registered in `WorkScheduleManager.init()` via `updateWorkerImpl(webViewUpdateScheduler, true, false)`.
Do NOT use a static `schedulePeriodicWork()` helper — that bypasses `WorkScheduleManager`.

**AniList chapter count** — added `fetchLatestChapterCount(mediaId: Int): Float?` to
`WebViewAniListRepository`. Uses the public GraphQL endpoint with no auth token.
Returns null gracefully on network error or if AniList has no chapter count.

**HTML polling regex** — the chapter URL pattern uses `{N}` as placeholder.
The regex is built as `Regex.escape(pattern).replace(Regex.escape("{N}"), "(\d+(?:\.\d+)?)")`.
This correctly handles patterns that contain regex metacharacters (dots, slashes, etc.).

**Notification deep-link** — `WebViewSourceNotificationHelper.notify()` passes a `start_url`
extra in the `WebViewReaderActivity` intent. The reader reads this in `loadSource()` before
falling back to `lastReadUrl` / `baseUrl`. Priority order: `start_url` > `lastReadUrl` > `baseUrl`.

**POST_NOTIFICATIONS** — already declared in `AndroidManifest.xml` from a prior phase.
`checkNotificationPermission(CHANNEL_ID)` from `core/util/ext/Android.kt` guards all `notify()` calls.

### Key rules for future phases

- `DATABASE_VERSION` is now **30**. Any new column must be a `Migration30To31`.
- `notificationsEnabled` defaults to `true`; the worker already filters with `filter { it.notificationsEnabled }`.
- The worker is always-on (never unscheduled). If a per-user toggle is added later, route it through `WorkScheduleManager` the same way `trackerScheduler` is gated on `settings.isTrackerEnabled`.
- `WebViewSourceUpdateWorker.TAG = "webview_source_update"` — use this constant when enqueueing one-time runs from dev/debug UI; do not hardcode the string.

### Fix (same session) — @BaseHttpClient qualifier

**Error:** `Dagger/MissingBinding] okhttp3.OkHttpClient cannot be provided without
an @Inject constructor or an @Provides-annotated method.`

**Root cause:** `NetworkModule` registers four distinct `OkHttpClient` bindings, each with
its own qualifier (`@BaseHttpClient`, `@MangaHttpClient`, plus scrobbler variants with
`@ScrobblerType`). Injecting a bare unqualified `OkHttpClient` into the worker left Hilt
unable to pick one, causing a compile-time binding error.

**Fix:** Changed the worker constructor parameter from `private val okHttpClient: OkHttpClient`
to `@BaseHttpClient private val okHttpClient: OkHttpClient`.

**Key rule for future phases:** Any injection of `OkHttpClient` must use one of
`@BaseHttpClient` (generic HTTPS), `@MangaHttpClient` (adds common headers + cache),
or `@ScrobblerType(ScrobblerService.ANILIST/MAL/SHIKIMORI)` (adds auth interceptor).
Never inject the bare unqualified type.

---

## Phase 7 — Reader Polish — July 2026

### Summary

Implemented all Phase 7 reader polish features for the WebView reader:
tap zones, injected CSS cleanup, chapter navigation, immersive fullscreen,
brightness overlay, volume key scroll, and per-source custom CSS.

---

### Files changed

| File | Change |
|---|---|
| `core/db/entity/WebViewSourceEntity.kt` | Added `custom_css: String?` field |
| `core/db/migrations/Migration30To31.kt` | **New** — ALTER TABLE adds `custom_css TEXT` |
| `core/db/MangaDatabase.kt` | Version 29 → 31, added `Migration30To31` import + entry |
| `webviewsource/ui/reader/ReaderCssHelper.kt` | **New** — `DEFAULT_CLEANUP_CSS` constant + `injectCss()` helper |
| `webviewsource/ui/reader/TapOrScrollOverlay.kt` | **New** — single custom View: taps routed left/centre/right, scroll/fling forwarded to WebView |
| `webviewsource/ui/reader/WebViewReaderActivity.kt` | Full Phase 7 rewrite: CSS injection, tap overlay wiring, chapter nav, toolbar auto-hide (3 s), immersive fullscreen, volume key scroll, Custom CSS dialog, overflow menu items |
| `webviewsource/ui/reader/WebViewReaderViewModel.kt` | Exposed `currentChapter: Float?`, added `toggleNotifications()`, `saveCustomCss()`, `currentUrlForBrowser()` |
| `webviewsource/ui/list/WebViewSourceListFragment.kt` | Added "Custom CSS" to long-press context menu; extracted string resources |
| `webviewsource/ui/list/WebViewSourceListViewModel.kt` | Added `updateCustomCss()` |
| `res/layout/activity_webview_reader.xml` | Added `TapOrScrollOverlay` and `brightnessOverlay` View siblings to WebView |
| `res/menu/opt_reader.xml` | Replaced `action_info` stub with: Link AniList, Custom CSS, Toggle notifications, Open in browser |
| `res/values/strings.xml` | Added 9 new string resources for Phase 7 UI |

---

### Architecture decisions

**Single overlay instead of three Views** — `TapOrScrollOverlay` is one custom View that uses
`GestureDetectorCompat` to distinguish single taps from scroll/fling events. Taps are zone-split
(left ⅓ / centre / right ⅓); scroll and fling events are forwarded to the WebView via
`scrollBy` / `flingScroll`. Three transparent Views would have blocked all WebView touch routing.

**CSS injection order** — default cleanup CSS (`DEFAULT_CLEANUP_CSS`) is injected first in
`onPageFinished`, then the per-source `customCss` is injected as a second `<style>` element.
This lets user rules override the defaults via normal CSS cascade (later rules win).

**Brightness overlay** — pure Android `View` with `#00000000` background; alpha is adjusted
by `setDimAmount()` in the Activity. No JS injection. The `brightnessOverlay` sits above everything
(including the tap overlay) but has `clickable=false` so touches still reach the overlay below it.

**Toolbar auto-hide** — `postDelayed({ toggleToolbar() }, 3_000)` in `onCreate`. The toolbar
starts visible, hides to immersive fullscreen after 3 s. Centre tap always re-toggles both the
toolbar and system bars together.

**Fullscreen API** — `WindowInsetsController` on API 30+, `systemUiVisibility` flags on older
APIs, wrapped in `@Suppress("DEPRECATION")`.

### Key rules for future phases

- `DATABASE_VERSION` is now **31**. Any new column must be a `Migration31To32`.
- `TapOrScrollOverlay` must have `webView` set before the view receives touch events
  (set in `setupTapOverlay()` immediately after `setupWebView()`).
- CSS injection via `injectCss()` is safe to call after `onPageFinished` only;
  calling it before `document.head` exists will silently fail.
- `WebViewReaderViewModel.currentChapter` is `private set` — read it from the Activity
  directly; do not expose a StateFlow for it (it changes too frequently for UI binding).

### Fix (same session) — opt_reader.xml action_info removal

**Error:** `Unresolved reference 'action_info'` in `reader/ui/ReaderMenuProvider.kt:19`

**Root cause:** The native reader's `ReaderMenuProvider` references `R.id.action_info` from
`opt_reader.xml`. Phase 7 replaced the full menu XML and removed that item.

**Fix:** Restored `<item android:id="@+id/action_info" ... android:visible="false" />` to
`opt_reader.xml`. It stays invisible in the WebView reader; the native reader makes it visible
when it needs it via `menu.findItem(R.id.action_info)?.isVisible = true`.

**Rule for future phases:** Never remove existing items from `opt_reader.xml`. The menu is
shared between the WebView reader and the native manga reader. Add new items; never delete old ones.

---

## Constructor Fix — TapOrScrollOverlay — July 30 2026

### Error

```
java.lang.NoSuchMethodException: <init> [class android.content.Context, interface android.util.AttributeSet]
    at LayoutInflater.createView → WebViewReaderActivity.onCreate
```

### Root cause

`TapOrScrollOverlay` was declared with only a single-argument constructor:

```kotlin
class TapOrScrollOverlay(context: Context) : View(context)
```

Android's `LayoutInflater` always calls the two-argument `(Context, AttributeSet)` constructor when it inflates a custom `View` from XML. Because that overload did not exist, inflation crashed with `NoSuchMethodException` every time `WebViewReaderActivity` was created.

### Fix

**File changed:** `app/src/main/kotlin/io/github/landwarderer/futon/webviewsource/ui/reader/TapOrScrollOverlay.kt`

Replaced the single-arg constructor with the standard `@JvmOverloads` three-arg pattern:

```kotlin
class TapOrScrollOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr)
```

Added `import android.util.AttributeSet`.

No other code was changed — all gesture-detector logic, callbacks, and `onTouchEvent` are identical to before.

### Why `@JvmOverloads`

Kotlin default parameters are invisible to Java callers (including `LayoutInflater`, which is Java). `@JvmOverloads` generates all three JVM constructor overloads:
- `(Context)` — for programmatic instantiation
- `(Context, AttributeSet)` — required by `LayoutInflater`
- `(Context, AttributeSet, Int)` — required when the view is inside a styled context

### Rule for future phases

`TapOrScrollOverlay` is referenced in an XML layout. Any custom `View` used in a layout file **must** use `@JvmOverloads` (or manually declare all three constructors). Never declare a custom View with only `(context: Context)` if it will ever appear in XML.

---

## TapOrScrollOverlay Removal — July 30 2026

### Motivation

The tap-zone overlay (`TapOrScrollOverlay`) covered the entire WebView screen and consumed every touch event before they reached the page. This broke native link taps, in-page ads, dropdowns, and all interactive site elements on real manga sites like comick.live. The feature was removed entirely so the WebView gets 100% native touch handling with nothing on top of it.

### Files changed

| File | Change |
|---|---|
| `app/src/main/kotlin/…/reader/TapOrScrollOverlay.kt` | **Deleted** |
| `app/src/main/res/layout/activity_webview_reader.xml` | Removed `<io.github.landwarderer.futon.webviewsource.ui.reader.TapOrScrollOverlay>` block (`android:id="@+id/tapOverlay"`) |
| `app/src/main/kotlin/…/reader/WebViewReaderActivity.kt` | Removed `setupTapOverlay()` call from `onCreate`; deleted `setupTapOverlay()`, `navigateNextChapter()`, and `navigatePreviousChapter()` methods; updated KDoc |

### What was kept

- `toggleToolbar()` — still called by the 3-second auto-hide `postDelayed` in `onCreate`; that behaviour is unrelated to the tap overlay and was left intact.
- `toolbarVisible` field — still needed by `toggleToolbar()`.
- `ProgressJsBridge`, scroll-percent tracking, resume logic, AniList sync, notifications, `DEFAULT_CLEANUP_CSS`, custom CSS injection — untouched.

### What was removed

- `TapOrScrollOverlay` class (the custom `View` with `GestureDetectorCompat`).
- `setupTapOverlay()` — wired `tapOverlay.onTapLeft → navigatePreviousChapter`, `onTapRight → navigateNextChapter`, `onTapCenter → toggleToolbar`.
- `navigateNextChapter()` and `navigatePreviousChapter()` — were exclusively called from the tap-zone wiring; no toolbar buttons or other entry points existed for them.

### Rule for future phases

Do **not** re-add any full-screen transparent overlay above the WebView. If chapter navigation is re-introduced, wire it to explicit toolbar buttons or swipe gestures that do not intercept WebView touch events.

---

## Keiyoushi Extension Fix + Dropdown Menu Fix — August 1 2026

### Problems fixed

#### 1. Extensions manager shows only "Outdated App / Update to Mihon 0.20.1+"

Keiyoushi migrated their extension index to a new schema. The `repo` branch
`index.min.json` (the URL the app was configured to fetch) now returns only two
stub entries as a forced upgrade notice. The real 1,367 extensions are served from
a brand-new `index.json` on the `main` branch with a completely different JSON schema.

**Old URL (stub):**
`https://raw.githubusercontent.com/keiyoushi/extensions/refs/heads/repo/index.min.json`

**New URL (real extensions):**
`https://raw.githubusercontent.com/keiyoushi/extensions/main/index.json`

**Old schema (flat array):**
```json
[{ "pkg": "eu.kanade…", "apk": "tachiyomi-all.x.apk", "code": 4, "version": "1.6.4", "nsfw": 0, "sources": [{"name":"…"}] }]
```

**New schema (wrapped object, full URLs):**
```json
{
  "extensionList": {
    "extensions": [{
      "packageName": "eu.kanade…",
      "resources": { "apkUrl": "https://cdn.jsdelivr.net/…", "iconUrl": "https://…" },
      "extensionLib": "1.6", "versionCode": "4", "versionName": "1.6.4",
      "contentWarning": "CONTENT_WARNING_NSFW",
      "sources": [{ "name": "…", "language": "all", "id": "…", "homeUrl": "…" }]
    }]
  }
}
```

#### 2. Three-dot dropdown menu items ("Create Extension", "Manage Repos") did nothing

`ExtensionDownloaderActivity`'s `ExtensionManagerMenuProvider.onMenuItemSelected`
returned `false` unconditionally, so neither `action_create_extension` nor
`action_manage_repos` ever fired.

---

### Files changed

| File | Change |
|---|---|
| `mihon/extensions/repo/ExtensionRepoService.kt` | Added `KeyyoushiIndexWrapperDto` + 4 supporting DTOs; added `KeyyoushiExtensionItemDto.toAvailableExtension()`; updated `fetchAvailableExtensions` to try `index.json` (new format) first for MIHON/ANIYOMI, fall back to legacy `index.min.json` |
| `mihon/extensions/install/ExtensionInstallService.kt` | APK URL construction now detects if `apkName` is already a full `https://` URL (Keiyoushi v2 CDN URLs) and skips the `${repoUrl}/apk/` prefix |
| `core/db/DatabasePrePopulateCallback.kt` | Keiyoushi base URL updated to `main` branch; signing key updated to `9add655a…` |
| `core/db/migrations/Migration31To32.kt` | **New** — updates any existing `external_extension_repos` row with the old `refs/heads/repo` URL to the new `main` URL + new signing key fingerprint |
| `core/db/MangaDatabase.kt` | `DATABASE_VERSION` bumped 31 → 32; `Migration31To32` added to migrations array |
| `settings/sources/extension/ExtensionDownloaderActivity.kt` | `onMenuItemSelected` now handles `action_create_extension` → `CreateExtensionActivity` and `action_manage_repos` → `ExtensionRepoActivity`; added missing `Intent`, `CreateExtensionActivity`, `ExtensionRepoActivity` imports |

---

### How the fetch fallback works

```
fetchAvailableExtensions(repo):
  if type is MIHON or ANIYOMI:
    GET ${baseUrl}/index.json
    if response has extensionList.extensions (non-empty) → parse with new DTOs, return
    else → log "falling back"
  GET ${baseUrl}/index.min.json  ← legacy path for other repo types or repos without index.json
  parse with old flat-array DTOs, return
```

For Keiyoushi's new `main` base URL, `index.json` is present and returns the full list →
new path taken. For third-party repos that only have `index.min.json`, the fallback
path is taken unchanged.

### APK URL handling

Old format: `apkName` was a filename (e.g. `tachiyomi-all.ahottie-v1.6.4.apk`).
Install service prepended `${repoUrl}/apk/`.

New format: `apkName` stores the full CDN URL from `resources.apkUrl`
(e.g. `https://cdn.jsdelivr.net/gh/keiyoushi/extensions@repo/apk/…`).
Install service now checks `apkName.startsWith("https://")` and uses it verbatim.

### Rules for future phases

- `DATABASE_VERSION` is now **32**. Any new column must use `Migration32To33`.
- Keiyoushi base URL is `https://raw.githubusercontent.com/keiyoushi/extensions/main`.
  Do **not** revert to `refs/heads/repo` — that branch now serves the stub only.
- For any new extension repo type, add both a new DTO set and a new branch in
  `fetchAvailableExtensions`. The fallback to `index.min.json` remains as the
  last resort for third-party repos that haven't adopted the new schema.
- `ExtensionDownloaderActivity` and `ExtensionsActivity` share `opt_extensions.xml`.
  Both activities must handle all menu items in their own `onMenuItemSelected`.

---

## Session — August 1 2026 — Fix JAR Plugin Loading (UMA / tsuki format)

### Problem

Both import paths showed errors:
- **GitHub import** (`InvalidDavid/UMA`): "Downloaded file is not a valid plugin JAR"
- **File picker import**: "File does not appear to be a valid plugin JAR"

Both errors were thrown when `PluginLoader.loadPlugin()` returned `null`.

### Root cause analysis

Inspection of the UMA `.jar` file revealed the following structure:
```
classes.dex                       ← DEX bytecode (pre-compiled, loadable by DexClassLoader)
META-INF/plugins.kotlin_module    ← Kotlin module metadata (NOT plugin.json)
tsuki/site/...                    ← parser resource directories
assets/i18n/...                   ← i18n strings
```

Key findings:
1. **No `META-INF/plugin.json`** — `readManifest()` returned null; `entryClass` not found.
2. **Wrong entry class names** — `KNOWN_ENTRY_CLASSES` listed `uma.plugin.PluginFactory`, `com.invalidd.uma.Plugin`, etc. None of these exist in the UMA jar.
3. **UMA uses the KSP factory pattern**, not a plugin singleton with `getSources()`. The plugin has:
   - `tsuki.MangaParserFactoryKt` — static factory class with `newParser(MangaParserSource, MangaLoaderContext): MangaParser`
   - `tsuki.model.MangaParserSource` — enum whose constants each represent one manga source
   - `tsuki.MangaLoaderContext` — interface for the network/http context
4. **Wrong context class lookup** — `tryInstantiate()` tried `classLoader.loadClass("org.koitharu.kotatsu.parsers.MangaLoaderContext")` but UMA uses the `tsuki.*` package; the class does not exist under the `org.koitharu.*` name in the plugin classloader.

This was confirmed by reading Usagi's `MangaDynamicRepository.kt`, which is the reference implementation for loading these plugins.

### Fix

Three files were changed:

#### `PluginLoader.kt` — complete rewrite of the loading strategy

- Added **Strategy 1: tsuki/UMA factory format** (`tryLoadTsukiFormat()`):
  - Tries `tsuki.MangaParserFactoryKt` + `tsuki.model.MangaParserSource` + `tsuki.MangaLoaderContext` first.
  - Falls back to the older `org.koitharu.kotatsu.parsers.*` package names.
  - Resolves the static `newParser(enumClass, ctxClass)` method via reflection.
  - Creates a `java.lang.reflect.Proxy` wrapping the host's `MangaLoaderContext` so it appears as the plugin's `tsuki.MangaLoaderContext` interface. This bridges the classloader boundary.
  - Enumerates `MangaParserSource.values()` to discover all sources; reads `title` and `locale` via reflection.
  - Returns a `LoadedPlugin` with `newParserMethod`, `sourceEnumClass`, and `loaderContextProxy` populated.
- **Strategy 2: legacy reflection format** (`tryLoadLegacyFormat()`) is unchanged in behaviour; it's now a fallback after Strategy 1.
- Renamed `KNOWN_ENTRY_CLASSES` → `LEGACY_ENTRY_CLASSES` to clarify scope.
- `tryInstantiate()` renamed to `tryInstantiateLegacy()` and now also tries `MangaLoaderContext::class.java` (the host's type) directly before the classloader lookup.

#### `LoadedPlugin.kt` — added UMA/tsuki format fields

Added three nullable fields (default null = legacy format):
- `newParserMethod: java.lang.reflect.Method?` — the static factory method
- `sourceEnumClass: Class<*>?` — the `MangaParserSource` enum class from the plugin classloader
- `loaderContextProxy: Any?` — the Proxy wrapping the host's `MangaLoaderContext`
- Added `isTsukiFormat: Boolean` computed property (`newParserMethod != null && sourceEnumClass != null`)

#### `PluginMangaRepository.kt` — two-path parser resolution

- `getParserForSource()` now dispatches to:
  - `getParserViaTsukiFactory()` when `loadedPlugin.isTsukiFormat`
  - `getParserViaLegacyReflection()` for legacy plugins (identical to previous code)
- `getParserViaTsukiFactory()` calls `newParserMethod.invoke(null, enumConstant, loaderContextProxy)` to create a fresh parser per call.

### Architecture decisions

- **Proxy bridge for MangaLoaderContext**: The host app uses `org.koitharu.kotatsu.parsers.MangaLoaderContext`; UMA uses `tsuki.MangaLoaderContext`. Since both classloaders are separate, direct casting is impossible. A `java.lang.reflect.Proxy` bridges them by forwarding method calls by name+arity. This pattern is consistent with how Usagi wraps plugin parsers back into its own `MangaParser` interface.
- **Strategy ordering**: tsuki format is tried first because UMA and all modern Usagi-compatible plugins use it. Legacy format is the fallback for any pre-KSP plugins.
- **No change to download logic**: The GitHub download (`PluginDownloader.downloadFromGithub`) already fetches the first `.jar` asset from the latest release, which is correct for UMA. The failure was purely in the validation/loading step.

### Rules for future agents

- UMA and similar Usagi-ecosystem plugins use the KSP-generated factory pattern. **Do not** expect `META-INF/plugin.json` or a root plugin class with `getSources()` from these plugins.
- The three required classes are `tsuki.MangaParserFactoryKt`, `tsuki.model.MangaParserSource`, and `tsuki.MangaLoaderContext`. Older variants use `org.koitharu.kotatsu.parsers.*` equivalents.
- `LoadedPlugin.isTsukiFormat` is the canonical check for which loading path was used.
- Never try to pass the host's `MangaLoaderContext` directly to a plugin method; always use `loaderContextProxy` which was created from the plugin's own classloader.

### Files changed

| File | Change |
|---|---|
| `app/src/main/kotlin/.../plugins/data/PluginLoader.kt` | Added tsuki/UMA factory loading strategy; refactored legacy strategy as fallback |
| `app/src/main/kotlin/.../plugins/domain/LoadedPlugin.kt` | Added `newParserMethod`, `sourceEnumClass`, `loaderContextProxy`, `isTsukiFormat` |
| `app/src/main/kotlin/.../plugins/data/PluginMangaRepository.kt` | Two-path `getParserForSource()` dispatching on `isTsukiFormat` |
