# 선택한 종료 이력의 수동 과정 마무리 선언

- 상태: PR #69의 삭제 취소/준비 실패 뒤 선언 읽기 복원을 수정한 최종 제품/native `d1bd59dc5969ca44b94db976032260cd92d71f79`의 전체 verify와 A6/B 양 테마3/C 양 테마1을 확인했다. 기준 A/중간 B1/최종 B2의 여섯 M0/M1 보고서와 원본/환경 보존도 완료했다. 원래 P2 답글/native 해결과 d1 Codex completed/no findings는 확인했으나 CI는 진행 중이며 문서 갱신 head의 CI/Codex 및 병합/정리는 미완료다.
- 요구사항: APP-08의 과정 마무리 중 사용자의 명시적 선언 저장과 재열람. 전체 과정 진행, 보관 정책과 APP-08 전체 완료는 별도다.
- 기준 제품: `4b1ba6d9c437767c258e9d33843af6c600f24b01`
- 관련 계약: [당시 코스 기록 요약](../../development/course-history-summary.md), [종료 기록 삭제](../../development/ended-session-deletion.md), [저장 실행 이력](../../development/history-recording-runs.md)

## 목표와 범위

사용자가 지난 연습에서 선택한 종료 기록의 당시 목표, 코스와 과제를 확인하고 직접 과정 마무리를 선언할 수 있게 한다. 저장된 선언은 같은 종료 기록에서 다시 읽는다. 원래 기획의 목표 과정 마무리, 선택한 목표와 연습 이력 정리, 과정 진행 상태 저장과 APP-08의 미완료 요구를 잇는 독립 범위다.

원래 기획은 졸업을 선택한 학습 과정을 마무리했다는 서비스 표현으로 정의하며 운전 자격, 안전성 또는 독립 주행 능력의 자동 인증으로 정의하지 않는다. 완료 횟수, 자동 판정, 완료 단위와 재개 정책까지 이미 정해졌다고 주장하지 않는다. 아래 정책은 이번 구현을 위해 선택한 최소 정책이다.

### 이번에 선택한 정책

- 선택한 종료 session 하나에 선언 하나만 저장한다. 동일 요청은 idempotent하게 기존 선언을 반환하며 최초 선언 시각, version과 원문을 바꾸지 않는다.
- 선언 당시의 전체 PracticeSession snapshot을 별도로 복사한다. 당시 목표, 코스, 과제, 버전, 시연 출처와 연습 시작/마무리 버튼 시각을 보존한다. 현재 카탈로그로 과거 원문을 다시 해석하지 않는다.
- 선언 출처는 USER_DECLARATION이다. 시연 코스 표시와 사용자 직접 선언이라는 출처를 유지하며 운전 능력, 실제 과제 수행, 도로 과정 이수 또는 안전성을 인증하지 않는다.
- 저장은 source 존재, 종료 상태, 전체 snapshot 일치, 활성 session 없음과 canCommit을 같은 Room transaction에서 검증한다. 쓰기 전후 허용과 취소를 확인해 거부나 COMMIT 전 취소가 관찰되면 롤백한다.
- 활성 session 존재가 미확인인 상태를 false로 바꾸지 않는다. 이미 저장된 선언 읽기는 주차와 UX 허용 아래 가능하지만 새 저장은 활성 session이 있으면 거부한다. 저장소도 UI와 독립적으로 모든 session에서 활성 행을 확인한다.
- 기존 준비, session, 메모, 차량 관찰과 recording run은 변경하지 않는다. 선언이 새 연습을 막거나 준비를 지우지 않는다.
- 선언은 source session 삭제 때 FK CASCADE로 삭제한다. 기존 종료 기록 삭제 확인 snapshot에 nullable 선언 전체와 version을 포함해 부재와 존재를 구분하고, 확인 뒤 새 선언이 추가된 경우도 변경 충돌로 거부한다.
- 저장 전 취소는 가능하다. 저장 뒤 독립적인 선언 취소, 수정, 전체 과정 상태, 재개와 회차 정책은 이번 범위가 아니다.
- 자동 삭제, 보관 기한, 공유, 클라우드 기능, 자동 재시작과 자동 능력 판정을 추가하지 않는다. 마무리 선언 자체가 기존 기록 삭제나 보관 종료라는 뜻으로 표시되지 않아야 한다.

[SessionCourseSummary](../../../core/domain/src/main/kotlin/io/github/hangillab/hangil/core/domain/SessionCourseSummary.kt)의 기록 수는 버튼으로 남긴 종료 기록 수다. 모든 과제에 기록이 있거나 N회에 도달했다는 이유로 선언을 만들지 않는다. 기존 [PracticeCourse](../../../core/domain/src/main/kotlin/io/github/hangillab/hangil/core/domain/PracticeCourse.kt)의 DEMO는 코스 출처이며 완료 상태로 재사용하지 않는다.

### 합의한 계층과 계약

- core:domain에 순수 Kotlin 선언 모델, USER_DECLARATION 출처, 저장소와 결과 타입을 이름별 파일로 추가한다.
- 합의한 모델은 CourseCompletionDeclaration(source: PracticeSession, version: String, declaredAtEpochMillis: Long, origin: CourseCompletionOrigin)이다. version은 불투명한 비어 있지 않은 토큰이며 최초 삽입 때 생성한다.
- 합의한 저장소는 CourseCompletionRepository.observe(sessionId): Flow<CourseCompletionDeclaration?>와 suspend declare(expectedSource, declaredAtEpochMillis, canCommit)이다. null은 실제 부재만 뜻하고 손상 자료, 예외와 스트림 완료를 성공 부재로 바꾸지 않는다.
- 합의한 결과는 Saved(declaration), AlreadyDeclared(declaration), MissingSession, SourceChanged, ActiveSession, Blocked와 InvalidTime이다. AlreadyDeclared도 현재 source 전체와 활성 session 없음, 허용 검사를 통과한 경우에 기존 원형을 반환한다.
- 선언 시각은 앱이 기록한 벽시계 값이다. 양수만 검사하고 벽시계 역행을 인정한다. 현재 시각으로 보정하거나 경과 시간, 실제 학습 종료 시각으로 해석하지 않는다.
- core:database는 v6 신규 선언 테이블과 기존 Room observe/transaction을 사용한다. 전체 source snapshot과 version을 보존하고 SQLite 실제 자료형을 검증한다. 알 수 없는 출처나 손상 행은 예외이며 기본 선언으로 만들지 않는다.
- feature:home은 부모 history의 전체 수신 source, generation, reader/query, STARTED host와 live P+UX receipt를 사용한다. source/선택/부모 실패/STOP/주행 거부에서 옛 읽기와 저장 동작을 폐기한다. 저장 완료 응답이 취소돼도 중복 쓰기로 결과를 추정하지 않고 현재 source의 저장 원형을 다시 읽는다.
- app은 DI만 연결한다. SDK, 위치, 내비, 차량 서비스, 새 권한과 새 모듈은 추가하지 않는다.

## 완료 조건

