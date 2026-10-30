# 확인한 종료 기록과 연결 자료의 원자적 삭제

종료 이력에서 사용자가 고른 기록을 명시 확인 후 이 기기에서 삭제한다. 해당 세션의 차량 관찰과 사용자 메모는 기존 schema4의 FK CASCADE로 함께 정리한다. 다른 종료/활성 세션과 준비는 보존하며 과정 완료 판정이나 전체 보관 정책은 이번 범위가 아니다.

## 확인과 삭제의 경계

- 별도 `SessionDeletionRepository`가 `prepare`와 `delete`를 구분한다. 단순 ID가 아닌 종료 `PracticeSession` 전체, 실제 관찰 COUNT, 메모 version을 확인 대상으로 고정한다. 메모 없음도 null이라는 비교 대상이다.
- 실제 Room 트랜잭션에서 종료/비활성/전체 snapshot/관찰 수/메모 version을 재확인한다. SQLite REAL 시각의 정수 절삭, 범위 밖 version의 Int wrap, BLOB 텍스트가 같은 확인으로 변환되지 않도록 실제 저장형과 범위도 검사한다. 일반 이력 observe나 schema는 바꾸지 않았다.
- 삭제 전후 최신 P+UX, reader/query/source/화면 STARTED/nonce와 coroutine 취소를 확인한다. 마지막 검사에서 거부나 취소면 CASCADE까지 rollback한다. 이미 commit한 트랜잭션을 이후 이탈로 되돌린다고 보장하지 않는다. SDK와 SQLite의 분산 트랜잭션이 아니라 마지막으로 수신한 live 상태를 확인하는 경계다.
- Missing/Active/Changed/Blocked/Deleted와 실제 저장 오류를 구분한다. 이미 없음이나 source 소실 알림을 성공으로 바꾸지 않는다. 실제 typed Deleted를 기다리고, 삭제된 커서 뒤 다른 기록은 사용자가 명시적으로 다시 선택한다.
- 확인창에는 선택한 코스/과제/종료 시각과 연결 관찰 수/메모 유무, 영구 삭제와 복구 불가를 표시한다. STOP/닫기/조회/선택/수신 source 변경 뒤 옛 확인은 재사용하지 않는다.

[삭제 계약](../../core/domain/src/main/kotlin/io/github/hangillab/hangil/core/domain/SessionDeletionRepository.kt), [Room 구현](../../core/database/src/main/kotlin/io/github/hangillab/hangil/core/database/RoomSessionDeletionRepository.kt), [확인 수명](../../feature/home/src/main/kotlin/io/github/hangillab/hangil/feature/home/SessionHistoryViewModel.kt), [화면](../../feature/home/src/main/kotlin/io/github/hangillab/hangil/feature/home/SessionHistoryScreen.kt)

## 정확성 검증과 보존 범위

JDK17 전체 `./scripts/verify.sh`의 품질/모듈 경계/Lint, build-logic/domain/Android 단위, debug/unsigned release가 통과했다. 기존 보고서 합계41 suites/539개/실패/오류/건너뜀0이며 변경 없는 항목은 캐시/UP-TO-DATE를 포함한다. 새 실제 SQLite27개와 controller fake31개는 첫 제품 검사에서 새로 실행했다. 이 집계와 Gradle/JUnit 시간은 앱 성능이 아니다.

최종 native harness에서는 기본 글자/라이트9개와 확대1.3/다크9개가 통과했다. 내부 UUID로 새로 예약한 debug 전용 별도 Room DB만 사용하며 fixture는 실제 준비/시작/관찰/종료/메모 API로 생성했다. 차량 값은 합성 제어 입력이다. 실제 Route/ViewModel과 Room/SQLite 동작은 검증하지만 실제 DDS 삭제나 운전자 효과로 표현하지 않는다.

- 선택한 종료 세션/자식만 실제 삭제하고 다른 종료/활성/준비, 관찰/메모의 domain 필드/순서와 SQLite sequence를 보존했다. 격리 비교를 모든 SQLite 물리 자료형 비교로 확대하지 않는다.
- 메모 없음에서 생성, version 변경, 선택/수신 source 변경, silent D/P, 중복/옛 callback, STOP/재생성을 검사했다.
- 실제 트랜잭션 내부에서 부모/관찰/메모가 모두 삭제된 것을 확인한 후 D/STOP을 주입했다. 가드 호출 횟수만으로 쓰기 후를 추정하지 않았으며 rollback 후 전체 domain 자료/순서/sequence를 다시 비교했다.
- 기존 이력 상세 공개 native도 기본과 확대/다크에서 각각 통과했다. 이전 검사를 삭제 기능의 성능 표본으로 합치지 않는다.

