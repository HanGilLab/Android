# 내비 전면 중 차량 상태 기록과 중단 표시

APP-05의 차량 상태 기록 유지와 중단 표시를 연결한 구현이다. a2cf9e2의 양 테마와 Main 전면 프로세스 중단/복구, 최종 DB exact 대조와 환경 복원을 확인했다. 최신 리뷰에서 추가 지적된 경계는 후속 수정/재검증 전까지 미해결로 유지한다. 기본은 활성 연습과 앱 화면의 STARTED 수명에 따른 기존 수집이다. 주차와 UX 허용이 모두 확인된 상태에서 사용자가 명시적으로 켠 경우에만 foreground service가 같은 연습의 차량 관찰을 소유한다. 위치, 실제 주행 경로, 목적지 전달, 길안내와 차량 제어는 추가하지 않는다.

앞선 실제 앱 검증의 제품 기준은 `a2cf9e2`다. 최신 리뷰의 두 추가 경계는 후속 수정 코드와 별도로 재검증한다. 이전 제품 `de6357ff94d49bc1a88fbdbda7ea1a86b0612894`에서 실제 시스템 내비 전환 중 수집을 확인했고, 뒤이은 다크 실행에서 수집 재개와 Paused 표시가 갈라지는 상태 덮어쓰기를 발견했다. 즉시 전면 재진입을 재현한 뒤 상태를 먼저 준비하고 소유권을 마지막에 반환하는 최소 수정을 적용했다. 수정 제품의 전체 자동 검사는765개 통과했고 실제 기본 라이트와 확대 다크의 전체 흐름도 각각 통과했다. 초기 AX 전면 미확인 실패는 보존했으며, 최종 다크는 실제 Main display의 focused window를 관찰하는 `2d77775` test 하네스로 통과했다. 이어 Main 전면에서 의도적으로 프로세스를 종료한 실제 abort와 fresh process 복구를 분리해 확인했다. 소유 검증 캐시는 completed이며 최종 DB exact 대조와 환경 복원을 확인했다.

초기 v1 변경 전후 계측은 `de6357ff94d49bc1a88fbdbda7ea1a86b0612894`까지의 자료이며 최종 제품 성능의 근거가 아니다. 구현과 빌드, 이번 실제 흐름의 성공을 플랫폼의 일반 지원이나 APP-04 위치/경로와 MAP-04 길안내까지 충족한 APP-05 전체 완료로 계산하지 않는다. 실행 결과, 최초 실패와 최신 PR/리뷰 조건은 [작업 계획](../plans/active/2026-10-31-background-vehicle-recording.md)에 구분해 기록한다.

## 수집 소유권과 화면 경계

[PracticeRecordingController](../../core/domain/src/main/kotlin/io/github/hangillab/hangil/core/domain/PracticeRecordingController.kt)는 하나의 recorder와 공통 작업 잠금으로 일반 전면 수집과 서비스 수집을 직렬화한다. 전면의 `owner.collectLatest`가 기존 작업을 취소하고, 서비스 준비와 수집은 같은 작업 잠금을 획득한 뒤 진행한다. 종료 처리도 준비 작업과 수집 작업의 `cancelAndJoin()`을 기다린다. 따라서 별도 수집기를 만들어 같은 세션에 동시에 쓰는 방식을 사용하지 않는다.

[BackgroundRecordingRoute](../../feature/home/src/main/kotlin/io/github/hangillab/hangil/feature/home/BackgroundRecordingRoute.kt)와 [ViewModel](../../feature/home/src/main/kotlin/io/github/hangillab/hangil/feature/home/BackgroundRecordingViewModel.kt)은 표시한 세션 전체 스냅샷, 배경 상태의 표시 당시 객체, 요청 token과 현재 RESUMED 호스트를 고정한다. 명시적 시작 전, marker 저장과 서비스 수신 경계에서 최신 주차와 UX 허용을 다시 확인한다. PAUSE/STOP/폐기와 source 교체 뒤 옛 클릭이 새 화면이나 다른 세션의 허용 조건을 빌리지 못하게 한다. P 복귀만으로 거부했던 시작을 다시 실행하지 않는다.

