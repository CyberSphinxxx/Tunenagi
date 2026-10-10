# Phase 05 — Lyrics and metadata enrichment

**Goal:** Local LRC/LRCLIB synced lyrics and bounded MusicBrainz enrichment work without disrupting audio.

## Mandatory prerequisite

Prior phase must be fully verified and have a separate fresh-context `STATE/audits/phase-NN-pass.json` report. Use `python SCRIPTS/taskctl.py next` to check eligibility. Revisit official API docs whenever needed.

## Implementation slices

### TUN-0501 — Local LRC parser and sidecar import

**Deliver:** Robust timestamp, Unicode, offset, malformed and non-synced cases; seek map correctness tests.

**Dependencies:** phase gate only. **Evidence:** command.

**Agent working protocol:** make the smallest real change that passes the acceptance; write a failure-path test, execute relevant tests via `run_check.py`, inspect Android UX if necessary, capture proof, update `tasks.json` via `taskctl.py`, commit, checkpoint. No mocks-only success for device or live source task.

### TUN-0502 — LRCLIB HTTP with throttling

**Deliver:** Real docs, UA, search scoring, retry-after, bounded caching; mock tests for 429/non-JSON.

**Dependencies:** TUN-0501. **Evidence:** command.

**Agent working protocol:** make the smallest real change that passes the acceptance; write a failure-path test, execute relevant tests via `run_check.py`, inspect Android UX if necessary, capture proof, update `tasks.json` via `taskctl.py`, commit, checkpoint. No mocks-only success for device or live source task.

### TUN-0503 — Lyrics UI with seek and fallback

**Deliver:** Highlight currently playing text; tapping line seeks; plain/missing states; device screenshots.

**Dependencies:** TUN-0501, TUN-0502. **Evidence:** command, device.

**Agent working protocol:** make the smallest real change that passes the acceptance; write a failure-path test, execute relevant tests via `run_check.py`, inspect Android UX if necessary, capture proof, update `tasks.json` via `taskctl.py`, commit, checkpoint. No mocks-only success for device or live source task.

### TUN-0504 — MusicBrainz rate-limited enrichment

**Deliver:** Identifying User-Agent, 1req/sec rate limit, cache, conservative scoring and ambiguity protections.

**Dependencies:** phase gate only. **Evidence:** command.

**Agent working protocol:** make the smallest real change that passes the acceptance; write a failure-path test, execute relevant tests via `run_check.py`, inspect Android UX if necessary, capture proof, update `tasks.json` via `taskctl.py`, commit, checkpoint. No mocks-only success for device or live source task.

### TUN-0505 — Artwork fallback and size controls

**Deliver:** Artwork priority, downsampling, safe URLs, placeholder; OOM/format tests.

**Dependencies:** TUN-0504. **Evidence:** command, device.

**Agent working protocol:** make the smallest real change that passes the acceptance; write a failure-path test, execute relevant tests via `run_check.py`, inspect Android UX if necessary, capture proof, update `tasks.json` via `taskctl.py`, commit, checkpoint. No mocks-only success for device or live source task.

### TUN-0506 — Real lyrics/metadata smoke

**Deliver:** Rate-respecting live calls, source credits, offline lyric cache plus app regression device proof.

**Dependencies:** TUN-0503, TUN-0504, TUN-0505. **Evidence:** command, device, live.

**Agent working protocol:** make the smallest real change that passes the acceptance; write a failure-path test, execute relevant tests via `run_check.py`, inspect Android UX if necessary, capture proof, update `tasks.json` via `taskctl.py`, commit, checkpoint. No mocks-only success for device or live source task.

## Phase completion audit

**Mandatory proof:** LRC parser fuzz/edge tests, rate-limit and Retry-After HTTP tests, real smoke sample, manual lyrics seek screenshot.

**Common traps:** Incorrect lyric matches, title-based wrong recording merges, uncontrolled background requests.

Run `python SCRIPTS/taskctl.py gate 05` to check task metadata. Start a skeptical independent session using `PROMPTS/02_PHASE_AUDIT.md`. Write `STATE/audits/phase-05-YYYYMMDD-HHMM.md` with verdict and checks. Run `python SCRIPTS/taskctl.py audit 05 --report STATE/audits/<report>.md` after independent review. Inspect previous phase regressions. Do not create a PASS certificate merely because the metadata gate succeeds.
