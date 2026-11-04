# 저장된 준비 원본이 정상 연습 시작으로 변환되는 경계 차단

- 상태: 진행 중
- 요구사항: ENV-07/APP-02/03의 준비 복원과 시작 원본, QA-01/02의 저장 경계
- 기준 main: `c1af1f28c5c12e367d59124ff11c2f7460841eba`

## 목표와 범위

실제 홈 시작 경로는 `RoomPracticeRepository.observeSelection`의 저장 준비를 카탈로그로 해석하고 `RoomPracticeSessionRepository.start`가 같은 준비를 다시 읽는다. 두 경계가 `PreparationEntity.selection()`의 이미 변환된 Int를 사용해 REAL 버전과 Int 밖 INTEGER가 현재 버전1처럼 읽히면 정상 Ready와 새 Started 세션이 만들어질 수 있다. BLOB 식별자도 원래 SQLite 타입을 잃은 문자열로 정상화하지 않아야 한다.

제품 변경 전 실제 Room/SQLite fixture에서 관찰→카탈로그와, 이미 표시된 정상 준비를 넘기는 시작을 각각 확인한다. 전자는 손상을 Ready/빈 준비로 바꾸지 않고 오류로 전파해야 한다. 후자는 같은 시작 transaction에서 손상 원본을 오류로 거부하며 원본/기존 종료 세션/메모와 전체 자료를 보존해야 한다.

같은 SQL 관찰의 실제 타입과 원래 Long 버전을 담는 별도 projection으로 검사한다. 단순 Int 크기 검사는 Room 변환 후 overflow를 놓친다. 별도 쿼리를 관찰 뒤 실행하는 방식은 두 읽기의 snapshot을 달리할 수 있어 선택하지 않는다. raw DAO 경로와 Entity/schema는 보존하고 실제 두 제품 경계만 checked projection을 사용한다. 준비의 기존 계약은 signed Int다. 0/음수/다른 버전 등 타입이 유효한 준비는 기존 Outdated로 유지하고 새 양수 제약이나 카탈로그 일치 조건을 저장 모델에 추가하지 않는다. UI/domain/SDK/권한/마이그레이션과 열린 앱 PR83/86/87은 변경하지 않는다.

## 완료 조건

- [x] 제품 변경 전 실제 REAL/overflow 준비가 Ready/Started가 되는 RED를 확인한다.
- [x] 같은 projection의 원래 타입/범위 검사로 관찰과 시작을 모두 차단하고 원시 자료/행 순서/schema/sequence를 보존한다.
- [x] 정상 시작, Empty, 타입이 유효한 Outdated, AlreadyActive와 권한 거부/롤백의 기존 계약을 유지한다.
- [x] JDK17 focused 동일 검증과 전체 verify, 독립 코드 검토를 확인한다.
- [ ] 최신 PR head CI4/Codex 및 미해결 지적과 정책을 확인한다.
- [ ] 실제 병합/head/tree/main ff-only/병합 브랜치 정리 뒤 계획을 종료한다.

## 구현 단계

1. 독립 DB에 같은 정상 준비와 보존할 종료 원본/메모를 만들고 한 열만 지정한 타입/원값으로 바꾼다.
2. fixture의 실제 typeof/값을 먼저 확인하고 관찰/카탈로그/시작의 실제 반환과 전체 원시 자료를 기록한다. 컴파일/fixture 실패와 취소는 RED가 아니다.
3. 제품 변경 전 근거를 확인한 뒤 projection과 두 제품 읽기를 최소 수정하고 바이트 동일한 검증 소스를 다시 실행한다.
4. 정상/Outdated/Empty/권한 및 기존 준비/시작 회귀, 전체 검증과 최신 리뷰를 확인한다.

## 변경 전후 측정

이 작업은 손상된 저장 원본을 정상 준비와 세션으로 만들지 않는 의미 정확성 수정이다. 속도/화면 흐름 최적화가 아니므로 앱/SDK 성능 전후 측정을 적용하지 않는다. 같은 SDK34 Robolectric과 Room in-memory fixture, 고정 카탈로그/준비/시작 시각으로 원래 타입/값, 반환과 저장 변화만 비교한다. 각 손상은 독립 DB다. 실패/미실행/성공과 느린 실행을 숨기지 않고 검사 수나 JUnit/Gradle 시간을 성능 값으로 쓰지 않는다. projection 추가 열의 읽기 비용은 미측정이고 속도 향상/동등성/무회귀를 주장하지 않는다.

## 검증 결과

