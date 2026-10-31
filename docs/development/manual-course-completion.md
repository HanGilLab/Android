# 선택한 종료 이력의 수동 과정 마무리 선언

선택한 종료 연습에 사용자의 명시적인 과정 마무리 선언을 저장하고 다시 읽는 기능을 연결했다. PR #69의 삭제 취소/준비 실패 뒤 펼침이 비는 문제까지 수정한 최종 제품과 native 원본은 `d1bd59dc5969ca44b94db976032260cd92d71f79`이며 고정 product-v3/test-v5 APK로 검증했다. 전체 자동 검사, 제어 Compose/소유 Room 및 실제 Main 읽기의 양 테마, 고정 계측 APK의 최종 M0/M1 비교와 원본/환경 보존을 확인했다. 기준 A, 리뷰 전 중간 B1과 수정 후 최종 B2를 구분한다. 성능 향상/동등성/무회귀는 확정하지 않으며 최신 문서 head의 CI/Codex와 정상 병합은 아직 별도 조건이다. [작업 계획](../plans/active/2026-11-01-manual-course-completion.md)은 active로 유지하며 독립 제품 검증과 전체 APP-08 완료를 구분한다.

## 선택한 최소 정책

선언 단위는 사용자가 선택한 종료 session 하나다. 동일 session에는 USER_DECLARATION 하나만 저장하며, 최초 선언의 전체 source snapshot, version과 선언 시각을 유지한다. 기록 횟수나 모든 과제의 기록 유무로 자동 완료시키지 않는다. [기존 과정 요약](../../core/domain/src/main/kotlin/io/github/hangillab/hangil/core/domain/SessionCourseSummary.kt)의 기록 수는 연습 시작/마무리 버튼 기록의 수이며 운전 능력이나 실제 과제 수행의 근거로 바뀌지 않는다.

확인 화면은 저장 당시 목표, 코스/과제, 시연 출처와 연습 마무리 버튼 시각을 보여 준다. 사용자가 ‘과정 마무리로 기록’을 최종 확인해야 저장한다. 사용자 직접 선언이라는 출처와 운전 능력을 인증하는 정보가 아니라는 의미를 표시하며 기존 연습 기록을 그대로 보존한다. 원시 session ID와 version은 화면에 노출하지 않는다. 출처는 전체 가용 폭의 큰 본문으로 표시하고 확인 내용은 스크롤할 수 있게 구성했다. 기본 라이트와 확대 다크의 실제 C 확인창 및 B 저장 완료 원본 이미지를 직접 읽어 큰 글자/출처/버튼/시각 표시를 확인했다.

선언은 기존 준비, 연습 시작/마무리, SDK 관찰, 메모와 배경 실행을 변경하지 않는다. 새 준비나 연습을 자동으로 만들거나 막지 않으며 자동 재시작도 하지 않는다. 저장 전 확인 취소는 쓰기를 수행하지 않는다. 저장 이후 선언만 독립적으로 철회/수정하는 동작, 전체 과정 진행/재개/회차 정책, 보관 기한과 자동 삭제는 구현하지 않았다. 이 연결로 APP-08 전체를 완료 처리하지 않는다.

## domain과 저장소의 원형

[CourseCompletionDeclaration](../../core/domain/src/main/kotlin/io/github/hangillab/hangil/core/domain/CourseCompletionDeclaration.kt)은 full PracticeSession source, 불투명한 version, declaredAtEpochMillis와 [USER_DECLARATION 출처](../../core/domain/src/main/kotlin/io/github/hangillab/hangil/core/domain/CourseCompletionOrigin.kt)를 갖는다. source에는 당시 목표/코스/과제와 버전, 시연 출처, 연습 시작/마무리 버튼 시각이 포함된다. 현재 카탈로그로 과거 원문을 재해석하지 않는다.

선언 시각은 최종 확인 때 한 번 캡처한 앱의 벽시계 기록이다. 양수만 요구하며 source의 시작/종료 이후인지 비교하지 않는다. 벽시계가 역행했을 때 기존 시각을 현재 시각으로 보정하지 않고, 이 값으로 학습 시간이나 실제 과정 종료 시각을 계산하지 않는다.

[CourseCompletionRepository](../../core/domain/src/main/kotlin/io/github/hangillab/hangil/core/domain/CourseCompletionRepository.kt)는 다음 두 경로를 제공한다.

- observe(sessionId): Flow의 실제 null만 선언 부재다. 손상된 저장값과 조회 예외는 null로 대체하지 않는다.
- declare(expectedSource, declaredAtEpochMillis, canCommit): source 존재/종료와 전체 snapshot 일치, 다른 활성 연습 없음, 유효 시각과 허용 조건을 확인한다. [결과](../../core/domain/src/main/kotlin/io/github/hangillab/hangil/core/domain/CourseCompletionDeclarationResult.kt)는 Saved, AlreadyDeclared, MissingSession, SourceChanged, ActiveSession, Blocked와 InvalidTime을 구분한다.

