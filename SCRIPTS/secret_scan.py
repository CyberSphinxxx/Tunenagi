#!/usr/bin/env python3
"""Secret-scanning gate.

`SPEC/SECURITY_LEGAL.md` requires that no key or credential reaches the repository, and
the release checklist requires CI secret scanning. This is that scan: it reads tracked
files (default) or the patch stream of every commit (`--history`) and fails on anything
that looks like a credential.

Two rules keep the gate honest and usable:
  * findings never print the secret itself, only the file, line and a short redacted
    prefix, so running the scan cannot leak what it found;
  * this file is excluded from its own scan, because its pattern table would otherwise be
    reported as the very thing it detects.

`--self-test` writes fake credentials to a temporary directory and asserts each pattern
fires while a clean file and a documented placeholder pass. A scanner that cannot fail is
not a scanner.
"""
import argparse
import re
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SELF = Path(__file__).resolve()

# (rule id, pattern, human description). Patterns are deliberately narrow: a noisy gate
# gets ignored, and a gate that gets ignored is worse than no gate.
PATTERNS = [
    ("S1-private-key", re.compile(rb"-----BEGIN [A-Z ]*PRIVATE KEY-----")),
    ("S2-aws-access-key", re.compile(rb"\bAKIA[0-9A-Z]{16}\b")),
    # Google keys are `AIza` plus 35 characters; no trailing \b, so a longer key still
    # matches on its first 35 characters instead of slipping through the boundary check.
    ("S3-google-api-key", re.compile(rb"\bAIza[0-9A-Za-z_\-]{35}")),
    ("S4-slack-token", re.compile(rb"\bxox[abpsr]-[0-9A-Za-z-]{10,}\b")),
    ("S5-github-token", re.compile(rb"\bgh[pousr]_[0-9A-Za-z]{36,}\b")),
    ("S6-bearer-token", re.compile(rb"(?i)authorization\s*:\s*bearer\s+[A-Za-z0-9._\-]{20,}")),
    ("S7-jwt", re.compile(rb"\beyJ[A-Za-z0-9_\-]{8,}\.[A-Za-z0-9_\-]{8,}\.[A-Za-z0-9_\-]{8,}\b")),
    ("S8-store-password", re.compile(rb"(?i)\b(store|key|keystore)password\s*[=:]\s*[^\s#\"'<>]{6,}")),
    ("S9-api-key-assignment", re.compile(rb"(?i)\b(api[_-]?key|client[_-]?secret|access[_-]?token)\s*[=:]\s*[\"']?[A-Za-z0-9._\-]{24,}")),
]

# Placeholders, documentation and configuration *keys without values* are legitimate.
ALLOW_MARKERS = (
    b"example",
    b"placeholder",
    b"redacted",
    b"your-",
    b"your_",
    b"xxxx",
    b"<your",
    b"not a real",
    b"'<",
)

TEXT_SKIP_SUFFIXES = {
    ".png", ".jpg", ".jpeg", ".webp", ".gif", ".ico", ".jar", ".zip", ".apk",
    ".aab", ".so", ".dex", ".bin", ".exe", ".dll", ".hprof",
}


def allowed(line: bytes) -> bool:
    lowered = line.lower()
    if any(marker in lowered for marker in ALLOW_MARKERS):
        return True
    # A variable name or documentation mention with no value is not a secret.
    stripped = line.strip().rstrip(b"\r")
    return stripped.endswith((b"=", b":", b"store.password=", b"key.password="))


def redact(match: bytes) -> str:
    head = match[:4].decode("utf8", "replace")
    return f"{head}{'*' * 4} ({len(match)} chars)"


def scan_text(text: bytes, label: str, findings):
    for offset, line in enumerate(text.split(b"\n"), start=1):
        if allowed(line):
            continue
        for rule, pattern in PATTERNS:
            match = pattern.search(line)
            if match:
                findings.append((label, offset, rule, redact(match.group(0))))


def tracked_files():
    out = subprocess.run(
        ["git", "ls-files", "-z"], cwd=ROOT, stdout=subprocess.PIPE, check=True
    ).stdout
    return [name for name in out.decode("utf8").split("\0") if name]


_PREFIX = None


def checkout_prefix():
    """`git show :<path>` resolves against the repository root, not the working
    directory, so the kit's checkout prefix must be prepended. Getting this wrong makes
    every read fail silently, which would look exactly like a clean repository."""
    global _PREFIX
    if _PREFIX is None:
        out = subprocess.run(
            ["git", "rev-parse", "--show-prefix"], cwd=ROOT,
            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, check=True,
        ).stdout
        _PREFIX = out.decode("utf8").strip()
    return _PREFIX


