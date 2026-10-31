# 지난 연습의 배경 기록 실행 근거 연결

- 상태: 진행 중. 실제 M0/M1 기준 확보 뒤 제품 연결과 검증 소스를 준비했고 root의 feature JVM 검사는 통과했다. 전체 검증/APK/native/비교/M2는 미실행이다.
- 요구사항: APP-05 저장 실행 근거를 APP-06의 종료한 연습 이력에 연결하는 독립 읽기 범위
- 관련 근거: [배경 차량 기록 계약](../../development/background-vehicle-recording.md), [선택한 지난 연습의 저장 차량 관찰](../../development/history-stored-observations.md)
- 시작 제품: `4838db6436d745e3df5c64aadb1b800adc08e134`, 고정 M0/M1 하네스 `9855a6c65fbfec3f8cd5c336f104a912ded6e17e`. root가 실제 설치 APK/hash와 원본 manifest를 고정했다. 구현 담당은 Git이나 기기를 조작하지 않았다.

## 목표와 범위

종료한 연습 이력에 별도 ‘배경 기록 실행’ 접힘 영역을 추가한다. 사용자가 이 새 영역을 명시적으로 열면 해당 연습에 실제 저장된 실행 목록과 상태, 앱이 저장한 시작 및 종료 시각을 확인한다. 기존 차량 관찰, 과제 요약과 메모 펼침의 본문, 조작, tag와 조회 계약은 바꾸지 않는다. 이미 표시하는 SDK 관찰의 출처, 저장 순서, 앱 관찰 시각과 값도 보존한다. 원시 session/run/process 식별자는 제품 UI에 표시하지 않는다.

작업 전 4838의 [SessionHistoryViewModel](../../../feature/home/src/main/kotlin/io/github/hangillab/hangil/feature/home/SessionHistoryViewModel.kt)은 세션, 관찰, 메모를 읽었지만 실행 저장소를 받지 않았다. [HistoryStoredObservationRoute](../../../feature/home/src/main/kotlin/io/github/hangillab/hangil/feature/home/HistoryStoredObservationRoute.kt)는 선택 source와 generation에 묶인 관찰만 표시했다. 중단 경고는 현재 연습의 BackgroundRecordingRoute에만 있어 종료한 연습의 복습에서 찾을 수 없었다. 이 공백은 정적 코드로 확인했으며 느린 조회나 렌더 병목은 관찰하지 않았다.

새 접힘 영역은 HistoryRecord의 기존 과제 요약과 버튼 시각 패널 뒤, 삭제 행동 앞의 형제로 추가한다. 기존 펼침 안에 실행 reader를 넣지 않으며 기존 영역을 여는 행동으로 실행 저장소를 조회하지 않는다. 새 영역을 닫으면 실행 reader도 폐기한다. 관찰이 비었거나 관찰 조회가 실패해도 새 영역에서 실행 정보를 독립적으로 읽을 수 있어야 한다. 실행 조회 실패가 기존 관찰, 과제 요약이나 메모를 지우거나 빈 결과로 바꾸어서는 안 된다.

Navigation SDK 화면 요청, 목적지와 경유지, 길안내, 위치, 지도와 실제 도로 구간은 이 작업에 포함하지 않는다. 이력 읽기에서 RecordingController, recoverInterrupted, 기록 서비스나 append/finish를 호출하지 않는다. APP-05/APP-06 전체 완료, 연속 수집 또는 운전자 성과로 확대하지 않는다. 기존 메모, 삭제, 카탈로그와 준비 자료의 계약은 유지한다.

### 표시 의미

| 저장 근거 | 제품 표현의 의미 | 금지할 해석 |
|---|---|---|
| STOPPED | 실행 종료 상태가 저장됨 | 실제 서비스 종료까지 확인됨, 연속 SDK 수집 완료 |
| INTERRUPTED | 이전 실행의 종료를 확인하지 못함. 종료 시각은 미확인 | 강제 종료/앱 크래시/OS 종료라는 원인 확정, 복구 시각을 종료로 표시 |
| FAILED | 실행 실패가 저장됨 | 저장하지 않은 구체적인 실패 원인 추정 |
| OPEN | 종료 상태가 아직 저장되지 않음 | 지금 수집 중 또는 서비스가 현재 활성이라는 단정 |
| terminal의 종료 시각 null | 종료 시각 미확인 | 0, 현재 시각, 연습 종료 버튼 시각으로 대체 |
| 실행 목록이 비어 있음 | 저장된 배경 실행 정보가 없음 | SDK 관찰도 없음, 정상 완료, 기록 시간 0 |

[VehicleRecordingRun](../../../core/domain/src/main/kotlin/io/github/hangillab/hangil/core/domain/VehicleRecordingRun.kt)은 앱 실행의 마커다. 시작은 서비스 활성화 전에 저장될 수 있고 종료 상태는 native 서비스 중지보다 먼저 저장된다. ‘실행 시작 기록’, ‘실행 종료 기록’과 실제 관찰 시각을 구분하는 짧은 안내를 둔다. 문구는 구현 전에 위 의미와 대조한다.

