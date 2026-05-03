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