실제 SDK는 별도 MainActivity 시나리오를 기본/확대 다크에서 각각 실행했다. 실제 브라우저의 DDS ONLINE, P 제어와 앱 P/UX 수신을 확인하고 확인창/취소/D 잠금/P 복귀/옛 확인 미복원/새 명시 확인을 검증했다. 원본 DB의 삭제 확인 action은 얻거나 실행하지 않았다. 원본에는 검사 기록도 만들지 않았다.

외부 보존 대조에서 원래 DB의 모든 값/SQLite 자료형/행 순서/sequence와 권한/runtime/font/night/IME/proxy가 정확히 같았다. 자기 소유 격리 파일 잔여0을 확인했고 소유 AVD/forwarding/콘솔 탭만 정리했다.

### 실패한 실행도 별도 보존

테마 전환 직후의 초기 격리 실행3회는 첫 시나리오가 실패하고 나머지8개가 성공했으며, 전체 실행은 실패로 남겼다. 최초 확대 실패는 실패 직전 전면 증거가 없어 원인을 확정하지 않았다. 뒤 두 실패는 시나리오 전 기본 내비가 전면인 것을 실제 PNG/익명 상태로 확인했다. 하나는 Ready/이력 태그가 있었고 다른 하나는 Loading/reader0/Compose root0였다. 전면 요청 순간의 확인만으로 이후 전면 유지까지 보장하지 않는다.

실제 Main/SDK 환경 preflight 후 같은 확대/다크 조건의9개가 통과했다. 기본 조건을 변경하지 않은 별도9개와 최종 기본9개도 통과했다. 이 성공으로 실패 기록을 지우거나 최초 원인을 소급 확정하지 않는다. 테마 전환 뒤 실제 전면 앱을 확인하는 환경 준비가 필요하며 시나리오 중 이탈은 계속 실패로 남긴다.

첫 실패에서는 Activity 재개 실패가 cleanup을 건너뛰었다. 실제 소유 ViewModelStore 취소와 reader/쓰기 종료를 먼저 확인하도록 바꿨다. 그 단일 합성 DB는 고정 fixture 시각/내용, 원본 session 집합과 겹치지 않음을 확인해 비공개 백업 후 nonce 소유 파일만 정리했다. 보강 뒤 실패 경로에서도 격리 파일 잔여0을 확인했다. 이 정리와 전면 setup은 앱 실행 성능 개선이 아니다.

## 이력 진입 비용 비교

새 삭제 동작은 기준 제품에 없어 삭제 완료 속도의 전후 비교를 지원하지 않는다. 대신 새 UI/VM 경계가 기존 이력 진입에 미치는 비용을 코드 변경 전에 계획하고 측정했다. 실제 성능 향상은 확정하지 않았다.

