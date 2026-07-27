---
name: Android plugin CI
description: Durable constraints for adding plugin services and screens to Tsuki's Android app.
---

When adding injectable plugin services that hold an Android `Context`, qualify application-scoped dependencies with Hilt's `@ApplicationContext`. Plugin fragments extending `BaseFragment` must implement `onApplyWindowInsets` and initialize UI in `onViewBindingCreated`.

**Why:** The plugin feature initially passed source review but failed only during Android's Kotlin/Hilt build stages: unqualified `Context` caused a Dagger missing-binding error, while direct `onViewCreated` use conflicted with the base fragment's final lifecycle implementation.

**How to apply:** For future plugin UI or worker changes, inspect the nearest existing Hilt service and `BaseFragment` implementation before compiling; use the same qualifiers and inset handling patterns.