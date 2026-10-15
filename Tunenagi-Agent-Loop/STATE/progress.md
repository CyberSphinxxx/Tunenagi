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
- **Commit SHA**: d28ac34c1dd603f6cc39c9ccf25d18f5b9bf366f
- **Blockers or risks**: see `STATE/blockers.md` - the x86_64 emulator cannot start on this host
  without the AEHD driver or a physical device. Untracked build junk at the loop root
  (`gradle-8.11.1-bin.zip`, `.gradle-native/`, `.kotlin/`, `build_fail.log`) should be absorbed by
  TUN-0004's strict .gitignore.
- **Next exact action**: obtain an Android runtime (approve the AEHD elevation or attach a
  device), run `:app:connectedDebugAndroidTest`, install and launch the debug APK, capture
  `device-*.png` screenshots, then `taskctl.py verify TUN-0003 --artifact ...`. If the runtime
  stays unavailable, work TUN-0004 next.

## 2026-10-11 - Tunenagi session (TUN-0001) - recovered

This entry, plus `STATE/preflight/TUN-0001-environment.md` and
`STATE/preflight/TUN-0001_preflight_test.py`, was written to a stray `STATE/` directory at
the *repository root* instead of the kit's own `STATE/`. As a result TUN-0001's preflight
document was never committed and this log had no record of the task. TUN-0004 (repository
hygiene) copied the three artifacts into the kit and removed the root copy; nothing was
reworded. The only content change is a single over-long `print(...)` line in
`TUN-0001_preflight_test.py`, re-wrapped with identical output so the new formatting gate
can stay uniform; the recovered test was re-run afterwards and still passes. The text
below is the original entry's content.

- **Phase/task**: Phase 00 / TUN-0001 Preflight SDK and provider policies.
- **Summary/change paths**: `STATE/preflight/TUN-0001-environment.md` (environment
  preflight: JDK, Android SDK/adb/emulator status, official provider/API terms, rights
  posture) and `STATE/preflight/TUN-0001_preflight_test.py` (negative/failure-path test).
- **Commands and exit codes (evidence paths)**:
  - `python SCRIPTS/run_check.py --task TUN-0001 --name preflight-neg-tests -- python
    STATE/preflight/TUN-0001_preflight_test.py` -> exit 0. Evidence:
    `STATE/evidence/TUN-0001/20261010T192645Z-preflight-neg-tests.json` (that manifest
    records the absolute path the script had at the time, before the move).
  - `python SCRIPTS/taskctl.py verify TUN-0001 --evidence <manifest> --artifact
    STATE/evidence/TUN-0001/device-preflight.txt` -> exit 0.
- **Device / emulator observations**: no SDK/adb/emulator at the time; recorded as a
  blocker rather than faked.
- **Commit SHA**: not committed then; the recovered artifacts land with the TUN-0004 slice.
- **Blockers or risks**: the preflight document records JDK 25.0.1 as the environment JDK.
  Kotlin 2.0.20 cannot parse JDK 25, so builds now pin JDK 21 - see `STATE/blockers.md`.
- **Next exact action (as of that session)**: claim TUN-0002 once the SDK was configured.

## 2026-10-13T08:20Z - deepseek-v4-flash session (TUN-0004)

- **Phase/task**: Phase 00 / TUN-0004 "Repository hygiene and licenses" - claimed,
  implemented, command evidence captured. Device: none needed (`evidenceKinds: [command]`).
- **Summary/change paths**: added real formatting, secret and licensing hygiene.
  New: `OPERATIONS/GIT_CONVENTIONS.md`, `LEGAL/LICENSE_PROPOSAL.md`,
  `LEGAL/THIRD_PARTY_NOTICES.md`, `SCRIPTS/check_format.py`, `SCRIPTS/secret_scan.py`,
  `app/lint.xml`, `STATE/preflight/TUN-0001-environment.md` (recovered),
  `STATE/preflight/TUN-0001_preflight_test.py` (recovered).
  Changed: `.gitattributes` (LF policy for the index, CRLF only for bat/cmd, binaries
  marked), `.gitignore` (local Gradle distribution downloads, heap dumps, logs, .kotlin),
  `app/.../ui/shell/TunenagiShell.kt` (ModifierParameter), `app/src/main/res/mipmap-anydpi-v26/*`
  (monochrome layer), `core/.../TunenagiContrast.kt` (one over-long line re-wrapped).
  Removed: the stray repository-root `STATE/` (contents preserved in the kit, ADR-005).
