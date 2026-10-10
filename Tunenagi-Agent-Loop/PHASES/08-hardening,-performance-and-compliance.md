# Phase 08 — Hardening, performance and compliance

**Goal:** Systematic crash, stress, storage, performance and supply-chain tests pass and documentation is accurate.

## Mandatory prerequisite

Prior phase must be fully verified and have a separate fresh-context `STATE/audits/phase-NN-pass.json` report. Use `python SCRIPTS/taskctl.py next` to check eligibility. Revisit official API docs whenever needed.

## Implementation slices

### TUN-0801 — Error and chaos regression suite

**Deliver:** Test network outage, rapid controls, permission revoke, media deletion, malformed data and cancellation.

**Dependencies:** phase gate only. **Evidence:** command, device.

**Agent working protocol:** make the smallest real change that passes the acceptance; write a failure-path test, execute relevant tests via `run_check.py`, inspect Android UX if necessary, capture proof, update `tasks.json` via `taskctl.py`, commit, checkpoint. No mocks-only success for device or live source task.

### TUN-0802 — Room migrations and large libraries

**Deliver:** Database upgrades + seeded realistic 10k-tracks pagination/memory tests, no data loss.

**Dependencies:** TUN-0801. **Evidence:** command.

**Agent working protocol:** make the smallest real change that passes the acceptance; write a failure-path test, execute relevant tests via `run_check.py`, inspect Android UX if necessary, capture proof, update `tasks.json` via `taskctl.py`, commit, checkpoint. No mocks-only success for device or live source task.

### TUN-0803 — Media background OEM matrix

**Deliver:** Service/audio focus/BT/screen lock across available APIs/OEMs, document untestable vendor behavior.

**Dependencies:** TUN-0801. **Evidence:** command, device.

**Agent working protocol:** make the smallest real change that passes the acceptance; write a failure-path test, execute relevant tests via `run_check.py`, inspect Android UX if necessary, capture proof, update `tasks.json` via `taskctl.py`, commit, checkpoint. No mocks-only success for device or live source task.

### TUN-0804 — Benchmark startup, scroll, memory

**Deliver:** Macrobenchmark release-like baseline, compare regressions; bounded caches and ANR checks.

**Dependencies:** TUN-0802. **Evidence:** command, device.

**Agent working protocol:** make the smallest real change that passes the acceptance; write a failure-path test, execute relevant tests via `run_check.py`, inspect Android UX if necessary, capture proof, update `tasks.json` via `taskctl.py`, commit, checkpoint. No mocks-only success for device or live source task.

### TUN-0805 — Security privacy and license review

**Deliver:** Threat model, secret scan, SBOM/license NOTICE, clear disclosure and remote access policy audit.

**Dependencies:** TUN-0801. **Evidence:** command.

**Agent working protocol:** make the smallest real change that passes the acceptance; write a failure-path test, execute relevant tests via `run_check.py`, inspect Android UX if necessary, capture proof, update `tasks.json` via `taskctl.py`, commit, checkpoint. No mocks-only success for device or live source task.

### TUN-0806 — Dependency CVEs and CI hardening

**Deliver:** Pinned reproducible versions, CI build/lint/unit/instrumented where environment supports, vulnerability check.

**Dependencies:** TUN-0804, TUN-0805. **Evidence:** command.

**Agent working protocol:** make the smallest real change that passes the acceptance; write a failure-path test, execute relevant tests via `run_check.py`, inspect Android UX if necessary, capture proof, update `tasks.json` via `taskctl.py`, commit, checkpoint. No mocks-only success for device or live source task.

### TUN-0807 — Cross-phase regression and bug bash

**Deliver:** Run full test matrix and manually challenge all flows; close critical/high and document mediums.

**Dependencies:** TUN-0803, TUN-0804, TUN-0806. **Evidence:** command, device.

**Agent working protocol:** make the smallest real change that passes the acceptance; write a failure-path test, execute relevant tests via `run_check.py`, inspect Android UX if necessary, capture proof, update `tasks.json` via `taskctl.py`, commit, checkpoint. No mocks-only success for device or live source task.

### TUN-0808 — Comprehensive skeptical audit

**Deliver:** Independent phase and cross-phase skeptical review with source evidence and regression confirmation.

**Dependencies:** TUN-0805, TUN-0807. **Evidence:** command.

**Agent working protocol:** make the smallest real change that passes the acceptance; write a failure-path test, execute relevant tests via `run_check.py`, inspect Android UX if necessary, capture proof, update `tasks.json` via `taskctl.py`, commit, checkpoint. No mocks-only success for device or live source task.

## Phase completion audit

**Mandatory proof:** Regression test suite, Macrobenchmark captures, adverse device conditions, license/privacy audit, CI full green.

**Common traps:** Skipping real device tests, unsafe unbounded caching, non-reproducible builds, undeclared remote network use.

Run `python SCRIPTS/taskctl.py gate 08` to check task metadata. Start a skeptical independent session using `PROMPTS/02_PHASE_AUDIT.md`. Write `STATE/audits/phase-08-YYYYMMDD-HHMM.md` with verdict and checks. Run `python SCRIPTS/taskctl.py audit 08 --report STATE/audits/<report>.md` after independent review. Inspect previous phase regressions. Do not create a PASS certificate merely because the metadata gate succeeds.
