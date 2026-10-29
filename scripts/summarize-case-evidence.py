#!/usr/bin/env python3
"""Summarize the four README cases from Gradle JUnit XML, without inventing outcomes."""

import argparse
import json
import sys
import xml.etree.ElementTree as ET
from datetime import datetime
from pathlib import Path

CASES = (
    ("parking", "core/domain/build/test-results/test", "DrivingRestrictionsTest"),
    ("session", "core/database/build/test-results/testDebugUnitTest", "RoomPracticeSessionRepositoryTest"),
    ("map", "feature/map/build/test-results/testDebugUnitTest", "MapViewModelTest"),
    ("observation", "core/database/build/test-results/testDebugUnitTest", "RoomVehicleObservationRepositoryTest"),
)


def read_suite(root, directory, name):
    files = list((root / directory).glob(f"TEST-*.{name}.xml"))
    if len(files) != 1:
        return {"suite": name, "status": "NOT_RUN", "reason": "exactly one report required"}
    path = files[0]
    try:
        suite = ET.parse(path).getroot()
        tests = suite.findall("testcase")
        if suite.tag != "testsuite" or not tests or not suite.get("name", "").endswith("." + name):
            raise ValueError("unexpected JUnit suite")
        timestamp = suite.attrib["timestamp"]
        if datetime.fromisoformat(timestamp.replace("Z", "+00:00")).tzinfo is None:
            raise ValueError("report timestamp must include a timezone")
        names = [test.get("name", "").strip() for test in tests]
        if any(not name for name in names) or len(set(names)) != len(names):
            raise ValueError("test cases require distinct nonempty names")
        if any(test.get("classname") != suite.attrib["name"] for test in tests):
            raise ValueError("test case class differs from suite")
        counts = {
            "tests": len(tests),
            "failures": sum(test.find("failure") is not None for test in tests),
            "errors": sum(test.find("error") is not None for test in tests),
            "skipped": sum(test.find("skipped") is not None for test in tests),
        }
        for key, value in counts.items():
            if int(suite.attrib[key]) != value:
                raise ValueError("suite totals disagree with testcase outcomes")
        status = "FAILED" if counts["failures"] or counts["errors"] else "SKIPPED" if counts["skipped"] else "PASSED"
        return {"suite": name, "status": status, **counts, "reported_at": suite.get("timestamp"), "report": str(path.relative_to(root))}
    except (OSError, ET.ParseError, KeyError, ValueError) as error:
        return {"suite": name, "status": "INVALID_REPORT", "reason": type(error).__name__ + ": unreadable or inconsistent JUnit report"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parent.parent)
    args = parser.parse_args()
    root = args.root.resolve()
    cases = {key: read_suite(root, directory, name) for key, directory, name in CASES}
    # Migration is separate from the observation repository's regression suite.
    migration = read_suite(root, "core/database/build/test-results/testDebugUnitTest", "VehicleObservationMigrationTest")
    output = {"cases": cases, "observation_migration": migration, "scope": "Existing JUnit results only; timestamps may precede this invocation. Not SDK collection or performance evidence."}
    print(json.dumps(output, ensure_ascii=False, indent=2))
    return 0 if all(row["status"] == "PASSED" for row in [*cases.values(), migration]) else 1


if __name__ == "__main__":
    sys.exit(main())
