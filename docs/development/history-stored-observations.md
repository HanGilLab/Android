# 선택한 지난 연습의 저장 차량 관찰

이 문서는 로컬 구현과 검증이 끝난 독립 읽기 경계, 실제 측정과 재현 조건을 기록한다. 제품65221b4의 실제 두 테마와 최종 제어 검증은 통과했으며 최신 PR은 아직 미생성이다. 로컬 기능 검증을 PR의 필수 CI/Codex 또는 병합 완료로 대신하지 않는다. 작업 기준은 [계획](../plans/active/2026-10-31-history-stored-observations.md)이다.

## 실제 제품 공백과 선택한 범위

기존 이력 화면은 과거 종료 기록의 당시 목표, 코스/과제, 버튼 시각과 과정 요약을 확인하지만 해당 차량 관찰을 열 수 없었다. 홈의 최근 종료 기록에서만 열리던 저장 관찰을 이력의 선택 기록에도 연결한다. 저장소가 이미 제공하는 sessionId별 Room 조회를 사용하고 실제 SDK를 다시 수집하거나 위치/경로를 생성하지 않는다. 조회 자료가 없음을 정상 주행이나 관찰값0으로 해석하지 않는다.

목표는 읽기 경로 연결이며 최신 기록 전용 경로의 선택 계약은 유지한다. 메모 쓰기, 종료 기록 삭제, DB 스키마, 준비/카탈로그, 위치 권한과 내비는 변경하지 않는다. 실제 도로 구간 복습과 APP-06/APP-08 전체 완료를 대신하지 않는다.

## 현재 선택과 상세 reader의 소유권

- History의 동일 reader/query Job, epoch와 선택 generation, 전체 PracticeSession snapshot을 상세 수집 시작에 고정한다. sessions를 상세에서 다시 구독해 두 개의 선택 기준을 만들지 않는다.
- 상세는 펼친 동안만 만드는 cold Flow이며 고유 reader/query/revision token을 발행한다. 새 행, 페이지와 실패가 수신되면 revision을 갱신한다. 페이지/재시도는 화면에 표시된 token과 행 ID를 그대로 캡처한다.
- source 변경/삭제/선택 전환, 부모 실패/취소와 STOP은 이전 상세를 무효화한다. 실제로 직접 읽은 D/UX 거부도 부모의 기존 denied query 경로로 소유권을 폐기한다. 이후 P나 같은 ID의 다른 source가 옛 동작을 재승인하지 않는다.
- UI는 ParkedDisclosure의 당시 guard/STARTED 소유자와 별도의 순수 lifecycle 소유자 검사를 함께 사용한다. 무효화된 reader를 새 token으로 덮어 읽지 않으며 callback에 rememberUpdatedState를 사용하지 않는다.
- 이전 접근성 동작이 재사용된 Foundation clickable node의 최신 callback을 따라가지 않도록 표시 당시 페이지/재시도 콜백을 명시적인 semantics action에도 고정한다.

## 값, 순서와 실패

저장 ID가 양수이고 엄격히 증가하며 같은 선택 sessionId에 속하고 관찰 시각이 양수인 행만 표시한다. 벽시계 역행은 ID 순서를 바꾸지 않는다. 첫 선택은 마지막 저장 행이며 이후에는 사용자가 고른 행을 유지한다. 선택한 행이 사라졌다면 다른 행으로 자동 대체하지 않고 실패로 구분한다. 빈 결과, 잘못된 행, 저장소 오류/독립 취소, 정상 스트림 종료와 source 만료를 구분한다. terminal 실패는 다른 입력 방출로 자동 재조회하지 않고 명시적 재시도를 요구한다.

관찰 본문은 최신 경로와 같은 실제 출처, 연결, 기어, 방향지시등, 브레이크와 관찰 시각 표시를 재사용한다. Unknown/Unsupported/PermissionDenied/Error를 정상이나0으로 바꾸지 않고 차량 이벤트만으로 운전 능력/위반/위험을 판단하지 않는다.

## 측정과 검증 경계

구현 전 제품016245cc8399b580b17a737cac04b3911d023d94와 고정 지원4dbea6c4c6fc59cf846870f5bcaefd38c031b4e1에서 실제 기존 이력 진입을 측정했다. 새 기능 자체에는 같은 과거 동작이 없으므로 관찰 열람 속도 개선율은 계산하지 않는다. 같은 접힌 이력 진입의 비용만 관찰한다.

