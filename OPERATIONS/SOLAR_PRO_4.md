# Solar Pro 4 Agent Operating Profile

Verified upstream model page: https://www.upstage.ai/blog/en/solar-pro-4 . **The exact platform exposing Solar Pro 4 (IDE, Freebuff, Hermes, OpenRouter, etc.) may impose independent time, tool and context limits.** “Free/unlimited” is a host plan statement, not a capability of this prompt. Do not assume context window, invocation format, native tool use or self-continuation without testing your actual host.

## Prompting strategy

- Clear task identity/inputs/outputs, no wandering open-ended rewrites.
- Read small, relevant files (progressive disclosure) rather than the full bundle each context.
- One task per slice with acceptance criteria; evidence-first and error diagnosis.
- Use 3–6 line plan, perform changes, run checks, write concise status with exact paths.
- Persist every important decision in repo; on context reset use `01_SESSION_LOOP.md`.
- Avoid asking the model to 'think forever' or 'never stop' as the execution mechanism. The **host runner** must call the model repeatedly, with persistent Git and task state.
- Prefer deterministic scripts for selection/gating instead of letting the model pick arbitrary next features.

## Response format expected from each coding invocation

```
TASK: TUN-XXX
PLAN: <up to 6 lines>
CHANGED: <paths>
CHECKS: <command, actual exit code, evidence path>
DEVICE: <serial/API or NOT RUN>
RISKS: <specific>
STATE: VERIFIED | IN_PROGRESS | BLOCKED
NEXT: <task ID + exact first step>
```

Do not waste context regurgitating all product specs. Choose simple, idiomatic Kotlin and documented APIs. If tool output cannot be seen, say NOT VERIFIED.