- [x] root가 기준 제품과 고정 M0/M1 하네스, 실제 설치 APK, 원본 자료/source/catalog/environment manifest를 고정하고 제품 변경 전에 기준 측정을 완료했다. 그 전에는 production 코드를 수정하지 않았다.
- [x] UUID 소유 실제 Room과 production route에서 선택한 종료 기록의 전체 원문/사용자 선언/시연 출처를 확인해 저장하고, 새 ViewModel과 실제 Room handle을 다시 열어 같은 기록을 재열람한다. 생산 앱의 저장 후 process death/재실행 검증은 원래 DB 쓰기0 정책상 미실행이다.
- [x] 동일 session의 중복 선언은 최초 source/version/시각을 그대로 반환하며 새 행이나 변경 시각을 만들지 않는다.
- [x] source 없음, 활성 source, 전체 metadata 변경, 다른 활성 session, live P+UX 거부, 호스트 폐기, 취소와 잘못된 시각은 각각 거부/실패로 구분한다. 실패나 COMMIT 뒤 응답 취소를 새 성공/부재로 바꾸지 않는다.
- [x] 조회 null, 손상/오류, 독립 취소와 유한 stream 완료를 구분하고 terminal 실패는 명시 재시도로만 다시 조회한다.
- [x] 옛 펼침/저장/재시도와 source 동일 ID의 다른 metadata가 새 선택 또는 P 복귀의 권한을 빌리지 못한다. 활성 존재 unknown은 저장 허용으로 쓰지 않는다.
- [x] v5 원래 모든 table/row/field/SQLite 자료형/순서/sequence를 보존하고 새 선언 테이블이 빈 상태로 시작하는 migration을 별도로 확인한다. v1/v2/v3/v4/v5에서 v6까지 명시적 경로를 등록하며 destructive fallback을 사용하지 않는다.
- [x] 선언 전후/null 포함 삭제 확인의 exact 비교, source 삭제 CASCADE와 삭제 뒤 늦은 선언의 no-resurrection을 확인한다. 다른 session과 준비/관찰/메모/run은 보존한다.
- [x] 관련 domain/Room/JVM 경계, 전체 JDK17 verify와 APK, 제어 Compose를 확인한다. UUID 소유 실제 Room/route의 기본 1.0 라이트와 확대 1.3 다크 저장/재열람은 합성 차량/UX 입력으로 검증하고, 실제 Main/SDK/생산 Room의 양 테마는 읽기/확인 취소/P-D-P와 선언0 보존으로 구분한다.
- [x] 같은 입력과 고정 하네스의 M0/M1 비교를 완료한다. 새 저장 M2는 아래 반복 조건 미확정에 따라 미측정으로 구분하고 구현 완료나 성능 향상의 조건으로 대신 쓰지 않는다. 실제 원값, 분포와 실패/미실행/누락/제외를 보존한다.
- [x] 원래 DB 전체, 권한/runtime/설정과 baseline 파일을 exact 대조하고 실행이 소유한 검증 자료 및 프로세스/forward/AVD만 정리한다.
- [x] README 대표 사례 4개와 새 실제 근거를 비교해 근거가 있는 경우만 교체하며 기존 실제 코드/이미지와 개인정보 경계를 유지한다.
- [ ] 최신 PR의 동일 head 필수 CI/Codex, 구체적 지적 처리와 관련 실제 검증을 확인한다. 정상 병합, main 동기화/브랜치 정리와 전체 완료 판정은 root가 별도로 수행한다.

## 구현 단계

1. 원래 기획, AGENTS, 현재 코드와 이번 최소 정책을 읽고 domain/DB/UI의 source와 결과 계약을 합의한다. 지금은 이 계획만 작성한다.
2. root가 새 측정 계열 지원을 기준 실행 전에만 준비하고 하네스 SHA/바이트 hash/APK와 입력을 고정한다. 기존 timer와 endpoint를 바꾸지 않고 새 namespace로 기준 M0/M1을 수집한다.
3. root의 실제 기준 완료/보존 확인과 GO 뒤 domain/DB v6 및 삭제 CAS, feature의 읽기/명시 저장과 DI를 작은 단위로 구현한다. 다른 담당자의 파일을 덮어쓰지 않는다.
4. domain/Room/JVM과 실제 Compose 경계, 전체 검증 및 관련 실기에서 실패를 원형으로 보존하고 원인에 맞게 수정한다.
5. 최종 제품의 같은 M0/M1을 실행하고 분포/정확성/보존 결과를 기록한다. M2는 아래 실행 범위 확정에 따라 미측정으로 남긴다.
6. README 사례 비교, 최종 문서와 최신 PR 리뷰를 마친 뒤 root가 전체 완료/계획 이동/정상 병합과 정리를 판정한다.

## 변경 전후 측정

현재 관찰한 공백은 사용자가 과정 마무리 의사를 저장하고 다시 볼 수 없다는 점이다. 느린 DB/렌더 병목을 관찰한 것이 아니며 가속을 가설로 삼지 않는다. 기존 이력 진입과 과제 요약에 추가되는 공통 비용을 전후로 관찰한다. 새 선언 저장에는 대응하는 이전 기능이 없다.

- 기준 제품: `4b1ba6d9c437767c258e9d33843af6c600f24b01`. 리뷰 전 B1은 `a639fc02c2e891527b98d87907a5e10f554006f8`의 고정 product-v2, 최종 B2는 `d1bd59dc5969ca44b94db976032260cd92d71f79`의 고정 product-v3 APK다. M0/M1은 고정 `6f45d5a82bf0b48b0bb0a81f4dd04304633faa24`의 동일 계측 APK로 실제 비교했고, 각 제품의 설치 APK가 해당 고정 원본과 일치함을 확인했다. 전후 원값과 한계는 마지막 최종 계측 결과에 기록한다.
- M0 계열: manual-course-completion-entry-v1. 기존 home_history 클릭 직전부터 같은 source의 history_ready/history_course 실제 가시성, Compose idle와 재확인까지의 정수 elapsedRealtimeNanos/ns와 파생 ms.
- M1 계열: manual-course-completion-tasks-v1. 기존 history_summary_tasks 클릭 직전부터 같은 history_summary_scope 실제 가시성, Compose idle와 재확인까지다. 기존 사전 이동/원문 검사/닫기 계약을 유지한다.
- M0/M1은 기존 진입/과제 요약의 공통 wall time이다. 새 선언 영역은 닫혀 있고 새 선언 reader를 구독하지 않아야 한다. SDK 관찰이나 새 선언 조회/쓰기 자체의 속도로 부르지 않는다.
- root가 두 계열을 지원하는 frozen 하네스의 완전 SHA, source 바이트 hash와 실제 instrumentation APK를 기준 측정 전에 고정한다. 전후 동일한 하네스와 namespace 계약을 사용하고 과거 완료 원형을 덮어쓰지 않는다.
- 동일 조건: 같은 original fixture, 전체 수신 source와 생성 순서, catalog와 앱 가용 영역, 활성 연습/FGS 없음, 실제 SDK P+UX 허용. JDK17/debug/Pleos API34/font 1.0/라이트를 사용한다. 확대 1.3/다크 correctness는 timing에 합치지 않는다.
- 각 metric/stage는 외부에서 시작한 fresh process/Activity 뒤 같은 프로세스의 5warm과 30본이다. 모든 Gradle 작업 종료 뒤 실행하며 캐시, 앱 자료와 Tile/DB/ART/JIT 상태를 지우지 않는다.
- 순서는 A1→B로 계획한다. root가 안전하게 같은 원본/제품을 유지할 수 있으면 별도 A2의 5warm/30본을 추가하며 A1을 대체하지 않는다. A2를 못 하면 순차 실행 한계를 남긴다.
- 클릭/동기화/semantics 비용을 포함하며 준비/사전 스크롤/JSON/export/닫기/전체 자료 대조/프로세스 시작은 기존 계약대로 제외한다. 순수 DB, 프레임/GPU, SDK 지연 또는 운전자 행동 시간이 아니다.
- 20초 endpoint 한계, 다른 source, 허용 상실, 실제 Main/viewport 미확인, 예외와 원본/환경 불일치는 실패다. 느린 성공 표본을 제외하거나 유리한 재시도로 교체하지 않는다. 실패 시간과 성공 latency를 구분하고 없는 값은 null로 남긴다.
- 원문 JSON, 전체 fixture, 실제 식별자/시각/환경과 설치 provenance는 ignored 비공개 자료로만 보존한다. 공개에는 익명 조건과 원값, 표본 및 한계를 기록한다.

### M2 신규 저장은 변경 후 특성