def stored_bytes(name):
    """What git would commit for this path, so the scan covers the repository content
    rather than only whatever happens to sit in the working tree."""
    proc = subprocess.run(
        ["git", "show", f":{checkout_prefix()}{name}"], cwd=ROOT, stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL, check=False,
    )
    return proc.stdout if proc.returncode == 0 else None


def scan_repository():
    findings = []
    scanned = 0
    for name in tracked_files():
        if Path(name).suffix.lower() in TEXT_SKIP_SUFFIXES:
            continue
        if (ROOT / name).resolve() == SELF:
            continue  # the pattern table itself is not a credential
        text = stored_bytes(name)
        if text is None or b"\0" in text[:8000]:
            continue
        scanned += 1
        scan_text(text, name, findings)
    if scanned == 0:
        print("WARNING: no files were readable to scan")
    return scanned, findings


def scan_history():
    """Scans the patch stream of every commit, so a secret that was committed and later
    deleted is still reported."""
    proc = subprocess.run(
        ["git", "log", "-p", "--all", "--no-color", "--no-merges"],
        cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True,
    )
    findings = []
    current = "history"
    for line in proc.stdout.split(b"\n"):
        if line.startswith(b"+++ b/"):
            current = line[6:].decode("utf8", "replace")
            continue
        if current.endswith("secret_scan.py"):
            continue
        if not line.startswith((b"+", b" ")):
            continue
        scan_text(line[1:], f"history:{current}", findings)
    return findings


def self_test():
    cases = {
        "aws.txt": b"key = AKIAIOSFODNN7EXAMPLE\n",  # allowlisted placeholder sample
        "aws_real.txt": b"aws_access_key_id=AKIA7QF2M4XR9TZP1WVD\n",
        "gcp.txt": b"AIzaSyD-1234567890abcdefghijklmnopqrstuv\n",
        "github.txt": b"token: ghp_" + b"a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6q7R8\n",
        "slack.txt": b"xoxb-1234567890-abcdefghijkl\n",
        "jwt.txt": b"Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.abcdefghijklmno\n",
        "bearer.txt": b"Authorization: Bearer 8f3a9c1d7b6e5f4a3b2c1d0e\n",
        "keystore.txt": b"storePassword=Sup3rS3cretValue\n",
        "private.key": b"-----BEGIN RSA PRIVATE KEY-----\n",
        "clean.txt": b"fun main() = println(\"no credentials here\")\n",
        "doc.md": b"Set `api_key=<your-key-here>` before running the sample server.\n",
    }
    expectations = {
        "aws_real.txt": "S2-aws-access-key",
        "gcp.txt": "S3-google-api-key",
        "github.txt": "S5-github-token",
        "slack.txt": "S4-slack-token",
        "jwt.txt": "S7-jwt",
        "bearer.txt": "S6-bearer-token",
        "keystore.txt": "S8-store-password",
        "private.key": "S1-private-key",
    }
    failures = []
    with tempfile.TemporaryDirectory(prefix="tunenagi-secrets-") as tmp:
        base = Path(tmp)
        for name, content in cases.items():
            (base / name).write_bytes(content)
        for name, expected in expectations.items():
            findings = []
            scan_text((base / name).read_bytes(), name, findings)
            rules = {rule for _, _, rule, _ in findings}
            if expected not in rules:
                failures.append(f"{name} did not report {expected} (reported {sorted(rules)})")
            for _, _, _, preview in findings:
                if "*" not in preview:
                    failures.append(f"{name} finding was not redacted")
        for name in ("aws.txt", "clean.txt", "doc.md"):
            findings = []
            scan_text((base / name).read_bytes(), name, findings)
            if findings:
                failures.append(
                    f"{name} was a false positive: {[rule for _, _, rule, _ in findings]}"
                )
        if failures:
            for item in failures:
                print(f"SELF-TEST FAIL: {item}")
            return 1
    print(
        "PASS: secret scan detects private keys, AWS/GCP/GitHub/Slack tokens, JWTs, "
        "bearer tokens and keystore passwords; redacts them; allows placeholders and clean files."
    )
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--history", action="store_true", help="Also scan every commit's patch stream.")
    parser.add_argument("--self-test", action="store_true", help="Verify the scanner can actually fail.")
    args = parser.parse_args()

    if args.self_test:
        return self_test()

    scanned, findings = scan_repository()
    if args.history:
        findings += scan_history()

    for label, line, rule, preview in findings:
        print(f"{label}:{line}: {rule} {preview}")
    scope = "tracked files" + (" and full git history" if args.history else "")
    print(f"scanned {scanned} file(s) across {scope}, {len(findings)} finding(s)")
    if findings:
        print("FAIL: potential credential in the repository; rotate and remove it, then re-run")
        return 2
    print("PASS: no credential patterns found")
    return 0


if __name__ == "__main__":
    sys.exit(main())
