# 선택한 지난 연습의 저장 메모

기존 이력에서 선택한 종료 기록의 메모를 읽는 독립 경계를 연결했다. 제품7017ddf5079582df6b823be42602bc17b5583f7d의 로컬 자동/제어/실제 검증과 기존 진입 비용의 전후 계측은 완료했다. 최신 PR의 CI/Codex와 정상 병합은 별도 대기다. [작업 계획](../plans/active/2026-10-31-history-stored-memo.md)에 완료 조건과 최초 실패를 관리한다. 전체 APP-06/APP-08, 지도 구간 복습과 실제 도로 검증의 완료를 뜻하지 않는다.

## 제품 동작과 선택

과거에는 홈의 최신 종료 기록만 저장 메모를 읽을 수 있었다. 기존 sessionId별 Room 계약을 사용해 지난 연습에서 직접 선택한 종료 기록의 저장 메모를 주차 상세로 펼친다. 최신 종료 기록의 입력/수정/지우기 계약은 그대로 두고, 과거 메모에는 편집/저장/삭제 버튼을 추가하지 않는다. 스키마/권한/위치/내비/SDK 수집도 바꾸지 않는다. 사용자 메모 출처는 SDK 관찰이나 시연 코스 출처와 다르게 표시한다.

- [SessionHistoryViewModel](../../feature/home/src/main/kotlin/io/github/hangillab/hangil/feature/home/SessionHistoryViewModel.kt)은 기존 부모 receipt lock, reader/query/generation, 전체 PracticeSession snapshot에 상세 Binding을 고정한다. 별도 sessions 구독이나 최신 종료 기록으로의 자동 대체는 없다.
- [SessionHistoryMemoReader](../../feature/home/src/main/kotlin/io/github/hangillab/hangil/feature/home/SessionHistoryMemoReader.kt)는 펼친 동안만 cold query를 만든다. readerId/queryNonce/revision의 당시 token, 부모 원본, STARTED 호스트와 실제 P/UX 허용을 재확인한다. 새 수신/실패는 revision을 바꾸고 재시도는 당시 실패 token만 받는다.
- 부모 source/선택 변경/삭제/실패와 STOP/닫기는 옛 query를 취소한다. 현재 token이 직접 D/UX 거부를 관찰하면 기존 부모 denied-query 경로도 폐기한다. 통지 없는 P나 같은 ID의 새 snapshot이 옛 callback을 재승인하지 않는다. 취소를 무시한 늦은 결과도 새 reader에 전달하지 않는다.
- [HistoryStoredMemoRoute](../../feature/home/src/main/kotlin/io/github/hangillab/hangil/feature/home/HistoryStoredMemoRoute.kt)는 lifecycle/원본/generation/token이 모두 유효할 때만 원문을 표시한다. 실제 null만 부재이며 잘못된 sessionId/빈 version/유효하지 않은 Unicode나 원문, 오류/독립 취소/정상 stream 종료는 terminal 실패다. 재시도는 사용자 명시 동작만 허용한다. 정상 원문은 줄바꿈/공백을 보존하고 정제한 대체 문장으로 바꾸지 않는다.

## 실제 검증과 자료 보존

| 구분 | 실제 실행 결과와 한계 |
|---|---|
| 자동 | JDK17.0.21+9의 전체 verify4단계, 46suites689 JVM 실패/오류/건너뜀0. 새 메모 JVM35개 포함. debug/unsigned release/계측 APK 통과 |
| 최초 서식 | 긴 선언/복수 문장 자동 교정 순서와 진단 실행 준비 오류를 별도 보존. 규칙 완화 없이 같은 ktlint/원래 Spotless로 정리 후 통과. 앱/SDK 실패와 구분 |
| 최초 제어 native | 확대 다크1FAIL/7PASS. 첫 메서드 정리의 RESUMED 대기/STOPPED와 이후 기본 내비/카메라 전면 관찰. 정리가 앞선 예외를 가렸을 가능성을 배제하지 못하며 원인/해결을 확정하지 않음 |
| 외부 전면 복원 시도 | exported=false catalog Activity의 외부 시작이 거부됨. 복원 성공으로 세지 않음 |
| 최종 제어 native | 앱 force-stop 뒤 같은 소스/APK의 새 프로세스에서8PASS/건너뜀0. 새 메모4개/기존 관찰3개/기존 요약1개. 합성 세션/P/UX/메모/실패/옛 callback이며 실제 SDK 오류 주입 아님 |
| 실제 검사 소유 seed | 기존 실제 홈 UI로 종료 세션2개/일반 검사 문장 메모1개를 추가, 해당 세션에 실제 SDK P 관찰2개 저장.1PASS. 전후 계측을 모두 마친 뒤에만 추가 |
| 실제 기본1.0/라이트 | Main/SDK/Room1PASS/건너뜀0. 모든11개 종료 기록의 선택/부재/양성 원문/사용자 출처, 닫기/재진입/재생성/STOP-START, 실제 DDS ONLINE D 잠금/P복귀와 fresh 펼침 |
| 실제 확대1.3/다크 | 같은 실제 범위1PASS/건너뜀0. 각 테마5개 비공개 PNG에서 부재/원문/잠금/복귀/재진입을 직접 검토, glyph/ellipsis/화면 경계와 버튼 도달 검사 |