M2 후보 endpoint는 명시적 최종 확인 클릭 직전부터 Saved의 전체 declaration 원형, 현재 source의 재열람 값과 실제 표시/Compose idle 재확인까지다. 저장 전 확인 화면 준비와 원본 대조, export와 정리는 timer 밖이다. 실제 저장소/Room과 UI의 비용, gate 및 receipt 확인 비용을 포함하며 SDK 처리시간이 아니다.

신규 저장과 AlreadyDeclared는 다른 결과다. 같은 source에 35번 클릭해 첫 저장과 34번 중복 반환을 신규 저장 표본으로 합치지 않는다. 반복 측정은 원본과 분리한 명시적 소유/격리 fixture와 같은 최초 미선언 상태를 필요로 한다. root가 반복 fixture, 초기화/정리, 실제/제어 근거 범위, 새 하네스와 5warm/30본 조건을 쓰기 전에 확정한다. 그 조건이 확정되지 않은 동안 M2는 미측정으로 남긴다. 실제 원래 기록에 선언을 만들어 표본을 채우거나 기존 자료를 지우지 않는다.

실제 UI의 쓰기/저장 검증은 별도 UUID 소유 Room과 production repository/route를 사용한다. 실제 Main/SDK/생산 Room 검증은 원래 자료 읽기, 확인 취소와 gate로 분리하며 생산 DB에는 선언 0개를 유지한다. 새 기능의 소유 fixture/제어 SDK 계측을 실제 앱/SDK의 일반 성능으로 확대하지 않는다. 주 성능 지표는 M0/M1의 같은 원본 공통 비용이다.

M2의 변경 전 값은 해당 행동 없음이다. 0, 기존 실패 대기 시간이나 M0/M1을 baseline으로 삼지 않고 개선율을 계산하지 않는다. 신규 선언의 실제 저장/복원 correctness는 성능 표본 수와 별도로 검증한다.

### migration과 입력 동일성

비교의 의도한 차이는 v6 신규 선언 테이블과 새 UI/쓰기 계약이다. M0/M1 비교 전 새 테이블의 원본 대응 선언은 0개여야 하며 v5 원래 table/row/field/물리형/순서/sequence는 baseline과 같아야 한다. 새로운 migration 테이블의 정상 부재를 다른 원래 자료 소실과 섞지 않는다. 원래 순서는 소유 쓰기도 공통 비교 뒤로 계획했으나, 실제 실행은 정확성 결함을 먼저 찾기 위해 UUID 격리 Room 검증을 앞에 배치했다. 해당 저장소는 생산 DB와 별도이며 생산 선언 0개와 원래 자료/source를 다시 exact 대조한 뒤 공통 비교를 실행한다. M2는 실행하지 않는다. 이 변경으로 실행 순서/캐시 효과가 제거됐다고 주장하지 않는다.

통계는 본 표본 30개의 중앙값, Q1/Q3, IQR과 min/max다. Q1/Q3는 정렬한 하위 15개/상위 15개의 중앙값으로 고정한다. 절대 차이와 상대 변화의 방향/공식을 명시하고 기준값이 0이면 상대율을 만들지 않는다. 분포 중첩과 순차/캐시/열/OS 효과를 분리하지 못하면 향상, 인과 저하, 동등성이나 무회귀를 확정하지 않는다. 테스트 수, 상태 수와 보존 행 수는 정확성의 보조 근거이며 성능 수치가 아니다.

아래 표는 계획 작성 당시 상태다. 실제 기준과 후속 결과는 실행별 기록에 구분한다.

| 항목 | 변경 전 | 변경 후 |
|---|---|---|
| 실제 실행한 M0 5warm/30본 | 미실행 | 미실행 |
| 실제 실행한 M1 5warm/30본 | 미실행 | 미실행 |
| 새 저장 M2 | 대응 행동 없음 | 미측정 |
| 중앙값/Q1/Q3/IQR/min/max | 미측정 | 미측정 |
| 실패/누락/미실행/제외 | 미실행 | 미실행 |

[전후 측정 기준](../../development/measured-improvements.md)에 따라 실행한 원값만 기록한다.

## 계획 작성 당시 검증 상태

아래 표는 작성 당시 기록이다. 현재 실제 결과는 뒤의 실행별 결과에 구분한다.

| 명령 또는 시나리오 | 결과 | 근거 |
|---|---|---|
| 원래 기획/현재 domain/Room/이력 계약 읽기 | 수행 | 원래 과정 마무리 의도와 이번 최소 정책을 구분 |
| 계획 작성 | 수행 | TEMPLATE의 목표/범위/완료 조건/측정/결과/인계 구조 사용 |
| production 구현 | 미착수 | root의 실제 기준 M0/M1 완료와 GO 대기 |
| 기준/비교 M0/M1 및 M2 | 미실행 | 결과나 성능 개선을 만들지 않음 |
| domain/Room/JVM/Compose 및 실제 양 테마 | 미실행 | 제품 구현 이후 |
| `./scripts/verify.sh`와 APK | 미실행 | root 순차 실행 |
| 원본/환경 최종 exact 보존과 정리 | 미실행 | 실행별 root 관리 |
| 최신 PR CI/Codex/병합 | 미실행 | root가 동일 최종 head 기준 판정 |

이 계획 작성은 앱 실행 경로를 바꾸지 않는 문서 작업이다. 계획 자체에는 새 성능 측정이 필요하지 않지만 예정된 제품 변경에는 위 실제 기준 측정이 선행한다.

## 계획 작성 당시 남은 문제와 인계

- 지금 허용된 편집은 이 계획 파일뿐이다. 기준 측정이 끝나기 전 domain/DB/UI production 변경과 새 migration/schema 생성을 시작하지 않는다.
- root는 AVD/환경 보존/측정/Gradle/Git/PR을 소유하고 별도 담당은 feature UI/VM/reader와 해당 검증을 소유한다. 이 계획 담당은 baseline GO 뒤 domain/DB/삭제 CAS 및 관련 JVM/Room 검증을 맡을 예정이다.
- 모델/저장소/결과 명칭과 양수 시각/벽시계 역행 인정 정책은 root와 UI 담당에게 합의 전달했다. v6 전체 snapshot column/물리형, nullable 삭제 receipt의 구현과 M2 반복 fixture는 baseline GO 뒤 구체화한다.
- COMMIT 이후 응답 전달 취소는 실제 저장이 없었다는 뜻이 아니다. 현재 source의 idempotent 재조회와 이미 저장된 원형을 사용해 불확실성을 해소해야 한다. 저장 전 취소와 source 변경은 쓰기를 막아야 한다.
- 전체 과정 상태, 회차, 선언 독립 취소/재개, 보관 기간과 암호화 정책, 실제 위치/도로, Navigation 요청 원인 미확정과 기존 legacy 리뷰는 이 부분 구현의 완료로 대체하지 않는다.
- 완료 조건과 최신 PR 검증을 충족한 뒤 root가 완료 표시와 completed 이동, 정상 병합 및 main/브랜치 정리를 판정한다.

## 제품 변경 전 실제 기준 확보

기준 제품 `4b1ba6d9c437767c258e9d33843af6c600f24b01`, 고정 계측 `6f45d5a82bf0b48b0bb0a81f4dd04304633faa24`를 사용했다. 측정 계열 지원만 추가한 계측 APK와 제품 APK/소스 해시를 기준 실행 전에 고정했고, 설치한 실제 APK 해시도 같다. 처음 계획의 EOF 빈 줄은 diff 검사에서 중단된 준비 실패였고 문서 끝만 정리한 뒤 통과했다. JDK17 spotlessCheck/debug/계측 APK 빌드는 성공했다. 실제 Main/P와 브라우저 DDS ONLINE을 확인한 뒤 SDK P+UX 및 각 endpoint를 측정 하네스에서 재확인했다.