[Room 구현](../../core/database/src/main/kotlin/io/github/hangillab/hangil/core/database/RoomCourseCompletionRepository.kt)은 같은 트랜잭션에서 현재 source와 모든 session의 활성 여부를 검사한다. UI가 받지 못한 새 활성 연습도 저장소가 다시 거부한다. 이미 선언이 있어도 현재 source/활성 연습/허용 검사를 먼저 통과해야 하며, 기존 source/version/시각을 그대로 반환한다. 중복 요청으로 새 행이나 새 선언 시각을 만들지 않는다.

쓰기 전후에는 현재 coroutine의 취소와 순수 canCommit 조건을 확인한다. COMMIT 전에 거부나 취소가 관찰되면 롤백한다. COMMIT 뒤 결과 전달 취소는 저장이 없었다는 근거가 아니다. 이 경우 중복 쓰기로 성공/실패를 추정하지 않고 실제 저장 원형을 다시 읽는다. 저장 오류 문구도 ‘저장 결과를 확인하지 못했어요’로 구분하며 자동 저장 재시도는 하지 않는다.

## Room v6와 삭제 확인

[Entity](../../core/database/src/main/kotlin/io/github/hangillab/hangil/core/database/CourseCompletionDeclarationEntity.kt)는 sessionId를 기본 키로 사용하고 source 전체를 별도로 보존한다. [DAO](../../core/database/src/main/kotlin/io/github/hangillab/hangil/core/database/CourseCompletionDeclarationDao.kt)와 [Row](../../core/database/src/main/kotlin/io/github/hangillab/hangil/core/database/CourseCompletionDeclarationRow.kt)는 SQLite typeof와 정수 범위를 검사해 Room의 문자열/숫자 변환이 손상된 원형을 정상값으로 보이게 하지 않는다. 알 수 없는 origin/시연 출처, 빈 version과 잘못된 source/시각도 오류다.

[5→6 migration](../../core/database/src/main/kotlin/io/github/hangillab/hangil/core/database/PreparationMigration5To6.kt)은 새 선언 테이블만 추가한다. 기존 v5 자료와 선언 테이블의 초기 부재를 구분하며 새 선언을 자동 생성하지 않는다. v1~v5에서 v6까지의 명시적인 경로를 등록하고 destructive fallback을 사용하지 않는다. 원래 table/row/field, SQLite 물리형, 생성 순서와 sequence 보존은 migration 타깃 검사로 확인했다. native 종료 시점의 실제 생산 DB에서도 v6 빈 선언 테이블 추가 외에 원래 v5 자료/물리형/순서/sequence와 권한/설정의 exact 보존을 확인했다. 비교 계측 뒤에도 같은 원본/권한/설정과 생산 선언0의 exact 보존을 다시 확인했다.

source 삭제는 FK CASCADE로 연결 선언도 지운다. [SessionDeletionTarget](../../core/domain/src/main/kotlin/io/github/hangillab/hangil/core/domain/SessionDeletionTarget.kt)에 nullable 선언 전체를 포함하고 [삭제 저장소](../../core/database/src/main/kotlin/io/github/hangillab/hangil/core/database/RoomSessionDeletionRepository.kt)가 같은 트랜잭션에서 다시 비교한다. 확인 당시 선언이 없었는데 새로 생겼거나 version/원문/시각이 달라진 경우도 Changed다. source가 사라진 뒤 늦은 선언 요청으로 행을 되살리지 않는다. 확인 화면에는 선언 있음/없음과 함께 연결 자료를 삭제한다는 의미를 표시한다.

## 이력 reader와 확인 동작의 소유권

[SessionHistoryViewModel](../../feature/home/src/main/kotlin/io/github/hangillab/hangil/feature/home/SessionHistoryViewModel.kt)은 부모 이력이 받은 전체 session 목록에서 활성 연습 여부를 같은 lock 안에 보관한다. 미수신/오류를 활성 없음으로 채우지 않으며 상세에서 sessions를 다시 구독하지 않는다. 저장된 선언은 P+UX 허용 아래 읽을 수 있지만 활성 연습이 있거나 그 존재가 미확인이면 새 저장을 허용하지 않는다.

[독립 reader](../../feature/home/src/main/kotlin/io/github/hangillab/hangil/feature/home/SessionHistoryCourseCompletionReader.kt)는 새 영역이 펼쳐진 동안만 선언 Flow를 수집한다. 기존 차량 관찰, 메모, 과제 요약과 배경 실행의 본문/tag/조작을 유지하며 그 영역만 열 때 선언 저장소를 읽지 않는다. [route](../../feature/home/src/main/kotlin/io/github/hangillab/hangil/feature/home/HistoryCourseCompletionRoute.kt)는 [화면 상태](../../feature/home/src/main/kotlin/io/github/hangillab/hangil/feature/home/SessionHistoryCourseCompletionUiState.kt)의 표시 당시 값을 동작에 고정한다.