익명 조건은 JDK17.0.21+9/debug/Pleos API34/font1.0/라이트/원래 자료/실제SDK P+UX다. 각 단계 새 프로세스/Activity에서5회 워밍업 뒤 같은 프로세스의30회 열기/닫기를 실행한다. 기존 고정 UI click→Ready/원문/Compose idle endpoint이며 클릭 dispatch/검사 대기를 포함한다. 앱 전용 frame/GPU/SDK timer나 사용자 속도 실험이 아니다. 캐시/프로필/ART/JIT/발열/OS/순서 영향을 분리하지 못한다.

변경 전 본 표본 중앙값284.1812085ms, Q1/Q3 260.741667/312.910542ms, IQR52.168875ms, min/max241.669292/483.016417ms이며5warm+30표본의 실패/누락/제외0이다. 이 기준값을 기록할 당시에는 비교가 미측정이었다. 최종 비교와 한계는 아래에 별도로 기록하며 향상/저하/동등성/무회귀를 확정하지 않는다. 원형/APK hash/식별자/원문은 ignored 로컬에 보존하고 공개에는 timing과 익명 조건만 기록한다.

초기 로컬 helper 파일 문맥 누락은 계측 시작 전 오류다. 실제 계측1개 PASS 뒤의 사용자 문맥 누락은 결과 export 오류이며 이미 완료한 측정을 다시 실행하지 않고 원형만 회수했다. 앱 실패 표본이나 성공 개선율로 바꾸지 않는다.

JVM의 동시성/터미널/오염 입력, 제어 native의 합성 무방출/선택/STOP과 실제 Main/SDK/Room의 원래 자료 열람 및 DDS D-P를 각각 구분해 확인했다. 당시 실행 전 계획과 최종 결과를 분리하고 미실행 조건은 성공으로 기록하지 않는다. 실제 SDK last-known 위치/내비/독립 UX 오류 주입과 실제 SDK의 강제 늦은 callback은 이 기능의 검증 범위가 아니다. JVM의 늦은 행 방출과 제어 UI의 옛 callback 재생은 별도의 소유권 검사다.

최종 JDK17 전체4단계/45suites654개 실패/오류/건너뜀0, debug/unsigned release 및 AndroidTest APK는 통과했다. 최초 파일 이름 품질 실패와 stateIn 전달 시점 기대를 정정한 JVM 실패는 계획에 분리했다. 이 전체 자동 검사 시점에는 제어 native와 실제 Main 시나리오가 미실행이었다. 이후 최종 실기를 아래에 별도로 기록하며 자동 검사만으로 실기를 대신하지 않는다.


## 실제 최종 검증과 과거 실패의 구분

제품은 `65221b4deeb2f457d48567bb9aeaed7a1eb66f13`이고 최종 제어 하네스는 `5eb81d78f4d80717eb9e0ab45d85c955763f669e`다. 하네스 수정 전후 제품 코드, 실제 Main 계측 소스와 고정 진입 측정 지원4d는 불변이다.

| 검증 | 실제 결과와 한계 |
|---|---|
| JDK17 전체 검증 | verify4단계, 45suites/654 JVM의 실패/오류/건너뜀0과 debug/unsigned release/AndroidTest APK 통과. 하네스 정정 뒤 quality/계측 APK도 재통과 |
| 최초 품질/JVM | 새 함수 파일 PascalCase 이름 실패와 stateIn 전달 전 Locked 기대 실패1개를 보존. 동기 action 거부와 scheduler 뒤 표시를 분리해 재검증했으며 제품 gate를 약화하지 않음 |
| 최초 제어 native | 3PASS/1FAIL. silent D 거부 뒤 통지하지 않은 P만으로 자동 Ready가 된다고 가정한 하네스 대기 실패 |
| 하네스 보강 뒤 첫 제어 실행 | 3PASS/1FAIL. 첫 검사 NoComposeHierarchy/STOPPED host와 실행 후 기본 내비 전면을 관찰. 이 상태만으로 앱 결함이나 복구 성공을 단정하지 않음 |
| 최종 제어 native | 실제 Main 전면 복원 뒤 같은 코드/APK에서4PASS/건너뜀0. 합성 P/UX/저장 자료를 사용한 새 관찰 경계3개와 기존 요약 경계1개이며 실제 차량 SDK 오류 주입과 구분 |
| 실제 기본1.0/라이트 | Main/SDK/Room 시나리오1PASS/건너뜀0. 원래9개 종료/15개 관찰/빈 기록, 원문과 순서/페이지/재생성/STOP/닫기/실제DDS D-P/홈 최신 열람 확인 |
| 실제 확대1.3/다크 | 같은 범위1PASS/건너뜀0. 각 테마5개 비공개 PNG를 별도 보존하고 실제 글자/빈 결과/기록/잠금 화면을 직접 검토 |