| 기준 metric | warm/본 | 중앙값 ms | Q1/Q3 ms | IQR ms | min/max ms | 실패/누락/제외 |
|---|---:|---:|---|---:|---|---|
| M0 기존 이력 진입 |5/30|279.446584|260.963084/307.00525|46.042166|241.263584/662.491709|0/0/0|
| M1 기존 과제 요약 펼침 |5/30|168.898625|167.479166/170.427916|2.94875|163.258917/186.706|0/0/0|

두 보고서는 모두 completed이며 원문과 source/catalog/environment, 제품과 계측 원본 및 실제 APK가 일치한다. 느린 M0 성공 표본도 제외하지 않았다. 각각 fresh process/Activity 뒤5warm/30본, JDK17/debug/API34/font1.0/라이트, 캐시와 자료를 삭제하지 않은 조건이다. schema5 준비1/종료13/관찰145/메모1/terminal run15/활성0과 모든 원래 필드/물리형/생성 순서/sequence/권한/runtime/설정이 외부 fresh snapshot과 exact 일치했다. 소유 AVD/forward/DDS 탭도 종료했다. 이전 앱이 생산하는 선언 테이블은 아직 없으며 제품 변경/비교/M2/실기 성공이나 성능 향상으로 쓰지 않는다. 이 실제 기준 확보 뒤에만 core/DB와 feature 구현 착수를 허용했다.

## 신규 저장 계측의 실행 범위 확정

새 저장은 session당 최초 선언1회와 이후 AlreadyDeclared가 다른 행동이다. 원래 생산 자료의 선언0을 보존하며 동일한 신규 저장35표본을 만들려면 별도 반복 fixture/DB와 초기화/열기/캐시 조건을 추가로 고정해야 한다. 이 실행은 해당 M2 하네스를 작성하거나 표본을 수집하지 않는다. 관찰된 결과를 골라 제외하는 결정이 아니라, 아직 정의/실행하지 않은 부가 지표를 분리한 범위 결정이다. 명시 저장과 영속 복원은 소유 실제 Room과 생산 route의 정확성 검증으로 수행하며 그 JUnit 시간이나 제어 시각을 앱/SDK 성능으로 쓰지 않는다. M0/M1은 이미 정한 동일 원본 공통 UI 비용의 전후 실측으로 그대로 수행하고 새 저장 속도/개선율이나 처리량으로 확대하지 않는다.

예정된 v5→v6 migration 뒤에 v5 앱으로 왕복하면 기존 자료의 downgrade 문제가 생길 수 있어 이 실행에서는 A2를 하지 않는다. 원래 자료를 지우거나 백업으로 덮어써 순서를 맞추지 않으며 A1→B의 캐시/순서/열/OS 편차 한계를 남긴다.

## 초안 구현과 부분 자동 검사

동결한 core/domain과 실제 Room의 선언/중복/전체 source/다른 active/삭제 CAS/물리형/migration 검사는 JDK17 타깃 실행에서 통과했다. 앱 debug 생산 빌드와 실제 Hilt 연결도 통과했다. feature 첫 실행은 새 테스트 Pair/Flow 제네릭 추론 오류로 컴파일에서 중단됐고 본문은 실행하지 못했다. 입력과 기대 조건은 바꾸지 않고 타입만 명시한 후 동일 feature 타깃이 통과했다. 기존 coroutine opt-in과 migration 혼합 List 타입 경고는 검사 실패와 구분한다. 아직 전체 verify/계측 APK/실제 UI/비교 결과는 미실행이다.

독립 읽기 검토에서 새 reader가 이미 Locked/Unavailable를 발행한 뒤 후속 상태가 덮을 수 있는 경계를 발견해 폐기된 owner의 후속 발행을 막았다. 실행 RED나 실기 재현으로 쓰지 않는다. 별도 debug 격리 저장소의 reopen에서도 새 handle 열기와 첫 정리가 모두 실패하면 handle 참조를 잃을 수 있어 openOwned 반환 직후 새 handle을 보존하도록 정정했다. 실제 사용자 DB와는 무관한 UUID 소유 자원 경계다. close 재시도 성공이나 해당 실패의 실제 재현까지 검증했다고 주장하지 않는다.

## 최종 자동 검사와 계측 준비

이 절은 첫 native 실행 전의 자동 검사 시점이다. 당시 미실행 표현과 이후 실패 원형은 보존하며 후속 결과는 뒤의 리뷰 전 B1 당시 기록과 마지막 PR #69 수정 후 최종 결과에서 구분한다.

생산/검사 소스 동결 뒤 JDK17 전체 `./scripts/verify.sh`의4개 Gradle 단계가 통과했다. app/DB 계측 APK도 컴파일을 통과했다. JVM XML55 suites/865개/실패0/오류0/건너뜀0은 cache/up-to-date 포함 가능성이 있는 정확성 근거다. 최초 strict포맷3개 긴줄, 후속2개 if배치+1개 긴줄, 잔여1개 긴줄 실패는 준비 실패로 보존했다. 동일 상태 타입의 짧은 import별칭과 줄바꿈만 사용했고 규칙/검사/실패 조건을 완화하지 않았다. 수정 뒤 strict formatter/quality와 전체공식verify를 확인했다.

README는 기존4사례/6항목/22실제Kotlin발췌/18기존이미지를 유지하며 이동한 소스 anchor와 삭제 확인 모델의 실제 nullable선언 필드만 맞췄다. 두 번째 사례의 exact child snapshot 비교에 선언이 포함되는 근거를 기존 회귀 집계 도구에도 연결했다. 새 사례를 다섯 번째로 만들거나 검사 수를 성능으로 쓰지 않는다. 지금은 실제 native/전후 비교/최신 PR 검증을 아직 실행하지 않았으며 이 자동 검사로 대신 완료 처리하지 않는다.

## 실제 장치 첫 실행과 중단

제품 `6c4265cdb10794267e9e1c35a419d4e2ef2f5dcc`를 기존 앱에 덮어 설치했다. 첫 촬영은 빈 전환 화면이어서 성공 근거로 쓰지 않았다. 다시 실제 Home과 P 표시를 확인한 뒤 v6 migration을 포함한 fresh snapshot을 대조했다. 원래 v5의 모든 행/필드/물리형/순서/sequence와 권한/runtime/설정이 일치했고 새 선언 테이블만 빈 상태로 추가됐다.

첫 제어 Compose A는 4개 중 2개 통과/2개 실패였다. 실패 두 건은 확인 dialog footer의 confirm 버튼에서 `performScrollTo()`가 스크롤 부모를 찾지 못해 발생했다. 해당 버튼 동작 검증에 도달하지 못한 테스트 도구 오류이며 제품 저장 실패나 통과로 바꾸지 않는다. 원형 실패 로그를 비공개로 보존하고 재실행 로그와 분리한다. 실패 뒤 설정 복원과 원본/빈 선언 exact 보존을 확인하고 소유 AVD/forward/DDS만 종료했다.

## 실제 경계 재실행과 화면 넘침 발견

footer 수정 `9249f1bee499dfcf2184a1c98ee2cceb9aa036bd`의 strict 포맷/계측 APK 빌드는 통과했다. 첫 재실행 A는 4개 중 2개 통과/2개 실패로, 하나는 실제 호스트 PAUSE/STOP/DESTROY 관찰, 하나는 확인창 취소 버튼 직전 focused package 미확인이다. 사후 기본 내비 화면은 실패 당시 전환 원인을 확정하는 근거가 아니며 원인은 미확정으로 남긴다. 같은 APK/동일 assertion으로 전면 앱을 다시 확인한 뒤 A 4개가 통과했다. 앞선 실패 표본을 지우거나 성공으로 교체하지 않았다. UUID 격리 실제 Room B 라이트 3개도 통과했다.

