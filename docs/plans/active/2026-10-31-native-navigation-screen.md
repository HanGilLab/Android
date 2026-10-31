# 주차 후 기본 내비 화면 열기

- 상태: 화면 전환 범위 main 반영. 기존 활성 길안내 보존은 미검증으로 이 계획을 active에 유지한다.
- 요구사항: 체크리스트 MAP-04의 화면 전환 연결. 코스/목적지/경유지/길안내와 MAP-04 전체는 별도 미완료다.

## 목표와 범위

현재 제품 NavigationGateway는 항상 UNAVAILABLE이고 실제 기본 내비로 연결되는 행동이 없다. 실제 초기 연결이 검증된 NaviHelper3.0.5의 NavigationScreenRequest.Foreground만 사용해 홈에서 명시적으로 기본 내비 화면 전환을 요청한다. SDK 초기 연결, 호출 반환과 실제 전면 전환/길안내 성공을 구분한다. 코스 대표점을 목적지로 전송하거나 기존 안내를 취소/변경하지 않고 내비 정보/위치도 조회하지 않는다. 배경/내비 중 기록 유지는 구현하지 않으며 기존 전면 STARTED 차량 수집 계약을 유지한다.

공식 SDK와 실제 AAR ABI/현재 서비스의 정의를 확인했다. NAVI_CUSTOM_MAP은 현재 이미지에서 normal이고 화면 API에만 필요하다. 앱은 이 normal 권한만 선언하고 실제 허용을 재확인한다. NAVI_ROUTE/NAVI_ROUTE_SEARCH나 새 dangerous 권한/외부 grant는 추가하지 않는다. 기존 유효 권한/runtime 플래그는 보존하며 새 normal 선언/자동 grant는 제품 변경으로 분리한다. 다른 플랫폼에서 권한이 없으면 요청하지 않고 거부로 표시한다.

## 완료 조건

- [x] 현재 RESUMED 호스트, 표시 당시 token/요청 세대와 live P+UX 모두 유효한 명시 클릭만 초기화/화면 요청을 한다. 연결 대기 중 주행/STOP/닫기 뒤 늦은 callback과 거부 뒤 P 복귀로 전환하지 않는다.
- [x] 지원/권한/오류/시간 초과/취소/정리를 구분하고 성공을 가장하는 기본값/자동 재시도/목적지 요청을 쓰지 않는다. void 반환은 전환 요청이며 전면/길안내 성공으로 표시하지 않는다.
- [x] 홈에서 현재 행동, 목적지 미전송과 실제 SDK 요청 상태를 간단히 표시한다. 기본 라이트/확대 다크의 글자/버튼/현재 P-D-P와 실제 기본 내비 전면, 한길 복귀/재요청을 확인한다.
- [ ] JDK17 전체 verify/변경 자동/제어 및 실제 SDK 관련 실기를 확인하고 원래 DB 모든 행/형식/순서/sequence와 기존 권한/runtime/설정, 기존 내비 목적지/안내 상태를 보존한다. 실제 위치/내비 원문은 공개하지 않는다.
- [x] 변경 전후 동일한 기존 진입 비용을 실제 관찰하고 원값/분포/제외/한계를 남긴다. 새 내비 전환에는 동등한 변경 전 기능이 없어 속도 개선율을 만들지 않는다.
- [x] 기존 README4개와 기술/영향/원인/대안 근거를 비교해 강한 교체 근거가 없으면 보존한다. 6항목/실제 펼침 코드/기존 브랜드/화면을 유지한다.
- [x] 최신 PR 필수 CI/Codex/실기/미해결/정책 확인 후 정상 병합, 실제 head/tree/main ff-only/브랜치 정리와 Notion readback을 확인한다.

## 구현 단계

1. 최신 명세/AGENTS/기존 계획/소유권/PR/CI/공식 안내와 실제 SDK/권한 정의를 확인한다.
2. 제품05b8948의 고정 계측에 새 namespace만 추가해 계측 지원을 고정하고 실제 기준값을 측정한다.
3. domain 단일 요청 계약, core:vehicle의 한 요청 수명/SDK 해제/플랫폼 binding, feature:home의 명시 클릭/호스트/실제 안전 조건을 연결한다.
4. 동일 조건 비교와 자동/제어/실제 검증 뒤 사실과 한계만 README/계획/PR/Notion에 반영한다.

