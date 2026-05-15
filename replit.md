# Tsuki Manga Reader — manhwaread.com Custom Source Fixes

  _Android manga reader app (fork of Kotatsu) with custom source support. This session fixed three bugs for the manhwaread.com Madara-fork source._

  ## Run & Operate

  - `pnpm --filter @workspace/api-server run dev` — run the API server (port 5000)
  - `pnpm run typecheck` — full typecheck across all packages
  - `pnpm run build` — typecheck + build all packages
  - `pnpm --filter @workspace/api-spec run codegen` — regenerate API hooks and Zod schemas from the OpenAPI spec
  - `pnpm --filter @workspace/db run push` — push DB schema changes (dev only)
  - Required env: `DATABASE_URL` — Postgres connection string

  ## Stack

  - pnpm workspaces, Node.js 24, TypeScript 5.9
  - API: Express 5
  - DB: PostgreSQL + Drizzle ORM
  - Validation: Zod (`zod/v4`), `drizzle-zod`
  - API codegen: Orval (from OpenAPI spec)
  - Build: esbuild (CJS bundle)
  - Android app: Kotlin, Hilt DI, OkHttp, Jsoup

  ## Where things live

  - **Custom source parsers**: `app/src/main/kotlin/io/github/landwarderer/futon/customsource/data/`
  - **Main parser of interest**: `MadaraHtmlParser.kt` — handles all Madara-based manga sites
  - **CMS detector**: `CmsTypeDetector.kt` — routes domains to the correct parser (manhwaread.com → MADARA)
  - **Crash handler**: `app/src/main/kotlin/io/github/landwarderer/futon/core/CrashHandler.kt`
  - **Application class**: `app/src/main/kotlin/io/github/landwarderer/futon/core/BaseApp.kt`

  ## Architecture decisions

  - manhwaread.com uses `mangomic-core` (a renamed Madara plugin fork), NOT standard Madara
  - Chapters are embedded directly in the HTML as `<a class="chapter-item">` inside `.chapters-list` — no AJAX endpoint (/ajax/chapters/ returns 404)
  - Page images are NOT in the HTML DOM; they are encoded in a JS variable: `var chapterData = {"data":"<base64>","base":"https://manread.xyz/{postId}"}`
  - Genre browsing uses `/manhwa/?genre=SLUG` (not `/manga/?genre=SLUG` which 404s on manhwaread)
  - CrashHandler uses Thread.setDefaultUncaughtExceptionHandler — saves log to files/last_crash.txt, starts CrashReportActivity before killing the process

  ## Product

  - Manga/manhwa reader with custom source support
  - Users can add manhwaread.com (and other Madara-based sites) as custom sources
  - Chapter list, page reading, genre filtering, and crash reporting all work for manhwaread.com

  ## User preferences

  - Push all fixes to the `devel` branch via GitHub API
  - Poll CI after commits until the build is successful
  - Always update replit.md after completing a session's work

  ## Gotchas

  - manhwaread.com URL pattern: manga at `/manhwa/slug/`, chapters at `/manhwa/slug/chapter-XX/`
  - `/manga/` path 404s on manhwaread — never use it as the browse base URL for this site
  - `/ajax/chapters/` POST endpoint 404s on manhwaread — chapters are DOM-embedded only
  - Chapter images CDN: `https://manread.xyz/{postId}/{chapterId}/mr_XXX.jpg`
  - mancover.xyz is the COVER image CDN (not chapter images)
  - CrashReportActivity must be registered in AndroidManifest.xml (done — excludeFromRecents=true)
  - When adding new Madara fork sites, check `.chapters-list a.chapter-item` vs `li.wp-manga-chapter` HTML structure

  ## Pointers

  - See the `pnpm-workspace` skill for workspace structure, TypeScript setup, and package details
  - GitHub repo: Space4414/Tsuki, active branch: devel
  