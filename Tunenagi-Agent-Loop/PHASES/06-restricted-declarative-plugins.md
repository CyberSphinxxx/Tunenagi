# Phase 06 — Restricted declarative plugins

**Goal:** A user can approve/install/disable/uninstall an external declarative source; host enforces security.

## Mandatory prerequisite

Prior phase must be fully verified and have a separate fresh-context `STATE/audits/phase-NN-pass.json` report. Use `python SCRIPTS/taskctl.py next` to check eligibility. Revisit official API docs whenever needed.

## Implementation slices

### TUN-0601 — Plugin manifest schema and validator

**Deliver:** Fixed supported schema, apiVersion, domains, capabilities, parsers and hostile payload tests.

**Dependencies:** phase gate only. **Evidence:** command.

**Agent working protocol:** make the smallest real change that passes the acceptance; write a failure-path test, execute relevant tests via `run_check.py`, inspect Android UX if necessary, capture proof, update `tasks.json` via `taskctl.py`, commit, checkpoint. No mocks-only success for device or live source task.

### TUN-0602 — Host network gateway SSRF protections

**Deliver:** IP/redirect/DNS allowlist, HTTP response/time/quota caps; fake resolver tests incl IPv6/rebinding.

**Dependencies:** TUN-0601. **Evidence:** command.

**Agent working protocol:** make the smallest real change that passes the acceptance; write a failure-path test, execute relevant tests via `run_check.py`, inspect Android UX if necessary, capture proof, update `tasks.json` via `taskctl.py`, commit, checkpoint. No mocks-only success for device or live source task.

### TUN-0603 — Declarative HTTP JSON execution

**Deliver:** Fixed GET operations + limited mapping; no eval/scripts, rate/error paths and contract tests.

**Dependencies:** TUN-0601, TUN-0602. **Evidence:** command.

**Agent working protocol:** make the smallest real change that passes the acceptance; write a failure-path test, execute relevant tests via `run_check.py`, inspect Android UX if necessary, capture proof, update `tasks.json` via `taskctl.py`, commit, checkpoint. No mocks-only success for device or live source task.

### TUN-0604 — Plugin installation UX and storage

**Deliver:** File/HTTPS manifest import, explicit approval, permission expansion, enable/disable/uninstall safe UI.

**Dependencies:** TUN-0601, TUN-0603. **Evidence:** command, device.

**Agent working protocol:** make the smallest real change that passes the acceptance; write a failure-path test, execute relevant tests via `run_check.py`, inspect Android UX if necessary, capture proof, update `tasks.json` via `taskctl.py`, commit, checkpoint. No mocks-only success for device or live source task.

### TUN-0605 — External sample plugin E2E

**Deliver:** External manifest installed via UI, search and play controlled rights-cleared test fixture on Android.

**Dependencies:** TUN-0603, TUN-0604. **Evidence:** command, device.

**Agent working protocol:** make the smallest real change that passes the acceptance; write a failure-path test, execute relevant tests via `run_check.py`, inspect Android UX if necessary, capture proof, update `tasks.json` via `taskctl.py`, commit, checkpoint. No mocks-only success for device or live source task.

### TUN-0606 — Plugin resilience and recovery

**Deliver:** Invalid/disabled/uninstalled plugin does not corrupt queue/library or crash app, lifecycle tests.

**Dependencies:** TUN-0604, TUN-0605. **Evidence:** command, device.

**Agent working protocol:** make the smallest real change that passes the acceptance; write a failure-path test, execute relevant tests via `run_check.py`, inspect Android UX if necessary, capture proof, update `tasks.json` via `taskctl.py`, commit, checkpoint. No mocks-only success for device or live source task.

### TUN-0607 — Independent adversarial security audit

**Deliver:** Run hostile plugin checks, report true results, close Critical/High before phase audit.

**Dependencies:** TUN-0602, TUN-0605, TUN-0606. **Evidence:** command.

**Agent working protocol:** make the smallest real change that passes the acceptance; write a failure-path test, execute relevant tests via `run_check.py`, inspect Android UX if necessary, capture proof, update `tasks.json` via `taskctl.py`, commit, checkpoint. No mocks-only success for device or live source task.

## Phase completion audit

**Mandatory proof:** Controlled fixture streaming through installed plugin on device, forbidden hosts/redirects/input fuzz, independent hostile plugin audit.

**Common traps:** Script execution, SSRF/DNS rebinding, trust badge without verification, plugin crashes app.

Run `python SCRIPTS/taskctl.py gate 06` to check task metadata. Start a skeptical independent session using `PROMPTS/02_PHASE_AUDIT.md`. Write `STATE/audits/phase-06-YYYYMMDD-HHMM.md` with verdict and checks. Run `python SCRIPTS/taskctl.py audit 06 --report STATE/audits/<report>.md` after independent review. Inspect previous phase regressions. Do not create a PASS certificate merely because the metadata gate succeeds.
