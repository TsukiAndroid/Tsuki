<div align="center">

# 月 Tsuki

**A free and open-source manga/manhwa/manhua reader for Android — built by a newbie and an AI.**

![Android 6.0+](https://img.shields.io/badge/android-6.0+-brightgreen)
![Based on Futon](https://img.shields.io/badge/based%20on-Futon-orange)
![Built with Claude](https://img.shields.io/badge/built%20with-Claude%20AI-blueviolet)

</div>

---

## 🌙 Our Story

Tsuki started from the most relatable problem imaginable: a 17-year-old from Khulna, Bangladesh named Aanan just wanted to read manga on his old **Huawei P9 Lite running Android 7**.

What followed was a journey through SSL handshake errors, dead parser repos, and a graveyard of manga apps:

- **Kotatsu** — got taken down
- **Yukimi** — got taken down
- **Usagi** (Yukimi fork) — kept crashing
- **Yumemi** — build errors everywhere
- **Futon** — finally worked ✅

Along the way, Aanan teamed up with **Claude** (an AI made by Anthropic) to fix, fork, rename, and ship something that would actually survive. Aanan is a total newbie to coding — studying Physics, Math, Chemistry, and Biology for his college exams in Bangladesh. Claude wrote the code. Together they debugged GitHub Actions workflows, chased down dead JitPack dependencies, dealt with Gradle configuration cache errors, and somehow made it all work.

This is **Tsuki (月)** — the app that refused to die.

---

## ✨ Features

- 📚 **1200+ manga sources** built-in via kotatsu-parsers-redo
- 🌐 **Add custom sources** — paste any URL to read from any website via WebView
- 🎨 **Material You UI** — dynamic color theming on Android 12+
- 🖼️ **Adjustable cover background transparency** — control the blur/alpha on manga detail pages
- 📖 Standard and Webtoon-optimized reader
- ⬇️ Download manga for offline reading
- 📌 Favorites, history, bookmarks
- 🔍 Search by name, genre, and filters
- 🌙 AMOLED dark theme support
- 🔒 App lock support

---

## 📱 Requirements

- Android 6.0 (API 23) or higher
- Works on Android 7 (tested on Huawei P9 Lite — the whole reason this app exists 😄)

---

## 🔨 Building

1. Fork this repo
2. Go to **Actions → Build Tsuki APK → Run workflow**
3. Download the APK from Artifacts when done (~15 min)

No PC, no Android Studio needed.

---

## 📄 Credits

- **Aanan** — the guy who just wanted to read Solo Leveling on a 7-year-old phone
- **Claude (Anthropic)** — the AI that wrote all the code
- **[Futon](https://github.com/AppFuton/Futon)** — the base we forked from
- **[Kotatsu](https://github.com/KotatsuApp/Kotatsu)** — the original upstream project
- **[kotatsu-parsers-redo](https://github.com/Kotatsu-Redo/kotatsu-parsers-redo)** — the parser library keeping this alive

---

<div align="center">
Built with 🌙 and a lot of Stack Overflow
</div>