읽기와 저장은 full source, 부모 generation/reader/query, STARTED host와 현재 P+UX에 묶인다. 확인과 최종 저장에는 표시 token 및 nonce가 필요하다. 같은 ID의 metadata 교체, source 삭제/선택 변경, 부모 실패/취소, host STOP/폐기와 직접 관찰한 주행/UX 거부는 옛 소유권을 폐기한다. P나 host가 돌아와도 옛 확인을 다시 승인하지 않는다.

[확인 화면](../../feature/home/src/main/kotlin/io/github/hangillab/hangil/feature/home/CourseCompletionDeclarationConfirmation.kt)의 Saving 전환은 같은 저장 동의의 표시 변화다. 그 변화만으로 canCommit을 스스로 거부하지 않으며, 실제 source/host/허용 조건 변경은 계속 거부한다. 저장 ACK보다 실제 저장 Flow가 먼저 오면 저장 원형을 권위 있는 읽기로 사용한다. ACK가 먼저 오면 옛 query와 늦은 null을 폐기하고 새 query로 확인한다. 재진입도 [저장된 선언 표시](../../feature/home/src/main/kotlin/io/github/hangillab/hangil/feature/home/CourseCompletionDeclarationContent.kt)로 복원하며 새 선언을 자동 요청하지 않는다.

Loading, Locked, Unavailable, 실제 부재, 조회/저장 실패와 Ready를 구분한다. 조회 예외, 조회의 독립 취소, 유한 stream 완료와 손상된 선언은 명시적인 조회 재시도 전까지 실패다. 반복 null 통지가 진행 중 확인이나 저장을 초기화하거나, 미확인 저장 결과를 새 부재로 바꾸지 않게 했다.

## 현재 검증 상태와 보완 이력

| 범위 | 현재 실제 결과 |
|---|---|
| domain/실제 Room 타깃 | 통과. 전체 source, idempotence, 다른 활성 연습, 시각 역행, 물리형, 삭제 CAS와 migration 경계 포함 |
| 앱 debug 빌드 | 통과. 실제 Hilt 연결과 생산 UI/VM 컴파일 확인 |
| feature 첫 실행 | 테스트 Pair/Flow 타입 추론 오류로 컴파일 실패. 테스트 본문은 미실행 |
| feature 재실행 | 제네릭 타입만 명시한 뒤 통과. 새 ViewModel/reader 41개 포함, 검증 조건 완화 없음 |
| 리뷰 지적의 focused JVM | 원래 5개에서 실제 4실패/1통과를 재현하고, 같은 source 바이트로 수정 후 5통과. 이후 전체 feature386개 실패0 |
| 전체 verify / native APK 컴파일 | 최종 d1bd59의 JDK17 전체4단계와 APK 컴파일 통과. 제품 모듈 JVM57 suites/876개와 별도 build-logic1 suite/4개 각각 실패/오류/건너뜀0 |
| 제어 Compose A | 최종 d1bd59 첫 실행6PASS. 실제 Compose와 합성 lifecycle/source/저장소/gate |
| 소유 Room B | 최종 d1bd59 첫 실행 기본 라이트3PASS/확대 다크3PASS. 별도 UUID DB/production 저장소와 실제 Route |
| 실제 Main C | 최종 d1bd59 첫 실행 기본 라이트1PASS/확대 다크1PASS. 실제 SDK/생산 Room의 읽기/확인 취소/P-D-P와 선언0 검증 |
| M0/M1 공통 비교 | A/B1/B2 여섯 보고서 각각5warm/30본 완료, 모두 valid와 실패/누락/미실행/제외0. 동일 입력/환경/고정 계측 APK 확인 |
| 새 저장 M2 | 미측정. 동일한 최초 저장 반복 fixture와 metric 계약 미확정 |
| 최신 PR CI/Codex / 병합 | d1bd59 Codex completed/no findings, 원래 P2 답글과 native 해결 확인. CI는 진행 중이었으며 이 문서 갱신 head는 새 확인 필요. 병합은 미완료 |

[feature 타깃 소스](../../feature/home/src/test/kotlin/io/github/hangillab/hangil/feature/home/SessionHistoryCourseCompletionViewModelTest.kt)는 cold 조회, 부모/전체 source/활성 unknown, 옛 token/nonce, silent gate와 host 만료, 오류/유한 stream, ACK/Flow 순서와 취소 뒤 저장 원형 재조회를 제어 입력으로 검사한다. 이 타깃 통과를 실제 SDK나 실제 화면 통과로 바꾸지 않는다.

독립 읽기 검토에서 두 번째 live 검사로 Locked/Unavailable를 발행한 뒤 후속 Absent/오류가 덮을 수 있는 경계를 발견해 이미 폐기된 owner의 후속 발행을 막았다. 이를 실행 RED나 실제 장치 재현으로 주장하지 않는다. 별도 debug 소유 저장소의 reopen도 새 handle의 열기와 첫 정리가 모두 실패할 때 참조를 잃지 않도록 새 handle 생성 직후 필드에 보관했다. 실제 사용자 DB와 분리된 UUID 자원의 정리 보완이며, 해당 오류 재현이나 close 재시도 성공까지 확인했다는 뜻은 아니다.