실제 Main/SDK/생산 DB C 라이트는 새 request 버튼의 `TextLayoutResult.didOverflowWidth`에서 실패했다. 확인 취소와 P-D-P까지 진행하지 못했으므로 그 동작 성공으로 기록하지 않는다. 실제 문구는 한 줄, 180×34px, 가로 넘침 true/세로 false/ellipsis 없음이었고 화면/원본 layout을 비공개로 보존했다. 수정 전 제품은 `6c4265cdb10794267e9e1c35a419d4e2ef2f5dcc`, 같은 원본/1.0라이트/API34/debug 조건이다. 가설은 fullwidth 버튼 안의 intrinsic 너비 Text가 정수 배치 너비로 제한된다는 점이다. 글자와 간격을 줄이지 않고 Text에 실제 가용 너비와 중앙 정렬을 부여한 뒤 동일 C의 가시성/원문/넘침 검사를 양 테마로 다시 실행한다. 이 정합성 지표를 앱/SDK 성능 향상이나 임의 개선율로 부르지 않는다. 기존 공통 M0/M1 계약은 그대로다.

수정 전에 원래 설정 복원, 생산 선언 0개와 기존 모든 자료의 exact 보존을 확인하고 소유 장치와 forward/DDS를 정리했다.

너비 수정 뒤 JDK17 전체 verify 4단계와 계측 APK 컴파일이 다시 통과했다. 앱 모듈 JVM55 suites/865개와 별도 build-logic1 suite/4개는 각각 실패/오류/건너뜀0이다. 이 값은 cache/up-to-date를 포함할 수 있는 정확성 근거이며 앱 성능으로 사용하지 않는다. 실제 화면 재실행과 변경 후 M0/M1은 아직 대기다.

저장 완료 UI를 실제로 읽기 위해 B에 별도 UUID Room/실제 Route/제어 SDK라는 출처를 명시한 비공개 PNG와 저장 원문/시각/글리프/실제 창 영역 검사를 추가했다. 기본1.0 라이트와 확대1.3 다크 인자를 필수로 하며 미확인을 성공으로 기본 처리하지 않는다. 추가 하네스의 긴 줄 포맷 2회 실패를 보존하고 capture 범위 표현식만 변수로 분리한 뒤 strict 포맷과 계측 APK 컴파일을 통과했다. 제품 소스와 고정 M0/M1은 이 검증 도구 추가로 바뀌지 않았다.

## 수정 제품의 실제 양 테마 검증과 초기 준비 실패

이 절의 B 다크 2/1과 A 3/1 두 회는 최종 호스트 준비 보완 이전의 당시 기록이다. 최종 동일 제품/최신 계측의 성공과 이전 실패를 아래 별도 결과에서 구분한다.

제품 `a639fc02c2e891527b98d87907a5e10f554006f8`, 계측 `b17902bf053f785a0c82c88fa6b894dfc83933f9`의 실제 Main/SDK/생산 Room 읽기 C 라이트1개와 확대1.3 다크1개가 각각 통과했다. 같은 request/confirm/cancel의 원문/가시성/넘침 검사를 유지했으며 실제 브라우저 DDS ONLINE의 D와 P 입력 뒤 앱 SDK 잠금/복귀를 확인했다. 확인창 취소, 선택/재생성 뒤 접힘과 옛 확인 의도 미복원, 생산 DB의 선언0을 함께 확인했다. 원래 C의 문구 넘침 실패를 별도로 보존했다.

최종 B 라이트3개가 통과했고 실제 저장 완료/새 Room handle 재열기/응답 취소 뒤 재열람의 문구와 저장 시각, 실제 글리프/창 영역을 검사했다. 확대 다크 B는2개 통과/1개가 처음 호스트 준비 단계에서 실패해 아직 전체 통과로 기록하지 않는다. C의 양 테마 실제 확인창과 B의 별도 UUID 합성 source 저장 화면을 직접 읽어 글자/버튼/출처/취소 동작을 확인했다. 이미지와 원형 source는 비공개이며 public README 이미지를 추가하지 않는다.

같은 제품의 A는 두 실행 모두3개 통과/1개 실패로, source-selection 시나리오의 첫 mount에서 이미 다른 focused package를 관찰했다. 경계 본문 실행 전에 발생한 실패를 독립 준비 조건으로 조사하며 assertion을 완화하거나 미확인을 성공으로 바꾸지 않는다. 기존 전체4개 통과는 이전 제품/계측의 과거 검증으로 구분한다.

모든 원래 v5 행/필드/물리형/순서/sequence, 생산 선언0과 권한/runtime/원래 font/night/IME/proxy가 v6 빈 선언 테이블 추가 외에 exact 일치했다. 소유 장치/forward/DDS를 정리한 뒤 테스트 시작 준비 조건만 수정한다. 실제 위치/내비 연동 성공이나 원래 생산 자료에 쓴 성공을 주장하지 않는다.

호스트 준비 보완은 실제 Catalog의 RESUMED/유효 display/window focus를 제한 시간 안에 확인한 뒤에만 제어 owner/observer와 VM을 만든다. 본문/확인창에서의 actual package, 같은 display, host 개입과 옛 콜백 검사는 그대로며 다른 앱을 발견하면 즉시 실패한다. 정확한 플랫폼 전환 원인을 확정한 앱 수정이 아니라 시작 준비 계약의 분리다. 후속 실패 시 구분할 수 있도록 실제 focus/root/host 이벤트의 비공개 진단을 보강했다. 포맷과 계측 APK 컴파일은 통과했고 실제 재실행은 다음 단계다.

## 리뷰 전 B1 native 확정 당시 기록

아래 제품/성공/준비 실패는 리뷰 수정 전 B1의 당시 기록이며 최종 d1bd59의 새 실행 결과는 마지막 리뷰 수정 결과에서 구분한다.

최종 제품은 `a639fc02c2e891527b98d87907a5e10f554006f8`의 고정 product-v2 APK이고 최신 native 계측 원본은 `9f1950e7fcab80cbf4f0b8b7fe66c3b4a70e4a9b`다. 제품의 JDK17 전체 verify4단계와 app/DB 계측 APK 컴파일이 통과했다. 앱 프로젝트 JVM XML55 suites/865개와 별도 build-logic1 suite/4개는 각각 실패/오류/건너뜀0이며 두 집계를 합쳐 앱 검사 수로 쓰지 않는다. cache/up-to-date 포함 가능성이 있는 정확성 근거이며 성능 수치가 아니다.

| 실제 범위 | 최종 결과 | 의미와 한계 |
|---|---|---|
| A 제어 Compose | 최신 9f1950에서4PASS | 실제 Compose/Activity와 합성 lifecycle/source/저장소/차량 및 UX gate. 옛 callback, 확인/취소와 receipt 경계를 검증하며 실제 OS PAUSE 성공이나 SDK 장애 주입이 아님 |
| B 소유 Room 기본1.0/라이트 | 최신 9f1950에서3PASS | UUID 소유 실제 Room, production 저장소/route와 합성 차량/UX 입력. 최초 선언/재열람/DB 재개방/결과 취소와 원형/중복을 확인 |
| B 소유 Room 확대1.3/다크 | 최신 동일 APK 첫 회2PASS/1setup실패, 재시도3PASS | 첫 실패는 owner 생성 전 실제 Catalog 준비 조건의20초 대기 timeout. 실패 원형을 유지하고 같은 APK/검사로3개 모두 통과한 별도 재시도를 기록 |
| C 실제 Main 기본1.0/라이트 | b179 계측에서1PASS | 실제 Main/SDK P+UX/생산 Room 읽기, 확인 취소, source 선택/재생성, DDS D-P와 생산 선언0. 최종 확인 저장은 누르지 않음 |
| C 실제 Main 확대1.3/다크 | 같은 b179 계측에서1PASS | 같은 실제 읽기/취소/gate와 원본 보존. 소유 fixture의 저장 성공과 생산 DB 쓰기를 혼동하지 않음 |

