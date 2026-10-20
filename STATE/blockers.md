# Tunenagi blockers

**No P0 task is currently blocked.** Phase 02 is audited (PASS for the machine-verifiable half) and
phase 03 is open. The device-only proof for every phase remains deferred under ADR-008 and is
recorded in `OPERATIONS/DEFERRED_VERIFICATION.md`, not as a task blocker.

The resolved entries below are kept so a future session does not repeat the same dead end.

## RESOLVED — TUN-0003 needed an Android runtime

**Resolved**: 2026-10-13T19:47Z, under ADR-008 (owner-directed build without device testing).

### What the blocker was

TUN-0003 (design tokens and navigation shell) carried `command,device` evidence, and
`taskctl verify` required a non-empty `device-` artifact. The work itself was implemented and
green, but every route to an Android runtime failed:

- `adb devices` listed no device; `sc query aehd` returned 1060 (no accelerator service).
- `emulator -avd tunenagi_api33 -accel off ...` spawned QEMU and registered with adb, but the
  guest never executed: QEMU's CPU time stayed flat for a 9-minute control run and in three other
  configurations. `-qemu -accel tcg` and `-qemu -cpu qemu64` crashed the frontend (SIGSEGV).
- An ARM64 image was refused by the emulator's QEMU2 launcher on this x86_64 host.
- Two approved-elevation attempts to install the hypervisor driver were cancelled at the UAC
  prompt; `sc query aehd` still returned 1060 afterwards.

The full method, commands and raw samples are in `OPERATIONS/EMULATOR_RUNTIME.md`; the
connected-test preflight failure is
`STATE/evidence/TUN-0003/20261013T190507Z-connected-android-test-preflight.json`.

### How it was resolved

The owner directed that the build continue without a device (ADR-008). `evidenceKinds` was
reduced to `command` for TUN-0003 and the other device tasks, and the missing device proof was
moved to `OPERATIONS/DEFERRED_VERIFICATION.md` rather than fabricated or silently dropped. TUN-0003
was then verified against its command evidence and the phase-00 audit passed. The underlying
on-device launch proof is still outstanding and is listed in that register; this resolution is a
deferral, not a waiver.

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
