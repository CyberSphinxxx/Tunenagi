# Tunenagi (繋ぎ)

An open-source, source-neutral music player for Android built with modern Android development standards: **Kotlin 2.0**, **Jetpack Compose (Material 3)**, and **AndroidX Media3 (ExoPlayer)**.

Tunenagi (*繋ぎ*, meaning connection or link) unifies your music experience across local audio files, open web streaming (Audius), synchronized lyrics (LRCLIB), and community metadata enrichment (MusicBrainz) into a single playback queue.

---

## Highlights & Features

- **High-Fidelity Audio Engine**: Built on AndroidX Media3 ExoPlayer running in a dedicated `MediaLibraryService`. Supports background playback, lock screen media controls, media notifications, audio focus handling, and seamless queue management.
- **Local Audio Discovery**: Fast MediaStore indexing with Storage Access Framework (SAF) support, directory browsing, tag parsing, and album artwork extraction.
- **Unified Queue & Persistence**: Backed by Room database with persistent playback checkpoints debounced to survive application restarts and swipe-aways.
- **Open Web Providers**: Stream open-access music via public Audius APIs respecting creator licensing.
- **Synchronized Lyrics**: Embedded and remote synchronized/plain lyrics retrieval powered by LRCLIB.
- **Metadata Enrichment**: Optional MusicBrainz metadata integration to enrich artist and release information without locking playback to network availability.
- **Declarative Plugin Architecture**: Restricted declarative JSON/HTTP provider specification for extensible catalogs without dynamic script execution risks.
- **Modern Jetpack Compose UI**: 100% declarative UI with Material 3 styling, dynamic themes, smooth transitions, and responsive layouts across phones and tablets.

---

## Tech Stack & Pinned Toolchain

| Component | Pinned Version / Technology |
|---|---|
| **Language** | Kotlin 2.0.20 |
| **Android Gradle Plugin** | AGP 8.7.3 |
| **Gradle** | 8.11.1 (Wrapper included) |
| **Compiler Plugin** | KSP 2.0.20-1.0.25 |
| **JDK Compatibility** | JDK 17 to JDK 21 (JDK 17 recommended) |
| **Target / Compile SDK** | Android 15 (API 35) |
| **Minimum SDK** | Android 8.0 Oreo (API 26) |
| **UI Framework** | Jetpack Compose (Compose BOM 2024.10.01 / Material 3) |
| **Playback & Media** | AndroidX Media3 1.4.1 (ExoPlayer, MediaSession) |
| **Dependency Injection** | Dagger Hilt 2.51.1 |
| **Local Database** | Room 2.6.1 (SQLite with foreign keys & migrations) |
| **Networking** | OkHttp 4.12.0, Retrofit 2.11.0, Kotlinx Serialization |
| **Testing** | JUnit 4, Robolectric 4.14.1, AndroidX Test, Espresso |

*Note: Toolchain versions are centrally managed in [`gradle/libs.versions.toml`](gradle/libs.versions.toml) and [`gradle.properties`](gradle.properties).*

---

## Repository Structure

```
.
├── app/                        # Android application module
│   └── src/main/java/com/tunenagi/player/
│       ├── MainActivity.kt     # Single activity entry point
│       ├── TunenagiApp.kt      # Application class with Hilt setup
│       ├── playback/           # UI playback controllers & bindings
│       └── ui/                 # Compose screens (Home, Library, Player, Search), theme, and shell
├── core/                       # Shared domain & infrastructure library module
│   └── src/main/java/com/tunenagi/core/
│       ├── database/           # Room database, DAOs, entities, and type converters
│       ├── designsystem/       # Shared UI tokens, themes, and design components
│       ├── library/            # Source matching, repository contracts, and import logic
│       ├── model/              # Domain models (Track, Album, Artist, QueueItem, Playlist)
│       ├── playback/           # MediaLibraryService, ExoPlayer instance, queue coordinator
│       └── provider/           # Local MediaStore indexer and external provider adapters
├── gradle/                     # Version catalog and Gradle wrapper distribution
│   └── libs.versions.toml      # Single source of truth for build dependencies
├── build_loop/                 # Autonomous development loop & verification harness
│   ├── OPERATIONS/             # CI, state protocols, and git conventions
│   ├── PHASES/                 # Phase implementation roadmap
│   ├── SCRIPTS/                # Hygiene gates, formatting, and validation scripts
│   ├── SPEC/                   # Architecture, PRD, UX design, and engine specs
│   └── STATE/                  # Task tracking graph and evidence records
├── build.gradle.kts            # Root build configuration
├── settings.gradle.kts         # Module settings (:app, :core)
└── gradle.properties           # Project-wide properties (SDK levels, JVM args)
```

---

## How to Run with Android Studio

### 1. Prerequisites

