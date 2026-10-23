#!/usr/bin/env python3
"""Gate the Android Lint reports.

Android Lint only fails a build on errors, so a *new warning* introduced by later work would
otherwise pass CI unnoticed - exactly how the `ModifierParameter` and missing-monochrome-icon
defects reached the tree at TUN-0004. This turns the documented baseline in
`OPERATIONS/GIT_CONVENTIONS.md` into an enforced one:

  L1  no lint errors at all
  L2  every warning ID is in the accepted baseline below, with its reason
  L3  the reports exist (a missing report is a failure, never a silent pass)

Accepted baseline:

* `AndroidGradlePluginVersion`, `GradleDependency` - "a newer version is available"
  advisories. Versions are pinned deliberately (ADR-001) and moved only on owner review, so
  the advisory is information, not a defect. Left visible on purpose.

Run `bash gradlew :app:lintDebug :core:lintDebug` first; `SCRIPTS/verify.py` does both in the
right order. `--self-test` proves each rule fires on deliberately bad report fixtures.
"""
import argparse
import sys
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

REPORTS = ("app/build/reports/lint-results-debug.xml", "core/build/reports/lint-results-debug.xml")
ACCEPTED_WARNING_IDS = {"AndroidGradlePluginVersion", "GradleDependency"}


def analyse(report_path):
    """Returns (errors, warnings) as lists of (id, message) for one report."""
    root = ET.parse(report_path).getroot()
    errors, warnings = [], []
    for issue in root:
        entry = (issue.get("id"), issue.get("message"))
        # Lint severity is explicit; anything that is not a warning is treated as blocking.
        if issue.get("severity") == "Warning":
            warnings.append(entry)
        else:
            errors.append(entry)
    return errors, warnings


def check(paths):
    problems = []
    checked = 0
    for name in paths:
        path = ROOT / name
        if not path.is_file():
            problems.append(f"L3 missing lint report {name}; run the lint task first")
            continue
        checked += 1
        errors, warnings = analyse(path)
        for issue_id, message in errors:
            problems.append(f"L1 {name}: {issue_id} error: {message}")
        for issue_id, message in warnings:
            if issue_id not in ACCEPTED_WARNING_IDS:
                problems.append(
                    f"L2 {name}: new warning class {issue_id}: {message} "
                    f"(fix it, or add it to the baseline in this script with a reason)"
                )
    if checked == 0:
        problems.append("L3 no lint report could be read")
    return checked, problems


def write_fixture(directory, name, body):
    path = Path(directory) / name
    path.write_text(f'<issues format="6" by="lint 8.7.3">{body}</issues>', encoding="utf8")
    return path


def self_test():
    """Each rule must fire on a report built to break it."""
    failures = []
    with tempfile.TemporaryDirectory(prefix="tunenagi-lint-") as tmp:
        cases = {
            "error.xml": ('<issue id="NewApi" severity="Error" message="boom"/>', "L1"),
            "regression.xml": ('<issue id="ModifierParameter" severity="Warning" message="order"/>', "L2"),
            "baseline.xml": ('<issue id="GradleDependency" severity="Warning" message="newer"/>', None),
        }
        paths = {}
        for name, (body, _) in cases.items():
            paths[name] = write_fixture(tmp, name, body)
        for name, (_, expected) in cases.items():
            local_errors, local_warnings = analyse(paths[name])
            ids = {entry[0] for entry in local_errors + local_warnings}
            if expected == "L1" and not local_errors:
                failures.append(f"{name} did not produce an error entry")
            if expected == "L2" and ids - ACCEPTED_WARNING_IDS == set():
                failures.append(f"{name} did not produce a non-baseline warning")
            if expected is None and (local_errors or ids - ACCEPTED_WARNING_IDS):
                failures.append(f"{name} should be accepted by the baseline")
        missing = check(["definitely/missing/lint-results.xml"])
        if not any(problem.startswith("L3") for problem in missing[1]):
            failures.append("a missing report was not reported as L3")

    if failures:
        for item in failures:
            print(f"SELF-TEST FAIL: {item}")
        return 1
    print("PASS: lint gate blocks errors, rejects warnings outside the documented baseline, "
          "accepts the baseline, and refuses to pass on a missing report.")
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--self-test", action="store_true", help="Verify the gate can fail.")
    args = parser.parse_args()

    if args.self_test:
        return self_test()

    checked, problems = check(REPORTS)
    for problem in problems:
        print(problem)
    if problems:
        print(f"FAIL: {len(problems)} lint gate problem(s) across {checked} report(s)")
        return 2
    print(f"PASS: {checked} lint report(s), 0 errors and no warning outside the baseline")
    return 0


if __name__ == "__main__":
    sys.exit(main())