합성 복구 검사는 옛 generation/action 거부와 구독0/열림 수 불변을 먼저 확인한다. 명시적 D 통지→idle/Locked→P 통지 뒤 새 generation Ready와 fresh 펼침을 확인하며, 값만 바꾼 P를 실제 SDK 통지로 바꾸어 설명하지 않는다. 이전 callback을 새 token으로 업데이트하거나 실패를 성공 기본값으로 대신하지 않는다.

원래 SQLite 전체 값/물리 자료형/순서/sequence와 권한/runtime/설정 보존은 외부 실행자가 별도 대조한다. 계측의 domain snapshot 일치만으로 SQLite 물리형식/sequence/권한까지 검증했다고 주장하지 않는다. 마지막 비교 뒤 외부 helper의 설정 복원/snapshot-after/종료는 모두 exit0이었다. 원래 모든 table/row/field/물리 자료형/순서/sequence/grants/runtime/font/night/IME/proxy의 정확한 보존을 확인했고 이번 소유 AVD/forward/DDS만 정리했다. 비공개 run-state에도 원본 환경 복원/소유 AVD와 forward 종료/DDS 탭 닫기의 세 상태가 모두 true다.

## 고정 진입 비용의 최종 전후 결과

[70개 timing 원값](measurements/history-observation-entry-ui-times.csv)에 기준/비교의 각5warm+30본 표본을 보존했다. LF, 정수 ns와 ns에서 유도한 ms를 대조했고 통계는30개 본 표본만 사용한다. Q1/Q3는 정렬한 하위15/상위15 각각의 중앙값이며 IQR은 Q3−Q1이다.

| 지표 | 변경 전 | 변경 후 |
|---|---:|---:|
| 제품 | 016245cc8399b580b17a737cac04b3911d023d94 | 65221b4deeb2f457d48567bb9aeaed7a1eb66f13 |
| 워밍업/본 표본 | 5/30 | 5/30 |
| 중앙값(ms) | 284.1812085 | 292.6500005 |
| Q1/Q3(ms) | 260.741667/312.910542 | 270.229459/324.851667 |
| IQR(ms) | 52.168875 | 54.622208 |
| min/max(ms) | 241.669292/483.016417 | 236.618333/377.567667 |
| 실패/누락/제외 | 0/0/0 | 0/0/0 |

관찰된 중앙값 차이는 +8.468792ms/+2.980067557844874%이며 두 IQR은 겹친다. 접힌 새 UI를 추가한 기존 이력 진입 비용의 순차 비교이며, 새 관찰 열람 속도 자체의 전후 개선 실험이 아니다. fixedSource/originalData/catalog/environment/measurementContract와 기준 계측 소스가 같고 각 실제 설치 APK hash도 확인했다. 그래도 APK 교체/ART/profile/JIT/캐시/발열/OS/네트워크와 순서 효과는 분리하지 못했다. 향상, 인과 저하, 동등성/무회귀는 모두 미확정이며 숫자를 유리하게 선택하거나 다른 작업의 표본과 합치지 않는다. 새로 펼친 관찰을 읽는 시간이나 모든 기기/release/콜드 실행으로 일반화하지 않는다.

개인 경로/장치/원문 자료를 포함한 baseline/comparison JSON, APK/hash와 외부 복구 증거는 ignored 비공개 기록에 보존한다. 공개 원값 링크에는 timing과 익명 단계만 담는다. README에는 기능 표 한 문장만 추가하고 기존4개 사례/17개 실제 코드/18개 이미지를 유지한다. 이번 연결은 기존 live/source와 reader 소유권을 확장하지만 독립적인 더 강한 원인 규명이나 확인된 속도 향상을 입증하지 않아 사례 교체 근거로 삼지 않는다.

## 재현 옵션과 안전한 실행 전제