실제 UI 쓰기 검증은 별도 UUID 소유 Room과 production 저장소/route로 수행했다. 실제 Main/SDK/생산 Room은 원래 자료 읽기, 확인 취소와 gate만 검증하고 생산 선언0개를 유지했다. 저장 성공은 소유 fixture의 근거이며 생산 자료에 선언을 쓴 성공을 주장하지 않는다.

## 최종 native 결과와 이전 실패의 구분

최종 제품/native 원본 `d1bd59dc5969ca44b94db976032260cd92d71f79`의 product-v3/test-v5에서 [A 제어 Compose](../../app/src/androidTest/kotlin/io/github/hangillab/hangil/ManualCourseCompletionBoundaryTest.kt)6개, [B 소유 실제 Room](../../app/src/androidTest/kotlin/io/github/hangillab/hangil/ManualCourseCompletionRoomFlowTest.kt)기본1.0/라이트3개와 확대1.3/다크3개, [C 실제 Main 읽기](../../app/src/androidTest/kotlin/io/github/hangillab/hangil/ManualCourseCompletionFlowTest.kt)라이트/확대 다크 각각1개가 모두 첫 실행에 통과했다. A는 실제 Compose/Activity와 제어 lifecycle/source/저장소/gate의 근거이며 실제 OS PAUSE나 SDK 장애 주입 성공이 아니다.

B는 별도 UUID Room의 최초 저장, 새 ViewModel과 실제 Room handle 재개방/재열람, 원형/중복과 결과 취소 뒤 복원에 더해, 실제 삭제 준비/취소 뒤 부재/저장된 선언을 다시 읽는 경계를 검사한다. 차량/UX 입력은 합성이다. 생산 앱에서 선언 저장 후 process death/재실행한 검증은 원래 DB 쓰기0 정책상 미실행이다.

C는 실제 Main/SDK/생산 Room에서 삭제 준비와 확인 취소 뒤 같은 펼침의 Absent가 새 읽기로 복원되고, 새 과정 확인창을 열어 다시 취소하는 경계를 추가 확인했다. source 이동/재생성, 실제 SDK P+UX와 DDS P-D-P, 원래 자료 및 생산 선언0도 유지했다. 삭제와 선언의 최종 확인은 어느 쪽도 누르지 않았다. 다크 실행 전 촬영된 내비 화면은 Main 성공 증거가 아니며, 실제 C 본문의 strict Main/focused window 검사와 별도 capture가 통과한 근거를 사용한다.

리뷰 전 B1 제품 a639/product-v2의 A4, B 라이트3/다크 재시도3, C b179의 양 테마 성공도 각 원래 source의 과거 기록으로 보존한다. 그때 C 파일/공용 실제 읽기 Harness/제품이 9f1950에서 바이트 동일했던 확인을 최종 d1bd59의 새 실행과 혼동하지 않는다.

첫 C 라이트는 요청 문구의 한 줄180×34px에서 가로 overflow true/세로 false/ellipsis 없음으로 실패했다. 확인 취소/P-D-P까지 통과한 실행으로 세지 않았다. 요청/확인/취소/재조회 Text를 실제 가용 폭에 가운데 정렬하고 글자/간격을 줄이지 않은 제품에서 같은 원문/가시성/넘침 검사로 양 테마가 통과했다. 이 배치 정합 보완을 성능 향상으로 해석하지 않는다.

초기 A의 dialog footer 스크롤 실패와 이후 실제 host 개입/AX 미확인, 수정 제품 A의3PASS/1mount foreign 실패 두 회, B 다크의2PASS/1초기 host 실패를 각각 보존했다. 리뷰 전9f 첫 B 다크도2PASS/1setup 실패로, 실제 Catalog 준비 조건을20초 안에 확인하지 못했다. 동일 APK와 동일 검사로 재시도한3PASS와 원래 실패를 분리한다. 정확한 플랫폼 전환 원인은 미확정이며 제품 저장 실패/성공으로 바꾸지 않는다.

최신 준비 gate는 실제 Catalog RESUMED/유효 display/window focus를 확인한 뒤에만 제어 owner/observer를 만든다. 본문과 확인창의 실제 focused package/같은 display/host 개입/옛 token 검사를 유지하며 알려진 다른 앱은 실패다. 미확인을 기대 package나 정상값으로 채우지 않는다. A의 합성 lifecycle을 실제 OS PAUSE 성공으로 부르지 않는다.

C 확인창 라이트/다크와 B 저장 완료 라이트/다크의 비공개 원본 이미지4개를 직접 검토했다. 원문/출처/큰 글자/버튼/선언 시각을 읽었으며 TextLayout overflow/ellipsis 검사는 보조 근거다. 합성 저장 화면을 실제 SDK가 생산 자료에 쓴 증거로 표시하거나 공개 이미지로 추가하지 않았다.