- **Commands and exit codes (evidence paths)**, all under
  `STATE/evidence/TUN-0004/`:
  - `lint-and-assemble` -> exit 0, BUILD SUCCESSFUL, 103 tasks, 25 core tests / 0 failures
    (`20261013T080401Z-lint-and-assemble.json`). An earlier attempt failed with exit 1
    (`20261013T080049Z-lint-and-assemble.json`) - kept as the honest record of the
    `mipmap-anydpi` rename breaking AAPT2 resource linking.
  - `format-gate` -> exit 0, 118 tracked files, 0 problems (`20261013T080527Z-format-gate.json`);
    `format-gate-selftest` -> exit 0 (`20261013T080415Z-format-gate-selftest.json`).
  - `secret-scan --history` -> exit 0, 117 tracked files plus full history, 0 findings
    (`20261013T080448Z-secret-scan.json`); `secret-scan-selftest` -> exit 0
    (`20261013T080447Z-secret-scan-selftest.json`).
  - `preflight-recovered` -> exit 0 (`20261013T080531Z-preflight-recovered.json`).
  - `dependency-inventory` -> exit 0 (`20261013T080550Z-dependency-inventory.json`).
  - Sanitized summary artifact: `TUN-0004-static-check-summary.txt`.
- **Static analysis result**: `:core` 0 findings; `:app` 0 errors, and the three actionable
  warnings are gone - `ModifierParameter`, `ObsoleteSdkInt`, `MonochromeLauncherIcon` x2.
  What remains is 45 version-update advisories (`AndroidGradlePluginVersion` 6,
  `GradleDependency` 39), left visible on purpose because ADR-001 pins versions.
- **Defect found and corrected in the new gates**: `git show :<path>` resolves against the
  repository root, not the working directory, so both gates initially reported "clean"
  while reading nothing. Fixed by prepending the checkout prefix, and both now warn loudly
  if they read zero files. Found because a deliberately over-long line was not reported.
- **Device / emulator observations**: not applicable; this task's evidence kind is `command`
  only. The TUN-0003 runtime blocker in `STATE/blockers.md` is unchanged.
- **Commit SHA**: 778c50e (this slice: code, gates, licence proposal and state)
- **Blockers or risks**: the licence is still a **proposal** - no `LICENSE` file exists and
  publishing is not possible until the owner decides (see `LEGAL/LICENSE_PROPOSAL.md`).
  ktlint/detekt adoption is deferred to TUN-0005. `.freebuff/` at the repository root is
  another tool's local state and was deliberately left alone. The two new gate scripts are
  enforcement code and need coverage by the phase-00 independent audit (ADR-004).
- **Next exact action**: TUN-0005 (CI build and local verification commands) is next once
  TUN-0004 is verified; it must call `check_format.py`, `secret_scan.py` and the Gradle
  lint/test tasks. Phase 00 then needs its independent audit.

## 2026-10-13T08:40Z - deepseek-v4-flash session (TUN-0005)

- **Phase/task**: Phase 00 / TUN-0005 "CI build and local verification commands" - claimed,
  implemented, command evidence captured. Device: not applicable (`evidenceKinds: [command]`).
- **Summary/change paths**: added the CI pipeline and one local entry point that mirrors it.
  New: `.github/workflows/ci.yml` (hygiene + android jobs), `SCRIPTS/verify.py` (eight steps,
  one verdict, JDK discovery), `SCRIPTS/check_ci.py` (CI definition rules C1-C8),
  `SCRIPTS/check_lint.py` (lint baseline gate), `OPERATIONS/CI_AND_VERIFICATION.md`.
  Changed: `OPERATIONS/GIT_CONVENTIONS.md` (verify.py first, enforced lint baseline),
  `gradlew` file mode 100644 -> 100755.
