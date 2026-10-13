# Android runtime: which paths exist, and what each one actually does on this host

`SPEC/QUALITY_GATES.md` and the task contracts require *real* device evidence for the tasks
whose `evidenceKinds` contain `device` (TUN-0003, TUN-0006). `STATE/blockers.md` records that
no runtime was available. This document is the measured record of every runtime path that was
tried, so that the next session neither repeats a dead end nor repeats the claim without the
measurement behind it.

Everything below was measured on this machine on 2026-10-14. Nothing here is a claim that a
device test passed - no device test has run yet.

## Host and toolchain under test

| Fact | Value |
| --- | --- |
| CPU | AMD Athlon 3000G, 2 cores / 4 threads |
| Firmware virtualisation | `VirtualizationFirmwareEnabled` True, `VMMonitorModeExtensions` True, SLAT True |
| Windows | 10.0.19045 (Windows 10 22H2) |
| Hyper-V / WHPX | not running (`HypervisorPresent` False) |
| AEHD driver | not installed (`sc query aehd` -> 1060) |
| Android Emulator | 37.2.12.0 (build_id 16428233), graphics backend gfxstream |
| System image | `system-images;android-33;google_apis;x86_64` (installed) |
| AVD | `tunenagi_api33`, device profile `pixel_5`, `-m 2048`, `-smp cores=2`, skin 1080x2340 |
| Java per `local.properties` | none usable; builds need JDK 21 (see `STATE/blockers.md`) |

The AVD was created with:

    avdmanager create avd -n tunenagi_api33 -k "system-images;android-33;google_apis;x86_64" -d pixel_5

That command prints `Error: Could not load devices from .../x86_64/devices.xml` because the
installed system image ships no `devices.xml`, but it still creates a usable AVD - confirmed
by `emulator -list-avds` listing `tunenagi_api33` and by the AVD booting far enough to open
its adb port. The error is cosmetic here; do not treat it as a failed creation.

## Path 1 - software emulation (`-accel off`): **starts, then never executes**

This is the one path that needs no administrator rights, so it was tested hardest. The
emulator's own help documents it:

    $ emulator -help-accel
    off    Disables acceleration entirely. Mostly useful for debugging.

Base invocation for every probe below:

    emulator -avd tunenagi_api33 -accel off -no-window -no-audio -no-boot-anim -no-snapshot [extra flags]

Samples are the QEMU child's cumulative CPU time, taken ~60 s after launch and again 20-25 s
later. A live guest adds CPU time continuously; a guest that never executes adds none.

| # | Extra flags | QEMU CPU (t0 -> t+20/25 s) | adb | Outcome |
| --- | --- | --- | --- | --- |
| A | `-gpu off` | 0.609375 -> 0.609375 | `emulator-5554 offline` | QEMU alive, guest never executes |
| B | `-cores 1` (swiftshader_indirect) | 0.453125 -> 0.453125 | `emulator-5554 offline` | both processes alive, guest never executes |
| C | `-qemu -cpu max` | 0.5 -> 0.5 | `emulator-5554 offline` | QEMU alive, guest never executes |
| D | `-qemu -accel tcg` | n/a | no device | frontend `SIGSEGV` (exit 139), nothing survives |
| E | `-qemu -cpu qemu64` | n/a | no device | frontend `SIGSEGV` (exit 139), nothing survives |
| F | control: as B, left running 9 minutes | 0.4375 for the full 540 s | `offline` throughout | no execution in nine minutes |

Raw probe output kept locally (not committed; `STATE/evidence/**` is ignored):
`STATE/evidence/TUN-0003/emu-probe2-*.log`, `emu-verbose.log`, `emulator-boot.log`.

Two details matter more than the table:

1. **`-accel off` is accepted.** The emulator does not refuse to run: it reports
   `hasCompatibleHypervisor ... Ok`, warns `x86_64 emulation may not work without hardware
   acceleration!`, spawns `qemu-system-x86_64-headless.exe`, opens the console (5554) and adb
   (5555) ports, and reaches `Sending adb public key` - the frontend's adb registration step.
   It then simply never runs the guest.
2. **TCG really is the selected accelerator.** In the verbose run QEMU prints
   `TCG doesn't support requested feature: CPUID.01H:ECX.avx [bit 28]` and `...f16c [bit 29]`,
   which only the TCG accelerator emits. So this is not the case of "no accelerator was
   chosen". TCG initialises and then does not execute the guest.

Consequence, stated plainly: **`-accel off` is not a usable runtime here.** It cannot produce
a `device-` artifact for TUN-0003, and it must not be offered as a CI substitute for device
tests later in the project.

### An earlier claim in this repository was wrong

`STATE/blockers.md` said the emulator "demands hardware acceleration" and that `-accel off`
"is not a supported fallback". The emulator does not demand it, and the fallback *is*
accepted - it is simply non-functional on this build. The distinction matters: "refuses to
start" invites hunting for a flag, while "starts happily and silently never executes" is the
observable that the next session must not re-diagnose from scratch.

### Two side notes measured while testing

- An ARM64 image is not a way out. The upstream emulator's QEMU2 launcher rejects an ARM64 AVD
  on an x86_64 host (`Avd's CPU Architecture 'arm64' is not supported by the QEMU2 emulator on
  x86_64 host`), and current emulator release notes announce no ARM64-guest emulation for x86
  hosts. Do not spend a ~1 GB download on this without checking an ARM host first.
