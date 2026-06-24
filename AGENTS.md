# Repository Guidelines

## Project Structure & Module Organization

This is a single-module Android application (`:app`). The root `build.gradle.kts` declares plugins; Android configuration and dependencies live under `app/`.

- `app/src/main/java/com/example/epubreader/`: Compose UI, view models, `MainActivity`, and `ReaderApplication`.
- `data/`: Room entities, DAO, database, migrations, and repository.
- `epub/`: EPUB parsing and sentence splitting.
- `tts/`: foreground playback service, engine wrappers, model management, and diagnostics.
- `app/src/main/res/`: Android resources and assets.
- `app/src/test/`: JVM tests mirroring production package paths.
- `app/src/androidTest/`: device or emulator instrumentation tests.

Place code in the narrowest relevant package. Keep application logic out of Compose screens; use view models, repositories, or services.

## Build, Test, and Development Commands

Use the checked-in Gradle wrapper with JDK 17 and Android SDK 35.

- `./gradlew.bat assembleDebug`: build the debug APK.
- `./gradlew.bat installDebug`: install it on a connected device.
- `./gradlew.bat testDebugUnitTest`: run JVM unit tests.
- `./gradlew.bat connectedDebugAndroidTest`: run instrumentation tests.
- `./gradlew.bat lintDebug`: run Android lint checks.

## Coding Style & Naming Conventions

Follow official Kotlin and Android conventions (`kotlin.code.style=official`). Use four-space indentation and Android Studio's Kotlin formatter; no standalone formatter is configured. Use `PascalCase` for classes and composables, `camelCase` for functions and properties, and lowercase package names under `com.example.epubreader`. Room schema changes require explicit migrations. Preserve sherpa-onnx JNI keep rules when modifying release shrinking.

## Testing Guidelines

Tests use JUnit 4, Robolectric, and `kotlinx-coroutines-test`. Name test classes after the subject, such as `SentenceSplitterTest`, and write behavior-focused test methods. Use `RobolectricTestRunner` when tests require Android APIs, Room, or shared preferences. Add regression tests for parser limits, reading-position persistence, database migrations, and TTS state transitions. Run unit tests and lint before submitting; run instrumentation tests for device-dependent changes.

## Commit & Pull Request Guidelines

Use Conventional Commit subjects with a focused scope, for example `fix(tts): resume persisted sentence` or `feat(epub): reject oversized entries`. Keep commits cohesive. Pull requests should explain the behavior change, testing performed, and relevant issue. Include screenshots or recordings for visible Compose changes and note database, signing, model, or ProGuard impacts.

## Security & Configuration

Never commit passwords, keystores, downloaded models, or machine-specific SDK paths. Store signing values in untracked `local.properties` or `TTS_READER_*` environment variables. Maintain EPUB XML hardening and archive size limits.