- **Commands and exit codes (evidence paths)**, all under `STATE/evidence/TUN-0005/`:
  - `python SCRIPTS/verify.py` -> exit 0, `VERDICT: PASS - 8 step(s) green`
    (`20261013T083748Z-verify-all.json`). An earlier run failed with exit 2
    (`20261013T083421Z-verify-all.json`) and is kept: it is the record of three defects in
    `verify.py` that the run itself exposed (below).
  - `python SCRIPTS/verify.py --self-test` -> exit 0 (`20261013T083809Z-verify-selftest.json`).
  - `python SCRIPTS/check_ci.py --self-test` -> exit 0 (`20261013T083809Z-ci-check-selftest.json`).
  - `python SCRIPTS/check_lint.py --self-test` -> exit 0 (`20261013T083446Z-lint-gate-selftest.json`).
  - Sanitized summary artifact: `TUN-0005-ci-and-verification-summary.txt`.
- **Defects the first verify run exposed (all fixed, each with a self-test assertion now)**:
  1. the `assemble` step was missing `needs_java=True`, so it ran under the host's JDK 25 and
     failed with `IllegalArgumentException: 25.0.1`; the self-test now asserts that every
     Gradle step declares its JDK need;
  2. skipped steps were counted as failures, conflating "could not run" with "failed" -
     `skipped` and `failed` are now computed separately and the verdict distinguishes FAIL
     from INCOMPLETE;
  3. the JDK search missed a JDK extracted into a versioned subdirectory
     (`C:/Users/CyberSphinx/jdk-21/jdk-21.0.12.1+1`), which is why the first run fell back to
     JDK 25.
  Two further bugs were found in `check_ci.py` by its own self-test: a greedy `\s*` after `run:`
  captured the *following* line (so comments were treated as commands), and `uses:` detection
  missed list-item form, which let a deprecated pin pass.
- **CI verification result**: `python SCRIPTS/check_ci.py` passes on the real workflow -
  verified action pins, only locally-runnable commands, no provider host, full-history
  checkout, `contents: read`, no secrets, explicit working directory.
- **Device / emulator observations**: none; this task has no device evidence kind. TUN-0003's
  runtime blocker in `STATE/blockers.md` is unchanged.
- **Commit SHA**: 90250d5 (this slice: CI, verify.py, gates and state)
- **Blockers or risks**: CI has never executed - the first run happens on push, which is an
  owner action, and `OPERATIONS/CI_AND_VERIFICATION.md` states that as residual risk rather
  than implying a green pipeline. The new gate scripts need phase-00 audit coverage. ktlint
  adoption is still open (the F1-F5 gate covers formatting meanwhile).
- **Next exact action**: phase 00 has TUN-0003 (device evidence, blocked) and TUN-0006 (first
  device record, depends on TUN-0003 and TUN-0005) left; TUN-0006 cannot start before TUN-0003
  produces a runtime. Otherwise the phase-00 independent audit is the remaining gate.


## 2026-10-13T18:19Z - Buffy session (TUN-0003 runtime investigation; no task status change)

- **Phase/task**: Phase 00 / TUN-0003 "Design tokens and navigation shell" - stays `blocked`;
  this session produced measurements and documentation, not device evidence.
- **Summary/change paths**: measured every Android-runtime path available on this host and wrote
  the result down so the next session does not have to rediscover it. New:
  `OPERATIONS/EMULATOR_RUNTIME.md`. Changed: `STATE/blockers.md` (ACTIVE section replaced with
  the measured failure and three concrete unblock actions), `STATE/decisions.md` (ADR-007),
  `STATE/progress.md` (this entry), `STATE/status.json` (checkpoint timestamps). Created
  locally, outside the repository and not committed: the AVD `tunenagi_api33` under
  `%USERPROFILE%\.android\avd`.
- **Commands and exit codes**: raw probe output is kept locally under `STATE/evidence/TUN-0003/`
  (`emu-probe2-*.log`, `emu-verbose.log`, `emulator-boot.log`), all under:
  - `emulator -avd tunenagi_api33 -accel off -no-window -no-audio -no-boot-anim -no-snapshot`
    variants, sampled for the QEMU child's cumulative CPU time: 0.4375 s constant over 540 s in
    the control run, and 0.609375 / 0.453125 / 0.5 with no growth over 20-25 s in the other
    three. adb reported `emulator-5554 offline` throughout: no guest execution.
  - `-qemu -accel tcg` and `-qemu -cpu qemu64`: frontend SIGSEGV, exit 139.
  - `emulator -help-accel` documents `off` as "Disables acceleration entirely"; the verbose run
    shows QEMU printing `TCG doesn't support requested feature: CPUID.01H:ECX.avx`, so TCG is
    the live accelerator and the failure is not "no accelerator was selected".
  - `sc query aehd` -> 1060 before and after two cancelled elevation attempts.
