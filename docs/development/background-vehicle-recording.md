# 내비 전면 중 차량 상태 기록과 중단 표시

APP-05의 차량 상태 기록 유지와 중단 표시를 연결한 부분 구현이다. 기본은 활성 연습과 앱 화면의 STARTED 수명에 따른 기존 수집이다. 주차와 UX 허용이 모두 확인된 상태에서 사용자가 명시적으로 켠 경우에만 foreground service가 같은 연습의 차량 관찰을 소유한다. 위치, 실제 주행 경로, 목적지 전달, 길안내와 차량 제어는 추가하지 않는다.

제품 `de6357ff94d49bc1a88fbdbda7ea1a86b0612894`의 전체 자동 검사, debug/unsigned release와 계측 APK 빌드, 기존 이력 진입의 변경 전후 계측을 마쳤다. 제어 native 정책은 같은 v2 APK의5개 batch 본문과1개 단독 실행으로 확인했으나 전체 batch 성공은 아니다. 실제 Pleos의 서비스 승격과 명시적 켜기/끄기는 부분 확인했다. 앱의 내비 버튼을 통한 전면 전환은 이번 시도에서 실패했으며, 독립적인 시스템 launcher 전환 이후 수집과 프로세스 중단 복구 시나리오는 미실행이다. 구현과 빌드 성공을 플랫폼 지원이나 APP-05 전체 완료로 계산하지 않는다. 실행 결과, 최초 실패와 최신 PR/리뷰 조건은 [작업 계획](../plans/active/2026-10-31-background-vehicle-recording.md)에 구분해 기록한다.

## 수집 소유권과 화면 경계

[PracticeRecordingController](../../core/domain/src/main/kotlin/io/github/hangillab/hangil/core/domain/PracticeRecordingController.kt)는 하나의 recorder와 공통 작업 잠금으로 일반 전면 수집과 서비스 수집을 직렬화한다. 전면의 `owner.collectLatest`가 기존 작업을 취소하고, 서비스 준비와 수집은 같은 작업 잠금을 획득한 뒤 진행한다. 종료 처리도 준비 작업과 수집 작업의 `cancelAndJoin()`을 기다린다. 따라서 별도 수집기를 만들어 같은 세션에 동시에 쓰는 방식을 사용하지 않는다.

[BackgroundRecordingRoute](../../feature/home/src/main/kotlin/io/github/hangillab/hangil/feature/home/BackgroundRecordingRoute.kt)와 [ViewModel](../../feature/home/src/main/kotlin/io/github/hangillab/hangil/feature/home/BackgroundRecordingViewModel.kt)은 표시한 세션 전체 스냅샷, 배경 상태의 표시 당시 객체, 요청 token과 현재 RESUMED 호스트를 고정한다. 명시적 시작 전, marker 저장과 서비스 수신 경계에서 최신 주차와 UX 허용을 다시 확인한다. PAUSE/STOP/폐기와 source 교체 뒤 옛 클릭이 새 화면이나 다른 세션의 허용 조건을 빌리지 못하게 한다. P 복귀만으로 거부했던 시작을 다시 실행하지 않는다.

서비스 수신 뒤에는 고정한 실행과 세션에만 수집을 연결한다. 같은 세션이 종료되면 중단하고, 다른 세션으로 바뀌거나 읽기가 실패하면 실패로 표시한다. 새 활성 세션을 자동 채택하지 않는다. 서비스 실행 중 SDK의 D 상태 관찰과 상세 입력 허용은 별개다. 상세 조작은 계속 P와 UX 허용을 모두 요구한다.

배경 기록 중지는 해당 run만 정리하며 연습 자체를 종료하지 않는다. 원래 세션 source를 더 읽지 못해도 남아 있는 소유 run의 정리 상태와 정확한 중지 token을 유지한다. 이 경우 새 시작은 제공하지 않는다. 실행 실패 중 일반 전면 재시도를 숨은 배경 재개로 사용하지 않으며 새 명시 동작을 요구한다.

## 네이티브 서비스 선택과 한계

[VehicleRecordingService](../../app/src/main/kotlin/io/github/hangillab/hangil/VehicleRecordingService.kt)는 네이티브 승격과 서비스 수명만 담당한다. 현재 Starting 상태의 run/세션과 같은 intent만 받고, 알림을 구성해 `startForeground()`가 성공한 뒤 controller의 `recordInService()`를 호출한다. 중복 intent나 옛 intent가 이미 실행 중인 소유권을 바꾸지 않는다. 시작/승격 실패와 시간 초과를 실패로 전달하며 `START_NOT_STICKY`로 자동 서비스 재시작을 요청하지 않는다.