C를 실행한 계측 원본은 `b17902bf053f785a0c82c88fa6b894dfc83933f9`다. 최신9f1950에서도 C 파일, C가 재사용하는 기존 실제 읽기 Harness와 제품이 바이트 동일함을 확인했다. C를9f에서 새로 실행했다고 바꾸어 쓰지 않는다. A/B의 호스트 준비 변경은 실제 Catalog RESUMED/유효 display/window focus를 확인한 뒤에만 제어 owner/observer를 생성한다. 본문과 확인창의 실제 focused package/같은 display/host 개입 및 옛 token 검사와20초 준비 한계를 유지한다. 다른 알려진 앱이나 미확인을 기대값으로 채우지 않았다.

직전 A의3PASS/1mount foreign 실패 두 회, B 다크의2PASS/1초기 host 실패와 최종9f 첫 B 다크의2PASS/1setup timeout은 모두 원형을 보존한다. 어느 준비 실패도 앱 동작 성공으로 바꾸거나 관측 조건만으로 플랫폼 전환의 원인을 확정하지 않는다. 앞선 footer 스크롤 실패, 실제 host 개입/AX 미확인과 C의180×34px 가로 넘침 실패도 삭제하지 않았다. 글자/간격 축소와 검사 완화 없이 가용 폭/중앙 정렬을 보완한 제품에서 C 양 테마가 통과했다.

실제 C 확인창 라이트/다크와 B 저장 완료 라이트/다크의 비공개 원본 이미지4개를 직접 읽어 원문/큰 글자/출처/버튼/선언 시각 표시를 확인했다. TextLayout의 overflow/ellipsis 검사는 보조 검사로 구분하며 실제 글리프 검토를 대신했다고 주장하지 않는다. A의 합성 화면을 실제 SDK 저장 증거로 쓰거나 새 공개 이미지를 만들지 않았다.

native 종료 시점의 외부 exact 대조에서 원래 v5 모든 table/row/field/SQLite 물리형/저장 순서/sequence와 grants/runtime/원래 font/night/IME/proxy를 보존했다. 의도한 차이는 schema6의 빈 선언 테이블뿐이며 생산 선언은0개다. 원래 준비/연습/관찰/메모/run에 쓰지 않았고 저장/복원 검증의 쓰기는 별도 UUID 소유 DB에 한정했다. 이 시점의 원본/설정 복원과 소유 자원 정리 근거를 비교 계측 종료 후의 최종 대조와 구분한다.

native 종료 당시에는 M0/M1 변경 후 측정과 공개140행 CSV가 아직 준비 중이었다. 이후 두 원형 baseline/고정6f45 계측, source/catalog/environment/기존 자료와 생산 선언0을 대조하며 아래 최종 비교를 완료했다. M2는 하네스/표본 없이 미측정이고 A2는 v6→v5 downgrade 위험 때문에 실행하지 않는다. 현재까지의 자동/native 성공은 성능 향상, 동등성/무회귀나 앱의 능력 인증/전체 APP-08/보관 정책 완료가 아니다.

- [x] 제품 구현, 전체 자동 검사와 위 범위의 A/B/C 및 실제 네 이미지 검토.
- [x] native 종료 시점의 원래 자료/설정 exact 보존과 생산 선언0 확인.
- [x] 같은 입력/고정 계측 APK의 M0/M1 after, 실제140행 원값과 분포/실패/미실행/누락/제외 및 한계 기록.
- [x] 비교 종료 뒤 최종 원본/권한/설정/원형 baseline 보존과 이번 소유 자원 정리.
- [ ] 최신 PR 동일 head의 필수 CI/Codex, 새 구체 지적 처리와 정상 병합/동기화/브랜치 정리. root의 전체 완료 판단 전 completed로 이동하지 않음.

## 리뷰 전 A→B1 계측과 보존 당시 기록

아래140행과 네 보고서는 원래 기준 A/리뷰 전 중간 B1이며 변경하지 않는다. 최종 B2의70행을 새 파일로 보존하고 마지막 결과에서 A/B1/B2를 비교한다.

[익명 원값140행](../../development/measurements/manual-course-completion-ui-times.csv)은 M0/M1 각각의 변경 전/후5warm+30본이다. 네 보고서 모두 completed/valid이며 워밍업과 본 표본의 실패/누락/미실행/제외0이다. 통계는30본만 사용하고 정수ns에서 정확한ms를 유도했다. Q1/Q3는 정렬한 하위15/상위15 각각의 중앙값, IQR은 Q3−Q1이다. 느린 성공 표본도 그대로 보존했다.

| 지표/단계 | warm/본 | 중앙값 ms | Q1 / Q3 ms | IQR ms | 최소 / 최대 ms | 실패/누락/미실행/제외 |
|---|---:|---:|---|---:|---|---|
| M0 변경 전 | 5/30 | 279.446584 | 260.963084 / 307.00525 | 46.042166 | 241.263584 / 662.491709 | 0/0/0/0 |
| M0 변경 후 | 5/30 | 274.0576875 | 253.070208 / 290.953208 | 37.883 | 236.348875 / 356.42775 | 0/0/0/0 |
| M1 변경 전 | 5/30 | 168.898625 | 167.479166 / 170.427916 | 2.94875 | 163.258917 / 186.706 | 0/0/0/0 |
| M1 변경 후 | 5/30 | 169.5672085 | 168.577459 / 171.632833 | 3.055374 | 154.113333 / 193.424583 | 0/0/0/0 |

M0의 중앙값 관측 차이는 −5.3888965ms/−1.9284173823%, M1은 +0.6685835ms/+0.3958489893%다. 상대 변화는(변경 후−변경 전)/변경 전이며 백분율은 소수점10자리로 반올림했다. 양수는 더 긴 시간이다. 양쪽 Q1/Q3 구간이 겹치며 작은 차이만으로 성능 향상, 인과적인 성능 저하, 동등성 또는 무회귀를 확정하지 않는다.

고정6f45 계측 APK의 전후SHA256과 두 하네스 원문 바이트hash가 같다. 기준4b와 비교a639 product-v2의 실제 설치 제품 APK는 각 단계의 고정 원본과 일치한다. M0/M1의 originalData/full fixedSource/catalog/environment와 각 metric의 measurementContract, baseline 원바이트hash 및 비교의 원본 선언이 모두 일치했다. 현재 소스로 계측 APK를 새로 빌드한 성공과 보존된 실제 계측 binary의 동일성을 구분한다.

실행은 A1→B 한 순서이며 A2를 하지 않았다. v6→v5 downgrade를 위해 원래 자료를 삭제하거나 백업으로 덮어쓰지 않았다. 캐시/ART/JIT/프로필/발열/OS scheduling/시간과 실행 순서의 영향을 분리하지 못한다. M0는 기존 이력 진입, M1은 기존 과제 요약 펼침의 계측 wall time으로, 새 선언 조회/쓰기 또는 SDK/DB/frame/GPU 성능이 아니다. M2는 반복 fixture와 metric 미정으로 하네스/표본 없이 미측정이며 전값0/after값/개선율을 만들지 않는다.

별도 Decimal 검산으로 공개CSV의140행 정수ns↔ms, 각5warm/30본과 네 분포가 실제 summary와 정확히 일치했다. 첫 보조 검산은 benchmark 열을 내부 메서드 이름으로 오해해0행을 선택한 assertion 실패였고 실제M0/M1 스키마로 정정한 뒤 통과했다. CSV 원값 변경은 없으며 측정 표본 실패0과 분석 도구의 준비 실패를 구분한다.

