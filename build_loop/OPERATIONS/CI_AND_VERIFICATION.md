# CI and local verification

**Task:** TUN-0005. `SPEC/QUALITY_GATES.md` requires that the same checks run on a machine
and in CI, and that a check which cannot fail is not treated as a check.

## One entry point

    python SCRIPTS/verify.py            # every step, including the Gradle ones
    python SCRIPTS/verify.py --fast     # only the steps that need no JDK
    python SCRIPTS/verify.py --list     # what would run
    python SCRIPTS/verify.py --only format --only secrets
    python SCRIPTS/verify.py --self-test

It prints one line per step with the real exit code and a single `VERDICT:` line, and exits
non-zero if any step fails. `INCOMPLETE` (exit 3) is reported when the JDK-dependent steps
could not run at all — never as a pass.

Steps, in order:

| Step | Command | Why it exists |
|---|---|---|
| `package` | `python SCRIPTS/validate_package.py` | harness structure, task graph, dependency cycles |
| `format` | `python SCRIPTS/check_format.py` | text hygiene of the content git stores |
| `secrets` | `python SCRIPTS/secret_scan.py --history` | no credential in tracked files or history |
| `ci-definition` | `python SCRIPTS/check_ci.py` | the workflow matches what runs locally |
| `unit-tests` | `bash gradlew :core:testDebugUnitTest` | JVM tests (needs JDK) |
| `assemble` | `bash gradlew :app:assembleDebug` | the debug APK actually builds |
| `lint` | `bash gradlew :app:lintDebug :core:lintDebug` | Android/Compose correctness |
| `lint-report` | `python SCRIPTS/check_lint.py` | no lint error, and no *new* warning class |

### The JDK trap this solves

Kotlin 2.0.20 cannot parse JDK 25 and Gradle ignores `org.gradle.java.home` in
`local.properties`, so on a machine whose default JDK is newer, `bash gradlew` fails with
`IllegalArgumentException: 25.0.1` before any project code runs. `verify.py` finds a JDK in
the supported range (17-21), exports `JAVA_HOME` for the Gradle steps, and refuses to run
them with a readable message otherwise. This cost two sessions before it was automated; see
`STATE/blockers.md`.

## What CI runs

[`.github/workflows/ci.yml`](../.github/workflows/ci.yml): a `hygiene` job (the four
JDK-free steps) and an `android` job that needs it (the four Gradle/report steps). Every
`run:` command is a step of `verify.py`, and `check_ci.py` fails if that ever stops being
true — a workflow that runs something a contributor cannot run locally makes "it passed CI"
unfalsifiable.

Decisions inside the workflow, with their sources:

- **Action pins** `actions/checkout@v7`, `actions/setup-java@v6`,
  `gradle/actions/setup-gradle@v6`. The node20-era `@v4` pins are deprecated by their own
  maintainers and their runtime was removed from GitHub runners (announced 2026-09-23), so
  they are rejected by `check_ci.py`. Sources: the projects' release pages, listed in the
  workflow header.
- **`runs-on: ubuntu-24.04`**, not `ubuntu-latest`: the `latest` label migrates to Ubuntu
  26.04 during October-November 2026, and the 24.04 x64 image already provides the Android
  SDK (`ANDROID_HOME`/`ANDROID_SDK_ROOT`), so no SDK setup action is needed. Arm64 images do
  not list an Android SDK, which is one more reason to name the image explicitly.
- **JDK 17** via Temurin: AGP 8.7.x documents 17 as its default; this project supports 17-21.
- **`cache-provider: basic`** on setup-gradle: the open-source (MIT) cache provider, which
  suits a GPL project; the default `enhanced` provider is a proprietary component. Gradle
  caching is deliberately *not* also enabled through `setup-java`, because the two
  mechanisms interfere.
- **`fetch-depth: 0`** on the hygiene checkout: the secret scan reads every commit, and a
  shallow clone would silently weaken it.
- **`working-directory: Tunenagi-Agent-Loop`**: the kit is a subdirectory of this
  repository, so every relative command would otherwise run in the wrong place. `check_ci.py`
  rule C8 enforces this whenever the kit is not the repository root.
- **`permissions: contents: read`**, and no secrets. Nothing in this pipeline needs write
  access or credentials.
- **No provider calls.** CI never contacts Audius, LRCLIB or MusicBrainz: live smoke tests
  are manual, rate-limited and owner-approved work, and repeating them on every push is the
  "remote source smoke spam" this task forbids. Rule C5 fails the build if a provider host
  appears in the workflow.

## What a green run does **not** prove

- **No device behaviour.** Nothing here runs an emulator, so `SPEC/PHASE_GATES.md`'s device
  requirements stay with TUN-0003 and TUN-0006. A green CI is not device evidence, and this
  project has already had to hold a task open for exactly that reason.
- **No provider integration.** Audius/LRCLIB/MusicBrainz behaviour is exercised in the phases
  that add them, with recorded live evidence.
- **Not "released".** Release readiness is the phase-09 gate plus the final independent audit.

## Verifying CI itself

GitHub Actions cannot be executed locally, so the workflow is verified by what *can* be
checked here and the residual risk is stated rather than hidden:

1. `python SCRIPTS/check_ci.py` asserts every pin is one this project verified against
   sources (C1), the runner is pinned (C2), no command hides in a block scalar (C3), every
   command is also a `verify.py` step (C4), no provider host appears (C5), a full-history
   checkout is requested (C6), permissions are read-only and no secret is referenced (C7),
   and the working directory is set (C8).
2. Every one of those commands is executed locally and must pass before a push.
3. `python SCRIPTS/check_ci.py --self-test` proves each rule fails on a workflow built to
   break it.

**Residual risk:** the first actual workflow run happens when the repository is pushed, which
is an owner action (`OPERATIONS/OWNER_REQUIRED.md`). Until then the claim is "the definition
is consistent and every command passes locally", not "CI is green".

## Adding or changing a step

1. Add it to `STEPS` in `SCRIPTS/verify.py` (with `needs_java=True` if it needs the JDK).
2. Add the same command to the matching job in `.github/workflows/ci.yml` as a single line.
3. Run `python SCRIPTS/check_ci.py` — it fails until the two agree, which is the point.
