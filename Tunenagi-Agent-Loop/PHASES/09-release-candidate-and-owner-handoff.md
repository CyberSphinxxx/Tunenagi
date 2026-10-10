# Phase 09 — Release candidate and owner handoff

**Goal:** A reproducible verified installable candidate, checksums, release docs and final independent audit are ready.

## Mandatory prerequisite

Prior phase must be fully verified and have a separate fresh-context `STATE/audits/phase-NN-pass.json` report. Use `python SCRIPTS/taskctl.py next` to check eligibility. Revisit official API docs whenever needed.

## Implementation slices

### TUN-0901 — Release build and signing plan

**Deliver:** Build release candidate; owner key protected/no committed secrets; debug signed QA APK if public key not provided.

**Dependencies:** phase gate only. **Evidence:** command.

**Agent working protocol:** make the smallest real change that passes the acceptance; write a failure-path test, execute relevant tests via `run_check.py`, inspect Android UX if necessary, capture proof, update `tasks.json` via `taskctl.py`, commit, checkpoint. No mocks-only success for device or live source task.

### TUN-0902 — Fresh install and upgrade verification

**Deliver:** Install APK with adb, launch/replay local and online, test app data migration from prior version.

**Dependencies:** TUN-0901. **Evidence:** command, device.

**Agent working protocol:** make the smallest real change that passes the acceptance; write a failure-path test, execute relevant tests via `run_check.py`, inspect Android UX if necessary, capture proof, update `tasks.json` via `taskctl.py`, commit, checkpoint. No mocks-only success for device or live source task.

### TUN-0903 — End-to-end owner demo walkthrough

**Deliver:** Record actual v1 journeys local/Audius/mixed playlist/lyrics/plugin/offline/background on Android.

**Dependencies:** TUN-0902. **Evidence:** command, device, live.

**Agent working protocol:** make the smallest real change that passes the acceptance; write a failure-path test, execute relevant tests via `run_check.py`, inspect Android UX if necessary, capture proof, update `tasks.json` via `taskctl.py`, commit, checkpoint. No mocks-only success for device or live source task.

### TUN-0904 — Release docs, license and checksums

**Deliver:** Prepare README, install, privacy/license NOTICE, known limits, changelog, SHA256 and artifact paths.

**Dependencies:** TUN-0901. **Evidence:** command.

**Agent working protocol:** make the smallest real change that passes the acceptance; write a failure-path test, execute relevant tests via `run_check.py`, inspect Android UX if necessary, capture proof, update `tasks.json` via `taskctl.py`, commit, checkpoint. No mocks-only success for device or live source task.

### TUN-0905 — Final security regression + performance

**Deliver:** Re-run full suite, critical security tests and benchmark on final SHA; collect independent evidence.

**Dependencies:** TUN-0903, TUN-0904. **Evidence:** command, device.

**Agent working protocol:** make the smallest real change that passes the acceptance; write a failure-path test, execute relevant tests via `run_check.py`, inspect Android UX if necessary, capture proof, update `tasks.json` via `taskctl.py`, commit, checkpoint. No mocks-only success for device or live source task.

### TUN-0906 — Final independent release audit

**Deliver:** Run release reviewer; READY FOR OWNER APPROVAL only if verified gates and signed/installable QA candidate.

**Dependencies:** TUN-0905. **Evidence:** command, device.

**Agent working protocol:** make the smallest real change that passes the acceptance; write a failure-path test, execute relevant tests via `run_check.py`, inspect Android UX if necessary, capture proof, update `tasks.json` via `taskctl.py`, commit, checkpoint. No mocks-only success for device or live source task.

## Phase completion audit

**Mandatory proof:** Fresh install + upgrade/restore proof, device playback of local/Audius, sample plugin, artifact checksum/signature audit, final checklist.

**Common traps:** No owner release key; public publish without approval; fake APK path or unchecked checksum.

Run `python SCRIPTS/taskctl.py gate 09` to check task metadata. Start a skeptical independent session using `PROMPTS/02_PHASE_AUDIT.md`. Write `STATE/audits/phase-09-YYYYMMDD-HHMM.md` with verdict and checks. Run `python SCRIPTS/taskctl.py audit 09 --report STATE/audits/<report>.md` after independent review. Inspect previous phase regressions. Do not create a PASS certificate merely because the metadata gate succeeds.
