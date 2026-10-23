# Optional CLI Loop Driver — How To Run Unattended

## Important

An AI model does not run by itself; it needs an **agent host/CLI with file edit and terminal tools**. This kit does not include a Solar Pro 4 CLI or authenticate to Upstage/OpenRouter. The model may be selected inside Freebuff, Hermes, an IDE, or another compatible tool, but each host has different command syntax. **Do not invent a command.** Test one single prompt invocation manually first.

## Driver interface

```
python SCRIPTS/loop_driver.py --dry-run
python SCRIPTS/loop_driver.py \
  --agent-command 'YOUR_AGENT_CLI --prompt-file {prompt_file}' \
  --max-invocations 10000 --timeout-seconds 3600 \
  --max-wall-seconds 604800 --max-no-progress 4
```

Placeholders are substituted from a validated whitelist: `{prompt_file}`, `{repo}`. Quoting is parsed via `shlex`; Windows users must adapt shell/argument syntax and may need `python` rather than `py`. Driver invokes CLI as subprocess, streams transcript to disk in `STATE/driver_runs/`, detects verified-task progress, and pauses if no progress or repeated failures. **No bypass of service usage limits.** It never evaluates arbitrary Python from the prompt or overwrites task state.

To test against a harmless fake agent: run `python SCRIPTS/loop_driver.py --agent-command 'python SCRIPTS/fake_agent.py {prompt_file}' --max-invocations 2 --max-no-progress 1` and verify that it exits safely without marking tasks complete. This tests orchestration, **not** coding capability.

## Driver exit rules

- All P0 tasks verified -> exit for final independent audit/release gate (does NOT auto-claim released).
- No runnable dependency-ready tasks -> exit BLOCKED.
- Consecutive failed/no-progress sessions above configured threshold -> circuit-breaker exit.
- Wall-time/invocation cap -> checkpoint then exit; resume with same command later.
- Network, token, quota or CLI problems -> log and back off only within safe limits; do not attempt account rotation or ban circumvention.

## Running overnight

Use a terminal multiplexer or OS task mechanism configured by the owner; an IDE chat may not be safely relaunchable. Redirect logs to local disk, keep the PC awake, and check on return. Do not give the agent access to unrelated folders, privileged system operations or accounts. Avoid running the command inside an untrusted repository with arbitrary tasks.

## Manual sessions

Without CLI integration, paste `PROMPTS/00_START_SOLAR_PRO_4.md` for the first session and `PROMPTS/01_SESSION_LOOP.md` for later sessions. This is recoverable, but **not automatic 24/7 continuation**.
