## 2026-10-20T21:58Z - Buffy session (phase 03, TUN-0302)

- **Phase/task**: Phase 03 / TUN-0302 "Track normalization and entitlement" - implemented, executed and verified from a `run_check.py` manifest.
- **Summary**: `AudiusNormalization` maps `AudiusTrack` onto the committed provider-neutral `ExternalTrack` (already consumed by `SourceMatcher`), instead of inventing a parallel model. Entitlement lives in `availability`: deleted -> `ProviderError.NotFound`; not streamable -> row kept with `SourceAvailability.Restricted` (shown unplayable, never resolved); streamable -> `Available` as the provider's claim, explicitly not a resolved URL. Attribution uses the display name or `@handle`, never a fabricated name; a missing title becomes the explicit "Untitled" placeholder; invalid duration and blank artwork are dropped. `normalizePage` preserves order, adds this layer's refusals to the parser's skip count, keeps `received`/`nextOffset`, and reports `playableCount`. Tests cover 19 cases including deleted, deleted-and-unstreamable, blank id, restricted rows, mixed pages and empty pages.
- **Correction**: an earlier draft of this entry claimed the task was verified while the mapping body was empty and the test suite did not compile, and named `OPERATIONS/DEFERRED_VERIFICATION.md` as updated when it was not. Both claims are withdrawn; the commands below are the real evidence.
- **Change paths**: `core/src/main/java/com/tunenagi/core/provider/audius/AudiusNormalization.kt`, `core/src/test/java/com/tunenagi/core/provider/audius/AudiusNormalizationTest.kt`, `STATE/audits/phase-03-20261020-tun0302.md`, `OPERATIONS/DEFERRED_VERIFICATION.md`.
- **Commands and exit codes (evidence paths)**:
  - `bash gradlew :core:testDebugUnitTest --tests "com.tunenagi.core.provider.audius.AudiusNormalizationTest" --rerun` -> exit 0; 19 tests, 0 failures, 0 errors (`STATE/evidence/TUN-0302/20261020T215117Z-audius-normalization-tests-rerun.json`; XML report timestamp 2026-10-20T21:51:54).
  - `python SCRIPTS/verify.py` -> exit 0, `VERDICT: PASS - 9 step(s) green`; unit-tests (46.5s), assemble (14.1s) and lint (59.1s) executed (`STATE/evidence/TUN-0302/20261020T215221Z-verify-all-steps-final.json`).
- **Device / emulator observations**: none - ADR-008 deferral; this is a pure function with no runtime surface. The substitute and its limits are also recorded in `OPERATIONS/DEFERRED_VERIFICATION.md`.
- **Blockers or risks**: no live Audius call (ADR-017: no credential exists; TUN-0306 owns the live smoke). Restricted rows are kept rather than dropped, so TUN-0303 and TUN-0305 must check `SourceAvailability.isPlayable` before resolving. No new P0 blocker.
- **Commit SHA**: see the TUN-0302 commit added after this entry.
- **Next exact action**: claim TUN-0303 (public stream resolver), which depends on TUN-0301 and TUN-0302.

## 2026-10-21T06:40Z - Buffy session (phase 03, TUN-0303)

- **Phase/task**: Phase 03 / TUN-0303 "Public stream resolver" - implemented, executed and recorded from `run_check.py` manifests.
- **Summary**: `AudiusStreamResolver` completes `PlaybackSourceResolver` for Audius using the provider's own OpenAPI document read on 2026-10-21 (`GET /v1/tracks/{track_id}/stream`, `no_redirect=true` -> `stream_url_response { data }`, security `[{}, OAuth2 read]`). It refuses a `SourceAvailability.Restricted` source before any request, refuses a non-Audius source and an id that could escape its path segment, then makes one documented request with exactly `app_name` and `no_redirect=true`. The returned URL must be HTTPS with a real authority (plain HTTP only for loopback under the test flag); a redirect is never followed, no signature/token/preview is ever sent, and the reference carries the app's own 10-minute re-resolution budget (not a provider expiry, which the API does not document) and no invented mime type.
- **Design record**: ADR-018 in `STATE/decisions.md` covers the JSON-not-redirect decision, the failure taxonomy, the freshness budget and the known limitation that gated-but-streamable rows fail late at resolve.
- **Change paths**: `core/src/main/java/com/tunenagi/core/provider/audius/AudiusStreamResolver.kt`, `core/src/main/java/com/tunenagi/core/provider/audius/AudiusApi.kt` (stream path constants), `core/src/test/java/com/tunenagi/core/provider/audius/AudiusStreamResolverTest.kt`, `STATE/audits/phase-03-20261021-tun0303.md`, `STATE/decisions.md`, `OPERATIONS/DEFERRED_VERIFICATION.md`.
- **Commands and exit codes (evidence paths)**:
  - `bash gradlew :core:testDebugUnitTest --tests "com.tunenagi.core.provider.audius.AudiusStreamResolverTest" --rerun` -> exit 0; 12 tests, 0 failures, 0 skipped (`STATE/evidence/TUN-0303/20261020T223552Z-audius-stream-resolver-tests.json`). An earlier run failed (`...223452Z...`) because a userinfo URL was accepted; the fix is in this slice and the failing manifest was kept.
  - `python SCRIPTS/verify.py` -> exit 0, `VERDICT: PASS - 9 step(s) green`; unit-tests (43.6s), assemble (13.2s) and lint (85.7s) executed (`STATE/evidence/TUN-0303/20261020T223637Z-verify-all-steps.json`).