서비스 수신 뒤에는 고정한 실행과 세션에만 수집을 연결한다. 같은 세션이 종료되면 중단하고, 다른 세션으로 바뀌거나 읽기가 실패하면 실패로 표시한다. 새 활성 세션을 자동 채택하지 않는다. 서비스 실행 중 SDK의 D 상태 관찰과 상세 입력 허용은 별개다. 상세 조작은 계속 P와 UX 허용을 모두 요구한다.

배경 기록 중지는 해당 run만 정리하며 연습 자체를 종료하지 않는다. 원래 세션 source를 더 읽지 못해도 남아 있는 소유 run의 정리 상태와 정확한 중지 token을 유지한다. 이 경우 새 시작은 제공하지 않는다. 실행 실패 중 일반 전면 재시도를 숨은 배경 재개로 사용하지 않으며 새 명시 동작을 요구한다.

## 일반 전면 재진입과 상태 쓰기 순서

구형 `de6357ff94d49bc1a88fbdbda7ea1a86b0612894`의 뒤이은 실제 다크 실행에서는 내비 전면 중 SDK D/P가 저장됐고 Main STOP과 복귀 후 D 상세 잠금도 확인됐다. 명시적 기록 끄기 뒤 run은 STOPPED, 백그라운드 상태는 Disabled, native FGS는0개, 실제 Main은 RESUMED였다. 그런데 일반 기록 상태가 Recording으로 바뀌지 않고15초 대기 뒤에도 Paused여서 실행은 실패했다. 끄기 뒤에도 새 SDK 관찰 행이 존재해 수집 재개 자체보다 표시 상태가 덮이는 문제로 좁혔다. 이 실패는 앞선 AX 미확인과 별개로 보존한다.

`owner` StateFlow에 `null`을 먼저 쓰면 일반 전면 수집자가 즉시 재진입해 Recording 또는 Idle을 발행할 수 있었다. 기존 종료 흐름은 그 뒤에 Paused를 기록해 새 수집자의 상태를 다시 덮었다. 같은 스레드의 재진입은 `synchronized`만으로 차단되지 않는다. controller36개 항목에서 기존31개는 통과했지만 새 즉시 재진입5개는 모두 실패했다. 기대한4개 Recording과1개 Idle이 Paused로 남아 실제 관찰과 같은 순서 문제를 재현했다.

수정은 [PracticeRecordingController](../../core/domain/src/main/kotlin/io/github/hangillab/hangil/core/domain/PracticeRecordingController.kt)의 소유권 반환4개 분기에서 백그라운드 상태와 일반 상태를 먼저 준비하고 `owner.value = null`을 마지막으로 옮긴 것이다. 이미 정리된 run의 명시적 끄기와 종료 정리의 각 반환 경로에 같은 순서를 적용했다. 다음 전면 수집자가 재진입해 발행한 상태를 이전 실행의 후속 쓰기가 덮지 않게 하며 Recording을 무조건 정상값으로 넣지 않는다. 활성 연습이 없을 때 Idle을 고르는 기존 수집자의 책임도 유지한다.

수정 제품 `a2cf9e2`에서 전체 자동 검사는765개 통과했다. 실제 기본 라이트와 확대 다크에서는 일반 전면 Recording 재개까지 각각 전체 흐름을 통과했다. 최종 다크는 display-scoped `2d77775` test 하네스의 결과다. Main 전면 프로세스 중단 뒤 별도 복구도 통과했지만 이 revision의 최종 DB exact 대조와 환경 복원을 확인했다. 자동 검사의 RED/GREEN, 실제 흐름 성공과 최종 보존/복구 완료 판정을 구분한다. 이 수정의 SDK 처리나 상태 전이 성능은 동일 조건의 전후 실측을 확보하지 않았다.

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

## 실제 검증 상태와 재현 경계

