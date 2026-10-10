# Just-in-time skills for coding agents

These are repo-local **procedures**. The agent should read only the skill relevant to its active task, not paste them all into context at once. They are compatible with Solar Pro 4 or any IDE/CLI agent able to read files and run tools. They are not third-party downloaded executable skills; avoid unknown plugin executables.

| Active task | Skill |
|---|---|
| Compose page/design polish | `compose-ux.md` |
| Audio/media session/player | `media3-playback.md` |
| Audius/MusicBrainz/LRCLIB APIs | `provider-integrations.md` |
| Manifest/install/SSRF | `plugin-security.md` |
| Failure diagnosis and tests | `android-qa.md` |
| Release and signing | `release.md` |
| Changes to schema | `migration.md` |
| Cross-context work sessions | `agent-handoff.md` |

Run task-specific checks then append evidence and update `STATE/progress.md`. Skills are sub-guides; `AGENTS.md` and P0 specs take priority.
