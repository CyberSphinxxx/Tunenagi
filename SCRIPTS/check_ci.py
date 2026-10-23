#!/usr/bin/env python3
"""Check the CI definition against what this repository can actually run.

GitHub Actions cannot be executed here, so a workflow that "looks right" is not evidence.
This checker turns the claims the workflow makes into assertions:

  C1  every `uses:` pin is a version this project verified against official sources
  C2  the runner is pinned to an explicit image, not a moving label
  C3  every `run:` is a single-line command, so the checker (and a reviewer) can see all
      of them; a block scalar hides commands from this check
  C4  every command the workflow runs is also a command `SCRIPTS/verify.py` runs locally,
      so CI and a contributor's machine cannot drift apart
  C5  no provider host is contacted - CI must never smoke-test Audius/LRCLIB/MusicBrainz
  C6  a full-history checkout is requested whenever the secret scan runs, otherwise it
      would silently scan a single commit
  C7  least privilege: `contents: read` and no secrets are referenced
  C8  the kit's working directory is set when the kit is not the repository root, because
      every command here is relative to the kit

`--self-test` feeds the checker deliberately broken workflows and asserts every rule fires,
because a checker whose assertions cannot fail is decoration.
"""
import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github/workflows/ci.yml"

# C1: verified action pins. Sources are recorded so a future bump has somewhere to start.
ALLOWED_USES = {
    "actions/checkout@v7": "https://github.com/actions/checkout/releases",
    "actions/setup-java@v6": "https://github.com/actions/setup-java/releases",
    "gradle/actions/setup-gradle@v6": "https://github.com/gradle/actions/releases",
}
DEPRECATED_USES = {
    "actions/checkout@v4": "node20 runtime and no further updates",
    "actions/checkout@v3": "node20 runtime and no further updates",
    "actions/setup-java@v4": "deprecated by the action's own README",
    "gradle/gradle-build-action": "superseded by gradle/actions/setup-gradle",
}

# Separators are `[ \t]`, never `\s`: `\s` can swallow a newline, which made a bare `run:`
# key capture the following comment line and treat it as a command.
KEYLINE = r"^[ \t]*(?:-[ \t]*)?{key}:[ \t]*(.*?)[ \t]*$"
RUNNER_PATTERN = re.compile(KEYLINE.format(key="runs-on"), re.M)
USES_PATTERN = re.compile(KEYLINE.format(key="uses"), re.M)
RUN_PATTERN = re.compile(KEYLINE.format(key="run"), re.M)

# C5: contacting these from CI would be the "remote source smoke spam" the task forbids.
PROVIDER_HOSTS = ("audius.co", "audius.org", "lrclib.net", "musicbrainz.org")


def normalize(command):
    """Make a command comparable across platforms and interpreters.

    Locally the interpreter is an absolute path (`C:/Python314/python.exe`); on the runner
    it is `python`. Comparing raw strings would report every command as unknown on one side
    and hide a real drift, which is the opposite of the point.
    """
    text = re.sub(r"\s+", " ", command.replace("\\", "/")).strip()
    if sys.executable:
        text = text.replace(sys.executable.replace("\\", "/"), "python")
    text = re.sub(r"python3(?:\.\d+)?\b", "python", text)
    return re.sub(r"python\.exe\b", "python", text)


def local_commands():
    """Commands verify.py runs locally, normalised for comparison.

    Imported from verify.py rather than duplicated, so the two cannot disagree; a change
    there is picked up here automatically.
    """
    sys.path.insert(0, str(ROOT / "SCRIPTS"))
    import verify  # noqa: E402  (path is set up immediately above)

    return {normalize(" ".join(step.argv)) for step in verify.STEPS}


