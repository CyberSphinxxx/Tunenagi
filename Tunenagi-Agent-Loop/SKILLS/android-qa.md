# Skill — Failure Reproduction / Test-First Android QA

**Trigger:** Bug, test failure, phase gate, device verification, performance regression.

1. State expected vs actual with **exact reproduction**; capture tool command, SDK/Android API, device serial, build SHA and environment.
2. Make a failing focused test if practicable (red). Reproduce once without changing code. Check logs and isolate where the state crossed a boundary. Do not shotgun-upgrade Gradle/dependencies or refactor unrelated modules.
3. Implement smallest fix (green); add negative test and rerun affected suite. Refactor only after verified green. Save stdout and exit with `SCRIPTS/run_check.py`.
4. E2E tests must validate actual observable outcomes: audio plays, file imported, queue persisted, album art displayed, source error shown. A test whose only assertion is `mockProvider.called` is insufficient.
5. If failure appears flaky, run bounded repeats and record frequency; quarantine only with a filed P0 issue and release disposition, never hide it.
6. Screenshot/UI inspection is separate from unit tests. Runtime unavailable = NOT RUN / blocked; don't substitute a fake screenshot.
7. After two unsuccessful patches, stop and perform root-cause experiment; after three unproductive invocations, log blocker and work independent task.

**Exit:** original failure reproduced then fixed, regression test survives, related phase audit updated.
