#!/usr/bin/env sh
# Safe preflight; no privileged installations or agent invocations.
set -u
for x in python3 git java adb; do if command -v "$x" >/dev/null 2>&1; then echo "FOUND $x"; else echo "MISSING $x"; fi; done
python3 SCRIPTS/validate_package.py
python3 SCRIPTS/taskctl.py status
printf '%s\n' 'Read START_HERE.md and PROMPTS/00_START_SOLAR_PRO_4.md.'