## 변경 전후 측정

- 관찰/가설: 앱에서 기본 내비를 여는 행동이 없다. 속도 병목은 관찰하지 않았다. 새 SDK는 클릭 전 연결을 만들지 않아야 하고 기존 이력 진입에 추가되는 비용만 관찰한다.
- 지표/단위/기준: 제품05b89481cce6088764b8635894fc922de8a75905의 기존 home_history click→같은 Ready/원문/Compose idle elapsed ns/ms. dispatch/동기화/검사를 포함하며 앱 전용 frame/GPU/SDK timer나 운전자 속도가 아니다. 새 내비 열기 자체의 전후 비교가 아니다.
- 조건: JDK17.0.21+9/debug/PleosAPI34/font1.0/라이트/원래 준비1/종료11/관찰17/메모1/같은 catalog/source/실제 SDK P+UX 허용. baseline과 comparison에 같은 frozen 메서드/measurementSeries=native-navigation-entry-v1을 사용한다. 실제 APK hash와 원래 입력을 검증한다.
- 워밍업/표본/순서: B1→F1, 단계별 fresh process/Activity 뒤 같은 프로세스5warm+30본 열기/닫기. 시작/사전 스크롤/닫기/캡처/export/자료 비교는 timer 밖. 각 원값을 보존한다.
- 제외: 원문/endpoint/입력/실제 P+UX 확인 실패와 고정20초 초과는 실패다. 느린 표본을 제외하지 않는다. 실패/누락/미실행/제외는 따로 기록하고 기준0이면 상대율을 계산하지 않는다. 정수 ns에서 ms/중앙값/Q1/Q3/IQR/min/max를 구한다. Q1/Q3는 하위15/상위15 중앙값이다.
- 한계: 기존 데이터/Tile/DB/코드 캐시를 지우지 않는다. APK 교체/ART/profile/JIT/OS/발열/순서/네트워크를 분리하지 못해 겹치는 분포로 향상/인과 저하/동등성/무회귀를 확정하지 않는다. 새 normal 화면 권한/의존성은 제품 변경으로 별도 대조하며 기존 권한/runtime은 보존한다.
- 보존: 원형/설치 hash/실제 식별자/위치/내비 화면과 원문 로그는 ignored 비공개로만 보존한다. 공개에는 익명 timing/조건만 기록한다. 기존 자료를 지우거나 새 검사 세션/메모를 만들지 않는다.

| 지표 | 변경 전 | 변경 후 |
|---|---|---|
| warm/본 표본 | 5/30 | 5/30 |
| 중앙값(ms) | 306.897833 | 272.9259795 |
| Q1/Q3(ms) | 272.925333/373.48275 | 257.689459/288.796833 |
| IQR(ms) | 100.557417 | 31.107374 |
| min/max(ms) | 209.513292/1319.774792 | 222.036291/688.840625 |
| 실패/누락/제외 | 0/0/0 | 0/0/0 |

문서 상태/배치는 앱 실행 성능과 무관해 새 측정에서 제외하고 링크/발췌/렌더/공개 콘텐츠로 검증한다. 회귀 검사 수와 저장 행 수는 정확성 근거이며 성능 수치가 아니다.

## 검증 결과

- 시작: main05b8948 clean, 같은 checkout 다른 활성 채팅 없음/원자 lease 획득. 열린 PR0/전체61개 담당 누락0, 현재 main push/필수4개 App15368 success. PR60/61 exact head Codex completed/지적 없음, 원래 PR1/8/10 head/댓글 hash 변경 없음. 원래 최종 리뷰 미확인은 별도 유지한다.
- 기존 P0: 독립 UX 조합/오류 주입은 현재 이미지 공개 제어 경로 제약으로 차단이다. 실제 LastKnown/권한 흐름과 지도 렌더는 main에서 검증됐으나 전체 MAP01/ENV06과 과거 리뷰를 이번 연결로 완료 처리하지 않는다.
- 시작 실기: 기존 AVD/준비1/종료11/관찰17/메모1의 schema4 전체 snapshot을 비공개로 보존했다. 설치된 내비 화면/경로 권한은 normal이며 권한/목적지 요청 없이 정의만 읽었다. 모든 원래 자료/권한/설정을 대조하고 소유 AVD/forward를 종료한 뒤 빌드한다.
- 자동/비교/새 제품 실기/최신 PR: 미실행. 준비나 과거 연결 프로브를 새 기능 성공으로 세지 않는다.

