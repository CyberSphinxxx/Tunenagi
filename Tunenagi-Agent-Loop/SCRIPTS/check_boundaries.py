#!/usr/bin/env python3
"""Dependency-direction gate: enforce the layer rules from SPEC/ARCHITECTURE.md.

`SPEC/ARCHITECTURE.md` says "UI -> domain interfaces/use cases -> repositories ->
providers/database/network", and that plugins "must not see Room, Android Context, host tokens,
MediaStore or raw HTTP clients". Written down, those are aspirations; this script turns them into
assertions a build can fail on.

Rules (each is also listed in OPERATIONS/DEPENDENCY_GRAPH.md, and B9 keeps the two in step):

  B1  com.tunenagi.core.model imports no Android, Compose, Room, network or storage type
  B2  com.tunenagi.core.database may use the model layer and nothing above it
  B3  com.tunenagi.core.playback may use the model and database layers, not library or plugins
  B4  com.tunenagi.core.library may use the model and database layers, not playback or plugins
  B5  com.tunenagi.core.plugins may not reach Android, Room, network clients, storage or library
  B6  :core must not depend on the :app module (the dependency runs one way)
  B7  :app may not import com.tunenagi.core.database - the UI talks to domain interfaces, so a
      screen cannot bypass a repository
  B8  no data or domain package may import Compose UI, and the design system reaches nothing below
  B9  every rule above is named in OPERATIONS/DEPENDENCY_GRAPH.md

**What is read:** the *stored* content (`git show :<path>`) for tracked files, and the working tree
for files passed with `--paths`, exactly like `check_format.py`, so a CRLF checkout cannot change
the verdict. Kotlin files under `src/test` are included on purpose: a test that reaches around a
boundary is the first sign the production code will too.

`--self-test` feeds the gate files that break B1, B2, B5, B7 and B6 and asserts each rule fires
while a clean file passes: a gate that cannot fail is not a gate.
"""
import argparse
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
GRAPH_DOC = "OPERATIONS/DEPENDENCY_GRAPH.md"

ANDROID = ("android.", "androidx.")
COMPOSE = ("androidx.compose.",)
ROOM = ("androidx.room.", "androidx.sqlite.")
NETWORK = ("okhttp3.", "retrofit2.", "java.net.", "javax.net.")


def forbidden(*groups):
    """Splats a mix of tuples and sets into one set of prefix strings."""
    merged = set()
    for group in groups:
        merged.update(group)
    return merged

# layer package -> (allowed internal layers, forbidden import prefixes)
RULES = {
    "com.tunenagi.core.model": (
        set(),
        forbidden(ANDROID, COMPOSE, ROOM, NETWORK),
    ),
    "com.tunenagi.core.database": (
        {"com.tunenagi.core.model"},
        forbidden(COMPOSE, NETWORK, {"com.tunenagi.core.library", "com.tunenagi.core.playback",
                                    "com.tunenagi.core.plugins", "com.tunenagi.core.network"}),
    ),
    "com.tunenagi.core.playback": (
        {"com.tunenagi.core.model", "com.tunenagi.core.database"},
        forbidden(COMPOSE, {"com.tunenagi.core.library", "com.tunenagi.core.plugins"}),
    ),
    "com.tunenagi.core.library": (
        {"com.tunenagi.core.model", "com.tunenagi.core.database"},
        forbidden(COMPOSE, {"com.tunenagi.core.playback", "com.tunenagi.core.plugins"}),
    ),
    "com.tunenagi.core.plugins": (
        {"com.tunenagi.core.model", "com.tunenagi.core.network"},
        forbidden(ANDROID, ROOM, NETWORK, {"com.tunenagi.core.database", "com.tunenagi.core.library"}),
    ),
    "com.tunenagi.core.designsystem": (
        set(),
        forbidden(ROOM, NETWORK, {"com.tunenagi.core.database", "com.tunenagi.core.library",
                                 "com.tunenagi.core.playback", "com.tunenagi.core.plugins"}),
    ),
}

# The rule a violation of this layer's rules is reported under.
RULE_ID = {
    "com.tunenagi.core.model": "B1",
    "com.tunenagi.core.database": "B2",
    "com.tunenagi.core.playback": "B3",
    "com.tunenagi.core.library": "B4",
    "com.tunenagi.core.plugins": "B5",
    "com.tunenagi.core.designsystem": "B8",
}

