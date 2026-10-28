#!/usr/bin/env python3
"""Check tracked source without printing matched private values.

This is a guard against common accidental commits, not a complete secret audit.
History, PR discussions, Actions logs and artifacts require a separate review.
"""

import os
import pathlib
import re
import subprocess
import sys

RULES = {
    "private-planning-url": re.compile(rb"https?://(?:app\.)?notion\.(?:com|so)/", re.I),
    "personal-absolute-path": re.compile(rb"(?:/Users/|[A-Z]:\\Users\\)[A-Za-z0-9_.-]+", re.I),
    "private-key": re.compile(rb"-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----"),
    "credential-token": re.compile(rb"(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,}|AKIA[0-9A-Z]{16})"),
}
LOCAL_EXTENSIONS = {".db", ".sqlite", ".sqlite3", ".jks", ".keystore", ".p12", ".pfx"}


def main():
    root = pathlib.Path(__file__).resolve().parent.parent
    try:
        files = subprocess.check_output(["git", "ls-files", "-z"], cwd=root, stderr=subprocess.PIPE).split(b"\0")
    except (OSError, subprocess.CalledProcessError):
        print("Cannot inspect tracked source; check failed without printing local diagnostics.", file=sys.stderr)
        return 1
    findings = []
    for name in files:
        if not name:
            continue
        path = root / name.decode("utf-8", errors="surrogateescape")
        label = path.relative_to(root).as_posix().encode("unicode_escape").decode("ascii")
        try:
            if path.is_symlink():
                data = os.fsencode(os.readlink(path))
            elif path.is_file():
                data = path.read_bytes()
            else:
                continue
        except OSError:
            findings.append((label, "unreadable-tracked-file"))
            continue
        if path.suffix.lower() in LOCAL_EXTENSIONS:
            findings.append((label, "local-data-or-signing-file"))
        for rule, pattern in RULES.items():
            if pattern.search(data):
                findings.append((label, rule))
    if findings:
        for label, rule in findings:
            print(f"{label}: {rule}", file=sys.stderr)
        print("Public content check failed; matched values were not printed.", file=sys.stderr)
        return 1
    print("Tracked public content check passed (limited pattern guard).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
