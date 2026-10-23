# Phase 07 — Product-quality Compose UX

**Goal:** Home, Search, Library, Now Playing, queue, playlists, lyrics and plugins are polished and accessible.

## Mandatory prerequisite

Prior phase must be fully verified and have a separate fresh-context `STATE/audits/phase-NN-pass.json` report. Use `python SCRIPTS/taskctl.py next` to check eligibility. Revisit official API docs whenever needed.

## Implementation slices

### TUN-0701 — Home and navigation polish

**Deliver:** Original Tunenagi design, responsive Home, skeleton/error/empty, miniplayer inset and no dead CTA.

**Dependencies:** phase gate only. **Evidence:** command, device.

**Agent working protocol:** make the smallest real change that passes the acceptance; write a failure-path test, execute relevant tests via `run_check.py`, inspect Android UX if necessary, capture proof, update `tasks.json` via `taskctl.py`, commit, checkpoint. No mocks-only success for device or live source task.

### TUN-0702 — Search and library UI polish

**Deliver:** Unified search, filters, sorting, long strings, scroll restore, keyboard and touch targets.

**Dependencies:** TUN-0701. **Evidence:** command, device.

**Agent working protocol:** make the smallest real change that passes the acceptance; write a failure-path test, execute relevant tests via `run_check.py`, inspect Android UX if necessary, capture proof, update `tasks.json` via `taskctl.py`, commit, checkpoint. No mocks-only success for device or live source task.

### TUN-0703 — Now Playing and queue UX

**Deliver:** Artwork/seek controls, source chip, gesture, lyric/queue sheets, rotation and queue drag; screenshot.

**Dependencies:** TUN-0701. **Evidence:** command, device.

**Agent working protocol:** make the smallest real change that passes the acceptance; write a failure-path test, execute relevant tests via `run_check.py`, inspect Android UX if necessary, capture proof, update `tasks.json` via `taskctl.py`, commit, checkpoint. No mocks-only success for device or live source task.

### TUN-0704 — Playlist and plugins UX polish

**Deliver:** Editing, confirmations, permission disclosure/diagnostic, error/empty states; device screenshots.

**Dependencies:** TUN-0702. **Evidence:** command, device.

**Agent working protocol:** make the smallest real change that passes the acceptance; write a failure-path test, execute relevant tests via `run_check.py`, inspect Android UX if necessary, capture proof, update `tasks.json` via `taskctl.py`, commit, checkpoint. No mocks-only success for device or live source task.

### TUN-0705 — Settings privacy and storage UX

**Deliver:** Playback/preferences/data cache/backup/credits/license sections all wired, screen reader accessible.

**Dependencies:** TUN-0701. **Evidence:** command, device.

**Agent working protocol:** make the smallest real change that passes the acceptance; write a failure-path test, execute relevant tests via `run_check.py`, inspect Android UX if necessary, capture proof, update `tasks.json` via `taskctl.py`, commit, checkpoint. No mocks-only success for device or live source task.

### TUN-0706 — Accessibility font and display audit

**Deliver:** TalkBack semantics, font scaling 150/200%, small/large/landscape, contrast, motion preferences.

**Dependencies:** TUN-0703, TUN-0704, TUN-0705. **Evidence:** command, device.

**Agent working protocol:** make the smallest real change that passes the acceptance; write a failure-path test, execute relevant tests via `run_check.py`, inspect Android UX if necessary, capture proof, update `tasks.json` via `taskctl.py`, commit, checkpoint. No mocks-only success for device or live source task.

### TUN-0707 — Visual reviewer fix/retest round

**Deliver:** Full screenshot matrix, independent UX evaluator, fix all high defects and reshoot proof.

**Dependencies:** TUN-0702, TUN-0706. **Evidence:** command, device.

**Agent working protocol:** make the smallest real change that passes the acceptance; write a failure-path test, execute relevant tests via `run_check.py`, inspect Android UX if necessary, capture proof, update `tasks.json` via `taskctl.py`, commit, checkpoint. No mocks-only success for device or live source task.

## Phase completion audit

**Mandatory proof:** Screenshot matrix, font scale, small/large screen, TalkBack, back behavior, keyboard/mini-player insets, UI automation and no dead controls.

**Common traps:** Beautiful-only mockups, fake music data, inaccessible buttons, clipped player or loading forever.

Run `python SCRIPTS/taskctl.py gate 07` to check task metadata. Start a skeptical independent session using `PROMPTS/02_PHASE_AUDIT.md`. Write `STATE/audits/phase-07-YYYYMMDD-HHMM.md` with verdict and checks. Run `python SCRIPTS/taskctl.py audit 07 --report STATE/audits/<report>.md` after independent review. Inspect previous phase regressions. Do not create a PASS certificate merely because the metadata gate succeeds.
