# 지난 연습의 저장된 배경 기록 실행

종료한 연습 이력에 ‘배경 기록 실행’ 영역을 별도로 연결했다. 선택한 연습에 실제 저장된 실행 목록, 상태와 앱의 마커 시각을 읽는다. 최종 제품 `c226c569ab963939d54b992e5e039a041fb4cc18`에서 자동 검사, 제어 Compose 경계, 실제 Main/SDK/Room의 기본 라이트와 확대 다크, 기존 흐름의 전후 계측 및 새 영역의 변경 후 계측을 완료했다. [PR #67](https://github.com/HanGilLab/Android/pull/67)의 최신 커밋 CI/Codex와 관련 검증을 확인해 정상 병합했고, 실제 main 동기화와 작업 브랜치 정리를 마쳤다. [완료 계획](../plans/completed/2026-11-01-history-recording-runs.md)에 이 독립 구현 범위의 근거를 기록한다.

## 제품 공백과 읽기 범위

이전 이력은 차량 관찰, 과제 요약과 메모를 읽었지만 배경 실행 저장소의 STOPPED/INTERRUPTED 근거를 보여 주지 않았다. 현재 연습에 남는 중단 경고도 종료한 연습을 복습할 때 확인할 수 없었다. 새 영역은 과제 요약과 버튼 시각 뒤, 삭제 행동 앞에 독립적으로 배치한다. 사용자가 해당 영역을 펼친 동안만 실행 저장소를 읽으며 기존 차량 관찰, 과제 요약과 메모의 행동, tag와 조회 경로는 유지한다.

[SessionHistoryViewModel](../../feature/home/src/main/kotlin/io/github/hangillab/hangil/feature/home/SessionHistoryViewModel.kt)이 domain의 [RecordingRunRepository](../../core/domain/src/main/kotlin/io/github/hangillab/hangil/core/domain/RecordingRunRepository.kt)를 주입받아 [별도 reader](../../feature/home/src/main/kotlin/io/github/hangillab/hangil/feature/home/SessionHistoryRecordingRunReader.kt)를 소유한다. [route](../../feature/home/src/main/kotlin/io/github/hangillab/hangil/feature/home/HistoryStoredRecordingRunsRoute.kt), [화면 상태](../../feature/home/src/main/kotlin/io/github/hangillab/hangil/feature/home/SessionHistoryRecordingRunUiState.kt)와 [실행 본문](../../feature/home/src/main/kotlin/io/github/hangillab/hangil/feature/home/StoredRecordingRunContent.kt)을 이름별 파일로 분리했다. feature는 domain/designsystem만 참조하며 DB 스키마, SDK, 권한과 API는 변경하지 않았다.

읽기 경로는 저장소의 observe만 호출한다. 기록 시작, 종료, 중단 복구나 관찰 쓰기를 수행하지 않는다. 저장된 실행이 없는 과거 연습도 차량 SDK 관찰을 가질 수 있다. 실행 조회 실패와 관찰 조회 실패는 독립적이며 어느 쪽도 다른 자료를 빈 값으로 바꾸지 않는다. Navigation 화면 요청 성공, 위치/경로와 실제 도로 구간은 이 기능의 전제나 검증 결과에 포함하지 않는다.

## 저장 상태와 시각의 의미

| 저장 상태/값 | 표시하는 의미 | 확인하지 않은 사실 |
|---|---|---|
| STOPPED | 종료 상태가 저장됨 | 실제 서비스 종료 확인, 연속 SDK 수집 완료 |
| INTERRUPTED | 실행 종료를 확인하지 못함 | 강제 종료/크래시/OS 종료라는 원인, 정확한 공백 |
| FAILED | 실패 상태가 저장됨 | 저장하지 않은 구체적인 실패 원인 |
| OPEN | 종료 상태가 아직 저장되지 않음 | 현재 서비스 활성 또는 수집 중이라는 단정 |
| 종료 시각 null | 실행 종료 기록의 시각 미확인 | 현재 시각이나 연습 종료 버튼 시각으로 대체 |
| 실행 목록 없음 | 저장된 배경 실행 정보가 없음 | 차량 관찰 없음, 정상 완료 또는 기록 시간 0 |

[VehicleRecordingRun](../../core/domain/src/main/kotlin/io/github/hangillab/hangil/core/domain/VehicleRecordingRun.kt)은 앱이 저장한 실행 마커다. 시작 마커는 서비스 활성화보다 먼저, 종료 상태는 native 서비스 중지보다 먼저 저장될 수 있다. UI도 ‘실행 시작 기록’과 ‘실행 종료 기록’으로 표시하고 실제 SDK 수집 기간, 연속 기록과 서비스 종료를 확인한 정보가 아니라는 안내를 유지한다. 원시 session/run/process 식별자는 표시하지 않는다.

목록은 저장소의 생성 순서를 그대로 사용한다. 벽시계가 역행해도 시작 시각이나 ID로 다시 정렬하지 않는다. 기본 선택은 마지막 저장 실행이며 사용자가 고른 실행을 유지한다. 선택한 실행이 사라지면 다른 실행으로 바꾸지 않고 실패로 남긴다. 이후 STOPPED가 저장돼도 목록의 과거 INTERRUPTED와 미확인 종료 경고는 유지한다.

SDK 관찰에는 sessionId만 있고 runId가 없다. 마커 사이의 관찰을 특정 배경 실행에 배정하거나 실행별 수집 시간, 연속성, 공백과 도로 구간을 계산하지 않는다. 기존 실제 SDK 관찰, 시연 코스와 사용자 메모의 출처도 유지한다.

## 현재 선택, host와 상세 동작

reader는 부모 history의 선택 generation, 전체 PracticeSession snapshot과 같은 선택 소유권을 사용한다. sessions를 상세에서 별도 구독해 두 번째 선택 기준을 만들지 않는다. 새 영역이 접혀 있거나 기존 영역만 열려 있으면 실행 조회가 없다. 펼친 STARTED host와 현재 실제 P 및 UX 허용이 모두 유효할 때만 cold Flow를 수집한다. 미확인, 연결 해제와 거부는 상세 허용으로 바꾸지 않는다.

source 교체/삭제, 같은 ID의 다른 snapshot, 부모 실패/취소, host STOP/폐기와 직접 확인한 D/UX 거부는 이전 reader와 동작을 폐기한다. 이후 P/RESUME으로 돌아와도 이전 동작은 재승인되지 않으며 새 펼침과 현재 receipt가 필요하다. 페이지/재시도는 표시 당시 reader/query/revision token과 실행 ID를 캡처한다. 재사용된 clickable/semantics node가 최신 callback을 따라 이전 동작을 실행하지 않도록 고정한다.

수신 목록은 불변 복사한 뒤 같은 session, 식별자의 유효성과 유일성, 시작/종료 시각 조합, OPEN/INTERRUPTED의 null 종료와 하나 이하의 OPEN을 검증한다. 손상 행을 걸러 정상 목록으로 만들지 않는다. Loading, Empty, Failed, Unavailable과 Locked를 구분하며 저장 상태 FAILED와 조회 Failed도 구분한다. 저장소 오류, 독립 취소와 유한 스트림 종료는 명시적 재시도 전까지 실패다.

## 실제 검증과 실패 이력

| 범위 | 최종 결과와 한계 |
|---|---|
| JDK17 자동 검사 | 최종 c226의 verify 4단계 통과, JVM 809개의 실패/오류/건너뜀 0, debug/unsigned release 및 native APK 빌드 통과 |
| 제어 native | 실제 Compose와 합성 lifecycle/source/저장소/gate를 사용한 6개 통과. 실제 OS PAUSE 성공이나 SDK 장애 주입으로 해석하지 않음 |
| 실제 기본 1.0/라이트 | 실제 Main, SDK P+UX와 기존 Room의 읽기 흐름 1개 통과 |
| 실제 확대 1.3/다크 | 같은 실제 읽기 흐름 1개 통과. 큰 글자, 출처, 버튼과 과거 중단 경고의 실제 이미지 직접 검토 완료 |
| 원본 및 환경 | 외부 전체 snapshot으로 schema5 자료의 전 필드/물리형/순서/sequence, grants/runtime/설정 보존 확인. 원래 준비 1, 종료 연습 13, 관찰 145, 메모 1, terminal 실행 15와 활성 연습 없음 유지 |
| 복원/정리 | 설정 복원, 최종 snapshot 대조와 이번 소유 프로세스/forward 정리 완료. 새 연습/관찰/실행/메모를 만들거나 원래 자료를 수정하지 않음 |

[제어 검증](../../app/src/androidTest/kotlin/io/github/hangillab/hangil/SessionHistoryRecordingRunBoundaryTest.kt)은 옛 펼침/페이지/재시도, source 교체, 합성 lifecycle과 silent P/UX 변경을 재생한다. 실제 Activity/Compose는 사용하지만 lifecycle 정책 입력은 명시적으로 제어한다. [실제 읽기 흐름](../../app/src/androidTest/kotlin/io/github/hangillab/hangil/SessionHistoryRecordingRunFlowTest.kt)은 모든 종료 연습의 저장 실행을 순서대로 열람하고, 구형 무마커 자료와 SDK 관찰의 공존, 재생성 뒤 접힘, 실제 DDS D 잠금과 P 복귀 뒤 새 펼침을 확인한다. 실제 SDK 수신과 합성 경계 검증을 서로 대신하지 않는다.

최초 포맷 실패와 후속 setup 관찰 보완의 import 누락은 빌드/하네스 실패로 보존했다. 최초 라이트는 같은 display의 focused package가 미확인이어서 본문 진입 전 중단됐다. 기대 package나 기본 display를 대입하지 않고 Main RESUMED와 유효한 같은 display를 유지한 채 null만 제한 시간 동안 다시 관찰하도록 보완했다. 알려진 다른 package는 실패로 유지한다.

본문 진입 뒤 출처 배지의 TextLayout 넘침도 실제 실패로 보존했다. 새 실행 출처와 본문을 가용 폭 전체에 배치하고 출처를 bodyLarge 24sp로 표시해 글자를 줄이지 않는 구성을 선택했다. 이 수정은 표시 정확성/가독성 보완이며 성능 개선 수치가 아니다. 이전 다크 실행의 OEM 오버레이/전면 setup 중단은 최종 c226의 라이트/다크 성공과 분리한다. 해당 초기 실패를 SDK/DB 오류로 확정하거나 최종 성공 표본에 합치지 않았다. 실패 원형과 비공개 진단/이미지는 유지한다.

## 측정 계약과 원값

[익명 timing 원값 175행](measurements/history-recording-runs-ui-times.csv)은 M0 전후 각 5warm/30본, M1 전후 각 5warm/30본과 M2 변경 후 5warm/30본이다. 통계에는 각 30개 본 표본만 사용한다. 정수 ns에서 ms를 정확히 유도했으며 Q1/Q3는 정렬한 하위 15개와 상위 15개의 중앙값, IQR은 Q3−Q1이다. 다섯 보고서 모두 완료이며 실패/누락/제외 0이다. 느린 성공 표본을 제외하지 않았다.

기준 제품은 `4838db6436d745e3df5c64aadb1b800adc08e134`, 비교 제품은 `c226c569ab963939d54b992e5e039a041fb4cc18`이다. M0/M1은 전후에 고정 하네스 `9855a6c65fbfec3f8cd5c336f104a912ded6e17e`와 동일 instrumentation APK를 사용했다. 9855는 기준 실행 당시 로컬 원본 커밋이며 이후 공개 전 브랜치 rebase로 공개 SHA가 달라졌다. 보고서의 실제 9855 선언을 새 SHA로 바꾸지 않았고 접근할 수 없는 GitHub 커밋 링크도 만들지 않는다. 실제 frozen APK와 두 계측 파일의 바이트 hash를 비공개로 보존했으며 현재 저장소의 두 하네스 파일도 그 hash와 같다. 아래 하네스 링크는 현재 소스를 가리키고 기준 실행의 원본 식별과 구분한다. 원본 baseline 파일의 바이트 SHA256, originalData, full fixedSource, 생성 순서, catalog, environment와 해당 measurementContract가 일치한다. M0/M1 비교와 M2의 실제 제품 APK도 같다. M2는 c226의 별도 새 하네스를 사용하며 이전 계측 APK와 같다고 요구하지 않는다. 선언 SHA와 실제 APK hash의 연결 근거는 비공개 실행 자료에 보존한다.

각 단계는 JDK17/debug/Pleos API34/font 1.0/라이트, 같은 원본과 가용 앱 영역, 실제 Main 전면/RESUMED와 SDK P+UX에서 새 프로세스/Activity로 시작했다. 캐시/자료를 삭제하지 않고 5회 워밍업 뒤 같은 프로세스에서 30회 실행했다. 확대 다크 correctness 표본은 이 timing에 합치지 않는다.

| 지표 | 측정한 행동 | timing 범위 |
|---|---|---|
| M0 기존 이력 진입 | home_history 클릭 → 같은 source의 history_ready와 완전히 보이는 history_course, Compose idle와 재확인 | 기존 공통 이력 진입의 계측 wall time |
| M1 기존 과제 요약 펼침 | history_summary_tasks 클릭 → 완전히 보이는 history_summary_scope, Compose idle와 재확인 | 기존 tasks 펼침의 계측 wall time. SDK 관찰이나 새 실행 조회 시간이 아님 |
| M2 새 실행 정보 | 새 영역 클릭 → 정확한 최신 실행 값, 정해진 종료 마커 스크롤, 실제 가시성/Compose idle와 값 재확인 | 새로운 실행 읽기/표시 흐름의 변경 후 wall time |

M0/M1은 [고정 진입](../../app/src/androidTest/kotlin/io/github/hangillab/hangil/SessionHistoryOpeningMeasureTest.kt)/[고정 과제 요약](../../app/src/androidTest/kotlin/io/github/hangillab/hangil/SessionHistoryDisclosureMeasureTest.kt)의 같은 endpoint다. 두 계측 동안 새 실행 영역은 접혀 있고 실행 reader도 없다. 클릭 전달, Compose 동기화와 semantics 검사를 포함하며 사전 스크롤, SDK/source/자료 검사, JSON, 닫기와 프로세스 시작은 고정 계약대로 제외한다. 순수 DB query, frame/GPU 시간이나 사용자 속도를 측정한 값이 아니다.

[M2](../../app/src/androidTest/kotlin/io/github/hangillab/hangil/SessionHistoryRecordingRunMeasureTest.kt)는 클릭 전달, 실제 실행 읽기와 값/receipt 검사, 종료 마커 스크롤, Compose idle, geometry, 현재 P+UX 및 focused window/Main/display 검사를 포함한다. 이력 열기와 사전 이동, bulk 원본/source/catalog/environment 대조, JSON과 종료 정리는 제외한다. 유효 endpoint는 동기 blocking까지 포함한 실제 20초 한계를 통과해야 한다. 실패 latency는 null로 유지하고 실제 실패 대기 시간과, 후속 실패 전에 관찰한 유효 endpoint 시간은 별도 필드로 보존한다.

## 관측 결과와 해석 한계

| 지표/단계 | warm/본 | 중앙값 ms | Q1 / Q3 ms | IQR ms | 최소 / 최대 ms | 실패/누락/제외 |
|---|---:|---:|---|---:|---|---|
| M0 변경 전 | 5/30 | 274.4009795 | 258.441 / 293.113959 | 34.672959 | 228.448666 / 352.68175 | 0/0/0 |
| M0 변경 후 | 5/30 | 271.976 | 257.854125 / 309.413625 | 51.5595 | 239.207625 / 369.236625 | 0/0/0 |
| M1 변경 전 | 5/30 | 169.5276255 | 168.419291 / 170.792041 | 2.37275 | 150.64725 / 174.092375 | 0/0/0 |
| M1 변경 후 | 5/30 | 170.241938 | 168.123042 / 172.867 | 4.743958 | 166.521166 / 300.332334 | 0/0/0 |
| M2 변경 후만 | 5/30 | 788.235563 | 780.789167 / 818.309458 | 37.520291 | 749.602959 / 877.812959 | 0/0/0 |

M0 중앙값의 관측 차이는 −2.4249795ms/−0.88373573%, M1은 +0.7143125ms/+0.42135463%다. 상대 차이는 (변경 후−변경 전)/변경 전이며 양수는 더 긴 시간이다. 두 전후 Q1/Q3 구간은 겹친다. 이 값으로 성능 향상, 인과적인 성능 저하, 동등성이나 무회귀를 확정하지 않는다.

실행 순서는 A1→B이며 A2 기준 재측정은 하지 않았다. OS scheduling, 캐시, 프로필/ART/JIT, 발열과 시간/순서 효과를 원인별로 분리하지 못한다. 같은 입력/환경과 계측 APK가 이러한 차이를 제거했다는 뜻은 아니다. release, 다른 기기, 콜드 실행이나 사용자 성과로 일반화하지 않는다.

M2에는 변경 전 대응 행동이 없다. 변경 전을 0으로 두거나 이전 실패 대기 시간을 기준값으로 삼지 않으며 전후 개선율도 계산하지 않는다. 788.235563ms는 위에 정의한 새 동작의 계측 후 특성이다. M0/M1과 다른 endpoint이므로 세 값의 차이를 조회 오버헤드나 가속으로 해석하지 않는다.

## 재현 전제와 남은 제품 범위

외부 실행자가 원래 DB 전체/물리형/순서/sequence, 권한/runtime과 설정을 먼저 보존하고 활성 연습/FGS 없음, 실제 Main 전면과 SDK P+UX를 확인해야 한다. 원형 baseline, 실패 보고서와 AtomicFile sidecar를 덮어쓰지 않는다. 원문 fixture, 식별자, 환경/로그와 개발 중 이미지는 공개하지 않는다.

- M0: manualHistoryOpeningMeasure=true, measurementSeries=history-recording-runs-entry-v1, measurementStage=baseline|comparison, 실제 완전 SHA의 declaredProductCommit/declaredHarnessRevision.
- M1: manualHistoryDisclosureMeasure=true, measurementSeries=history-recording-runs-tasks-v1과 같은 stage/provenance 전제.
- 실제 읽기: manualHistoryRecordingRunFlow=true와 expectedFontScale=1.0/expectedDark=false 또는 1.3/true. 외부 DDS 단계는 HISTORY_RECORDING_RUN_AWAIT_D/P이며 실제 종료 표식은 HISTORY_RECORDING_RUN_COMPLETED.
- 제어 경계: manualHistoryRecordingRunBoundary=true. 실제 Compose와 제어 lifecycle/source/저장소/gate의 범위로 표시한다.
- M2: manualHistoryRecordingRunMeasure=true, measurementStage=after, expectedFontScale=1.0/expectedDark=false와 실제 완전 product/harness SHA. 종료 표식은 HISTORY_RECORDING_RUN_M2_AFTER_COMPLETED.

로컬 제품/계측/보존 검증과 최신 PR67의 CI/Codex, 정상 병합/동기화/작업 브랜치 정리를 완료했다. 이 계획은 저장 실행 마커의 독립 읽기 연결 범위만 완료로 옮겼다. APP-05/APP-06 전체, Navigation API 전면 전환의 모든 성공, 실행별 관찰 소속, 연속 수집과 실제 위치/도로 구간의 미검증도 유지한다.
