## Alpha 1.425 (Build [#105](https://github.com/Space4414/Tsuki/actions/runs/25718085998))

_2026-05-12 06:42 UTC_

- Update index.html (ec675e9)
- Add files via upload (28da118)


## Alpha 1.423 (Build [#104](https://github.com/Space4414/Tsuki/actions/runs/25717718216))

_2026-05-12 06:33 UTC_

- Add files via upload (28da118)
- Create index.html (2293b7e)


## Alpha 1.421 (Build [#103](https://github.com/Space4414/Tsuki/actions/runs/25714204860))

_2026-05-12 04:54 UTC_

- Create index.html (2293b7e)
- ci: upgrade all workflows from JDK 17 to JDK 21 (aceac5b)


## Alpha 1.419 (Build [#102](https://github.com/Space4414/Tsuki/actions/runs/25707286963))

_2026-05-12 01:19 UTC_

- ci: upgrade all workflows from JDK 17 to JDK 21 (aceac5b)
- fix: dialog_add_template_site hint + template fingerprint auto-detect (eb320cd)
- feat(parser-templates): Phase 2 — TemplateHtmlParser runtime engine + full CUSTOM_TEMPLATE wiring (3b31854)
- feat: user-importable parser templates (3a97b7a)


## Alpha 1.417 (Build [#101](https://github.com/Space4414/Tsuki/actions/runs/25706303907))

_2026-05-12 00:50 UTC_

- fix: dialog_add_template_site hint + template fingerprint auto-detect (eb320cd)
- feat(parser-templates): Phase 2 — TemplateHtmlParser runtime engine + full CUSTOM_TEMPLATE wiring (3b31854)
- feat: user-importable parser templates (3a97b7a)


## Beta 1.4.8 (Build [#2](https://github.com/Space4414/Tsuki/actions/runs/25650421750))

_2026-05-11 04:32 UTC_

- feat: persistent language filter for sources (strings.xml) (e21e2eb)
- feat: persistent language filter for sources (opt_sources.xml) (d2e6223)
- feat: persistent language filter for sources (opt_explore.xml) (e0ce7fa)
- feat: persistent language filter for sources (SourcesManageFragment.kt) (8ce3ba6)
- feat: persistent language filter for sources (SourcesListProducer.kt) (5b55a9d)
- feat: persistent language filter for sources (ExploreMenuProvider.kt) (39c03e7)
- feat: persistent language filter for sources (ExploreFragment.kt) (557a5d2)
- feat: persistent language filter for sources (MangaSourcesRepository.kt) (a382d03)
- feat: persistent language filter for sources (AppSettings.kt) (384c1bb)
- fix(detector): tighten Zeroscans check to avoid false-positive on PizzaReader sites (bd5c0f9)
- ci: trigger clean build after fixing release conflict (4b630c8)
- fix(customsource): replace selectLast with select().last() in KeyoappHtmlParser (fb89410)
- fix(customsource): remove SortOrder.ALPHABETICAL_DESC and fix IkenApiParser authors (6d41475)
- feat(customsource): add 14 new CMS parser types (batch 3) (4ff6a6f)
- fix: workflow concurrency + dropdown non-filtering adapter (ae15d1b)
- feat: wire 10 new parsers into CustomMangaRepository (0532661)
- feat: add 10 new CMS fingerprint detection blocks (c90ce38)
- feat: add 10 new CustomSourceType enum values (56f5ec9)
- feat: add MangaKatana parser (1255fa6)
- feat: add TruyenQQ parser (Vietnamese) (65d4c28)
- feat: add NetTruyen parser (Vietnamese) (6205a3f)
- feat: add MangaOwl parser (6995e67)
- feat: add MangaFreak parser (e3dd71d)
- feat: add Mangago HTML parser (33bed86)
- feat: add MangaLib REST API parser (Russian) (76c8fc8)
- feat: add MangaHere/Foxaholic CMS parser (ba45b01)
- feat: add MangaHub parser (Bootstrap/PHP) (0eae04c)
- feat: add MangaPill parser (img.js-page reader) (1c7cc33)
- feat: wire 12 new parsers into CustomMangaRepository (5698f4e)
- feat: extend CmsTypeDetector with 12 new site fingerprints (b08a762)
- feat: add 12 new CustomSourceType enum entries (c62ece5)
- feat: add Cubari.moe JSON API parser (f46952f)
- feat: add KissManga style parser (025eda4)
- feat: add ReaderFront GraphQL parser (5942a14)
- feat: add MangaNato / MangaBat parser (9b921b4)
- feat: add TCBScans static site parser (f8b24fb)
- feat: add FanFox / MangaFox parser (3455aef)
- feat: add MangaReader.to style parser (c98ceb0)
- feat: add MangaHost / Leitor.net parser (PT-BR) (df2d29c)
- feat: add NineManga PHP CMS parser (c176e03)
- feat: add Bato.to HTML parser (bc173cd)
- feat: add ComicK REST API parser (comick.io) (bccfe4a)
- feat: add ComixTo parser for comix.to and clones (dae13e3)
- remove: dead KEY_LINK_GITHUB handler (Our Story section removed) (69dd9cf)
- remove: "Our Story" section from About settings (d477748)
- Update README.md (a5a672b)
- Update README.md (3397f13)
- feat: add space4414.github.io intent-filter for Tsuki share links (45c6c77)
- fix: resolve space4414.github.io/Tsuki/open share links in MangaLinkResolver (17ca831)
- fix: share URL → https://space4414.github.io/Tsuki/open (clickable in all apps) (5e1ed99)
- fix: resolve tsuki://manga deep links (host-based path check + isValidLink) (da7bf5c)
- feat: interval settings, notifications fix, download fix (AppUpdateActivity.kt) (3e2f680)
- feat: interval settings, notifications fix, download fix (pref_about.xml) (4c0f637)
- feat: interval settings, notifications fix, download fix (pref_tracker.xml) (f8e4ce1)
- feat: interval settings, notifications fix, download fix (WorkScheduleManager.kt) (b534743)
- feat: interval settings, notifications fix, download fix (AppUpdateWorker.kt) (91fdbf1)
- feat: interval settings, notifications fix, download fix (TrackWorker.kt) (4a9568d)
- feat: interval settings, notifications fix, download fix (AppSettings.kt) (3c312bd)
- feat: interval settings, notifications fix, download fix (constants.xml) (49afe79)
- feat: interval settings, notifications fix, download fix (arrays.xml) (5b2f4a0)
- feat: interval settings, notifications fix, download fix (strings.xml) (a9a3928)
- feat: resolve tsuki:// scheme deep links in MangaLinkResolver (ccf7f94)
- feat: use tsuki:// scheme for manga share links (69230bd)
- ci: add Discord role mentions to build-stable webhook notification (5f2571f)
- ci: add Discord role mentions to build-alpha webhook notification (bc9ee19)
- ci: add Discord role mentions to build-beta webhook notification (3d06372)
- feat(ci): add Discord webhook notifications to all release workflows (95dadc2)
- feat: app-wide immersive mode for Android 6+ (API 23+) (78e53dc)
- feat: smooth IME animation + haptic feedback for API 23+ devices (5fb9d89)
- fix: IME keyboard overlap on password screens and predictive-back double animation (b66bdd7)
- fix: reader cutout mode on Android 12+ and action mode status bar on Android 15+ (262b959)
- fix: nav bar blur on Android 12+ and toggleable performance mode (48ef288)
- Update CONTRIBUTING.md (121e0df)
- ci: support manual [Unreleased] release notes in CHANGELOG.md (654aa2f)
- brand: add Tsuki Project badge to README and release footers (e8d9c6e)
- ci: replace bare #N build refs with Tsuki CI run links (487dee5)
- fix: correct 3 remaining update-checker bugs for stable APK (861743b)
- fix: stable channel now matches stable-latest CI tag (07b77d6)
- fix: add LEGACY_SYNC_ACCOUNT_TYPE BuildConfig field to all flavors (f6130b8)
- feat: sync health worker + in-app update banner (6ecbabe)
- feat: fallback migration banner detection via had_sync_account flag (57a3f45)
- feat: write had_sync_account flag in SyncController for migration fallback detection (a770326)
- feat: call removeLegacySyncAccount before firing migration banner (ea24cf0)
- feat: show sync migration Snackbar in MainActivity (a8e2a12)
- feat: fire onShowSyncMigrationBanner event in MainViewModel init (456e135)
- fix: migrate Stable flavor authorities to com.space4414.tsuki branding (c9c14d7)
- fix: give Beta flavor unique ContentProvider authorities and account type (98550b9)
- fix: give Alpha flavor unique ContentProvider authorities and account type (8c28af5)
- ci: add draft mode input to Stable workflow (2166317)
- ci: add draft mode input to Beta workflow (95b6c91)
- ci: add release_notes input to Beta workflow (cff53c4)
- ci: add release_notes input to Stable workflow (87ab119)


## Alpha 1.404 (Build [#90](https://github.com/Space4414/Tsuki/actions/runs/25648850432))

_2026-05-11 03:33 UTC_

