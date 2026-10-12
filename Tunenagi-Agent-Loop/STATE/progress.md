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

## 2026-10-12T18:10Z - deepseek-v4-flash session (TUN-0003)

- **Phase/task**: Phase 00 / TUN-0003 "Design tokens and navigation shell" - claimed,
  implemented, command evidence captured, **device evidence blocked** (stays `in_progress`).
- **Summary/change paths**: moved the design tokens into `:core` as plain ARGB/dp/ms values with
  a real WCAG contrast audit, then built the three-tab shell, the player route, the responsive
  scaffold and the empty states on top of them.
  New: `core/src/main/java/com/tunenagi/core/designsystem/{TunenagiPalette,TunenagiContrast,TunenagiTokens}.kt`,
  `core/src/test/java/com/tunenagi/core/designsystem/{TunenagiContrastTest,TunenagiTokensTest}.kt`,
  `app/src/main/java/com/tunenagi/player/ui/shell/{TunenagiDestinations,MiniPlayer,TunenagiEmptyState,TunenagiShell}.kt`,
  `app/src/main/java/com/tunenagi/player/ui/screens/{HomeScreen,SearchScreen,LibraryScreen,PlayerScreen}.kt`,
  `app/src/androidTest/java/com/tunenagi/player/ui/shell/TunenagiShellTest.kt`.
  Changed: `app/.../ui/theme/Color.kt` (now delegates to the core palette), `Theme.kt` (audited
  outline and supporting-text roles), `MainActivity.kt` (hosts the shell),
  `app/src/main/res/values/strings.xml`, `app/build.gradle.kts` and `gradle/libs.versions.toml`
  (Compose ui-test and material-icons-core artefacts).
- **Commands and exit codes (evidence paths)**:
  - `clean :core:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
    --no-build-cache --console=plain` -> exit 0, `BUILD SUCCESSFUL`, 102/102 tasks executed,
    25 tests / 0 failures (6 toolchain + 11 contrast + 8 tokens).
    Evidence: `STATE/evidence/TUN-0003/20261012T180122Z-clean-build-and-unit-tests.json`;
    APK facts in `STATE/evidence/TUN-0003/apk-debug-build-artifact.txt` (13,448,693 bytes,
    sha256 ecbe296e703dc2d27b5438bcda0aa0cf6de670949156132634955ca69a6265ef).
  - Not run: `:app:connectedDebugAndroidTest` - no Android runtime is available (see below).
- **Audit findings**: the `#35394A` outline measures 1.70:1 (background) / 1.53:1 (surface),
  below the 3:1 non-text threshold, and `#B6B8C5` on the `#5C54B8` subdued accent is 3.12:1,
  below AA text. Both are recorded as explicit prohibitions with tests asserting they fail;
  `OutlineStrong` `#6E7288` (4.09 / 3.69 / 3.08) was added for meaningful boundaries. ADR-002.
- **Environment defect found and corrected**: `org.gradle.java.home` in `local.properties` is
  silently ignored by Gradle (only AGP reads that file, for `sdk.dir`), so `bash gradlew` picked
  JDK 25 from PATH and failed with `IllegalArgumentException: 25.0.1` before any project code
  ran. The working invocation is `JAVA_HOME=C:/Users/CyberSphinx/jdk-21/jdk-21.0.12.1+1 bash
  gradlew ...`. The misleading comment in `local.properties` (machine-local, untracked) was
  corrected.
- **Device / emulator observations**: emulator 37.2.12 and the API 33 google_apis x86_64 system
  image are now installed under `C:/Android`. No device is attached; the AEHD hypervisor driver
  is not installed and the elevation attempt was cancelled at UAC, so `sc query aehd` still
  returns 1060. No device screenshot exists and none was fabricated.
- **Commit SHA**: recorded in the follow-up commit to this log.
- **Blockers or risks**: see `STATE/blockers.md` - the x86_64 emulator cannot start on this host
  without the AEHD driver or a physical device. Untracked build junk at the loop root
  (`gradle-8.11.1-bin.zip`, `.gradle-native/`, `.kotlin/`, `build_fail.log`) should be absorbed by
  TUN-0004's strict .gitignore.
- **Next exact action**: obtain an Android runtime (approve the AEHD elevation or attach a
  device), run `:app:connectedDebugAndroidTest`, install and launch the debug APK, capture
  `device-*.png` screenshots, then `taskctl.py verify TUN-0003 --artifact ...`. If the runtime
  stays unavailable, work TUN-0004 next.
