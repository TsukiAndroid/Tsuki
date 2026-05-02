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