| 항목 | 현재 확인 상태 |
|---|---|
| 현재 제품과 최종 다크 계측 기준 | 제품 `a2cf9e2`, 최종 display-scoped test 하네스 `2d77775` |
| 수정 전 즉시 전면 재진입 | controller36개 중 기존31개 통과, 새5개 실패.4개 Recording과1개 Idle 기대가 Paused로 남음 |
| 수정 후 전체 자동 검사와 debug/unsigned release |765개 통과. 해당 제품의 자동 검사와 빌드 성공이며 실제 흐름과 최종 보존/환경 확인을 구분 |
| 초기 변경 전/후 이력 진입 v1 계측 | `de6357ff94d49bc1a88fbdbda7ea1a86b0612894`까지 각5회 워밍업/30본 성공. 최종 앱이나 상태 순서 수정의 성능 증거가 아님 |
| 최초 ActivityScenario 제어 native | 최초6개는5통과/1실패. 해당 항목 단독1개 통과. 전체 재실행6개는4통과/2실패 |
| 명시적 LifecycleRegistry 제어 native v2 | batch5개 본문 통과/1개 본문 진입 전 Activity 시작 실패. 같은 APK의 해당 항목 단독1개 통과. 단일 전체 batch 성공은 아님 |
| 구형 제품의 앱 Navi API 전면 전환 | 자동2회 실패. 수동으로 REQUESTED 표시를 확인했지만 Main 전면 유지. 실제 전환 성공과 원인은 미확인 |
| 구형 제품의 시스템 launcher 기본 라이트 | 실제 내비 전면과 Main STOP, FGS 단일 소유, 새 Room SDK D/P, 복귀와 D 잠금, 명시적 중지의 전체 흐름1회 통과 |
| 구형 제품의 다크 AX 전면 관찰 | D 행 저장 뒤 AX root/package 미확인으로 실패. Main STOP과 FGS/Active/OPEN은 별도 확인했지만 전체 성공으로 세지 않음 |
| 구형 제품의 뒤이은 다크 상태 덮어쓰기 | D/P 저장과 복귀, D 잠금, STOPPED/Disabled/FGS 종료 이후 일반 상태 Paused로 실패. 끄기 뒤 SDK 행도 있어 실제 수집과 표시가 갈라짐 |
| 수정 제품의 최종 기본 라이트 | 실제 전체 흐름 통과. 새 SDK D/P 저장과 일반 전면 Recording 재개 포함 |
| 수정 제품의 display-scoped 이전 다크 시도 | 초기 AX 전면 확인이25초 동안 미확인이어서 실패. 이 원형은 최종 재실행 성공과 별도로 보존 |
| 수정 제품의 최종 확대 다크 | `display-fixed-flow-dark` 실제 전체 흐름 통과. Main display의 실제 focused package, Main STOP, 같은 FGS/OPEN, 새 SDK D/P, 복귀와 D 잠금, 명시적 중지와 Recording 재개 확인 |
| 첫 실제 interruption 준비 | 초기 전면 조건 실패. 새 run은 생성되지 않음 |
| interruption 재시도 | READY 이후 Main 전면에서 부모 실행기가 명시적 force-stop. 실제 instrumentation abort이며 JUnit PASS가 아님 |
| fresh process recovery | 별도 실행 OK1과 RECOVERY_COMPLETED. 이전 OPEN의 INTERRUPTED/null, 자동 배경 재개 없음, 새 명시적 시작/중지와 소유 연습의 최종 종료 확인 |
| runner receipt 경합 | 부모 receipt 파일 생성 전에 원 runner가 읽어 FileNotFound. 원로그 보존 후 사후 읽기 전용 대조로 reconcile했으며 프로세스를 다시 종료하지 않음 |
| 실행 소유 자료의 현재 기록 | owner completed, 소유 run11개는 STOPPED10개와 INTERRUPTED/null1개, 추가 관찰89개. 실패와 진단 run을 삭제하거나 최종 성공 run으로 바꾸지 않음 |
| 최종 원본 exact 대조와 환경 복원 | 원래 준비/종료11/관찰17/메모1 전 필드/물리형/순서/sequence와 소유 추가를 분리해 exact 대조 PASS. 원래 권한/설정 재조회와 소유 도구 정리 완료 |

### 제어 정책과 실제 OS 수명의 구분

최초 ActivityScenario 제어 실행의 foreign source/폐기 시나리오는 재표시 대기 중 실제 호스트 STOPPED가 관찰됐고 finally의 RESUMED 전환도 성립하지 않았다. 같은 APK의 해당 항목 단독 실행은 통과했다. 원래 전체6개를 다시 실행했을 때는 STARTED 요청 미성립과 foreign stop 조회 직후 노드 소실로2개가 실패했다. 최초 실패 시점을 포함한 연속 window/lifecycle 자료가 충분하지 않아 환경, UX 또는 제품 원인 중 하나로 확정하지 않는다. 사후 내비 focus나 cleanup STOPPED만으로 원인을 단정하지 않으며 재시도 통과가 최초 실패를 지우지 않는다.

