# Tunenagi (繋ぎ)

An open-source music player for Android built with Kotlin, Jetpack Compose, and AndroidX Media3 (ExoPlayer). Features local audio playback, Audius public streaming, unified playlists, MusicBrainz metadata enrichment, LRCLIB lyrics, and a declarative plugin architecture.

## Repository Structure

```
├── app/                  # Android application module (UI, navigation, shell)
├── core/                 # Shared domain, playback engine, Room database, providers
├── gradle/               # Gradle wrapper and version catalogs
├── build_loop/           # Autonomous agent development loop & harness kit
│   ├── AGENTS.md         # Coding agent contract & guidelines
│   ├── START_HERE.md     # Setup and operating modes
│   ├── README.md         # Development kit documentation
│   ├── OPERATIONS/       # Automation, CI, and state management guides
│   ├── PHASES/           # Phased implementation roadmaps
│   ├── PROMPTS/          # Agent session and audit prompts
│   ├── SCRIPTS/          # Verification, testing, and loop automation scripts
│   ├── SKILLS/           # Just-in-time agent skills
│   ├── SPEC/             # Architectural & engineering specifications
│   └── STATE/            # Task tracking graph, audits, and evidence
├── build.gradle.kts      # Root build configuration
├── settings.gradle.kts   # Module settings
└── gradlew / gradlew.bat # Gradle wrapper scripts
```

## Build & Test

Building the Android app requires a JDK in the supported range (JDK 17–21) and the Android SDK:

```bash
# Run JVM unit tests
./gradlew testDebugUnitTest

# Assemble debug APK
./gradlew :app:assembleDebug
```

## Agent Build Loop & Verification

All autonomous development loop specifications, tasks, prompts, and verification gates are housed in [`build_loop/`](build_loop/):

```bash
# Run fast hygiene checks (packaging, format, secrets, CI definition, layer boundaries)
python build_loop/SCRIPTS/verify.py --fast

# Or run directly inside the build_loop directory:
cd build_loop
python SCRIPTS/validate_package.py
python SCRIPTS/verify.py --fast
```

For full agent setup instructions and operating modes, see [`build_loop/START_HERE.md`](build_loop/START_HERE.md) and [`build_loop/README.md`](build_loop/README.md).
