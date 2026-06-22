# Repository Guidelines

## Project Structure & Module Organization

Single-module Android application (`:app`). Root `build.gradle.kts` declares plugins only; app config lives in `app/`.

- `app/src/main/java/com/example/epubreader/`: Compose UI, view models, application entry point (`ReaderApplication`, `MainActivity`).
- `app/src/main/java/com/example/epubreader/data/`: Room entities (`BookEntity`, `ReadingLocatorEntity`, `ReaderSettingsEntity`), DAO (`ReaderDao`), database (`ReaderDatabase`), and repository (`ReaderRepository`).
- `app/src/main/java/com/example/epubreader/epub/`: EPUB parsing (`EpubParser`) and sentence splitting (`SentenceSplitter`).
- `app/src/main/java/com/example/epubreader/tts/`: TTS service, playback pipeline, embedded VITS/sherpa-onnx engine wrappers, model management, and performance tracking.
- `app/src/test/`: JVM unit tests mirror production package paths; many use Robolectric.

Keep new code in the narrowest relevant package. Avoid adding application logic directly to Compose screens — it belongs in a view model, repository, or service.

## Build, Test, and Development Commands

Use the checked-in Gradle wrapper with JDK 17 and Android SDK 35.

| Command | Purpose |
|---|---|
| `./gradlew.bat assembleDebug` | Build debug APK (appends `.debug` to appId) |
| `./gradlew.bat installDebug` | Install debug APK on connected device |
| `./gradlew.bat testDebugUnitTest` | Run local JVM unit tests (JUnit 4 + Robolectric + coroutines-test) |
| `./gradlew.bat connectedDebugAndroidTest` | Device/emulator instrumentation tests |
| `./gradlew.bat lintDebug` | Android lint |

No CI workflows exist yet (`.github/workflows/` is empty).

## Key Architecture Details

- **DI**: Manual — `ReaderApplication` creates `ReaderDatabase` and `ReaderRepository`, exposed via `lateinit var`; view models use `AndroidViewModel` with `Application` context.
- **Database**: Room `reader.db`, entities: `BookEntity`, `ReadingLocatorEntity`, `ReaderSettingsEntity`. Schema version 4, `exportSchema = false`. KSP for annotation processing (not KAPT). Manual SQL migrations (`MIGRATION_1_2`, `MIGRATION_2_3`, `MIGRATION_3_4`).
- **TTS**: `ReaderTtsService` (foreground, `mediaPlayback` type) wraps Android system TTS and embedded sherpa-onnx VITS engines. Sentence-level persistence guarantees — progress saved to Room before each sentence utterance, resumes from that sentence on restart (at most 1 sentence repeat).
- **NDK**: Only `arm64-v8a` ABI via `ndk { abiFilters += "arm64-v8a" }`.
- **App signing**: Optional fixed debug/release signing via `TTS_READER_*` env vars or `local.properties`. Without these, debug builds use the default Android debug keystore and release builds fail signing.
- **ProGuard**: Release builds minify + shrink. Sherpa-onnx JNI classes must be kept (`proguard-rules.pro`).
- **UI**: Material3 + Compose with BOM `2024.12.01`. Kotlin 2.0.21 compose compiler plugin (not the deprecated extension). Theme via `ReaderSettingsEntity.theme` ("SYSTEM", "DARK", "LIGHT").
- **Parser security**: `EpubParser` disables XML external entities and limits file/entry/total sizes.
- **Embedded VITS models**: Downloaded on-demand from Hugging Face with SHA-256 verification, stored outside APK. Model registries (`VitsModelRegistry`, `BertVits2MnnModelRegistry`, `KokoroModelRegistry`) manage lifecycle.
- **Robolectric tests**: Use `@RunWith(RobolectricTestRunner::class)` for any test needing Android framework APIs (Room DB, shared prefs, etc.).

## Coding Style & Naming Conventions

Follow standard Kotlin and Android conventions. `kotlin.code.style=official` in `gradle.properties`. No standalone formatter — use Android Studio's Kotlin formatting. Package names lowercase under `com.example.epubreader`.

## Commit & Pull Request Guidelines

Conventional Commit subjects: `fix(tts): ...`, `feat(diagnostics): ...`. Reference scope from the package list above.

## Security & Local Configuration

Never commit passwords, generated keystores, or machine-specific SDK paths. Keep signing values in untracked `local.properties` or `TTS_READER_*` environment variables. `signing/` is gitignored.