실행 목록은 [RecordingRunRepository](../../../core/domain/src/main/kotlin/io/github/hangillab/hangil/core/domain/RecordingRunRepository.kt)의 생성 순서를 유지하고 벽시계로 다시 정렬하지 않는다. 이후 STOPPED가 생겨도 이전 INTERRUPTED와 미확인 종료를 숨기지 않는다. 목록에 모든 실행을 보존하고 길면 한 실행씩 순서대로 열람하는 방식을 우선 검토한다. 페이지 동작은 표시 당시 run과 receipt에 고정한다.

SDK 관찰은 sessionId만 갖고 runId를 갖지 않는다. 실행 시각 사이에 있는 관찰을 해당 배경 실행의 관찰로 배정하거나 수집 공백, 주행 시간과 구간을 계산하지 않는다. 원래 시연 코스 배지, SDK 관찰, 사용자 메모도 서로 바꾸어 부르지 않는다.

### 구현 경계

- feature:home에 별도 SessionHistoryRecordingRunReader, UiState와 표시 Composable을 이름별 파일로 둔다. 기존 SessionHistoryViewModel이 domain RecordingRunRepository를 주입받아 부모 선택 receipt와 연결한다. SessionHistoryScreen에는 새 sibling disclosure만 추가하고 기존 관찰/과제 요약/메모 route와 본문은 수정하지 않는다.
- 기존 reader의 full PracticeSession snapshot, reader/query 수명, history generation, STARTED host와 최신 P+UX 허용을 재사용한다. sessions를 새 reader에서 따로 구독해 두 번째 선택 기준을 만들지 않는다.
- 새 영역을 펼친 동안만 cold Flow를 수집한다. 기존 관찰/과제 요약/메모만 열거나 새 영역이 접혀 있으면 실행 조회는 없다. source 변경/삭제, 같은 ID의 다른 snapshot, 부모 실패/취소, STOP/폐기와 직접 관찰한 D/UX 거부가 옛 reader와 동작을 영구 폐기한다. P나 RESUME 이후에는 새 펼침과 새 receipt가 필요하다.
- Loading, Empty, Failed, Unavailable, Locked와 Ready를 구분한다. 잘못된 session, 중복/빈 식별자, 잘못된 상태/시각 조합을 정상으로 보정하거나 행을 걸러 숨기지 않는다. 저장소 예외와 유한 스트림 완료는 명시 재시도 전까지 실패로 남긴다.
- feature는 core:domain과 core:designsystem만 사용한다. 기존 Room observe와 DI binding을 재사용하며 새 DB 테이블, migration, 권한이나 모듈을 추가하지 않는다.
- 삭제 confirmation은 이미 전체 recordingRuns를 비교하고 FK cascade를 갖는다. 새 조회는 이 계약을 바꾸지 않으며 삭제/선택 교체 때 이전 실행 상세를 무효화한다.

## 완료 조건

- [x] 기준 제품, 고정 측정 하네스, 동일 종료 연습 fixture와 실행 조건을 완전한 SHA/hash 및 비공개 manifest로 확정하고 production 변경 전에 기준 M0/M1을 실제 측정한다.
- [ ] 별도 배경 기록 실행 펼침에서 선택한 종료 연습의 실제 저장 실행 목록만 표시하고 원시 식별자를 노출하지 않는다. 기존 관찰/과제 요약/메모 펼침을 여는 동작과 본문은 유지한다.
- [ ] 네 상태, nullable 종료 시각, 구형 무마커 자료를 위 의미대로 구분하고 과거 중단을 새 STOPPED로 가리지 않는다.
- [ ] 실행 순서와 SDK 관찰 원문/순서를 보존한다. 관찰과 실행을 시각으로 연결하거나 수집 범위/주행을 추정하지 않는다.
- [ ] 새 영역을 펼치기 전/폐기 후 및 기존 영역만 열 때 실행 조회가 없고 source/host/P+UX/receipt 경계를 통과한 현재 동작만 읽기와 페이지/재시도를 수행한다.
- [ ] 조회 오류, 스트림 종료와 손상 입력이 Empty 또는 정상 상태로 바뀌지 않는다. 실행 조회 실패와 기존 관찰 조회는 서로 독립적이다.
- [ ] 관련 JVM/Room 계약과 제어 Compose 경계를 확인하고 실제 Main/SDK gate/Room의 기본 1.0 라이트 및 확대 1.3 다크에서 표시, 스크롤, D/P 잠금과 새 펼침을 확인한다.
- [ ] 동일 하네스와 fixture로 변경 후 M0/M1을 측정하고 M2는 변경 후 특성으로 별도 기록한다. 원값과 실패를 보존하고 성능 향상/동등성은 근거가 있을 때만 판단한다.
- [ ] 기존 준비, 세션, 관찰, 메모, 실행과 sequence/물리형, 권한/runtime/설정을 외부 exact 대조로 보존한다. 이번 작업은 읽기만 하며 실제 자료를 새 fixture로 교체하지 않는다.
- [ ] JDK17 전체 verify, 관련 실제 검증과 최신 PR 리뷰를 완료하고 결과와 미확인 범위를 기록한다. root가 병합 및 전체 완료를 별도로 판정한다.

## 구현 단계