처음 입력은 준비1/종료9/관찰15/메모0이었다. 양성 메모를 가진 과거 기록이 없어서 계측 뒤 검사 소유 세션을 홈에서 시작/종료하고 메모를 저장한 뒤 두 번째 세션을 시작/종료했다. 기존 준비/원래 행은 수정하지 않았고 소유 표식은 중복 생성을 막는다. 검사 문장은 사용자 능력/실제 도로/주행 성과의 근거가 아니다. 최종 자료는 준비1/종료11/관찰17/메모1이며 소유 추가를 원래 DB와 동일하다고 부르지 않는다.

외부 seeded 대조기는 최초에 practice_session의 autoGenerate rowId sequence9→11을 예상 목록에 빠뜨렸다. 이는 로컬 검사 누락이며 제품 자료/sequence를 바꾸지 않고 대조기를 정정했다. 원래 모든 행/값/물리 자료형/순서와 권한/설정 보존, 소유 종료2/메모1/SDK관찰2와 session sequence9→11/관찰 sequence15→17만 증가했음을 재대조했다. 실제 시나리오 전후11/17/1 자료는 읽기만 수행해 정확히 같았다. 최종 외부 SQLite/sequence/grants/runtime/font/night/IME/proxy가 기대 snapshot과 정확히 일치했고 원래 환경을 복원한 뒤 이번 소유 AVD/forward/DDS 탭만 종료했다.

실제 SDK의 강제 늦은 callback/독립 UX 오류 주입, 새 권한/위치/내비와 실제 도로는 미실행이다. JVM/제어 입력의 손상 메모/무방출 D/P/늦은 결과를 실제 SDK 장애 재현으로 바꾸지 않는다.

## 기존 이력 진입의 전후 관찰

제품 변경 전에 계획의 병목/가설/지표/조건/제외 기준을 고정했다. 관찰한 문제는 과거 메모 경로 부재이며 속도 병목은 관찰하지 않았다. 새 열람 기능에는 이전의 같은 동작이 없어 메모 펼침 속도 개선율을 계산하지 않는다. 기존 접힌 이력 진입 비용만 같은 endpoint로 관찰한다.

고정 지원044ad28799307d24771e96daa049e650561786f0의 history-memo-entry-v1 격리 계열과 계측 소스 SHA를 양쪽에서 그대로 사용했다. 조건은 JDK17.0.21+9/debug/Pleos API34/font1.0/라이트/같은 현재 사용자/원래 준비1/종료9/관찰15/메모0/같은 카탈로그/실제 SDK P+UX 허용이다. 양쪽 실제 설치 APK hash와 fixedSource/originalData/catalog/environment/measurementContract의 일치를 확인했다.

각 단계 새 프로세스/Activity에서5회 warm 뒤 같은 프로세스의30회 이력 열기/닫기를 실행했다. timer는 home_history click 직전부터 같은 Ready/원문/Compose idle endpoint까지 elapsedRealtimeNanos다. 클릭 dispatch/검사/동기화를 포함하며 앱 전용 frame/GPU/SDK 지연이나 운전자 행동 시간은 아니다. 시작/사전 스크롤/닫기/원형 export/캡처/사후 자료 대조는 timer 밖이다. 고정20초 endpoint와 원본/P/UX/입력 확인 실패를 실패로 정하고 느린 값을 제외하지 않았다.

[70개 익명 원값](measurements/history-memo-entry-ui-times.csv)은 각5warm+30본 표본의 정수 ns와 정확히 유도한 ms를 순서대로 보존한다. 통계는30개 본 표본만 사용하고 Q1/Q3는 정렬한 하위15/상위15의 중앙값이다.

