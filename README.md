# Komiku Reader

Android project scaffold for application ID `com.comic.xenvyx.id`.

## Product constraints

- Reads public/free content from `https://komiku.org/` only. It must not bypass authentication, paywalls, or other access restrictions.
- Kotlin + Jetpack Compose shell with a restricted WebView for the public website.
- Minimum SDK 21 (Android 5.0); target/compile SDK 35; ABIs `armeabi-v7a` and `arm64-v8a`.
- The continuous reader's five-chapter memory policy is implemented in the platform-independent `core` module: preload only at the actual final panel, and evict old in-memory resources only after the replacement chapter loads successfully. Persistent history/bookmarks and offline files are not part of that eviction.
- Local preferences include theme, reading mode, and backup-reminder state. The theme and reading-mode controls are not yet fully wired into the UI.

## Implemented source

### Core logic

- `core/.../ChapterWindowManager.kt`: five-chapter rolling memory coordinator.
- `core/.../DownloadQueue.kt`: FIFO queue policy, duplicate prevention, progress, pause/resume, cancel, retry, and single-task concurrency policy.
- `./run-core-tests.sh` compiles and runs the platform-independent smoke tests.

### Android download infrastructure

- `DownloadJobStore`: persistent SQLite job, image-manifest, allowed-host, and per-image status storage.
- `DownloadScheduler`: persists the full manifest, then gives WorkManager only a compact job ID. Jobs are appended to a unique FIFO work chain and execute one chapter at a time.
- `ChapterDownloadWorker`: downloads caller-supplied HTTPS image URLs, checks redirects against the persisted host allow-list, requires image MIME type, caps each image at 20 MiB, writes temporary files before finalizing, and retries failures.
- `OfflineChapterManager`: lists completed chapters, exposes ordered local image files, calculates disk usage, and safely deletes completed/failed/cancelled chapter data without deleting bookmarks or reading history.
- Compose **Unduhan** screen: displays job states and progress, refreshes the list, cancels queued/running work, and removes offline data.

The download infrastructure is not yet connected to a verified Komiku chapter parser, so the app cannot yet discover image URLs or create download jobs from a chapter page. A native offline reader, user-facing pause/resume, and download queue editing remain outstanding.

### Bookmark/history backup

- `ReadingBackupManager` exports versioned JSON through Android Storage Access Framework and imports a user-selected JSON backup.
- **Merge** upserts imported records while retaining unrelated local records. **Replace** transactionally replaces bookmark and history tables.
- Backup contains comic/chapter IDs, titles, panel positions, and timestamps only. It excludes cookies, passwords, and authentication tokens.
- Import validates schema version, record counts, field lengths, and values before the database transaction.
- Settings dialog exposes backup and restore flows.

## Verification status and limitations

- **Verified:** platform-independent core tests pass for end-panel-only preload, the five-chapter window, safe eviction, queue ordering, duplicate prevention, progress, pause/resume, failure, and retry.
- **Not verified:** Android-specific Kotlin compilation, WorkManager execution, SQLite behavior on-device, SAF import/export, Compose UI, network image downloads, and installation.
- This environment currently has no Android SDK, Gradle executable, or ADB. No APK has been built or installed from this source.
- Website DOM integration, actual chapter/image URL discovery, image-server fallback, Google sign-in (only if officially supported), theme controls, reader gestures/modes, bookmark/history capture, backup reminder popup, and Android 5/11 device testing remain unfinished.

## Build requirements

Use JDK 17, Android SDK Platform 35, Android Build Tools 35.0.0, and Gradle 8.9. WorkManager is pinned to 2.9.1 to preserve compatibility with the Android 5.0 minimum. Configure release signing locally; never commit signing keys.

### Build an APK with GitHub Actions

The repository includes `.github/workflows/android-debug-apk.yml`. Push the project to a GitHub repository, then open **Actions → Build Komiku Reader APK → Run workflow**. When the workflow succeeds, download the `komiku-reader-debug-apk` artifact. The output is a debug-signed APK intended for testing, not a production release. The workflow has not yet been run from this environment, so its success is not verified.

### Build locally

Open the project in Android Studio with JDK 17 and Android SDK Platform 35 installed, or run `gradle :app:assembleDebug` with Gradle 8.9. The APK should appear at `app/build/outputs/apk/debug/app-debug.apk` if the build succeeds.