1. 별도 실행 disclosure, 상태 표현과 기존 M0/M1 하네스의 fixture/실행 namespace를 root와 확정한다. 기존 차량 관찰 펼침에는 새 reader를 연결하지 않는다. 현재 root 관리의 nav androidTest-only 변경은 건드리지 않는다.
2. 기존 SessionHistoryOpeningMeasureTest와 SessionHistoryDisclosureMeasureTest의 동작과 endpoint를 그대로 사용하는 M0/M1 하네스를 동결하고 현재 제품 4838에서 빌드/설치 provenance를 고정한다. 과거 완료 cache를 덮지 않는 실행 namespace가 필요하면 root가 test-only 결과 prefix/실험 계열 지원만 기준 측정 전에 준비한다. 새 관찰 펼침 metric이나 하네스는 이번 최소 범위에 추가하지 않는다. M0/M1 소스는 전후에 바이트를 동일하게 유지한다.
3. root가 실제 M0/M1 기준값과 원본 보존 receipt를 확보한다. 기준 manifest의 필수 값이 비었거나 측정이 실패한 동안 production 코드는 변경하지 않는다.
4. 최소 실행 reader와 상태/표시를 구현하고 source/host/P+UX 정확성 경계를 검증한다. 코루틴, token과 UI 생명주기는 기존 관찰 reader 계약과 맞춘다.
5. root의 순차 JDK17 검증과 제어/실제 읽기 시나리오를 수행하고 최초 실패를 보존한다. 원래 자료를 고치거나 새 성공 표본으로 덮지 않는다.
6. 같은 하네스, Room fixture와 환경으로 비교 M0/M1 및 변경 후 M2를 수집한다. 실제 새 오류나 조건 차이가 생기면 원인과 영향만큼 수정/재검증한다.
7. 기능 연결과 비용 관찰 결과, 남은 MAP/위치/도로 미검증을 문서화한다. root가 최신 CI/Codex와 보존/정리 결과를 확인한 뒤 완료 계획 이동과 PR 처리를 수행한다.

## 변경 전후 측정

실행 정보가 종료 이력에 없어서 저장된 미확인 종료를 복습할 수 없는 사용 흐름을 연결한다. 기존 조회나 과제 요약 펼침이 느리다는 근거는 아직 없으므로 가속을 가설로 삼지 않는다. 새 실행 조회는 독립된 새 영역을 열 때만 발생하게 한다. M0 기존 이력 진입과 M1 기존 과제 요약 펼침의 회귀 비용을 전후로 관찰하며 두 측정 동안 새 실행 영역은 닫혀 있다. 이 두 값은 새 run 조회의 오버헤드 측정이나 기존 차량 관찰 펼침 측정이 아니다. 새 정보의 조회/표시 비용은 변경 후 M2로만 기록한다. 문서 전용 적용 제외에 해당하지 않으며 기준값을 먼저 측정한다.

### 고정 입력과 실행 조건

- 기준 제품은 root가 지정한 main 4838의 완전한 SHA와 실제 설치 APK SHA256, 비교 제품은 최종 production SHA와 실제 APK SHA256으로 기록한다. 고정 하네스의 완전 SHA, 소스 hash와 실제 instrumentation APK hash도 기록한다. Git, 빌드와 기기 조작은 root가 관리한다.
- M0/M1은 기존 두 하네스가 선택하는 저장 생성 순서상 최근 종료 연습을 그대로 사용하고 그 full source와 같은 코스의 과제 요약 입력을 비공개로 동결한다. 임의 source 선택 지원이나 관찰 ID endpoint를 새로 넣지 않는다. M2와 실제 correctness에는 이미 보존된 STOPPED와 INTERRUPTED/null 실행이 함께 있는 소유 종료 연습을 사용하며 가능하면 같은 source를 유지한다. 필요한 실제 자료가 없으면 해당 검증을 차단하고 알린다. 원래 기록에 쓰거나 임의 생성/삭제해서 조건을 맞추지 않는다.
- 기준 시작 전에 source 전 필드, 전체 세션 생성 순서, 해당 코스의 과제 요약 원문/순서, 관찰의 전 필드/ID 순서, 실행의 전 필드/생성 순서, 메모/준비/카탈로그와 현재 원본 DB manifest를 비공개로 고정한다. fixture 선택 ID/개수/행 내용은 공개 계획에 적지 않는다.
- 기준과 비교의 실제 앱 DB 및 환경은 같아야 한다. 완료된 배경 기록 실험의 소유 자료도 기존 자료로 보존하며 새 run, 관찰, 메모나 세션을 자동 생성하지 않는다. 활성 연습과 FGS가 없는 상태에서 읽기만 한다.
- JDK17, 같은 debug 변형, 같은 Pleos API34 AVD 이미지/ABI/해상도/density/가용 앱 영역, 같은 사용자/locale/timezone/font 1.0/라이트/IME/proxy/권한/runtime을 고정한다. 실제 Main RESUMED/전면과 SDK P+UX 허용을 시작 및 endpoint에서 확인한다. 미확인 입력을 정상으로 채우지 않는다.
- 각 측정 단계는 외부에서 시작한 새 앱 프로세스/Activity에서 실행한다. 캐시와 앱 자료를 지우지 않고 각 지표마다 5회 워밍업 뒤 30회 본 표본을 수집한다. 모든 Gradle 작업이 끝난 뒤 계측한다. 확대 1.3 다크는 별도 correctness 시나리오이며 이 라이트 timing과 합치지 않는다.