1. JDK17을 명시해 전체 verify와 필요한 계측 APK를 먼저 빌드한 뒤 무거운 Gradle을 종료한다. 기존 AVD/자료/권한을 삭제하거나 원래 준비/세션을 시험 자료로 바꾸지 않는다.
2. 외부 실행자가 원래 DB 전체/권한/runtime/글꼴/테마/IME/proxy를 비공개로 보존하고 활성 세션이 없는지, 실제 Main 전면과 실제 DDS 브리지/SDK P+UX를 확인한다. 빈 UI 계층이나 HTTP200만으로 이 조건을 충족했다고 판단하지 않는다.
3. 고정 계측은 [SessionHistoryOpeningMeasureTest.measureHistoryOpeningAndPreserveOriginalData](../../app/src/androidTest/kotlin/io/github/hangillab/hangil/SessionHistoryOpeningMeasureTest.kt)에 `manualHistoryOpeningMeasure=true`, `measurementSeries=history-observation-entry-v1`, `measurementStage=baseline|comparison`, 실제 완전 SHA의 `declaredProductCommit`, `declaredHarnessRevision=4dbea6c4c6fc59cf846870f5bcaefd38c031b4e1`을 사용한다. 같은 고정 소스의 별도 prefix로 원형을 저장하며 완료된 기준 파일을 지우거나 덮어쓰지 않는다. 실제20초 endpoint는 클릭부터 history_ready/전체 가시 history_course와 Compose idle/재확인까지다. 동기 blocking을 coroutine timeout이 선점한다는 보장은 아니며 복귀 뒤 실제 경과도 검사한다.
4. 실제 시나리오는 [SessionHistoryStoredObservationFlowTest.allStoredHistoryObservationsRemainReadOnlyAcrossSelectionLifecycleAndDrivingLock](../../app/src/androidTest/kotlin/io/github/hangillab/hangil/SessionHistoryStoredObservationFlowTest.kt)에 `manualHistoryStoredObservations=true`, 기본은 `expectedFontScale=1.0, expectedDark=false`, 확대 다크는 `expectedFontScale=1.3, expectedDark=true`로 명시 선택한다. 기본 테스트 발견에서의 skip은 성공이 아니다.
5. `history_observations`를 실제로 펼쳐 모든 원래 종료 기록의 관찰/빈 결과와 review_previous/review_next를 확인한다. 과거의 source를 현재 카탈로그로 재해석하거나 실제 SDK 관찰과 메모/시연 자료를 섞지 않는다. `HISTORY_OBSERVATION_AWAIT_D`에서 외부 DDS D, `HISTORY_OBSERVATION_AWAIT_P`에서 P를 실제 SDK 수신까지 확인한다. 실제 전면/레이아웃과 기존 홈 최신 경로도 대조한다.
6. 제어 경계는 [SessionHistoryObservationBoundaryTest](../../app/src/androidTest/kotlin/io/github/hangillab/hangil/SessionHistoryObservationBoundaryTest.kt)의3개 메서드에 `manualHistoryObservationBoundary=true`를 사용한다. 기존 [SessionHistoryDisclosureBoundaryTest.staleDisclosureCallbackCannotExpandAfterSilentLiveDOrRememberDeniedIntent](../../app/src/androidTest/kotlin/io/github/hangillab/hangil/SessionHistoryDisclosureBoundaryTest.kt)는 `manualHistoryDisclosureBoundary=true`다. 합성 소스/가드/실패를 명시하며 실제 원래 Room 자료나 차량 SDK 장애 재현으로 부르지 않는다.
7. 최종 domain 자료와 외부 SQLite/sequence/권한/설정을 각각 대조하고 복원한다. 원형/실패/비공개 캡처를 보존한 뒤 이번 소유 AVD/forward/DDS만 정리한다. 이 문서는 새 권한이나 데이터 삭제를 승인하지 않는다.

기능 검증은 완료됐지만 최신 PR은 아직 미생성이며 동일 최종 head 필수 CI/Codex와 정상 병합/브랜치 정리/Notion readback은 후속이다. APP-06/APP-08 전체, 지도 복습/실제 도로/위치 연속/내비 수집과 독립 UX 오류 주입의 미완료는 유지한다.

## 이미지 원본 재확인

확대 다크 홈 복귀의 축소 미리보기에서 일부 문구가 비어 보였지만, 같은 해시의 원본 크기 이미지와 별도 검토, 실제 PNG 픽셀에서 출처 배지와 관찰 문구가 모두 존재함을 확인했다. 제품 결함이나 수정 성과로 기록하지 않는다. 이미 시작한 같은 제품652/하네스5eb의 추가 다크 시나리오도1PASS/건너뜀0으로 마쳤으며 새 비공개 PNG5개를 따로 보존했다. 추가 홈 PNG는 최초 원본과 바이트가 완전히 같았다. 추가 실행은 새 성능 표본이 아니며 앞선70개 timing에 섞지 않았다. 원래 모든 자료/권한/설정을 다시 대조하고 소유 AVD/forward/DDS 탭을 종료했다.
