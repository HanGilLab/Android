# 선택한 과거 종료 연습의 기존 메모 수정

- 상태: 진행 중
- 요구사항: APP-06과 APP-08의 선택한 과거 종료 기록 복습. 기존 저장 메모의 읽기 전용 공백만 연결한다. 전체 실제 도로 구간 복습, 보관 정책과 APP-06/08 전체 완료는 별도다.

## 목표와 범위

과거 종료 연습에서 실제로 읽은 기존 메모를 사용자가 명시적으로 수정하고 저장하거나 취소할 수 있게 한다. 신규 작성과 메모 삭제, 위치/지도/내비/SDK/권한/스키마 변경은 하지 않는다. 현재 main의 저장 메모 reader와 Room memo DAO, 전체 source 및 version CAS를 재사용한다. 기존 최근 종료 메모의 save/delete 계약은 유지한다.

후보인 SDK 마지막 위치 수동 보관은 기존 값의 현재성/연습한 장소를 입증하지 못하고 진단 정보를 제품 흐름에 늘릴 위험이 있어 선택하지 않았다. 이 후보의 검토를 연속 위치/경로 수신이나 구현 성과로 쓰지 않는다. 지도/위치와 과거 원래 PR 리뷰 미확인은 이 독립 범위의 착수를 차단하지 않으며 완료/병합 조건은 그대로 유지한다.

## 완료 조건

- [ ] 과거 기록의 Ready인 기존 사용자 메모만 표시 당시 전체 source/메모 원형과 token으로 편집한다. 빈 메모 생성/삭제/자동 저장은 하지 않는다.
- [ ] 선택 변경/동일 ID metadata 변경/관찰된 active 또는 unknown/부모 query 실패/host 만료/P+UX 거부는 이전 입력/저장 동의를 폐기한다. 반복 같은 메모 통지는 초안을 덮지 않으며 다른 version/오류/유한 stream은 저장을 막는다.
- [ ] 별도 history update 계약은 기존 최근 종료 계약을 바꾸지 않는다. Room transaction의 실제 전체 source 일치/종료/활성 연습 없음/메모 전체 원형 및 version/쓰기 전후 gate와 취소 검사, text 원문 보존과 새 version을 검증한다.
- [ ] 기존 삭제 CAS의 version 변경 거부와 편집/삭제 UI 상호 배제, 삭제 준비 취소/실패 뒤 새 읽기 복원을 확인한다. COMMIT/응답 취소의 불확실성은 실제 재조회로 확인하고 자동 재저장하지 않는다.
- [ ] JDK17 전체 verify, 변경 경계 테스트와 실제 Compose/소유 실제 Room/실제 Main 및 SDK 읽기 기본/확대/다크를 구분해 확인한다. 실제 생산 메모는 수정하지 않고 원형/권한/설정을 보존한다.
- [ ] 동일 고정 하네스의 변경 전 A1/후 B/동일 이전 제품 A2 공통 UI 원값과 source/APK/입력/표본을 보존하고 새 편집 저장 성능과 혼동하지 않는다.
- [ ] README 기존4개와 새 원인/판단/실제 결과를 비교해 더 강한 경우만 교체하고 4개/지정6항목/펼친 실제 코드/유효 링크/기존 실제 이미지 및 공개 경계를 유지한다.
- [ ] 최신 동일 head 필수 CI/Codex/구체 지적/실제 관련 검증/보호 정책 뒤 정상 병합하고 실제 head/tree/기록과 main ff-only/브랜치 정리를 확인한다.

## 구현 단계

1. 실제 main/기획/기존 reader/최근 메모 제한과 DAO를 확인하고 새 최소 history update 계약을 합의한다.
2. root가 계측 하네스/실제 APK를 고정하고 원래 schema6 자료로 변경 전 A1 기준을 실제 측정한 뒤 생산 구현 GO를 전달한다. 그 전 생산 소스를 바꾸지 않는다.
3. DB/domain, feature reader/editor와 native 경계를 나누어 구현하고 기존 계약/선택/source/동의/취소를 검사한다.
4. 전체 검증과 native를 순차 실행하고 같은 고정 하네스로 B를 측정한 뒤 정확히 같은 A APK로 A2를 재측정한다. schema6와 사용자 원형을 바꾸지 않는다.
5. 실패/미실행/오류 원형과 실제 자료 보존, 최신 PR 검사/리뷰 및 정상 병합을 구분해 기록한다.

