# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

NotiFilter — an Android app (Kotlin) that intercepts incoming notifications via a `NotificationListenerService` and replays them with user-configured feedback (custom vibration pattern, sound, camera flash, time-of-day schedule). Also includes battery/charger diagnostics and notification history/insights views.

- `applicationId` / `namespace`: `com.notifylter.app` (the project name is "NotiFilter" — the package was renamed in commit b8ed3ed; expect both spellings in user-facing strings vs. code).
- `minSdk 26`, `targetSdk 32`, `compileSdk 34`, Kotlin 1.9.22, AGP 8.13.2, JVM target 1.8.
- Release builds enable `minifyEnabled` with the default ProGuard rules + `proguard-rules.pro`.

## Build & run

The Gradle wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/` — Gradle 8.13) is present and working. If it ever goes missing, regenerate with a system Gradle: `gradle wrapper --gradle-version 8.13`.

- Debug build: `./gradlew assembleDebug` (APK at `app/build/outputs/apk/debug/`)
- Release build: `./gradlew assembleRelease`
- Install on connected device: `./gradlew installDebug`
- Clean: `./gradlew clean`

There are **no unit or instrumentation tests** in the repo — only `app/src/main/`. Don't invent test commands.

`./gradlew lintDebug` fails on one **intentional** error: `ExpiredTargetSdkVersion` (Play wants ≥33; `targetSdk` is deliberately pinned at 32). Treat that as the expected baseline — any *other* lint error is new.

On Windows use `gradlew.bat` instead of `./gradlew`. Open `E:\NotiFylter` in Android Studio for IDE workflows.

## Runtime permission flow

The app is non-functional until the user grants **Notification Listener access** in system settings. `MainActivity.AppsFragment` opens that settings page (with three intent fallbacks) via the "Grant Notification Access" button. `MainActivity.onCreate` also prompts the user to whitelist the app from battery optimization — required so the listener survives in the background.

The manifest declares `BIND_NOTIFICATION_LISTENER_SERVICE` (on the service), plus `VIBRATE`, `CAMERA`, `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_SPECIAL_USE`, `POST_NOTIFICATIONS`, and `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.

## Architecture

Single-module app, single-process, no DI framework, no Room/Retrofit. State is local-only.

**Notification pipeline.** `NotificationService` (a `NotificationListenerService` declared in the manifest with `directBootAware="true"` and `default_filter_types=1`) is the entry point for every event:

1. `onNotificationPosted` logs every non-ongoing notification into history (capped at 100, newest-first) via `AppPriorityManager.addLog`.
2. It then looks up the per-package `FeedbackConfig` and, if enabled, hands off to `FeedbackHelper.playFeedback` for vibration + sound + flash.
3. Independently, the service registers a `BroadcastReceiver` for `ACTION_POWER_CONNECTED` / `ACTION_POWER_DISCONNECTED` / `ACTION_BATTERY_CHANGED` to drive the anti-theft alarm and the 80%-charge alert. The receiver is bound to the service lifecycle (registered in `onCreate`, unregistered in `onDestroy`).

**Persistence.** `AppPriorityManager` is the single source of truth for all stored state, backed by two `SharedPreferences` files:

- `notifilter_prefs` — per-package `FeedbackConfig` (serialized to JSON via Gson, keyed by package name), plus toggles like `dark_mode`, `anti_theft`, `battery_saver`.
- `notifilter_history` — the notification log list (JSON-serialized `List<NotificationLog>`), separate so "Clear History" doesn't wipe configs.

`getConfig` has a legacy-data fallback: if the stored value isn't JSON it tries to read a plain boolean (`isHigh`) and synthesizes a default `FeedbackConfig` — keep this branch when refactoring, it migrates older installs.

**UI.** `MainActivity` hosts a `DrawerLayout` + `NavigationView` and swaps four fragments into `R.id.fragmentContainer`:

- `AppsFragment` (inner class) — lists launchable installed apps, per-row enable toggle, opens the settings dialog. Has an "Auto-Set Priority" button that flips a hardcoded set of messaging packages (WhatsApp, Telegram, Slack, Viber, Mattermost, Messenger) to "Heartbeat" pattern.
- `InsightsFragment` — aggregates the history log by package and shows counts.
- `HistoryFragment` (inner class) — raw timeline of `NotificationLog` entries with a clear button.
- `ChargerFragment` — polls `BatteryManager` every 1s while visible, classifies charge speed into rating tiers based on `BATTERY_PROPERTY_CURRENT_NOW` (with a divide-by-1000 normalization for devices that report in µA), and exposes the anti-theft / battery-saver toggles.

`MainActivity.showSettingsDialog` and `showRecorderDialog` are the per-app config and custom-vibration-recorder dialogs — both Fragments call back into the Activity for these because `FeedbackHelper` is held on the Activity, not per-fragment.

**Feedback engine.** `FeedbackHelper` owns the `Vibrator` (with the API 31+ `VibratorManager` branch) and the camera-flash torch. It exposes a `presetPatterns` map (insertion-ordered) plus `patternNames`, which is the single source of truth for the settings spinner — the pattern keys are persisted in `FeedbackConfig`, so renaming one needs a migration. The dialog's "Custom" option swaps in a `LongArray` recorded from touch-down/up timings on a touch area. Vibrations are issued with `AudioAttributes(USAGE_ALARM)` so they aren't suppressed by the system's notification routing. The `isWithinSchedule` helper handles overnight ranges (start > end) correctly; `playFeedback` is **silent** for disabled apps and for notifications outside the schedule window.

`FeedbackHelper` owns a `HandlerThread` for the flash sequence, so every owner must call `release()` in its teardown (`MainActivity.onDestroy`, `NotificationService.onDestroy`).

## Gotchas

- The brand name appears as both **NotiFilter** (display, README, theme name `Theme.NotiFilter`) and **NotiFylter** (repo root directory). Code paths use `com.notifylter.app`. Don't "fix" one to match the other without checking — the manifest's `android:label` and `strings.xml` are authoritative for what users see.
- `NotificationService.onNotificationPosted` early-returns on `sbn.isOngoing` (persistent notifications like media controls), on the app's own package, and on `FLAG_GROUP_SUMMARY` (the summary duplicates its children) — preserve all three, and keep them **before** `addLog` so skipped notifications don't reach history.
- The manifest declares `<queries>` for the MAIN/LAUNCHER intent. Without it, Android 11+ package visibility silently truncates the app list — don't drop it, and enumerate apps via `queryIntentActivities` rather than `getInstalledApplications`.
- Pref keys live as `AppPriorityManager.KEY_*` constants; app-wide flags share the config prefs file, so `RESERVED_KEYS` stops a package name from colliding with one.
- History is hard-capped at 100 entries inside `addLog` (FIFO eviction). If you change persistence, keep the cap or surface it as a setting.
- `commit 20ffc78` fixed unresolved `R` imports by switching to `com.notifylter.app.R` — keep that exact import when adding new fragments/activities.