# B9 needs the document to name every rule that exists.
ALL_RULE_IDS = ("B1", "B2", "B3", "B4", "B5", "B6", "B7", "B8", "B9")

APP_PACKAGE = "com.tunenagi.player"

IMPORT = re.compile(r"^[ \t]*import[ \t]+([A-Za-z0-9_.]+)", re.M)
SOURCE_PATH = re.compile(r"src/(?:main|test|sharedTest|androidTest)/java/(.+)/[^/]*\.kt$")


def tracked_files():
    out = subprocess.run(["git", "ls-files", "-z"], cwd=ROOT, stdout=subprocess.PIPE, check=True).stdout
    return [name for name in out.decode("utf8").split("\0") if name]


_PREFIX = None


def checkout_prefix():
    global _PREFIX
    if _PREFIX is None:
        out = subprocess.run(["git", "rev-parse", "--show-prefix"], cwd=ROOT,
                             stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, check=True).stdout
        _PREFIX = out.decode("utf8").strip()
    return _PREFIX


def stored_text(name):
    proc = subprocess.run(["git", "show", f":{checkout_prefix()}{name}"], cwd=ROOT,
                          stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, check=False)
    return proc.stdout.decode("utf8", "replace") if proc.returncode == 0 else None


def layer_of(name):
    """The package a Kotlin file lives in, derived from its path."""
    match = SOURCE_PATH.search(name.replace("\\", "/"))
    return match.group(1).replace("/", ".") if match else None


def imports_of(text):
    return IMPORT.findall(text)


def violations(name, text):
    """Rule violations for one file, as (rule, detail) pairs."""
    normalized = name.replace("\\", "/")
    problems = []
    layer = layer_of(normalized)
    if layer is None:
        return problems
    package = layer if layer in RULES else None
    allowed, forbidden_prefixes = RULES[package] if package is not None else (set(), ())

    for imported in imports_of(text):
        if imported.startswith(APP_PACKAGE) and "/core/" in normalized:
            problems.append(("B6", f":core imports an app type: {imported}"))
        if package is None:
            continue
        rule = RULE_ID[package]
        target = next((candidate for candidate in RULES if imported.startswith(candidate)), None)
        if target is not None and target != package and target not in allowed:
            problems.append((rule, f"{package} imports {imported} from a layer it must not know"))
        for prefix in forbidden_prefixes:
            if imported.startswith(prefix):
                problems.append((rule, f"{package} imports {imported} (forbidden prefix '{prefix}')"))
    return problems


def scan(files, read):
    findings = []
    checked = 0
    for name in files:
        if not name.endswith(".kt"):
            continue
        text = read(name)
        if text is None:
            continue
        checked += 1
        findings.extend((name, rule, detail) for rule, detail in violations(name, text))
    return checked, findings


def declaration_findings(files, read):
    """B6/B7 for module declarations and for the UI reaching into storage."""
    findings = []
    for name in files:
        normalized = name.replace("\\", "/")
        if normalized.startswith("core/") and normalized.endswith(".gradle.kts"):
            text = read(name)
            if text is None:
                continue
            if 'project(":app")' in text or "project(':app')" in text:
                findings.append((name, "B6", "the :core module declares a dependency on :app"))
        if normalized.startswith("app/src/main/java/") and normalized.endswith(".kt"):
            text = read(name)
            if text is None:
                continue
            for imported in imports_of(text):
                if imported.startswith("com.tunenagi.core.database"):
                    findings.append((name, "B7", f"the UI imports the storage layer directly: {imported}"))
    return findings


def documentation_findings():
    doc = ROOT / GRAPH_DOC
    if not doc.is_file():
        return [("B9", f"{GRAPH_DOC} does not exist")]
    text = doc.read_text(encoding="utf8")
    return [("B9", f"rule {rule} is not named in {GRAPH_DOC}")
            for rule in ALL_RULE_IDS if rule not in text]


