# 선택한 과거 종료 기록에서 다음 연습 준비

- 상태: 진행 중
- 요구사항: 출품 체크리스트 APP-07의 복습 → 과제 선택 → 다음 준비 연결
- 기준 main: `a33e74b0d19118ca1652bca804f63a0fb5e3cacb`

## 목표와 범위

현재 Home의 다음 준비, NextPracticeViewModel과 RoomNextPracticeRepository는 모두 최신 종료 기록만 허용한다. 과거 이력에는 해당 행동이 없어 이전 연습의 목표와 코스를 유지한 다음 준비로 이어지지 않는다. 버튼만 추가하면 VM과 저장소가 SourceChanged로 거부하므로 세 경계를 함께 연결한다.

기존 최신 종료 기록의 정책을 유지한 별도 선택 원본 저장 operation을 추가한다. 과거 이력의 표시된 전체 PracticeSession, 현재 generation, RESUMED 소유자와 단회 행동을 확인하고 앱 메모리의 token으로 다음 준비 화면에 전달한다. ID만으로 새 원본을 재선택하거나 process 재생성 뒤 최신 기록으로 대체하지 않는다. 현재 카탈로그와 일치하는 같은 목표/코스/버전에서 과제를 직접 고르고 준비만 저장한다. 연습을 자동 시작하지 않는다.

저장 전후 같은 transaction에서 선택 원본의 실제 rowId/자료형/전체 snapshot, 모든 미종료 행, 카탈로그/과제, 최신 P+UX 및 취소/소유권을 검사한다. 실제 위치/도로 구간, SDK/권한, 지도/내비, DB/schema, 기존 최신 경로와 과거 기록 수정/삭제는 바꾸지 않는다. APP-07 전체의 실제 구간 복습 연결 완료를 대신하지 않는다.

## 완료 조건

- [ ] 과거 종료 기록에서 다음 준비를 열고 같은 전체 원본/목표/코스/버전을 확인한 뒤 과제를 명시적으로 선택해 준비를 저장한다.
- [ ] 기존 최신 경로는 과거 원본을 계속 거부하고 새 선택 경로만 명시적으로 선택한 종료 원본을 허용한다.
- [ ] source 변경/삭제/동일 ID 교체, 손상 자료형, 미종료 행, 카탈로그 변경과 P/UX 거부, 취소에서 준비와 기존 자료를 보존한다.
- [ ] 선택 이동, STOP/PAUSE, D/P와 독립 UX 변경, 화면 이탈에서 오래된 버튼/저장/완료 token을 차단한다.
- [ ] 앱 재생성에서는 소유 원본을 유지하고 process 재생성/없는 token은 다시 이력을 선택하도록 안내하며 최신 기록으로 대체하지 않는다.
- [ ] 의미 있는 domain/Room/ViewModel 회귀와 JDK17 전체 verify, debug/unsigned release 및 필요한 계측 APK 빌드가 통과한다.
- [ ] 기본 라이트/1.3배 확대 다크에서 원본 확인/수동 선택/취소/저장/잠금/버튼 도달을 실제 확인한다.
- [ ] 최신 head 필수 CI/Codex와 지적 처리, 정책 및 실제 수동 검증 충족 뒤 정상 병합/ff-only/브랜치 정리를 확인한다.

## 구현 단계

1. 기존 최신 원본 계약, 이력의 generation/전체 source와 카탈로그 검사, 앱 token 내비게이션을 읽는다.
2. 선택 원본 전용 저장 operation과 실제 Room 회귀를 먼저 연결하며 기존 최신 경로의 거부 검사를 유지한다.
3. 전체 source와 소유 token을 전달하는 과거 이력 버튼/전용 목적지, 기존 다음 준비 화면의 명시적 선택 모드를 연결한다.
4. 소유권/잠금/저장/재생성 회귀와 전체 검사를 실행하고 실제 화면 미실행은 따로 남긴다.

## 변경 전후 측정

