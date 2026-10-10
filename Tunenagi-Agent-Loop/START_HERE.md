# START HERE — Owner setup and operating modes

## Recommended: AI agent in a terminal-enabled IDE

1. Install JDK supported by chosen Android Gradle Plugin, Android Studio + command-line SDK, platform tools, build tools and emulator images. Validate `java -version`, `adb version`, and a clean Android Hello World build.
2. Create a new repository `tunenagi` and copy every file/folder from this kit to its root; use Git from the beginning.
3. Open repo in the IDE agent with **Solar Pro 4** selected. The model provider/host must support editing files and executing commands; model access alone does not supply an executor.
4. Paste `PROMPTS/00_START_SOLAR_PRO_4.md` unchanged. Tell the agent the repo root if needed.
5. The model creates app code beside `SPEC/`, `PHASES/`, `STATE/` etc; it does not remove the harness.
6. At each reset/new session, send `PROMPTS/01_SESSION_LOOP.md`, or use a CLI runner if supported.

## Unattended mode

An IDE chat may stop when it reaches tool/conversation limits even with “don't stop” wording. For reliable unattended execution use a **host-supported agent loop** or a persistent CLI invocation per session. The optional `SCRIPTS/loop_driver.py` is **not automatically wired to Solar Pro 4**; configure the exact CLI command of your chosen agent using the instructions in `OPERATIONS/AUTOMATION.md`. The driver supports resumable state, invocation timeouts and circuit breakers.

## Before leaving the computer

- Ensure power, disk space, reliable network, Java/Android SDK, Git and permissions are configured.
- Bring up an emulator and verify `adb devices`; preferably supply API 26/33/35+ test targets. Android hardware/OEM tests are stronger than emulator-only tests.
- Obtain Audius API credentials **only if currently required for your request volume**. Read-only endpoints can be tested according to current docs; no secret embedded in APK.
- Define repository and package names early (`com.tunenagi.app` placeholder is fine until ownership check). Reserve no domain or name without your approval.
- Never put release signing keys, personal tokens or unrelated credentials in the repo. For public signing, store encrypted CI secrets or local secure files managed by the owner.
- Review `OPERATIONS/OWNER_REQUIRED.md` for actions automation must not improvise.

## Check progress when you return

Run `python SCRIPTS/taskctl.py status`, read `STATE/progress.md`, `STATE/blockers.md`, latest `STATE/audits/`, `git log --oneline -15`, and inspect `build/reports` and evidence logs. A green state without `adb` installation and end-to-end proof is **not a release**.

## Host-independent manual continuation

Paste `PROMPTS/01_SESSION_LOOP.md` as a new agent session any time. The agent should read the files and resume the first eligible dependency-unblocked task automatically. The intent is **continuous iteration until verified**—not an unsafe promise to defeat rate limits, developer authentication, permissions, or outages.
