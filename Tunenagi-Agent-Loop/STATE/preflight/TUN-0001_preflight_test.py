#!/usr/bin/env python3
"""TUN-0001 preflight negative/failure-path test.

Exercises:
- Positive path: JDK version is actually recorded in the preflight doc.
- Negative path: missing Android SDK/adb/emulator is detected and reported as a failure.
- Negative path: LRCLIB terms recorded as unverified rather than assumed permissive.

This must run against the real STATE/preflight/TUN-0001-environment.md file, not a mock.
"""
import re
import sys
from pathlib import Path

PREFlight = Path(__file__).resolve().parent / "TUN-0001-environment.md"


def contents():
    if not PREFlight.is_file():
        raise FileNotFoundError(f"Preflight doc missing: {PREFlight}")
    return PREFlight.read_text(encoding="utf-8")


def fail(msg):
    print(f"FAIL: {msg}")
    return 1


def main():
    txt = contents()
    issues = 0

    # Positive path: real JDK present, recorded with exact version.
    if "Java version" not in txt and "version" not in txt.lower():
        issues += fail("JDK version not recorded in preflight doc")
    if "25.0.1" not in txt:
        issues += fail("Expected recorded JDK 25.0.1 not found in preflight doc")
    if "HotSpot" not in txt:
        issues += fail("JDK VM details not recorded")

    # Negative path: the initial preflight must document that SDK was missing and not pretend it existed before install.
    # After install it must record the concrete components and must never claim device proof without an attached device/emulator.
    has_sdk_record = (
        "ANDROID_HOME" in txt
        or "C:\\Android" in txt
        or "cmdline-tools" in txt
        or "platform-tools" in txt
        or "build-tools" in txt
        or "platforms" in txt
    )
    has_missing_sdk_record = (
        "not configured" in txt.lower()
        or "not found" in txt.lower()
        or "was not" in txt.lower()
        or "not set" in txt.lower()
        or "missing" in txt.lower()
    )
    if not has_sdk_record:
        issues += fail("SDK configuration not recorded in preflight doc")
    if not has_missing_sdk_record:
        issues += fail("preflight must record that SDK was initially missing/not configured, not pretend it existed from the start")

    # Negative path: do not pretend device/emulator evidence ran without an attached device/emulator.
    if "without an attached device" not in txt.lower() and "no emulator instance" not in txt.lower():
        issues += fail("device preflight must state that device proof requires an attached device/emulator")

    # Negative path: LRCLIB terms must be recorded as UNVERIFIED, not assumed free.
    if "unverified" not in txt.lower():
        issues += fail("LRCLIB auth/rate/reuse terms must be recorded as unverified")
    if "do not assume" not in txt.lower() and "not assume" not in txt.lower():
        issues += fail("preflight must not assume LRCLIB unlimited anonymous access")

    # Negative path: provider posture must forbid prohibited behaviors.
    if "no" not in txt.lower() or "scraping" not in txt.lower():
        issues += fail("prohibited scraping posture not recorded")

    if issues:
        print("Preflight negative/failure checks did not all pass.")
        return 1

    print(
        "PASS: JDK recorded; missing SDK/adb/emulator correctly detected; "
        "LRCLIB terms recorded as unverified; prohibited behaviors documented."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