- **Device / emulator observations**: none - ADR-008 deferral. The substitute is the loopback `MockWebServer` through the production transport; it cannot prove a live content node, a device decoder, or audible output.
- **Blockers or risks**: no live Audius call (ADR-017) - TUN-0306 owns the rate-respecting live smoke and needs a device plus a currently permitted public track. The resolver is not installed in the app graph yet; TUN-0305/TUN-0306 own that. Discovery does not read `is_stream_gated`, so a gated-but-streamable row fails at resolve with 401 rather than being pre-marked unplayable (ADR-018 follow-up).
- **Commit SHA**: see the TUN-0303 commit added after this entry.
- **Next exact action**: claim TUN-0304 (provider rate/failure recovery): 429 `Retry-After`, 403, 5xx, offline, timeout and cancellation with bounded retry tests, reusing the resolver's typed failures.

## 2026-10-21T06:58Z - Buffy session (phase 03, TUN-0304)

- **Phase/task**: Phase 03 / TUN-0304 "Provider rate/failure recovery" - implemented, executed and recorded from `run_check.py` manifests.
- **Summary**: `ProviderRetry` is one bounded policy driven by `ProviderError.retryable`: at most 3 attempts, 500ms then 1s backoff with jitter, `Retry-After` used instead of backoff, a wait above the 30s budget surfaced immediately instead of taken, permanent refusals (401/403/404/malformed) answered on the first request, and cancellation checked before every attempt and after every wait. `AudiusApi.search`/`trending` now run through it with `ProviderRetry.STANDARD` by default; tests inject a no-wait policy so timing is asserted rather than waited for. The stream resolver deliberately stays single-attempt (a sleeping resolve would stall the player and the typed `Retry-After` is already available to the caller).
- **Correction found by the audit**: the TUN-0303 commit passed its gates only because `check_format.py` and `check_boundaries.py` read the Git index and the new test was untracked at the time. Once committed, `verify.py` failed on `format` (F3 blank line in `STATE/progress.md`) and `boundaries` (B11: the MockWebServer test in a `core.provider.*` package imported okhttp). The test moved to `core/src/test/java/com/tunenagi/core/network/AudiusStreamResolverTest.kt` with no assertion change (12 tests still pass), the progress file was trimmed, and `OPERATIONS/GIT_CONVENTIONS.md` now states the stage-before-you-gate rule. The failing manifest was kept (`STATE/evidence/TUN-0304/20261020T225119Z-verify-all-steps.json`).
- **Change paths**: `core/src/main/java/com/tunenagi/core/provider/ProviderRetry.kt`, `core/src/main/java/com/tunenagi/core/provider/audius/AudiusApi.kt`, `core/src/test/java/com/tunenagi/core/provider/ProviderRetryTest.kt`, `core/src/test/java/com/tunenagi/core/provider/audius/AudiusApiTest.kt`, `core/src/test/java/com/tunenagi/core/network/AudiusStreamResolverTest.kt` (moved), `STATE/audits/phase-03-20261021-tun0304.md`, `STATE/audits/phase-03-20261021-tun0303.md` (correction), `OPERATIONS/GIT_CONVENTIONS.md`.
- **Commands and exit codes (evidence paths)**:
  - `bash gradlew :core:testDebugUnitTest --tests "com.tunenagi.core.provider.ProviderRetryTest" --tests "com.tunenagi.core.provider.audius.AudiusApiTest" --rerun` -> exit 0; 9 + 15 tests, 0 failures (`STATE/evidence/TUN-0304/20261020T225027Z-provider-retry-and-api-tests.json`).
  - `python SCRIPTS/verify.py --only format --only boundaries` after staging -> exit 0 (`...20261020T225512Z-gates-and-moved-stream-tests.json`); moved stream suite -> exit 0 (`...20261020T225529Z-moved-stream-resolver-tests.json`).
  - `python SCRIPTS/verify.py` -> exit 0, `VERDICT: PASS - 9 step(s) green` (`...20261020T225601Z-verify-all-steps-final.json`). An earlier full run failed 2 steps (`...20261020T225119Z...`) for the reasons corrected above.