- 관찰 근거: 기존 Home/VM/Room은 최신 종료 원본만 요구하며 과거 이력에 다음 준비 진입이 없다. 기존 Room 회귀는 오래된 seed를 SourceChanged로 거부하도록 고정한다.
- 이번 작업은 없던 선택 원본 경로를 추가하는 제품 기능이다. 동일한 변경 전 행동이 없어 해당 신규 경로의 전후 성능 비교는 성립하지 않는다. 실행 시간 단축, 동등성이나 무회귀를 주장하지 않는다.
- 실제 성능 전후 표본/중앙값/분포/차이/개선율은 미측정이다. 저장 성공/거부와 회귀 개수는 정확성 근거로만 사용한다.
- 기존 화면/저장 비용의 성능을 이후 주장하려면 [전후 측정 기준](../../development/measured-improvements.md)에 따라 지표/단위, 동일 입력/빌드/콜드/웜/캐시, 워밍업/반복/실행 순서, 제외 기준을 먼저 고정하고 기준값부터 실측한다. 이번 테스트 시간과 Gradle/CI 시간은 성능 표본으로 쓰지 않는다.

## 검증 결과

| 명령 또는 시나리오 | 결과 | 근거 |
|---|---|---|
| 기존 최신 경로/새 선택 경로 Room 회귀 | 통과 | 기존35 baseline과 최종47, transaction/rollback 및 원본 보존 |
| 이력/다음 준비/앱 token ViewModel 회귀 | 통과 | History61/Next60/token4, 실제 process 재시작과 구분 |
| JDK17 전체 verify와 계측 APK 빌드 | 통과, 계측 실행 미실행 | 하네스 최종 보강 뒤 영향 품질/APK 재검사도 통과 |
| 기본 라이트/확대 다크 실제 화면 | 미실행 | 실제 확인 전 완료/병합하지 않음 |
| 최신 head 필수 CI/Codex와 정책 | 미실행 | 대기/실패는 승인 아님 |

## README 사례 비교

새 선택 경로는 제품의 빠진 연결이지만 기존 네 사례의 재현된 비동기 소유권, 원자 삭제/rollback, native 입력과 실제 수명주기 경합보다 강한 실제 측정/검증 근거는 아직 없다. 네 사례의 내용/순서와 실제 코드/화면을 보존하고 다섯 번째 사례나 테스트/CI 집계, 미측정 성능 수치를 추가하지 않는다. 새 근거를 확보하면 채용 관점에서 다시 비교한다.

## 남은 문제와 인계

실제 수동 검증과 최신 PR 병합 조건을 모두 충족하기 전 active로 유지한다. PR83/86의 수동 검증을 대신하지 않고 변경되지 않은 인증/기기 상태에서 반복 부팅하거나 SDK/권한/JDI를 실행하지 않는다. 원래 위치 승인 소비 원장은 보존한다. 실제 구간 복습/지도와 APP-07 전체, 체크리스트 19/54와 선택3개 제외를 별도로 관리한다.

## 구현과 실제 자동 검증

- 선택 원본 전용 saveHistorySelection을 추가했다. 기존 saveSelection은 latest-only를 유지하며, 새 operation만 선택 ID의 실제 행을 조회한다. 원시 자료형/rowId, 전체 원본, 미종료 수와 카탈로그/과제, 호출 소유권/현재 P+UX/취소를 쓰기 전후 같은 transaction에서 검사한다.
- 이력 버튼은 표시한 전체 source와 generation, 현재 RESUMED 소유자 및 단회 intent를 확인한다. 거부된 직접 D/UX 관찰은 옛 query를 즉시 퇴역시킨다. 앱 메모리 token과 전체 source를 다음 준비에 전달하며, 없는/다른 token이나 process 메모리 소실을 ID/최신 기록으로 복원하지 않는다. 같은 entry의 정상 재생성은 선택 복원 binding에 mode/token/전체 seed를 함께 확인한다.
- 독립 검토에서 화면 STARTED 중 닫기 거부 전에 VM이 영구 종료될 수 있는 경합을 수정했다. history close는 해당 RESUME receipt와 현재 앱 entry를 먼저 확인하며, ACK는 최신 close callback을 사용한다. 부모의 상태 재구성만으로 Flow가 다시 만들어지지 않게 앱 guard를 안정화하고 callback 내부는 현재 token/source/lifecycle을 읽는다.
- 새 제어 계측은 SDK를 생성하지 않는 ComponentCatalogActivity와 nonce 소유 Room을 사용한다. 각 조작/시각 검사 전 실제 host/focus/keyguard/config를 확인하고 중간 host 이탈을 실패로 남긴다. VM Job뿐 아니라 Compose가 소유하는 실제 Room collector Job도 추적/join하며, 모든 reader 종료와 active0을 확인한 뒤에만 소유 DB를 닫는다. join 실패 시 fixture를 보존한다. 이 절차는 컴파일만 확인했고 실제 실행하지 않았다.
- 변경 전 기존 RoomNextPracticeRepositoryTest 35개가 JDK17로 모두 통과했으며, 검사 전후 세 원본 파일의 hash 동일과 기존 latest의 과거 원본 거부를 확인했다. 새 API의 RED를 실행했다고 주장하지 않는다.
- 통합 후 JDK17 전체 verify의 품질/모듈 경계/Lint, build-logic/단위와 debug/unsigned release가 통과했다. 집계한 JUnit XML 1,162개는 실패/오류/건너뜀0이고 cache/up-to-date가 포함될 수 있다. 관련 Room47, NextPracticeVM60, HistoryVM61와 앱 token4도 모두 통과했다. 이는 정확성 근거이며 성능이나 실제 UI 실행 수가 아니다.
- 계측 APK 빌드는 통과했다. 이후 하네스 정리/host/Compose reader 추적 보강에 영향받는 JDK17 품질 검사와 계측 APK 빌드를 다시 통과했다. 제품/JVM 소스는 전체 verify 통과본과 동일하다. 실제 계측과 기본 라이트/확대 다크, production 내비게이션의 수동 확인은 미실행이다.