- **Device / emulator observations**: no runtime obtained. An AVD now exists and boots far
  enough to open its adb port and register, so the remaining failure is precisely "the guest CPU
  never executes" rather than "the emulator will not start". No screenshot exists and none was
  fabricated.
- **Commit SHA**: this slice (the commit that follows this entry).
- **Blockers or risks**: TUN-0003 remains blocked on a one-time owner action - run
  `silent_install_safe.bat` from an elevated console, or attach a device. With phase 00 at 4/6
  verified and TUN-0006 depending on TUN-0003, `taskctl` correctly reports no runnable task.
- **Next exact action**: with a runtime available, run the four-step procedure in
  `OPERATIONS/EMULATOR_RUNTIME.md` (instrumented tests, install/launch, screenshots, then
  `taskctl verify TUN-0003 --artifact device-*.png`), then TUN-0006, then the phase-00 audit.

### 2026-10-13T19:07Z - addendum: the device step is wired, only the runtime is missing

- **Commands and exit codes**: `JAVA_HOME=<jdk-21> bash gradlew :app:connectedDebugAndroidTest`
  with no runtime -> exit 1, `DeviceException: No connected devices!`, after
  `:app:packageDebugAndroidTest` built the instrumentation APK. Evidence (a failing manifest,
  kept deliberately): `STATE/evidence/TUN-0003/20261013T190507Z-connected-android-test-preflight.json`. This is the honest form of the TUN-0003 claim: the
  implementation and its test APK are correct, and only the device is absent.
- **Summary/change paths**: recorded in `STATE/blockers.md` and
  `OPERATIONS/EMULATOR_RUNTIME.md`. `SCRIPTS/device_evidence.py` (preceding commit) now turns the
  device step into one command; its self-test passes, four injected mutations are caught, and
  `--preflight` plus the accelerator guard both exit 2 without writing any artifact or touching
  the task status.
- **Next exact action**: unchanged - install AEHD from an elevated console
  (`silent_install_safe.bat`) or attach a device, then
  `python SCRIPTS/device_evidence.py --task TUN-0003 --avd tunenagi_api33`.

## 2026-10-14T04:00Z - Codebuff session (phase 00 completion and phase 01)

- **Phase/tasks**: phase 00 audited and certified (TUN-0003, TUN-0006 verified on this session's
  evidence); phase 01 all seven tasks implemented and verified.
- **Owner decision (ADR-008)**: "continue until everything is finished, no need to test in android".
  `evidenceKinds` reduced to `command` on the 38 tasks that required `device`/`live`; the missing
  proof is enumerated in `OPERATIONS/DEFERRED_VERIFICATION.md` and the release gates still require it
  to be closed. The substitute is real: `app/src/sharedTest` Compose tests run on the JVM under
  Robolectric *and* as instrumented tests from one source, and its first JVM run found BUG-0001 (the
  compact shell mounted the mini-player twice). `verify.py` and CI now run `:app:testDebugUnitTest`,
  which they would not have done otherwise.
- **Phase 01**: domain vocabulary (TUN-0101), Room schema v1 + 43 constraint tests (TUN-0102), an
  explicit v1->v2 migration proven against a database rebuilt from version 1's own exported DDL
  (TUN-0103), conservative source matching with persisted manual locks (TUN-0104), queue persistence
  with a deterministic seeded shuffle (TUN-0105), atomic playlist/favourite/history repositories
  (TUN-0106), and the enforced dependency-direction gate (TUN-0107).
- **Defects found and closed**: BUG-0001 (Medium, duplicate mini-player), BUG-0002 (Low, no test for
  the "source disappears" trap - found by the phase-01 audit, fixed, retested).
- **Commands and exit codes (evidence paths)**: `bash gradlew clean :core:testDebugUnitTest
  :app:testDebugUnitTest :app:assembleDebug --no-build-cache` -> exit 0, 94 tasks;
  `python SCRIPTS/verify.py` -> VERDICT: PASS, 9/9 steps. 151 JVM tests (core 143, app 8), 0 failures.
  Manifests under `STATE/evidence/TUN-0003`, `TUN-0006`, `TUN-0101`..`TUN-0107`.
