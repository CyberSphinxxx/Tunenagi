#!/usr/bin/env python3
"""Produce the real-device evidence a task with `device` in `evidenceKinds` owes.

`taskctl verify` refuses a `device` task without an artifact whose name starts with
`device-`, and `OPERATIONS/STATE_PROTOCOL.md` refuses a fabricated one. This script is the
procedure from `OPERATIONS/EMULATOR_RUNTIME.md` in executable form, so the device step is one
command with a real verdict instead of eight hand-issued ones:

1. **Preflight** - find adb and a usable device. An `offline`/`unauthorized` device is
   reported as unusable rather than as absent, because those need different fixes.
2. **Instrumented tests** - `gradlew :app:connectedDebugAndroidTest` through
   `SCRIPTS/run_check.py`, so the run is recorded as a manifest `taskctl` accepts.
3. **Install and launch** - installs the debug APK and proves the process actually runs.
4. **Screenshots** - real `screencap` bytes per screen, named `device-<screen>.png`; a
   screencap that yields nothing usable is deleted rather than kept as fake evidence.
5. **Verify** - `taskctl verify <task>` with the manifest and the device artifacts.

It deliberately **refuses to boot an AVD without a working accelerator**. The measurements in
`OPERATIONS/EMULATOR_RUNTIME.md` show that `-accel off` starts a guest that never executes, so
booting that way would produce a hang and no evidence; `--allow-unaccelerated` exists only for
someone deliberately re-testing that finding.

Usage:

    python SCRIPTS/device_evidence.py --task TUN-0003
    python SCRIPTS/device_evidence.py --preflight          # report the runtime, change nothing
    python SCRIPTS/device_evidence.py --self-test          # prove the pure logic, no device
"""
import argparse
import os
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PY = sys.executable or "python"

# Reuse the wrapper command and the JDK discovery rather than restating them (ADR-001): the
# JDK trap documented in STATE/blockers.md would otherwise have to be solved twice.
sys.path.insert(0, str(ROOT / "SCRIPTS"))
import verify  # noqa: E402

