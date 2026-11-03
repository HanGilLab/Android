# 차량 관찰 저장 원본의 SQLite 숫자 강제 변환 차단

- 상태: 완료. PR84의 최신 필수 CI/Codex, 실제 병합과 main 동기화, 병합 브랜치 정리를 확인했다. 실제 Android 기기 계측과 SDK 수집은 미실행이다.
- 요구사항: APP-04의 실제 기록 출처와 저장 정확성, QA-01의 미확인/오류를 정상으로 바꾸지 않는 경계
- 기준 main: `6b85c6b5d76a21dd84c772670f42e6868607cd35`

## 목표와 범위

활성 연습 원본에 저장된 비정수 숫자나 Int 범위를 벗어난 버전이 Room 변환으로 정상 모델처럼 읽혀 새 차량 관찰의 부모가 되는지 확인한다. `RoomVehicleObservationRepository.append`는 현재 `find(...).session()`만 사용한다. 같은 원본을 다루는 기록 실행과 위치 저장 어댑터는 이미 `countValidDeletionStorage`로 실제 SQLite 타입과 버전 범위를 검사한다.

기존 실제 SQLite fixture에 REAL `courseVersion`, `briefingVersion`, `startedAtEpochMillis`, `activeSlot`과 Int 범위를 넘는 두 버전을 각각 독립 회귀로 추가한다. 실제 저장 타입과 값을 먼저 확인하고 손상 원본에 append를 시도해 실패와 전체 fixture의 값/타입/행 순서/sequence 불변을 기대한다. 정상 관찰을 가진 대상과 별도 종료 원본을 함께 남겨 새 행 차단만으로 기존 자료 보존을 대신하지 않는다. 실제 데이터나 SDK 입력은 읽지 않는다.

기준 RED를 확보한 뒤에만 기존 타입 검사를 같은 append transaction 안에서 재사용하는 최소 어댑터 수정을 진행한다. domain 계약, schema, UI, SDK 호출과 권한은 변경하지 않는다. PR83의 문구와 실제 화면 검증 보류는 이 작업의 선행 조건이 아니다.

## 완료 조건

- [x] 제품 변경 전 독립 숫자 손상 fixture가 기준 append에서 정상 저장되는 RED를 실제 확인한다.
- [x] 손상 source를 모델로 변환하기 전에 거부하고 관찰 행, 원본의 원시 타입/값과 sequence를 보존한다.
- [x] 정상, 없는/종료 세션, 비정상 관찰 시각, 취소와 동시 finish의 기존 계약을 유지한다.
- [x] JDK17 focused 회귀와 전체 verify를 확인한다.
- [x] 최신 PR head 필수 CI/Codex와 미해결 지적을 확인한다.
- [x] 실제 병합과 main 동기화, 병합 브랜치 정리 후 계획을 종료한다.

## 구현 단계

1. 제품 미변경 상태에서 실제 Room/SQLite 숫자 저장 강제 변환의 독립 회귀를 준비한다.
2. 같은 focused test class를 JDK17로 한 번 실행해 실제 실패/통과/오류/건너뜀과 원래 결과를 구분해 보존한다.
3. 확인된 결함에 한해 기존 `countValidDeletionStorage`를 append transaction에 재사용한다.
4. 같은 focused 회귀와 전체 검증, 최신 PR 리뷰를 확인하고 실제 결과와 한계를 기록한다.

## 변경 전후 측정

이번 변경은 저장된 원본의 숫자 타입과 범위를 정상으로 강제 변환하지 않는 의미 정확성 수정이다. 속도나 사용 흐름을 최적화하지 않으므로 앱/SDK 성능 전후 측정은 적용하지 않는다. 기준/수정 후 같은 fixture의 실제 저장 결과와 전체 자료 불변을 정확성 근거로 비교한다. 향후 검사가 추가하는 query 비용을 미측정 상태에서 속도 개선, 성능 동등 또는 무회귀로 주장하지 않는다. 테스트 개수와 JUnit/Gradle 시간은 성능 개선 수치를 대신하지 않는다.

재현 조건은 기준 main의 제품, 같은 실제 Room/SQLite in-memory fixture, SDK34의 Robolectric 실행과 한 개 focused test class다. 각 회귀는 독립 빈 DB에서 동일한 정상 기존 자료를 만들고 지정한 숫자 열 하나만 바꾼다. 원래 실행 결과는 비공개 근거로 보존하며 실패/미실행과 성공을 분리한다.

## 검증 결과

