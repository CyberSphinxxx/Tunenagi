# Quality, Evidence, Testing and Definition of Done

## Test pyramid

1. **Static**: Gradle dependency resolution/compile, ktlint/detekt or equivalent consistent formatter/lint, Android Lint, manifest check, license/sca/secret check.
2. **Unit**: queue transitions; resolver ranking/locking; LRC parsing; duration/artists matching; Room DAO/migration logic; plugin manifest validation; URL/address policy; HTTP retry/rate limiting; playback state reducer.
3. **Contract/integration**: MockWebServer exact Audius/LRCLIB/MusicBrainz payload variations, redirects/rate/errors; provider mapping schema; test audio stream; actual Room persistence and JSON import/export.
4. **Instrumentation**: compose navigation, permissions, media session/notifications, local playback, process recreation, plugin install -> search -> play; API 26, 33 and latest supported as tool access permits.
5. **Live third-party smoke** (controlled/non-exhaustive): a real accessible Audius public track searched/streamed; LRCLIB and MusicBrainz requests while honoring service limits; record timestamp, status and redacted URL. Never spam public APIs from repeated CI.
6. **Manual/visual device QA**: actual emulator/physical screenshots, headset/Bluetooth/audio focus where possible, screen lock, low RAM, rotation, font sizes and battery; report tests **not runnable** on emulator (OEM-specific behaviors).
7. **Benchmark**: Compose jank, startup and scrolling Macrobenchmark; collect before/after regression artifacts and environment. Define thresholds from measurements rather than fabricated universal ms.

## Per-task completion contract

- Task requirements traced to code changes and test cases.
- Relevant test command(s) actually run and pass with saved full output; command exit code recorded by `SCRIPTS/run_check.py`.
- On UI/audio/integration task, automated end-to-end or documented manually witnessed device behavior, with screenshot/log and device details.
- No known Critical/High regressions; security and license checks appropriate to task.
- State updated using `taskctl.py verify <id> --evidence <manifest.json>`; evidence manifest created by **real** runner, not by manually typing PASS.
- Git commit with task ID. `STATE/progress.md` records summary, verification and next steps.

## Non-negotiable integrity

Never replace failing live test with mocked-only test and call it end-to-end. Never delete/relax assertions to get green; avoid `@Ignore` without filed issue and release disposition. Never fabricate a device run; **NOT RUN** is valid and leaves gate blocked. Do not use headless JVM tests as proof of Android background services. Re-run prior passed phase regressions after cross-cutting changes.

**ADR-008 amendment (owner-directed 2026-10-14):** with device testing deferred, the UI suites in
`app/src/sharedTest/` run on the JVM under Robolectric *in addition to* running on a device, and a
JVM run is accepted as evidence for a UI task **only** for what Robolectric genuinely executes
(composition, navigation, semantics, state). It is never accepted for insets, rendering,
hardware, background-service survival, audio focus or live providers; every such gap is listed in
`OPERATIONS/DEFERRED_VERIFICATION.md`. The "cannot be certified complete" rule below still holds
while that register is non-empty.

## Acceptance testing matrix

| Scenario | Unit | Integration | Android device | Live source |
|---|---|---|---|---|
| Queue shuffle/repeat/persistence | Yes | Room | Yes | No |
| Local import/permission/loss | Mapper | SAF/Room | Yes | No |
| Audius discovery/play | DTO/error mapping | MockWebServer | Yes | Yes |
| Media focus/headphones/lockscreen | State reducer | Service | Yes | No |
| Playlist mixed/offline/export | Yes | DB/serialization | Yes | Optional |
| LRCLIB synchronized lyrics | Parser | HTTP | Yes | Optional |
| Plugin install/disable/stream | Security | Local test server | Yes | Controlled |
| UI font/rotation/miniplayer | Component | Navigation | Yes | No |
| DB migrations | Yes | Room migration tests | Yes | No |

## Severity scale

Critical: security exploit, serious data loss, malware risk, illegal bypass, crash for most flows. High: playback broadly broken, data corruption, uncontrolled retries, app inaccessible, rights failure. Medium: frequent bad UX, specific codec/device failing, visible layout defect. Low: polish/copy/rare non-blocking issue.

**No Critical or High open issue at release**, and no high-priority P0 requirement marked blocked. Medium findings require explicit owner disposition; do not reclassify without evidence.

## Final gates

`./gradlew clean ...` successful compilation, unit tests, lint, Android instrumentation, import/export and migrations, full documented demo on at least one real Android runtime; audit completeness; release checksum/signature; fresh reinstall + upgrade test; all P0 tasks verified with evidence. Provide artifact paths. If Android SDK/emulator unavailable, build cannot be certified complete.
