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

## Architecture notes (for future agents)

### Two independent source creation paths

| Path | Entry point | Parser |
|---|---|---|
| **Universal Source Beta** | `UniversalSourceActivity` → `UniversalSourceViewModel` → `SiteAutoDetector` | `TemplateHtmlParser` (Kotlin, Jsoup) |
| **Create Extension (JS/Dart)** | `CreateExtensionActivity` → `CreateExtensionViewModel` | JS engine (QuickJS or similar) calling exported functions |

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

Build #248 was the last successful build before this session (tag `alpha-latest`).