### 지표와 endpoint

| 지표 | 시작과 완료 endpoint | 단위와 비교 의미 |
|---|---|---|
| M0 기존 접힌 이력 진입 | home_history 클릭 직전 → 같은 source의 history_ready와 실제 가용 영역에 완전히 보이는 history_course, Compose idle 및 재확인 | 정수 ns와 파생 ms. 전후 공통 진입 비용 |
| M1 기존 과제 요약 펼침 | 기존 SessionHistoryDisclosureMeasureTest 그대로 history_summary_tasks 클릭 직전 → history_ready와 실제 가용 영역에 완전히 보이는 history_summary_scope, Compose idle 및 재확인. 이후 같은 첫 과제 원문 대조 | 정수 ns와 파생 ms. 이미 존재하는 과제 요약 펼침의 전후 회귀 비용. SDK 관찰 조회/펼침 metric이 아님 |
| M2 새 실행 정보 표시 | 비교에서 새 배경 기록 실행 영역 클릭 직전 → 정확한 run 목록 receipt와 선택 실행의 상태/시작/종료 값 표시, 필요하면 미리 정의한 스크롤, Compose idle 및 재확인 | 변경 후에만 측정하는 새 기능의 특성. 변경 전 값은 해당 동작 없음. M0/M1과 다른 행동 |

M0는 [기존 고정 진입 하네스](../../../app/src/androidTest/kotlin/io/github/hangillab/hangil/SessionHistoryOpeningMeasureTest.kt), M1은 [기존 과제 요약 펼침 하네스](../../../app/src/androidTest/kotlin/io/github/hangillab/hangil/SessionHistoryDisclosureMeasureTest.kt)를 사용한다. M1의 실제 클릭 tag는 history_summary_tasks이며 관찰 tag인 history_observations가 아니다. 두 하네스의 click/endpoint, 기존 준비 스크롤/검사/닫기, 5warm+30본, clock과 20초 한계를 그대로 고정한다. 과거 완료 cache와 새 실행의 결과 namespace를 분리해야 하며 필요한 test-only 저장 prefix 지원은 root가 기준 측정 전에 확정한다. 기존 다른 작업의 timing CSV를 이번 기준값으로 차용하지 않는다.

M0/M1은 양쪽에 이미 존재하는 행동이다. 새 영역은 기존 과제 요약과 버튼 시각 패널 뒤에 두고 기존 펼침의 body, guard, tag와 조작을 바꾸지 않는다. 같은 source/과제 요약/데이터/viewport와 기존 하네스의 실제 가시성 검사로 비교한다. 새 실행 영역은 두 측정 동안 접힌 상태이고 실행 reader를 구독하지 않아야 한다. 새 실행 UI가 없다는 사실을 기준 실패, 지연 0 또는 더 빠른 성공으로 처리하지 않는다.

M1은 순수 DB query 시간이나 순수 render/frame/GPU 시간이 아니다. 공개 보고에는 ‘기존 과제 요약 펼침의 계측 wall time’으로 표시하고 click dispatch, Compose 동기화/idle와 semantics/geometry 확인이 포함됨을 적는다. 이 endpoint는 새 실행 저장소 읽기나 SDK 관찰 조회/렌더의 직접 측정값이 아니다. 새 run 조회/표시 자체에는 이전 대응 행동이 없으므로 M2만 변경 후 특성으로 남긴다. 별도 Room/Compose controlled fixture에서 새 영역이 접혀 있을 때 실행 조회 구독 0, 펼친 동안의 정확한 source 구독과 기존 reader 독립성을 검증할 수 있지만 그 수나 synthetic 지연을 성능 수치로 사용하지 않는다.

M0/M1의 준비 스크롤, source/자료/환경 검사, JSON 쓰기, 닫기와 프로세스 시작은 기존 하네스 계약대로 timing에서 제외한다. M1은 history_finished 사전 스크롤, 접힌 요약 확인과 실제 history_summary_tasks 가시성 검사를 유지한다. 매 표본 뒤 이력을 닫아 같은 홈/source에서 새 접힌 이력을 열며 M1 측정 중 새 실행 정보 reader는 존재하지 않는다. M2의 새 영역 이동 스크롤과 실행 조회는 M2 계약에만 정의하고 전후 공통 M1에 섞지 않는다.

시계는 SystemClock.elapsedRealtimeNanos를 사용한다. 클릭 전달과 Compose 동기 blocking을 포함해 실제 경과 20초 이내에 endpoint를 확인하고 idle 뒤 다시 검사한다. timeout 도구가 blocking UI 호출을 선점할 수 있다고 주장하지 않는다. 양쪽 endpoint와 클릭 방법, polling/idle/timeout, source 선택, 스크롤 정책은 기준 측정 전에 하네스 hash로 동결한다.

### 순서, 실패와 보존