| 지표 | 변경 전 | 변경 후 |
|---|---:|---:|
| 제품 | 74be91fbebcbf96fba6f479e45efa453955e0f86 | 7017ddf5079582df6b823be42602bc17b5583f7d |
| warm/본 표본 | 5/30 | 5/30 |
| 중앙값(ms) | 259.743292 | 259.953500 |
| Q1/Q3(ms) | 245.792167/293.151833 | 243.818167/289.70075 |
| IQR(ms) | 47.359666 | 45.882583 |
| min/max(ms) | 222.643958/323.701 | 224.571125/325.539958 |
| 실패/누락/제외 | 0/0/0 | 0/0/0 |

관찰된 차이는 +0.210208ms/+0.08092913521708965%이며 IQR이 겹친다. B1→F1 순차이고 데이터/Tile/DB/코드 캐시를 지우지 않았다. APK 교체/ART/profile/JIT/OS/발열/순서/네트워크 영향을 분리하지 못해 향상/인과 저하/동등성/무회귀를 모두 확정하지 않는다. release/다른 기기/콜드 실행/메모 펼침으로 일반화하지 않는다. JUnit/CI/Gradle 시간과 검사 수/보존 행 수는 성능 수치가 아니다. 원형 JSON/APK hash/개인 환경/원문/복구 기록은 ignored 비공개 자료로 보존한다.

## 재현 전제와 옵션

1. JDK17 전체 verify와 계측 APK를 먼저 빌드하고 무거운 Gradle 종료 뒤 실제 시나리오를 시작한다. 외부 실행자가 기존 AVD/원래 DB 전체/물리형식/권한/설정을 비공개로 보존하고 활성 세션이 없음을 확인한다. 실제 전면 앱과 DDS 브리지/SDK P+UX를 확인하며 HTTP200이나 빈 UI 계층만으로 성공을 판단하지 않는다.
2. [SessionHistoryOpeningMeasureTest](../../app/src/androidTest/kotlin/io/github/hangillab/hangil/SessionHistoryOpeningMeasureTest.kt)에 manualHistoryOpeningMeasure=true, measurementSeries=history-memo-entry-v1, measurementStage=baseline|comparison, 실제 완전 SHA인 declaredProductCommit과 declaredHarnessRevision=044ad28799307d24771e96daa049e650561786f0을 명시한다. 원래9/15/0 입력에서 먼저 양쪽을 끝내고 완료한 원형은 덮어쓰지 않는다. 소유 자료 추가 뒤11/17/1을 같은 입력인 것처럼 섞지 않는다.
3. [SessionHistoryStoredMemoFlowTest](../../app/src/androidTest/kotlin/io/github/hangillab/hangil/SessionHistoryStoredMemoFlowTest.kt)의 createOnlyOwnedPastMemoAfterComparisonMeasurements는 manualHistoryMemoSeed=true/measurementComparisonCompleted=true에서만 소유 자료를 만든다. 외부 runner도 양쪽 완료를 확인해야 한다. 기존 소유 표식이 ready이면 보존을 대조하고 재생성하지 않는다. 사용자 자료 편집/삭제나 임의 sequence 복원은 하지 않는다.
4. 같은 파일의 storedPastMemoRemainsReadOnlyAcrossSelectionLifecycleAndActualDrivingLock은 manualHistoryStoredMemo=true와 expectedFontScale=1.0, expectedDark=false 또는1.3/true로 명시 실행한다. HISTORY_MEMO_AWAIT_D/P 단계에서 실제 브라우저 DDS D/P를 조작하고 SDK 수신을 확인한다. 기본 발견에서 opt-in skip은 성공이 아니다.
5. [SessionHistoryMemoBoundaryTest](../../app/src/androidTest/kotlin/io/github/hangillab/hangil/SessionHistoryMemoBoundaryTest.kt)는 manualHistoryMemoBoundary=true로4개 합성 경계를 실행한다. 기존 관찰/요약은 각각 manualHistoryObservationBoundary=true/manualHistoryDisclosureBoundary=true다. 합성 자료와 실제 원래 Room/SDK를 구분한다.
6. 마지막 domain snapshot 외에 외부 SQLite/sequence/권한/설정을 별도로 대조하고 원래 환경을 복원한다. 원래 자료와 명시적 소유 추가를 분리해 보존하고 이번 소유 AVD/forward/탭만 정리한다.

README는 실제 기능 표의 한 문장과 유효한 소스 링크만 갱신했다. 기존4개/지정 여섯 항목/17개 펼친 실제 코드/18개 기존 이미지는 유지한다. 이번 읽기 연결은 기존1번/3번의 경계를 확장하지만 더 강한 독립 원인/영향/성능 개선을 입증하지 않아 기존 사례를 대체하지 않는다. 문서 정리는 런타임 성능 측정 적용 제외이며 링크/발췌/렌더/공개 콘텐츠로 검증한다.
