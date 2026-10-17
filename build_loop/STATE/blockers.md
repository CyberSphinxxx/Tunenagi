# Tunenagi active blockers

One task is currently blocked. This file records the active blocker exactly, and keeps the
resolved ones so a future session does not repeat the same dead end.

## ACTIVE - TUN-0003 needs an Android runtime (raised 2026-10-12T18:05Z, re-measured 2026-10-13T18:19Z)

**Task:** TUN-0003 (design tokens and navigation shell). Its `evidenceKinds` are `command` and
`device`, and `taskctl verify` requires a non-empty artifact whose name is prefixed `device-`.

**State of the work:** tokens, the three-tab shell, the player route, the mini-player slot and
the empty states are implemented and green: clean build plus 25 JVM unit tests, evidence
`STATE/evidence/TUN-0003/20261012T180122Z-clean-build-and-unit-tests.json` (BUILD SUCCESSFUL,
exit 0, 102/102 tasks executed), and the instrumented test APK
(`:app:assembleDebugAndroidTest`) builds. Only the on-device install, launch and UI proof is
missing.

**Exact failure as measured on 2026-10-13T18:19Z** - method, commands and raw samples are in
`OPERATIONS/EMULATOR_RUNTIME.md`:

- `adb devices` lists no device; `sc query aehd` returns 1060 (no accelerator service).
- `emulator -avd tunenagi_api33 -accel off ...` is **accepted**: the hypervisor check passes,
  the frontend spawns QEMU, opens ports 5554/5555 and reaches adb registration - but the guest
  **never executes**. QEMU's cumulative CPU time stayed at 0.4375 s for a full 9-minute run and
  showed no growth over 20-25 s samples in three further configurations (0.609375, 0.453125,
  0.5). TCG is genuinely the selected accelerator, because QEMU printed its
  `TCG doesn't support requested feature` lines, so this is a non-functional
  software-emulation path rather than a missing flag.
- `-qemu -accel tcg` and `-qemu -cpu qemu64` crash the frontend with SIGSEGV (exit 139).
- An ARM64 system image is refused by the emulator's QEMU2 launcher on an x86_64 host, so it is
  not an alternative here.
- The device step itself is wired correctly, so the runtime is the only missing input: run
  with JDK 21 and no device attached, `:app:connectedDebugAndroidTest` builds the test APK
  (`:app:packageDebugAndroidTest` executes) and then fails only with `DeviceException: No
  connected devices!` - exit 1, evidence `STATE/evidence/TUN-0003/20261013T190507Z-connected-android-test-preflight.json`.

**Attempts:** 1. (2026-10-12) elevated `silent_install.bat` through the approved-elevation flow
-> the UAC prompt was cancelled, nothing ran. 2. (2026-10-13T18:19Z) the same flow with
`silent_install_safe.bat` -> "The operation was canceled by the user. The UAC prompt may have
been cancelled or blocked", nothing ran. 3. (2026-10-13T18:19Z) the complete software-emulation path above,
including a 9-minute control run. Nothing was half-installed: `sc query aehd` still returns
1060 after both elevation attempts.

**Required external action (any one):**

1. Run this in an elevated console - the account `CyberSphinx` is already in `Administrators`
   and firmware AMD-V/SLAT are enabled, so nothing else needs changing:
   `cd /d C:\Android\extras\google\Android_Emulator_Hypervisor_Driver && silent_install_safe.bat`
2. Or approve the UAC prompt if the sandbox elevation flow shows one - it runs the same
   installer.
3. Or attach a physical Android device with USB debugging enabled (API 26+).

**Safe fallback actually taken instead of faking it:** an unaccelerated JVM or a mock-only
substitute is not valid evidence for a `device` kind, so the task stays `blocked`, no device
artifact is fabricated, and the device-independent phase-00 work was completed instead
(TUN-0004, TUN-0005).

**Next eligible task while this is blocked:** none inside phase 00. TUN-0004 and TUN-0005 are
verified, TUN-0006 depends on TUN-0003, and every later phase is gated behind the phase-00
audit, which requires all six phase-00 tasks verified.

## RESOLVED — TUN-0002 blocked on build runtime JDK

**Resolved**: 2026-10-12T07:07Z.

### What the blocker actually was

The environment shipped only `jdk-25`. Kotlin 2.0.20 embedded in Gradle 8.11.1 cannot parse
Java 25 during Kotlin DSL / script compilation, so every Gradle invocation failed on
`java.lang.IllegalArgumentException: 25.0.1` inside
`org.jetbrains.kotlin.com.intellij.util.lang.JavaVersion.parse` before any project
dependency resolution happened. Installing a JDK through the OS installer required
elevation, which the local UAC flow kept cancelling — that was the loop the previous
session got stuck in.

### How it was resolved (no elevation needed)

The installer was never the only option: a plain JDK archive under the user profile works
just as well and needs no administrator rights.

1. Downloaded the Eclipse Temurin 21 JDK archive from the Adoptium API:
   `https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jdk/hotspot/normal/eclipse`
2. Extracted it with the system `tar.exe` (bsdtar handles zip) to
   `C:\Users\CyberSphinx\jdk-21\jdk-21.0.12.1+1`.
3. Pointed `local.properties` at it: `org.gradle.java.home=C:/Users/CyberSphinx/jdk-21/jdk-21.0.12.1+1`.
4. `./gradlew --version` then reported `Launcher JVM: 21.0.12.1 (Eclipse Adoptium)`.

### Three further defects the JDK fix exposed

The previous session could not see these because Gradle never got far enough to report
them. All three were real and are fixed:

1. **No plugin versions declared.** `app`/`core` requested `com.android.application`,
   `org.jetbrains.kotlin.android`, `org.jetbrains.kotlin.plugin.compose`,
   `com.google.devtools.ksp`, `com.google.dagger.hilt.android` and the serialization
   plugin while the root build declared no versions at all. Fixed with a version catalog
   (`gradle/libs.versions.toml`) as the single source of truth, applied `false` at the root.
2. **`android.useAndroidX` was never set.** AGP failed deliberately on AndroidX
   dependencies in `debugRuntimeClasspath` and the KSP classpath. Fixed in
   `gradle.properties`.
3. **Invalid build-script code and a wrong SDK key.** The root build referenced
   `com.android.build.gradle.tasks.AndroidJavadocs` and
   `KotlinNativeBinary` (neither exists), called `property(...)` from a class with no
   `Project` receiver, and `local.properties` used `android.sdk.path`, which is not an AGP
   key — AGP reads `sdk.dir`. All corrected; the `package` attribute was also removed from
   `AndroidManifest.xml` (an AGP 8 error).

### Proof it is closed

`STATE/evidence/TUN-0002/20261012T070749Z-clean-no-cache-build.json` — `clean
:app:assembleDebug :core:testDebugUnitTest --no-build-cache --no-configuration-cache`,
exit code 0, 72/72 tasks executed. The APK facts are in
`STATE/evidence/TUN-0002/apk-debug-build-artifact.txt`.

### Honest residual note

The JDK lives in a user-profile directory, not a system install. CI and any fresh machine
must provide JDK 17-21 themselves; `local.properties` is machine-local and is not
committed, so nothing silently depends on this specific path.
