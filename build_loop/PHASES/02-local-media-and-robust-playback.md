# Phase 02 — Local media and robust playback

**Goal:** Actual rights-cleared local MP3 and FLAC play via Media3 with persistent queue and media controls on Android.

## Mandatory prerequisite

Prior phase must be fully verified and have a separate fresh-context `STATE/audits/phase-NN-pass.json` report. Use `python SCRIPTS/taskctl.py next` to check eligibility. Revisit official API docs whenever needed.

## Implementation slices

### TUN-0201 — Single Media3 service and controller

**Deliver:** One service-owned ExoPlayer and MediaLibrarySession, UI MediaController, manifest permissions and basic device play.

**Dependencies:** phase gate only. **Evidence:** command, device.

**Agent working protocol:** make the smallest real change that passes the acceptance; write a failure-path test, execute relevant tests via `run_check.py`, inspect Android UX if necessary, capture proof, update `tasks.json` via `taskctl.py`, commit, checkpoint. No mocks-only success for device or live source task.

### TUN-0202 — Device local audio discovery

**Deliver:** MediaStore scanning, SAF grants, revoke/unavailable handling, artwork and incremental indexing; device test.

**Dependencies:** TUN-0201. **Evidence:** command, device.

**Agent working protocol:** make the smallest real change that passes the acceptance; write a failure-path test, execute relevant tests via `run_check.py`, inspect Android UX if necessary, capture proof, update `tasks.json` via `taskctl.py`, commit, checkpoint. No mocks-only success for device or live source task.

### TUN-0203 — Local playable source resolver

**Deliver:** Resolve actual local MP3/FLAC and unsupported media via typed errors; instrument real playback.

**Dependencies:** TUN-0202. **Evidence:** command, device.

**Agent working protocol:** make the smallest real change that passes the acceptance; write a failure-path test, execute relevant tests via `run_check.py`, inspect Android UX if necessary, capture proof, update `tasks.json` via `taskctl.py`, commit, checkpoint. No mocks-only success for device or live source task.

### TUN-0204 — Queue commands and shuffle repeat

**Deliver:** Next/previous/seeking, add/remove/reorder, shuffle repeat, bound behavior; state transition tests/device demo.

**Dependencies:** TUN-0201, TUN-0203. **Evidence:** command, device.

**Agent working protocol:** make the smallest real change that passes the acceptance; write a failure-path test, execute relevant tests via `run_check.py`, inspect Android UX if necessary, capture proof, update `tasks.json` via `taskctl.py`, commit, checkpoint. No mocks-only success for device or live source task.

### TUN-0205 — Playback state restoration

**Deliver:** Persist queue/current position with debounced writes; relaunch restores without unwanted autoplay; device test.

**Dependencies:** TUN-0204. **Evidence:** command, device.

**Agent working protocol:** make the smallest real change that passes the acceptance; write a failure-path test, execute relevant tests via `run_check.py`, inspect Android UX if necessary, capture proof, update `tasks.json` via `taskctl.py`, commit, checkpoint. No mocks-only success for device or live source task.

### TUN-0206 — Media controls and audio focus

**Deliver:** Notification, lockscreen, BT/noisy behavior, focus loss/duck, service lifecycle; instrumented/device evidence.

**Dependencies:** TUN-0201, TUN-0204. **Evidence:** command, device.

**Agent working protocol:** make the smallest real change that passes the acceptance; write a failure-path test, execute relevant tests via `run_check.py`, inspect Android UX if necessary, capture proof, update `tasks.json` via `taskctl.py`, commit, checkpoint. No mocks-only success for device or live source task.

### TUN-0207 — Buffering/error/retry state

**Deliver:** Timeout/cancel/race/track missing/codec errors with clear states; retry bounds and tested concurrency.

**Dependencies:** TUN-0203, TUN-0204. **Evidence:** command.

**Agent working protocol:** make the smallest real change that passes the acceptance; write a failure-path test, execute relevant tests via `run_check.py`, inspect Android UX if necessary, capture proof, update `tasks.json` via `taskctl.py`, commit, checkpoint. No mocks-only success for device or live source task.

### TUN-0208 — Offline-first local smoke

**Deliver:** Actual offline playback of rights-cleared fixtures after airplane mode; screenshot & diagnostics.

**Dependencies:** TUN-0205, TUN-0206, TUN-0207. **Evidence:** command, device.

**Agent working protocol:** make the smallest real change that passes the acceptance; write a failure-path test, execute relevant tests via `run_check.py`, inspect Android UX if necessary, capture proof, update `tasks.json` via `taskctl.py`, commit, checkpoint. No mocks-only success for device or live source task.

## Phase completion audit

**Mandatory proof:** Install on emulator/device; seek, queue, shuffle, background, lockscreen, audio focus, headphone/noisy events; screen-off test + log.

**Common traps:** Player in Activity; no foreground service; fake `isPlaying`; autoplay surprises; audio glitches and leaked controllers.

Run `python SCRIPTS/taskctl.py gate 02` to check task metadata. Start a skeptical independent session using `PROMPTS/02_PHASE_AUDIT.md`. Write `STATE/audits/phase-02-YYYYMMDD-HHMM.md` with verdict and checks. Run `python SCRIPTS/taskctl.py audit 02 --report STATE/audits/<report>.md` after independent review. Inspect previous phase regressions. Do not create a PASS certificate merely because the metadata gate succeeds.