- **Device / emulator observations**: none and none possible - no device attached, `emulator-check
  accel` exits 6 (hypervisor driver absent). Recorded, not worked around (ADR-008).
- **Blockers or risks**: the deferral in `OPERATIONS/DEFERRED_VERIFICATION.md` is the main risk:
  nothing is proven on real hardware, no provider has ever been contacted, and CI has never run.
- **Next exact action**: audit phase 01, then phase 02 (Media3 service and local playback), whose
  device half is deferred under ADR-008.

## 2026-10-14T04:20Z - Codebuff session (phase 01 audit)

- **Phase/tasks**: phase 01 audited. `STATE/audits/phase-01-20261014-0415.md` written; the
  machine-check certificate is `STATE/audits/phase-01-pass.json` (`taskctl.py audit 01`, exit 0).
- **Independence limitation, stated in the report**: the same agent implemented the phase and audited
  it. The mitigation is that the audit re-ran every gate from the artifacts instead of trusting the
  task runs, and it did not come back clean - it found a real gap and a red gate (below). A separate
  reviewer must still re-run the commands before release.
- **What the audit checked that the task runs had not**:
  - every table's primary key in the exported schema, against the named trap "provider ID used as PK"
    (no external id, provider id, MBID, ISRC or URI is a primary key anywhere);
  - `python SCRIPTS/check_format.py` - **exit 2, 14 findings** that no task had run: 12 code lines
    over 140 characters and 2 Room-exported schema files with no final newline. Fixed; the generated
    export directory is now excluded by path rather than content (ADR-010), and the self-test still
    catches tabs, trailing whitespace, missing newlines, CRLF and long lines;
  - the named trap "accidental deletion after source loss" - **not covered** by any test. BUG-0002
    filed and closed with `DatabaseConstraintTest.deletingASourceLeavesTheTrackCreditsAndPlaylistsIntact`;
  - every evidence manifest's `sha256` recomputed: 59 manifests, all hashes match (the nonzero exits
    among them are the retained failing attempts, kept rather than deleted).
- **Commands and exit codes (evidence paths)**: `python SCRIPTS/verify.py` -> exit 0, 9/9 steps;
  `python SCRIPTS/check_format.py` -> 2 then 0; `python SCRIPTS/check_boundaries.py --self-test` ->
  exit 0 with all six injected violations caught; `taskctl.py audit 01` -> exit 0.
- **Device / emulator observations**: none and none possible (no device, no hypervisor driver). The
  audit says so explicitly rather than implying device proof, per ADR-008.
- **Blockers or risks**: the same deferral as before, plus one honest note - the audit's own trap
  coverage is now asserted by tests, but only the checks listed above were re-derived independently.
- **Next exact action**: phase 02 - the Media3 playback service and local playback path
  (`python SCRIPTS/taskctl.py next` to confirm eligibility).

## 2026-10-14T04:35Z - Codebuff session (phase 02, TUN-0201)

- **Phase/tasks**: TUN-0201 verified (phase 02, 1/8). `python SCRIPTS/taskctl.py next` now points at
  TUN-0202 (local media discovery).
- **What was built**: the app now has exactly one player. `core.playback.TunenagiPlaybackService` is
  a real `MediaLibraryService` whose `onCreate` builds one `TunenagiPlaybackEngine` (a real
  `ExoPlayer` with `USAGE_MEDIA`/`CONTENT_TYPE_MUSIC` and `handleAudioFocus = true`) and one
  `MediaLibrarySession` over it, handing the same session to every controller; `onDestroy` releases
  both. The app reaches playback only through `PlaybackConnection`, a bound `MediaController` named
  by a `SessionToken`, and the mini-player now shows the session's real state with a working
  play/pause control. The service and the permissions it needs are declared in the core module's
  manifest so playback cannot be dropped by an unrelated app-manifest edit.
- **A new rule, because a comment is not enforcement**: `SCRIPTS/check_boundaries.py` gained **B10**
  (only `core.playback` may import `androidx.media3.exoplayer` or build an
  `ExoPlayer`/`MediaSession`/`MediaLibrarySession`), with a self-test case for each direction and a
  matching row in `OPERATIONS/DEPENDENCY_GRAPH.md`. Recorded as ADR-011. This is what turns the
  phase's "player in the Activity" trap into something a build can fail on.
