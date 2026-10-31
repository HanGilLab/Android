# 선택한 종료 이력의 수동 과정 마무리 선언

- 상태: 계획 작성, 제품 변경 전 기준 측정 대기. 제품, DB와 UI 구현은 아직 시작하지 않았다.
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

- [ ] root가 기준 제품과 고정 M0/M1 하네스, 실제 설치 APK, 원본 자료/source/catalog/environment manifest를 고정하고 제품 변경 전에 기준 측정을 완료한다. 그 전에는 production 코드를 수정하지 않는다.
- [ ] 선택한 종료 기록의 전체 원문과 사용자 직접 선언/시연 출처를 확인해 명시적으로 저장하고 같은 기록에서 재생성/재실행 뒤 복원한다.
- [ ] 동일 session의 중복 선언은 최초 source/version/시각을 그대로 반환하며 새 행이나 변경 시각을 만들지 않는다.
- [ ] source 없음, 활성 source, 전체 metadata 변경, 다른 활성 session, live P+UX 거부, 호스트 폐기, 취소와 잘못된 시각은 각각 거부/실패로 구분한다. 실패나 COMMIT 뒤 응답 취소를 새 성공/부재로 바꾸지 않는다.
- [ ] 조회 null, 손상/오류, 독립 취소와 유한 stream 완료를 구분하고 terminal 실패는 명시 재시도로만 다시 조회한다.
- [ ] 옛 펼침/저장/재시도와 source 동일 ID의 다른 metadata가 새 선택 또는 P 복귀의 권한을 빌리지 못한다. 활성 존재 unknown은 저장 허용으로 쓰지 않는다.
- [ ] v5 원래 모든 table/row/field/SQLite 자료형/순서/sequence를 보존하고 새 선언 테이블이 빈 상태로 시작하는 migration을 별도로 확인한다. v1/v2/v3/v4/v5에서 v6까지 명시적 경로를 등록하며 destructive fallback을 사용하지 않는다.
- [ ] 선언 전후/null 포함 삭제 확인의 exact 비교, source 삭제 CASCADE와 삭제 뒤 늦은 선언의 no-resurrection을 확인한다. 다른 session과 준비/관찰/메모/run은 보존한다.
- [ ] 관련 domain/Room/JVM 경계, 전체 JDK17 verify와 APK, 제어 Compose, 실제 기본 1.0 라이트와 확대 1.3 다크의 저장/재열람/P-D-P/취소를 검증한다. 합성 source/저장소와 실제 SDK/원래 자료 검증을 구분한다.
- [ ] 같은 입력과 고정 하네스의 M0/M1 비교를 완료하고 새 저장 M2는 변경 후 특성으로만 기록한다. 원값, 분포와 실패/미실행/누락/제외를 보존한다.
- [ ] 원래 DB 전체, 권한/runtime/설정과 baseline 파일을 exact 대조하고 실행이 소유한 검증 자료 및 프로세스/forward/AVD만 정리한다.
- [ ] README 대표 사례 4개와 새 실제 근거를 비교해 근거가 있는 경우만 교체하며 기존 실제 코드/이미지와 개인정보 경계를 유지한다.
- [ ] 최신 PR의 동일 head 필수 CI/Codex, 구체적 지적 처리와 관련 실제 검증을 확인한다. 정상 병합, main 동기화/브랜치 정리와 전체 완료 판정은 root가 별도로 수행한다.

## 구현 단계

1. 원래 기획, AGENTS, 현재 코드와 이번 최소 정책을 읽고 domain/DB/UI의 source와 결과 계약을 합의한다. 지금은 이 계획만 작성한다.
2. root가 새 측정 계열 지원을 기준 실행 전에만 준비하고 하네스 SHA/바이트 hash/APK와 입력을 고정한다. 기존 timer와 endpoint를 바꾸지 않고 새 namespace로 기준 M0/M1을 수집한다.
3. root의 실제 기준 완료/보존 확인과 GO 뒤 domain/DB v6 및 삭제 CAS, feature의 읽기/명시 저장과 DI를 작은 단위로 구현한다. 다른 담당자의 파일을 덮어쓰지 않는다.
4. domain/Room/JVM과 실제 Compose 경계, 전체 검증 및 관련 실기에서 실패를 원형으로 보존하고 원인에 맞게 수정한다.
5. 최종 제품의 같은 M0/M1과 after-only M2를 실행하고 분포/정확성/보존 결과를 기록한다. 중복 결과를 신규 저장 속도로 섞지 않는다.
6. README 사례 비교, 최종 문서와 최신 PR 리뷰를 마친 뒤 root가 전체 완료/계획 이동/정상 병합과 정리를 판정한다.