native 종료 시점에 모든 원래 v5 table/row/field/물리형/순서/sequence와 grants/runtime/원래 font/night/IME/proxy가 exact 일치했다. 의도한 차이는 schema6의 빈 선언 테이블이며 생산 선언은0개다. 원래 준비/세션/관찰/메모/run에 쓰지 않고 소유 UUID DB에만 저장했다. 비교 계측 뒤의 최종 원본/환경/소유 자원 대조도 완료했으며 아래 계측 결과와 구분해 기록한다.

## 제품 변경 전 M0/M1 기준

생산 변경 전에 제품 `4b1ba6d9c437767c258e9d33843af6c600f24b01`, 고정 하네스 `6f45d5a82bf0b48b0bb0a81f4dd04304633faa24`로 실제 기준을 수집했다. 실제 설치 제품 APK가 고정 빌드와 일치함을 확인했고, 원본 보고서의 source/catalog/environment, 두 하네스 파일의 바이트 hash와 실제 계측 APK를 비공개로 보존했다. 선언 SHA와 실제 APK/hash의 실행 근거를 구분하며, 현재 고정 하네스 파일을 수정하거나 기존 보고서의 원본 선언을 바꾸지 않는다. 비교에서도 고정된 동일 계측 APK와 두 소스의 바이트hash, 원형 baseline 및 입력/환경의 일치를 확인했다.

JDK17/debug/Pleos API34/font 1.0/라이트, 같은 원본과 가용 앱 영역, 실제 Main 전면 및 SDK P+UX를 사용했다. 각 metric은 fresh process/Activity에서 5회 워밍업 뒤 같은 프로세스의 30회 본 표본을 수집했다. 캐시/자료를 지우지 않았고 느린 성공 표본을 제외하지 않았다. 기준 종료 뒤 원래 schema5 자료의 전 필드/물리형/순서/sequence, 권한/runtime/설정을 외부 exact 대조로 보존하고 이번 소유 AVD/forward/DDS를 정리했다.

| 기준 지표 | warm/본 | 중앙값 ms | Q1 / Q3 ms | IQR ms | 최소 / 최대 ms | 실패/누락/제외 |
|---|---:|---:|---|---:|---|---|
| M0 기존 접힌 이력 진입 | 5/30 | 279.446584 | 260.963084 / 307.00525 | 46.042166 | 241.263584 / 662.491709 | 0/0/0 |
| M1 기존 과제 요약 펼침 | 5/30 | 168.898625 | 167.479166 / 170.427916 | 2.94875 | 163.258917 / 186.706 | 0/0/0 |

두 기준 보고서 모두 completed이며 5warm/30본이 전부 성공했다. 통계는 30개 본 표본만 사용한다. 정수 elapsedRealtimeNanos에서 ms를 유도하고 Q1/Q3는 정렬한 하위 15개와 상위 15개의 중앙값, IQR은 Q3−Q1이다. 이 표는 제품 변경 전 실제 기준이다. 이후 완료한 같은 metric의 비교는 아래 최종 결과에서 구분하며 성능 향상/저하/동등성/무회귀를 확정하지 않는다.

M0는 [고정 진입 하네스](../../app/src/androidTest/kotlin/io/github/hangillab/hangil/SessionHistoryOpeningMeasureTest.kt)의 manual-course-completion-entry-v1, M1은 [고정 과제 요약 하네스](../../app/src/androidTest/kotlin/io/github/hangillab/hangil/SessionHistoryDisclosureMeasureTest.kt)의 manual-course-completion-tasks-v1 계열이다. M0는 home_history 클릭부터 같은 source의 history_ready/history_course 가시성과 Compose idle/재확인까지, M1은 history_summary_tasks 클릭부터 history_summary_scope 가시성과 Compose idle/재확인까지다. 기존 준비/검사/닫기와 20초 한계를 고정하며 새 선언 영역은 접혀 있고 선언 reader도 없다.

이 값은 기존 공통 흐름의 계측 wall time이다. 클릭 전달, Compose 동기화와 semantics/geometry 검사 비용을 포함한다. 준비/사전 스크롤, SDK/source/자료 검사, JSON/export, 닫기와 프로세스 시작은 기존 계약대로 제외한다. 순수 DB/프레임/GPU/SDK 처리시간이나 신규 선언 조회/쓰기 시간으로 해석하지 않는다. 확대 다크 correctness를 라이트 timing에 합치지 않는다.

비교는 v6의 새 선언 테이블과 UI가 의도한 차이며 원래 v5 자료는 그대로여야 한다. 기준/비교 동안 생산 선언은 0개를 유지한다. v6 적용 뒤 v5 앱으로 돌아가는 downgrade 위험 때문에 이번 실행의 A2는 수행하지 않는다. A2를 맞추려고 원래 DB를 삭제하거나 백업으로 덮어써 버전 왕복하지 않는다. 실제 순서는 A→B1→B2이며 캐시, ART/JIT/프로필, 발열, OS와 시간/순서 효과를 원인별로 분리하지 못한다는 한계를 남긴다.

## 기존 공통 흐름의 A/B1/B2 결과

