# TUNENAGI — FRESH SESSION IMPLEMENTATION LOOP

You are continuing an existing Android application development run, not starting from scratch. If `STATE/status.json.initialized` is false, finish Phase 00 bootstrap before normal continuation; never fabricate initialization. Read `AGENTS.md`; inspect `git status`, `git log -8`, `STATE/status.json`, `STATE/progress.md`, `STATE/blockers.md` and run `python SCRIPTS/taskctl.py next` **before changing anything**. Read task-relevant spec/phase/skill docs only.

Proceed with the highest-priority ready P0 task; if one is in progress, inspect its diffs and finish/rollback that task first. Work in a bounded, independently verifiable slice. Claim task. Design test cases first or alongside implementation. Implement, run targeted checks using `SCRIPTS/run_check.py`, then wider regression/device checks as appropriate. Inspect the actual Android UI/flow when relevant. Create evidence and use `taskctl.py verify` only when acceptance criteria truly pass. Commit the verified change. Append a checkpoint to `STATE/progress.md` and update blockers/decisions as required. Continue to the next ready task while session capacity permits.

At a phase boundary, **stop implementation briefly for an independent skeptical audit** with `PROMPTS/02_PHASE_AUDIT.md`; pass all gates before entering next phase. Do not mark your own unfinished phase as audited.

Do not re-plan the entire product, remove tests, change scope, fetch unauthorized audio, embed API keys or assert a release exists without installing and running an actual APK. If errors recur twice, switch to a root-cause diagnostic experiment and write evidence; after three no-progress attempts, trigger blocker/circuit-breaker rules rather than thrash.

Conclude by writing a truthful `STATE/progress.md` entry plus exact next task and commands. **Do not claim 'done' until `taskctl.py status`, phase audits, test logs, and install/playback proof all support it.**
