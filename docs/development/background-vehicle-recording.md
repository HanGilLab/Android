# 내비 전면 중 차량 상태 기록과 중단 표시

활성 연습의 일반 전면 수집과, 사용자가 명시적으로 켠 foreground service의 차량 관찰을 같은 recorder로 연결한다. 서비스 시작은 주차와 UX 허용을 모두 확인해야 한다. 위치, 실제 주행 경로, 목적지 전달, 길안내와 차량 제어를 추가하지 않는다. 구현과 검증 근거는 [PR64](https://github.com/HanGilLab/Android/pull/64)에 남겼다. 차량 기록의 부분 검증을 APP-04, APP-05와 MAP-04 전체 완료로 확대하지 않는다.

## 전면 수집과 정지 경계

서비스가 소유하지 않은 일반 수집은 MainActivity가 STARTED이고 Room의 활성 세션이 하나일 때 VehicleGateway 상태를 순차 기록한다. 주행 잠금으로 홈 상세 UI가 사라져도 기록 수명은 끝나지 않는다. STOPPED 또는 활성 세션 종료/교체 시 이전 기록 작업을 취소하고 정리가 끝난 뒤 다음 실행을 시작한다. 중지 중 수신한 값을 역채우거나 복귀 때의 현재 스냅샷을 새로운 SDK 신호로 해석하지 않는다. 앱 관찰 시각은 SDK 발생 시각이 아니며 StateFlow는 동일/중간 상태를 생략할 수 있다.

전면 경계의 실제 검증은 [ForegroundVehicleRecordingFlowTest](../../app/src/androidTest/kotlin/io/github/hangillab/hangil/ForegroundVehicleRecordingFlowTest.kt)의 `actualSdkSnapshotsStopAndResumeWithForegroundSession`을 `manualDDS=true`로 명시 선택한다. P와 UX 허용에서 시작한 같은 세션에 실제 SDK D 저장과 상세 잠금, CREATED 동안 제품 쓰기 중단, 복귀 후 P 저장과 단일 세션, 종료 뒤 추가 거부를 확인한다. 검사가 별도 SDK 구독을 유지하므로 저장 중단은 모든 SDK 구독/플랫폼 서비스 해제의 증거가 아니다. 기본 실행의 건너뜀과 안정 구간의 행 불변을 실제 성공이나 성능 수치로 바꾸지 않는다.

실행 전에는 기존 활성 세션이 없는지 확인하고 원본 DB/권한/설정과 차량 상태를 보존한다. 실제 DDS 수신과 전면 앱을 확인하며 HTTP 응답, 빈 UI 계층이나 플랫폼 오버레이를 앱 성공으로 계산하지 않는다. 실패한 실행은 재실행 성공과 구분하고, 남은 검사 소유 세션은 실제 P와 UX 허용 아래 정상 UI로만 종료한다. DB 삭제/덮어쓰기나 허용 gate 대체로 정리를 가장하지 않는다. 원래 차량 상태와 전체 행을 대조하고 자신이 만든 자원만 정리한다. 이 검사는 새 위치나 다른 권한을 부여하지 않는다.

## 수집 소유권과 화면 경계

[PracticeRecordingController](../../core/domain/src/main/kotlin/io/github/hangillab/hangil/core/domain/PracticeRecordingController.kt)는 하나의 recorder와 공통 작업 잠금으로 일반 전면 수집과 서비스 수집을 직렬화한다. 전면의 `owner.collectLatest`가 기존 작업을 취소하고, 서비스 준비와 수집은 같은 작업 잠금을 획득한 뒤 진행한다. 종료 처리도 준비 작업과 수집 작업의 `cancelAndJoin()`을 기다린다. 따라서 별도 수집기를 만들어 같은 세션에 동시에 쓰는 방식을 사용하지 않는다.

[BackgroundRecordingRoute](../../feature/home/src/main/kotlin/io/github/hangillab/hangil/feature/home/BackgroundRecordingRoute.kt)와 [ViewModel](../../feature/home/src/main/kotlin/io/github/hangillab/hangil/feature/home/BackgroundRecordingViewModel.kt)은 표시한 세션 전체 스냅샷, 배경 상태의 표시 당시 객체, 요청 token과 현재 RESUMED 호스트를 고정한다. 명시적 시작 전, marker 저장과 서비스 수신 경계에서 최신 주차와 UX 허용을 다시 확인한다. PAUSE/STOP/폐기와 source 교체 뒤 옛 클릭이 새 화면이나 다른 세션의 허용 조건을 빌리지 못하게 한다. P 복귀만으로 거부했던 시작을 다시 실행하지 않는다.

서비스 수신 뒤에는 고정한 실행과 세션에만 수집을 연결한다. 같은 세션이 종료되면 중단하고, 다른 세션으로 바뀌거나 읽기가 실패하면 실패로 표시한다. 새 활성 세션을 자동 채택하지 않는다. 서비스 실행 중 SDK의 D 상태 관찰과 상세 입력 허용은 별개다. 상세 조작은 계속 P와 UX 허용을 모두 요구한다.

배경 기록 중지는 해당 run만 정리하며 연습 자체를 종료하지 않는다. 원래 세션 source를 더 읽지 못해도 남아 있는 소유 run의 정리 상태와 정확한 중지 token을 유지한다. 이 경우 새 시작은 제공하지 않는다. 실행 실패 중 일반 전면 재시도를 숨은 배경 재개로 사용하지 않으며 새 명시 동작을 요구한다.

## 일반 전면 재진입과 상태 쓰기 순서

서비스 기록을 끈 뒤 SDK 관찰은 저장되는데 일반 상태가 Paused로 남는 실패가 있었다. `owner` StateFlow에 `null`을 먼저 쓰면 일반 전면 수집자가 즉시 재진입해 Recording 또는 Idle을 발행하고, 이전 종료 흐름의 뒤늦은 Paused가 이를 덮을 수 있었다. 같은 스레드의 재진입은 `synchronized`만으로 막히지 않는다.

[PracticeRecordingController](../../core/domain/src/main/kotlin/io/github/hangillab/hangil/core/domain/PracticeRecordingController.kt)는 소유권 반환 네 분기에서 백그라운드 상태와 일반 상태를 먼저 준비하고 `owner.value = null`을 마지막에 쓴다. 이미 정리된 run의 명시적 끄기와 종료 정리에도 같은 순서를 적용한다. 다음 수집자의 상태를 옛 실행이 덮지 않으며 Recording을 무조건 정상값으로 넣지 않는다. 활성 연습이 없을 때 Idle을 고르는 책임은 일반 수집자에 남긴다.

즉시 재진입의 다섯 회귀가 수정 전 실패하고 수정 후 통과했으며, `a2cf9e2`의 기본 라이트와 확대 다크에서 일반 전면 Recording 재개를 확인했다. 이 수정 자체의 SDK 처리나 상태 전이 성능은 전후 실측하지 않았다.

## 네이티브 서비스 선택과 한계

[VehicleRecordingService](../../app/src/main/kotlin/io/github/hangillab/hangil/VehicleRecordingService.kt)는 네이티브 승격과 서비스 수명만 담당한다. 현재 Starting 상태의 run/세션과 같은 intent만 받고, 알림을 구성해 `startForeground()`가 성공한 뒤 controller의 `recordInService()`를 호출한다. 중복 intent나 옛 intent가 이미 실행 중인 소유권을 바꾸지 않는다. 시작/승격 실패와 시간 초과를 실패로 전달하며 `START_NOT_STICKY`로 자동 서비스 재시작을 요청하지 않는다.

서비스는 [manifest](../../app/src/main/AndroidManifest.xml)에 `exported=false`, `foregroundServiceType=specialUse`와 사용 목적 subtype을 명시했다. Android의 [FGS 종류](https://developer.android.com/develop/background-work/services/fgs/service-types), [시작과 승격](https://developer.android.com/develop/background-work/services/fgs/launch), [사용자의 앱 종료](https://developer.android.com/develop/background-work/services/fgs/handle-user-stopping)를 작업 계획의 근거로 사용했다. 차량 IPC가 connectedDevice의 외부 장치 용도에 해당한다고 확인되지 않아 해당 종류를 선택하기 위한 더미 권한을 넣지 않았다. specialUse가 이 기능에 적합하다는 판단은 구현 선택이며 Pleos 또는 스토어의 승인 근거가 아니다.

추가 선언은 normal `FOREGROUND_SERVICE`와 `FOREGROUND_SERVICE_SPECIAL_USE`다. `POST_NOTIFICATIONS`, 위치와 다른 dangerous 권한은 새로 요청하지 않는다. [알림 권한](https://developer.android.com/develop/ui/compose/notifications/notification-permission)과 FGS 실행, 실제 알림 표시를 구분한다. 현재 검증 대상인 Pleos API34에서 시스템 launcher로 연 실제 내비 전면, Main STOP과 같은 service run의 새 SDK D/P 저장은 기본 라이트와 확대 다크에서 확인했다. 프로세스 중단/복구는 별도의 Main 전면 종료 조건에서 확인했다. 내비 전면에서 프로세스를 종료했다고 주장하지 않으며 플랫폼의 일반 지원이나 최종 자료/환경 확인까지 마친 전체 완료로 일반화하지 않는다. 작업 계획에서 읽은 [Pleos Vehicle 안내](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/Vehicle/intro)만으로 백그라운드 수집이 보장된다고 해석하지 않는다.

FGS는 모든 SDK callback이나 물리 동작의 보존을 보장하지 않는다. 기존 [VehicleObservation](../../core/domain/src/main/kotlin/io/github/hangillab/hangil/core/domain/VehicleObservation.kt)은 앱에서 관찰한 SDK 상태 스냅샷이다. StateFlow의 생략된 중간 상태나 동일 상태, 놓친 이벤트 수를 추정하지 않으며 앱 관찰 시각을 SDK 이벤트 시각이나 실제 출발/도착 시각으로 바꾸지 않는다. 실제 도로, 목적지/경유지/안내 경로와 기존 활성 길안내의 보존은 이번 검증 범위가 아니다.

## 영속 구간과 미확인 종료

[RecordingRunRepository](../../core/domain/src/main/kotlin/io/github/hangillab/hangil/core/domain/RecordingRunRepository.kt)는 세션과 별도로 기록 실행의 수명을 저장한다. [Room 구현](../../core/database/src/main/kotlin/io/github/hangillab/hangil/core/database/RoomRecordingRunRepository.kt)은 전체 active 세션 스냅샷을 실제 트랜잭션에서 재비교하고, 쓰기 전후의 순수 허용 gate와 coroutine 취소를 검사한다. 허용이 사라지거나 취소되면 새 OPEN 기록도 롤백한다. 유일한 nullable `openSessionId` 인덱스로 같은 세션에 OPEN 하나만 허용한다.

| 상태 | 저장 의미 |
|---|---|
| OPEN | 해당 프로세스가 시작한 기록 실행이 아직 종료 기록을 남기지 않음 |
| STOPPED | 해당 소유 실행이 중단됨. 연습 종료나 과제 수행 완료의 증거가 아님 |
| FAILED | 해당 실행을 정상적으로 유지/정리했다고 확인하지 못함 |
| INTERRUPTED | 이전 프로세스가 OPEN을 남김. 정확한 종료 시각은 미확인 |

종료 쓰기는 같은 run/프로세스 소유자의 OPEN만 update한다. 이미 terminal이거나 없는 행은 성공으로 만들지 않고 삽입하지 않는다. STOPPED/FAILED의 null 종료 시각도 명시적인 미확인 값이며 0이나 현재 시각으로 채우지 않는다. 시각 조회나 정상 종료 저장을 확인하지 못하면 가능한 범위에서 FAILED/null로 남기며 저장 실패 자체도 정상 완료로 바꾸지 않는다.

새 프로세스는 이전 프로세스의 OPEN을 INTERRUPTED/null로 바꾼다. 종료 콜백이 실행됐다고 가정하거나 재진입 시각을 실제 종료 시각으로 저장하지 않는다. 중단 경고만으로 배경 기록을 재개하지 않으며 새 명시적 시작은 새 run을 사용한다. 과거 FAILED를 다시 표시할 때 저장되지 않은 원인을 추정하지 않는다. OPEN과 terminal marker는 수집기 실행 경계이며 정확한 공백 시간이나 연속 SDK 수집의 증거가 아니다.

[명시적 v4→v5 마이그레이션](../../core/database/src/main/kotlin/io/github/hangillab/hangil/core/database/PreparationMigration4To5.kt)은 새 테이블과 인덱스만 추가하고 기존 v1/2/3/4→v5 연결을 등록한다. destructive fallback은 사용하지 않는다. [검사된 행](../../core/database/src/main/kotlin/io/github/hangillab/hangil/core/database/VehicleRecordingRunRow.kt)은 값과 함께 원래 SQLite `typeof()`를 읽어 ID/상태/시각/slot 자료형과 상태 조합을 검증한다. 손상된 행을 건너뛰거나 정상값으로 보정하지 않는다.

run은 세션의 FK CASCADE 자식이다. [종료 기록 삭제](../../core/database/src/main/kotlin/io/github/hangillab/hangil/core/database/RoomSessionDeletionRepository.kt)의 확인 스냅샷에 순서가 있는 전체 run 목록을 포함한다. 확인 이후 종료 상태가 바뀌거나 새 marker가 생기면 이전 확인으로 지우지 않는다. 삭제 뒤 늦은 종료/복구는 update-only이므로 자식이나 세션을 되살리지 않는다. run이 없는 이전 기록의 빈 목록은 명시적으로 비교하는 정상 상태다.

## COMMIT 취소와 종료 통지

`9fbf4d2`는 open COMMIT 뒤 결과 전달 취소와 FinishedSession이 세션 통지보다 먼저 도착하는 순서를 수정했다. open 결과 수신과 opened 영수증 게시만 NonCancellable로 보호하되 원래 prepare context의 취소/isStarting/현재 허용 gate를 COMMIT 전에 확인한다. 보호 밖에서 원래 context를 확인한 뒤에만 native dispatch한다. cleanup은 prepare join 뒤 정확한 run/process를 정리한다. FinishedSession만 정상 STOPPED/knownEnd/optOut으로 분기하며 다른 실패, cleanup 실패와 state-before-owner 순서는 유지한다.

이 경계는 domain과 실제 Room adapter의 제어된 RED/GREEN으로 확인했다. 특정 Room dispatcher나 SDK 동작의 직접 재현을 뜻하지 않으며, 아래 초기 v1 계측을 이 수정의 성능 근거로 사용하지 않는다.

## 기존 이력 진입의 변경 전후 관찰

이 절은 고정 `background-recording-entry-v1`로 초기 구현을 비교한 역사적 계측이다. 변경 후 제품은 `de6357ff94d49bc1a88fbdbda7ea1a86b0612894`까지이며, 이후 상태 순서를 수정한 `a2cf9e2`나 후속 관찰 하네스는 측정 대상에 포함하지 않았다. 아래 원값과 통계는 그대로 보존하며 최종 앱 성능, 이번 결함 수정의 개선율이나 무회귀를 입증하는 자료로 차용하지 않는다.

속도 병목을 관찰한 최적화가 아니다. 내비 중 새 수집에는 동등한 변경 전 기능이 없어 수집 속도의 개선율을 만들지 않는다. 코드 변경 전에 계획과 기준값을 확보하고, 배경 기록을 켜지 않은 기존 이력 진입에서 관찰되는 차이만 비교했다. 두 단계의 같은 입력과 endpoint, 고정 source/catalog/environment/measurement contract를 대조했다.

고정 계측은 `04dc200c31843d6984d76b386da85e25a3cfe204`의 `background-recording-entry-v1`이다. 변경 전 제품은 `ebd9ebd859b8a90386bb5d74a58fab2e397ba068`, 변경 후는 `de6357ff94d49bc1a88fbdbda7ea1a86b0612894`다. 변경 전후 APK의 hash는 서로 다르다. 각 단계에서 기록한 제품/계측 APK hash와 실제 설치 APK hash의 일치를 확인했으며 원형과 hash는 비공개로 보존한다. 양쪽 고정 계측 소스 hash, harness 선언과 contract/source/catalog/environment/originalData는 같았다.

조건은 JDK17.0.21+9, debug, Pleos API34, 글꼴1.0/라이트, 같은 현재 사용자, 준비1/종료11/관찰17/메모1/활성 없음, 같은 카탈로그와 실제 SDK P+UX 허용이다. v5의 빈 구간 테이블과 normal FGS 선언은 의도한 제품 차이로 분리했다. 양쪽 측정이 끝나기 전에는 검사 소유 세션을 만들거나 새 서비스를 켜지 않았다.

실행 순서는 B1→F1이다. 각 단계 fresh process/Activity 이후 같은 프로세스에서 워밍업5회와 본 표본30회의 이력 열기/닫기를 실행했다. timer는 `home_history` 클릭 직전부터 같은 Ready/원문/Compose idle endpoint까지 `elapsedRealtimeNanos`다. dispatch, 검사와 동기화 비용을 포함하며 앱 전용 frame/GPU/SDK 지연이나 운전자 시간을 뜻하지 않는다. 시작, 사전 스크롤, 닫기, export, 캡처와 대조는 timer 밖이다.

[70행의 익명 원값 CSV](measurements/background-recording-entry-ui-times.csv)는 단계별 워밍업5회/본 표본30회를 모두 보존하며 정수 ns와 정확히 유도한 ms를 담는다. 아래 통계는 각30개 본 표본에만 적용한다. Q1/Q3는 정렬한 하위15개/상위15개의 중앙값이다. 고정20초 endpoint 초과나 원본/입력/P/UX 불일치는 실패로 정했으며 느린 값을 제외하지 않았다.

| 지표 | 변경 전 | 변경 후 |
|---|---:|---:|
| 워밍업/본 표본 | 5/30 | 5/30 |
| 중앙값(ms) | 324.838167 | 339.4698955 |
| Q1/Q3(ms) | 307.284291/363.237208 | 306.209126/420.552417 |
| IQR(ms) | 55.952917 | 114.343291 |
| min/max(ms) | 243.064792/853.199667 | 256.101333/2886.263751 |
| 실패/누락/제외 | 0/0/0 | 0/0/0 |

중앙값 관찰 차이는 **+14.6317285ms**이며 `(변경 후 - 변경 전) / 변경 전 × 100`으로 계산한 관찰 증가율은 **+4.5043132201%**다. 개선율이 아니다. 변경 후 가장 느린2886.263751ms 표본도 원값과 통계에 그대로 포함했고 실패나 제외로 바꾸지 않았다.

IQR 분포가 겹치며 데이터/Tile/DB/코드 캐시를 지우지 않았다. APK 교체, ART/profile/JIT, OS, 발열, 실행 순서와 네트워크 영향을 분리하지 못했다. 따라서 이 관찰만으로 성능 향상, 변경에 의한 인과적 저하, 동등성 또는 무회귀를 확정하지 않는다. B1→F1 한 순서의 debug 웜 실행을 다른 기기, 콜드 실행이나 release에 일반화하지 않는다. 새 서비스에서의 SDK 수집 속도, 배터리, 메모리와 실제 도로 동작은 측정하지 않았다. 테스트 수, 보존 행 수와 JUnit/CI/Gradle 시간은 이 성능 비교를 대신하지 않는다.

## 검증 근거와 실패 한계

[PR64](https://github.com/HanGilLab/Android/pull/64)의 `a2cf9e2`와 후속 `9fbf4d2` 회차는 서로 구분한다. 후속 제품의 기본 라이트와 확대 다크에서 실제 Main display의 내비 전면, Main STOP, 단일 FGS와 같은 OPEN, 해당 run의 새 SDK D/P 저장, 복귀 후 D 잠금, 명시적 STOPPED와 일반 Recording 재개를 확인했다. 별도 Main 전면 종료 fixture는 실제 instrumentation abort이며 JUnit PASS나 내비 전면 종료 실험이 아니다. fresh process에서 INTERRUPTED/null, 자동 배경 재개 없음, 새 명시 시작/중지와 소유 연습 종료를 확인했다. 원래 자료와 권한/설정 보존은 최종 SQLite 전 필드/물리형/순서/sequence 및 소유 추가를 분리해 대조했다.

최초 ActivityScenario와 LifecycleRegistry 제어의 batch/시작 실패, 앱 Navi API 요청 뒤 Main 전면 유지, AX 전면 미확인, Paused 덮어쓰기, interruption 준비와 runner receipt 경합, 후속 본문 시작 전 종료는 이후 성공과 합치지 않는다. 시작 실패의 반복성 한계와 미확정 원인은 남아 있다. REQUESTED, 사후 focus, 빈 root나 원인을 알 수 없는 프로세스 종료로 SDK 성공/실패나 결함 부재를 단정하지 않는다. 실행별 상세 이력은 PR64에서 추적한다.

[BackgroundRecordingBoundaryTest](../../app/src/androidTest/kotlin/io/github/hangillab/hangil/BackgroundRecordingBoundaryTest.kt)는 `manualBackgroundRecordingBoundary=true`에서 실제 Compose와 합성 LifecycleRegistry/source/controller/gate의 정책을 확인한다. 옛 token, source/gate 변경, 폐기와 정확한 run 조건을 검사하지만 실제 SDK, 서비스와 Room을 사용하지 않는다. PAUSE/STOP/RESUME 입력도 실제 OS ActivityScenario 전환 성공으로 기록하지 않는다. 실제 Activity의 초기 RESUMED/전면을 확인하고 도중 수명이 바뀌면 환경 조건 실패로 처리한다. v2의 본문 다섯 개와 별도 단독 한 개 통과를 단일 batch 전체 통과로 바꾸지 않는다.

## 재현과 전면 관찰

[BackgroundVehicleRecordingFlowTest](../../app/src/androidTest/kotlin/io/github/hangillab/hangil/BackgroundVehicleRecordingFlowTest.kt)는 `manualBackgroundRecording=true`, `backgroundRecordingStage=flow|interrupt|recover`, 외부에서 실제 확인한 `expectedNavigationPackage`/`expectedNavigationComponent`, `expectedFontScale=1.0`/`expectedDark=false` 또는 `1.3`/`true`, `measurementComparisonCompleted=true`를 요구한다. component 형식과 package/내부 class 경계를 검사하고 설치/exported 접근은 실제 OS dispatch로 판정한다. 임의 대상 fallback이나 제품 package queries/권한 확대를 사용하지 않는다. 이 시스템 launcher handoff를 MAP-04 앱 Navi API, 목적지 전달이나 길안내 성공으로 해석하지 않는다.

전면 관찰은 실제 Main이 RESUMED일 때 확보한 유효 display의 `windowsOnAllDisplays` bucket을 사용한다. null display를0으로 바꾸지 않는다. UiAutomation의 `FLAG_RETRIEVE_INTERACTIVE_WINDOWS` 원래 bit를 보존하고 필요 시 설정/readback한 뒤 다른 flags를 유지해 복원한다. 각 window의 display가 일치하고 focused window가 정확히 하나일 때 실제 root package만 읽는다. SYSTEM/IME를 버려 기대 앱을 찾거나 `isActive`로 focus를 대신하지 않는다. 부재, null root/package, 복수 focus, display 모순과 API 실패는 사유가 있는 unknown이며 기본값으로 채우지 않는다.

실제 다른 package는 즉시 실패다. null만 기존20초/100ms 범위에서 재관찰하며 매번 Main STARTED 미만, 같은 Active/OPEN과 단일 FGS를 확인한다. deadline의 unknown은 실패로 남긴다. private proof의 display/focus/flag/명시적 null/unknown 사유와 표본 수는 UI 관찰 근거이며 SDK 손실 수가 아니다. shell fallback은 없고 focused package만으로 내비 렌더 완성이나 Navi SDK 성공을 주장하지 않는다.

DDS 이후 같은 소유 세션/run의 실제 SDK 저장과 출처를 확인하며 UI 문자열만으로 저장을 판단하지 않는다. 프로세스 종료 fixture는 원형을 보존한 별도 fresh process 복구까지 확인해야 한다. 계측은 프로세스 우선순위에 영향을 줄 수 있어 자연스러운 배경 생존이나 모든 OS 종료 정책을 보장하지 않는다. 원래 전체 자료와 의도한 소유 추가, 실패/진단 run을 분리하고 실패 marker를 성공 run으로 바꾸거나 임의 삭제하지 않는다. 권한/설정을 복원한 뒤 자신이 만든 자원만 정리한다. 원문 DB, SDK 값/시각, 식별자, 개인 경로, 내비 화면과 플랫폼 로그는 비공개로 관리한다. 자동 검사, 실제 시나리오와 최신 PR CI/Codex를 각각 판정하며 다른 PR의 원래 최종 리뷰 추적도 별도로 유지한다.