계측 뒤 외부 exact 대조에서 원래 v5의 모든 table/row/field/SQLite 물리형/순서/sequence, grants/runtime/원래 font/night/IME/proxy와 두 baseline 원바이트를 보존했다. schema6의 선언 테이블은 빈 상태0이고 기존 생산 자료에 쓰지 않았다. 별도 UUID 소유DB/marker/sidecar 잔존은0이며 이번 소유 AVD/forward를 종료하고 연결된 DDS 탭을 닫았다. 이 최종 보존은 native 직후의 중간 점검과 별도로 확인했다.

B의 저장 복원 근거는 새 ViewModel과 실제 Room handle 재개방/재열람이다. 실제 생산 앱에서 선언 저장 후 프로세스를 종료하고 다시 실행하는 시나리오는 원래 DB 쓰기0 정책상 미실행이며 새 성공 주장으로 확대하지 않는다. 공개 재현 옵션/명령과 필수 입력/환경 계약은 [개발 문서](../../development/manual-course-completion.md)에 연결한다.

구현/자동/native/전후 계측/보존은 확인했지만 최신 PR 동일 head의 필수 CI/Codex, 새 구체 지적 처리와 정상 병합/동기화/브랜치 정리는 아직 별도다. APP-08 전체/운전 능력 인증/보관 정책의 미완료도 유지하며 계획을 completed로 옮기지 않는다.

## README 사례 선정 비교

이번 선언은 사용자 확인 nonce, 전체 source snapshot, idempotence와 COMMIT 뒤 응답 취소의 재조회 경계를 제품에 연결한다. 다만1번의 실제 무방출 최신 주행값/오래된 화면 콜백 경합,2번의 파괴적 삭제 확인/전체 연결 자료 CAS/원자적 롤백,3번의 비동기 조회/네이티브 MapView 렌더와 직접 입력 소유권,4번의 기록 소유권 반환/복귀 상태 덮어쓰기보다 강한 새 원인 규명이나 성능 향상 근거를 확보한 것은 아니다. 기존 안전 reader와 transaction 계약을 재사용한 확장이고 새 native 호스트 준비 및 문구 넘침은 검증/배치 정합 보완이므로 네 사례의 내용/순서를 보존한다.2번의 확인 snapshot에 nullable 선언 전체가 추가된 실제 모델과 소스 링크, 해당 회귀 집계 대상만 맞췄다. 기능 소개에는 실제 확인한 수동 선언을 한 행 추가하고 사진과 회귀 수치/목록은 추가하지 않는다. 후속 P2의 원인은 영구 invalidate와 살아 있는 펼침 collector 사이의 연결 누락이었고 전용 read epoch로 복원했다. 실제 RED/Room/양 테마 근거를 확보했지만 기존4사례의 주행 경합/파괴적 CAS/네이티브 입력 소유권/recorder 반환보다 단순한 통합 복원이며 성능 향상도 입증하지 않았다. 따라서 이 지적으로 사례를 교체하지 않고 정확히4개를 유지한다.

## 최종 문서와 원값 검산

마지막 README 기능 소개/출처 링크, 작업 기록과 익명 CSV 배치는 앱 실행 경로를 바꾸지 않아 새 성능 측정 적용 대상이 아니다. 같은 제품/계측 소스와 실제 APK의 앞선 검증을 보존하고 공개 원값의 독립 Decimal 재계산, source 바이트 동일성, 상대 링크/README 발췌/정적 개인정보 guard와 diff 검사를 수행한다. 새 앱 성능 성과를 추가하지 않는다. 최신 PR의 CI/Codex 완료와 정상 병합은 아직 별도 조건이다.

## PR #69의 삭제 취소 뒤 reader 복원과 최종 결과