## 변경 전후 측정

실제 성능이나 사용 흐름을 개선할 때는 구현 전에 이 항목을 정하고 기준값부터 측정한다.
문서 배치처럼 앱 실행 성능과 무관한 변경은 측정 적용 제외 이유를 적는다.

- 병목과 관찰 근거, 개선 가설:
- 지표와 단위, 비교 방향:
- 기준 커밋과 비교 커밋:
- 동일 입력/부하, 빌드 종류와 실행 조건:
- 워밍업, 반복/표본 수와 전후 실행 순서:
- 원값 보존 위치와 재현 절차, 실패/제외 표본 기준:

| 항목 | 변경 전 | 변경 후 |
|---|---|---|
| 실제 실행한 표본 수 | 미측정 | 미측정 |
| 중앙값과 적절한 분포 지표 | 미측정 | 미측정 |
| 실패/누락/제외 표본 | 미측정 | 미측정 |

절대 차이와 개선율, 재측정 편차, 회귀 여부와 한계를 적는다.
기준값이 0이거나 측정하지 않았으면 개선율을 만들지 않는다.
테스트 통과 수와 상태/저장 행 수는 성능 개선 수치와 분리한다.
자세한 기록 기준은 [전후 측정 기준](https://github.com/HanGilLab/Android/blob/main/docs/development/measured-improvements.md)을 따른다.

## 검증 결과

| 명령 또는 시나리오 | 결과 | 근거 |
|---|---|---|
| `./scripts/verify.sh` | 미실행 | |

## 남은 문제와 인계

실패한 검사, 확인하지 못한 환경, 다음 실행자가 필요한 정보를 적는다.
완료 조건이 충족되면 상태를 완료로 바꾸고 이 파일을 `completed/`로 이동한다.

## 구현 전에 고정한 최소 계약

- 새 순수 domain `HistorySessionMemoWriter.update(expectedSource: PracticeSession, expectedMemo: SessionMemo, text: String, canCommit: () -> Boolean): SessionMemoWriteResult`만 추가한다. 기존 SessionMemoRepository save/delete/observe는 그대로다.
- expectedMemo는 null을 받지 않는다. 세션 ID가 source와 같고 실제 읽은 전체 메모/text/version과 일치해야 하며 빈 메모 생성 경로를 제공하지 않는다. 실제 memo absence/손상/version 또는 원문 변경은 성공으로 추정하지 않는다.
- Room은 기존 schema6/session_memo/DAO의 update를 사용한다. 원래 전체 source 존재/종료/동일성과 모든 active 부재 및 memo 전체 원형을 쓰기 전 같은 transaction에서 검사한다. 현재 coroutine과 canCommit을 쓰기 전후 확인하며 차단 시 rollback한다. 불투명 version은 새 UUID이며 입력 text를 trim/truncate하지 않는다.
- feature는 부모의 현재 reader/query/generation/전체 선택 source와 STARTED 수명, live P+UX 및 active 없음이 확인된 Ready receipt를 사용한다. 표시 token과 별도 editor/action nonce를 입력/취소/저장에 묶는다. 다른 메모 version이나 source가 도착하면 옛 저장 의도를 폐기한다. 부모/선택 실패를 실제 memo absence로 바꾸지 않는다.
- 저장 응답 순서/취소는 실제 memo Flow/명시 재조회로 확인한다. 초안을 동의 없이 자동 저장하지 않고 기존 read-only의 다른 펼침을 불필요하게 구독하지 않는다. 저장된 수정 시각 필드가 없으므로 만들거나 표시하지 않는다.
- 상세 입력은 P와 UX 해제 모두 확인된 경우에만 허용한다. 새 SDK/API/권한/서비스/위치 수집/스키마는 없다. 원래 생산 메모와 다른 자료에는 검증 쓰기를 하지 않는다. 실제 UI 쓰기는 UUID 소유 Room/production writer와 분리한다.

## 실제 변경 전 측정 계획

기능적 병목은 과거 메모를 실제로 읽어도 그 자리에서 수정할 수 없고 기존 writer는 최신 종료 기록만 허용하는 점이다. 느린 처리라는 증거는 없으며 성능 최적화나 사용자 시간 단축을 주장하지 않는다. 새 편집 입력/저장에는 이전 대응 동작이 없으므로 그 성능 전후 값을 만들지 않는다. 공통 조회에 새 편집 상태 연결이 영향을 줄 수 있다는 가설만 실제 UI 원값으로 관찰한다.

- 기준 제품 A1/A2: `55af9b01b766fef22360de36eb079cb359e2efa3`의 동일 고정 debug APK. 비교 B는 구현 뒤 고정하며 APK/hash와 source 증거를 별도 보존한다. A2는 schema6 무변경과 원형 보존을 확인한 뒤에만 같은 A APK로 실행한다.
- M0 일반 이력 진입: A1 실행 전에 이번 측정에서 제외했다. 기존 하네스에는 이번에 요구한 실제 Main display/single focused window/host 개입 검증이 없어 신규 편집과 직접 닿는 M1 메모 조회에 집중한다. 이전 측정의 원값과 당시 조건은 바꾸지 않으며 이번 M0는 미실행이다.
- M1: 실제 선택한 종료 기록의 기존 메모 접힘에서 펼침 클릭 직전부터 동일 source/원문/version의 Ready 가시성과 idle/재확인까지의 monotonic ns/ms. 새 수정 기능을 열거나 저장하지 않는 공통 조회이다. root가 구현 전 고정한 새 읽기 전용 하네스만 같은 APK/source 바이트로 A1/B/A2에 쓴다.
- 원래 준비1/종료13/차량관찰145/사용자메모1/terminal run15/활성0/schema6/과정선언0의 전체 값/SQLite 물리형/순서/sequence를 보존한다. M1은 실제 원래 memo를 가진 source/메모 원형을 고정하며 새 fake 데이터/원래 자료 쓰기/현재 최신으로 자동 선택을 하지 않는다.
- JDK17/debug/Pleos API34/기본 글꼴1.0/라이트, 같은 사용자/가용 앱 영역/catalog과 실제 Main 전면/SDK P+UX 허용, 같은 원본/source/하네스 APK/hash를 확인한다. 실제 식별자/위치/메모 원문/개인 환경과 manifest는 ignored 비공개에만 둔다.
- A1→B→A2. 각 metric은 fresh process/Activity에서5warm 뒤 같은 프로세스30본. 준비/사전 스크롤/실행 뒤 복귀/캡처/자료 export/cleanup은 timer 밖, click/Compose동기화/semantics를 포함한다. 실제 SDK 단독/순수 DB/frame/GPU/운전자 시간으로 확대하지 않는다.
- cold 앱 프로세스와 warm 본 표본을 구분하며 cold device boot/OS 부하/ART/JIT/프로필/thermal/타일/네트워크/DB 캐시 동등성은 보장하지 않는다. 사용자 자료와 cache를 지우지 않는다. 느린 유효 성공 표본을 제외하지 않는다. 20초 endpoint 실패/거부/host 전환/누락/제외는 개별 기록하고 timeout 대기값을 성공 latency나 0으로 바꾸지 않는다.
- 원래 baseline 완료 파일/sidecar를 덮어쓰지 않으며 새 namespace와 완료된 원형 보고서, source/APK hash를 비공개 보존한다. 공개 CSV는 익명 metric/stage/group/index/ns/ms만 둔다. 본30의 중앙값, 하위/상위15 각각 중앙 Q1/Q3와 IQR, min/max, 전후 절대 차이와 실제 방향/상대 변화, 모든 실패/누락/제외와 A2 변동을 기록한다. 0 baseline의 비율은 계산하지 않는다.
- A2가 있더라도 분포와 반복 편차, 순서/캐시/열/OS 조건을 고려하고 차이가 잡음 범위이면 향상/인과 저하/동등성/무회귀를 확정하지 않는다. JUnit/Gradle/CI 시간이나 테스트 수는 앱 성능 수치가 아니다.
- 현재 A1/M1/B/A2는 모두 미실행이다. 하네스 준비/계획과 실제 측정 완료를 구분한다.

## 현재 시작 점검

- 직전 제품 PR69와 종료 PR70은 이미 정상 병합/최종 main CI4개 성공/clean main/브랜치 정리 및 자료/권한/설정 보존까지 완료했다. 이 작업을 중복 구현하거나 재검증 완료로 대신 쓰지 않는다.
- 같은 프로젝트의 활성 채팅은 현재 채팅 하나였고 기존 lease released를 확인한 뒤 이번 실행 token의 lease를 원자적으로 기록했다. JDK17/SDK/기존 전용 AVD 설치와 원래 보존/미실행 프로세스를 확인했다.
- 최신 공식 안내7페이지와 빈 양식1개의 실제 GET/SHA는 이전 실행과 모두 같았다. 발송/서명/개인 정보/새 저장소/이력 정리/공개 범위 변경은 실행하지 않는다.
- 진행 중 원래 계획과 최신 체크리스트19/54(선택3개 제외), 열린 PR 없음, 원래 미완료 위치/내비/리뷰와 이번 독립 범위를 구분했다.

## baseline 전에 합의한 상세 경계

- 실제 memo가 없거나 표시된 전체 원형과 다르면 history update는 Conflict이며 insert하지 않는다. expectedMemo의 session ID 불일치는 SessionChanged, 실제 session 부재는 MissingSession으로 구분한다. DB 물리형/원형 손상과 SQL/독립 예외를 absence나 정상 conflict로 삼키지 않는다.
- UI는 기존 cold memo reader와 부모의 이미 수신한 active 여부만 사용한다. 같은 source의 별도 sessions 조회를 추가하지 않는다. 표시 nonce를 빌리는 옛 입력/저장/취소를 거부하고 기존 최신 메모 VM을 session ID만으로 재사용하지 않는다.
- 편집과 삭제 준비는 현재 gate 아래 상호 거부한다. 삭제 준비로 폐기한 memo 읽기의 안전한 취소/준비 실패는 한 번 새 읽기로 복원하고 실행 중 COMMIT 불확실성/Missing은 자동 재생성하지 않는다. PR69의 course 복원과 parent 세대/다른 펼침도 유지한다.
- M1의 version은 현재 UI에 표시되지 않는다. 존재하지 않는 version 태그를 새 성과처럼 추가하지 않고 실제 UI의 같은 source/출처/원문 완전 가시성과 idle 뒤 실제 repository의 전체 memo 원형/text/version을 timer 안에서 재대조한다. 이 도구/DB 검증 비용을 포함한다. pre-scroll은 memo 뒤 기존 tasks disclosure를 anchor로 하고 클릭 뒤 추가 스크롤은 하지 않는다. 원문이 완전히 보이지 않으면 글자/검사를 줄이지 않고 실패로 기록한다.
- 실제 기준 제품 debug 빌드는 JDK17에서 통과했고 생산 source는 base main과 동일하다. APK 원바이트/hash를 비공개로 보존했으며 A1 계측은 아직 미실행이다.

## 하네스 준비의 실행 기록

엄격한 Spotless 준비7회가 실패했고 이 동안 계측 APK 컴파일과 A1 본문은 미실행이었다. 처음에는 오류 행을 원본 행으로 해석해 긴 조건식의 줄바꿈/지역 변수/개별 check와 긴 상수 연결식으로 보완했으나 해결되지 않았다. 이 원인 가설은 기각했다. 실제 원인은 ktlint1.7.1 첫 자동 수정 pass가 이미 최대139자의 정규화 출력을 반환하면서 이전 중간 출력의140초과와 비수렴 진단을 남긴 점이었다. 같은 엄격한 규칙과 원래 설정으로 그 실제 반환 출력을 다시 넣으면 exit0/출력 byte 동일이었다. 그 정규화 결과만 적용한 여덟 번째 JDK17 `spotlessApply`는 종료0/BUILD SUCCESSFUL이며 동일 strict CLI 재입력도 byte 동일했다.

기대/원값/통계 재계산/실제 source/host/가시성/20초/표본 조건은 바꾸지 않았다. 모든 문자열 값과 getter/statistics 검증을 보존하고 suppress/config/성공 기본값은 추가하지 않았다. root 보조 수정 스크립트가 적용 전에 중단된 정적 준비 실패3회도 별도다. 그 중 getter 대조가 통계 재검증 누락을 발견한 수정안은 적용하지 않았다. 원래 로그와 strict 반복/해시 근거는 비공개로 보존한다. 포맷 준비/정적 검토 통과를 실제 UI/측정/제품 검증으로 세지 않는다.

A1 실행 전 최종 지표를 M1 기존 메모 펼침 하나로 고정했다. 이전 코드의 일반 이력 진입 M0를 추가하려던 namespace 두 줄은 root의 미커밋 변경만 정확히 제거했고 원래 하네스는 main 바이트와 같다. 결과가 유리해서 제외한 것이 아니라 실행 전 전면 관찰 계약과 직접 관련성을 확인한 선택이며 M0 표본은0개다. M1은 A1/B/A2 각각5warm/30본, 총105원값을 별도 보존한다.
