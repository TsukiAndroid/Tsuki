# Tsuki — Android Manga Reader

  A Kotlin/Jetpack Compose Android manga reader app. It bundles 
  `kotatsu-parsers-redo` (com.github.clquwu) as built-in sources AND ships
  ~50 custom CMS-family parsers (Madara, MangaThemesia, ComixTo, etc.) in
  `customsource/` for user-added sites of any matching CMS type.

  ## Run & Operate

  > This is an **Android Studio** project — it does not run on Replit.  
  > Use this Replit workspace only for GitHub API-based file edits and CI polling.

  - Build: push commits to the `devel` branch — CI (`.github/workflows/build-alpha.yml`) will build the APK automatically.
  - CI poll: `GET https://api.github.com/repos/Space4414/Tsuki/actions/runs?branch=devel&per_page=3`

  ## Stack

  - Kotlin + Jetpack Compose, minSdk 26
  - kotatsu-parsers-redo (com.github.clquwu:kotatsu-parsers-redo:13cfd261d1) — built-in sources
  - customsource/ — ~50 CMS-family parsers for user-added sites
  - Coil 3 — image loading, with `MangaSourceHeaderInterceptor`
  - OkHttp 4 — networking in custom parsers
  - Jsoup — HTML scraping

  ## Where things live

  | Path | Purpose |
  |---|---|
  | `app/.../customsource/data/CmsTypeDetector.kt` | Detects CMS type for a user-added URL |
  | `app/.../customsource/data/*HtmlParser.kt` | HTML scrapers: Madara, MangaThemesia, MangaStream, ComixTo, … |
  | `app/.../customsource/data/*ApiParser.kt` | JSON API parsers: ComicK, HeanCms, Iken, Zeroscans, … |
  | `app/.../core/image/MangaSourceHeaderInterceptor.kt` | Coil interceptor — adds Referer + UA for cover image loads |
  | `app/.../customsource/domain/CustomSourceType.kt` | Enum of all ~50 supported CMS types |

  ## Architecture decisions

  1. **Built-in vs Custom sources** — `kotatsu-parsers-redo` contains 900+ site-specific parsers for known domains, bundled at compile time. The `customsource/` layer adds ~50 CMS-family parsers so users can paste ANY site URL and the app detects its CMS automatically. These two layers serve completely different purposes.

  2. **CmsTypeDetector is structure-first** — As of 2026-05-14, ALL detection uses API response shape or HTML structural markers, never raw domain-name strings as the primary signal. Domain strings are only fast-path shortcuts in `KNOWN_DOMAIN_TYPES`. This means every clone/mirror of a known CMS is detected automatically.

  3. **Browser UA everywhere** — All parsers use a full Chrome/Android User-Agent string. Bot/app UAs (e.g. "Tsuki/1.0") get blocked by hotlink-protected CDNs on Madara/MangaThemesia sites. `MangaSourceHeaderInterceptor` FORCES the browser UA for all Coil image requests from custom sources.

  4. **Lazy-image cascade** — Every `resolveImageUrl()` helper checks: `data-src` → `data-lazy-src` → `data-original` → `data-url` → `srcset[0]` → `src`. This covers standard WP lazy-load, Jetpack, EWWW, ShortPixel, Smush, and all other popular WP image plugins.

  5. **tsuki-parsers JSON templates** — The repo at `Space4414/tsuki-parsers` contains `.json` template files (templates/madara.json etc.) consumed by the app's `CUSTOM_TEMPLATE` source type via `TemplateHtmlParser`. `RemoteTemplateSync` fetches `index.json` at startup and the templates are applied at runtime by `TemplateHtmlParser`.

  ## Bugs fixed (2026-05-14)

  ### 1. Manhwaread.com red/placeholder cover images — FIXED
  - **Root cause A**: `MangaSourceHeaderInterceptor` sent `"Tsuki/1.0 (Android)"` UA. Many CDNs reject non-browser UAs and return red error images.
  - **Root cause B**: Some Madara/MangaThemesia sites use `data-lazy-src` (Jetpack plugin) instead of `data-src`. Parsers only checked `data-src`, falling through to `src` which contains a placeholder.
  - **Fix**: Interceptor now forces Chrome/Android UA unconditionally. All HTML parsers use full attribute cascade.

  ### 2. ComixTo / Komix site shows "Nothing found" — FIXED
  - **Root cause**: Only 5 CSS selectors tried for canonical comix.to layout; variant layouts returned empty results from all.
  - **Fix**: 5-layer selector cascade (specific → broad → ultra-broad) + nuclear fallback scanning any `<a href=/manga/>…<img>` pattern. 10 browse URL patterns, 5 search URL patterns.

  ### 3. CmsTypeDetector domain-locked detection — FIXED
  - **Root cause**: `isComicK` checked `html.contains("comick")` — domain-locked. `isHeanCms`/`isIkenCms` only tried `api.{domain}` subdomain, missing self-hosted installs at `{domain}/api/`.
  - **Fix**: `isComicK` probes `/v1.0/comic/?limit=1` and validates `"hid"` + `"slug"` fields. `isHeanCms`/`isIkenCms` try both `api.{domain}` and `{domain}/api/` endpoints.

  ### 4. MangaThemesia + MangaStream bot UA — FIXED
  - Both parsers had `USER_AGENT = "Tsuki/1.0 (Android)"` causing 403s on many sites.
  - **Fix**: Upgraded to full Chrome/Android UA + added `resolveImageUrl()` helper with full attribute cascade.

  ## Gotchas

  - **NEVER use bot UA** — "Tsuki/1.0" or any non-browser UA gets blocked by CDNs on Madara/MangaThemesia sites. Always use the full Chrome/Android string.
  - **CI cancellations** — Rapid consecutive pushes cause GitHub Actions to cancel earlier runs; only the last push builds. This is normal behaviour.
  - **kotatsu-parsers-redo upgrade** — The built-in parser library is pinned to commit `13cfd261d1` in `app/build.gradle`. Bumping this can bring in new built-in sources but may break API compatibility.
  - **CustomSourceType enum** — Every new CMS type requires an entry in `CustomSourceType.kt`, a matching detector in `CmsTypeDetector.kt`, and a new parser class.
  - **GitHub secret scanning** — Do NOT embed PATs or API keys in files pushed via the GitHub API. The push will be rejected by repository rules.

  ## tsuki-parsers JSON templates — How they work

  Repo: `Space4414/tsuki-parsers`

  ```
  index.json                  ← list of all templates (name, version, file path)
  templates/madara.json       ← selector rules for Madara CMS
  templates/mangathemesia.json
  ... (one file per CMS type)
  ```

  At startup, `RemoteTemplateSync` fetches `index.json` and downloads any template the user does not have locally. The user creates a source with type `CUSTOM_TEMPLATE`, and `TemplateHtmlParser` applies the template's selectors at runtime.

  **They work correctly as long as:**
  1. The JSON schema matches what `ParserTemplate` expects (it does — schema was designed together).
  2. `RemoteTemplateSync` is pointed at the correct raw GitHub URL for `index.json`.
  3. The selector fields in each `.json` match current site HTML (same maintenance burden as any other scraper).

  ## Pointers

  - GitHub Actions CI: https://github.com/Space4414/Tsuki/actions
  - tsuki-parsers: https://github.com/Space4414/tsuki-parsers
  - See the `pnpm-workspace` skill for workspace structure docs (not relevant to Android)
  