# State, Evidence, Checkpoint and Audit Protocol

## Machine truth

`STATE/tasks.json` is an append-only conceptual backlog: tasks have `id`, `phase`, `priority`, `title`, `deps`, `acceptance`, `evidenceKinds`, `status`, `evidence`, `notes`. Status transition `todo -> in_progress -> verified`, with `blocked` and `in_progress` recovery. Only verified dependencies unlock successors. Do not remove/reorder task IDs to get a green dashboard. Never mark status verified with manually invented evidence.

`STATE/defects.json` holds real bugs with severity and evidence. Use `taskctl.py defect-add --phase NN --task TUN-NNXX --severity High --title "..." --repro "..."` and `defect-close BUG-0001 --evidence ... --resolution "..."` after real retest; Critical/High defects automatically invalidate audits and block gates.

`STATE/status.json` tracks owner target release, current phase, latest checkpoint, last agent invocation and critical open blockers. `STATE/progress.md` is a chronological log. `STATE/decisions.md` contains ADRs. `STATE/blockers.md` gives blocked task, exact failure, attempts, required external action, safe fallback, and next eligible task.

## Evidence

`SCRIPTS/run_check.py --task TUN-001 --name gradle-build -- ./gradlew assembleDebug` captures command, working directory, timestamp, exit code, stdout/stderr and machine-generated manifest in `STATE/evidence/TUN-001/`. **Only a real command can produce runner evidence. Raw logs are locally ignored by `.gitignore` because they may contain sensitive media paths/URLs; capture sanitized check summaries in `STATE/progress.md` and re-run unavailable evidence after fresh clone.** Do not run `true` or `echo OK` as fake check; meaningful tests must be documented in acceptance criteria. UI/manual proof artifacts can be attached in the same task directory. For tasks with `device` and/or `live` evidenceKinds, supply a distinct nonempty file prefixed `device-` and/or `live-` (e.g. `device-player.png`, `live-audius-smoke.txt`) via repeatable `--artifact` arguments to `taskctl verify`; reviewer inspects authenticity. Artifacts are not a substitute for running tests.

`python SCRIPTS/taskctl.py claim TUN-001` marks in progress; `python SCRIPTS/taskctl.py verify TUN-001 --evidence STATE/evidence/TUN-001/...json` verifies the manifest has an actual executed success command. This script checks evidence existence/integrity, not semantic completeness: auditor must scrutinize tests/flow.

## Checkpoint

After each task/agent invocation: append to progress file: UTC timestamp, task & phase, commit SHA, changed files, command/evidence paths, user-visible behavior, current blockers, next exact action. Commit meaningful tested change; leave clean workspace except known, documented in-progress file diffs. On host restart read `git log` + state before writing.

## Phase audits

Use separate reviewer context whenever possible. Auditor re-runs independent tests, compares screenshots, checks negative paths, writes report; only then `STATE/audits/phase-NN-pass.json`. Tests originally run by coder cannot be the only basis for independent audit. An audit referencing an earlier commit is no longer valid for affected areas after refactor; re-run related gates.

## Progress/stuck policy

A meaningful progress event: passing previously failed acceptance test, verified task, resolved blocker with test, or reproducible root cause isolate. Mere prompt iterations, refactors without checked improvement, and new TODOs aren't progress. After two repeated failures perform isolated diagnosis; after three without progress escalate/block and work on a different dependency-ready task. Runner should stop after configurable consecutive no-progress invocations and leave a checkpoint, not spin forever.

## Self-change restrictions

Agent can edit implementation and add derived tests. Changes to specs or existing acceptance criteria need ADR and owner review. Changes to scripts that enforce evidence/gates require independent audit; never weaken them to pass completion. Save historical logs and baseline before modifying any gate.