## README 사례 비교와 남은 문제

기존4개는 live/source, 파괴적 CAS/트랜잭션/롤백, 조회/렌더/native입력 소유권, SQLite 물리 자료형을 다룬다. 이번 요청의 실제 근거를 해당 원인/영향/대안과 비교하고 더 설득력 있는 독립 근거가 생긴 경우만 교체한다. 최신성/코드량/검사 수는 근거가 아니다. 새 공개 캡처/빈 자리표시자는 만들지 않는다.

코스/목적지/경유지/길안내 시작, 내비 중 기록, 실제 도로/위치 경로/복습 지도, 전체 MAP04/APP05는 별도 미완료다. foreground 요청이 실제 목적지나 안내 상태를 보존했는지 비공개 UI로 확인하고 위치/주행 원문은 공개하지 않는다. 이미 서비스로 전달한 요청을 앱 종료가 소급 취소한다고 보장하지 않는다.

## 변경 전 기준과 로컬 구현

제품 변경 전 계측 지원6bf134267af174344b7bc1f00c4dd99e2d47f221을 고정했다. 실제 DDS ONLINE D/P와 생산 SDK/AAOS/홈 잠금의 기본 프로브2개가 통과했다. 설치/자료 대조가 끝나기 전에 첫 전면 읽기를 시도한 로컬 순서 오류는 실패로 남기며 앱 복구 성공이나 결함으로 세지 않는다. 설치/대조/launch 완료 뒤 실제 Main 전면과 원본 화면을 확인하고 계측을 실행했다.

고정 native-navigation-entry-v1의 기준5warm/30본 표본이 모두 완료됐고 실패/누락/제외0이다. 느린 표본도 보존했다. 원형/설치hash/조건은 비공개로 남겼고 모든 원래 자료/권한/설정을 정확히 대조한 뒤 소유 AVD/forward를 종료했다. 비교는 아직 미측정이다.

명시적 홈 행동/RESUMED 호스트/표시 당시 revision/live P+UX와 SDK 연결 뒤 dispatch 직전 확인, 한 요청 Mutex/지원/권한/시간 초과/취소/정리를 구현했다. Gateway 연결 표시는 요청이 소유한 연결 수명만 나타내고 해제 뒤 DISCONNECTED다. SDK 함수 반환은 REQUESTED이며 실제 전면이나 길안내 성공이 아니다. 정리 실패나 호출 오류는 요청이 전혀 전달되지 않았다는 보장도 아니다. 이미 서비스가 받은 전환을 앱 STOP이 되돌린다고 약속하지 않는다.

Location의 기존 플랫폼 바인딩 경계를 SdkBindingContext로 이름만 일반화해 두 어댑터가 인스턴스별로 재사용한다. 닫힌 context는 플랫폼 재바인딩/늦은 callback을 차단하며 SDK 내부 Job 자체 취소와 구분한다. 실제 지원 normal NAVI_CUSTOM_MAP만 선언하고 경로/위치/정보/경로 취소 API는 호출하지 않는다. 홈에는 목적지 미전송과 내비 중 차량 기록 중단을 명확히 표시한다.

초기 Spotless의 복합 if 서식 오류와 단위 fixture의 익명 object가 인터페이스로 노출되어 read-only StateFlow로 보인 컴파일 실패를 구분해 보존했다. 첫 fixture 교정 문자열이 서식 후 소스와 맞지 않았던 재검사 컴파일 실패도 별도다. 테스트 자료를 명시적인 state 필드로 고치고 원래 서식 규칙을 유지한 뒤 core 요청16개/화면 VM10개가 실패/오류/건너뜀0으로 통과했다. 제품 runtime의 테스트 실패를 숨기거나 성공 기본값을 추가하지 않았다. 최종 native 소스/계측 APK 빌드도 통과했으나 이 시점의 실제 SDK 화면 전환과 제어 native는 아직 미실행이다.

