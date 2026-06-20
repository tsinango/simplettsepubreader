# Repository Guidelines

## Project Structure & Module Organization

This is a single-module Android application. Root Gradle files define shared plugin and repository configuration; application code lives under `app/`.

- `app/src/main/java/com/example/epubreader/`: Kotlin application, Compose UI, and view models.
- `app/src/main/java/com/example/epubreader/data/`: Room entities, database, and repository code.
- `app/src/main/java/com/example/epubreader/epub/`: EPUB parsing and sentence splitting.
- `app/src/main/java/com/example/epubreader/tts/`: text-to-speech services, model management, and playback pipeline.
- `app/src/main/res/`: Android resources and launcher assets.
- `app/src/test/`: JVM unit tests mirroring production package paths.

Keep new code in the narrowest relevant package. Avoid adding application logic directly to Compose screens when it belongs in a view model, repository, or service.

## Build, Test, and Development Commands

Use the checked-in Gradle wrapper with JDK 17 and Android SDK 35. On Windows:

- `./gradlew.bat assembleDebug` builds a debug APK.
- `./gradlew.bat testDebugUnitTest` runs local JUnit tests.
- `./gradlew.bat connectedDebugAndroidTest` runs device/emulator instrumentation tests, when present.
- `./gradlew.bat lintDebug` runs Android lint checks.
- `./gradlew.bat installDebug` installs the debug build on a connected device.

Use `./gradlew` instead on macOS or Linux. Android Studio can run the `app` configuration directly on Android 12 (API 31) or newer.

## Coding Style & Naming Conventions

Follow standard Kotlin and Android conventions: four-space indentation, trailing commas in multiline declarations, `PascalCase` for types and composables, `camelCase` for functions and properties, and `UPPER_SNAKE_CASE` for constants. Prefer small, focused functions and immutable state. Package names remain lowercase under `com.example.epubreader`. No standalone formatter is configured, so use Android Studio's Kotlin formatting and optimize imports before committing.

## Testing Guidelines

Local tests use JUnit 4; coroutine tests use `kotlinx-coroutines-test`. Name test classes after the subject, such as `SentenceSplitterTest`, and use behavior-focused method names such as `ignoresWhitespaceOnlyText`. Add tests for parsing boundaries, persistence behavior, and TTS chunking regressions. Run `testDebugUnitTest` before opening a pull request. No numeric coverage threshold is currently enforced.

## Commit & Pull Request Guidelines

History follows Conventional Commit-style subjects, for example `fix(tts): stream generated VITS audio` and `feat(diagnostics): add local log export`. Use an imperative, concise subject with a relevant scope. Pull requests should explain the user-visible change, testing performed, and any device/API assumptions; link related issues and include screenshots or recordings for Compose UI changes.

## Security & Local Configuration

Never commit passwords, generated keystores, or machine-specific SDK paths. Keep signing values in untracked `local.properties` or environment variables using the `TTS_READER_*` names documented in `README.md`.