- feat: persistent language filter for sources (AppSettings.kt) (384c1bb)


## Beta 1.4.7 (Build [#1](https://github.com/Space4414/Tsuki/actions/runs/25635971999))

_2026-05-10 18:08 UTC_

- fix(detector): tighten Zeroscans check to avoid false-positive on PizzaReader sites (bd5c0f9)
- ci: trigger clean build after fixing release conflict (4b630c8)
- fix(customsource): replace selectLast with select().last() in KeyoappHtmlParser (fb89410)
- fix(customsource): remove SortOrder.ALPHABETICAL_DESC and fix IkenApiParser authors (6d41475)
- feat(customsource): add 14 new CMS parser types (batch 3) (4ff6a6f)
- fix: workflow concurrency + dropdown non-filtering adapter (ae15d1b)
- feat: wire 10 new parsers into CustomMangaRepository (0532661)
- feat: add 10 new CMS fingerprint detection blocks (c90ce38)
- feat: add 10 new CustomSourceType enum values (56f5ec9)
- feat: add MangaKatana parser (1255fa6)
- feat: add TruyenQQ parser (Vietnamese) (65d4c28)
- feat: add NetTruyen parser (Vietnamese) (6205a3f)
- feat: add MangaOwl parser (6995e67)
- feat: add MangaFreak parser (e3dd71d)
- feat: add Mangago HTML parser (33bed86)
- feat: add MangaLib REST API parser (Russian) (76c8fc8)
- feat: add MangaHere/Foxaholic CMS parser (ba45b01)
- feat: add MangaHub parser (Bootstrap/PHP) (0eae04c)
- feat: add MangaPill parser (img.js-page reader) (1c7cc33)
- feat: wire 12 new parsers into CustomMangaRepository (5698f4e)
- feat: extend CmsTypeDetector with 12 new site fingerprints (b08a762)
- feat: add 12 new CustomSourceType enum entries (c62ece5)
- feat: add Cubari.moe JSON API parser (f46952f)
- feat: add KissManga style parser (025eda4)
- feat: add ReaderFront GraphQL parser (5942a14)
- feat: add MangaNato / MangaBat parser (9b921b4)
- feat: add TCBScans static site parser (f8b24fb)
- feat: add FanFox / MangaFox parser (3455aef)
- feat: add MangaReader.to style parser (c98ceb0)
- feat: add MangaHost / Leitor.net parser (PT-BR) (df2d29c)
- feat: add NineManga PHP CMS parser (c176e03)
- feat: add Bato.to HTML parser (bc173cd)
- feat: add ComicK REST API parser (comick.io) (bccfe4a)
- feat: add ComixTo parser for comix.to and clones (dae13e3)
- remove: dead KEY_LINK_GITHUB handler (Our Story section removed) (69dd9cf)
- remove: "Our Story" section from About settings (d477748)
- Update README.md (a5a672b)
- Update README.md (3397f13)
- feat: add space4414.github.io intent-filter for Tsuki share links (45c6c77)
- fix: resolve space4414.github.io/Tsuki/open share links in MangaLinkResolver (17ca831)
- fix: share URL → https://space4414.github.io/Tsuki/open (clickable in all apps) (5e1ed99)
- fix: resolve tsuki://manga deep links (host-based path check + isValidLink) (da7bf5c)
- feat: interval settings, notifications fix, download fix (AppUpdateActivity.kt) (3e2f680)
- feat: interval settings, notifications fix, download fix (pref_about.xml) (4c0f637)
- feat: interval settings, notifications fix, download fix (pref_tracker.xml) (f8e4ce1)
- feat: interval settings, notifications fix, download fix (WorkScheduleManager.kt) (b534743)
- feat: interval settings, notifications fix, download fix (AppUpdateWorker.kt) (91fdbf1)
- feat: interval settings, notifications fix, download fix (TrackWorker.kt) (4a9568d)
- feat: interval settings, notifications fix, download fix (AppSettings.kt) (3c312bd)
- feat: interval settings, notifications fix, download fix (constants.xml) (49afe79)
- feat: interval settings, notifications fix, download fix (arrays.xml) (5b2f4a0)
- feat: interval settings, notifications fix, download fix (strings.xml) (a9a3928)
- feat: resolve tsuki:// scheme deep links in MangaLinkResolver (ccf7f94)
- feat: use tsuki:// scheme for manga share links (69230bd)
- ci: add Discord role mentions to build-stable webhook notification (5f2571f)
- ci: add Discord role mentions to build-alpha webhook notification (bc9ee19)
- ci: add Discord role mentions to build-beta webhook notification (3d06372)
- feat(ci): add Discord webhook notifications to all release workflows (95dadc2)
- feat: app-wide immersive mode for Android 6+ (API 23+) (78e53dc)
- feat: smooth IME animation + haptic feedback for API 23+ devices (5fb9d89)
- fix: IME keyboard overlap on password screens and predictive-back double animation (b66bdd7)
- fix: reader cutout mode on Android 12+ and action mode status bar on Android 15+ (262b959)
- fix: nav bar blur on Android 12+ and toggleable performance mode (48ef288)
- Update CONTRIBUTING.md (121e0df)
- ci: support manual [Unreleased] release notes in CHANGELOG.md (654aa2f)
- brand: add Tsuki Project badge to README and release footers (e8d9c6e)
- ci: replace bare #N build refs with Tsuki CI run links (487dee5)
- fix: correct 3 remaining update-checker bugs for stable APK (861743b)
- fix: stable channel now matches stable-latest CI tag (07b77d6)
- fix: add LEGACY_SYNC_ACCOUNT_TYPE BuildConfig field to all flavors (f6130b8)
- feat: sync health worker + in-app update banner (6ecbabe)
- feat: fallback migration banner detection via had_sync_account flag (57a3f45)
- feat: write had_sync_account flag in SyncController for migration fallback detection (a770326)
- feat: call removeLegacySyncAccount before firing migration banner (ea24cf0)
- feat: show sync migration Snackbar in MainActivity (a8e2a12)
- feat: fire onShowSyncMigrationBanner event in MainViewModel init (456e135)
- fix: migrate Stable flavor authorities to com.space4414.tsuki branding (c9c14d7)
- fix: give Beta flavor unique ContentProvider authorities and account type (98550b9)
- fix: give Alpha flavor unique ContentProvider authorities and account type (8c28af5)
- ci: add draft mode input to Stable workflow (2166317)
- ci: add draft mode input to Beta workflow (95b6c91)
- ci: add release_notes input to Beta workflow (cff53c4)
- ci: add release_notes input to Stable workflow (87ab119)


## Alpha 1.401 (Build [#88](https://github.com/Space4414/Tsuki/actions/runs/25635319045))

_2026-05-10 17:38 UTC_

- fix(detector): tighten Zeroscans check to avoid false-positive on PizzaReader sites (bd5c0f9)
- ci: trigger clean build after fixing release conflict (4b630c8)


## Alpha 1.399 (Build [#87](https://github.com/Space4414/Tsuki/actions/runs/25635014567))

_2026-05-10 17:23 UTC_

- ci: trigger clean build after fixing release conflict (4b630c8)
- fix(customsource): replace selectLast with select().last() in KeyoappHtmlParser (fb89410)
- fix(customsource): remove SortOrder.ALPHABETICAL_DESC and fix IkenApiParser authors (6d41475)
- feat(customsource): add 14 new CMS parser types (batch 3) (4ff6a6f)


## Alpha 1.397 (Build [#86](https://github.com/Space4414/Tsuki/actions/runs/25634532452))

_2026-05-10 17:00 UTC_

- fix(customsource): replace selectLast with select().last() in KeyoappHtmlParser (fb89410)
- fix(customsource): remove SortOrder.ALPHABETICAL_DESC and fix IkenApiParser authors (6d41475)
- feat(customsource): add 14 new CMS parser types (batch 3) (4ff6a6f)
- fix: workflow concurrency + dropdown non-filtering adapter (ae15d1b)


## Alpha 1.393 (Build [#83](https://github.com/Space4414/Tsuki/actions/runs/25631295973))

_2026-05-10 14:30 UTC_

- fix: workflow concurrency + dropdown non-filtering adapter (ae15d1b)


## Alpha 1.379 (Build [#70](https://github.com/Space4414/Tsuki/actions/runs/25630236487))

_2026-05-10 13:41 UTC_

- feat: add MangaPill parser (img.js-page reader) (1c7cc33)


## Alpha 1.364 (Build [#56](https://github.com/Space4414/Tsuki/actions/runs/25629719567))

_2026-05-10 13:16 UTC_

- feat: add ComicK REST API parser (comick.io) (bccfe4a)
- feat: add ComixTo parser for comix.to and clones (dae13e3)


## Stable 1.4.6 (Build [#5](https://github.com/Space4414/Tsuki/actions/runs/25599286527))

_2026-05-09 10:50 UTC_