[BackgroundRecordingBoundaryTest](../../app/src/androidTest/kotlin/io/github/hangillab/hangil/BackgroundRecordingBoundaryTest.kt)의 v2는 `manualBackgroundRecordingBoundary=true`에서 실제 Compose와 명시적으로 주입한 synthetic LifecycleRegistry/source/controller/허용 gate의 정책을 검사한다. 기존 여섯 항목의 옛 token 거부, source/gate 변경, 폐기와 정확한 run 조건을 유지한다. 실제 Activity는 초기 RESUMED와 전면을 확인하고 도중 PAUSE/STOP/DESTROY가 발생하면 환경 조건 실패로 처리한다. PAUSE/STOP/RESUME 정책 입력은 주입한 registry를 움직이며 실제 OS ActivityScenario 전환 성공으로 주장하지 않는다. 실제 SDK, 서비스와 Room은 이 검사에서 사용하지 않는다.

v2 batch의 표시 receipt 항목은 `ActivityScenarioRule.before`의 `launchInternal` 시작 상태 확인에서 실패해 본문에 진입하지 못했다. 같은 v2 APK의 해당 항목 단독 실행은 통과했다. 따라서 제어 정책의 근거는5개 batch 본문과1개 단독 실행이며 단일 batch 전체 통과가 아니다. 실제 Activity 시작의 반복성 한계와 앞선 실패는 남겨 둔다. 이 test-only 범위 변경은 초기 v1 비교 계측의 frozen 소스나 원값을 바꾸지 않았다.

### 실제 전환, 저장과 표시 상태의 구분

구형 제품의 기본 라이트 자동2회는 같은 소유 연습에서 FGS 하나, controller Active와 OPEN을 확인한 뒤 앱 내비 버튼을 입력했으나 Main이 전면에 남아 실패했다. 정리한 두 run과 수동 FGS 켜기/끄기의 진단 run은 STOPPED로 보존했다. 완전히 보이는 버튼의 실제 터치에서도 REQUESTED가 표시됐지만 Main 전면이 유지됐다. REQUESTED는 실제 전면 전환이나 렌더 완료의 증거가 아니며 이 결과의 원인도 확정하지 않는다.

APP-05 차량 기록은 이 앱 Navi API 호출에 기술적으로 의존하지 않아 [BackgroundVehicleRecordingFlowTest](../../app/src/androidTest/kotlin/io/github/hangillab/hangil/BackgroundVehicleRecordingFlowTest.kt)는 별도로 실제 내비 앱을 OS launcher intent로 연다. 외부에서 이미 확인한 `expectedNavigationPackage`와 `expectedNavigationComponent`를 명시하고 component 형식, package 일치와 내부 class 경계를 확인한다. 설치와 exported 접근은 실제 OS dispatch가 판정한다. 제품의 package queries나 권한을 확대하지 않았으며 임의 대상을 허용하는 fallback도 두지 않는다. 이 실제 시스템 handoff 결과를 MAP-04 앱 Navi API, 목적지 전달이나 길안내 성공으로 사용하지 않는다.

시스템 handoff 이후 구형 기본 라이트는 전체 흐름을 통과했다. 구형 다크의 한 시도는 실제 D 행 저장 뒤 AX 전면 package가 미확인이어서 실패했고, 별도 다크 시도에서는 D/P 저장, Main 복귀와 D 잠금, 명시적 중지가 진행된 뒤 일반 기록 표시가 Paused로 남았다. 후자의 실제 수집/표시 불일치가 state-first/owner-last 수정으로 이어졌다. 서로 다른 실패를 통합하거나 앞선 terminal marker를 성공한 최종 run으로 바꾸지 않는다.