## 변경 전후 측정

현재 관찰한 공백은 사용자가 과정 마무리 의사를 저장하고 다시 볼 수 없다는 점이다. 느린 DB/렌더 병목을 관찰한 것이 아니며 가속을 가설로 삼지 않는다. 기존 이력 진입과 과제 요약에 추가되는 공통 비용을 전후로 관찰한다. 새 선언 저장에는 대응하는 이전 기능이 없다.

- 기준 제품: `4b1ba6d9c437767c258e9d33843af6c600f24b01`. 비교 제품과 실제 설치 product/test APK hash는 최종 검증 시 확정하며 지금 미정이다.
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

비교의 의도한 차이는 v6 신규 선언 테이블과 새 UI/쓰기 계약이다. M0/M1 비교 전 새 테이블의 원본 대응 선언은 0개여야 하며 v5 원래 table/row/field/물리형/순서/sequence는 baseline과 같아야 한다. 새로운 migration 테이블의 정상 부재를 다른 원래 자료 소실과 섞지 않는다. M2/실기 소유 선언은 두 공통 비교가 끝난 뒤만 만들고 외부 manifest에 정확히 구분한다.

통계는 본 표본 30개의 중앙값, Q1/Q3, IQR과 min/max다. Q1/Q3는 정렬한 하위 15개/상위 15개의 중앙값으로 고정한다. 절대 차이와 상대 변화의 방향/공식을 명시하고 기준값이 0이면 상대율을 만들지 않는다. 분포 중첩과 순차/캐시/열/OS 효과를 분리하지 못하면 향상, 인과 저하, 동등성이나 무회귀를 확정하지 않는다. 테스트 수, 상태 수와 보존 행 수는 정확성의 보조 근거이며 성능 수치가 아니다.

| 항목 | 변경 전 | 변경 후 |
|---|---|---|
| 실제 실행한 M0 5warm/30본 | 미실행 | 미실행 |
| 실제 실행한 M1 5warm/30본 | 미실행 | 미실행 |
| 새 저장 M2 | 대응 행동 없음 | 미측정 |
| 중앙값/Q1/Q3/IQR/min/max | 미측정 | 미측정 |
| 실패/누락/미실행/제외 | 미실행 | 미실행 |

[전후 측정 기준](../../development/measured-improvements.md)에 따라 실행한 원값만 기록한다.

## 검증 결과

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

## 남은 문제와 인계

- 지금 허용된 편집은 이 계획 파일뿐이다. 기준 측정이 끝나기 전 domain/DB/UI production 변경과 새 migration/schema 생성을 시작하지 않는다.
- root는 AVD/환경 보존/측정/Gradle/Git/PR을 소유하고 별도 담당은 feature UI/VM/reader와 해당 검증을 소유한다. 이 계획 담당은 baseline GO 뒤 domain/DB/삭제 CAS 및 관련 JVM/Room 검증을 맡을 예정이다.
- 모델/저장소/결과 명칭과 양수 시각/벽시계 역행 인정 정책은 root와 UI 담당에게 합의 전달했다. v6 전체 snapshot column/물리형, nullable 삭제 receipt의 구현과 M2 반복 fixture는 baseline GO 뒤 구체화한다.
- COMMIT 이후 응답 전달 취소는 실제 저장이 없었다는 뜻이 아니다. 현재 source의 idempotent 재조회와 이미 저장된 원형을 사용해 불확실성을 해소해야 한다. 저장 전 취소와 source 변경은 쓰기를 막아야 한다.
- 전체 과정 상태, 회차, 선언 독립 취소/재개, 보관 기간과 암호화 정책, 실제 위치/도로, Navigation 요청 원인 미확정과 기존 legacy 리뷰는 이 부분 구현의 완료로 대체하지 않는다.
- 완료 조건과 최신 PR 검증을 충족한 뒤 root가 완료 표시와 completed 이동, 정상 병합 및 main/브랜치 정리를 판정한다.