- **Commands and exit codes (evidence paths)** - all re-run against the final code, because the
  first version of this task was verified with an evidence manifest that predated three fixes:
  - `python SCRIPTS/verify.py` -> exit 0, all 9 steps green (package, format, secrets,
    ci-definition, boundaries, unit-tests, assemble, lint, lint-report)
    (`STATE/evidence/TUN-0201/20261015T195434Z-verify-all-steps.json`).
  - `bash gradlew clean :core:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug
    :app:assembleDebugAndroidTest --no-build-cache` -> exit 0, 123 tasks, 2m51s
    (`.../20261015T195612Z-clean-build-and-all-jvm-tests.json`). Earlier attempts that exited 1 are
    kept, not deleted: one because a leftover lint worker still held
    `app/build/intermediates/lint-cache` (daemons stopped, lint caches removed, run repeated), and
    the mutation run below, which is supposed to fail.
  - `:app:testDebugUnitTest --tests NowPlayingTest --rerun-tasks` against the *old* disconnect ->
    exit 1, `AssertionError: ... expected:<Connecting> but was:<Closed>`
    (`.../20261015T194929Z-old-disconnect-mutation-fails.json`); the same command with the fix in
    place -> exit 0, 6 tests (`.../20261015T195042Z-playback-tests-after-release-fix.json`). That
    pair is the evidence for BUG-0003.
  - `:core:testDebugUnitTest --tests TunenagiPlaybackServiceTest` -> exit 0, 9 tests;
    `python SCRIPTS/check_boundaries.py` -> exit 0 (53 files); `--self-test` -> exit 0 with both
    B10 directions firing and a `MediaController` in the UI still accepted.
  - `bash gradlew :app:lintDebug :core:lintDebug` failed on the first attempt of this task, and the
    two causes were fixed in the source rather than baselined: four `UnsafeOptInUsageError`s in
    `PlaybackConnection.kt` (`MediaController.Builder(...)`, `releaseFuture`) and one new
    `ModifierParameter` warning in `MiniPlayer.kt` (`modifier` must be the first optional
    parameter). `python SCRIPTS/verify.py --only lint --only lint-report` -> exit 0.
- **A leak fixed before it shipped (BUG-0003)**: `disconnect()` released the controller only when
  one had already *attached*, so a disconnect while the controller was still binding released
  nothing, kept the pending future, and every later `connect()` short-circuited on it - the app
  stayed `Closed` for the rest of the process with a bound service still behind it. `onError` had
  the same shape: it reported `Failed` and left the controller attached. All three paths now leave
  through one `controllerRelease()`, and `NowPlayingTest` fails against the old code (above).
- **Test totals now**: 177 JVM tests (`:core` 152 across 14 classes, `:app` 25 across 4),
  0 failures. The app's 25 are `NowPlayingTest` 6 (`:core` counts are unchanged by this task's later
  fixes).
- **Device / emulator observations**: none possible (no device, no hypervisor driver), so the
  substitute is stated rather than implied: the tests drive a **real** `ExoPlayer`, a real
  `MediaLibrarySession` and the real merged manifest under Robolectric, and they prove the state
  machine, the identity invariants, the failure paths and the declarations. They do **not** prove
  codecs, audio focus, background survival, the notification or lockscreen controls - all of that is
  in `OPERATIONS/DEFERRED_VERIFICATION.md` (ADR-008), and the task note records the substitution.
- **Blockers or risks**: unchanged standing deferral; plus the honest limit of TUN-0201 - no sound
  has been produced by this project yet, because local media discovery is TUN-0202/0203. The
  connection's *successful* binding path is asserted only as far as the JVM allows (the state is
  `Connecting`, not `Connected`, without a device): the same deferral ADR-008 records.
- **Next exact action**: TUN-0202 - discover rights-cleared local media through MediaStore and SAF
  grants, and handle revocation and unavailable files.

## 2026-10-15T20:30Z - Codebuff session (phase 02, TUN-0202)

- **Phase/tasks**: TUN-0202 verified (phase 02, 2/8). `python SCRIPTS/taskctl.py next` now points at
  TUN-0203 (local playable source resolver).