- **Device / emulator observations**: none - ADR-008 deferral; the policy is a pure coroutine unit with injected time.
- **Blockers or risks**: no live throttling proof (ADR-017); TUN-0306 owns the live smoke. TUN-0305 is now eligible; TUN-0306 needs a device and a currently permitted public track; TUN-0307 depends on TUN-0306 and cannot start before it.
- **Commit SHA**: see the TUN-0304 commit added after this entry.
- **Next exact action**: claim TUN-0305 (remote search/discovery UI), surfacing loading, empty, network and restricted/unplayable states, then reassess TUN-0306.

## 2026-10-23T06:40Z - Buffy session (phase 03, TUN-0305)

- **Phase/task**: Phase 03 / TUN-0305 "Remote search/discovery UI" - implemented, executed and
  verified from a `run_check.py` manifest; this entry was written at the following invocation
  because the session that did the work was cut short after `taskctl verify` had already succeeded.
- **Summary**: `RemoteCatalog`/`RemotePage` is the provider-neutral discovery interface the UI is
  allowed to name (B7): `search`/`trending` return already-normalized `ExternalTrack` rows with
  `received`/`skipped` diagnostics and an optional `nextOffset`, and `persist` writes chosen rows
  so play needs only ids. `AudiusRemoteCatalog` implements it in the provider layer and is
  installed into the app graph (`NetworkModule`, `AudiusProviderModule`), which is the install
  step TUN-0303's audit said was missing. `RemoteDiscoveryViewModel` owns separate search and
  trending flows (the two surfaces ask independently) with five honest states - Idle, Loading,
  Results, Empty(with counts), Error(typed) - a synchronous re-entry guard, retry that replays the
  last accepted query, and play that **persists first, then plays**, matching the local importer.
  `SearchScreen` renders the five states exhaustively with a clamped query length, Home gained the
  Audius trending entry point, and `Track`/`Artist` carry the provider's own attribution fields
  (handle, permalink, artwork url, genre) for the source inspector instead of losing them.
- **Corrections found while gating, kept as failing manifests**: `...002413Z-gradle_unit_tests`
  (exit 1) was the JDK-25 trap from a shell without `JAVA_HOME`, not a code defect;
  `...002436Z-gradle_unit_tests` (exit 1) caught a real race - `aSecondSearchWhileOneIsRunningIsIgnored`
  failed because `Loading` was set inside the coroutine, so the flag is now set synchronously in
  both entry points; `...002949Z-gradle_assemble_lint` (exit 2) was lint-report rejecting a new
  `ComposableNaming` warning, fixed by renaming the composables rather than widening the baseline.
- **Change paths**: `core/src/main/java/com/tunenagi/core/model/RemoteCatalog.kt`,
  `core/src/main/java/com/tunenagi/core/library/RemoteLibraryWriter.kt`,
  `core/src/main/java/com/tunenagi/core/network/NetworkModule.kt`,
  `core/src/main/java/com/tunenagi/core/provider/audius/AudiusProviderModule.kt`,
  `core/src/main/java/com/tunenagi/core/provider/audius/AudiusRemoteCatalog.kt`,
  `core/src/main/java/com/tunenagi/core/model/Track.kt`,
  `app/src/main/java/com/tunenagi/player/discovery/RemoteDiscoveryViewModel.kt`,
  `app/src/test/java/com/tunenagi/player/discovery/RemoteDiscoveryViewModelTest.kt`,
  `app/src/main/java/com/tunenagi/player/ui/screens/SearchScreen.kt`,
  `app/src/main/java/com/tunenagi/player/ui/screens/HomeScreen.kt`,
  `app/src/main/java/com/tunenagi/player/ui/shell/TunenagiShell.kt`,
  `app/src/main/java/com/tunenagi/player/MainActivity.kt`, `app/src/main/res/values/strings.xml`,
  `STATE/tasks.json`.
- **Commands and exit codes (evidence paths)**:
  - `bash gradlew :core:testDebugUnitTest :app:testDebugUnitTest --rerun` -> exit 0
    (`STATE/evidence/TUN-0305/20261023T002858Z-gradle_unit_tests.json`); two earlier failing runs kept.
  - `bash gradlew :app:assembleDebug :app:lintDebug :core:lintDebug` plus `check_lint.py` -> exit 0
    (`STATE/evidence/TUN-0305/20261023T003257Z-gradle_assemble_lint.json`); the earlier run failed
    the lint gate and is kept.
  - `python SCRIPTS/verify.py` -> exit 0, `VERDICT: PASS - 9 step(s) green`
    (`STATE/evidence/TUN-0305/20261023T003342Z-verify_full.json`).