수정 제품 `a2cf9e2`의 기본 라이트는 일반 전면 Recording 재개까지 통과했다. 이후 다크의 초기 AX 전면 미확인 시도도 실패로 보존하며, 빈 root만으로 실제 내비가 전면이 아니었다거나 SDK 수집이 실패했다고 단정하지 않는다. 사후 WindowManager의 내비 focus를 실행 중 성공으로 대신하지 않았다. 후속 `2d77775` test 하네스의 `display-fixed-flow-dark`는 실제 Main display에서 focused package를 확인하고 전체 흐름을 통과했다. 최종 양 테마에서는 같은 소유 연습에 새 SDK D/P 관찰이 저장됐고, 실제 내비 전면/Main STOP과 FGS/OPEN, 복귀 후 D 잠금, 명시적 중지와 일반 Recording 재개를 확인했다. 이 양 테마의 수집 결과와 별도의 프로세스 중단/복구 결과를 구분한다.

### 현재 test-only 전면 관찰

현재 소스는 `rootInActiveWindow`의 display가 불분명한 값을 실제 Main 전면으로 채택하지 않는다. `assertEnvironment`에서 실제 Main이 RESUMED인 main thread에서 유효한 display를 확보하며 null을 기본 display0으로 바꾸지 않는다. UiAutomation의 기존 `FLAG_RETRIEVE_INTERACTIVE_WINDOWS` bit를 저장하고 필요할 때만 켠 뒤 readback으로 확인한다. `@After`에서는 그때의 다른 flags를 보존하면서 원래 bit만 복원하고 확인한다. 앱 권한이나 제품 서비스 설정을 추가하지 않는다.

관찰은 `windowsOnAllDisplays`의 고정한 Main display bucket에서 각 window의 display 일치를 확인하고, focused window가 정확히 하나일 때 그 실제 root의 package만 읽는다. SYSTEM/IME window를 버리고 expected 앱을 찾지 않으며 `isActive`만으로 focus를 대신하지 않는다. window 부재, null root/package, 복수 focus, display 모순과 API 읽기 실패는 사유가 있는 unknown이다. 기대 package를 기본값으로 채우지 않는다.

D/P 뒤 전면 유지 확인에서 실제 다른 nonnull package는 즉시 실패한다. null 관찰만 기존20초 한도와100ms 간격으로 재관찰하며 매회 실제 Main의 STARTED 미만 상태와 같은 Active/OPEN, native FGS 단일 소유를 확인한다. expected package가 실제 관찰된 경우만 해당 전면 조건을 통과한다. deadline까지 unknown이면 실패로 남긴다. 전송 자체가 막히는 문제와 UI 관찰 unknown도 구분한다.

private proof는 AX focused-window basis, 실제 package의 명시적 JSON null, focus 수, flag 관찰값, Main display 확인 여부와 display 일치, unknown 사유와 표본 수를 남긴다. 이 수치는 UI 관찰의 미확인 표본이며 SDK 이벤트 누락 수가 아니다. 현재 방식에 WindowManager shell fallback은 추가하지 않았다. focused package 관찰만으로 내비 화면의 렌더 완성이나 앱 Navi SDK 성공을 주장하지 않는다. 소스 반영만으로 성공을 계산하지 않았으며 이 관찰 방식의 실제 최종 다크 전체 실행은 통과했다. 그 결과도 내비 렌더 완성이나 MAP-04 앱 Navi API 성공으로 확대하지 않는다.

### Main 전면 프로세스 중단과 복구

첫 interruption 준비는 초기 실제 전면 조건에서 실패해 새 run을 만들지 않았다. 재시도는 실제 Main 전면에서 OPEN/Active/FGS와 READY를 확인한 뒤 부모 실행기가 명시적으로 force-stop했다. 이 실행은 의도한 실제 instrumentation abort이며 JUnit 통과나 정상 종료가 아니다. 내비 전면에서 종료한 실험으로 서술하지 않는다.

원 runner가 부모 receipt 파일이 생성되기 전에 읽어 FileNotFound로 끝난 별도 경합도 원로그에 보존했다. 사후 읽기 전용 대조로 이미 수행된 종료와 receipt를 reconcile했으며 같은 프로세스를 다시 종료하지 않았다. 이 orchestration 실패를 앱 복구 실패나 JUnit PASS로 바꾸지 않는다.