1. 기준 M0 5warm+30본, 기준 M1 5warm+30본을 production 변경 전에 완료한다. 각 지표는 동일 fixture의 접힌 상태로 재설정하고 원본 보존을 확인한다.
2. production 구현/검증 뒤 같은 단계 순서로 비교 M0/M1을 각 5warm+30본 실행한다. M2는 별도 새 영역의 5warm+30본을 기록하며 기존 과제 요약 펼침 표본과 합산하지 않는다. M2에는 baseline 수치나 개선율을 만들지 않는다.
3. 가능하면 root가 제품 APK/원본 자료/동일 하네스를 안전하게 유지한 상태에서 A1→B→A2를 수행해 순서 편차를 확인한다. A2는 기준과 같은 제품 및 fixture의 별도 5warm+30본이며 A1 원형을 대체하지 않는다. A2를 하지 못하면 A→B 순차 비교 한계를 남긴다. 실행 중 제품 버전 왕복은 이 초안 작성자가 수행하지 않는다.
4. 정수 ns 원값과 파생 ms, 각 표본의 warmup/sample 구분, endpoint 확인, source/환경 일치, 성공/실패/누락/제외 사유를 보존한다. 워밍업을 본 표본에 넣지 않는다. timeout, 다른 source, P+UX 상실, Main 이탈, 미확인 UI 계층과 계약 불일치는 실패/미완료로 남기며 0 성공으로 채우지 않는다. 중간 실패 때문에 유리한 재시도를 골라 30개 성공으로 대체하지 않는다.
5. 원문 fixture/식별자/APK/환경/실패와 로그는 ignored .gradle/history-recording-runs 아래의 실행별 비공개 자료로 보존하는 안이다. 공개에는 익명 조건과 timing만 남긴다. 원본 baseline JSON은 reserve/hash 방식으로 덮어쓰지 않으며 비교는 baseline hash와 manifest가 일치해야 시작한다.

본 표본 30개별 중앙값, Q1/Q3와 IQR, min/max 및 성공/실패/누락/제외 수를 보고한다. 사분위는 정렬한 하위 15개와 상위 15개의 중앙값으로 고정한다. 공통 M0/M1의 절대 차이와 상대 변화는 기준값이 양수일 때만 계산한다. 작은 표본 p95나 평균 하나로 안정성을 확정하지 않는다. 분포가 겹치거나 반복 편차를 분리하지 못하면 향상, 인과 저하, 동등성/무회귀를 확정하지 않는다. M2에는 이전 대비 개선율을 만들지 않는다. 상태/테스트/저장 행 수는 정확성의 보조 근거이며 성능이 아니다.

| 항목 | 변경 전 | 변경 후 |
|---|---|---|
| 실제 실행한 M0 표본 수 | 5warm+30본, 모두 성공 | 미실행 |
| 실제 실행한 M1 표본 수 | 5warm+30본, 모두 성공 | 미실행 |
| 새 실행 정보 M2 | 동작 없음, 비교 기준 아님 | 미측정 |
| 중앙값과 Q1/Q3, IQR | 아래 제품 변경 전 기준 확보 표의 실제 M0/M1 값 | 미측정 |
| 실패/누락/제외 표본 | M0/M1 각각 0/0/0 | 미실행 |

전후 측정은 기존 공통 흐름의 비용을 관찰하는 근거다. 기능 완료와 성능 향상 주장을 구분하고 사용자 복습 효과나 개선율을 만들지 않는다. [전후 측정 기준](../../development/measured-improvements.md)을 따른다.

## 검증 결과

| 명령 또는 시나리오 | 결과 | 근거 |
|---|---|---|
| 변경 전 source/저장 계약 읽기 검토 | 완료, 구현 검증 아님 | 작업 전 history VM에 run 저장소 연결 없음, domain observe와 Room 순서/검사/삭제 계약 확인 |
| M0/M1 기준 하네스 준비 | 고정 하네스의 실제 기준 실행 완료 | 9855a6c, 전 세션 실행 목록과 생성 순서 포함. 이후 제품 구현에서 두 고정 하네스는 변경하지 않음 |
| 기준 제품/고정 하네스/fixture manifest 확정 | 완료 | root가 완전 SHA/hash, 실제 source와 전체 자료/환경을 비공개로 동결 |
| 코드 변경 전 M0/M1 기준 측정 | 각각 5warm+30본 모두 성공 | 아래 실측 표. production 착수 전 완료 |
| 상태/nullable/순서/잘못된 행/과거 중단 보존 JVM | root JDK17 목표 검사 통과 | :feature:home:testDebugUnitTest, 새 reader와 기존 feature 회귀 포함. 전체 verify와 native 성공을 뜻하지 않음 |
| source/generation/옛 클릭/STOP/P+UX/오류 Compose 경계 | 미실행 | 제어 lifecycle/source/repository 사용, 실제 OS/SDK 장애 주입과 구분 |
| 실제 Main/SDK gate/Room 읽기 양 테마 | 미실행 | 기존 저장 자료만 읽고 DDS D/P와 복귀 후 새 펼침 확인 |
| 변경 후 공통 비용 및 새 정보 표시 M2 | 미실행 | 기준 하네스/fixture/환경 일치 후 수행 |
| ./scripts/verify.sh | 미실행 | root 순차 JDK17 실행 |
| 원본 SQLite/권한/환경 exact 보존 | 기준 종료 exact 통과, 제품 비교 후는 미실행 | root의 fresh snapshot 대조와 소유 AVD/forward/DDS 종료 확인 |
| 최신 CI/Codex/관련 리뷰 처리 | 미실행 | root가 PR 단계에서 동일 최종 head 기준 확인 |

