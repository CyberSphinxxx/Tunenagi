# Agent Progress Log

Do not erase previous entries. Each entry records what actually ran and what it proved.

## Session template

### UTC date/time — Agent/session ID
- Phase/task:
- Summary/change paths:
- Commands and exit codes (evidence paths):
- Device / emulator observations:
- Commit SHA:
- Blockers or risks:
- Next exact action:

## 2026-10-12T07:09Z — deepseek-v4-flash session (TUN-0002)

- **Phase/task**: Phase 00 / TUN-0002 "Initialize Gradle Kotlin project" — verified.
- **Summary**: Unblocked the build runtime without administrator rights, then fixed the
  build configuration the previous session never reached. Added a real unit test for the
  pinned-toolchain invariants (including its failure paths).
  Changed: `gradle/libs.versions.toml` (new), `build.gradle.kts`, `app/build.gradle.kts`,
  `core/build.gradle.kts`, `gradle.properties`, `local.properties`, `gradle/wrapper/gradle-wrapper.properties`,
  `app/src/main/AndroidManifest.xml`, `app/src/main/java/com/tunenagi/player/ui/theme/Theme.kt`,
  `core/src/main/java/com/tunenagi/core/ToolchainCatalog.kt` (new),
  `core/src/test/java/com/tunenagi/core/ToolchainCatalogTest.kt` (new).
- **Commands and exit codes**:
  - `bash gradlew clean :app:assembleDebug :core:testDebugUnitTest --no-build-cache --no-configuration-cache`
    → exit 0, `BUILD SUCCESSFUL in 38s`, 72/72 tasks executed.
    Evidence: `STATE/evidence/TUN-0002/20261012T070749Z-clean-no-cache-build.json`.
  - `bash gradlew :core:testDebugUnitTest --rerun-tasks` → exit 0;
    `tests="6" skipped="0" failures="0" errors="0"`.
    Evidence: `STATE/evidence/TUN-0002/20261012T070717Z-core-unit-tests.json`.
  - `bash gradlew :app:assembleDebug` → exit 0.
    Evidence: `STATE/evidence/TUN-0002/20261012T070704Z-gradle-assembleDebug.json`.
  - `bash gradlew :app:dependencies --configuration debugRuntimeClasspath` → exit 0.
    Evidence: `STATE/evidence/TUN-0002/20261012T070727Z-dependency-resolution.json`.
  - APK facts: `STATE/evidence/TUN-0002/apk-debug-build-artifact.txt`
    (`com.tunenagi.player`, versionCode 1, versionName 0.1.0-dev, minSdk 26, targetSdk 35,
    sha256 549a6a5664f14f89295b9db9c2e44eb2a7d2d55645ebe776e828d5f2ed363e96).
- **Toolchain actually used**: Temurin **JDK 21.0.12.1** at
  `C:\Users\CyberSphinx\jdk-21\jdk-21.0.12.1+1` (extracted from a user-profile archive, no
  elevation), Gradle 8.11.1, AGP 8.7.3, Kotlin 2.0.20, KSP 2.0.20-1.0.25, Compose BOM
  2024.10.01, Android SDK at `C:\Android` (platform 35, build-tools 35.0.0).
- **Device / emulator observations**: none this task — no Android runtime was launched.
  TUN-0002's evidence kind is `command`; install/launch proof belongs to TUN-0003/TUN-0006.
- **Commit SHA**: not committed as part of this session; working tree is left with the
  changes above for review.
- **Blockers or risks**: none open. The earlier JDK blocker is resolved and documented in
  `STATE/blockers.md`. `org.gradle.java.home` points at a user-profile JDK path, so CI and
  fresh machines must supply JDK 17-21 themselves.
- **Next exact action**: claim TUN-0003 (design tokens and navigation shell) — three-tab
  Compose shell, player host navigation, empty states, then install and launch on an
  emulator/device and capture device evidence.