| 명령 또는 시나리오 | 결과 | 근거 |
|---|---|---|
| 같은 fixture의 기준 실행 | 실제 RED 확인 | 보강13개 중 숫자6개 실패, 기존 계약/BLOB7개 통과, 오류/건너뜀0. typeof/원값 확인 뒤 Ready/Started 및 자료 변경 assertion |
| 수정 후 focused 검증 | 실제 GREEN 확인 | 보강 기준과 바이트 동일한 검증 소스13개 통과, 실패/오류/건너뜀0. 원래 타입/값/행/schema/sequence 보존 |
| 기존 준비/시작 회귀와 전체 verify | 통과 | JDK17, 최종 포맷 소스의 품질/모듈 경계/Lint/build-logic/JVM/debug/unsigned release, cache/up-to-date 포함 가능 |
| 최신 PR CI/Codex/정책 | 미실행 | 대기/실패는 승인 아님 |
| 실제 Android 계측/SDK/화면 | 미실행, 이번 저장 경계 대상 아님 | 기존 화면과 SDK는 변경하지 않음 |

## README 사례 비교

현재 네 사례의 종료 원본/조회 소유권, 삭제 CAS/rollback, 비동기 지도/native 입력, 기록 반환 순서는 이번 좁은 projection 보강보다 넓은 상태/비동기 경계와 실제 제품 근거를 보여 준다. 이번 실제 SQLite 정확성 근거만으로 더 강한 채용 사례라고 판단하지 않는다. 정확히 네 사례와 여섯 항목, 실제 발췌/링크/화면을 보존하며 새 사례나 회귀 집계/성능 수치를 만들지 않는다.

## 남은 문제와 인계

기준 재현과 수정 후 검증 전에는 결함 해결로 확정하지 않는다. 실제 사용자 DB/기기/SDK를 사용하지 않으며 Room/Robolectric과 JVM/build를 실제 Android 계측으로 쓰지 않는다. 관련 전체 기능과 기존 앱 PR 수동 검증은 별도다. 필수19/54/선택3개 제외를 유지하고 완료 조건 전 계획을 active에 둔다.

## 기준 실행과 독립 검토 보완

제품 미변경의 첫12개는 숫자 손상6개가 실제 Ready/Started와 원시 자료 변경으로 실패했고 오류/건너뜀0이다. BLOB3개와 기존 계약3개는 이미 통과했으므로 BLOB 거부를 새 해결 성과로 쓰지 않는다. 실제 typeof/원값 확인 뒤 assertion에서 실패했으며 컴파일/fixture 실패를 RED로 세지 않았다.

독립 검토에서 유효한 활성 연습과 무관한 손상 준비가 공존할 때의 AlreadyActive 우선 계약을 추가했다. 새 projection 검사는 새 세션을 만들 경계에 적용하고 기존 활성 연습이 있으면 같은 원본과 두 번의 권한 확인을 유지한다. 무관한 준비 손상 때문에 기존 활성 연습을 덮거나 오류로 바꾸지 않는다. 추가한 control을 포함해 제품 미변경에서 다시 기준 실행하고 같은 검증 소스로 수정 후를 비교한다. 별도 원본 로그/소스/XML은 덮어쓰지 않는다.

## 보강한 기준 실행과 최소 구현

활성 세션 control을 포함한 제품 미변경13개도 숫자 손상6개가 실제 Ready/Started와 원시 자료 변경으로 실패했고, 나머지7개는 통과했다. 모든 실패는 fixture 타입/값 확인 뒤 기대 거부 assertion이며 오류/건너뜀은0이다. 첫12개와 보강13개의 원문/소스/XML을 분리해 보존했다.

그 뒤 preparation의 같은 SQL statement에서 모델/원래 타입/Long 값을 함께 읽는 `PreparationRow`를 추가했다. 세 식별자의 text 타입과 두 버전의 integer 타입/전체 signed Int 범위를 확인한 뒤 selection으로 변환한다. 기존 raw DAO/Entity/schema를 바꾸지 않고 제품의 관찰과 새 세션 시작 읽기 두 곳만 checked projection을 쓴다. 기존 활성 세션이 있으면 무관한 준비 읽기를 먼저 하지 않고 원래 AlreadyActive 및 두 권한 확인을 유지한다. 타입이 유효한 미지원 버전의 Outdated와 빈 준비의 Empty를 강제 정상화하거나 새 양수 제약으로 바꾸지 않는다. 보강한 기준과 바이트 동일한 검증 소스의 수정 후13개가 모두 통과했다. 이후 포맷한 최종 소스와 source anchor 수정의 JDK17 전체 verify도4단계 성공/종료0이다. 기존 준비3개와 시작15개를 포함한 회귀도 통과했고 독립 코드/문서 검토의 추가 지적은 없다. 최신 PR 검사/리뷰와 실제 병합은 별도로 확인한다.

README의 네 사례/본문/코드/화면은 보존하며 시작 함수의 줄 이동으로 영향을 받은 기존 strict finish 소스 링크 한 곳만 실제104~144행에 맞췄다. 링크를 원래 anchor로 되돌리면 기준 README와 바이트 동일함을 확인했다. 사례 교체나 성능/회귀 집계 추가는 없다.