첫 전체 검증은 어댑터 모듈 Lint의 NAVI_CUSTOM_MAP 선언 누락에서 중단됐다. 앱 manifest에만 선언한 권한을 SDK를 호출하는 core:vehicle의 production manifest로 옮겼다. 기존 연결 전용 계측 manifest에는 다른 기능 권한과 같은 명시 제거를 추가해 무권한 초기 연결 프로브의 조건을 보존한다. 직접 checkSelfPermission 확인만으로는 이 정적 모듈 선언 오류가 해소되지 않았고, 첫 문자열 교정 미적용 재검사와 직접 검사 뒤 실패도 따로 보존했다. Lint 억제/기준 완화는 하지 않는다. 최종 앱에는 같은 normal 화면 권한 하나만 merge되며 기존 runtime 권한과 경로 권한 범위를 바꾸지 않는다. 이 시점에 전체 검사 통과를 기록하지 않는다.

모듈 권한 정정 뒤 JDK17 전체4단계가 통과했다. 이어 수명 검토에서 [Android의 공식 NavBackStackEntry 수명 설명](https://developer.android.com/guide/navigation/use-graph/programmatic)에서 현재 목적지가 아닌 entry도 STARTED일 수 있음을 확인해 화면 전환 요청의 UI 소유권은 RESUMED로 더 좁혔다. ON_PAUSE에도 요청을 폐기하고 복귀하면 새 host/revision으로만 요청한다. 배경 수집 계약은 바꾸지 않았다. 제어 native에 PAUSE 전 옛 클릭 재생을 추가했고 기록 중단 문구도 화면 이탈 조건으로 맞췄다. 이 새 변경 뒤 최신 자동/실제 검증은 다시 확인한다. 이는 실제 기기 결함 재현이나 성능 향상 결과가 아니라 보수적인 요청 수명 설계다.

최신 RESUMED/문구/PAUSE 검사를 포함한 JDK17 전체 verify4단계와 앱/core:vehicle 계측 APK 빌드가 통과했다. JVM715개/48 suite의 실패/오류/건너뜀은0이며 성능 근거와 구분한다. debug/release의 NAVI_CUSTOM_MAP 하나, 경로 권한 없음과 별도 연결 프로브 APK의 기능 권한 없음도 확인했다. 첫 manifest 확인기의 넓은 문자열 FUSED_LOCATION 매칭은 기능 권한과 FUSED_LOCATION_BINDING을 혼동해 실패했고 정확한 완전한 권한명으로 대조해 구분했다. 실제 SDK/제어 native 실행은 아직 하지 않았다.

## 실제 비교 관찰

기준05b89481cce6088764b8635894fc922de8a75905와 제품4b29088cdc9c4da27a97c7b7f3d21e8686005b17 양쪽에서 같은 고정 계측6bf134267af174344b7bc1f00c4dd99e2d47f221과 입력/source/catalog/environment/contract의 일치를 확인했다. 설치 APK와 frozen 소스 hash를 대조하고 실제 DDS D/P 수신 프로브를 반복한 뒤 각5warm/30본을 완료했다. [70개 익명 ns/ms 원값](../../development/measurements/native-navigation-entry-ui-times.csv)을 보존했다. 중앙값 차이는 -33.9718535ms, 관찰 변화율은 -11.0694341397%이나 IQR이 겹친다. 순서/캐시/ART/OS/열 영향 미분리로 성능 향상/인과 저하/동등성/무회귀를 확정하지 않는다. 새로운 내비 행동의 속도 개선율이 아니다.

제어 native3개는 실패/건너뜀0으로 통과했다. 최초 실제 기본1.0/라이트 검사도1PASS지만, 전면 패키지 관찰 직후 캡처2장은 compositor 전환 전 Main 이미지를 담았다. 이 패키지 성공을 실제 내비 화면 시각 검증으로 세지 않는다. 제품은 바꾸지 않고 계측에 접근성 idle/화면 안정 대기를 추가해 실제 화면 검증을 재실행한다. 해당 대기는 모든 성능 timer 밖이며 완료한 고정 전후 계측과 원형/계측 APK를 덮어쓰지 않는다. 확대 다크는 아직 실행하지 않았다.

## 실제 화면 재확인과 자료 보존

안정 캡처 기본1.0/라이트1PASS와 확대1.3/다크1PASS, 건너뜀0이다. 두 테마 각각6개 비공개 PNG의 실제 내비 검색/2D/설정 UI와 한길 복귀/잠금/재요청, scope glyph/ellipsis/overflow/버튼 도달을 확인했다. 내비 지도 바탕 타일은 비어 있어 지도 렌더나 길안내 성공이 아니다. 두 요청에서 활성 안내 경로가 표시되지 않았고 새 경로를 시작하지 않았다. 변경 전의 활성 목적지/안내 상태는 확보하지 않았으므로 그 보존은 미검증으로 남긴다. 원래 완료 조건의 내비 내부 상태 보존까지 해결한 것으로 체크하지 않는다. 이 부분과 전체 MAP04를 미완료로 유지한 채 목적지/경로 API를 사용하지 않는 화면 전환 범위를 검토한다.

제어 native3개는 합성 차량/UX/요청 지연이며 실제 SDK 오류 주입으로 세지 않는다. 이번 실행은 새 세션/관찰/메모를 만들지 않았고 원래 준비1/종료11/관찰17/메모1의 모든 값/물리형/순서/sequence와 기존 grants/runtime/font/night/IME/proxy가 정확히 유지됐다. normal NAVI_CUSTOM_MAP 하나의 의도한 자동 허용만 별도 제품 차이로 확인했다. 원래 환경을 복원하고 소유 AVD/forward/DDS 탭만 종료했다.

README 기존1번/3번은 재현된 live/source/render/native 입력의 근거,2번은 CAS/트랜잭션/롤백,4번은 SQLite 물리 자료형을 제시한다. 이번 전환은1번/3번의 판단을 재사용하고 독립적으로 더 큰 영향/원인/대안/성능 개선을 입증하지 않았다. 모듈 권한 Lint/캡처 시차만으로 더 강한 제품 사례라고 대체하지 않는다.4개 내용/순서/지정6항목/17펼침 코드/18기존 이미지를 보존하고 실제 확인한 기능 문장과 SDK 스택만 추가했다. 회귀 수/목록/집계는 README에 넣지 않는다.

[정제한 검증과 재현 기록](../../development/native-navigation-screen.md)에 현재 사실과 미검증을 분리했다. 최신 PR/정책/검사/리뷰/병합과 Notion readback은 아직 대기다.

## 제품 PR62 반영과 남은 범위

최종 head7e1ae499b7d2ebbb43c7fb76006a9e7deefef525의 [pull_request CI41196869030](https://github.com/HanGilLab/Android/actions/runs/41196869030)/attempt1, 같은 SHA의 App15368 필수4개 success와 [Codex6542768713](https://github.com/HanGilLab/Android/pull/62#issuecomment-6542768713)의 completed/지적 없음/미해결 없음, native 스레드/정식 리뷰0을 확인했다. active strict/no bypass 정책과 충돌 없음 뒤 [PR62](https://github.com/HanGilLab/Android/pull/62)를 정상 squash 병합했다. mergea5651033fd4ef8110060a6bd5a21d32029d0eee7의 실제 head/동일 tree/parent05b8948/noreply와 main ff-only/로컬 및 원격 작업 브랜치 정리를 확인했다.

Notion의 해당 MAP04 기록을 현재 main 링크/실제 병합/검증/미검증으로 갱신하고 readback했다. 필수19/54/선택3개 제외, MAP04/APP05 미체크를 유지했다. 병합 main push41197339886의 검사와 이번 정리 문서 PR의 최신 CI/Codex는 이 시점에 진행 중/미실행이므로 제품 PR의 완료와 구분한다. 후속 실제 상태는 PR/Notion과 비공개 체크포인트에 관리한다.

README의 인증 없는 실제 PR 브랜치 렌더에서4사례/17펼침 Kotlin/기존18이미지 로드/1280px 가로 넘침 없음을 확인했고17개 발췌와 소스가 일치했다. 코드/단위/계측/권한/README/70원값은 이 문서 정리에서 바꾸지 않는다. 문서의 상태 배치는 앱 실행 성능과 무관해 새 측정 적용 제외이며 링크/공개 콘텐츠/필수 CI와 리뷰로 검증한다.

전체 계획은 완료로 옮기지 않는다.4번째 완료 조건의 앱 자료/기존 권한/설정 보존은 확인했지만 변경 전 활성 목적지/안내 상태를 확보하지 않아 그 보존은 미검증이다. 내비 내부 DB/기존 활성 안내/타일 렌더/길안내 성공을 보장하지 않는다. 이번 전면 요청 구현은 목적지/경로/정보/위치/취소 API와 분리되며 해당 미검증을 화면 전환의 전체 실패나 다른 독립 제품 구현의 일괄 차단으로 해석하지 않는다. 향후 실제 활성 안내 조건의 보존 검증은 별도로 설계하고 MAP04의 목적지/경유지/안내 및 APP05 내비 중 수집은 기존 미완료 범위를 따른다.

## 현재 main의 실제 전환 재검증 계획 (추가 실행)

기준4838db6436d745e3df5c64aadb1b800adc08e134는 PR62의 과거 실제 전환 성공과 PR64의 REQUESTED 뒤 Main 전면 유지 관찰이 공존한다. 기존 Foreground SDK 요청이 지금 실제 Main display에서 내비 전면 전환으로 이어지는지 먼저 확인한다. 기존 앱/SDK 호출을 다른 launcher로 대체하지 않으며 새 위치/경로/정보/취소 API나 권한은 추가하지 않는다. 기존 legacy 리뷰 미확인이나 전체 MAP01 미체크를 이 독립 범위의 일괄 차단으로 사용하지 않는다.

정적 근거: 공식3.0.5 sources/배포 bytecode의 화면 요청은 별도 요청 coroutine Job이 아닌 직접 호출 경로이며 AIDL FLAG_ONEWAY로 반환한다. 따라서 즉시 release가 request Job을 취소한다는 가설은 기각하고 반환을 원격 처리/실제 전면 ACK로 쓰지 않는다. binder 공백의 SDK queue/해제와 로그만 남기는 RemoteException, 앱의 동기 오류 확인 뒤 즉시 해제는 조사 지점이며 실제 누락의 원인으로 아직 확정하지 않는다. 오류 전용 listener에 화면 성공 ACK는 없다.

제품 코드 변경 전에 검증용 observer를 실제 Main RESUMED에서 확보한 유효 display의 단일 focused window로 한정한다. 초기 실제 Main, 요청 뒤 실제 내비/Main STOP, 복귀와 D/P 잠금, 원래 자료를 대조한다. rootInActiveWindow의 다른 display/빈 값과 실제 package를 구분하고 예상값 대입은 하지 않는다. interactive-windows flag는 종료 때 원래 bit만 복원한다. 이 준비는 계측 관찰 변경이며 제품 실행 성능 개선이 아니다.

성능/관찰 계획: 기준 제품은 위 main과 동일한 debug 앱, JDK17/PleosAPI34/font1.0/라이트/현재 사용자/준비1/종료13/관찰145/메모1/terminal run15/활성 없음/같은 catalog/실제 P+UX 허용이다. 기존 소유 자료까지 전 필드/물리형/순서/sequence/권한/설정을 보존한다. 각 fresh process 뒤 같은 프로세스의2warm+5본 요청을 실행한다. 스크롤/화면 준비/복귀/캡처/원본 대조는 timer 밖, 클릭 직전→같은 Main display의 실제 내비 focused package와 Main<STARTED endpoint까지 elapsedRealtimeNanos/ns(ms유도)를 기록한다. 입력/관찰/동기화 비용을 포함하며 SDK 단독 처리나 프레임/운전자 시간이 아니다.

표본은 성공/다른 package/unknown/거부/timeout을 구분하며25초는 검사 제한이다. 실제 전면에 도달하지 못한 표본의 latency는null이고 대기 시간을 성공 지연값으로 넣지 않는다. 느린 성공 표본은 제외하지 않고 모든 warm/main 순서와 원값/성공/실패/누락/제외를 보존한다. 같은 조건의 변경 후 표본이 없거나 실패로 전후 latency 비교가 성립하지 않으면 성능 향상으로 확정하지 않는다. 5본의 중앙값/Q1/Q3/IQR/min/max는 관찰 기술 통계이며 안정적인 p95/동등성/무회귀를 주장하지 않는다. 데이터/Tile/DB/ART/JIT/OS/열/네트워크 캐시를 지우지 않고 순서 영향을 한계로 남긴다. 실제 위치나 지도 원문 화면/SDK/창 로그는 ignored 비공개에만 둔다.

제품 수정은 실제 재현과 원인 증거 뒤 최소 범위로 선택한다. 같은 요청의 callback/해제 수명을 변경한다면 원래 RESUMED/P+UX/source gate와 취소/단절/옛 클릭/자동 재요청 금지를 유지한다. 실패가 플랫폼/관찰 조건에만 묶이거나 원인을 확인하지 못하면 부분 차단으로 기록하고 독립 제품 연결을 선택한다. 이번 조사로 과거 PR62 성공을 취소하거나 현재 REQUESTED만 성공으로 올리지 않는다. README 기존4개보다 강한 원인/판단/실제 결과를 확보하기 전에는 사례/순서를 보존하고 새 공개 사진을 만들지 않는다.


## 재검증의 관찰 결과와 다음 경계

제품4838db/동일9fbf4d 실행 소스는 그대로다. 계측 준비 중 존재하지 않는 모듈 Spotless 작업명2회로 Gradle이 실행 전 실패했고 저장소 루트 spotlessApply로 정정했다. 사전 검토에서 timer 전 상태 읽기, endpoint와 사후 진단 분리, 늦은 endpoint, 현재 Main identity/파괴, 실패 뒤 제품 결과 분류를 보완한 뒤 JDK17 계측 APK 빌드가 통과했다. 검토 전 APK와 이후 APK/source hash는 비공개로 각각 보존했으며 준비물을 실행 성과로 세지 않는다.

기준2warm/5본은 모두 절차를 실행했지만 실제 전환은 warm2/본3개만 성공하고 본2개는 Main 전면에 남았다. JUnit 절차 PASS와 SDK 전환 성공을 구분한다. 실패 latency는null, 원값/실제 대기/unknown/사후 REQUESTED 문구를 보존했다. [정제 원값](../../development/measurements/native-navigation-foreground-revalidation.csv)의 성공 본3개 중앙값547.059959ms/min546.525875/max589.590625와 실패2개는 변경 전 관찰이며 비교/개선율이 아니다. 누락/제외0, 관찰 unknown10개도 SDK 손실 수로 바꾸지 않는다.

실패2개를 포함한7개 시간 구간은 원격 foreground 수신까지 이어졌지만 성공5개 구간에서만 Activity START를 확인했다. requestId 직접 조인과 원격 onForeground 이후 내부 분기는 미확인이다. 성공/실패에 공통인 release/callback 순서만으로 앱 해제 수명이나 별도 SDK Job을 원인으로 선언하지 않는다. 무관한 시스템 예외는 Navi 오류에서 제외했다. 임의 delay/상시 binding/launcher fallback 제품 변경을 하지 않는다.

현재 Main display를 쓰는 별도 실제 라이트 흐름1PASS/skip0에서 SDK 내비 전면/Main STOP/복귀/옛 클릭/실제 DDS D 잠금/P 복귀 자동 요청 없음/새 요청과 schema5 domain 자료 보존을 확인했다. 확대 다크는 이번 미실행으로 과거 PR62와 분리한다. 원래 전 필드/물리형/순서/sequence/기존 권한/환경의 fresh exact 일치, 소유 AVD/forward 종료와 DDS 탭 닫기를 확인했다. 이번 전면 관찰만으로 타일/활성 안내 보존/목적지/전체 MAP04를 완료하지 않는다. 현재 자료와 설정의 fresh exact 보존 뒤 소유 AVD/forward/DDS 탭을 정리하고 전체 verify/최신 PR 검사/리뷰는 별도로 확인한다.

README의 현재4개는 실제 조작 소유권, 파괴적 원자 삭제, 비동기 지도 렌더 소유권, 재진입 상태 발행 순서의 원인과 제품 해결을 설명한다. 이번 원격 전환의 원인을 확정/수정하지 못한 관찰만으로 그 사례보다 구현 판단을 더 강하게 입증하지 못해4개/순서/6항목/실제 펼침 코드/기존18이미지를 보존한다. 실제 성능 향상이나 새 화면을 추가하지 않는다. 위치/내비 API에 독립적인 저장된 배경 실행의 이력 연결은 별도 계획/기준 측정 뒤 진행하며 이 부분 미검증을 전체 개발 대기로 확대하지 않는다.


현재 계측 수정 뒤 JDK17 전체 ./scripts/verify.sh의4개 Gradle 단계와 앱 계측 APK 빌드가 통과했다. JUnit XML773개/실패0/오류0/건너뜀0은 cached/up-to-date 포함 가능성이 있는 회귀 집계이며 전환 성공률/성능 수치가 아니다. 현재 README의4사례/각6항목/현재22개 실제 Kotlin 발췌/기존18이미지를 유지했다. 최신 PR의 CI/Codex/병합은 아직 미확인으로 별도 추적한다.


## PR66 진단 손실 리뷰 수정

[원래 P2 지적](https://github.com/HanGilLab/Android/pull/66#discussion_r4432530413)은 Main 재생성/파괴 검증이 증거 저장에서도 다시 예외를 던져 JSON/PNG를 모두 잃는 실제 제어 흐름이다. 수명 검증을 runCatching해 null과 별도 failure 필드로 보존하고 JSON 쓰기/PNG 캡처를 독립 시도하며 후속 정리 실패를 원실패에 붙인다. 실제 ActivityScenario.recreate로 옛 Main identity 검증 실패를 발생시킨 최신 native1PASS/skip0에서 MAIN_RECREATED JSON과 PNG header를 외부에서도 확인했다. 실제 SDK 전환1PASS는 a9a8607의 앞선 관련 검증이며 이번에는 진단 실패 경로를 추가 검증한 것이다. 원래7개 timing 원형과 source/APK/hash는 덮어쓰지 않았고 timer/제품 코드 변경도 없다.

수정 뒤 JDK17 전체 verify4단계/앱 계측 빌드가 다시 통과했다. 새 native 뒤 현재13종료/145관찰/메모1/terminal실행15의 전 필드/물리형/순서/sequence/기존권한/환경 exact 일치와 소유 AVD/forward 종료를 확인했다. 원래 스레드의 답글/해결과 새 head 필수 CI/Codex는 아직 별도로 확인한다.


## PR66 실제 병합과 로컬 정리

수정 head9fb290796b97770d957add8d3a9c4f7ae3ee52c2의 [CI41223164299](https://github.com/HanGilLab/Android/actions/runs/41223164299)/attempt1/App15368 필수4개 success, [Codex6546434149](https://github.com/HanGilLab/Android/pull/66#issuecomment-6546434149) completed/새 지적 없음과 원래 P2의 [한국어 해결 답글4432561990](https://github.com/HanGilLab/Android/pull/66#discussion_r4432561990)/실제 native 해결 상태를 확인했다. 스레드 해결을 PR 승인으로 바꾸지 않고 정식 리뷰의 변경 요청 없음과 active strict/no bypass 정책도 별도로 대조했다. 정상 squash merge7e5a8e1671f1c89b4dff8d6cb0b2e101eca56548의 실제 head/tree/parent/noreply와 [main push CI41223641478](https://github.com/HanGilLab/Android/actions/runs/41223641478)/attempt1/동일 SHA 필수4개 success를 확인했다.

독립 이력 구현의 소유 변경을 먼저 커밋으로 보존한 뒤 clean 상태에서 main ff-only 동기화, 정확한 병합 끝 커밋의 로컬/원격 작업 브랜치 정리와 fetch prune을 완료했다. 이후 새 이력 브랜치의 미공개 소유 커밋만 현재 main 위로 rebase했고 원본 main/PR/리뷰 이력은 재작성하지 않았다. Notion MAP04는 반복 실패2개와 원격 내부 원인/활성 안내/목적지/길안내 미검증을 유지하며 필수19/54/선택3제외를 readback했다. 계획 전체는 active다.