- **Android Studio**: Android Studio Ladybug (2024.2.1+) or newer is recommended.
- **Java Development Kit (JDK)**: JDK 17 (recommended) or JDK 21.
  > **Important**: Android Gradle Plugin 8.7.3 supports JDK 17 to JDK 21. Do not use JDK 22+ or early-access JDK 25, as AGP will fail with an unsupported class version error.
- **Android SDK Components**:
  - Android SDK Platform 35 (Android 15)
  - Android SDK Build-Tools 35.x.x

### 2. Opening the Project

1. Launch Android Studio.
2. Select **File > Open...** (or click **Open** on the Welcome screen).
3. Navigate to and select the repository root folder (`Tunenagi/` containing `settings.gradle.kts` and `build.gradle.kts`).
4. Click **OK** to open the project.

### 3. Configuring the Gradle JDK

To prevent Gradle JDK incompatibilities:

1. Open **Settings** (Windows/Linux: `Ctrl+Alt+S`, macOS: `Cmd+,`).
2. Navigate to **Build, Execution, Deployment > Build Tools > Gradle**.
3. Locate **Gradle JDK**.
4. Choose either:
   - **Embedded JDK** (comes bundled with Android Studio, typically JDK 17 or JDK 21).
   - Any installed JDK version in the **17 to 21** range (e.g. Eclipse Temurin 17, Amazon Corretto 17, or JetBrains Runtime).
5. Click **Apply** and then **OK**.

### 4. Syncing the Project

1. Click **File > Sync Project with Gradle Files** (or the elephant sync icon in the top-right toolbar).
2. Wait for Gradle to download dependencies and configure the project graph.
3. If prompted to install missing SDK components (such as Android SDK Platform 35), click the provided installation link in the build window.

### 5. Running the App

1. Ensure the run configuration dropdown in the top toolbar is set to **`app`**.
2. Select a target device:
   - **Android Emulator**: An AVD running API 26 (Android 8.0) or newer (API 34/35 recommended).
   - **Physical Device**: A connected device with USB Debugging enabled (Android 8.0+).
3. Click the green **Run** button (`Shift + F10`) or **Debug** button (`Shift + F9`).
4. Android Studio will assemble the debug APK and launch `MainActivity` on your device.

### 6. Running Unit & Integration Tests in Android Studio

- **Run all unit tests**: In the Project tool window, right-click on `core/src/test` or `app/src/test` and select **Run 'Tests in ...'**.
- **Run a specific test**: Open any test file (e.g. `core/src/test/java/com/tunenagi/core/playback/TunenagiPlaybackServiceTest.kt`) and click the green play icon next to the class or test method name.
- Tests utilize **Robolectric** to simulate Android runtime behaviors on the JVM without requiring an emulator.

---

## Command-Line Build & Verification

You can also build and test directly from your terminal using the Gradle wrapper:

```bash
# Compile and run JVM unit tests across all modules
./gradlew testDebugUnitTest

# Or run tests for a specific module
./gradlew :core:testDebugUnitTest
./gradlew :app:testDebugUnitTest

# Assemble the debug APK
./gradlew :app:assembleDebug

# Run Android Lint analysis
./gradlew :app:lintDebug :core:lintDebug
```

Output APK will be generated at:
```
app/build/outputs/apk/debug/app-debug.apk
```

---

## Development Loop & Hygiene Gates

The repository includes automated hygiene gates under [`build_loop/`](build_loop/) to verify code formatting, architecture boundaries, package consistency, and CI definitions:

```bash
# Run fast hygiene validation (packaging, formatting, boundaries, CI definition)
python build_loop/SCRIPTS/verify.py --fast

# Or run individual checks directly inside build_loop:
cd build_loop
python SCRIPTS/validate_package.py   # Verifies task graph and packaging
python SCRIPTS/check_format.py       # Enforces tab, trailing space, and line ending rules
python SCRIPTS/check_boundaries.py   # Verifies architectural layer dependencies
python SCRIPTS/check_ci.py           # Validates CI workflow pins and commands
```

For full details on the specification, architecture contracts, and agent loop workflows, refer to [`build_loop/README.md`](build_loop/README.md) and [`build_loop/SPEC/ARCHITECTURE.md`](build_loop/SPEC/ARCHITECTURE.md).

---

## Architecture Principles

1. **Single Source of Truth for Playback**: Exactly one `MediaLibraryService` runs the underlying ExoPlayer. UI components never construct their own player instances.
2. **Source-Neutral Identity**: Internal tracks are identified by a generated UUID `TrackId`, decoupling library identity from third-party streaming IDs or temporary remote URLs.
3. **Reactive State**: ViewModels observe Room database queries via Kotlin `Flow`, exposing immutable UI states to Jetpack Compose.
4. **Resilient Persistence**: Playback position and queue state are persisted on state changes and task removals to ensure smooth session resumption.
5. **Safe Plugins**: External providers use a restricted, declarative schema. No third-party executable code is dynamically loaded or executed.

---

## License

Tunenagi is licensed under the [GNU General Public License v3.0](LICENSE) (or see repository license terms).
