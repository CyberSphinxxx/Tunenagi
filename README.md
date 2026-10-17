# Tunenagi (繋ぎ) — Autonomous Android Development Kit

**Edition:** 2026-10-11 | **Target:** Android-first v1.0 | **Primary model:** Solar Pro 4 (model-agnostic tooling)

This is **a development specification and agent harness, not the finished app**. It tells a code-capable agent how to create, test, audit and package Tunenagi: a Kotlin + Jetpack Compose + Media3 open-source music player with local audio, Audius public streaming, unified playlists, MusicBrainz metadata, LRCLIB lyrics, and a restricted *declarative* plugin system.

## Begin here

1. **Read `START_HERE.md`.** Copy this entire folder into the root of an **empty Git repository** (or tell the agent its path). The folder can become the repo's baseline; do not place secrets in it.
2. Give your coding agent the entire contents of `PROMPTS/00_START_SOLAR_PRO_4.md` as its opening prompt. The agent must have **real** repo edit, terminal, Android SDK and (for device gates) emulator/device access.
3. For successive fresh coding sessions, supply `PROMPTS/01_SESSION_LOOP.md`. Configure your agent application's own continuous mode if available. Alternatively use the optional **`SCRIPTS/loop_driver.py`** for a CLI that can be launched once per session; details in `OPERATIONS/AUTOMATION.md`.
4. Task-state and sanitized checkpoint notes are versionable; raw device/check logs in `STATE/evidence/` and agent transcripts in `STATE/driver_runs/` are gitignored for privacy. State is in `STATE/tasks.json`, `STATE/progress.md`, `STATE/status.json`, `STATE/defects.json`, `STATE/audits/` and Git history. Run `python SCRIPTS/taskctl.py status` and `python SCRIPTS/validate_package.py` at any time.
5. A release is **not finished** until all v1 P0 tasks have objective evidence, per-phase audits pass, an APK is actually installable and usable on an Android device, and a final gate passes. A missing SDK, upstream outage or keystore is a **recorded blocker**, not a reason to fabricate success.

## Navigation (progressive disclosure)

| Need | Read |
|---|---|
| Product intent & scope | `SPEC/PRD.md`, `SPEC/OUT_OF_SCOPE.md` |
| UX journeys & visual polish | `SPEC/UX_DESIGN.md` |
| Kotlin architecture & data | `SPEC/ARCHITECTURE.md`, `SPEC/DATA_MODEL.md` |
| Streaming and provider rules | `SPEC/PROVIDERS.md`, `SPEC/PLUGINS.md` |
| Playback and Android lifecycle | `SPEC/AUDIO_ENGINE.md` |
| Security / rights / privacy | `SPEC/SECURITY_LEGAL.md` |
| Tests and acceptance | `SPEC/QUALITY_GATES.md`, `SPEC/EDGE_CASES.md` |
| Exact phase plan | `PHASES/00...09` |
| Agent operating rules | `AGENTS.md`, `OPERATIONS/STATE_PROTOCOL.md` |
| Model-specific prompting | `OPERATIONS/SOLAR_PRO_4.md` |
| Audits | `PROMPTS/02_PHASE_AUDIT.md`, `OPERATIONS/AUDIT_TEMPLATE.md` |
| Agent skills | `SKILLS/README.md` |
| Evidence commands | `SCRIPTS/taskctl.py`, `SCRIPTS/run_check.py` |

## What is / is not automated

- The supplied Python scripts manage **state, checks and repeatable CLI invocations**. They do not contain a coding AI, magically provision a model, or provide Android SDK/keystore credentials.
- `loop_driver.py` requires an existing **CLI agent** to invoke (Solar Pro 4 may be selectable in that host). It has bounded unattended operation, logs and no-progress stop conditions. It **must not run forever without safety limits**.
- A running agent can keep working through approved tasks while unattended. Network loss, tool outages, context limits, permission barriers, API changes and infrastructure failure can interrupt it. It must checkpoint and resume; **no agent can truthfully promise zero bugs or guaranteed 24/7 completion**.
- Release signing secrets and public publishing require explicit owner setup; the agent must not manufacture keys, billing accounts or API privileges.

## Core scope (frozen)

- **Android minimum SDK:** API 26; target/compile current supported stable after verification.
- **Native:** Kotlin, Compose, Material 3 as a foundation, Media3 ExoPlayer + `MediaLibraryService`, Room, DataStore, Coroutines/Flow, Hilt, OkHttp, Coil, WorkManager (non-playback).
- **v1 playable providers:** local files and accessible **Audius** public tracks, respecting creator controls.
- **Metadata:** local tags and provider metadata primary; **MusicBrainz enrichment** optional / bounded; do not make all playback depend on it.
- **Lyrics:** local LRC and LRCLIB sync/plain fallback.
- **Plugins:** a genuine, installable **restricted declarative HTTP/JSON provider**, fixed operations, allowlists. Do not run third-party executable scripts in v1.
- **Distribution:** GitHub release APKs; later review for F-Droid. Release candidate can be debug-signed for QA; public releases must be properly signed with owner-approved keys.

## Research grounding (verified Oct 2026)

- Long-lived agents, feature lists, evidence and incremental commits: https://www.anthropic.com/engineering/effective-harnesses-for-long-running-agents
- Repository as system of record, strict architecture/evals: https://openai.com/index/harness-engineering/
- Upstage Solar Pro 4: https://www.upstage.ai/blog/en/solar-pro-4
- Android background audio: https://developer.android.com/media/media3/session/background-playback
- Audius API: https://docs.audius.co/api/
- Audius license: https://audius.org/open-music-license.pdf
- MusicBrainz API: https://musicbrainz.org/doc/MusicBrainz_API
- LRCLIB: https://lrclib.net/docs
- Google Play dynamic-code rules: https://support.google.com/googleplay/android-developer/answer/16559646

Dates, endpoints, library versions and terms must be **rechecked by the development agent at implementation time**. Do not assume documentation remains stable.
