# Git conventions and repository hygiene

**Task:** TUN-0004. This file is the contract for how the Tunenagi repository is written
to. `SPEC/QUALITY_GATES.md` requires a clean tracked project, and `OPERATIONS/STATE_PROTOCOL.md`
requires a checkpoint after every invocation; both depend on these rules.

## Commit messages

    TUN-NNNN: <imperative summary, under ~72 characters>

    Why this change is needed, what it actually does, and anything a reviewer
    cannot infer from the diff (rejected alternatives, known limits).

- The task ID prefix is mandatory so `git log` can be read against `STATE/tasks.json`.
- The subject says what changed; the body says *why*. "Update" and "fix" alone are noise.
- A commit is one coherent slice that passes its checks. Do not commit a red build, a
  half-finished refactor, or a change whose tests have not been run.
- Never claim verification in a message that the evidence does not show. A slice whose
  device proof is still outstanding says so.
- Reformatting, renames and behaviour changes stay in separate commits where practical.

## What may be committed

Committed: source, build configuration, the version catalog, `STATE/*.md`, `STATE/*.json`,
specs, prompts, scripts, and sanitized check summaries.

**Never committed:** secrets or credentials of any kind; signing material (`*.keystore`,
`*.jks`, `*.p12`, `*.pem`, `*.key`); `local.properties` (machine-specific paths);
raw evidence logs and manifests under `STATE/evidence/**` and driver transcripts under
`STATE/driver_runs/**` (gitignored because they can contain device paths, file paths and
provider URLs); build output; APK/AAB artefacts; local Gradle distribution downloads;
heap dumps; IDE and tool state.

The repository must stay tellable as "no credentials". `SCRIPTS/secret_scan.py` is the
enforcement point (below).

## Line endings

The index stores **LF** for all text files, declared in `.gitattributes` (`* text=auto
eol=lf`), with `*.bat`/`*.cmd` kept as CRLF and binaries marked `binary`. A Windows working
tree may show CRLF; that is a local checkout detail and not a repository property. If a
file still ends up stored with CRLF, `git add --renormalize <path>` fixes it and
`SCRIPTS/check_format.py` reports it.

## Identity

Commit as the repository owner's configured identity. Agents must not invent an author,
impersonate a third party, or add `Signed-off-by` for anyone who did not sign off; when the
local Git identity is unset, pass the repository's existing author explicitly for that
command rather than writing a new global configuration.

## Branches, remotes and releases

- `main` is the integration branch and must pass the checks below.
- Work happens in slices committed to `main` in this project (single-owner repository);
  if a branch is used, it is named `tun/NNNN-short-description`.
- **Pushing, tagging, opening pull requests and publishing releases are owner actions.**
  See `OPERATIONS/OWNER_REQUIRED.md`. An agent may prepare a release candidate and the
  exact instructions, and may not create tags on public branches or sign with a key it
  does not hold.
- Release tags are `vX.Y.Z`. A release candidate is only described as release-capable when
  the phase-09 gate and the final audit pass.

## Checks before a commit or push

Run from the kit root (`Tunenagi-Agent-Loop/`):

    python SCRIPTS/check_format.py          # F1-F5 text rules over stored content
    python SCRIPTS/secret_scan.py --history # credentials in tracked files and in history
    python SCRIPTS/taskctl.py status        # task/defect/gate state

and for this Android project (`JAVA_HOME` must point at JDK 17-21; Kotlin 2.0.20 cannot
parse JDK 25, see `STATE/blockers.md`):

    JAVA_HOME=<jdk-21> bash gradlew :core:testDebugUnitTest
    JAVA_HOME=<jdk-21> bash gradlew :app:assembleDebug :app:assembleDebugAndroidTest
    JAVA_HOME=<jdk-21> bash gradlew :app:lintDebug :core:lintDebug

Real check runs are captured with `SCRIPTS/run_check.py`, which writes the command,
working directory, exit code and log into `STATE/evidence/<TASK>/`.

## Formatting and static analysis

Two independent layers, both real and both runnable without a device:

1. **Android Lint** (`:app:lintDebug`, `:core:lintDebug`) for Android/Compose correctness:
   manifest problems, missing resources, API misuse.
2. **`SCRIPTS/check_format.py`** for text hygiene, enforced over the content git stores:

   | Rule | Meaning |
   |---|---|
   | F1 | no tab characters |
   | F2 | no trailing whitespace |
   | F3 | exactly one final newline |
   | F4 | no carriage return in the stored content |
   | F5 | code/script/config lines at most 140 characters |

   Markdown, JSON, XML and plain text are exempt from F5 only. `LENGTH_EXEMPT` in the
   script waives F5 for the pre-existing compact harness scripts and the generated Gradle
   wrapper; **that list may only shrink**, and adding to it requires an independent audit
   because it changes a gate. Each gate has a `--self-test` that proves it can fail.

### Lint baseline

`:core` reports no findings. `:app` reports **0 errors** and, as of TUN-0004, only:

- `GradleDependency` / `AndroidGradlePluginVersion` advisories ("a newer version is
  available"). These are accepted deliberately: ADR-001 pins every version in
  `gradle/libs.versions.toml` and version moves are owner-reviewed, so the advisory is
  information rather than a defect. They are not suppressed, because a stale pin should
  stay visible.
- `ObsoleteSdkInt` for `mipmap-anydpi-v26`, suppressed with evidence in `app/lint.xml`:
  the suggested merge breaks AAPT2 resource linking (reproduced with a clean build).

Fixed at TUN-0004 and worth keeping fixed: `ModifierParameter` (a Composable's `modifier`
parameter must be the first optional parameter) and `MonochromeLauncherIcon` (adaptive
icons need a `<monochrome>` layer for Android 13 themed icons).

Adopting ktlint/detekt with repository-wide auto-formatting remains open and belongs to
TUN-0005 (CI), where it can be pinned in the version catalog and run for every commit
rather than only locally.