def check(text, known_commands):
    problems = []

    for pin in USES_PATTERN.findall(text):
        if pin in DEPRECATED_USES:
            problems.append(f"C1 deprecated action pin '{pin}' ({DEPRECATED_USES[pin]})")
        elif not pin.startswith("./") and pin not in ALLOWED_USES:
            problems.append(f"C1 unverified action pin '{pin}' - add it with a source URL first")

    runners = RUNNER_PATTERN.findall(text)
    if not runners:
        problems.append("C2 no runs-on found")
    for runner in runners:
        if runner.endswith("-latest"):
            problems.append(f"C2 runner '{runner}' is a moving label; pin an explicit image")

    if re.search(r"^\s*(?:-\s*)?run:\s*[|>]", text, re.M):
        problems.append("C3 a run: block scalar hides commands from this check; use single lines")

    ran = [row.strip() for row in RUN_PATTERN.findall(text) if row.strip()]
    if not ran:
        problems.append("C3 no run: commands found, which cannot be right")
    for command in ran:
        if normalize(command) not in known_commands:
            problems.append(f"C4 '{command}' is not a command SCRIPTS/verify.py runs locally")

    lowered = text.lower()
    for host in PROVIDER_HOSTS:
        if host in lowered:
            problems.append(f"C5 workflow references provider host {host}; CI must not call providers")

    if any("secret_scan.py" in row for row in ran) and "fetch-depth: 0" not in text:
        problems.append("C6 secret scan runs without `fetch-depth: 0`; it would see one commit")

    if "contents: read" not in text:
        problems.append("C7 permissions are not narrowed to `contents: read`")
    if re.search(r"\$\{\{\s*secrets\.", text):
        problems.append("C7 workflow references secrets, but this pipeline needs none")

    prefix = checkout_prefix()
    if prefix and "working-directory:" not in text:
        problems.append(
            f"C8 the kit is not the repository root (git prefix '{prefix}') but the workflow "
            f"never sets `working-directory:`, so every relative command would run in the wrong place"
        )

    return problems


def checkout_prefix():
    """Path from the repository root to the kit, empty when the kit is the root."""
    import subprocess
    proc = subprocess.run(["git", "rev-parse", "--show-prefix"], cwd=ROOT,
                          stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, check=False)
    return proc.stdout.decode("utf8").strip() if proc.returncode == 0 else ""


SELF_TEST_CASES = {
    "deprecated pin": ("- uses: actions/checkout@v4", "C1"),
    "unknown pin": ("uses: someone/unknown-action@v9", "C1"),
    "moving runner": ("runs-on: ubuntu-latest", "C2"),
    "block scalar": ("- run: |", "C3"),
    "foreign command": ("- run: rm -rf /", "C4"),
    "provider host": ("- run: curl https://api.audius.co/v1/tracks", "C5"),
    "shallow checkout": ("- run: python SCRIPTS/secret_scan.py --history", "C6"),
    "secret reference": ("${{ secrets.TOKEN }}", "C7"),
    "missing working directory": ("jobs:\n  a:\n    runs-on: ubuntu-24.04\n", "C8"),
}


def self_test():
    failures = []
    # Only the real step commands: adding a fixture's command here would mask the very
    # failure the C4 case exists to catch.
    known = local_commands()
    for command in known:
        if normalize(command) != command:
            failures.append(f"local command is not normalised: {command!r}")
    for label, (snippet, expected) in SELF_TEST_CASES.items():
        problems = check(snippet, known)
        if not any(problem.startswith(expected) for problem in problems):
            failures.append(f"{label!r} did not raise {expected} (got {problems})")
    # A workflow built only from allowed pieces must stay clean, or the checker is useless.
    # `working-directory` is required here because the kit really is a subdirectory.
    good = (
        "permissions:\n  contents: read\n"
        "jobs:\n  a:\n    runs-on: ubuntu-24.04\n"
        "    defaults:\n      run:\n        working-directory: Tunenagi-Agent-Loop\n"
        "    steps:\n"
        "      - name: Check out\n        uses: actions/checkout@v7\n"
        "        with:\n          fetch-depth: 0\n"
        "      # a comment mentioning run: must not be mistaken for a command\n"
        "      - name: Secret scan\n        run: python SCRIPTS/secret_scan.py --history\n"
    )
    remaining = check(good, known)
    if remaining:
        failures.append(f"a valid workflow was rejected: {remaining}")

    if failures:
        for item in failures:
            print(f"SELF-TEST FAIL: {item}")
        return 1
    print("PASS: CI checker rejects deprecated pins, moving runners, hidden commands, "
          "commands that do not run locally, provider calls, shallow history scans, secrets "
          "and a missing working directory.")
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--self-test", action="store_true", help="Verify the checker can fail.")
    parser.add_argument("--path", help="Check a specific workflow file instead of the default.")
    args = parser.parse_args()

    if args.self_test:
        return self_test()

    path = Path(args.path) if args.path else WORKFLOW
    if not path.is_file():
        print(f"FAIL: no workflow at {path.relative_to(ROOT)}")
        return 2

    problems = check(path.read_text(encoding="utf8"), local_commands())
    for problem in problems:
        print(f"{path.name}: {problem}")
    if problems:
        print(f"FAIL: {len(problems)} CI definition problem(s)")
        return 2
    print(f"PASS: {path.name} pins verified actions, runs only locally-verified commands, "
          f"and contacts no provider")
    return 0


if __name__ == "__main__":
    sys.exit(main())