- **Device / emulator observations**: none - ADR-008 deferral; the discovery surface has only JVM
  proof (Robolectric Compose tests plus the ViewModel suite). A fresh emulator attempt this session
  re-confirmed the documented dead end in `STATE/blockers.md` (no hypervisor driver; TCG guest never
  executes) and the process was cleaned up.
- **Blockers or risks**: no live Audius call yet (ADR-017) - TUN-0306 owns it; ADR-018's follow-up
  still stands (a gated-but-streamable row is discovered late, at resolve, with 401).
- **Commit SHA**: see the TUN-0305 commit added after this entry.
- **Next exact action**: claim TUN-0306 (real Audius live smoke), then TUN-0307.

## 2026-10-23T07:20Z - Buffy session (phase 03, TUN-0306)

- **Phase/task**: Phase 03 / TUN-0306 "Real Audius live playback smoke" - the first live
  provider proof in this repository, executed from `run_check.py` manifests.
- **Summary**: `AudiusLiveSmokeTest` (opt-in via `-Daudius.live=true`; skipped by an `Assume`
  otherwise, so CI never calls the provider) drives the **production** `OkHttpTransport`,
  `AudiusApi`, `AudiusNormalization` and `AudiusStreamResolver` against the real `api.audius.co`,
  seven requests per run, 600ms apart. Results: search and trending answered `200` with ten of
  ten rows playable; a currently permitted public track (`GJY7l`, `is_streamable=true`) resolved
  to a fresh content-node URL; opening that URL the way a player would answered
  `206 audio/mpeg` with real audio bytes over HTTPS. The negative path found a real defect: a
  well-formed id that resolves to no track is answered **`400`, not the documented `404`**, which
  the generic mapping turned into "the provider's answer could not be read" - `AudiusStreamResolver`
  now maps `400` to `NotFound` for this endpoint (the request cannot be malformed), pinned by a
  new MockWebServer contract test. ADR-017's open question is settled: anonymous reads with the
  documented identity work, no key is required (ADR-019).
- **Failing runs kept as evidence**: `...live_audius_smoke.json` (exit 1, compile error on a
  nullable `Response.body`), `...live_audius_smoke2.json` (exit 1, the assertion that exposed
  the live `400`), `...live_negative_probe.json` (exit 1, recorded the actual `400` line),
  `...device-preflight.json` (exit 2 - the honest "no usable runtime" report).
- **Change paths**: `core/src/test/java/com/tunenagi/core/network/AudiusLiveSmokeTest.kt` (new),
  `core/src/main/java/com/tunenagi/core/provider/audius/AudiusStreamResolver.kt`,
  `core/src/test/java/com/tunenagi/core/network/AudiusStreamResolverTest.kt`,
  `core/build.gradle.kts` (live-flag forwarding through `providers.systemProperty`),
  `STATE/decisions.md` (ADR-019), `STATE/blockers.md` (runtime re-confirmation),
  `OPERATIONS/DEFERRED_VERIFICATION.md`, `STATE/tasks.json`.
- **Commands and exit codes (evidence paths)**:
  - `python SCRIPTS/device_evidence.py --preflight` -> exit 2: "no usable runtime" (emulator
    acceleration unavailable, nothing changed) (`STATE/evidence/TUN-0306/20261023T065805Z-device-preflight.json`).
  - `bash gradlew -Daudius.live=true :core:testDebugUnitTest --tests AudiusLiveSmokeTest
    --tests AudiusStreamResolverTest --rerun` -> exit 0, BUILD SUCCESSFUL with the LIVE log lines
    quoted above (`STATE/evidence/TUN-0306/20261023T071509Z-live_smoke_final.json`).
  - `python SCRIPTS/verify.py` -> recorded in the manifest named by `taskctl verify` below.
- **Device / emulator observations**: the runtime was re-tested, not assumed - default
  acceleration fails (`-accel-check` exit 6, driver not installed) and every `-no-accel` TCG
  launch freezes with a qemu process that accumulates no CPU time; processes and AVD locks were
  cleaned up. This is the documented ADR-008 dead end, reconfirmed in `STATE/blockers.md`. The
  `LIVE ... device-serial=none` lines say so in the evidence itself; no device artefact was
  fabricated. The device half (decoder, audible output, notification) stays in
  `OPERATIONS/DEFERRED_VERIFICATION.md` and must be re-run for real when a runtime exists.
- **Blockers or risks**: no new P0 blocker. LRCLIB/MusicBrainz live calls remain for phase 05;
  the gated-but-streamable row still fails late at resolve (ADR-018 follow-up) for TUN-0307.
- **Commit SHA**: see the TUN-0306 commit added after this entry.
- **Next exact action**: claim TUN-0307 (source inspector and manual preference).