correctness에는 구형 빈 run 목록과 실제 SDK 관찰 공존, INTERRUPTED/null 뒤 STOPPED 추가, STOPPED/FAILED의 nullable 종료 시각, OPEN을 live 서비스로 오인하지 않음, 벽시계 역행에도 생성 순서 유지, 조회 오류와 유한 스트림 완료의 terminal 실패, 불일치 session/중복 ID/손상 상태 거부를 포함한다. source 변경/삭제/같은 source 재수신, 부모 재시도/취소와 host STOP/폐기, silent D/UX 거부 후 P를 통한 옛 receipt 재사용도 거부해야 한다. 기존 관찰/과제 요약/메모만 펼칠 때 새 실행 조회가 없어야 하며 새 run 조회가 지연되거나 실패해도 기존 세 영역은 자기 상태를 유지한다.

기존 Room 실행 저장소, v4→v5 migration과 삭제 exact snapshot 계약은 회귀 검증에 재사용한다. 변경하지 않은 adapter에 구현을 그대로 복사한 테스트를 늘리지 않는다. 추가 실제 Room 연결 검증이 필요하면 별도 소유 in-memory fixture로 읽기/순서/독립성을 확인하며 원래 기기 자료를 seed하거나 수정하지 않는다.


### 기준 하네스 준비 상태

M0는 manualHistoryOpeningMeasure=true, measurementSeries=history-recording-runs-entry-v1을 사용한다. 결과 namespace는 measurements/history-opening-history-recording-runs-entry-v1-baseline.json 및 comparison.json이다. M1은 manualHistoryDisclosureMeasure=true, measurementSeries=history-recording-runs-tasks-v1을 사용하며 measurements/history-disclosure-history-recording-runs-tasks-v1-baseline.json 및 comparison.json으로 분리한다. 두 하네스 모두 measurementStage=baseline|comparison, 완전한 declaredProductCommit과 declaredHarnessRevision이 필요하다. 실제 원본과 환경의 외부 보존 전제는 그대로다.

Opening의 기존 실험 계열과 null 기본 경로는 유지했다. Disclosure는 null이면 기존 history-disclosure 경로와 measurementSeries 필드 생략을 유지하고 새 tasks 계열 외의 값은 거부한다. 비교에서는 null/명시 계열이 baseline과 정확히 같아야 한다. 과거 완료 결과를 새 계열로 재사용하거나 덮어쓰지 않는다.

두 snapshot과 originalData에는 모든 세션의 RecordingRun 전 필드와 저장 생성 순서가 포함된다. 빈 목록과 nullable 종료 시각도 그대로 보존하며 매 표본 전후와 종료 정리에서 목록을 대조한다. JSON 보고서 형식의 schema=1과 앱 DB schema5의 저장 입력을 구분한다. 이전 보고서에 실행 목록이 없으면 새 frozen 입력과 같다고 간주하지 않는다. 이 보강은 domain snapshot의 읽기 검사이며 SQLite 물리형/sequence와 실제 DB version/권한/환경의 외부 exact 대조를 대신하지 않는다.

두 metric의 기존 클릭 tag, endpoint, clock, timeout, 통계와 timing 구간은 소스 대조로 불변임을 확인했다. root가 이 고정 하네스의 기준 실행과 exact 보존을 완료한 뒤 제품 구현을 승인했다. 새 제품과 아래 추가 검증 소스의 컴파일/실행은 아직 성공으로 기록하지 않는다.

## 남은 문제와 인계

- root의 실제 기준 실행 및 복원 완료와 착수 승인 뒤 새 제품 reader/state/route/content, 부모 연결, 별도 disclosure와 문자열을 구현했다. 새 JVM/Compose/native 읽기/M2 검증 소스를 준비했고 root의 feature JVM 목표 검사는 통과했다. 구현 담당은 빌드, 기기, Git과 Notion을 조작하지 않았다. 전체 검증과 실제 UI 결과는 미실행으로 남긴다.
- 기준 제품 SHA, 고정 M0/M1 하네스 원본/hash와 결과 namespace, 동일 Room fixture/환경 manifest와 기존 endpoint는 root가 확정했다. 제품 비교와 M2 실행에서는 같은 기준 입력을 대조하며 frozen M0/M1 두 파일을 수정하지 않는다. 새 제품의 자동 검사와 실제 실행 결과는 root 확인 뒤에만 기록한다.
- 현재 root의 Navigation 전면 재검증과 androidTest-only 변경은 별도 소유 범위다. 이 기능은 해당 Navi API의 성공을 선행 조건으로 삼지 않으며 그 미확인 결과를 성공으로 바꾸지 않는다. 독립 실행 영역을 선택했으므로 기존 차량 관찰 fold와 run을 함께 바꾸지 않는다. 나중에 그 결합이 제품상 필수가 되면 production 변경 전에 별도의 관찰 펼침 전용 하네스와 실제 기준값을 준비해야 하며 기존 과제 요약 M1을 관찰 metric으로 바꾸어 부를 수 없다.
- 현재 공개 SDK 관찰과 실행 마커만으로 실행별 관찰 소속, 연속 수집, 정확한 공백, 실제 위치/도로 구간이나 native 서비스 종료 사실을 추가로 증명할 수 없다.
- 공개 README의 네 사례와 기존 이미지 원본은 이번 계획 작성에서 변경하지 않는다. 추후 새 근거의 사례 선정은 실제 구현/검증 뒤 기존 네 사례와 비교하며 테스트 수나 미측정 성능으로 교체하지 않는다.
- 완료 조건이 실제로 충족된 뒤 결과와 미확인 범위를 채우고 root가 상태 변경 및 completed 이동을 판정한다.


