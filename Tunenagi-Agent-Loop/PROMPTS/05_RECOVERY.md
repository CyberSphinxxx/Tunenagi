# RESUME AFTER CRASH / CONTEXT RESET / HOST RESTART

Do not guess previous state. Read `AGENTS.md`, `STATE/progress.md`, `STATE/blockers.md`, `STATE/tasks.json`, `git status/log`, `STATE/audits/` and last `STATE/evidence/` entries. Run `python SCRIPTS/validate_package.py` and `python SCRIPTS/taskctl.py status`.

If uncommitted changes exist, inspect them and determine whether they belong to the in-progress task, were partially generated, or are unrelated user edits. Preserve user edits. Run tests before recovering. Recover or revert only own changes with explicit evidence; never destructive reset as a reflex.

If a task is marked verified but artifact/test unavailable or source code changed after its evidence, demote it to in_progress, invalidate downstream audit, and regenerate evidence. Check SDK/network setup; re-run minimal build; select an eligible task and continue. Update progress with exact time and what was recovered.