- 기준 제품 [3903bc6](https://github.com/HanGilLab/Android/commit/3903bc624dbd7be0f0a39f31bc7f7b490703f218), 기준 지원/고정 계측 [659df9c](https://github.com/HanGilLab/Android/commit/659df9c457672c672a898d690cb9980c7676d126), 비교 제품 [5331bb3](https://github.com/HanGilLab/Android/commit/5331bb3f01898ade90d5e0c78b112c06211a8910), 비교 instrumentation 전체 소스 [eb77072](https://github.com/HanGilLab/Android/commit/eb77072cacee816f05a073efd538fe40a41f72a0).
- 제품 변경 전 고정한 [SessionHistoryOpeningMeasureTest](../../app/src/androidTest/kotlin/io/github/hangillab/hangil/SessionHistoryOpeningMeasureTest.kt)의 내용/clock/start/endpoint는 두 단계에서 동일하다. 후속 native 정리 보강은 이 파일을 수정하지 않았고 비교 제품 APK hash도5331bb3 빌드와 같았다. 설치/빌드 product/test APK hash를 대조했다.
- 동일 API34 Pleos/debug/JDK17.0.21, font1.0/라이트, 실제 SDK P/UX, 같은 원래 source/자료/고정 카탈로그/환경이다. 데이터나 캐시를 지우지 않았다.
- 단계마다 한 Activity에서 Home→History→close→Home5회 워밍업 뒤30회 웜 진입을 순차 실행했다. 매 표본의 콜드 앱/Activity 시작 지표가 아니다. 무거운 Gradle 검증과 수동 시나리오를 겹치지 않았다.
- `SystemClock.elapsedRealtimeNanos`로 home_history 클릭 직전부터 history_ready와 물리 viewport에 완전히 보이는 history_course, Compose idle 및 endpoint 재확인까지 측정했다. 클릭 전달/Compose 동기화/semantics 비용을 포함한다. 사전 scroll, SDK/source/data 대조, JSON, close, Activity/process startup은 타이머 밖이다.
- endpoint 예산20초에 클릭/Compose blocking을 포함한다. Compose 자체 blocking timeout은 중단 가능한 하드 deadline이 아니므로 실시간 응답 상한으로 표현하지 않는다.

| 지표(ms) | 변경 전 | 변경 후 |
|---|---:|---:|
| 워밍업/본 표본 | 5/30 | 5/30 |
| 중앙값 | 263.033292 | 260.9250005 |
| Q1 | 252.634542 | 253.745708 |
| Q3 | 286.492916 | 290.104292 |
| IQR | 33.858374 | 36.358584 |
| min/max | 229.221167/468.313792 | 222.640458/329.634334 |
| 실패/누락/미실행/제외/0시간 | 0/0/0/0/0 | 0/0/0/0/0 |

중앙값의 관찰 차이(after-before)는 -2.1082915ms, 기준 대비 -0.801530287%다. 음수는 관찰 비용 감소 방향이며 성능 향상 확정 값이 아니다. 차이가 IQR보다 작고 분포가 겹친다. 전→후 순서로만 실행해 캐시/JIT/OS/발열/실행 순서의 편차를 제거하지 못했다. 실제 사용자 속도, 순수 렌더/GPU/프레임/SDK/DB 시간, release나 실차의 성능으로 확대하지 않는다.

[정제한 원값 CSV](measurements/session-deletion-history-opening-ui-times.csv)는 전후 각 warmup5/sample30, 총70행이며 시각 차이와 결과만 담는다. 원본 source/사용자 자료/기기 식별자/경로/주소/로그는 공개하지 않는다. 사분위수는 정렬한30개를15개씩 나눈 각 절반의 중앙값이다. 원문 JSON과 입력 대조 자료는 ignored 로컬 증거로 보존한다. 기존 실측 파일과 이번 완료 기준 파일을 덮어쓰지 않았다.

### 재현 절차와 공개 재현 한계

1. 승인된 전용 환경의 원본/권한/설정을 먼저 보존하고 같은 자료/카탈로그/SDK P+UX/실제 전면/테마를 확인한다. 기준은659df9c의 제품 변경 전 지원 상태를, 비교는eb77072의5331bb3 제품과 같은 APK를 사용한다. JDK17로 debug/test APK를 준비하고 두 단계의 고정 계측 파일이 동일함을 확인한다.
2. `manualHistoryOpeningMeasure=true`, `measurementSeries=session-deletion-entry-v1`, baseline/comparison의 `measurementStage`, 각 제품 full SHA와 `declaredHarnessRevision=659df9c457672c672a898d690cb9980c7676d126`을 명시해 동일 메서드를 한 번씩 실행한다. SHA는 호출자가 선언하므로 Git 소스와 실제 설치/빌드 APK를 별도 대조해야 한다.
3. warmup5/sample30의 성공과 endpoint/source/원본/환경을 검사한다. 느린 표본을 버리거나0으로 채우지 않는다. 실패/누락/제외를 보존하고 해당 실행을 성공으로 합치지 않는다. 완료 파일은 덮어쓰지 않는다.
4. 각 단계 원값의 통계를 다시 계산하고 마지막 원본/권한/설정을 대조한다. 기준0이면 상대율을 계산하지 않는다. 이번 기준은0이 아니지만 잡음 범위이므로 향상으로 완료하지 않는다.

CSV의 통계는 공개 원값으로 다시 계산할 수 있다. 실제 원본 입력은 비공개로 보존하므로 공개 자료만으로 같은 사용자 입력의 앱 측정을 완전히 재현할 수 있다고 주장하지 않는다. 같은 크기의 합성 자료 재실행은 새 계열로 구분해야 한다.
