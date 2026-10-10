# Phase 03 — Audius public online streaming

**Goal:** Users can discover and play an accessible real Audius track, and restricted/unavailable tracks fail honestly.

## Mandatory prerequisite

Prior phase must be fully verified and have a separate fresh-context `STATE/audits/phase-NN-pass.json` report. Use `python SCRIPTS/taskctl.py next` to check eligibility. Revisit official API docs whenever needed.

## Implementation slices

### TUN-0301 — Audius HTTP client and discovery DTOs

**Deliver:** Implement documented search/trending with pagination, UA/auth per docs; recorded mock contract tests.

**Dependencies:** phase gate only. **Evidence:** command.

**Agent working protocol:** make the smallest real change that passes the acceptance; write a failure-path test, execute relevant tests via `run_check.py`, inspect Android UX if necessary, capture proof, update `tasks.json` via `taskctl.py`, commit, checkpoint. No mocks-only success for device or live source task.

### TUN-0302 — Track normalization and entitlement

**Deliver:** Source-neutral mapping, creator/API restrictions, deleted/premium states, attribution; tests.

**Dependencies:** TUN-0301. **Evidence:** command.

**Agent working protocol:** make the smallest real change that passes the acceptance; write a failure-path test, execute relevant tests via `run_check.py`, inspect Android UX if necessary, capture proof, update `tasks.json` via `taskctl.py`, commit, checkpoint. No mocks-only success for device or live source task.

### TUN-0303 — Public stream resolver

**Deliver:** Stream accessible Audius track with late URL resolution, expiry handling and no bypass; MockWebServer tests.

**Dependencies:** TUN-0301, TUN-0302. **Evidence:** command.

**Agent working protocol:** make the smallest real change that passes the acceptance; write a failure-path test, execute relevant tests via `run_check.py`, inspect Android UX if necessary, capture proof, update `tasks.json` via `taskctl.py`, commit, checkpoint. No mocks-only success for device or live source task.

### TUN-0304 — Provider rate/failure recovery

**Deliver:** 429 Retry-After, 403 restriction, 5xx, offline, timeout and cancellation; bounded retry tests.

**Dependencies:** TUN-0301. **Evidence:** command.

**Agent working protocol:** make the smallest real change that passes the acceptance; write a failure-path test, execute relevant tests via `run_check.py`, inspect Android UX if necessary, capture proof, update `tasks.json` via `taskctl.py`, commit, checkpoint. No mocks-only success for device or live source task.

### TUN-0305 — Remote search/discovery UI

**Deliver:** Playable real Search/Home Audius results, loading empty permission/network errors; device UI test.

**Dependencies:** TUN-0302, TUN-0304. **Evidence:** command, device.

**Agent working protocol:** make the smallest real change that passes the acceptance; write a failure-path test, execute relevant tests via `run_check.py`, inspect Android UX if necessary, capture proof, update `tasks.json` via `taskctl.py`, commit, checkpoint. No mocks-only success for device or live source task.

### TUN-0306 — Real Audius live playback smoke

**Deliver:** On connected Android runtime search + play a currently permitted public track; log actual status/serial.

**Dependencies:** TUN-0303, TUN-0305. **Evidence:** command, device, live.

**Agent working protocol:** make the smallest real change that passes the acceptance; write a failure-path test, execute relevant tests via `run_check.py`, inspect Android UX if necessary, capture proof, update `tasks.json` via `taskctl.py`, commit, checkpoint. No mocks-only success for device or live source task.

### TUN-0307 — Source inspector and manual preference

**Deliver:** Source identity/status/format where known; source override locked and persists, unavailable variant feedback.

**Dependencies:** TUN-0302, TUN-0306. **Evidence:** command, device.

**Agent working protocol:** make the smallest real change that passes the acceptance; write a failure-path test, execute relevant tests via `run_check.py`, inspect Android UX if necessary, capture proof, update `tasks.json` via `taskctl.py`, commit, checkpoint. No mocks-only success for device or live source task.

## Phase completion audit

**Mandatory proof:** MockWebServer contract tests plus one rate-respecting real API/device stream proof; negative HTTP/path tests; no unauthorized extraction.

**Common traps:** Using undocumented routes, mixing temporary stream URL into canonical DB ID, abusing provider limits.

Run `python SCRIPTS/taskctl.py gate 03` to check task metadata. Start a skeptical independent session using `PROMPTS/02_PHASE_AUDIT.md`. Write `STATE/audits/phase-03-YYYYMMDD-HHMM.md` with verdict and checks. Run `python SCRIPTS/taskctl.py audit 03 --report STATE/audits/<report>.md` after independent review. Inspect previous phase regressions. Do not create a PASS certificate merely because the metadata gate succeeds.
