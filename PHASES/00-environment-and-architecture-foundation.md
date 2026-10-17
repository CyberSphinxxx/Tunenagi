# Phase 00 — Environment and architecture foundation

**Goal:** A clean native Android app builds, launches, and has reproducible verification commands.

## Mandatory prerequisite

Prior phase must be fully verified and have a separate fresh-context `STATE/audits/phase-NN-pass.json` report. Use `python SCRIPTS/taskctl.py next` to check eligibility. Revisit official API docs whenever needed.

## Implementation slices

### TUN-0001 — Preflight SDK and provider policies

**Deliver:** Record actual JDK, Android SDK/adb, emulator availability, official version citations and legal API terms; write environment preflight.

**Dependencies:** phase gate only. **Evidence:** command.

**Agent working protocol:** make the smallest real change that passes the acceptance; write a failure-path test, execute relevant tests via `run_check.py`, inspect Android UX if necessary, capture proof, update `tasks.json` via `taskctl.py`, commit, checkpoint. No mocks-only success for device or live source task.

### TUN-0002 — Initialize Gradle Kotlin project

**Deliver:** Create executable Android app, wrapper, AGP/Kotlin/Compose version catalog and Hilt setup; assembleDebug completes.

**Dependencies:** TUN-0001. **Evidence:** command.

**Agent working protocol:** make the smallest real change that passes the acceptance; write a failure-path test, execute relevant tests via `run_check.py`, inspect Android UX if necessary, capture proof, update `tasks.json` via `taskctl.py`, commit, checkpoint. No mocks-only success for device or live source task.

### TUN-0003 — Design tokens and navigation shell

**Deliver:** Three-tab Compose shell, player host navigation and responsive scaffold with empty states; install & launch on Android.

**Dependencies:** TUN-0002. **Evidence:** command, device.

**Agent working protocol:** make the smallest real change that passes the acceptance; write a failure-path test, execute relevant tests via `run_check.py`, inspect Android UX if necessary, capture proof, update `tasks.json` via `taskctl.py`, commit, checkpoint. No mocks-only success for device or live source task.

### TUN-0004 — Repository hygiene and licenses

**Deliver:** Git conventions, strict .gitignore, base GPL license proposal, notices plan, formatting/static checks and no secrets.

**Dependencies:** TUN-0002. **Evidence:** command.

**Agent working protocol:** make the smallest real change that passes the acceptance; write a failure-path test, execute relevant tests via `run_check.py`, inspect Android UX if necessary, capture proof, update `tasks.json` via `taskctl.py`, commit, checkpoint. No mocks-only success for device or live source task.

### TUN-0005 — CI build and local verification commands

**Deliver:** CI build/lint/unit jobs work, local scripts/docs reproducible, no remote source smoke spam.

**Dependencies:** TUN-0002, TUN-0004. **Evidence:** command.

**Agent working protocol:** make the smallest real change that passes the acceptance; write a failure-path test, execute relevant tests via `run_check.py`, inspect Android UX if necessary, capture proof, update `tasks.json` via `taskctl.py`, commit, checkpoint. No mocks-only success for device or live source task.

### TUN-0006 — Foundational tests and baseline device record

**Deliver:** Run first unit, lint, assemble and emulator smoke, record screenshot/adb API; solve failures.

**Dependencies:** TUN-0003, TUN-0005. **Evidence:** command, device.

**Agent working protocol:** make the smallest real change that passes the acceptance; write a failure-path test, execute relevant tests via `run_check.py`, inspect Android UX if necessary, capture proof, update `tasks.json` via `taskctl.py`, commit, checkpoint. No mocks-only success for device or live source task.

## Phase completion audit

**Mandatory proof:** Verify SDK/JDK/AGP/Compose/Media3 official compatibility, no fake pinned versions, launch on actual emulator, basic CI, clean tracked project.

**Common traps:** No emulator or Android SDK; plugin versions that do not resolve; auto-generated unsigned APK mistaken for release.

Run `python SCRIPTS/taskctl.py gate 00` to check task metadata. Start a skeptical independent session using `PROMPTS/02_PHASE_AUDIT.md`. Write `STATE/audits/phase-00-YYYYMMDD-HHMM.md` with verdict and checks. Run `python SCRIPTS/taskctl.py audit 00 --report STATE/audits/<report>.md` after independent review. Inspect previous phase regressions. Do not create a PASS certificate merely because the metadata gate succeeds.