## 제품 변경 전 기준 확보

제품4838db6436d745e3df5c64aadb1b800adc08e134(실행 APK의 제품 소스는9fbf4d와 동일), 고정 계측9855a6c65fbfec3f8cd5c336f104a912ded6e17e에서 M0/M1을 순차 실행했다. 제품/계측 APK와 두 source의 SHA256, 원본 JSON/완전 source/catalog/environment/실행 마커 생성 순서를 비공개로 고정했다. 실제 브라우저 DDS ONLINE/P, 실제 SDK P+UX, debug/JDK17/PleosAPI34/font1.0/라이트/준비1/종료13/관찰145/메모1/terminal run15/활성 없음이다. 각 metric은 fresh process 뒤5warm+30본으로 모두 실제 endpoint/source/환경/자료 일치를 확인했다.

| 기준 metric | 본 표본 | 중앙값(ms) | Q1/Q3(ms) | IQR(ms) | min/max(ms) | 실패/누락/제외 |
|---|---:|---:|---|---:|---|---|
| M0 기존 접힌 이력 진입 |30|274.4009795|258.441/293.113959|34.672959|228.448666/352.68175|0/0/0|
| M1 기존 과제 요약 펼침 |30|169.5276255|168.419291/170.792041|2.37275|150.64725/174.092375|0/0/0|

각 warm5개도 성공이며 느린 본 표본을 제외하지 않았다. 원래 물리형/순서/sequence/기존 권한/font/night/IME/proxy까지 fresh snapshot이 exact 일치했고 소유 AVD/forward/DDS 탭을 종료했다. 기준 확보 시점에는 제품 코드를 변경하지 않았고 이후 root의 승인을 받아 아래 구현을 진행했다. 비교/M2는 미실행이다. 이 기준값은 새 run 기능의 이전 표시 시간을0으로 정의하거나 성능 향상으로 쓰지 않는다. 다음 구현은 다른 active 계획의 legacy review/내비 원격 전환 미확인과 독립적이다.


## 기준 확보 뒤 제품 연결

[SessionHistoryRecordingRunReader](../../../feature/home/src/main/kotlin/io/github/hangillab/hangil/feature/home/SessionHistoryRecordingRunReader.kt)와 [별도 UiState](../../../feature/home/src/main/kotlin/io/github/hangillab/hangil/feature/home/SessionHistoryRecordingRunUiState.kt)를 추가했다. 부모 history receipt lock과 full source/generation을 공유하며, 새 disclosure가 펼쳐진 STARTED host와 현재 P+UX 허용에서만 RecordingRunRepository.observe를 수집한다. reader/query/revision token과 표시 run ID를 페이지/재시도에 묶고 부모 선택/상태 변경과 host 폐기에서 무효화한다. 읽기 경로의 저장소 호출은 observe뿐이며 복구/시작/종료/쓰기 API를 호출하지 않는다.

수신 목록은 검증 전에 별도 불변 복사한다. 같은 session, 비어 있지 않은 run/process ID와 runId 유일성, 양수 시작, 알려진 종료의 시작 이후 조건, OPEN/INTERRUPTED의 null 종료와 하나 이하의 OPEN을 검증한다. 벽시계나 ID로 다시 정렬하지 않는다. 최신 저장 순서를 기본으로 표시하되 선택한 실행이 사라지면 실패로 남기며 과거 INTERRUPTED 존재는 새 STOPPED 뒤에도 배너로 보존한다.

[새 route](../../../feature/home/src/main/kotlin/io/github/hangillab/hangil/feature/home/HistoryStoredRecordingRunsRoute.kt)와 [실행 본문](../../../feature/home/src/main/kotlin/io/github/hangillab/hangil/feature/home/StoredRecordingRunContent.kt)은 저장 상태와 마커 시각만 표시하고 식별자는 출력하지 않는다. Empty, 조회 Failed와 저장 상태 FAILED를 구분하며 nullable 종료 시각은 명시적으로 미확인이다. SessionHistoryScreen의 기존 과제 요약/버튼 시각 뒤, 삭제 행동 앞에 sibling disclosure만 추가했다. 기존 관찰/과제/메모 fold의 본문과 tag는 유지했고 고정 M0/M1 두 하네스도 수정하지 않았다.

[새 JVM 검증 소스](../../../feature/home/src/test/kotlin/io/github/hangillab/hangil/feature/home/SessionHistoryRecordingRunViewModelTest.kt)와 기존 생성자 fixture의 throwing 저장소 입력을 준비했다. root가 JDK17 :feature:home:testDebugUnitTest를 실행해 통과했고 기존 opt-in 경고는 실패와 구분했다. 실제 Compose와 합성 lifecycle/source/repository/gate를 쓰는 [제어 native 소스](../../../app/src/androidTest/kotlin/io/github/hangillab/hangil/SessionHistoryRecordingRunBoundaryTest.kt)는 실제 SDK 또는 OS 수명 성공의 근거가 아니다. 정적 resource/XML/호출 경계와 별도 읽기 검토를 수행했지만 전체 verify, APK, 제어/실제 native와 M2는 미실행이다. 목표 JVM 통과만으로 실제 제품 완료를 판정하지 않는다.


