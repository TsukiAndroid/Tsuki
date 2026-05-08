# Contributing to Tsuki 月

First off, thanks for considering contributing to Tsuki!
Every contribution helps, no matter how small 🌙

## 💬 Community

Join our Discord for questions and dev discussion!

[![Discord](https://img.shields.io/badge/Discord-Join%20Server-5865F2?logo=discord)](https://discord.gg/tmFwj72b6k)

---

## 📋 Tsuki Contribution Guidelines

- If you want to **fix bugs** or **implement new features** that already have an [issue card](https://github.com/Space4414/Tsuki/issues): please assign the issue to yourself and/or comment about it.
- If you want to **implement a new feature:** open an issue or discussion regarding it first to ensure it will be accepted.
- **Community chat**: Join our [Discord server](https://discord.gg/tmFwj72b6k)!
- In case you want to **add a new manga source**, refer to the [parsers repository](https://github.com/Kotatsu-Redo/kotatsu-parsers-redo).

**Refactoring** or some **dev-facing improvements** might also be accepted. However, please stick to the following principles:

- **Performance matters.** When choosing between source code beauty and performance, performance should always be the priority.
- Please **do not modify README and other information files** (except for typos, or if you can explain it better — that's also accepted!)
- **Avoid adding new dependencies** unless absolutely required. APK size is important.
- **Please explain your changes** — at least try to, cause it just helps us work a lot faster than spending time figuring out what you did 🥲

---

## 👥 Ways to Contribute

### 🐛 Bug Reports
- Use the GitHub Issues tab or #bug-reports on Discord
- Describe the bug clearly
- Include your Android version and device model
- Include steps to reproduce the issue
- Screenshots are super helpful!

### 💡 Feature Requests
- Open a GitHub Issue with the "enhancement" label
- Or suggest it in #suggestions on Discord
- Describe what you want and why it'd be useful
- Check existing issues first to avoid duplicates

### 🛠️ Code Contributions
1. Fork the repository
2. Create a new branch:
   ```
   git checkout -b feature/your-feature-name
   ```
3. Make your changes
4. Test on a real device if possible
5. Commit with a clear message:
   ```
   git commit -m "Add: your feature description"
   ```
6. Push and open a Pull Request

### 🌐 Adding New Manga Sources
- Tsuki uses **kotatsu-parsers-redo** for sources
- Check the [kotatsu-parsers-redo](https://github.com/Kotatsu-Redo/kotatsu-parsers-redo) repo for reference
- Follow the same parser structure as existing ones
- Use the `@MangaSourceParser` annotation correctly
- Test the source thoroughly before submitting a PR
- You can also request new sources in #source-requests on Discord!

### 🌍 Translations
- Translations live in `app/src/main/res/values-xx/`
- Copy `strings.xml` from the `values/` folder
- Replace `xx` with your language code
  (e.g. `bn` for Bengali, `ja` for Japanese)
- Translate the strings and submit a PR!

---

## 🏷️ Issue Labels
- `good first issue` — great for newcomers!
- `bug` — something is broken
- `enhancement` — new feature request
- `help wanted` — needs extra attention
- `parser` — related to manga sources

---

## ⚡ Release Channels
- **Alpha** `com.space4414.tsuki.alpha` — developer testing
- **Beta** `com.space4414.tsuki.beta` — public testing
- **Stable** `com.space4414.tsuki` — production release

---

## ⚠️ Note From The Dev

Hey! I'm Space4414, solo dev behind Tsuki 🌙
I am a college student so responses might be slow but I read everything!
All contributions are genuinely appreciated!

Thanks for helping make Tsuki better! 🌙