## 수동 검증 인계와 한계

- 실제 host가 정상 준비된 경우에만 HistoryNextPracticeFlowTest의 SDK-free 제어 검증을 opt-in으로 실행한다. manualHistoryNextControlled=true와 실제 설정에 맞는 expectedHistoryNextFontScale=1.0/1.3, expectedHistoryNextDark=false/true를 명시한다. 옵션 없는 건너뜀이나 APK 빌드를 실행 성공으로 계산하지 않는다.
- 제어 검증은 과거 선택, 수동 과제, 취소/오래된 저장 callback, 독립 UX/D-P 잠금과 실제 Room 준비 저장/기존 세션 보존을 확인하도록 작성했다. 실제 Main의 진입 전환 중 닫기, 설정 재생성, process 메모리 소실과 전체 동선은 별도 수동 완료 조건으로 남는다.
- 같은 ID라도 필드가 바뀐 source를 거부한다. 모든 필드가 동일한 source를 삭제/재삽입한 물리 incarnation까지 구별하는 계약으로 확대하지 않는다. 저장 행의 원시 검사는 각 쓰기 transaction에서 실제 조회한 rowId에 묶는다.
- 실제 SDK/위치 권한/JDI/기기 부팅과 새 승인 namespace 생성은0이다. 기존 소비 원장과 README 네 사례/본문/코드/실제 이미지의 바이트를 보존했다. 공식 안내/양식8개도 HTTP200 및 이전 hash 동일을 확인했다.
- 최신 head의 CI/Codex는 PR에서 추적한다. 실제 화면 검증이 없으므로 자동 검사만으로 완료/병합하지 않고 계획을 active로 유지한다. APP-07 전체와 실제 도로 구간 복습 연결, 필수19/54와 선택3개 제외는 그대로다.

## README 소스 위치 정합

- MainActivity와 SessionHistoryViewModel의 코드 추가로 기존 다섯 source range가 이동해 줄번호 anchor만 현재 코드 위치로 정정했다. 변경 전 링크가 가리키던 전체 코드 블록과 현재 대상 블록의 바이트/줄별 일치를 각각 확인했다. 다섯 anchor를 되돌린 README는 기준 main과 완전히 같다. 네 사례/순서/본문/펼친 코드/기존 이미지와 스크린샷은 보존했다.
- 줄번호 링크 정정과 검증 기록만 추가하므로 앱 실행 성능 측정 적용 대상이 아니다. 공개 콘텐츠, 상대 링크/실제 코드 range와 README 원형 보존을 검사했다. 제품/테스트/빌드 소스는 전체 자동 검사 및 계측 APK 빌드 통과본과 같다. 새 문서 head의 필수 CI/Codex는 별도로 추적하며 실제 수동 검증은 여전히 미실행이다.