- Some emulator builds gate software emulation behind `ANDROID_I_WANT_MY_TCG=yes`. Version
  37.2.12 here does not: that string does not occur anywhere in the installed emulator
  binaries (checked in both ASCII and UTF-16LE), and `-accel off` proceeds without it.

## Path 2 - AEHD acceleration: **the supported path, needs administrator rights**

Google's package is already downloaded at
`C:/Android/extras/google/Android_Emulator_Hypervisor_Driver` (`aehd.Sys`, `aehd.Inf`,
`silent_install.bat`, `silent_install_safe.bat`; `README.txt` states it is for Windows without
WHPX). The firmware supports it: AMD-V is enabled, and Hyper-V is not running, which is the
configuration AEHD requires.

Install command (from an **elevated** console; `silent_install_safe.bat` does the install
without spawning its own UAC prompt, `silent_install.bat` self-elevates via a VBScript
`ShellExecute runas`):

    cd /d C:\Android\extras\google\Android_Emulator_Hypervisor_Driver
    silent_install_safe.bat

It installs the driver from `aehd.Inf` and starts the `aehd` service. Uninstall with
`silent_install_safe.bat -u`.

### Elevation status as measured

- The account `CyberSphinx` **is** a member of `Administrators`, so the install is possible in
  principle.
- The agent shell is not elevated (`net session` -> exit 2), so the install needs a real
  elevation step.
- Two attempts were made through the sandbox's approved-elevation flow. Both failed with
  `The operation was canceled by the user ... The UAC prompt may have been cancelled or
  blocked.` Nothing ran.
- Verified afterwards that nothing was half-installed: `sc query aehd` still returns 1060, and
  no `aehd`/`gvm` service exists.

So the remaining question is not technical but a **one-time owner action**: approve the UAC
prompt when it appears, or run the two commands above in an elevated console.

## Path 3 - a physical device

Any Android device with USB debugging and API 26+ is enough, and needs no driver install:

    adb devices          # must list the device as "device", not "unauthorized"/"offline"
    adb shell getprop ro.build.version.sdk

This is the lowest-risk path if the UAC prompt cannot be shown (for example over a remote
session using the secure desktop).

## Procedure once a runtime exists

The implementation side of TUN-0003 is already committed (`d28ac34`, `880fca6`); only the device
evidence is outstanding. Every later phase needs the same kind of proof, so the procedure is
executable rather than a list of commands to retype:

    python SCRIPTS/device_evidence.py --task TUN-0003
    python SCRIPTS/device_evidence.py --task TUN-0003 --avd tunenagi_api33   # boot it first
    python SCRIPTS/device_evidence.py --preflight                            # report only

`SCRIPTS/device_evidence.py` does five things and stops at the first real failure:

1. **Preflight** - finds `adb` and a device whose state is `device`. An `offline`/`unauthorized`
   device is reported as unusable rather than absent, because the two need different fixes.
2. **Instrumented tests** - `:app:connectedDebugAndroidTest` through `SCRIPTS/run_check.py`, so
   the run lands as a manifest `taskctl` accepts.
3. **Install and launch** - installs `app-debug.apk`, then waits until the process actually runs
   (`pidof com.tunenagi.player`) instead of trusting that `am start` returned.
4. **Screenshots** - taps the three bottom-bar destinations and captures `device-home.png`,
   `device-search.png` and `device-library.png`. Bytes that are not a real PNG are deleted,
   never kept as a placeholder.
5. **Verify** - `taskctl verify TUN-0003 --evidence <manifest> --artifact device-*.png`.

Two deliberate refusals keep it honest:

- It will not boot an AVD without a working accelerator (`--allow-unaccelerated` exists only to
  re-test the finding above). Booting the measured-broken path would waste twenty minutes and
  still produce no evidence.
- It never writes a `device-` artifact it did not receive from a device, because `taskctl
  verify` exists to refuse exactly that claim. A failed run leaves the task status untouched.

`gradlew` needs JDK 17-21 on `PATH`/`JAVA_HOME`; on this machine that is
`C:/Users/CyberSphinx/jdk-21/jdk-21.0.12.1+1`, and the script discovers it through
`SCRIPTS/verify.py` rather than restating the search (ADR-001).

An emulator boot on 2 host cores is slow even when accelerated, so the script polls
`sys.boot_completed` with a bounded timeout (default 900 s) instead of sleeping a fixed amount,
and keeps the emulator's console log at `STATE/evidence/<task>/emulator-boot.log`.

The manual equivalent, if the script is unavailable for some reason:

    bash gradlew :app:connectedDebugAndroidTest
    adb install -r app/build/outputs/apk/debug/app-debug.apk
    adb shell am start -n com.tunenagi.player/.MainActivity
    adb exec-out screencap -p > STATE/evidence/TUN-0003/device-home.png
    python SCRIPTS/taskctl.py verify TUN-0003 \
        --evidence STATE/evidence/TUN-0003/<manifest>.json \
        --artifact STATE/evidence/TUN-0003/device-home.png

## What this document is not

- It is not evidence that any device test passed. No `device-` artifact exists yet, which is
  exactly why TUN-0003 is `blocked` rather than `verified`.
- It is not a claim about other hosts. On a machine with WHPX enabled, or with AEHD installed,
  the accelerated path is the normal one and none of the above applies.
- The crash detail (frontend `SIGSEGV` in configurations D and E) is recorded because it
  happened, not as a diagnosed emulator bug; the crash reports the emulator writes are its own
  `emu-crash-37.2.12.db` crashpad database under `%TEMP%\AndroidEmulator`.