### 실제 읽기와 M2 실행 준비

[SessionHistoryRecordingRunFlowTest](../../../app/src/androidTest/kotlin/io/github/hangillab/hangil/SessionHistoryRecordingRunFlowTest.kt)는 실제 Main/현재 SDK P+UX와 기존 Room 그래프를 읽는 opt-in 하네스다. 고정 M0/M1 baseline 두 파일의 완료 상태/계열/원본/hash와 전체 originalData, 최신 종료 source와 catalog를 대조한다. 최신 고정 종료 연습에는 실제 STOPPED와 INTERRUPTED/null이 필요하며 구형 무마커 연습과 SDK 관찰의 공존도 별도로 확인한다. 모든 종료 연습의 실행을 저장 순서대로 열람하고 식별자 비노출, 재생성 후 새 접힘, DDS D 잠금과 P 복귀 후 fresh 펼침을 검사하도록 준비했다. 도메인 쓰기는 없고 SQLite 물리형/sequence/권한/전체 환경의 외부 exact 검사를 대신하지 않는다.

실제 flow는 manualHistoryRecordingRunFlow=true와 expectedFontScale=1.0/expectedDark=false 또는 1.3/true로 실행한다. 외부 DDS 대기 단계는 HISTORY_RECORDING_RUN_AWAIT_D와 HISTORY_RECORDING_RUN_AWAIT_P이며 전체 실제 종료 표식은 HISTORY_RECORDING_RUN_COMPLETED다. 캡처/JSON은 `cache/screenshots/history-recording-runs-<nonce>`의 비공개 실행별 자료다. 실제 Main의 display에서 focused window의 실제 package와 RESUMED를 확인하고 미확인을 정상값으로 채우지 않는다. 정상 PNG는 실제 Main이 기본 display에 있는 경우만 crop하며 실패 진단의 기본 display 원형과 구분한다. 원래 interactive-window flag bit는 다른 flags를 유지하며 복원한다.

[SessionHistoryRecordingRunMeasureTest](../../../app/src/androidTest/kotlin/io/github/hangillab/hangil/SessionHistoryRecordingRunMeasureTest.kt)는 manualHistoryRecordingRunMeasure=true, measurementStage=after, expectedFontScale=1.0, expectedDark=false와 완전한 declaredProductCommit/declaredHarnessRevision을 요구한다. 새 하네스 원본과 설치 instrumentation APK hash를 기록하며 frozen M0/M1 하네스 9855 또는 이전 instrumentation APK hash와 같다고 요구하지 않는다. 다만 두 기준 파일의 hash, 전체 데이터/source/catalog와 M2의 같은 환경은 대조한다.

M2는 새 disclosure 클릭부터 정확한 최신 실행 값, 명시된 종료 마커 스크롤, 실제 가시성/Compose idle와 값 재확인까지 5warm+30본을 수집한다. 결과는 measurements/history-recording-runs-after-only-v1.json의 비공개 reserve/AtomicFile 기록이며 기존 완료/실패/sidecar를 덮어쓰지 않는다. 이미 파일이 있으면 원형을 보존한 외부 실행자가 별도 회차를 준비해야 한다. 원값과 미실행/실패를 보존하고 baseline=0, 개선율이나 M0/M1과의 비교 통계를 만들지 않는다. 정상 완료 표식은 HISTORY_RECORDING_RUN_M2_AFTER_COMPLETED다. 제어 native는 manualHistoryRecordingRunBoundary=true를 사용하며 screenshot이나 실제 Room/SDK 성공을 만들지 않는다.

이 하네스들의 작성과 정적 대조는 끝났지만 실제 빌드/실행은 아직 하지 않았다. root가 원본/환경을 보존한 순차 JDK17 전체 검증, 실제 양 테마와 비교 M0/M1, after-only M2, 최종 exact 보존을 수행하고 그 결과를 별도로 갱신한다. 현재 구현 및 검증 준비 파일은 freeze하며 frozen M0/M1 두 파일과 README/기존 이미지/코어/스키마/SDK 권한/API는 변경하지 않는다.


README의4사례/내용/순서/지정6항목/22개 실제 코드/18개 기존 이미지는 보존했다. VM에 독립 reader가 추가되어 이동한 소스 줄 anchor만22개 발췌의 실제 유일 일치 위치로 다시 대조했다. 이 문서 링크 배치는 앱 실행 성능과 무관해 측정 적용 제외이며 실제 코드 일치/유효 링크/본문 및 이미지 보존/공개 콘텐츠 검사로 검증한다. 새 기능의 실제 읽기 연결은 기존1번의 source/수명/P+UX 계약을 재사용하므로 새 원인/판단/실측 향상을 확보하지 않은 상태에서 기존4개의 제품 해결 사례보다 더 강하다고 교체하지 않는다.