fresh process의 별도 recovery는 OK1과 RECOVERY_COMPLETED로 끝났다. 이전 OPEN은 INTERRUPTED와 `null` 종료 시각으로 남았고 자동 배경 재개나 새 run 자동 생성은 없었다. 새 명시적 시작과 중지, 소유 연습의 최종 UI 종료를 확인했으며 과거 중단 marker는 보존했다. 현재 owner는 completed이고 실행 소유 run11개는 STOPPED10개와 INTERRUPTED/null1개이며 추가 관찰89개가 기록됐다. 이 flow와 marker 확인은 최종 오프라인 DB exact 대조나 전체 환경 복원 확인을 대신하지 않는다.

### 재현과 최종 완료 조건

재현에는 `manualBackgroundRecording=true`, `backgroundRecordingStage=flow|interrupt|recover`, 외부에서 실제 확인한 `expectedNavigationPackage`와 `expectedNavigationComponent`, `expectedFontScale=1.0/expectedDark=false` 또는 `1.3/true`, `measurementComparisonCompleted=true`가 필요하다. opt-in 미지정에 따른 skip은 성공이 아니다. DDS 전환 이후 같은 소유 세션에 새로 저장된 실제 SDK D/P 관찰과 출처를 확인해야 하며 UI 문자열만으로 저장 성공을 판단하지 않는다. 실제 Main STOP, 같은 OPEN과 native FGS, 복귀 후 D 상세 잠금, 명시적 중지와 일반 Recording 재개 조건도 유지한다.

외부 프로세스 종료 준비 메서드는 실제 실행을 중간에서 종료하도록 만든 fixture다. 의도적인 instrumentation abort를 JUnit 통과나 정상 완료로 세지 않는다. 원형을 보존하고 fresh process의 별도 복구 시나리오에서 INTERRUPTED/null, 새 run 자동 생성 없음, 새 명시 시작/중지와 소유 연습의 최종 종료를 확인해야 한다. 계측은 프로세스 우선순위에 영향을 줄 수 있어 자연스러운 백그라운드 생존이나 모든 OS 종료 정책의 보장으로 일반화하지 않는다.

원래 준비/종료 세션/관찰/메모와 의도한 설치 차이, 실제 실행을 위해 추가한 소유 세션/관찰/run을 분리한다. 소유 연습은 recovery 마지막 UI 단계에서 종료됐으며 owner completed와 run11개, 추가 관찰89개의 근거를 보존한다. 이들을 임의로 삭제하거나 새 세션으로 교체하지 않는다. 최종 SQLite의 원본 전 필드/물리형/행순서와 허용한 소유 추가에 따른 정확한 sequence 증가, FK와 무결성은 명시적인 역할별 manifest로 오프라인 exact 대조를 통과했다. 원본 준비1/종료11/관찰17/메모1과 소유 종료 연습1/추가 관찰89/실행11을 분리했다. 실행은10 STOPPED와 종료 시각 null의1 INTERRUPTED이며 실패5/구 runtime 성공1/수동 진단1을 최종 native 성공과 합치지 않았다. grants/runtime/font/night/IME/proxy도 복원 후 새 DB 복사와 재대조를 통과했다. 이 개수는 정확성과 보존 근거이며 성능 수치가 아니다. 원문 DB, 식별자, 개인 경로, 내비 화면과 플랫폼 로그는 비공개로 보존한다. 결과를 확보한 단계만 작업 계획과 이 문서에 반영하고 원래 환경을 복구한 뒤 실행이 소유한 AVD/forward/임시 탭만 정리한다.

실제 수집/표시 상태 경합의 수정과 최종 양 테마, Main 전면 중단/복구 근거를 확보했다. 최종 DB exact 대조와 환경 복원을 확인했고 소유 AVD/forward/DDS 임시 탭만 정리했다. README는 실제 수집/표시 상태 경합의 새 사례로 기존4번을 교체하고 나머지 사례와 순서를 유지한다. 실제 사용 영향, Coroutine 즉시 재진입과 소유권 반환, 서비스와 영속 미확인 경계를 함께 설명하는 근거가 기존 단일 SQLite 물리형 사례보다 강하다고 판단한 이유를 계획에 기록했다. 최신성이나 검사 개수만으로 교체하지 않는다. 공개용 스크린샷이나 빈 자리표시자는 만들지 않았다. 이 문서의 작성과 배치는 앱 실행 경로를 바꾸지 않아 추가 성능 측정 대상이 아니며 링크, 초기 원값 대조와 공개 콘텐츠로 검증한다.
