# Phase Gates and Traceability

**Gate rule:** Every task in the phase verified, no open Critical/High according to `STATE/defects.json`, targeted unit/integration checks run, artifacts demonstrably working where indicated, separate audit report PASS, regression against prior phases. `taskctl.py gate NN` provides the basic machine status; human/device evidence is reviewed per `PROMPTS/02_PHASE_AUDIT.md`.

| Phase | Outcome and required proof |
|---|---|
| 00 Tooling | Git and build/version catalog, SDK/Gradle configured, running app on emulator, CI skeleton, real logs |
| 01 Domain/data | Source-neutral models, Room schema & migration, repo APIs, persistent queue/playlist repositories, tests |
| 02 Local playback | Media3 single service, local import, queue, notifications, background & focus; device playback demo |
| 03 Audius streaming | Real search/discovery/stream, auth/rights errors, unit mocks + real on-device public track proof |
| 04 Library | Mixed playlist, persistence, favorites, search, backup and UI; DB restoration tests |
| 05 Lyrics/metadata | LRCLIB sync/plain/local parsing, MusicBrainz throttling; live and mock tests with screenshots |
| 06 Declarative plugins | External manifest lifecycle, real sample through host, hostile URL tests, independent security audit |
| 07 UX polish | Home/Search/Library/Player interactions, accessibility, small-screen/rotation, visual evidence and fixes |
| 08 Hardening | Crash/error matrix, CI integration, macrobenchmark, memory/network, licenses/privacy/security checks |
| 09 Release | Reproducible release candidate, signed only with owner's approved key, install/upgrade proof, final independent audit |

### Cross-phase regressions

At the end of every later phase rerun fast unit + build + lint and core playback smoke. After schema changes rerun migration/backup; after player changes rerun background/media and queue; after provider/network changes rerun security and error handling; after design system changes rerun screenshot/accessibility. Auditor records command+exit+evidence paths, not just 'looks good'.

### Gate / audit distinction

`taskctl.py gate` is a **metadata checker** only. It cannot verify screenshots, Android state or actual quality. A separate auditor must inspect source and evidence and write a PASS report. Release requires both. If no device present, relevant phase remains BLOCKED rather than marking pass from mocks.

**Amendment (ADR-008, owner-directed 2026-10-14):** the owner has directed that the build continue without Android device testing, so the sentence above is honoured in a specific way rather than suspended. A phase is audited on its machine-verifiable evidence, the auditor states in the report which mandatory proof was unavailable, and the missing proof is added to `OPERATIONS/DEFERRED_VERIFICATION.md`. No phase may be called complete on a device or live requirement it did not test, and the release gates at the end of the project still require the register to be empty or explicitly accepted by the owner.
