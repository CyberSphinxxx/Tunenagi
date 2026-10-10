# Skill — Context Rollover and Session Handoff

**Trigger:** Approaching model context limit, host restart, long-running task, CI outage.

1. Stop starting new changes. Complete or carefully checkpoint current small task.
2. Run `git status --short`, `git log -8 --oneline`, `taskctl.py status`, `taskctl.py next`; record exact unfinished diffs.
3. Append to `STATE/progress.md`: timestamp, task, attempted commands and exit codes, evidence links, failing test, actual code location, next concrete shell commands. Update blocker if needed.
4. Commit only coherent/verified code; do not make a misleading 'finished' commit for broken work. Preserve uncommitted state for recovery, explaining it.
5. On fresh session, use `PROMPTS/01_SESSION_LOOP.md`, not whole huge initial prompt. Don't summarize away failures.
6. If an audit boundary is next, launch a **fresh skeptical reviewer context** with `PROMPTS/02_PHASE_AUDIT.md` and no implicit trust of coder assertions.

**Exit:** a fresh agent can resume in under a few minutes with no guesswork or hidden conversation-only decisions.
