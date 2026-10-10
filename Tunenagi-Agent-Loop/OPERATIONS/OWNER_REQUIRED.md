# Owner-dependent actions and unapproved autonomy boundaries

The agent may autonomously create local code/tests, download legitimate public build dependencies, run SDK/emulator tests and assemble debug candidates in the agreed repo. It **must request explicit owner action** or mark blocker for:

- Acquiring or providing paid accounts, service API tokens or licenses, billing/terms acceptance, domain registration.
- Choosing/holding a production signing keystore and passwords; using protected CI release credentials.
- Publishing a GitHub/F-Droid/store release or pushing tags to public organization branches; sending PRs to third parties; contacting providers.
- Changing scope of P0 features, taking an unknown legal risk, bypassing a provider's rights/authentication, reusing unlicensed artwork/audio.
- Deleting user files or resetting existing Git history, using non-project secrets, system-wide administrator operations.

The agent can do work that doesn't require these: debug-signed QA build, reproducible unsigned release candidate, release notes and step-by-step owner instructions. If blocked, don't claim public release complete.