| 명령 또는 시나리오 | 결과 | 근거 |
|---|---|---|
| JDK17 `RoomVehicleObservationRepositoryTest` focused 기준 실행 | 실제 RED 확인 | 전체17개 중 새6개 실패, 기존11개 통과, 오류/건너뜀0. 제품 코드 미변경 |
| 같은 focused 수정 후 검증 | 실제 GREEN 확인 | 수정 전과 같은 검증 소스의 전체17개 통과, 실패/오류/건너뜀0. 손상 원본과 기존 모든 자료/sequence 불변 |
| JDK17 `./scripts/verify.sh` | 실제 성공 | 포맷한 최종 제품/검증 소스의 품질/모듈 경계/Lint/build-logic/JVM/debug/unsigned release 통과. 캐시/UP-TO-DATE 포함 |

## README 사례 비교

새 후보는 한 어댑터의 손상 원본 읽기 경계를 기존 저장 정책과 맞추는 정확성 보강이다. 현재 1번 종료 원본/읽기 소유권, 2번 확인된 삭제 CAS/rollback, 3번 지도/native 입력 소유권, 4번 기록 소유권 반환 순서는 더 넓은 비동기/상태/생명주기 경계와 실제 제품 근거를 담고 있다. 이번 좁은 저장 회귀만으로 기존 사례보다 채용 관점의 설명력이 강해졌다고 판단하지 않으므로 네 사례의 내용, 순서, 코드, 유효 링크와 실제 이미지를 보존한다. 성능 수치나 다섯 번째 사례를 추가하지 않는다.

## 남은 문제와 인계

같은 제품에서 focused class를 한 번 실제 실행했다. 새6개는 모두 실제 SQLite 타입/원값 fixture 확인을 통과한 뒤 `Appended`가 반환되고 전체 원시 자료가 달라져 기대 거부 assertion에서 실패했다. fixture 구성 실패나 컴파일 실패를 RED로 계산하지 않았다. 기존11개는 통과했고 오류/건너뜀은 없다. 원래 JUnit/XML, 실행 로그와 실행 당시 검증 소스/계획은 비공개 근거에 별도로 보존했다. 같은 transaction 안에서 존재하는 원본을 찾은 뒤 model 변환 전에 기존 SQLite 자료형과 버전 범위 검사를 적용했다. 없는 원본과 정상 종료 원본의 결과, 관찰 시각 우선순위, insert 전후 취소 확인은 유지했다. 같은 검증 소스로 실행한 수정 후17개가 모두 통과했고, 포맷한 최종 소스의 전체 verify도 통과했다. 최신 PR 검사와 리뷰, 실제 병합과 후처리는 아래 실제 종료 기록으로 확인했다. UI나 SDK 동작을 바꾸지 않고 기존 SQLite 검사를 재사용하는 범위이므로 새 실제 위치 수집, 권한, 기기와 화면 시나리오에 의존하지 않는다. 실제 source 보존/저장 결과의 회귀를 통과해야 하며 과거 PR의 검사나 다른 native 실행을 이번 성공으로 바꾸지 않는다.

이번 검증은 Room/Robolectric SDK34의 SQLite fixture와 JVM/build 검증이다. 실제 Android 기기 내 계측과 실제 SDK 수집은 수행하지 않았으며 이를 성공으로 대신 기록하지 않는다. 기존 SDK나 UI 계약을 바꾸지 않는 저장 어댑터 내부 검사이므로 새 화면 수동 시나리오는 이번 완료 조건에 추가하지 않는다.

## PR84 병합과 계획 종료

[PR84](https://github.com/HanGilLab/Android/pull/84)의 실제 head `43ef1b42eeec3ff24cfdbef88b4ff3b96d64fee6`에서 [필수 CI4개](https://github.com/HanGilLab/Android/actions/runs/41651777829)와 [동일 head Codex 완료/지적 없음](https://github.com/HanGilLab/Android/pull/84#issuecomment-6606118687), 미해결 스레드0과 원래 strict 병합 정책을 확인했다. 검증한 noreply 작성자를 명시한 정상 squash로 `0d72c5dc317f151fb6e270f458d37d7393088112`에 병합했다. PR tip과 병합 트리 일치, 단일 부모와 실제 main ff-only 동기화, 해당 원격/로컬 병합 브랜치 정리를 확인했다. 기존 작업 공간과 미병합 PR83은 보존했다.

이 후속 변경은 실제 종료 근거와 계획 위치만 정리한다. 제품/검증 소스와 README 네 사례는 PR84 병합 트리와 동일하다. 앱 실행 성능과 무관한 문서 정리이므로 새 성능 전후 측정을 적용하지 않으며 같은 제품의 전체 verify를 반복하지 않는다. 문서 링크와 공개 콘텐츠, JDK17 품질 검사 및 후속 PR 최신 CI/Codex를 확인한다. 실제 Android 기기 계측과 SDK 수집 미실행, APP-04 전체 미완료는 그대로 유지한다.

문서 종료 변경의 JDK17 `./scripts/verify.sh quality`도 실제 통과했다. 계획 이동 뒤 링크와 diff, 공개 콘텐츠를 확인했고 제품/검증 소스와 README는 병합된 제품과 동일하다.