- **What was built**: a new `com.tunenagi.core.provider.local` layer. `MediaStoreAudioDiscovery`
  reads the device library (`IS_MUSIC != 0`, a bounded projection, artwork derived from the album id)
  and `SafTreeAudioDiscovery` reads one persistable folder grant through its child-documents uri;
  both report `PermissionRequired` when the provider throws `SecurityException`, which is Android's
  own revocation signal, and `Failed` - never an empty library - when the provider breaks. Row
  reading goes through one `AudioRowSource` seam that *consumes* the cursor in a block, so the query
  and the `use` are adjacent and no caller can leak one. `LocalLibraryIndexer` applies what was found
  to real Room rows through the database's own unique identity `(providerId, externalId,
  sourceVariant)`: new files are added, changed tags are applied, an unchanged file writes nothing at
  all, a file that is gone is marked `StorageGone` (never deleted, so its track, credits, playlist
  entries and history survive), a file that comes back is *restored* rather than reimported, and a
  duplicate row inside one pass is counted instead of aborting the transaction.
- **Grants fail independently**: MediaStore and each SAF folder are separate variants, so
  `LocalLibraryScanner` marks only the failing grant's sources `PermissionRequired` - losing a folder
  must not make the on-device library unreadable - while a provider *error* changes nothing, because a
  broken query is not evidence that a file is gone.
- **The gate grew a rule, and a hole was found in it**: `SCRIPTS/check_boundaries.py` gained **B11**
  (a provider adapter may read Android storage - that is what it is for - but may not import Compose,
  playback, plugins or the networking libraries), with both directions in the self-test and the row
  in `OPERATIONS/DEPENDENCY_GRAPH.md`. While adding it, rule lookup turned out to be an *exact*
  package match, so any sub-package of a layer (`com.tunenagi.core.model.queue`) was checked by
  nothing; lookup is now the longest prefix match, and a self-test case proves the domain layer's
  rule reaches a sub-package. Recorded as ADR-012.
- **Commands and exit codes (evidence paths)**:
  - `:core:testDebugUnitTest --tests LocalMediaDiscoveryTest --rerun-tasks` -> exit 0, 19 tests
    (`STATE/evidence/TUN-0202/20261015T202441Z-local-discovery-tests.json`). Two of those failed on
    the first run for real reasons, both fixed in the test rather than in the expectation: a fixture
    row was duplicated by mistake, and `updatedAt` cannot move when the clock is frozen, so the
    second scan now happens a minute later. The failure output is in that first run's XML, kept on
    disk.
  - `python SCRIPTS/verify.py` -> exit 0, 9/9 steps (`.../20261015T202518Z-verify-all-steps.json`).
    The first full run **failed** `lint-report` on a genuine finding - `Recycle: this Cursor should be
    freed up after use` (`.../20261015T201905Z-verify-all-steps.json`) - which is what the
    consume-in-a-block seam above fixes. It was fixed rather than baselined.
  - `python SCRIPTS/check_boundaries.py` -> exit 0 (69 files); `--self-test` -> exit 0, including
    B11 in both directions and the sub-package case (`.../20261015T202722Z-boundary-gate-and-self-test.json`).
    Two earlier boundary runs exited non-zero inside this task and are kept: one because a careless
    edit of mine had stripped the `core/src/main/java/...` prefix from `PLAYER_LAYER_PATH`, which made
    B10 fire on the playback layer itself - the gate caught a regression in the gate, which is the
    only reason it was noticed.
- **Test totals now**: 196 JVM tests (`:core` 171 across 15 classes, `:app` 25 across 4), 0 failures.
- **Device / emulator observations**: none possible (no device, no hypervisor driver). The substitute
  is stated rather than implied: the tests drive the real query-building code, real `Cursor` objects
  and a real Room database with its real indices. They do **not** prove a real MediaStore scan, a real
  SAF picker grant, provider mime-type behaviour or artwork decoding - all of that is in
  `OPERATIONS/DEFERRED_VERIFICATION.md` (ADR-008), and the task note records the substitution.
- **Blockers or risks**: the standing deferral above; plus the honest limits of this slice - album and
  artist rows are created from tags without grouping, artwork is referenced but never decoded, and
  nothing yet *plays* a discovered file (that is TUN-0203, including `StorageGone`/`PermissionRequired`
  at resolve time).
- **Next exact action**: TUN-0203 - resolve a playable local source for a track and its `TrackSource`,
  with typed errors for unsupported formats, missing files and missing permission.
