# Phase 01 — Domain and persistent storage

**Goal:** A stable source-neutral typed domain and Room persistence exist, migration and backup-safe IDs proven.

## Mandatory prerequisite

Prior phase must be fully verified and have a separate fresh-context `STATE/audits/phase-NN-pass.json` report. Use `python SCRIPTS/taskctl.py next` to check eligibility. Revisit official API docs whenever needed.

## Implementation slices

### TUN-0101 — Canonical Track and provider domain

**Deliver:** Define stable IDs, source entity, typed provider errors, queue enums and source-independent domain models with tests.

**Dependencies:** phase gate only. **Evidence:** command.

**Agent working protocol:** make the smallest real change that passes the acceptance; write a failure-path test, execute relevant tests via `run_check.py`, inspect Android UX if necessary, capture proof, update `tasks.json` via `taskctl.py`, commit, checkpoint. No mocks-only success for device or live source task.

### TUN-0102 — Room entities DAO and indices

**Deliver:** Implement normalized Track/Artist/Album/Source/Playlist/Favorite/History/Queue/Lyrics/Plugin tables with indices and FK; DAO tests.

**Dependencies:** TUN-0101. **Evidence:** command.

**Agent working protocol:** make the smallest real change that passes the acceptance; write a failure-path test, execute relevant tests via `run_check.py`, inspect Android UX if necessary, capture proof, update `tasks.json` via `taskctl.py`, commit, checkpoint. No mocks-only success for device or live source task.

### TUN-0103 — Migration strategy and persistence tests

**Deliver:** Explicit Room migration, no destructive fallbacks, migrate seed old-schema database without user data loss.

**Dependencies:** TUN-0102. **Evidence:** command.

**Agent working protocol:** make the smallest real change that passes the acceptance; write a failure-path test, execute relevant tests via `run_check.py`, inspect Android UX if necessary, capture proof, update `tasks.json` via `taskctl.py`, commit, checkpoint. No mocks-only success for device or live source task.

### TUN-0104 — Source matching and manual locks

**Deliver:** Implement conservative scoring + confirmed override, duplicate editions remain separate; deterministic unit tests.

**Dependencies:** TUN-0101, TUN-0102. **Evidence:** command.

**Agent working protocol:** make the smallest real change that passes the acceptance; write a failure-path test, execute relevant tests via `run_check.py`, inspect Android UX if necessary, capture proof, update `tasks.json` via `taskctl.py`, commit, checkpoint. No mocks-only success for device or live source task.

### TUN-0105 — Queue data repository

**Deliver:** Queue ordering, duplicate queue items, repeat/shuffle persistence, state restore transaction semantics with tests.

**Dependencies:** TUN-0102. **Evidence:** command.

**Agent working protocol:** make the smallest real change that passes the acceptance; write a failure-path test, execute relevant tests via `run_check.py`, inspect Android UX if necessary, capture proof, update `tasks.json` via `taskctl.py`, commit, checkpoint. No mocks-only success for device or live source task.

### TUN-0106 — Playlist/favorite/history repositories

**Deliver:** Durable add/remove/reorder/rename, favorites, history threshold; concurrency and rollback tests.

**Dependencies:** TUN-0102. **Evidence:** command.

**Agent working protocol:** make the smallest real change that passes the acceptance; write a failure-path test, execute relevant tests via `run_check.py`, inspect Android UX if necessary, capture proof, update `tasks.json` via `taskctl.py`, commit, checkpoint. No mocks-only success for device or live source task.

### TUN-0107 — Dependency boundary static checks

**Deliver:** Document dependency graph and enforce package/module direction with automated check; clean build.

**Dependencies:** TUN-0103, TUN-0104, TUN-0105, TUN-0106. **Evidence:** command.

**Agent working protocol:** make the smallest real change that passes the acceptance; write a failure-path test, execute relevant tests via `run_check.py`, inspect Android UX if necessary, capture proof, update `tasks.json` via `taskctl.py`, commit, checkpoint. No mocks-only success for device or live source task.

## Phase completion audit

**Mandatory proof:** Run Room database/migration tests, queue and playlist DAO transactions, foreign key constraints, ID stability and regression build.

**Common traps:** Provider ID used as PK; accidental deletion after source loss; migration only from empty install.

Run `python SCRIPTS/taskctl.py gate 01` to check task metadata. Start a skeptical independent session using `PROMPTS/02_PHASE_AUDIT.md`. Write `STATE/audits/phase-01-YYYYMMDD-HHMM.md` with verdict and checks. Run `python SCRIPTS/taskctl.py audit 01 --report STATE/audits/<report>.md` after independent review. Inspect previous phase regressions. Do not create a PASS certificate merely because the metadata gate succeeds.
