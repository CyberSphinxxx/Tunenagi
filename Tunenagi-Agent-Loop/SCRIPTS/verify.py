#!/usr/bin/env python3
"""One command that runs every check this repository owes a commit.

`OPERATIONS/GIT_CONVENTIONS.md` lists the checks; this runs them in one place so a green
local run and a green CI run mean the same thing. Each step is an independent process and
its real exit code is reported; the script exits non-zero if any step fails.

Two problems this deliberately solves:

* **The JDK trap.** Kotlin 2.0.20 cannot parse JDK 25, and Gradle ignores
  `org.gradle.java.home` in `local.properties`, so a plain `bash gradlew` on a machine whose
  default JDK is newer fails with `IllegalArgumentException: 25.0.1` before any project code
  runs. This script locates a JDK in the supported range (17-21), exports `JAVA_HOME` for
  the Gradle steps, and refuses to run them with a readable explanation instead of letting
  Gradle fail obscurely (`STATE/blockers.md`).
* **Silent no-ops.** A step whose command cannot be found is a failure, never a skip. The
  `--self-test` proves the orchestrator propagates a failing step and reports FAIL.

Usage:

    python SCRIPTS/verify.py              # everything, including the Gradle steps
    python SCRIPTS/verify.py --fast       # only the checks that need no JDK
    python SCRIPTS/verify.py --list
    python SCRIPTS/verify.py --only format --only secrets
    python SCRIPTS/verify.py --self-test  # prove the orchestrator fails when a step fails
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

JDK_MIN = 17
JDK_MAX = 21

# Where to look for a usable JDK when JAVA_HOME is absent or unsupported. The doubled
# `jdk-*/*` patterns matter: a JDK extracted into a versioned subdirectory
# (`C:/Users/<user>/jdk-21/jdk-21.0.12.1+1`) is not a JDK home itself, and missing this
# made verify fall back to an unsupported JDK and report a misleading Gradle failure.
JDK_SEARCH_GLOBS = (
    "C:/Users/*/jdk-*",
    "C:/Users/*/jdk-*/*",
    "C:/Users/*/.jdks/*",
    "C:/Program Files/Java/jdk*",
    "C:/Program Files/Eclipse Adoptium/jdk*",
    "C:/Program Files/Android/Android Studio/jbr",
    "/usr/lib/jvm/*",
    "/usr/lib/jvm/*/*",
    "/Library/Java/JavaVirtualMachines/*/Contents/Home",
    str(Path.home() / ".sdkman/candidates/java/*"),
    str(Path.home() / "jdk-*"),
    str(Path.home() / "jdk-*/*"),
)


def gradle_command():
    """How to invoke the wrapper on this host, matching GIT_CONVENTIONS.md."""
    if shutil.which("bash") and (ROOT / "gradlew").is_file():
        return ["bash", "gradlew"]
    if os.name == "nt":
        return [os.environ.get("COMSPEC", "cmd"), "/c", "gradlew.bat"]
    return ["./gradlew"]


GRADLE = gradle_command()


class Step:
    def __init__(self, name, argv, needs_java=False, description=""):
        self.name = name
        self.argv = argv
        self.needs_java = needs_java
        self.description = description


STEPS = [
    Step("package", [PY, "SCRIPTS/validate_package.py"],
         description="harness structure and task graph"),
    Step("format", [PY, "SCRIPTS/check_format.py"],
         description="text hygiene of the stored content"),
    Step("secrets", [PY, "SCRIPTS/secret_scan.py", "--history"],
         description="credentials in tracked files and history"),
    Step("ci-definition", [PY, "SCRIPTS/check_ci.py"],
         description="CI workflow matches what runs locally"),
    # Both modules: `:app` runs the Android UI suites from `src/sharedTest` on the JVM under
    # Robolectric, so leaving it out would mean the shell's only automated check never ran in CI.
    Step("unit-tests", GRADLE + [":core:testDebugUnitTest", ":app:testDebugUnitTest"], needs_java=True,
         description="JVM unit tests in :core and :app (Android UI suites included)"),
    Step("assemble", GRADLE + [":app:assembleDebug"], needs_java=True,
         description="debug APK builds"),
    Step("lint", GRADLE + [":app:lintDebug", ":core:lintDebug"], needs_java=True,
         description="Android Lint"),
    Step("lint-report", [PY, "SCRIPTS/check_lint.py"], needs_java=True,
         description="lint findings stay inside the accepted baseline"),
]


def java_major(java_executable):
    """Major version of a JDK, or None when it cannot be determined."""
    try:
        proc = subprocess.run([str(java_executable), "-version"], stdout=subprocess.PIPE,
                              stderr=subprocess.STDOUT, text=True, timeout=60, check=False)
    except (OSError, subprocess.SubprocessError):
        return None
    match = re.search(r'version "(\d+)(?:\.(\d+))?', proc.stdout)
    if not match:
        return None
    major = int(match.group(1))
    if major == 1 and match.group(2):  # legacy 1.8 style
        major = int(match.group(2))
    return major


def find_jdk():
    """Returns (java_home, major) for the first supported JDK, else (None, reason)."""
    seen = []
    candidates = []
    if os.environ.get("JAVA_HOME"):
        candidates.append(Path(os.environ["JAVA_HOME"]))
    for pattern in JDK_SEARCH_GLOBS:
        candidates.extend(sorted(Path(p) for p in _glob(pattern)))
    for home in candidates:
        executable = home / "bin" / ("java.exe" if os.name == "nt" else "java")
        if not executable.is_file():
            continue
        major = java_major(executable)
        if major is None:
            continue
        if JDK_MIN <= major <= JDK_MAX:
            return home, major
        seen.append(f"{home} (JDK {major})")
    detail = ", ".join(seen) if seen else "no JDK found in JAVA_HOME or the usual locations"
    return None, detail


def _glob(pattern):
    import glob
    return glob.glob(pattern)


def run_step(step, java_home):
    env = os.environ.copy()
    if java_home is not None:
        env["JAVA_HOME"] = str(java_home)
        env["PATH"] = str(Path(java_home) / "bin") + os.pathsep + env.get("PATH", "")
    started = time.monotonic()
    try:
        proc = subprocess.run(step.argv, cwd=ROOT, stdout=subprocess.PIPE,
                              stderr=subprocess.STDOUT, env=env, check=False)
        code = proc.returncode
        output = proc.stdout.decode("utf8", "replace")
    except OSError as exc:
        code = 127
        output = f"COMMAND_ERROR: cannot execute {step.argv[0]}: {exc}"
    return code, output, time.monotonic() - started


def tail(text, lines=12):
    rows = [row for row in text.splitlines() if row.strip()]
    return "\n".join(rows[-lines:])


def self_test():
    """Proves the orchestrator reports a failing step rather than a green verdict."""
    failures = []
    ok = Step("self-test-pass", [PY, "-c", "print('fine')"])
    bad = Step("self-test-fail", [PY, "-c", "raise SystemExit(3)"])
    missing = Step("self-test-missing", ["definitely-not-a-real-executable-xyz"])

    code, _, _ = run_step(ok, None)
    if code != 0:
        failures.append(f"passing step reported exit {code}")
    code, _, _ = run_step(bad, None)
    if code != 3:
        failures.append(f"failing step reported exit {code}, expected 3")
    code, output, _ = run_step(missing, None)
    if code == 0 or "COMMAND_ERROR" not in output:
        failures.append("missing command was not reported as a command error")

    # A skipped step must land in `skipped`, never in `failed`.
    results = [("a", None, 0.0, ""), ("b", 0, 0.0, ""), ("c", 2, 0.0, "")]
    skipped = [n for n, code, _, _ in results if code is None]
    failed = [n for n, code, _, _ in results if code is not None and code != 0]
    if skipped != ["a"] or failed != ["c"]:
        failures.append(f"skip/fail classification is wrong: skipped={skipped} failed={failed}")

    # Every Gradle step must declare that it needs the JDK, or it silently runs on whatever
    # the host default is - the exact failure this script exists to prevent.
    undeclared = [s.name for s in STEPS if "gradlew" in " ".join(s.argv) and not s.needs_java]
    if undeclared:
        failures.append(f"Gradle steps missing needs_java: {undeclared}")

    if failures:
        for item in failures:
            print(f"SELF-TEST FAIL: {item}")
        return 1
    print("PASS: verify reports step exit codes and treats a missing command as a failure.")
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--fast", action="store_true", help="Skip steps that need a JDK.")
    parser.add_argument("--only", action="append", default=[], metavar="NAME",
                        help="Run only the named step (repeatable).")
    parser.add_argument("--list", action="store_true", help="List the steps and exit.")
    parser.add_argument("--self-test", action="store_true",
                        help="Verify the orchestrator propagates failures.")
    args = parser.parse_args()

    if args.self_test:
        return self_test()

    if args.list:
        for step in STEPS:
            java = " (needs JDK)" if step.needs_java else ""
            print(f"{step.name}{java}: {' '.join(step.argv)}")
        return 0

    selected = [s for s in STEPS if not args.only or s.name in args.only]
    if args.only:
        unknown = sorted(set(args.only) - {s.name for s in STEPS})
        if unknown:
            print(f"ERROR: unknown step(s): {', '.join(unknown)}")
            return 2
    if args.fast:
        selected = [s for s in selected if not s.needs_java]

    java_home, detail = find_jdk()
    if java_home is not None:
        print(f"JDK: {java_home}")
    elif any(s.needs_java for s in selected):
        print(f"JDK: none in the supported range {JDK_MIN}-{JDK_MAX} - {detail}")
        print("     Kotlin 2.0.20 cannot parse newer JDKs; see STATE/blockers.md.")

    results = []
    for step in selected:
        if step.needs_java and java_home is None:
            results.append((step, None, 0.0, "no supported JDK available"))
            print(f"[SKIP] {step.name}: {step.description} (no supported JDK)")
            continue
        code, output, elapsed = run_step(step, java_home)
        results.append((step, code, elapsed, output))
        marker = "ok  " if code == 0 else "FAIL"
        print(f"[{marker}] {step.name} ({elapsed:.1f}s): {step.description}")
        if code != 0:
            print(tail(output))

    # A skipped step is not a failure and a failure is not a skip: conflating the two either
    # hides a broken build or reports a misleading failure, so both are computed explicitly.
    skipped = [s.name for s, code, _, _ in results if code is None]
    failed = [s.name for s, code, _, _ in results if code is not None and code != 0]
    print()
    if failed:
        print(f"VERDICT: FAIL - {len(failed)} step(s) failed: {', '.join(failed)}")
        return 2
    if skipped:
        print(f"VERDICT: INCOMPLETE - skipped without a supported JDK: {', '.join(skipped)}")
        return 3
    print(f"VERDICT: PASS - {len(results)} step(s) green")
    return 0


if __name__ == "__main__":
    sys.exit(main())
