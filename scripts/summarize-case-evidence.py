#!/usr/bin/env python3
"""Summarize the four README cases from Gradle JUnit XML, without inventing outcomes."""

import argparse
import json
import sys
import xml.etree.ElementTree as ET
from datetime import datetime
from pathlib import Path

CASES = (
    ("finished_review", "feature/home/build/test-results/testDebugUnitTest", "SessionFinishReviewViewModelTest"),
    ("session", "core/database/build/test-results/testDebugUnitTest", "RoomSessionDeletionRepositoryTest"),
    ("map", "feature/map/build/test-results/testDebugUnitTest", "MapViewModelTest"),
    ("recording", "core/domain/build/test-results/test", "PracticeRecordingControllerTest"),
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
    session_intents = read_suite(root, "feature/home/build/test-results/testDebugUnitTest", "SessionHistoryDeletionViewModelTest")
    # Nullable manual declarations are part of the exact deletion target and FK cascade.
    session_declarations = read_suite(root, "core/database/build/test-results/testDebugUnitTest", "RoomCourseCompletionRepositoryTest")
    # The map case now includes native input ownership; this pure suite covers its epoch policy.
    # Opt-in real native Route/MapLibre tests remain separate from these JVM report receipts.
    map_input = read_suite(root, "feature/map/build/test-results/testDebugUnitTest", "MapInputEpochTest")
    # The recording case spans one controller, durable run ownership and the feature adapter.
    # These JVM receipts do not establish native service or actual SDK collection success.
    recording_repository = read_suite(root, "core/database/build/test-results/testDebugUnitTest", "RoomRecordingRunRepositoryTest")
    recording_commit_boundaries = read_suite(root, "core/database/build/test-results/testDebugUnitTest", "RecordingControllerPersistenceBoundaryTest")
    recording_migration = read_suite(root, "core/database/build/test-results/testDebugUnitTest", "RecordingRunMigrationTest")
    recording_feature = read_suite(root, "feature/home/build/test-results/testDebugUnitTest", "SessionRecordingViewModelTest")
    # Receipt/source handoff and inactive collector ownership are separate JVM boundaries.
    finished_exact = read_suite(root, "feature/home/build/test-results/testDebugUnitTest", "SessionFinishedReviewViewModelTest")
    finished_repository = read_suite(root, "core/database/build/test-results/testDebugUnitTest", "RoomPracticeSessionRepositoryTest")
    parking_guard = read_suite(root, "core/domain/build/test-results/test", "DrivingRestrictionsTest")
    supporting = {
        "finished_review": [finished_exact, finished_repository, parking_guard],
        "session": [session_intents, session_declarations],
        "map": [map_input],
        "recording": [recording_repository, recording_commit_boundaries, recording_migration, recording_feature],
    }
    # Preserve the replaced observation case's regressions without adding a fifth README case.
    legacy_observation = read_suite(root, "core/database/build/test-results/testDebugUnitTest", "RoomVehicleObservationRepositoryTest")
    legacy_migration = read_suite(root, "core/database/build/test-results/testDebugUnitTest", "VehicleObservationMigrationTest")
    # The former parking case remains a legacy context for the same receipt, not an additive total.
    legacy_supporting = {"observation": [legacy_observation, legacy_migration], "parking": [parking_guard]}
    output = {
        "cases": cases,
        "case_supporting_suites": supporting,
        "legacy_regression_supporting_suites": legacy_supporting,
        "scope": (
            "Existing JUnit XML receipts only; reported timestamps may precede this invocation, and cached/up-to-date results "
            "are not fresh runs. No combined test total is computed; shared supporting/legacy receipts are not additive. "
            "FinishSourceReviewBoundaryTest uses real Home/History Compose with controlled lifecycle, source and P/UX, "
            "not actual SDK or production Room. FinishSourceReviewRoomFlowTest uses production repositories in a UUID-owned "
            "schema6 store with controlled source/ACK delivery; it does not prove an internal Room dispatcher defect. "
            "FinishedSessionReviewFlowTest actual Hilt Main/SDK/Room, theme, lifecycle and original-data preservation runs "
            "are opt-in and tracked separately, including failed or incomplete attempts. These JVM receipts do not prove "
            "app SDK or native screen success. Controlled BackgroundRecordingBoundaryTest uses real "
            "Compose with a synthetic LifecycleRegistry, source, controller and gate; it does not prove OS Activity "
            "transitions, actual SDK receipt or native foreground-service success. Actual BackgroundVehicleRecordingFlowTest "
            "Main STOP/native FGS/SDK and external process interruption/recovery runs are opt-in and tracked separately, "
            "including failed and intentionally aborted attempts. Its system-launcher navigation handoff does not prove "
            "the app Navi SDK request succeeds. SessionDeletionFlowTest, SessionDeletionSdkFlowTest, MapInputBoundaryTest "
            "and MapInputLifecycleTest native runs are also tracked separately. Not SDK collection, performance or driver-outcome evidence."
        ),
    }
    print(json.dumps(output, ensure_ascii=False, indent=2))
    required = [
        *cases.values(),
        *(suite for suites in supporting.values() for suite in suites),
        *(suite for suites in legacy_supporting.values() for suite in suites),
    ]
    return 0 if all(row["status"] == "PASSED" for row in required) else 1


if __name__ == "__main__":
    sys.exit(main())