[원래 익명 원값140행](measurements/manual-course-completion-ui-times.csv)은 M0/M1의 기준 A와 리뷰 전 중간 B1이다. [리뷰 수정 후 원값70행](measurements/manual-course-completion-review-fixed-ui-times.csv)은 최종 B2만 추가한다. 두 CSV의 고유 표본은 총210행이며 B2 파일에 A를 복제하거나 새 baseline을 만들지 않았다. 여섯 실제 보고서 모두 completed/valid이고 각각5warm/30본의 실패/누락/미실행/제외0이다. 아래 통계는30본만 사용하며 Q1/Q3는 정렬한 하위15/상위15 각각의 중앙값, IQR은 Q3−Q1이다.

| 지표/단계 | warm/본 | 중앙값 ms | Q1 / Q3 ms | IQR ms | 최소 / 최대 ms | 실패/누락/미실행/제외 |
|---|---:|---:|---|---:|---|---|
| M0 A 기준 | 5/30 | 279.446584 | 260.963084 / 307.00525 | 46.042166 | 241.263584 / 662.491709 | 0/0/0/0 |
| M0 B1 리뷰 전 중간 | 5/30 | 274.0576875 | 253.070208 / 290.953208 | 37.883 | 236.348875 / 356.42775 | 0/0/0/0 |
| M0 B2 리뷰 수정 최종 | 5/30 | 274.6634375 | 259.474291 / 293.450625 | 33.976334 | 225.497459 / 376.848917 | 0/0/0/0 |
| M1 A 기준 | 5/30 | 168.898625 | 167.479166 / 170.427916 | 2.94875 | 163.258917 / 186.706 | 0/0/0/0 |
| M1 B1 리뷰 전 중간 | 5/30 | 169.5672085 | 168.577459 / 171.632833 | 3.055374 | 154.113333 / 193.424583 | 0/0/0/0 |
| M1 B2 리뷰 수정 최종 | 5/30 | 169.135604 | 167.047208 / 171.377458 | 4.33025 | 163.587125 / 179.505333 | 0/0/0/0 |

최종 제품의 주 비교는 A→B2다. B1→B2도 같은 기록 입력을 확인한 뒤 산술 차이만 표시한다.

| 지표/비교 | 중앙값 차이 ms | 상대 변화 |
|---|---:|---:|
| M0 A→B2 | -4.7831465 | -1.7116496582% |
| M1 A→B2 | +0.236979 | +0.1403084247% |
| M0 B1→B2 | +0.60575 | +0.2210301070% |
| M1 B1→B2 | -0.4316045 | -0.2545329983% |

상대 변화는(후−전)/전이며 백분율은 소수점10자리 반올림이다. 양수는 더 긴 시간이다. 보존한 A→B1의 관측 차이는 M0 −5.3888965ms/−1.9284173823%, M1 +0.6685835ms/+0.3958489893%다. B1을 최종 결과로 바꾸거나 유리한 실행만 선택하지 않았다. 각 전후 Q1/Q3가 겹치고 A→B1→B2의 순차 실행에 A2가 없어 캐시/ART/JIT/프로필/발열/전체 OS 부하와 scheduling/시간 및 순서의 영향을 분리하지 못한다. 성능 향상, 인과적 저하, 동등성 또는 무회귀를 확정하지 않는다.

A는4b 고정 제품, B1은a639 product-v2, 최종 B2는d1bd59 product-v3의 실제 APK가 각 단계의 고정 원본과 일치했다. 세 단계 모두 기준 전에 고정한6f45 계측 APK와 동일한 두 하네스 source 바이트hash를 사용했다. originalData/full fixedSource/catalog/environment, 각 metric의 measurementContract와 baseline 원바이트hash도 일치했고 여섯 실행은 서로 구분된다. source SHA는 외부 실행자의 선언이며 실제 APK/hash와 원형을 대조한 근거를 보존하되 source-to-binary 재현 빌드를 증명했다고 확대하지 않는다.

B1의 보고서/140행 CSV와 baseline 파일은 바이트 동일하게 보존했다. B2/캐시 이동 전에 만든 외부 보존 checkpoint는 리뷰 수정 착수 전의 checkpoint라는 뜻은 아니다. 그 대조 뒤 해당 실행이 소유한 comparison 두 보고서만 이름 이동해 보관했고, 최종 B2를 별도 archive/70행으로 회수했다. 원래 DB와 baseline은 이동/삭제/덮어쓰지 않았다. 원래140행의 정수ns↔ms/표본/네 분포는 독립 Decimal 검산도 통과했다. 첫 보조 분석의 잘못된 benchmark 열 선택은 실제 스키마로 정정한 도구 준비 실패이며 측정 실패 표본으로 세지 않는다.

최종 native와 B2 계측 뒤 원래 v5 모든 행/필드/물리형/순서/sequence와 grants/runtime/font/night/IME/proxy, schema6 빈 선언0 및 두 원형 baseline 바이트의 exact 보존을 각각 확인했다. UUID 소유DB/marker/sidecar 잔존0이며 이번 소유 AVD/forward를 종료하고 연결된 DDS 탭도 닫았다. 원래 자료에 쓰지 않았으며 중간 native 점검과 최종 계측 후 보존을 구분한다.