- remove: dead KEY_LINK_GITHUB handler (Our Story section removed) (69dd9cf)
- remove: "Our Story" section from About settings (d477748)
- Update README.md (a5a672b)
- Update README.md (3397f13)
- feat: add space4414.github.io intent-filter for Tsuki share links (45c6c77)
- fix: resolve space4414.github.io/Tsuki/open share links in MangaLinkResolver (17ca831)
- fix: share URL → https://space4414.github.io/Tsuki/open (clickable in all apps) (5e1ed99)
- fix: resolve tsuki://manga deep links (host-based path check + isValidLink) (da7bf5c)
- feat: interval settings, notifications fix, download fix (AppUpdateActivity.kt) (3e2f680)
- feat: interval settings, notifications fix, download fix (pref_about.xml) (4c0f637)
- feat: interval settings, notifications fix, download fix (pref_tracker.xml) (f8e4ce1)
- feat: interval settings, notifications fix, download fix (WorkScheduleManager.kt) (b534743)
- feat: interval settings, notifications fix, download fix (AppUpdateWorker.kt) (91fdbf1)
- feat: interval settings, notifications fix, download fix (TrackWorker.kt) (4a9568d)
- feat: interval settings, notifications fix, download fix (AppSettings.kt) (3c312bd)
- feat: interval settings, notifications fix, download fix (constants.xml) (49afe79)
- feat: interval settings, notifications fix, download fix (arrays.xml) (5b2f4a0)
- feat: interval settings, notifications fix, download fix (strings.xml) (a9a3928)
- feat: resolve tsuki:// scheme deep links in MangaLinkResolver (ccf7f94)
- feat: use tsuki:// scheme for manga share links (69230bd)
- ci: add Discord role mentions to build-stable webhook notification (5f2571f)
- ci: add Discord role mentions to build-alpha webhook notification (bc9ee19)
- ci: add Discord role mentions to build-beta webhook notification (3d06372)
- feat(ci): add Discord webhook notifications to all release workflows (95dadc2)
- feat: app-wide immersive mode for Android 6+ (API 23+) (78e53dc)
- feat: smooth IME animation + haptic feedback for API 23+ devices (5fb9d89)
- fix: IME keyboard overlap on password screens and predictive-back double animation (b66bdd7)
- fix: reader cutout mode on Android 12+ and action mode status bar on Android 15+ (262b959)
- fix: nav bar blur on Android 12+ and toggleable performance mode (48ef288)
- Update CONTRIBUTING.md (121e0df)
- ci: support manual [Unreleased] release notes in CHANGELOG.md (654aa2f)
- brand: add Tsuki Project badge to README and release footers (e8d9c6e)
- ci: replace bare #N build refs with Tsuki CI run links (487dee5)
- fix: correct 3 remaining update-checker bugs for stable APK (861743b)
- fix: stable channel now matches stable-latest CI tag (07b77d6)


## Alpha 1.360 (Build [#54](https://github.com/Space4414/Tsuki/actions/runs/25588051696))

_2026-05-09 01:40 UTC_

- remove: dead KEY_LINK_GITHUB handler (Our Story section removed) (69dd9cf)
- remove: "Our Story" section from About settings (d477748)


## Alpha 1.356 (Build [#51](https://github.com/Space4414/Tsuki/actions/runs/25587102444))

_2026-05-09 01:02 UTC_

- Update README.md (3397f13)


## Alpha 1.354 (Build [#50](https://github.com/Space4414/Tsuki/actions/runs/25557489581))

_2026-05-08 13:12 UTC_

- feat: add space4414.github.io intent-filter for Tsuki share links (45c6c77)
- fix: resolve space4414.github.io/Tsuki/open share links in MangaLinkResolver (17ca831)
- fix: share URL → https://space4414.github.io/Tsuki/open (clickable in all apps) (5e1ed99)
- fix: resolve tsuki://manga deep links (host-based path check + isValidLink) (da7bf5c)


## Alpha 1.350 (Build [#47](https://github.com/Space4414/Tsuki/actions/runs/25555493331))

_2026-05-08 12:27 UTC_

- fix: resolve tsuki://manga deep links (host-based path check + isValidLink) (da7bf5c)


## Alpha 1.342 (Build [#40](https://github.com/Space4414/Tsuki/actions/runs/25554072992))

_2026-05-08 11:52 UTC_

- feat: interval settings, notifications fix, download fix (AppSettings.kt) (3c312bd)
- feat: interval settings, notifications fix, download fix (constants.xml) (49afe79)
- feat: interval settings, notifications fix, download fix (arrays.xml) (5b2f4a0)
- feat: interval settings, notifications fix, download fix (strings.xml) (a9a3928)


## Alpha 1.336 (Build [#35](https://github.com/Space4414/Tsuki/actions/runs/25553542677))

_2026-05-08 11:39 UTC_

- feat: use tsuki:// scheme for manga share links (69230bd)


## Alpha 1.333 (Build [#33](https://github.com/Space4414/Tsuki/actions/runs/25551716628))

_2026-05-08 10:55 UTC_

- ci: add Discord role mentions to build-alpha webhook notification (bc9ee19)
- ci: add Discord role mentions to build-beta webhook notification (3d06372)
- feat(ci): add Discord webhook notifications to all release workflows (95dadc2)


## Stable 1.4.5 (Build [#4](https://github.com/Space4414/Tsuki/actions/runs/25550193582))

_2026-05-08 10:18 UTC_

- feat(ci): add Discord webhook notifications to all release workflows (95dadc2)
- feat: app-wide immersive mode for Android 6+ (API 23+) (78e53dc)
- feat: smooth IME animation + haptic feedback for API 23+ devices (5fb9d89)
- fix: IME keyboard overlap on password screens and predictive-back double animation (b66bdd7)
- fix: reader cutout mode on Android 12+ and action mode status bar on Android 15+ (262b959)
- fix: nav bar blur on Android 12+ and toggleable performance mode (48ef288)
- Update CONTRIBUTING.md (121e0df)
- ci: support manual [Unreleased] release notes in CHANGELOG.md (654aa2f)
- brand: add Tsuki Project badge to README and release footers (e8d9c6e)
- ci: replace bare #N build refs with Tsuki CI run links (487dee5)
- fix: correct 3 remaining update-checker bugs for stable APK (861743b)
- fix: stable channel now matches stable-latest CI tag (07b77d6)


## Alpha 1.329 (Build [#31](https://github.com/Space4414/Tsuki/actions/runs/25549449569))

_2026-05-08 10:01 UTC_

- feat(ci): add Discord webhook notifications to all release workflows (95dadc2)
- feat: app-wide immersive mode for Android 6+ (API 23+) (78e53dc)


## Alpha 1.327 (Build [#30](https://github.com/Space4414/Tsuki/actions/runs/25541104547))

_2026-05-08 06:39 UTC_

- feat: app-wide immersive mode for Android 6+ (API 23+) (78e53dc)
- feat: smooth IME animation + haptic feedback for API 23+ devices (5fb9d89)


## Alpha 1.325 (Build [#29](https://github.com/Space4414/Tsuki/actions/runs/25539992197))

_2026-05-08 06:09 UTC_

- feat: smooth IME animation + haptic feedback for API 23+ devices (5fb9d89)
- fix: IME keyboard overlap on password screens and predictive-back double animation (b66bdd7)


## Alpha 1.323 (Build [#28](https://github.com/Space4414/Tsuki/actions/runs/25539639814))

_2026-05-08 05:58 UTC_

- fix: IME keyboard overlap on password screens and predictive-back double animation (b66bdd7)
- fix: reader cutout mode on Android 12+ and action mode status bar on Android 15+ (262b959)


## Alpha 1.321 (Build [#27](https://github.com/Space4414/Tsuki/actions/runs/25539226137))

_2026-05-08 05:46 UTC_

- fix: reader cutout mode on Android 12+ and action mode status bar on Android 15+ (262b959)
- fix: nav bar blur on Android 12+ and toggleable performance mode (48ef288)


## Alpha 1.319 (Build [#26](https://github.com/Space4414/Tsuki/actions/runs/25538946372))

_2026-05-08 05:37 UTC_

- fix: nav bar blur on Android 12+ and toggleable performance mode (48ef288)
- Update CONTRIBUTING.md (121e0df)


## Alpha 1.317 (Build [#25](https://github.com/Space4414/Tsuki/actions/runs/25531665198))

_2026-05-08 01:36 UTC_

- Update CONTRIBUTING.md (121e0df)
- ci: support manual [Unreleased] release notes in CHANGELOG.md (654aa2f)
- brand: add Tsuki Project badge to README and release footers (e8d9c6e)


## Stable 1.4.4 (Build [#3](https://github.com/Space4414/Tsuki/actions/runs/25501741547))

_2026-05-07 14:22 UTC_