SDK_HINTS = ("C:/Android", os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT"))
PNG_MAGIC = b"\x89PNG\r\n\x1a\n"
MIN_SCREENSHOT_BYTES = 5000
# Bottom-bar destinations, left to right, as they are laid out in TunenagiShell.
SCREENS = ("home", "search", "library")


def sdk_tool(name):
    """Absolute path to an SDK tool, or None. `adb`/`emulator` are never assumed on PATH."""
    for root in SDK_HINTS:
        if not root:
            continue
        for suffix in (f"platform-tools/{name}.exe", f"platform-tools/{name}",
                       f"emulator/{name}.exe", f"emulator/{name}"):
            candidate = Path(root) / suffix
            if candidate.is_file():
                return str(candidate)
    return shutil.which(name)


def shell(argv, timeout=600, env=None):
    """Run a command, return (exit code, combined output). Never raises for a bad command."""
    merged = os.environ.copy()
    if env:
        merged.update(env)
    try:
        proc = subprocess.run(argv, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                              text=True, timeout=timeout, env=merged, check=False)
        return proc.returncode, proc.stdout
    except subprocess.TimeoutExpired:
        return 124, f"TIMEOUT after {timeout}s: {' '.join(argv)}"
    except OSError as exc:
        return 127, f"COMMAND_ERROR: {exc}"


def adb_output(adb, args, serial=None, timeout=60):
    argv = [adb] + (["-s", serial] if serial else []) + args
    code, out = shell(argv, timeout=timeout)
    return out if code == 0 else out


def parse_devices(text):
    """(serial, state) pairs from `adb devices` output; header and daemon chatter dropped."""
    devices = []
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("List of devices") or line.startswith("*"):
            continue
        parts = line.split()
        if len(parts) >= 2:
            devices.append((parts[0], parts[1]))
    return devices


def choose_device(devices, serial=None):
    """(serial, reason) - a usable device, or the reason none is usable."""
    if serial:
        match = [d for d in devices if d[0] == serial]
        if not match:
            return None, f"device {serial} is not attached"
        if match[0][1] != "device":
            return None, f"device {serial} is attached but {match[0][1]}"
        return serial, ""
    ready = [name for name, state in devices if state == "device"]
    if ready:
        return ready[0], ""
    if devices:
        detail = ", ".join(f"{name} ({state})" for name, state in devices)
        return None, f"attached but not usable: {detail}"
    return None, "no device or emulator is attached"


def parse_wm_size(text):
    """(width, height) from `wm size`, preferring the override when the device reports one."""
    override = re.search(r"Override size:\s*(\d+)x(\d+)", text)
    physical = re.search(r"Physical size:\s*(\d+)x(\d+)", text)
    match = override or physical
    if not match:
        return None
    return int(match.group(1)), int(match.group(2))


def tap_targets(width, height):
    """Centres of the three bottom-bar destinations, for a given screen size."""
    y = height - max(60, int(height * 0.045))
    return {
        "home": (width // 6, y),
        "search": (width // 2, y),
        "library": (width * 5 // 6, y),
    }


def is_png(data):
    """True only for bytes that really are a complete-enough PNG."""
    return len(data) >= MIN_SCREENSHOT_BYTES and data.startswith(PNG_MAGIC)


def accelerators(emulator_check):
    """(accelerated, explanation) from `emulator-check accel`, whose exit code is the answer."""
    if not emulator_check:
        return False, "emulator-check was not found in the SDK"
    code, out = shell([emulator_check, "accel"], timeout=60)
    text = " ".join(out.split())
    return code == 0, f"exit {code}: {text}"


def wait_for_boot(adb, serial, timeout):
    """Poll sys.boot_completed. Returns (ok, detail). Never assumes a fixed delay."""
    deadline = time.monotonic() + timeout
    last = ""
    while time.monotonic() < deadline:
        state = [s for s, st in parse_devices(adb_output(adb, ["devices"])) if s == serial]
        if state and state[0] == "device":
            booted = adb_output(adb, ["shell", "getprop", "sys.boot_completed"], serial=serial)
            last = booted.strip()
            if last == "1":
                return True, "sys.boot_completed=1"
        time.sleep(10)
    return False, f"not booted within {timeout}s (last sys.boot_completed={last!r})"


def self_test():
    """Prove the pure logic without a device. A check that cannot fail is not a check."""
    failures = []

    def expect(condition, message):
        if not condition:
            failures.append(message)

    expect(parse_devices("List of devices attached\n\n") == [], "empty listing not parsed as empty")
    expect(parse_devices("* daemon started\nemulator-5554\toffline\n") == [("emulator-5554", "offline")],
           "offline device not parsed")
    expect(parse_devices("R58M12345\tdevice\n") == [("R58M12345", "device")], "device not parsed")
    expect(parse_devices("R58M12345\tunauthorized\n")[0][1] == "unauthorized", "state not preserved")

    serial, reason = choose_device([("emulator-5554", "offline")])
    expect(serial is None and "not usable" in reason and "offline" in reason,
           "an offline device must be reported as unusable, not absent")
    expect(choose_device([], None)[0] is None, "an empty listing must not yield a device")
    expect(choose_device([("R58M12345", "device")])[0] == "R58M12345", "ready device not chosen")
    expect(choose_device([("R58M12345", "device")], serial="other")[0] is None, "wrong serial accepted")

    expect(parse_wm_size("Physical size: 1080x2340") == (1080, 2340), "physical size not parsed")
    expect(parse_wm_size("Physical size: 1080x2340\nOverride size: 720x1560") == (720, 1560),
           "override size must win")
    expect(parse_wm_size("garbage") is None, "unparsable size must be None")

    points = tap_targets(1080, 2340)
    expect(set(points) == set(SCREENS), "every screen needs a tap target")
    xs = [points[name][0] for name in SCREENS]
    expect(xs == sorted(xs) and len(set(xs)) == 3, "tab centres must be distinct and ordered")
    expect(all(0 < x < 1080 and 0 < y < 2340 for x, y in points.values()), "tap outside the screen")
    expect(points["search"][0] == 540, "the middle tab must be horizontally centred")

    expect(is_png(PNG_MAGIC + b"\x00" * MIN_SCREENSHOT_BYTES), "a real PNG was rejected")
    expect(not is_png(b""), "empty bytes accepted as a screenshot")
    expect(not is_png(b"not a png" * 1000), "non-PNG bytes accepted as a screenshot")
    expect(not is_png(PNG_MAGIC), "a truncated PNG accepted as a screenshot")

    if failures:
        for item in failures:
            print(f"SELF-TEST FAIL: {item}")
        return 1
    print("PASS: device parsing, device selection, screen maths and PNG validation all behave.")
    return 0


def report_preflight(adb, emulator_check, serial=None):
    print(f"adb: {adb or 'NOT FOUND'}")
    if not adb:
        print("RESULT: no Android runtime reachable - adb is missing from the SDK and PATH.")
        return 2, None
    devices = parse_devices(adb_output(adb, ["devices"]))
    chosen, reason = choose_device(devices, serial)
    if chosen:
        print(f"device: {chosen} (state device)")
        return 0, chosen
    print(f"device: none usable - {reason}")
    accelerated, explanation = accelerators(emulator_check)
    print(f"emulator acceleration: {'available' if accelerated else 'NOT available'} ({explanation})")
    print("RESULT: no usable runtime. Either install AEHD and boot an AVD, or attach a device.")
    print("        See OPERATIONS/EMULATOR_RUNTIME.md; nothing was changed.")
    return 2, None


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--task", metavar="TUN-XXXX", help="Task whose device evidence to produce.")
    parser.add_argument("--avd", metavar="NAME", help="Boot this AVD when no device is attached.")
    parser.add_argument("--serial", metavar="SERIAL", help="Use this device instead of the first.")
    parser.add_argument("--screens", default=",".join(SCREENS),
                        help=f"Comma-separated screens to capture (default {','.join(SCREENS)}).")
    parser.add_argument("--boot-timeout", type=int, default=900, help="Seconds to wait to boot.")
    parser.add_argument("--allow-unaccelerated", action="store_true",
                        help="Boot an AVD even without acceleration (measured non-functional).")
    parser.add_argument("--no-verify", action="store_true",
                        help="Capture evidence but leave the task status alone.")
    parser.add_argument("--preflight", action="store_true", help="Report the runtime and exit.")
    parser.add_argument("--self-test", action="store_true", help="Check the pure logic, no device.")
    args = parser.parse_args()

    if args.self_test:
        return self_test()

    adb = sdk_tool("adb")
    emulator_check = sdk_tool("emulator-check")

    if args.preflight:
        code, _ = report_preflight(adb, emulator_check, args.serial)
        return code

    if not args.task or not re.fullmatch(r"TUN-\d{4}", args.task):
        print("ERROR: --task TUN-XXXX is required (or use --preflight / --self-test).")
        return 3

    code, serial = report_preflight(adb, emulator_check, args.serial)
    if code != 0:
        if not args.avd:
            return code
        emulator = sdk_tool("emulator")
        if not emulator:
            print("ERROR: --avd given but no emulator binary was found in the SDK.")
            return 3
        accelerated, explanation = accelerators(emulator_check)
        if not accelerated and not args.allow_unaccelerated:
            print(f"REFUSING to boot {args.avd}: no working accelerator ({explanation}).")
            print("A guest booted with -accel off never executes on this host - measured in")
            print("OPERATIONS/EMULATOR_RUNTIME.md. Pass --allow-unaccelerated only to re-test that.")
            return 2
        log = ROOT / "STATE" / "evidence" / args.task / "emulator-boot.log"
        log.parent.mkdir(parents=True, exist_ok=True)
        print(f"Booting {args.avd} (accelerated={accelerated}); console log: {log}")
        boot_args = [emulator, "-avd", args.avd, "-no-window", "-gpu", "swiftshader_indirect",
                     "-no-audio", "-no-boot-anim", "-no-snapshot"]
        with log.open("wb") as handle:
            subprocess.Popen(boot_args, cwd=ROOT, stdout=handle, stderr=subprocess.STDOUT)
        time.sleep(45)
        devices = parse_devices(adb_output(adb, ["devices"]))
        serial = next((name for name, state in devices if state in {"device", "offline"}), None)
        if not serial:
            print(f"ERROR: {args.avd} never appeared in `adb devices`; see {log}")
            return 1
        ok, detail = wait_for_boot(adb, serial, args.boot_timeout)
        print(f"boot: {detail}")
        if not ok:
            return 1

    java_home, detail = verify.find_jdk()
    if java_home is None:
        print(f"ERROR: no supported JDK (17-21): {detail}")
        return 1
    child_env = {"JAVA_HOME": str(java_home),
                 "PATH": str(Path(java_home) / "bin") + os.pathsep + os.environ.get("PATH", "")}
    print(f"JDK: {java_home}")
    print(f"device: {serial}")

    print("\n[1/4] instrumented tests on the device")
    manifest = None
    argv = [PY, "SCRIPTS/run_check.py", "--task", args.task, "--name", "connected-android-test",
            "--", *verify.GRADLE, ":app:connectedDebugAndroidTest"]
    code, out = shell(argv, timeout=3600)
    print(out[-2000:] if len(out) > 2000 else out)
    match = re.search(r"EVIDENCE=(\S+)", out)
    if match:
        manifest = match.group(1)
    if code != 0 or not manifest:
        print(f"FAIL: instrumented tests did not pass (exit {code}); no device evidence recorded.")
        return 1

    print("\n[2/4] install and launch the debug build")
    code, out = shell([*verify.GRADLE, ":app:assembleDebug"], timeout=1800, env=child_env)
    apk = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
    if code != 0 or not apk.is_file():
        print(f"FAIL: debug APK unavailable (exit {code}).\n{out[-1500:]}")
        return 1
    code, out = shell([adb, "-s", serial, "install", "-r", str(apk)], timeout=600)
    if code != 0 or "Success" not in out:
        print(f"FAIL: install failed (exit {code}).\n{out[-1500:]}")
        return 1
    shell([adb, "-s", serial, "shell", "am", "start", "-n",
           "com.tunenagi.player/.MainActivity"], timeout=120)
    running = False
    for _ in range(12):
        pid = adb_output(adb, ["shell", "pidof", "com.tunenagi.player"], serial=serial).strip()
        if pid:
            running = True
            print(f"launched: com.tunenagi.player is running as pid {pid}")
            break
        time.sleep(5)
    if not running:
        print("FAIL: the app was installed but its process never started.")
        return 1

    print("\n[3/4] capture screenshots")
    size = parse_wm_size(adb_output(adb, ["shell", "wm", "size"], serial=serial))
    if size is None:
        print("FAIL: could not read the screen size, so tap targets are unknown.")
        return 1
    targets = tap_targets(*size)
    evidence_dir = ROOT / "STATE" / "evidence" / args.task
    artifacts = []
    for name in [s.strip() for s in args.screens.split(",") if s.strip()]:
        if name not in targets:
            print(f"FAIL: unknown screen {name!r}; known: {', '.join(targets)}")
            return 1
        if name != "home":
            x, y = targets[name]
            shell([adb, "-s", serial, "shell", "input", "tap", str(x), str(y)], timeout=60)
            time.sleep(2)
        destination = evidence_dir / f"device-{name}.png"
        destination.parent.mkdir(parents=True, exist_ok=True)
        with destination.open("wb") as handle:
            subprocess.run([adb, "-s", serial, "exec-out", "screencap", "-p"], stdout=handle,
                           stderr=subprocess.DEVNULL, cwd=ROOT, check=False)
        data = destination.read_bytes() if destination.is_file() else b""
        if not is_png(data):
            destination.unlink(missing_ok=True)
            print(f"FAIL: screencap for {name} produced no usable PNG; nothing was kept.")
            return 1
        print(f"  device-{name}.png ({len(data)} bytes)")
        artifacts.append(str(destination.relative_to(ROOT)))

    print("\n[4/4] record the evidence on the task")
    if args.no_verify:
        print(f"--no-verify given; evidence kept in {evidence_dir.relative_to(ROOT)}")
        return 0
    argv = [PY, "SCRIPTS/taskctl.py", "verify", args.task, "--evidence", manifest]
    for artifact in artifacts:
        argv += ["--artifact", artifact]
    code, out = shell(argv, timeout=300)
    print(out.strip())
    if code != 0:
        print("FAIL: taskctl rejected the evidence; the task status is unchanged.")
        return 1
    print(f"\nVERDICT: PASS - {args.task} device evidence recorded ({len(artifacts)} screenshot(s)).")
    print("An independent audit is still required before the phase gate.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
