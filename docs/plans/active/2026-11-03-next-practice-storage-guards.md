# 다음 연습 준비의 실제 저장 원본 검사

- 상태: 구현과 로컬 검증 완료, 최신 PR head의 CI/Codex 확인과 정상 병합 대기. 별도 Android APK는 빌드했고 실제 Android 실행은 미실행이다.
- 요구사항: APP-07의 최근 종료 원본에 묶인 다음 준비 저장 무결성
- 기준 main: `9eb7ba194fc592670d16166fc616b0a064412230`

## 목표와 범위

최근 종료 기록의 SQLite 원시 자료형과 실제 최신 행을 확인한 뒤에만 다음 준비를 저장한다. Room의 숫자/문자 변환이 손상된 원본을 정상 domain 값으로 만들거나, 표시되지 않은 미종료 행이 있는 동안 준비를 덮어쓰는 경계를 닫는다. 최신 source와 전체 snapshot, 현재 카탈로그, 실제 P/UX commit predicate의 쓰기 전후 확인은 유지한다.

실제 최신 행의 rowId와 sessionId를 함께 묶는 내부 DAO 검사와 저장소 guard만 바꾼다. 동일 바이트를 가진 별도 TEXT/BLOB 행이 실제 최신 행의 원시 자료형 검사를 대신 통과하지 못하도록 rowId를 함께 묶는다. 일반 준비, 공개 domain API, UI/탐색, schema와 최신 기록 선택 규칙을 바꾸지 않는다. SDK/위치/권한/JDI/차량 제어와 기존 앱 자료에는 접근하지 않는다.

## 완료 조건

- [x] 제품 수정 전 실제 Room/Robolectric에서 손상 원본과 숨은 미종료 행의 실패를 재현한다.
- [x] 실제 최신 행의 원시 자료형과 미종료 수를 쓰기 전후 확인하고, 거부 시 기존 전체 typed 행/순서/sequence를 보존한다.
- [x] 정상 최신 원본 저장과 기존 source 변경/권한/취소/rollback 계약을 유지한다.
- [x] 관련 GREEN과 JDK17 전체 verify, 별도 library AndroidTest APK 빌드를 확인한다. 실제 Android 실행은 미실행과 구분한다.
- [ ] 최신 PR head CI/Codex와 미해결 지적을 확인한다. 병합과 main 동기화/브랜치 정리는 실제 결과 뒤에 기록한다.

## 구현 단계

1. 기존 최신 원본 guard, DAO와 인접 CAS 저장소의 원시 자료형 검사를 확인한다.
2. 실제 저장소는 그대로 두고 전검사/후검사 손상, TEXT/BLOB 별도 행과 미종료 marker 회귀를 먼저 실행한다.
3. 최신 행을 건너뛰는 fallback 없이 rowId에 묶인 strict 검사와 unfinishedCount를 적용한다.
4. 관련 GREEN과 전체 검증 뒤 별도의 합성 자료만 사용하는 library AndroidTest를 빌드한다.

## 변경 전후 측정

이번 범위는 손상된 물리 저장 자료를 정상으로 승인하지 않는 정확성 수정이다. 성능이나 UI 사용 흐름의 최적화가 아니며 UI/API/선택 행동은 동일하므로 속도와 사용자 성과의 전후 측정을 적용하지 않는다. 변경 전 RED와 변경 후 GREEN은 정확성 근거이며 지연/개선율을 뜻하지 않는다. 테스트 개수와 보존 행 수도 성능 수치로 사용하지 않는다.

## 검증 결과

| 명령 또는 시나리오 | 결과 | 근거 |
|---|---|---|
| 실제 Room/Robolectric 변경 전 RED | 실제 실패 재현 | 같은 35개 중 숫자 변환 8개와 숨은 미종료 행 2개가 잘못 저장돼 실패. 기존 BLOB 거부 대조 4개는 통과 |
| 관련 GREEN | 실제 성공 | 같은 35개 전부 통과, 실패/오류/건너뜀0. 전체 typed 행/물리 순서/schema/sequence와 후검사 rollback 확인 |
| JDK17 `./scripts/verify.sh` | 실제 성공 | 품질/모듈 경계/Lint/build-logic/JVM/debug/unsigned release 통과 |
| 별도 library AndroidTest APK | 실제 빌드 성공, 실행 미실행 | 기존 library test 패키지만 대상으로 UUID in-memory 합성 자료 사용. 7개 테스트는 앱/SDK/기존 자료를 읽거나 권한을 요청하지 않음 |

## README 사례 비교

이번 좁은 원시 저장 guard는 기존 1번의 종료 원본 전체 snapshot 확인과 2번의 쓰기 전후 CAS/rollback 경계에 겹친다. 기존 네 사례는 비동기 응답, 생명주기와 실제 native 입력의 더 넓은 근거를 갖고 있다. 3번 지도 입력 소유권과 4번 기록 소유권 반환 순서의 사례를 유지하고, 새 성능이나 실제 Android 실행 근거도 없으므로 네 사례, 코드/링크/이미지를 그대로 보존한다.

## 남은 문제와 인계

선택한 이전 종료 기록에서 다음 연습으로 가는 UI 연결과 APP-07/APP-08 전체는 이 작업의 완료 범위가 아니다. 실제 Android SQLite 검증은 별도 합성 library 테스트로만 인계하고, 실행하지 않았으면 성공으로 기록하지 않는다. 정상 병합은 이 작업에서 미실행이다.


## 실제 실패 구분과 최종 검토

최초 변경 전 회귀는 35개 중 14개가 실패했다. 그중 BLOB 문자열/ID 4개를 IllegalStateException으로만 예상한 검증 가정이 틀렸고 실제 cursor는 SQLiteException으로 이미 거부했다. 이를 기존 거부 대조로 정정한 뒤 제품 guard를 그대로 둔 재실행에서 실제 잘못 저장되는 10개 실패만 남았다. 두 원래 실행 결과를 보존했고 BLOB 거부를 새 수정의 성과로 세지 않았다. rowId에 묶인 DAO의 별도 대조는 정상 TEXT 행의 검사값1과 다른 최신 BLOB 행의 검사값0을 확인한다.

후검사 회귀는 두 번째 commit predicate 안에서 실제 target 준비가 쓰인 것을 먼저 확인한 다음 원본을 변형한다. 원본 guard가 거부한 뒤 기존 준비, 원본과 모든 합성 table의 원시 자료형/값/순서, schema와 sqlite_sequence가 원래 상태로 돌아오는지 비교했다. 소유한 합성 transaction 안의 변형이며 별도 SQLite writer가 transaction 직렬화를 우회했다는 주장이 아니다.

독립 소스 검토에서 구체적 수정 지적이 없었다. 하위 모듈 formatter task 이름이 지원되지 않은 최초 시도는 실패로 남겼고, 저장소의 정상 spotlessApply로 포맷한 뒤 관련 GREEN과 전체 verify를 통과했다. 별도 library test APK도 이후 순차 빌드했다. 제품 UI/선택 행동과 SDK 연결을 바꾸지 않아 새 화면 수동 시나리오는 없으며, 실제 Android SQLite 실행은 선택적 추가 근거로 남겨 미실행을 성공으로 대신하지 않는다.