서비스는 [manifest](../../app/src/main/AndroidManifest.xml)에 `exported=false`, `foregroundServiceType=specialUse`와 사용 목적 subtype을 명시했다. Android의 [FGS 종류](https://developer.android.com/develop/background-work/services/fgs/service-types), [시작과 승격](https://developer.android.com/develop/background-work/services/fgs/launch), [사용자의 앱 종료](https://developer.android.com/develop/background-work/services/fgs/handle-user-stopping)를 작업 계획의 근거로 사용했다. 차량 IPC가 connectedDevice의 외부 장치 용도에 해당한다고 확인되지 않아 해당 종류를 선택하기 위한 더미 권한을 넣지 않았다. specialUse가 이 기능에 적합하다는 판단은 구현 선택이며 Pleos 또는 스토어의 승인 근거가 아니다.

추가 선언은 normal `FOREGROUND_SERVICE`와 `FOREGROUND_SERVICE_SPECIAL_USE`다. `POST_NOTIFICATIONS`, 위치와 다른 dangerous 권한은 새로 요청하지 않는다. [알림 권한](https://developer.android.com/develop/ui/compose/notifications/notification-permission)과 FGS 실행, 실제 알림 표시를 구분한다. 현재 검증 대상인 Pleos API34에서 실제 서비스 승격은 부분 확인했지만 내비 전면 전환/SDK 저장 결과까지 확보한 지원 성공으로 기록하지 않는다. 작업 계획에서 읽은 [Pleos Vehicle 안내](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/Vehicle/intro)만으로 백그라운드 수집이 보장된다고 해석하지 않는다.

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
| 제품 전체 자동 검사와 debug/unsigned release | 통과 |
| 제품 계측 APK와 제어 harness v2 | 빌드 통과. 시스템 내비 handoff를 적용한 후속 actual harness는 빌드 중 |
| 변경 전/후 이력 진입 계측 | 각5회 워밍업/30본 실행 성공. 실패/누락/제외0 |
| 최초 ActivityScenario 제어 native | 최초6개는5통과/1실패. 실패 항목 단독1개 통과. 전체 재실행6개는4통과/2실패 |
| 명시적 LifecycleRegistry 제어 native v2 | batch5개 본문 통과/1개 본문 진입 전 Activity 시작 실패. 같은 APK의 해당 항목 단독1개 통과. 여섯 정책은5+1로 확인했으나 단일 전체 batch 성공은 아님 |
| 실제 기본 라이트 자동 FGS 시도 |2회 모두 native FGS 하나/Active/OPEN까지 확인. 앱 내비 버튼 입력 뒤 Main이 전면에 남아 두 흐름 모두 실패 |
| 실제 수동 native UI | FGS 켜기/끄기 확인. 세 번째 진단 run은 STOPPED. 완전히 보이는 앱 내비 버튼을 직접 터치해 REQUESTED 표시를 확인했으나 Main 전면 유지 |
| 시스템 launcher handoff 뒤 내비 전면/Main STOP 중 SDK D/P 추가 저장 | 미실행. 독립적인 차량 기록 범위의 후속 harness 준비 중 |
| 실제 기본 라이트/확대 다크의 전체 내비/복귀/주행 잠금/명시 중지 | 미완료. 위 부분 확인과 실패를 전체 성공으로 세지 않음 |
| 외부 프로세스 종료 뒤 INTERRUPTED/null와 자동 배경 재개 없음 | 미실행 |
| 중간 자료 보존과 환경 복구 | 원래 schema5 기준의 모든 기존 행과 설정을 대조하고, 같은 소유 활성 세션 하나와 STOPPED 진단 run3개를 구분해 보존 확인. 소유 AVD 정지 후 후속 harness 빌드 중 |
| 전체 시나리오 종료 뒤 최종 자료 보존 | 미실행. 소유 연습의 종료/중단 복구와 실제 추가 자료를 포함한 최종 대조는 별도 |

최초 ActivityScenario 제어 실행의 foreign source/폐기 시나리오는 재표시 대기 중 실제 호스트 STOPPED가 관찰됐고 finally의 RESUMED 전환도 성립하지 않았다. 같은 APK에서 그 항목만 다시 실행했을 때는 통과했다. 원래 전체6개를 다시 실행했을 때는 STARTED 요청 미성립과 foreign stop 조회 직후 노드 소실로2개가 실패했다. 연속 상태 근거가 충분하지 않아 환경, UX 또는 제품 원인 중 하나로 확정하지 않는다. 재시도 통과가 최초 실패를 지우지 않으며 이를 확정된 제품 RED/GREEN 재현으로 제시하지 않는다.

[BackgroundRecordingBoundaryTest](../../app/src/androidTest/kotlin/io/github/hangillab/hangil/BackgroundRecordingBoundaryTest.kt)의 v2는 `manualBackgroundRecordingBoundary=true`에서 실제 Compose와 명시적으로 주입한 synthetic LifecycleRegistry/source/controller/허용 gate의 정책을 검사한다. 기존 여섯 항목의 옛 token 거부, source/gate 변경, 폐기와 정확한 run 조건은 유지했다. 실제 Activity는 초기 RESUMED와 전면을 확인하고 검사 도중 PAUSE/STOP/DESTROY가 발생하면 환경 조건 실패로 처리한다. PAUSE/STOP/RESUME 정책 입력은 주입한 registry를 움직이며 실제 OS ActivityScenario 전환 성공으로 주장하지 않는다. 실제 SDK, 서비스와 Room은 이 검사에서 사용하지 않는다.

v2 batch의 표시 receipt 항목은 `ActivityScenarioRule.before`의 `launchInternal` 시작 상태 확인에서 실패해 본문에 진입하지 못했다. 같은 v2 APK의 해당 항목 단독 실행은 통과했다. 따라서 여섯 제어 정책의 근거는5개 batch 본문과1개 단독 실행이며 단일 batch 전체 통과가 아니다. 실제 Activity 시작의 반복성 한계와 앞선 실패는 남겨 둔다. 이 harness 범위 변경은 제품 APK, 완료된 비교 계측의 frozen 소스나 원값을 바꾸지 않았다.

실제 기본 라이트의 두 자동 시도는 같은 소유 연습에서 native FGS 하나, controller Active와 OPEN marker를 확인한 뒤 앱 내비 버튼을 입력했으나 Main이 전면에 남아 실패했다. 실패 정리에서 두 run은 STOPPED로 보존했으며 소유 연습은 활성 상태로 유지했다. 별도의 실제 native UI 조작으로 FGS 켜기/끄기를 확인한 세 번째 run도 STOPPED다. 이들 terminal marker를 성공한 내비 수집 run으로 바꾸지 않는다. 완전히 보이는 앱 내비 버튼의 직접 터치에서도 REQUESTED가 표시됐으나 Main 전면이 유지돼, 이번 실행에서 앱 Navi API의 실제 전면 전환 성공은 미확인이다. REQUESTED만으로 전면 전환을 판단하지 않으며 원인도 확정하지 않는다.

[BackgroundVehicleRecordingFlowTest](../../app/src/androidTest/kotlin/io/github/hangillab/hangil/BackgroundVehicleRecordingFlowTest.kt)의 후속 actual harness는 APP-05 차량 기록 범위를 독립 검증하기 위해 실제 내비 앱의 OS launcher intent로 handoff하도록 변경했다. `expectedNavigationPackage` 인자의 실제 대상과 조회한 intent의 package/component가 같은지 검증한 뒤 요청한다. 이 방식의 실제 수행은 아직 미실행이다. 이후 실제 내비 전면 패키지, Main STOP, native FGS와 같은 OPEN 소유권, Room의 실제 SDK D/P 추가 저장, 한길 복귀 후 상세 잠금과 복구 assertion은 그대로 요구한다. 이 별도 경로를 MAP-04의 앱 Navi API 성공, 목적지 전달이나 길안내 성공의 증거로 사용하지 않는다.

재현에는 `manualBackgroundRecording=true`와 `backgroundRecordingStage=flow|interrupt|recover`, 실제 확인한 `expectedNavigationPackage`, `expectedFontScale=1.0/expectedDark=false` 또는 `1.3/true`, `measurementComparisonCompleted=true`가 필요하다. opt-in 미지정에 따른 skip은 성공이 아니다. DDS 전환 이후 같은 세션에 추가된 SDK 관찰을 확인해야 하며 UI 상태 문자열만으로 저장 성공을 판단하지 않는다. 계측은 프로세스 우선순위에 영향을 줄 수 있어 자연스러운 백그라운드 생존이나 모든 OS 종료 정책의 보장으로 일반화하지 않는다.

외부 프로세스 종료 준비 메서드는 실제 실행을 중간에서 종료하도록 만든 fixture다. 의도적인 instrumentation abort를 JUnit 통과나 정상 완료로 세지 않는다. 원형을 보존하고 fresh process의 별도 복구 시나리오에서 INTERRUPTED/null, 새 run 자동 생성 없음, 새 명시 시작/중지와 소유 연습 종료를 확인해야 한다.

원본 준비/종료 세션/관찰/메모와 의도한 설치 차이, 실제 검사를 위해 추가한 소유 세션/관찰/run을 분리한다. 현재 중간 대조에서 보존한 활성 소유 연습과 진단 run을 임의로 삭제하거나 새 것으로 교체하지 않는다. 최종 SQLite의 모든 값/자료형/순서/sequence, FK와 무결성, grants/runtime/font/night/IME/proxy를 다시 대조해야 한다. 원문 DB, 식별자, 개인 경로, 내비 화면과 플랫폼 로그는 비공개로 보존한다. 결과를 확보한 단계만 작업 계획과 이 문서에 반영하고 원래 환경을 복구한 뒤 실행이 소유한 AVD/forward/임시 탭만 정리한다.

현재는 실제 내비 전면 수집과 프로세스 복구의 전체 근거가 아직 없어 README의 기존 네 사례와 순서를 유지한다. 실제 근거가 확보되면 새 비동기 소유권/영속 미확인 경계가 기존 네 사례보다 기술 판단과 사용자 영향을 더 잘 입증하는지 비교해 교체 이유를 계획에 남긴다. 최신성이나 검사 개수만으로 교체하지 않는다. 공개용 스크린샷이나 빈 자리표시자는 만들지 않았다. 이 문서의 작성과 배치는 앱 실행 경로를 바꾸지 않아 추가 성능 측정 대상이 아니며 링크, 원값 대조와 공개 콘텐츠로 검증한다.