## 공개 재현 명령과 필수 프로토콜

JDK17을 선택한 뒤 별도로 전체 검증을 마치고 Gradle 작업이 모두 끝난 상태에서 실행한다. 원래 DB/권한/runtime/설정, 완전 source/catalog/environment, baseline 원문과 APK/hash는 외부 실행자가 비공개로 고정/대조한다. 대상은 활성 연습/FGS가 없는 전용 debug 앱과 단일 소유 기기이며 실제 Main 전면, SDK P+UX, font1.0/라이트와 같은 앱 가용 영역을 확인한다. 기기 식별자와 실제 artifact 경로는 공개하지 않는다.

```sh
java -version
./scripts/verify.sh
```

아래 최종 B2 예시는 보존된 d1bd59 product-v3 APK와 기준 전에 고정한6f45 test APK를 사용한다. 환경 변수에는 실행자가 검증한 비공개 artifact 경로를 지정한다. 기준/비교에 동일한 계측 APK를 사용하며 현재 native 검사 APK의 빌드 성공으로 대체하지 않는다. 기준은 v5/4b에서 미리 확보한 원형이며 A2를 위해 v5로 되돌리거나 자료를 삭제/덮어쓰지 않는다.

```sh
adb install -r "$REVIEW_FIXED_PRODUCT_APK"
adb install -r -t "$FROZEN_MEASUREMENT_TEST_APK"
adb shell am force-stop io.github.hangillab.hangil.debug
adb shell am instrument -w -r \
  -e class io.github.hangillab.hangil.SessionHistoryOpeningMeasureTest \
  -e manualHistoryOpeningMeasure true \
  -e measurementSeries manual-course-completion-entry-v1 \
  -e measurementStage comparison \
  -e declaredProductCommit d1bd59dc5969ca44b94db976032260cd92d71f79 \
  -e declaredHarnessRevision 6f45d5a82bf0b48b0bb0a81f4dd04304633faa24 \
  io.github.hangillab.hangil.debug.test/androidx.test.runner.AndroidJUnitRunner

adb shell am force-stop io.github.hangillab.hangil.debug
adb shell am instrument -w -r \
  -e class io.github.hangillab.hangil.SessionHistoryDisclosureMeasureTest \
  -e manualHistoryDisclosureMeasure true \
  -e measurementSeries manual-course-completion-tasks-v1 \
  -e measurementStage comparison \
  -e declaredProductCommit d1bd59dc5969ca44b94db976032260cd92d71f79 \
  -e declaredHarnessRevision 6f45d5a82bf0b48b0bb0a81f4dd04304633faa24 \
  io.github.hangillab.hangil.debug.test/androidx.test.runner.AndroidJUnitRunner
```

고정 하네스는 B2도 measurementStage=comparison으로 작성한다. 먼저 외부의 `baseline-measurements-cache.tar`와 B1 `comparison-measurements-cache.tar`, 원래140행 CSV/두 baseline/제품 및 고정 test APK의 바이트를 대조한다. 앱 cache의 `history-opening-manual-course-completion-entry-v1-comparison.json`과 `history-disclosure-manual-course-completion-tasks-v1-comparison.json` 두 파일만 이 실행 소유임을 확인하고, 동일 바이트의 별도 소유 보관 위치로 이름 이동한 뒤 B2를 수집했다. baseline/사용자 DB는 옮기지 않는다. B2 회수본은 `review-fixed-measurements-cache.tar`로 구분하며, 새 CSV의 after-review-fix 표시는 분석 레이블이지 고정 하네스의 새 namespace가 아니다. 소유/보존을 확인하지 못했거나 완료 파일/sidecar가 남으면 덮어쓰지 않고 실행을 중단한다.

각 클래스는5warm/30본을 내부 고정하며 외부 실행자는 각 metric을 fresh process/Activity에서 시작한다. M0는 같은 source의 history_ready/history_course, M1은 history_summary_scope의 실제 가시성과 Compose idle/재확인까지인 기존20초 endpoint를 유지한다. 새 선언 영역은 접혀 있고 선언 조회/쓰기가 없다. 완료/실패 파일과 AtomicFile sidecar를 덮어쓰지 않으며 기존 결과가 있으면 해당 실행을 중단한다. 반복은 위 원형/소유 보관을 확인한 뒤 별도 실행으로 준비한다.

매 표본과 종료 때 같은 source/입력/환경 및 baseline hash를 대조하고 raw ns/ms, endpoint/성공과 실패/누락/미실행/제외를 함께 회수한다. 원래 v5 자료와 생산 선언0, 권한/설정을 다시 exact 대조하고 이번 소유 자원만 정리한다. 공개 명령은 실행 계약을 설명하며 비공개 fixture와 설치 provenance의 준비를 생략하지 않는다.

## 신규 저장 M2와 남은 완료 조건