def self_test():
    failures = []
    cases = {
        "a domain file importing Android": (
            "core/src/main/java/com/tunenagi/core/model/A.kt",
            "package com.tunenagi.core.model\nimport android.content.Context\n",
            "B1"),
        "a domain file importing storage": (
            "core/src/main/java/com/tunenagi/core/model/B.kt",
            "package com.tunenagi.core.model\nimport com.tunenagi.core.database.TunenagiDatabase\n",
            "B1"),
        "a data file importing a higher layer": (
            "core/src/main/java/com/tunenagi/core/database/C.kt",
            "package com.tunenagi.core.database\nimport com.tunenagi.core.library.Repo\n",
            "B2"),
        "a plugin importing Room": (
            "core/src/main/java/com/tunenagi/core/plugins/D.kt",
            "package com.tunenagi.core.plugins\nimport androidx.room.Room\n",
            "B5"),
        "a plugin importing Android context": (
            "core/src/main/java/com/tunenagi/core/plugins/E.kt",
            "package com.tunenagi.core.plugins\nimport android.content.Context\n",
            "B5"),
        "a playback file importing the library layer": (
            "core/src/main/java/com/tunenagi/core/playback/F.kt",
            "package com.tunenagi.core.playback\nimport com.tunenagi.core.library.SomeRepo\n",
            "B3"),
    }
    for label, (path, text, expected) in cases.items():
        found = {rule for rule, _ in violations(path, text)}
        if expected not in found:
            failures.append(f"{label} did not raise {expected} (got {sorted(found)})")

    clean = ("core/src/main/java/com/tunenagi/core/model/G.kt",
             "package com.tunenagi.core.model\nimport java.time.Instant\n")
    if violations(*clean):
        failures.append("a clean domain file was reported: " + str(violations(*clean)))

    ui_file = ("app/src/main/java/com/tunenagi/player/X.kt",
               "package com.tunenagi.player\nimport com.tunenagi.core.database.TunenagiDatabase\n")
    if not [row for row in declaration_findings([ui_file[0]], lambda _name: ui_file[1]) if row[1] == "B7"]:
        failures.append("B7 did not fire for a UI file importing the database")

    core_gradle = ("core/build.gradle.kts", 'implementation(project(":app"))\n')
    if not [row for row in declaration_findings([core_gradle[0]], lambda _name: core_gradle[1]) if row[1] == "B6"]:
        failures.append("B6 did not fire for :core depending on the :app module")

    if failures:
        for item in failures:
            print(f"SELF-TEST FAIL: {item}")
        return 1
    print("PASS: boundary gate rejects a domain file importing Android or storage, a data file "
          "importing a higher layer, a plugin importing Room or Context, playback importing the "
          "library layer, a UI file importing the database, and :core depending on :app.")
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--paths", nargs="*", help="Check these files instead of every tracked Kotlin file.")
    parser.add_argument("--list", action="store_true", help="Print the enforced rules and exit.")
    parser.add_argument("--self-test", action="store_true", help="Verify the gate can fail.")
    args = parser.parse_args()

    if args.list:
        for package, (allowed, forbidden) in sorted(RULES.items()):
            allow = ", ".join(sorted(allowed)) or "nothing internal"
            print(f"{RULE_ID[package]} {package}: allows {allow}; forbids {', '.join(forbidden)}")
        print("B6 :core must not depend on :app; B7 :app must not import core.database")
        print(f"B9 every rule is named in {GRAPH_DOC}")
        return 0
    if args.self_test:
        return self_test()

    explicit = bool(args.paths)
    files = [path.replace("\\", "/") for path in (args.paths or tracked_files())]

    def read(name):
        path = ROOT / name
        return path.read_text(encoding="utf8") if path.is_file() else None

    reader = read if explicit else stored_text
    checked, findings = scan(files, reader)
    findings += declaration_findings(files, reader)
    findings += [(GRAPH_DOC, rule, detail) for rule, detail in documentation_findings()]

    if checked == 0:
        print("WARNING: no Kotlin files were readable to check")
    for name, rule, detail in findings:
        print(f"{name}: {rule} {detail}")
    print(f"checked {checked} Kotlin file(s), {len(findings)} boundary problem(s)")
    if findings:
        print("FAIL: dependency direction violated (see OPERATIONS/DEPENDENCY_GRAPH.md)")
        return 2
    print("PASS: dependency direction respected")
    return 0


if __name__ == "__main__":
    sys.exit(main())