최초 head `dffc61254dcb0ce0efe7445abfceb616a4fc34a1`의 [원래 P2](https://github.com/HanGilLab/Android/pull/69#discussion_r4433563408)는 삭제 확인 취소/준비 실패 뒤 펼친 과정 마무리 본문이 사라지는 문제다. requestDeletion의 invalidate는 기존 reader를 영구 revoked로 만들지만 같은 source/generation의 Compose Flow가 유지되고 삭제 상태만 지워져 새 읽기가 시작되지 않았다. 저장 자료 손실이나 능력 판정이 아니라 읽기/새 명시 요청의 복원 결함이다.

계획을 먼저 기록한 뒤 focused JVM5개를 실제 실행해4실패/1통과를 확인했다. 원래 RED/XML/source 바이트를 보존했고 제품 수정 뒤 같은5개 source 바이트로5통과를 확인했다. 별도 guard6개와 기존 취소/선언 검사를 포함한 전체 feature386개 실패0도 확인했다. 뒤의 긴 if 식 포맷1회 실패는 괄호/줄바꿈만 정정했으며 RED 기대나 기존 검사 조건을 완화하지 않았다.

수정은 선언 읽기만의 refresh epoch로 새 reader/query를 연결하며 옛 nonce/token/저장 의도는 영구 폐기한다. 현재 full source/generation/STARTED host/live P+UX가 맞는 안전한 PREPARING/CONFIRMING 취소 및 준비 실패 뒤에만 새 읽기를 연결한다. Idle/Failed 게시를 먼저 하며 이미 자동 복원한 action의 뒤늦은 실패 안내 닫기는 새 동의를 다시 폐기하지 않는다. 삭제 작업이 terminal Failed를 발행한 뒤에는 명시 feedback dismiss에서 한 번 새 읽기를 허용한다. 예외의 COMMIT 결과를 추정하지 않고 실제 저장 원형을 다시 읽는다. Missing/관찰된 sourceAbsent/Deleted에는 새 읽기를 만들지 않는다. 부모 이력 query/generation, 다른 펼침, 저장 원형과 SDK 계약은 바꾸지 않는다.

DELETING dismiss를 false로 바꾸자는 초안은 기존 명시 취소가 commit predicate/job을 폐기해야 한다는 회귀 계약을 확인한 뒤 철회했다. 기존 취소 보장과 테스트는 변경하지 않았다. 이 단계의 COMMIT/응답 불확실성에는 자동 선언 읽기를 복원하지 않으며 기존 부모 이력 결과와 사용자의 명시적인 펼침 재열람 경계를 유지한다. 안전한 확인 단계 취소/준비 실패 복원과 실행 중 불확실한 취소를 구분한다.

### 최종 제품과 native 실제 검증

최종 제품/native source는 `d1bd59dc5969ca44b94db976032260cd92d71f79`의 고정 product-v3/test-v5다. JDK17 전체 verify4단계와 debug/unsigned release/계측 APK 빌드가 통과했다. 제품 모듈57 suites/876개와 별도 build-logic1 suite/4개 각각 실패/오류/건너뜀0이며 cache/up-to-date 가능성을 유지한다. 통과 수는 성능 근거가 아니다.

| 실제 범위 | 최종 d1bd59 결과 | 확인한 경계 |
|---|---|---|
| A 제어 Compose | 첫 실행6PASS | Absent/Ready 삭제 취소 및 준비 거부/예외 뒤 single fresh read, parent 세대/query 불변, 옛 동의 거부와 새 동의 보존 |
| B UUID 소유 실제 Room | 첫 라이트3PASS/확대 다크3PASS | production repository/route의 저장/원형/handle 재개방, 부재/저장된 선언의 실제 삭제 준비와 취소 뒤 재열람. 차량/UX는 합성 |
| C 실제 Main/SDK/생산 Room 읽기 | 첫 라이트1PASS/확대 다크1PASS | 실제 삭제 준비/취소→같은 펼침 Absent fresh read→새 과정 확인/취소, DDS P-D-P와 선언0. 양쪽 최종 확인은 누르지 않음 |

C 다크 실행 전의 내비 화면 사진은 Main 성공 증거로 사용하지 않았다. 실제 C 본문의 strict Main/focused window 및 별도 capture 통과를 사용했다. 앞선 footer/host/AX/mount/문구 넘침과 B 다크 준비 timeout은 위 당시 기록의 실패 원형으로 유지하며 최신 첫 성공으로 교체하지 않는다. A의 제어 lifecycle은 실제 OS PAUSE 성공이 아니며 실제 생산 앱의 선언 저장 후 process death/재실행은 생산 DB 쓰기0 정책상 미실행이다.

### 최종 공통 M0/M1과 중간 B1 보존

최초 제품 변경 전에 정한 M0/M1/고정6f45 APK/단위/5warm30본/실패와 제외 기준을 유지했다. 원래 기준 A는4b, 중간 B1은a639 product-v2, 최종 B2는d1bd59 product-v3다. [원래140행](../../development/measurements/manual-course-completion-ui-times.csv)은 A/B1을 그대로 보존하며 [새70행](../../development/measurements/manual-course-completion-review-fixed-ui-times.csv)은 B2만 포함한다. 고유 표본은210행/여섯 보고서이며 A를 복제하거나 B1을 새 baseline으로 바꾸지 않았다. 여섯 보고서 모두 valid/completed, 각5warm/30본의 실패/누락/미실행/제외0이다.

| 지표/단계 | warm/본 | 중앙값 ms | Q1 / Q3 ms | IQR ms | 최소 / 최대 ms | 실패/누락/미실행/제외 |
|---|---:|---:|---|---:|---|---|
| M0 A 기준 | 5/30 | 279.446584 | 260.963084 / 307.00525 | 46.042166 | 241.263584 / 662.491709 | 0/0/0/0 |
| M0 B1 리뷰 전 중간 | 5/30 | 274.0576875 | 253.070208 / 290.953208 | 37.883 | 236.348875 / 356.42775 | 0/0/0/0 |
| M0 B2 리뷰 수정 최종 | 5/30 | 274.6634375 | 259.474291 / 293.450625 | 33.976334 | 225.497459 / 376.848917 | 0/0/0/0 |
| M1 A 기준 | 5/30 | 168.898625 | 167.479166 / 170.427916 | 2.94875 | 163.258917 / 186.706 | 0/0/0/0 |
| M1 B1 리뷰 전 중간 | 5/30 | 169.5672085 | 168.577459 / 171.632833 | 3.055374 | 154.113333 / 193.424583 | 0/0/0/0 |
| M1 B2 리뷰 수정 최종 | 5/30 | 169.135604 | 167.047208 / 171.377458 | 4.33025 | 163.587125 / 179.505333 | 0/0/0/0 |

| 지표/비교 | 중앙값 차이 ms | 상대 변화 |
|---|---:|---:|
| M0 A→B2 | -4.7831465 | -1.7116496582% |
| M1 A→B2 | +0.236979 | +0.1403084247% |
| M0 B1→B2 | +0.60575 | +0.2210301070% |
| M1 B1→B2 | -0.4316045 | -0.2545329983% |

통계는30본이며 Q1/Q3는 정렬한 하위15/상위15 각각의 중앙값, IQR은 Q3−Q1이다. 차이는 후−전, 상대 변화는(후−전)/전이며 백분율은 소수점10자리 반올림이다. 양수는 더 긴 시간이다. 주 결과는 A→최종 B2이고 B1→B2는 동일 기록 입력에서의 산술 관측만 뜻한다. 원래 A→B1의 차이와 실패 없는 원값도 위 당시 기록대로 보존한다.

동일 고정6f45 계측 APK와 두 하네스 source 바이트hash, originalData/full fixedSource/catalog/environment, 각 metric의 measurementContract 및 baseline 원바이트hash가 일치했다. 각 단계 제품 APK는 그 단계의 고정 원본과 일치하고 여섯 실행은 구분된다. 보고서 source SHA는 외부 선언이며 실제 APK/hash 대조가 source-to-binary 재현 빌드 증명인 것은 아니다. A→B1→B2 순서이며 A2를 위해 v6→v5 downgrade/원본 삭제/덮어쓰기를 하지 않았다. 캐시/ART/JIT/프로필/발열/전체 OS 부하와 scheduling/시간/순서 효과를 분리하지 못했고 분포가 겹친다. 성능 향상, 인과 저하, 동등성 또는 무회귀를 확정하지 않는다. 공통 M0/M1을 선언 조회/저장이나 SDK/DB/frame/GPU 성능으로 확대하지 않는다.

B2/캐시 이동 전 외부 보존 checkpoint/hash 대조 뒤 해당 실행 소유 comparison 두 보고서만 동일 바이트의 별도 소유 archive로 이름 이동했다. 이 checkpoint는 리뷰 수정 착수 전을 뜻하지 않는다. 원래 baseline/140행/보고서/product-v2/고정 test APK는 바이트 동일하게 보존했고 원래 DB나 baseline은 옮기지 않았다. B2는 별도 `review-fixed-measurements-cache.tar`로 회수하고 새70행에만 공개했다. 기존 하네스는 B2도 comparison으로 작성하며 공개 after-review-fix는 분석 레이블이다. 정확한 공개 재현 명령/두 cache 파일 이름과 보존 프로토콜은 [개발 문서](../../development/manual-course-completion.md)에 연결한다. 삭제 취소의 이전 실패 시간을0/timeout 성능 baseline으로 쓰지 않으며 신규 저장 M2는 하네스/표본 없이 미측정이다.

최종 native 뒤와 최종 B2 timing 뒤에 각각 외부 exact 대조를 마쳤다. 원래 v5 전체 table/row/field/SQLite 물리형/순서/sequence, schema6 빈 선언0과 grants/runtime/원래 font/night/IME/proxy 및 baseline 원바이트를 보존했다. 기존 준비/연습/관찰/메모/run/선언에 쓰지 않았다. 별도 UUID 소유DB/marker/sidecar 잔존0, 이번 소유 AVD/forward 종료와 연결 DDS 탭 닫기를 확인했다.

- [x] 원래5개 실제 RED→동일 source GREEN, 기존 취소 계약과 전체 feature/verify/APK 확인.
- [x] 최종 d1bd59의 A6/B 양 테마3/C 양 테마1과 원본/선언0 보존.
- [x] A/B1/B2 여섯 보고서, 원래140행과 추가70행, 분포/실패/미실행/누락/제외 및 한계 기록.
- [x] 최종 native와 timing 뒤 자료/권한/설정/원형 baseline exact 보존 및 이번 소유 자원 정리.
- [ ] 이 문서 갱신을 포함한 최신 PR 동일 head의 필수 CI/Codex와 새 구체 지적 확인.
- [ ] 정상 병합, main 동기화와 병합 작업 브랜치 정리 후 root의 전체 완료/계획 이동 판단.

원래 [P2 답글](https://github.com/HanGilLab/Android/pull/69#discussion_r4433638300)과 native resolved를 확인했다. 원래 지적 본문이 새로 갱신된 것이 아니라 수정 답글/해결 및 d1bd59의 Codex completed/no findings가 새 근거다. CI는 진행 중이었으며 문서 갱신 head는 다시 확인해야 한다. 이 독립 선언으로 APP-08 전체, 능력 인증, 보관 정책이나 생산 저장 후 프로세스 재시작 성공을 주장하지 않는다. 계획을 active로 유지하고 완료/병합을 선행 표시하지 않는다.

fresh process/Activity 조건은 각 metric 전에 앱 프로세스를 외부에서 새로 시작했다는 뜻이며 기기 cold boot를 동일하게 통제했다는 뜻이 아니다. 검증/빌드/AVD 재시작을 사이에 거쳤고 OS/서비스 부하, 장치 uptime과 캐시/컴파일 프로필 동등성은 입증하지 못했다. 같은 기록 입력/계측/가용 영역과 명시한 조건의 관측값으로만 해석한다.
