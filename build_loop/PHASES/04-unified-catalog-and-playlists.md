# Phase 04 — Unified catalog and playlists

**Goal:** Local and Audius tracks coexist in durable favorites, searches, playlists and exports.

## Mandatory prerequisite

Prior phase must be fully verified and have a separate fresh-context `STATE/audits/phase-NN-pass.json` report. Use `python SCRIPTS/taskctl.py next` to check eligibility. Revisit official API docs whenever needed.

## Implementation slices

### TUN-0401 — Mixed library and reactive queries

**Deliver:** Merge local+Audius catalog by canonical Track and TrackSource, with paged lists and tests.

**Dependencies:** phase gate only. **Evidence:** command.

**Agent working protocol:** make the smallest real change that passes the acceptance; write a failure-path test, execute relevant tests via `run_check.py`, inspect Android UX if necessary, capture proof, update `tasks.json` via `taskctl.py`, commit, checkpoint. No mocks-only success for device or live source task.

### TUN-0402 — Favorites and recently played

**Deliver:** User can favorite local/online, history records threshold, persists; UI test.

**Dependencies:** TUN-0401. **Evidence:** command, device.

**Agent working protocol:** make the smallest real change that passes the acceptance; write a failure-path test, execute relevant tests via `run_check.py`, inspect Android UX if necessary, capture proof, update `tasks.json` via `taskctl.py`, commit, checkpoint. No mocks-only success for device or live source task.

### TUN-0403 — Playlist create edit and reorder

**Deliver:** User can create/rename/delete playlist, add duplicate tracks, drag reorder without data loss; device test.

**Dependencies:** TUN-0401. **Evidence:** command, device.

**Agent working protocol:** make the smallest real change that passes the acceptance; write a failure-path test, execute relevant tests via `run_check.py`, inspect Android UX if necessary, capture proof, update `tasks.json` via `taskctl.py`, commit, checkpoint. No mocks-only success for device or live source task.

### TUN-0404 — Mixed online offline playlist behavior

**Deliver:** Offline remote items remain visible/unavailable, local items work, source loss no data destruction.

**Dependencies:** TUN-0403. **Evidence:** command, device.

**Agent working protocol:** make the smallest real change that passes the acceptance; write a failure-path test, execute relevant tests via `run_check.py`, inspect Android UX if necessary, capture proof, update `tasks.json` via `taskctl.py`, commit, checkpoint. No mocks-only success for device or live source task.

### TUN-0405 — Search across local and Audius

**Deliver:** Debounce/cancellation, filters/pagination, no cross-source ID collision, keyboard UX tests.

**Dependencies:** TUN-0401. **Evidence:** command, device.

**Agent working protocol:** make the smallest real change that passes the acceptance; write a failure-path test, execute relevant tests via `run_check.py`, inspect Android UX if necessary, capture proof, update `tasks.json` via `taskctl.py`, commit, checkpoint. No mocks-only success for device or live source task.

### TUN-0406 — Library JSON backup and restore

**Deliver:** Versioned schema, bounds/validation, no private paths/tokens, transactional round-trip tests.

**Dependencies:** TUN-0402, TUN-0403. **Evidence:** command.

**Agent working protocol:** make the smallest real change that passes the acceptance; write a failure-path test, execute relevant tests via `run_check.py`, inspect Android UX if necessary, capture proof, update `tasks.json` via `taskctl.py`, commit, checkpoint. No mocks-only success for device or live source task.

### TUN-0407 — Library relaunch and migration E2E

**Deliver:** Install populated app, restart and verify favorites, mixed playlist, queue, backup restore; full device proof.

**Dependencies:** TUN-0404, TUN-0405, TUN-0406. **Evidence:** command, device.

**Agent working protocol:** make the smallest real change that passes the acceptance; write a failure-path test, execute relevant tests via `run_check.py`, inspect Android UX if necessary, capture proof, update `tasks.json` via `taskctl.py`, commit, checkpoint. No mocks-only success for device or live source task.

## Phase completion audit

**Mandatory proof:** Room transaction/migration tests, mixed playlist round-trip and offline/restart UI demonstration.

**Common traps:** Duplicate/stale data, playlist corruption after source removal, import ZIP/JSON abuse, broken source preference.

Run `python SCRIPTS/taskctl.py gate 04` to check task metadata. Start a skeptical independent session using `PROMPTS/02_PHASE_AUDIT.md`. Write `STATE/audits/phase-04-YYYYMMDD-HHMM.md` with verdict and checks. Run `python SCRIPTS/taskctl.py audit 04 --report STATE/audits/<report>.md` after independent review. Inspect previous phase regressions. Do not create a PASS certificate merely because the metadata gate succeeds.