M2는 아직 동일 반복 fixture와 metric을 확정하지 않았고 하네스/after 표본도 없다. session당 최초 저장 한 번과 이후 AlreadyDeclared 반환은 다른 행동이다. 같은 source에 35번 요청해 최초 저장과 중복 반환을 섞어 신규 저장 시간으로 집계하지 않는다. 원래 자료에 선언을 만들거나 기존 행을 지워 반복 조건을 맞추지 않는다. 별도의 소유 최초 저장 fixture와 초기화/열기/캐시/정리 조건을 확정한 뒤에만 측정을 설계한다.

신규 저장에는 이전 대응 행동이 없으므로 변경 전을 0, 이전 실패 대기 시간 또는 M0/M1로 두지 않는다. 신규 저장 M2의 after 수치나 개선율도 없다. 정확성 검사의 실행 시간과 통과 수를 신규 저장 성능으로 사용하지 않는다. 이 작업의 주 비교 지표는 기존 M0/M1이며 위 실제 전후 표본과 분포를 제한된 의미로 해석한다.

전체 verify/계측 APK 컴파일과 위 범위의 A/B/C, 실제 네 이미지 검토, M0/M1 실제 비교와 최종 원본/설정/선언0/원형 baseline 보존 및 소유 자원 정리를 확인했다. 최신 PR 동일 head의 필수 CI/Codex, 구체 지적 처리와 정상 병합/동기화/브랜치 정리는 별도 미완료 조건이다. 생산 앱의 선언 저장 후 프로세스 종료/재실행은 원래 DB 쓰기0 정책상 미실행이다. 원문 source/식별자/시각, 설치/환경 자료와 개발 중 이미지는 비공개로 보존하며 active 계획을 미리 completed로 옮기지 않는다.

## PR 리뷰 뒤 삭제 취소와 읽기 복원

삭제 요청은 옛 선언 reader와 token/동의를 영구 폐기한다. 리뷰 전에는 같은 source/generation의 펼침 Flow가 유지된 채 reader만 revoked라서 삭제 확인 취소나 준비 실패 뒤 본문이 사라졌다. [최소 수정](../../feature/home/src/main/kotlin/io/github/hangillab/hangil/feature/home/SessionHistoryViewModel.kt)은 선언 읽기만의 epoch를 갱신한다. 현재 full source/generation/STARTED host/live P+UX가 맞는 안전한 PREPARING/CONFIRMING 취소와 준비 실패 뒤에만 새 reader/query를 연결한다. Idle/Failed를 먼저 게시하고 새 읽기를 시작하며 옛 저장 의도는 재생하지 않는다. 이미 준비 실패에서 복원한 action의 안내를 나중에 닫아도 새 동의를 다시 폐기하지 않는다.

삭제 작업이 terminal Failed를 발행한 뒤 안내를 명시적으로 닫으면 현재 context에서 새 읽기를 한 번 허용한다. 이는 예외 때 COMMIT 결과를 확정하는 동작이 아니라 현재 저장 원형을 다시 읽는 동작이다. Missing/관찰된 sourceAbsent/Deleted는 재조회하지 않는다. DELETING 중 명시 취소가 job과 commit predicate를 폐기하는 기존 계약은 유지하며, COMMIT/응답이 불확실한 취소에 자동 읽기를 만들지 않는다. 이 경우 기존 부모 이력 결과와 사용자의 명시적인 펼침 재열람으로 돌아간다. 부모 query/generation, 기존 네 펼침 및 저장소 계약을 바꾸지 않았다.

[원래 focused JVM 재현](../../feature/home/src/test/kotlin/io/github/hangillab/hangil/feature/home/SessionHistoryCourseCompletionDeletionBoundaryTest.kt)은 실제5개 중4실패/1통과에서 같은 source 바이트의5통과로 바뀌었고 [추가 guard](../../feature/home/src/test/kotlin/io/github/hangillab/hangil/feature/home/SessionHistoryCourseCompletionDeletionRecoveryGuardTest.kt)와 기존 취소 검사를 포함한 feature386개가 통과했다. 포맷 전 이 source 동일 GREEN을 확보했고 뒤의 줄바꿈 수정은 기대를 바꾸지 않았다. 최종 d1bd59의 전체 verify와 A6/B 양 테마3/C 양 테마1 및 최종 B2를 위 결과로 구분해 확인했다. [원래 지적 답글](https://github.com/HanGilLab/Android/pull/69#discussion_r4433638300)과 native 해결을 확인했으며 d1bd59 Codex는 completed/no findings였다. CI는 진행 중이었고 이 문서 갱신 head의 필수 CI/Codex와 정상 병합은 다시 확인해야 한다.

fresh process/Activity 조건은 각 metric 전에 앱 프로세스를 외부에서 새로 시작했다는 뜻이며 기기 cold boot를 동일하게 통제했다는 뜻이 아니다. 검증/빌드/AVD 재시작을 사이에 거쳤고 OS/서비스 부하, 장치 uptime과 캐시/컴파일 프로필 동등성은 입증하지 못했다. 같은 기록 입력/계측/가용 영역과 명시한 조건의 관측값으로만 해석한다.