- ci: support manual [Unreleased] release notes in CHANGELOG.md (654aa2f)
- brand: add Tsuki Project badge to README and release footers (e8d9c6e)
- ci: replace bare #N build refs with Tsuki CI run links (487dee5)
- fix: correct 3 remaining update-checker bugs for stable APK (861743b)
- fix: stable channel now matches stable-latest CI tag (07b77d6)
- fix: add LEGACY_SYNC_ACCOUNT_TYPE BuildConfig field to all flavors (f6130b8)
- feat: sync health worker + in-app update banner (6ecbabe)
- feat: fallback migration banner detection via had_sync_account flag (57a3f45)
- feat: write had_sync_account flag in SyncController for migration fallback detection (a770326)
- feat: call removeLegacySyncAccount before firing migration banner (ea24cf0)
- feat: show sync migration Snackbar in MainActivity (a8e2a12)
- feat: fire onShowSyncMigrationBanner event in MainViewModel init (456e135)
- fix: migrate Stable flavor authorities to com.space4414.tsuki branding (c9c14d7)
- fix: give Beta flavor unique ContentProvider authorities and account type (98550b9)
- fix: give Alpha flavor unique ContentProvider authorities and account type (8c28af5)


## Alpha 1.314 (Build [#24](https://github.com/Space4414/Tsuki/actions/runs/25485244590))

_2026-05-07 08:38 UTC_

- ci: support manual [Unreleased] release notes in CHANGELOG.md (654aa2f)
- brand: add Tsuki Project badge to README and release footers (e8d9c6e)


## Alpha 1.312 (Build [#23](https://github.com/Space4414/Tsuki/actions/runs/25484797825))

_2026-05-07 08:28 UTC_

- brand: add Tsuki Project badge to README and release footers (e8d9c6e)


## Alpha 1.309 (Build #21)

_2026-05-07 08:11 UTC_

- fix: correct 3 remaining update-checker bugs for stable APK (861743b)
- fix: stable channel now matches stable-latest CI tag (07b77d6)


## Alpha 1.307 (Build #20)

_2026-05-07 03:31 UTC_

- fix: stable channel now matches stable-latest CI tag (07b77d6)
- fix: add LEGACY_SYNC_ACCOUNT_TYPE BuildConfig field to all flavors (f6130b8)
- feat: sync health worker + in-app update banner (6ecbabe)
- feat: fallback migration banner detection via had_sync_account flag (57a3f45)
- feat: write had_sync_account flag in SyncController for migration fallback detection (a770326)
- feat: call removeLegacySyncAccount before firing migration banner (ea24cf0)


## Stable v1.4.3 (Build #2)

_2026-05-07 02:10 UTC_

- fix: add LEGACY_SYNC_ACCOUNT_TYPE BuildConfig field to all flavors (f6130b8)
- feat: sync health worker + in-app update banner (6ecbabe)
- feat: fallback migration banner detection via had_sync_account flag (57a3f45)
- feat: write had_sync_account flag in SyncController for migration fallback detection (a770326)
- feat: call removeLegacySyncAccount before firing migration banner (ea24cf0)
- feat: show sync migration Snackbar in MainActivity (a8e2a12)
- feat: fire onShowSyncMigrationBanner event in MainViewModel init (456e135)
- fix: migrate Stable flavor authorities to com.space4414.tsuki branding (c9c14d7)
- fix: give Beta flavor unique ContentProvider authorities and account type (98550b9)
- fix: give Alpha flavor unique ContentProvider authorities and account type (8c28af5)
- ci: add draft mode input to Stable workflow (2166317)
- ci: add draft mode input to Beta workflow (95b6c91)
- ci: add release_notes input to Beta workflow (cff53c4)
- ci: add release_notes input to Stable workflow (87ab119)


## Alpha 1.304 (Build #19)

_2026-05-07 01:33 UTC_

- fix: add LEGACY_SYNC_ACCOUNT_TYPE BuildConfig field to all flavors (f6130b8)
- feat: sync health worker + in-app update banner (6ecbabe)
- feat: fallback migration banner detection via had_sync_account flag (57a3f45)
- feat: write had_sync_account flag in SyncController for migration fallback detection (a770326)
- feat: call removeLegacySyncAccount before firing migration banner (ea24cf0)
- feat: show sync migration Snackbar in MainActivity (a8e2a12)
- feat: fire onShowSyncMigrationBanner event in MainViewModel init (456e135)


## Alpha 1.294 (Build #11)

_2026-05-07 00:54 UTC_

- fix: give Beta flavor unique ContentProvider authorities and account type (98550b9)
- fix: give Alpha flavor unique ContentProvider authorities and account type (8c28af5)


## Stable 1.4.2 (Build #1)

_2026-05-07 00:29 UTC_

- ci: add draft mode input to Stable workflow (2166317)
- ci: add draft mode input to Beta workflow (95b6c91)
- ci: add release_notes input to Beta workflow (cff53c4)
- ci: add release_notes input to Stable workflow (87ab119)


## Alpha 1.289 (Build #8)

_2026-05-06 18:43 UTC_

- ci: add draft mode input to Beta workflow (95b6c91)


## Alpha 1.286 (Build #6)

_2026-05-06 18:30 UTC_

- ci: add release_notes input to Stable workflow (87ab119)


## Alpha 1.281 (Build #2)

_2026-05-06 18:05 UTC_

- ci: add build-beta.yml — manual-only Beta builds (5556b38)
- ci: add build-alpha.yml — auto-builds Alpha on every push (4e7d2d6)
- fix: restore com.space4414.tsuki base ID for alpha and beta flavors (e46978f)


## Stable 1.278 (Build #228)

_2026-05-06 17:55 UTC_

- fix: restore com.space4414.tsuki base ID for alpha and beta flavors (e46978f)
- fix: update notifications + faster chapter checks (7d09391)


## Beta 1.276 (Build #227)

_2026-05-06 16:02 UTC_

- fix: update notifications + faster chapter checks (7d09391)


## Alpha 1.273 (Build #225)

_2026-05-06 13:37 UTC_

- Update build.yml (ed24307)
- fix: stable signing key + arm32 APK naming for Huawei P9 Lite (4e5a894)


## Alpha 1.271 (Build #224)

_2026-05-06 09:04 UTC_

- fix: stable signing key + arm32 APK naming for Huawei P9 Lite (4e5a894)


## Beta 1.267 (Build #221)

_2026-05-06 08:23 UTC_

- fix(flavors): change alpha/beta applicationIds to avoid MIUI install conflict (f2bc817)
- fix(ci): fix release.yml top-level YAML indentation; exclude floating tags from trigger (71c88b0)


## Beta 1.265 (Build #220)

_2026-05-06 07:50 UTC_

