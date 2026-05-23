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