- fix(ci): fix release.yml top-level YAML indentation; exclude floating tags from trigger (71c88b0)
- fix(strings): escape apostrophe in whats_new_in (What\'s new in %s) (43196da)
- fix(whats-new): remove conflicting onCreateView; onCreateDialog+setView() is sufficient (dc701f1)
- fix(update): strip accidental 2-space over-indentation from entire file (71ffbd7)
- fix(strings): escape apostrophe in whats_new_in; fix indentation of new strings (82667ea)


## Beta 1.263 (Build #219)

_2026-05-06 07:20 UTC_

- fix(strings): escape apostrophe in whats_new_in (What\'s new in %s) (43196da)
- fix(whats-new): remove conflicting onCreateView; onCreateDialog+setView() is sufficient (dc701f1)
- fix(update): strip accidental 2-space over-indentation from entire file (71ffbd7)
- fix(strings): escape apostrophe in whats_new_in; fix indentation of new strings (82667ea)


## Stable 1.248 (Build #206)

_2026-05-06 07:01 UTC_

- build: add UPDATE_CHANNEL and RELEASES_URL buildConfigFields per productFlavor (41fb80b)


## Alpha 1.241 (Build #200)

_2026-05-06 06:47 UTC_

- feat(icons): alpha flavor uses amber background to distinguish from stable (32a5d1f)
- fix(notifications): BigPictureStyle with cover image; InboxStyle kept for group summary (1e5109e)


## Stable 1.233 (Build #193)

_2026-05-06 06:24 UTC_

- ci: release all three flavor variants in matrix (1cdb30a)
- ci: build all three flavor variants separately in matrix (2cf71fe)
- build: add alpha/beta/stable productFlavors; remove alpha buildType (2421d47)
- feat(customsource): comprehensive custom source improvements (4ae720d)
- refactor: rename applicationId from space4414.tsuki to com.space4414.tsuki (7d03294)
- fix: resolve 7 compile errors + expand fingerprint probes to 17 CMS families (f1efbc0)
- fix: source name always from user input; add Madara fingerprint probe (1c810e6)
- feat: API fingerprinting + domain override for truly new Kotatsu-compatible sites (c91b7d4)
- feat: Kotatsu parser auto-match + genre filters for Madara/Themesia/MangaStream (4722e7c)
- feat(custom-sources): Edit Source screen (997a0c0)
- fix(cms-detector): remove escaped quotes that broke Kotlin syntax (2cd3645)
- feat(custom-sources): 4 new parsers, label consistency, detected-as toast (ef1e913)
- fix(custom-sources): exhaustive when + add CMS auto-detect feature (d1041a3)
- feat(custom-sources): add 5 new CMS parsers for custom sources (98bbc16)
- feat(import-export): add import/export string resources (67f02be)
- feat(import-export): add import/export UI (file picker + menu) to CustomSourcesSettingsFragment (a3b4fb4)
- feat(import-export): add opt_custom_sources menu with import/export actions (6f7f2ec)
- feat(import-export): expose exportSourcesJson() and importSourcesJson() in CustomSourceViewModel (b73e9ec)
- feat(import-export): add exportJson() and importJson() to CustomSourcesRepository (61114d7)
- fix: guard viewBinding in BrowserActivity.onStop to prevent crash when WebView unavailable (56c85a1)
- fix: fixProtocol on String? receiver in GenkanHtmlParser (a8258a8)
- fix: fixProtocol on String? receiver in MadaraHtmlParser (973cf99)
- feat: open GENKAN sources as list view in CustomSourcesSettingsFragment (c51341c)
- feat: add GENKAN URL hint in AddCustomSourceSheet (7549988)
- feat: dispatch GENKAN type to GenkanHtmlParser in CustomMangaRepository (f274b80)
- feat: add GENKAN enum to CustomSourceType (c5e2c7e)
- feat: add Genkan scanlation CMS auto-parser (GenkanHtmlParser) (200b829)
- feat: open MADARA sources as list view in CustomSourcesSettingsFragment (0f87d18)
- feat: add MADARA URL hint in AddCustomSourceSheet (8b78d46)
- feat: dispatch MADARA type to MadaraHtmlParser in CustomMangaRepository (f6a9ab4)
- feat: add MADARA enum to CustomSourceType (f48ec7f)
- feat: add WordPress Madara auto-parser (MadaraHtmlParser) (9ec6fb3)
- feat(customsource): add saveLastUrl/getLastUrl to CustomSourcesRepository (6802d52)
- feat(browser): remember last visited URL per WebView custom source (5806e9c)
- fix(explore): WebView custom sources now open BrowserActivity on tap (c77afa8)
- remove: search bar blur effect only (keep transparent search bar toggle) (e90e4ff)
- fix: glassy explore buttons, search bar blur fix, transparent search bar toggle (aa6f428)


## Alpha-1.229 (Build #190)

_2026-05-06 00:35 UTC_

- feat(customsource): comprehensive custom source improvements (4ae720d)
- refactor: rename applicationId from space4414.tsuki to com.space4414.tsuki (7d03294)


## Alpha-1.227 (Build #189)

_2026-05-06 00:17 UTC_

- refactor: rename applicationId from space4414.tsuki to com.space4414.tsuki (7d03294)
- fix: resolve 7 compile errors + expand fingerprint probes to 17 CMS families (f1efbc0)
- fix: source name always from user input; add Madara fingerprint probe (1c810e6)
- feat: API fingerprinting + domain override for truly new Kotatsu-compatible sites (c91b7d4)
- feat: Kotatsu parser auto-match + genre filters for Madara/Themesia/MangaStream (4722e7c)


## Alpha-1.225 (Build #188)

_2026-05-05 17:24 UTC_

- fix: resolve 7 compile errors + expand fingerprint probes to 17 CMS families (f1efbc0)
- fix: source name always from user input; add Madara fingerprint probe (1c810e6)
- feat: API fingerprinting + domain override for truly new Kotatsu-compatible sites (c91b7d4)
- feat: Kotatsu parser auto-match + genre filters for Madara/Themesia/MangaStream (4722e7c)
- feat(custom-sources): Edit Source screen (997a0c0)


## Alpha-1.220 (Build #184)

_2026-05-05 12:34 UTC_

- feat(custom-sources): Edit Source screen (997a0c0)
- fix(cms-detector): remove escaped quotes that broke Kotlin syntax (2cd3645)
- feat(custom-sources): 4 new parsers, label consistency, detected-as toast (ef1e913)


## Alpha-1.218 (Build #183)

_2026-05-05 12:18 UTC_

- fix(cms-detector): remove escaped quotes that broke Kotlin syntax (2cd3645)
- feat(custom-sources): 4 new parsers, label consistency, detected-as toast (ef1e913)
- fix(custom-sources): exhaustive when + add CMS auto-detect feature (d1041a3)
- feat(custom-sources): add 5 new CMS parsers for custom sources (98bbc16)


## Alpha-1.215 (Build #181)

_2026-05-05 11:35 UTC_

- fix(custom-sources): exhaustive when + add CMS auto-detect feature (d1041a3)
- feat(custom-sources): add 5 new CMS parsers for custom sources (98bbc16)


## Alpha-1.208 (Build #175)

_2026-05-05 07:36 UTC_

- feat(import-export): add exportJson() and importJson() to CustomSourcesRepository (61114d7)
- fix: guard viewBinding in BrowserActivity.onStop to prevent crash when WebView unavailable (56c85a1)
- fix: fixProtocol on String? receiver in GenkanHtmlParser (a8258a8)
- fix: fixProtocol on String? receiver in MadaraHtmlParser (973cf99)
- feat: open GENKAN sources as list view in CustomSourcesSettingsFragment (c51341c)
- feat: add GENKAN URL hint in AddCustomSourceSheet (7549988)
- feat: dispatch GENKAN type to GenkanHtmlParser in CustomMangaRepository (f274b80)
- feat: add GENKAN enum to CustomSourceType (c5e2c7e)
- feat: add Genkan scanlation CMS auto-parser (GenkanHtmlParser) (200b829)
- feat: open MADARA sources as list view in CustomSourcesSettingsFragment (0f87d18)
- feat: add MADARA URL hint in AddCustomSourceSheet (8b78d46)
- feat: dispatch MADARA type to MadaraHtmlParser in CustomMangaRepository (f6a9ab4)
- feat: add MADARA enum to CustomSourceType (f48ec7f)
- feat: add WordPress Madara auto-parser (MadaraHtmlParser) (9ec6fb3)


## Alpha-1.205 (Build #173)

_2026-05-05 07:12 UTC_

- fix: fixProtocol on String? receiver in GenkanHtmlParser (a8258a8)
- fix: fixProtocol on String? receiver in MadaraHtmlParser (973cf99)
- feat: open GENKAN sources as list view in CustomSourcesSettingsFragment (c51341c)
- feat: add GENKAN URL hint in AddCustomSourceSheet (7549988)
- feat: dispatch GENKAN type to GenkanHtmlParser in CustomMangaRepository (f274b80)
- feat: add GENKAN enum to CustomSourceType (c5e2c7e)
- feat: add Genkan scanlation CMS auto-parser (GenkanHtmlParser) (200b829)
- feat: open MADARA sources as list view in CustomSourcesSettingsFragment (0f87d18)
- feat: add MADARA URL hint in AddCustomSourceSheet (8b78d46)
- feat: dispatch MADARA type to MadaraHtmlParser in CustomMangaRepository (f6a9ab4)
- feat: add MADARA enum to CustomSourceType (f48ec7f)
- feat: add WordPress Madara auto-parser (MadaraHtmlParser) (9ec6fb3)
- feat(customsource): add saveLastUrl/getLastUrl to CustomSourcesRepository (6802d52)
- feat(browser): remember last visited URL per WebView custom source (5806e9c)


## Alpha-1.192 (Build #161)

_2026-05-05 05:52 UTC_

- feat(customsource): add saveLastUrl/getLastUrl to CustomSourcesRepository (6802d52)
- feat(browser): remember last visited URL per WebView custom source (5806e9c)
- fix(explore): WebView custom sources now open BrowserActivity on tap (c77afa8)


## Alpha-1.189 (Build #159)

_2026-05-05 05:31 UTC_

- fix(explore): WebView custom sources now open BrowserActivity on tap (c77afa8)
- remove: search bar blur effect only (keep transparent search bar toggle) (e90e4ff)


## Alpha-1.187 (Build #158)

_2026-05-05 04:26 UTC_

- remove: search bar blur effect only (keep transparent search bar toggle) (e90e4ff)
- fix: glassy explore buttons, search bar blur fix, transparent search bar toggle (aa6f428)


## Alpha-1.185 (Build #157)

_2026-05-05 00:11 UTC_

- fix: glassy explore buttons, search bar blur fix, transparent search bar toggle (aa6f428)


## Alpha-1.183 (Build #156)

_2026-05-04 10:54 UTC_



## Alpha-1.181 (Build #154)

_2026-05-04 10:37 UTC_

- feat(perf): add pref_performance.xml with blur fps/quality/idle-skip settings (f5fc96c)
- feat(perf): add Performance entry to root settings menu (5a08a12)
- feat(perf): add Performance settings string resources (2a78ef8)
- feat(perf): wire blur performance settings (fps, quality, idle-skip) in applyBarBlur (4503ac8)
- feat(perf): add blurFps, blurCaptureQuality, isBlurIdleSkipEnabled prefs (a4b1e3b)
- feat(perf): add setFrameRate/setCaptureQuality/setIdleSkip to BlurBehindView (f290ca6)
- feat: apply blur tint in applyBarBlur() — frosted-glass white overlay with per-bar opacity setting (6638c57)
- feat: add PercentSummaryProvider for blur tint sliders in AppearanceSettingsFragment (662a8eb)
- feat: add tint opacity sliders for nav/search blur in Appearance settings (7394f52)
- feat: add nav_bar_blur_tint + search_bar_blur_tint string resources (dc8e97f)
- feat: add navBarBlurTintAlpha + searchBarBlurTintAlpha prefs (default 30%) (1ac7f83)
- feat: add white frosted-glass tint overlay to BlurBehindView (setBlurTint 0-100) (356a16d)


## Alpha-1.170 (Build #144)

_2026-05-04 10:18 UTC_

- feat: add navBarBlurTintAlpha + searchBarBlurTintAlpha prefs (default 30%) (1ac7f83)
- feat: add white frosted-glass tint overlay to BlurBehindView (setBlurTint 0-100) (356a16d)
- fix: use root CoordinatorLayout as blur source so background image is captured (fixes invisible blur) (9f939eb)
- fix: hide self during source.draw() so blur captures full background without recursion (22322bd)


## Alpha-1.166 (Build #141)

_2026-05-04 10:05 UTC_

- fix: hide self during source.draw() so blur captures full background without recursion (22322bd)


## Alpha-1.159 (Build #135)

_2026-05-04 09:46 UTC_

- fix: transparent background + invisible-by-default on BlurBehindViews (fixes black shadow) (552b927)
- fix: move applyBarBlur() inside MainActivity class (was prepended before package declaration causing KSP build failure) (654b307)


## Alpha-1.157 (Build #134)

_2026-05-04 07:59 UTC_

- fix: move applyBarBlur() inside MainActivity class (was prepended before package declaration causing KSP build failure) (654b307)


## Alpha-1.150 (Build #127)

_2026-05-04 06:59 UTC_

- feat: add searchBlurView and navBlurView to activity_main layout (f3d2ce8)
- feat: add BlurBehindView for real-time frosted-glass blur effect (f6f3238)
- Update constants.xml (7900d7a)


## Alpha-1.147 (Build #125)

_2026-05-04 06:18 UTC_

- Update constants.xml (7900d7a)


## Alpha-1.138 (Build #118)

_2026-05-04 03:34 UTC_

- feat: add transparent_nav_bar string resources (a67e941)
- feat: add Transparent Navigation Bar toggle to Appearance settings (15c4d1b)


## Alpha-1.135 (Build #116)

_2026-05-04 00:36 UTC_



## Alpha-1.132 (Build #113)

_2026-05-04 00:00 UTC_

- fix: change AniList OAuth redirect URI from futon:// to tsuki:// (7704959)
- Update README.md (9e36e24)


## Alpha-1.130 (Build #112)

_2026-05-03 17:58 UTC_

- Update README.md (9e36e24)
- Update constants.xml (277f278)


## Alpha-1.128 (Build #111)

_2026-05-03 17:46 UTC_

- Update constants.xml (277f278)
- fix: add glass_search_fill_amoled base declaration to values/colors.xml (41176ed)
- fix: restore glass SearchBar transparency; fix AMOLED black-box (f5ec7c1)
- fix: restore glass SearchBar; fix AMOLED black-box via theme style (0fcc5b1)


## Alpha-1.126 (Build #110)

_2026-05-03 12:06 UTC_

- fix: add glass_search_fill_amoled base declaration to values/colors.xml (41176ed)
- fix: restore glass SearchBar transparency; fix AMOLED black-box (f5ec7c1)
- fix: restore glass SearchBar; fix AMOLED black-box via theme style (0fcc5b1)
- fix: AMOLED SearchBar black rectangle — pill drawable instead of backgroundTint (7426d50)


## Alpha-1.122 (Build #107)

_2026-05-03 11:18 UTC_

- fix: AMOLED SearchBar black rectangle — pill drawable instead of backgroundTint (7426d50)
- fix: revert menuIconEnabled (unsupported attr), fix 3-dot via MainMenuProvider (2942daa)
- fix: 5 bugs — alpha-only pre-releases, Tsuki α name, book icon notification, no overflow 3-dot in SearchBar, explicit alpha/nightly sourceSets (d5b68f0)


## Alpha-1.120 (Build #106)

_2026-05-03 10:52 UTC_

- fix: revert menuIconEnabled (unsupported attr), fix 3-dot via MainMenuProvider (2942daa)
- fix: 5 bugs — alpha-only pre-releases, Tsuki α name, book icon notification, no overflow 3-dot in SearchBar, explicit alpha/nightly sourceSets (d5b68f0)
- feat: fix notifications, OAuth, update alerts, alpha build (f96ffb9)


## Alpha-1.117 (Build #104)

_2026-05-03 10:18 UTC_

- feat: fix notifications, OAuth, update alerts, alpha build (f96ffb9)
- fix: Explore tab background not showing (1495611)


## Alpha-1.115 (Build #103)

_2026-05-03 08:30 UTC_

- fix: Explore tab background not showing (1495611)
- feat: random bg rotation for Favourites, latest cover for Feed, suggestion cover for Explore (32402b0)


## Alpha-1.113 (Build #102)

_2026-05-03 08:02 UTC_

- feat: random bg rotation for Favourites, latest cover for Feed, suggestion cover for Explore (32402b0)
- fix: smooth RS blur, remove double-dim, glass chapter sheet, slider % (48dca80)


## Alpha-1.111 (Build #101)

_2026-05-03 06:52 UTC_

- fix: smooth RS blur, remove double-dim, glass chapter sheet, slider % (48dca80)
- fix: remove stale action_downloads/incognito/settings refs from MainMenuProvider (cf9f6c9)
- fix: 6 UI bugs — seamless bg, heavy blur on API24, no 3-dot overflow, floating SearchBar, incognito btn, blur slider value (b56bcd9)


## Alpha-1.109 (Build #100)

_2026-05-03 06:05 UTC_

- fix: remove stale action_downloads/incognito/settings refs from MainMenuProvider (cf9f6c9)
- fix: 6 UI bugs — seamless bg, heavy blur on API24, no 3-dot overflow, floating SearchBar, incognito btn, blur slider value (b56bcd9)
- feat: Dantotsu-style full-screen bg, blur slider, glass icon buttons, activity-level backdrop (3116d43)


## Alpha-1.106 (Build #98)

_2026-05-03 04:24 UTC_

- feat: Dantotsu-style full-screen bg, blur slider, glass icon buttons, activity-level backdrop (3116d43)
- feat: Tsuki title, adaptive glass search bar, fix nav pill height (af0bf92)


## Alpha-1.104 (Build #97)

_2026-05-03 00:22 UTC_

- feat: Tsuki title, adaptive glass search bar, fix nav pill height (af0bf92)
- fix: remove invalid indicatorHeight/indicatorColor attrs from BottomNav style\n\nMaterial3Expressive NavigationBarView does not expose indicatorHeight or\nindicatorColor as style attributes (they are not declared in the library's\nattrs.xml under those names). Removing them fixes the processReleaseResources\nAAP2 link failure. The pill outline-clip (ViewOutlineProvider) already prevents\nthe active indicator from overflowing the pill boundary. (6ad0b75)
- fix: glass nav centering, indicator clip, glass search bar, consistent glass\n\n  - SearchBar: permanent glass dark tint (@color/glass_nav_fill) + white stroke\n    foreground (fg_glass_stroke_pill.xml) — matches pill nav on ALL tabs\n  - BottomNav: android:clipToOutline + programmatic pill-shaped ViewOutlineProvider\n    so the Material active indicator is clipped inside the pill boundary (no\n    more indicator bleeding over the pill edge)\n  - BottomNav: android:paddingTop=6dp — pushes items/indicator into the pill's\n    safe zone away from the rounded caps\n  - Widget.Tsuki.BottomNav style: indicatorHeight=36dp, itemPadding 6dp top/bottom,\n    minHeight=64dp, indicatorColor=chip_teal_fill — items stay centered in pill\n  - themes.xml: bottomNavigationStyle → Widget.Tsuki.BottomNav (was Material3Expressive\n    base which had no padding/size constraints for our pill shape)\n  - updateBarsForBackground: removed backgroundTintList overrides that were\n    setting nav/search to solid black (#B4000000) on bg-image tabs (killed\n    the glass shimmer) and to null on other tabs (reverted to surface color);\n    now both bars keep their drawable look at all times — only alpha changes\n  - Remove unused ColorStateList + Color imports (523a0bf)


## Alpha-1.102 (Build #96)

_2026-05-02 23:40 UTC_

- fix: remove invalid indicatorHeight/indicatorColor attrs from BottomNav style\n\nMaterial3Expressive NavigationBarView does not expose indicatorHeight or\nindicatorColor as style attributes (they are not declared in the library's\nattrs.xml under those names). Removing them fixes the processReleaseResources\nAAP2 link failure. The pill outline-clip (ViewOutlineProvider) already prevents\nthe active indicator from overflowing the pill boundary. (6ad0b75)
- fix: glass nav centering, indicator clip, glass search bar, consistent glass\n\n  - SearchBar: permanent glass dark tint (@color/glass_nav_fill) + white stroke\n    foreground (fg_glass_stroke_pill.xml) — matches pill nav on ALL tabs\n  - BottomNav: android:clipToOutline + programmatic pill-shaped ViewOutlineProvider\n    so the Material active indicator is clipped inside the pill boundary (no\n    more indicator bleeding over the pill edge)\n  - BottomNav: android:paddingTop=6dp — pushes items/indicator into the pill's\n    safe zone away from the rounded caps\n  - Widget.Tsuki.BottomNav style: indicatorHeight=36dp, itemPadding 6dp top/bottom,\n    minHeight=64dp, indicatorColor=chip_teal_fill — items stay centered in pill\n  - themes.xml: bottomNavigationStyle → Widget.Tsuki.BottomNav (was Material3Expressive\n    base which had no padding/size constraints for our pill shape)\n  - updateBarsForBackground: removed backgroundTintList overrides that were\n    setting nav/search to solid black (#B4000000) on bg-image tabs (killed\n    the glass shimmer) and to null on other tabs (reverted to surface color);\n    now both bars keep their drawable look at all times — only alpha changes\n  - Remove unused ColorStateList + Color imports (523a0bf)
- fix: glass UI visibility and API 23 compat for Android 7+\n\n  - glass_panel_fill: #14000000 (8% black, invisible) → #33FFFFFF (20% white,\n    visible frosted panel on any background colour)\n  - glass_panel_gradient_start/end: black-tinted → white-based (#4DFFFFFF/#1AFFFFFF)\n  - night overrides: same fix — white-based so panels show on dark artwork\n  - glass_stroke: #26FFFFFF (15%) → #66FFFFFF (40%) — brighter rim highlight\n  - glass_nav_fill: near-opaque teal-white → #D9000000 (85% dark pill)\n  - chip_teal_fill: darkened to #332DD4BF (20% teal) for legibility\n  - bg_glass_panel.xml: add solid white base layer + top-gloss gradient\n  - bg_bottom_nav_pill.xml: add top shimmer gradient for glass depth\n  - New bg_teal_glow.xml: concentric teal rings to simulate FAB glow halo\n  - activity_main: layout_marginHorizontal → marginStart + marginEnd\n    (marginHorizontal is API 26+, minSdk is 23 — fixes pill nav on Android 7)\n  - activity_main: FAB elevation 10dp for shadow depth + marginHorizontal fix\n  - GlassEffectHelper: add blurImageView() — fast downscale-upscale blur for\n    API 23-30 (API 31+ still uses hardware RenderEffect)\n  - DetailsActivity: call blurImageView via view.post{} after loadCover() (5b92f4d)


## Alpha-1.99 (Build #94)

_2026-05-02 18:48 UTC_

- fix: glass UI visibility and API 23 compat for Android 7+\n\n  - glass_panel_fill: #14000000 (8% black, invisible) → #33FFFFFF (20% white,\n    visible frosted panel on any background colour)\n  - glass_panel_gradient_start/end: black-tinted → white-based (#4DFFFFFF/#1AFFFFFF)\n  - night overrides: same fix — white-based so panels show on dark artwork\n  - glass_stroke: #26FFFFFF (15%) → #66FFFFFF (40%) — brighter rim highlight\n  - glass_nav_fill: near-opaque teal-white → #D9000000 (85% dark pill)\n  - chip_teal_fill: darkened to #332DD4BF (20% teal) for legibility\n  - bg_glass_panel.xml: add solid white base layer + top-gloss gradient\n  - bg_bottom_nav_pill.xml: add top shimmer gradient for glass depth\n  - New bg_teal_glow.xml: concentric teal rings to simulate FAB glow halo\n  - activity_main: layout_marginHorizontal → marginStart + marginEnd\n    (marginHorizontal is API 26+, minSdk is 23 — fixes pill nav on Android 7)\n  - activity_main: FAB elevation 10dp for shadow depth + marginHorizontal fix\n  - GlassEffectHelper: add blurImageView() — fast downscale-upscale blur for\n    API 23-30 (API 31+ still uses hardware RenderEffect)\n  - DetailsActivity: call blurImageView via view.post{} after loadCover() (5b92f4d)
- feat: Structured Glass UI overhaul\n\n  - Add GlassEffectHelper.kt (API 31+ RenderEffect blur)\n  - Add drawable: bg_glass_panel, bg_bottom_nav_pill, bg_chip_teal, bg_scrim_dark\n  - Add glass color tokens: glass_teal #2DD4BF, glass_coral_red #FF6B6B,\n    glass_stroke #26FFFFFF, glass_panel_fill/gradient, glass_nav_fill,\n    chip_teal_fill, glass_scrim; night overrides in values-night/colors.xml\n  - Add Widget.Tsuki.GlassPanel, Chip.TealGlass, Button.ContinueTeal styles\n  - activity_main: floating pill BottomNav (12dp margins, glass pill bg),\n    FAB backgroundTint=glass_teal\n  - activity_details: alpha 0.4→0.5 on blur bg, add 40% black scrim layer\n  - layout_details_table: card_details → GlassPanel, progress tint=glass_teal\n  - item_scrobbling_info: GlassPanel card, teal icon bg/on_glass_teal tint\n  - DetailsActivity: import & call GlassEffectHelper.applyBlurBackground (122ab35)


## Alpha-1.97 (Build #93)

_2026-05-02 17:51 UTC_

- feat: Structured Glass UI overhaul\n\n  - Add GlassEffectHelper.kt (API 31+ RenderEffect blur)\n  - Add drawable: bg_glass_panel, bg_bottom_nav_pill, bg_chip_teal, bg_scrim_dark\n  - Add glass color tokens: glass_teal #2DD4BF, glass_coral_red #FF6B6B,\n    glass_stroke #26FFFFFF, glass_panel_fill/gradient, glass_nav_fill,\n    chip_teal_fill, glass_scrim; night overrides in values-night/colors.xml\n  - Add Widget.Tsuki.GlassPanel, Chip.TealGlass, Button.ContinueTeal styles\n  - activity_main: floating pill BottomNav (12dp margins, glass pill bg),\n    FAB backgroundTint=glass_teal\n  - activity_details: alpha 0.4→0.5 on blur bg, add 40% black scrim layer\n  - layout_details_table: card_details → GlassPanel, progress tint=glass_teal\n  - item_scrobbling_info: GlassPanel card, teal icon bg/on_glass_teal tint\n  - DetailsActivity: import & call GlassEffectHelper.applyBlurBackground (122ab35)
- fix: cover photo, adaptive bars, and tsuki:// OAuth scheme\n\n  - Add coverImage to ScrobblerUser; AniList fetches bannerImage,\n    Kitsu fetches coverImage; MAL/Shikimori fall back to avatar\n  - ScrobblerStorage updated to 5-line format (backward compatible)\n  - HistoryListViewModel, FavouritesListFragment, FeedFragment\n    use coverImage with avatar fallback for backgrounds\n  - MainActivity: adaptive transparent tint on SearchBar and\n    BottomNav when background image tab is active\n  - Redirect URIs updated futon:// -> tsuki://; tsuki:// scheme\n    handlers added to AndroidManifest alongside futon:// (cd7c97c)


## Alpha-1.95 (Build #92)

_2026-05-02 07:07 UTC_

- fix: cover photo, adaptive bars, and tsuki:// OAuth scheme\n\n  - Add coverImage to ScrobblerUser; AniList fetches bannerImage,\n    Kitsu fetches coverImage; MAL/Shikimori fall back to avatar\n  - ScrobblerStorage updated to 5-line format (backward compatible)\n  - HistoryListViewModel, FavouritesListFragment, FeedFragment\n    use coverImage with avatar fallback for backgrounds\n  - MainActivity: adaptive transparent tint on SearchBar and\n    BottomNav when background image tab is active\n  - Redirect URIs updated futon:// -> tsuki://; tsuki:// scheme\n    handlers added to AndroidManifest alongside futon:// (cd7c97c)
- fix: replace Futon splash logo with Tsuki crescent moon (Android 12+) (7064e1f)


## Alpha-1.93 (Build #91)

_2026-05-02 05:35 UTC_

- fix: replace Futon splash logo with Tsuki crescent moon (Android 12+) (7064e1f)
- chore: trigger clean post-refactor build [skip ci] (9dc492d)


## Alpha-1.91 (Build #90)

_2026-05-01 23:55 UTC_

- chore: trigger clean post-refactor build [skip ci] (9dc492d)


## Alpha-1.80 (Build #80)

_2026-05-01 23:44 UTC_

- fix: apply improvements to fragment_custom_sources.xml (16e6826)
- fix: apply improvements to AppearanceSettingsFragment.kt (cc22856)
- fix: apply improvements to SliderPreference.kt (88bfb0c)
- fix: remove .build() before .enqueueWith() in HistoryListFragment (308f221)
- fix: correct Coil3 API usage in HistoryListFragment (0be28d2)
- fix: decode keystore to app/ directory for Gradle signing config (1026200)
- fix: use GenericViewTarget + listener for Coil3 history background loading (e2a82b1)
- feat: custom sources instant refresh, badge fix, UI transparency, history background (1bee3b5)
- Update build.yml (7ede92d)
- UI overhaul (Dantotsu-style) + fix CI changelog push + auto-keystore in release.yml\n\nUI changes:\n- Deep violet/indigo accent color palette (dark theme now near-black with #0E0E13 bg)\n- Manga grid cards: title overlaid on cover art with gradient, MaterialCardView with 16dp corners\n- Cover corner radius increased to 16dp (large), 10dp (medium), 8dp (small)\n- Bottom sheets: 24dp corner radius\n- Navigation bar: transparent (true edge-to-edge)\n- Activity transitions: snappier fade+slide\n\nCI fix:\n- build.yml: git pull --rebase before changelog push to fix non-fast-forward rejection\n\nRelease workflow:\n- release.yml: auto-generates keystore with keytool if KEYSTORE_FILE secret not set\n- Supports both push-to-tag and workflow_dispatch triggers (ef7cec6)
- Rewrite release.yml: fix env var passing, per-ABI APKs, workflow_dispatch support (cc14739)
- Fix Discord RPC icon URL, update detection (prerelease->release), and WEBVIEW source browser launch\n\n- Fix app_icon_url path (was 404, now points to images/icon.png)\n- Set prerelease: false so updater detects new builds by default\n- Open in-app browser when tapping a WEBVIEW custom source instead of empty list (7bbeb24)


## Alpha-1.76 (Build #77)

_2026-05-01 11:28 UTC_

- fix: remove .build() before .enqueueWith() in HistoryListFragment (308f221)
- fix: correct Coil3 API usage in HistoryListFragment (0be28d2)
- fix: decode keystore to app/ directory for Gradle signing config (1026200)
- fix: use GenericViewTarget + listener for Coil3 history background loading (e2a82b1)
- feat: custom sources instant refresh, badge fix, UI transparency, history background (1bee3b5)
- Update build.yml (7ede92d)
- UI overhaul (Dantotsu-style) + fix CI changelog push + auto-keystore in release.yml\n\nUI changes:\n- Deep violet/indigo accent color palette (dark theme now near-black with #0E0E13 bg)\n- Manga grid cards: title overlaid on cover art with gradient, MaterialCardView with 16dp corners\n- Cover corner radius increased to 16dp (large), 10dp (medium), 8dp (small)\n- Bottom sheets: 24dp corner radius\n- Navigation bar: transparent (true edge-to-edge)\n- Activity transitions: snappier fade+slide\n\nCI fix:\n- build.yml: git pull --rebase before changelog push to fix non-fast-forward rejection\n\nRelease workflow:\n- release.yml: auto-generates keystore with keytool if KEYSTORE_FILE secret not set\n- Supports both push-to-tag and workflow_dispatch triggers (ef7cec6)
- Rewrite release.yml: fix env var passing, per-ABI APKs, workflow_dispatch support (cc14739)
- Fix Discord RPC icon URL, update detection (prerelease->release), and WEBVIEW source browser launch\n\n- Fix app_icon_url path (was 404, now points to images/icon.png)\n- Set prerelease: false so updater detects new builds by default\n- Open in-app browser when tapping a WEBVIEW custom source instead of empty list (7bbeb24)


## Alpha-1.69 (Build #71)

_2026-05-01 00:53 UTC_

- UI overhaul (Dantotsu-style) + fix CI changelog push + auto-keystore in release.yml\n\nUI changes:\n- Deep violet/indigo accent color palette (dark theme now near-black with #0E0E13 bg)\n- Manga grid cards: title overlaid on cover art with gradient, MaterialCardView with 16dp corners\n- Cover corner radius increased to 16dp (large), 10dp (medium), 8dp (small)\n- Bottom sheets: 24dp corner radius\n- Navigation bar: transparent (true edge-to-edge)\n- Activity transitions: snappier fade+slide\n\nCI fix:\n- build.yml: git pull --rebase before changelog push to fix non-fast-forward rejection\n\nRelease workflow:\n- release.yml: auto-generates keystore with keytool if KEYSTORE_FILE secret not set\n- Supports both push-to-tag and workflow_dispatch triggers (ef7cec6)
- Rewrite release.yml: fix env var passing, per-ABI APKs, workflow_dispatch support (cc14739)
- Fix Discord RPC icon URL, update detection (prerelease->release), and WEBVIEW source browser launch\n\n- Fix app_icon_url path (was 404, now points to images/icon.png)\n- Set prerelease: false so updater detects new builds by default\n- Open in-app browser when tapping a WEBVIEW custom source instead of empty list (7bbeb24)


## Alpha-1.67 (Build #68)

_2026-05-01 00:43 UTC_

- Rewrite release.yml: fix env var passing, per-ABI APKs, workflow_dispatch support (cc14739)
- Fix Discord RPC icon URL, update detection (prerelease->release), and WEBVIEW source browser launch\n\n- Fix app_icon_url path (was 404, now points to images/icon.png)\n- Set prerelease: false so updater detects new builds by default\n- Open in-app browser when tapping a WEBVIEW custom source instead of empty list (7bbeb24)
- Update README.md (a933454)
- Add files via upload (1976a4d)
- Delete metadata/en-US/images/icon.png (de8922e)


## Alpha-1.64 (Build #67)

_2026-04-30 23:48 UTC_

- Update README.md (a933454)
- Add files via upload (1976a4d)
- Delete metadata/en-US/images/icon.png (de8922e)


## Alpha-1.60 (Build #64)

_2026-04-30 17:03 UTC_

- Auto-generate CHANGELOG.md and use it as the release body (3276c27)
- Create the new-chapters notification channel at app start (1250373)
- Improve custom-source onboarding (2e8b062)
- Detect prereleases when checking for updates (ce9aa21)
- Add in-app Developer Mode toggle (cb3c223)
- Make debug builds appear as the real Tsuki app (1d85bad)
- Delete app/src/main/res/drawable/ic_launcher_moon_foreground.xml (911729d)
- Update ic_launcher_round.xml (1fb3fc2)
- Delete app/src/main/res/mipmap-xxxhdpi/ic_launcher_round.webp (a9118ba)
- Delete app/src/main/res/mipmap-xxxhdpi/ic_launcher.webp (159dab1)
- Add files via upload (ded6960)
- Add files via upload (1ebd846)
- Delete app/src/main/res/mipmap-xxhdpi/ic_launcher_round.webp (66dbb01)
- Delete app/src/main/res/mipmap-xxhdpi/ic_launcher.webp (5864093)
- Delete app/src/main/res/mipmap-xhdpi/ic_launcher_round.webp (547f5ec)
- Delete app/src/main/res/mipmap-xhdpi/ic_launcher.webp (2c2f2ea)
- Delete app/src/main/res/mipmap-mdpi/ic_launcher_round.webp (e4dfdcf)
- Delete app/src/main/res/mipmap-mdpi/ic_launcher.webp (1422263)
- Delete app/src/main/res/mipmap-hdpi/ic_launcher_round.webp (6ffb166)
- Delete app/src/main/res/mipmap-hdpi/ic_launcher.webp (31178f4)
- Add files via upload (278e4b6)
- Add files via upload (c2190ae)
- Add files via upload (7d110fd)
- Add files via upload (7070051)
- Add files via upload (c83d792)
- Add files via upload (39c49ab)
- Update constants.xml (fe97c7f)
- Update strings.xml (02158ff)
- Update AppUpdateActivity.kt (073bd84)
- Update strings.xml (abba09e)
- Update strings.xml (09be2cb)
- Update AppUpdateRepository.kt (a49dd8a)
- Update README.md (3787212)
- Update build.gradle (3694d35)
- Update build.yml (55272ac)
- Update build.yml (8c40938)
- Update CONTRIBUTING.md (cbdfa4b)
- Update README.md (c6b41cb)
- Inject CustomSourcesRepository in AppBackupAgent (9716b5a)
- Fix liftOnScroll attribute namespace in fragment_custom_sources (27633d8)
- Update AndroidManifest theme reference to Theme.Tsuki (665140f)
- Rename Futon -> Tsuki in style/theme identifiers (3820b7a)
- Rename futon_* color resources to tsuki_* (175e9b4)
- Make custom sources first-class in-app sources (930a80f)
- Rebrand Futon to Tsuki: silver crescent-moon icon, custom-source manager, ABI splits, Material animations, Arsveiled credit (c6ad0ea)
- Update themes.xml (067e094)
- Update build.gradle (ec97ee0)
- Update full_description.txt (5f974c6)
- Fix Kotlin compile errors: restore coverBackgroundAlpha, fix theme_name string refs (0328614)


# Changelog

All notable changes to this project are documented in this file.

The format is based on "Keep a Changelog" and follows semantic versioning where possible.

## [Unreleased]

### Tsuki rebrand & polish
- Renamed app to **Tsuki** with a new silver crescent‑moon adaptive icon
- New About section: rewritten copy, Space4414 source-code link.
- Discord Rich Presence text changed to "Watching Tsuki"
- Custom Sources: added a full management screen (list, open in WebView, delete) so user-added sources are now usable end-to-end
- Smooth fade & slide transitions added across activities and fragments
- All in-app icons normalized to Material 3 vector drawables
- APK size trimmed via per-ABI splits and tighter resource shrinking
- Removed unused agent / docs files left over from earlier Claude-AI iterations.

<!-- trigger-clean-build -->
