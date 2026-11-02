# 명시적으로 켠 전면 연습의 SDK 위치 표본 기록

- 상태: PR75 병합 main과 제품 통합, JDK17 전체 검사와 통합 소스296c584의 CI/Codex 완료. 전용 기기 시스템 서비스 불안정으로 통합 실기와 실제 위치 두 창은 미완료, 원래 단일 승인 미소비
- 요구사항: APP-04의 위치 입력/수집 상태 연결, 주차 후 기록 열람. 위치/경로 전체와 APP-05의 배경 위치 수집은 완료 범위가 아니다.
- 기준 main: dd4897880b24776c1599bb18096abc5bcc0a2219

## 목표와 범위

기존 LastKnown 한 번 조회와 차량 상태 FGS는 그대로 둔다. 업데이트 전용 domain 계약/실제 SDK 어댑터와 별도 세션 자식 저장소를 연결하고 사용자가 현재 한 활성 연습에서 P/UX 허용을 확인해 명시적으로 켠 경우에만 전면 앱의 위치 표본을 기록한다. 시작/상세 열람은 P와 UX 해제 모두 필요하고, 시작 뒤 D의 수동 SDK 수신은 상세 조작과 별개다. 실제 D 수신은 별도 수동 검증으로 확인한다.

소유권은 전체 활성 session snapshot과 현재 Main STARTED host에 묶는다. 앱 STOP, source 변경/종료, 권한 상실, SDK/저장 오류와 수집 상한에서 listener/sink를 닫고 자동 재개하지 않는다. 재개는 현재 P/UX와 새 사용자 요청을 다시 확인한다. FGS/내비 배경으로 위치 수집을 확장하지 않는다. 위치 표본을 실제 이동 경로/현재 위치로 추정하지 않고 SDK 원시 시각/confidence의 미정의 의미를 보존한다. 누락/Empty/거부/미지원/오류는0좌표나 정상 상태가 아니다.

원래 schema6 전체 행/값/타입/순서/sequence를 보존하는 additive migration을 사용한다. 새 위치 기록/실행 자료도 종료 기록 삭제의 연결 자료와 stale 확인 경계에 포함한다. 위치 원문, 실제 source 식별자/경로/장치/로그를 공개하지 않는다. 실제 SDK 저장 검증은 별도 명시 동의와 test-owned 자료로만 진행하고 앞선 두 창 동의는 재사용하지 않는다.

## 완료 조건

- [ ] 현재 검증된 callback API/기존 계약/실제 기술 의존성을 확인하고 전면 전용 source와 중단 정책을 구현한다.
- [ ] SDK 업데이트와 LastKnown 출처를 구분하고 typed 저장/마이그레이션/종료 뒤 append 거부/삭제 연결 자료 CAS를 확인한다.
- [ ] 명시 시작/중단과 주차 후 열람을 실제 제품 UI에 연결하고 P/UX 미확인, D 상세 잠금과 source 폐기를 유지한다.
- [ ] JDK17 전체 verify와 관련 제어/DB/native 검증, 기본/확대/다크의 실제 UI 흐름을 확인한다.
- [ ] 별도 실제 SDK 허용 검증의 새 동의/미실행/실패/수신/원형 복구를 구분하고 실제 검증이 없으면 그 조건은 완료로 세지 않는다.
- [ ] 같은 최신 PR head 필수 CI/Codex/미해결 지적/정책과 실제 수동 근거를 확인한 뒤 정상 병합/동기화/브랜치 정리를 수행한다.
- [ ] README 기존4개와 새 제품 문제 해결 근거를 비교하고 더 강한 근거가 없으면 내용/순서/여섯 항목/실제 발췌/이미지를 유지한다.

## 구현 단계

1. 작업 전 최신 Notion/AGENTS/진행 계획/Git/PR/실제 환경을 읽고 새 lease를 확보한다.
2. 코드 변경 전에 아래 고정 계측의 기준값을 실제 측정한다. SDK/기존 자료/AVD는 재사용하고 다운로드/초기화/새 권한을 하지 않는다.
3. domain/SDK, 저장/삭제, 앱/UI를 작은 계약으로 나눠 연결하고 실제 실패와 검증 근거를 보존한다.
4. 같은 고정 계측의 변경 후 표본과 전체 검증/수동 흐름을 확인한 뒤 최신 PR 리뷰/CI를 처리한다.

## 변경 전후 측정

새 위치 수집/저장은 기능적으로 동일한 변경 전 동작이 없으며 LastKnown 조회와 register API의 시간 비교를 하지 않는다. 새 callback 개수/저장 행/가상 시각/테스트 통과 수로 성능을 주장하지 않는다. 이번 새 기능 자체의 전후 속도 개선은 완료 목표가 아니다.

- 관찰/가설: 새 수집/이력 연결의 초기화와 추가 조회가 기존 저장 메모 열람을 지연시킬 수 있다. 성능 최적화 가설이 아니라 기존 행동 영향의 사전 관찰이다.
- 지표: 기존 종료 이력 선택 후 메모 클릭 직전부터 같은 원래 source/version/text와 실제 TextLayout 공개를 확인할 때까지 elapsedRealtimeNanos(ns/ms). SDK/Compose/상태/원형 재조회 비용을 포함한 disclosure wall-time이며 DB/SDK/앱 단독 처리나 운전자 시간은 아니다.
- 기준: 위 main의 동일 제품 소스와 새 측정 준비만 들어간 commit/APK를 비공개로 고정한다. 신규 namespace/class의 단일 frozen 계측 APK/소스 hash를 전후 공유하며 이전 M1 원값/소스/캐시는 덮어쓰지 않는다.
- 입력/조건: 동일 전용 Pleos API34/JDK17/debug/font1.0/라이트, 실제 Main의 유효 display/focus/RESUMED/P+UX, 원래 종료13/관찰145/메모1/terminal실행15/선언0/활성0, 같은 nonlatest 메모/source/version/catalog/environment. 위치 권한은 원래 미허용으로 유지하고 새 수집은 켜지 않는다.
- 반복/순서: A 기준5warm+30본 이후 구현, B 비교5warm+30본. 원래 자료/캐시는 삭제하지 않는다. schema6에서 additive 새 schema로 변경되면 원래 table의 모든 논리/물리 자료와 설정을 유지하고 새 빈 table만 의도된 차이로 별도 확인한다. 이전 schema로 downgrade/reinstall하지 않으므로 A2 반전 비교는 하지 않는다.
- 한계: A/B 순서, OS/ART/JIT/열/네트워크/캐시와 schema 차이는 완전 통제하지 못한다. 분포가 겹치거나 동조건 전후 비교가 성립하지 않으면 성능 향상/인과 저하/동등성/무회귀로 확정하지 않는다. 새 SDK 수집/저장 동작의 전후 수치로 사용하지 않는다.
- 보존/제외: 각 원래 warm/main 순서, 실패/누락/not-run/제외와 정제 ns/ms 원값/소스/APK/환경 증거를 비공개로 보존한다. 빠르거나 느린 성공 표본은 제외하지 않는다. 실제 endpoint 미확인은null/실패이며 대기를 성공 지연값으로 바꾸지 않는다.

실제 실행 뒤 표본 수, 중앙값/Q1/Q3/IQR/min/max, 절대 차이/관찰 변화율과 실패/누락/제외를 채운다. A의 실제 값은 아래에 분리했다. B 미실행 중에는 변화율과 성능 결론을 만들지 않는다.

## 검증 결과

| 명령 또는 시나리오 | 현재 결과 | 근거 |
|---|---|---|
| 현재 main/PR73 | 과거 완료와 이번 재확인 | 새 SDK callback18/20개는 이전 두 창의 기술 근거이며 이번 제품 저장 검증이 아님 |
| 기존 PR8/10 | 원래 최종 리뷰 미확인 유지 | 실제 원래 head와 상태를 보존. 독립 구현의 전역 차단으로 쓰지 않음 |
| A 기준 측정 | 실제 완료 | 아래 동일 frozen 계측 기준. B는 아직 미실행 |
| domain/Room/SDK/feature 단위, debug 앱 조립 | 실제 성공 | JDK17 관련 전체 단위 target와 assembleDebug 통과. 전체 verify와 실제 UI는 별도 대기 |
| 전체 verify 첫 실행 | 실패 | 작성 중 native 검증 파일의 포맷 검사에서 중단. 해당 실패 로그를 보존 |
| 최종 후보 전체 verify | 실제 성공 | JDK17 quality/모듈 경계/lint/build-logic/JVM/debug/unsigned release 통과. JVM 68 suite 1070개와 build-logic 4개 실패/오류/건너뜀0. 실제 SDK/수동 성공이나 성능 수치와 구분 |
| 최종 화면 검증 APK | 실제 빌드와 기본/확대 UI 및 미허용 실행 성공 | 초기 준비/전면 내비 실패와 정상 복귀 뒤 최종 성공을 아래에 구분. 새 실제 SDK 저장/D/Main STOP은 동의 전 미실행 |

## 남은 문제와 인계

새 모델/저장 상한과 구현 API는 코드 변경 전에 계약으로 고정한다. SDK 시각/신뢰도/현재성/연속성/배경 수집과 전체 APP04/05/MAP01은 별도 미완료다. 원래 FUSED_LOCATION 권한은 미허용이고 이전 두 창 동의는 consumed/restored다. 새 제품 실제 저장/Driving 검증은 구체적 코드/PR/미허용/원형 검증을 준비한 뒤 새 범위의 동의를 확인한다. 답변이 없으면 승인으로 추정하지 않는다. 필수19/54와 선택3개 제외를 유지한다.


## 실제 변경 전 A 기준과 준비 실패 구분

제품 소스 dd489788과 같은 baseline+harness commit ab336ee에서 동일 frozen 새 계측 source b9cdc721999866d5d6c93f2c6d4f8b1ab23a075179a349e73e96a5b10bd5f5f8/APK를 고정했다. 첫 외부 실행은 빈 추가table 인자가 remote shell에서 사라져 instrumentation 자체가 시작되지 않았다. 원문 외부 실패와 표본0/미실행을 보존하고 shlex 인자 인코딩만 정정했다. 제품/계측 APK/타이머를 바꾸지 않았다.

실제 A는 warm5/main30 전부 성공, 실패/누락/제외0으로 완료했다. 중앙값 930.950834ms, Q1 914.823667/Q3 941.282292ms, IQR 26.458625ms, 최솟값 863.816001/최댓값 1051.131208ms이다. quartile은 정렬한 각 절반의 중앙값이다. 이는 기존 메모 공개 wall-time 기준이며 새 위치 수집/저장 속도나 성능 개선 수치가 아니다. B는 이 A 기준을 기록할 당시 미실행이었고, 실제 B 결과는 아래 A/B 관찰에 분리했다.

모든 원래 row/typed값/순서/sequence/권한/runtime/font/night/IME/proxy와 source/version/catalog/environment가 정확히 보존됐고 FUSED_LOCATION도 실제 미허용이었다. SDK P/UX와 실제 Main/focus를 확인했다. 기준을 고정한 뒤에만 제품 구현 GO를 기록했고 소유 AVD/forward/DDS 탭을 종료했다. 실제 구현은 업데이트 domain/SDK, schema7 자식 자료와 삭제CAS, 전면controller, 앱/UI의 소유 경계를 나눠 진행한다.

## 구현 검증에서 구분한 실패와 수정

- 최초 Room migration 검증 소스의 닫기 helper가 RoomDatabase의 실제 타입과 맞지 않아 컴파일하지 못했다. 명시적인 close/finally helper로 정정했고 실제 migration 단위 검증이 통과했다. 이는 앱 데이터 손상이나 SDK 성능의 근거가 아니다.
- 최초 앱 연결에서 상태 표시와 화면 진입에 같은 리소스 이름을 사용해 resource merge가 실패했다. 이름을 분리해 상태 문구와 탐색 문구를 각각 보존한 뒤 debug 앱 빌드를 통과했다.
- SDK 취소 정리 재현은 실제로 실패했다. 정리 호출만 NonCancellable로 보호하면 디스패처 복귀의 prompt cancellation이 이후 watcher join/실패 결과 전달을 건너뛸 수 있었다. SDK 정리, watcher join, 명시적 cleanup failure callback과 내부 첫 원인 보존까지 하나의 NonCancellable 완료 경계에 두었다. 취소 예외 객체의 동일성이나 suppressed 역전파에 의존하지 않고 controller가 명시적 결과를 받아 정상 중지를 FAILED로 바꾸도록 연결했다. SDK callback Error의 원래 출처는 정리 오류와 구분한다. 수정 뒤 같은 SDK 정리 재현과 controller의 결과 전달 검증이 통과했다. 로컬 register/unregister 반환을 원격 공급자의 승인으로 해석하지 않는다.
- domain/저장/어댑터/feature 관련 단위와 debug 앱 조립은 실제 통과했다. 새로운 native/제어 UI 검증은 작성 및 별도 실행 단계이며 통과로 기록하지 않는다. 전체 verify 첫 실행은 아직 작성 중인 새 native 파일의 포맷 검사에서 중단했으며, 해당 파일 freeze 뒤 전체 명령을 다시 실행한다.

## README 사례 비교와 유지

새 취소 정리 경계는 coroutine 취소와 SDK 자원 정리 사이의 오류 전달 판단을 보여 준다. 그러나 기존 4번의 소유권 반환/화면 발행 경합과 기술 근거가 겹치고, 이번 실제 SDK 저장/주행/복구 검증은 아직 미실행이다. 기존 1번의 실제 차량 잠금, 2번의 전체 연결 자료 삭제 CAS, 3번의 비동기 지도/native 소유권, 4번의 기록 소유권 경합보다 강한 재현/실측 근거가 확보됐다고 선언하지 않는다. 따라서 사례의 내용과 순서는 유지하고, 2번의 실제 SessionDeletionTarget에 추가한 위치 자식 snapshot 한 필드와 바뀐 유효 소스 줄 링크만 동기화했다. 네 사례, 여섯 항목, 펼친 실제 코드와 기존 18개 이미지 참조를 확인했다. 성능 전후 미측정 상태를 회귀 통과 개수로 대신하지 않는다.


## 종료 응답 재확인 경계의 실제 RED/GREEN

읽기 검토에서 begin의 OPEN snapshot과 end의 불변 소유권을 같은 조건으로 비교하는 문제를 찾았다. end가 저장된 직후 응답을 받지 못해 durable terminal snapshot을 다시 읽으면, 같은 run/process/full source/start tuple임에도 expected.status가 OPEN이 아니라는 이유로 OwnerChanged가 됐다. 현재 최초 종료 marker를 재확인하지 못하고 정리 미확인 상태에 남을 수 있는 경계다.

동일 Room 회귀를 먼저 추가했고 수정 전 실제 실행에서 해당 재확인 사례가 실패했다. end에 한해 양쪽의 불변 tuple을 비교해 이미 끝난 같은 소유자에는 저장된 첫 marker 그대로 AlreadyEnded를 반환했다. append는 여전히 OPEN 기대를 요구한다. 같은 Room target을 다시 실행해 통과했고, 다른 process/source/start tuple 거부와 전체 snapshot/sequence/첫 marker 불변도 확인했다. 이는 종료 소유권 정확성 근거이며 속도 개선이나 성능 수치가 아니다.


## 동일 활성 원본 알림의 실제 RED/GREEN 분리

무관한 종료 기록의 삭제로 session 목록이 다시 전달돼도 현재 활성 full source가 동일할 수 있다. 기존 제어 VM은 그 알림까지 sourceRevision 변경으로 처리해 SDK 등록 응답을 기다리는 명시 시작을 취소했다. 현재 활성/다른 종료 행에서 동일 활성 행만 남는 실제 Flow 입력으로 회귀를 먼저 추가했고 수정 전 해당 guard 유지 기대가 실패했다. 유효 Source.Active의 전체 PracticeSession이 같을 때에만 accepted-start의 sourceRevision/issued를 유지하고, 화면 revision은 계속 바꿔 옛 Ready 클릭을 차단하도록 분리했다. source 교체/종료/오류, 새 reader/host, 권한/주차/UX 상실은 기존 폐기를 유지한다. 수정 뒤 최종 전체 verify에서 해당 18개 제어 VM 회귀를 모두 통과했다. 수정 전 단일 실패와 같은 기대/입력을 유지했다. 이는 시작 의도와 UI receipt의 정확성 근거다.

README의 발췌/소스 링크와 계획 갱신은 앱 실행 코드를 바꾸지 않으므로 별도 성능 측정 적용 대상이 아니다. 네 사례의 여섯 항목 순서, 실제 코드 일치/유효 줄 링크, 기존 이미지와 공개 민감 정보 검사를 수행한다. 제품 코드의 기존 행동 영향 측정은 앞선 A/B 계약을 그대로 따른다.


## 실제 A/B 기존 행동 영향 관찰

A는 `ab336ee`(제품 소스는 기준 main과 동일), B는 `4a6db65`의 debug APK다. 같은 frozen source `b9cdc721999866d5d6c93f2c6d4f8b1ab23a075179a349e73e96a5b10bd5f5f8`와 계측 APK를 두 번 사용했고 실제 설치 제품/계측 APK hash를 각각 고정 파일과 대조했다. JDK17, Pleos API34, font1.0/라이트, 실제 Main RESUMED/동일 display/focus/P+UX, 원래 nonlatest 메모/전체 source/version/catalog/environment와 미허용 권한 조건을 일치시켰다. Gradle 빌드는 AVD 수동 시나리오와 겹치지 않았다.

| 단계 | warm/본 표본 | 중앙값(ms) | Q1/Q3(ms) | IQR(ms) | 최솟값/최댓값(ms) | 실패/누락/미실행/제외 |
|---|---|---|---|---|---|---|
| A | 5/30 | 930.950834 | 914.823667 / 941.282292 | 26.458625 | 863.816001 / 1051.131208 | 0/0/0/0 |
| B | 5/30 | 913.8595835 | 904.772042 / 932.826459 | 28.054417 | 861.702084 / 983.843542 | 0/0/0/0 |

정렬한 각 절반의 중앙값을 quartile로 사용했다. B−A 중앙값의 관찰 차이는 −17.0912505ms, 관찰 변화율은 −1.835891851%다. IQR가 겹치고 차이가 각 단계 IQR보다 작다. A/B 실행 순서와 OS/ART/JIT/열/캐시, additive schema 차이를 완전 통제하지 못했고 A2/downgrade도 하지 않았다. 따라서 개선, 인과적 저하, 동등성 또는 무회귀를 확정하지 않는다. 새 위치 수집/저장 성능이나 운전자 성과를 뜻하지 않는다. README에는 성능 성과로 추가하지 않는다.

[정제한 실제 실행 순서의 70개 원값](../../development/measurements/2026-11-01-foreground-location-entry.csv)은 warm과 본 표본을 분리한 ns/ms 값만 담는다. 각 단계의 성공 표본은 빠르거나 느린 값을 포함해 모두 유지했다. 원래 source/메모, 개인 환경, 원문 로그는 포함하지 않는다. 재현은 계획에 명시한 원래 입력을 보존한 상태에서 같은 frozen `ForegroundLocationEntryReadMeasureTest#measureStoredMemoOpeningAndPreserveOriginalData`를 baseline/comparison stage로 각각 호출하고, schema6/7와 새 빈 table 두 개의 의도된 차이를 검증한다. 선언한 commit/source hash만 믿지 않고 설치 APK hash와 입력/환경 equality를 확인한다. 다른 입력을 만들거나 이전 schema를 재설치해 같은 실측이라고 하지 않는다.

## 실제 수동 검증의 현재 범위

- 실제 Android library-owned Room의 재열기/복구/연결 삭제와 commit 거부/좌표 없는 거부 metadata 검증을 통과했다. 제어 입력이며 실제 SDK 위치 수신으로 계산하지 않는다.
- 실제 Main/Hilt SDK 권한 미허용에서 단일 시작 요청은 거부됐고 생성 run/update flow/local register 0을 확인했다. 소유 controller/store 정리, 원래 domain/typed SQLite/앱 읽기 가능 설정과 automation flags 보존도 통과했다. 전 앱의 모든 SDK 초기화가 0이라는 주장은 아니다.
- 기본 font1.0/라이트에서 실제 제품 Route, 제어 SDK/차량 입력, 소유 Room의 명시 시작, D 수동 표본/상세 잠금, 중지, 종료 이력, PAUSE/STOP/원본 교체와 옛 클릭 차단을 통과했다. 실제 SDK의 D 수신 및 실제 Main STOP은 별도 미실행이다. 9개 anchor의 최소24sp TextLayout, glyph/줄 범위, ellipsis/overflow/viewport와 실제 화면을 비공개로 확인했다. SDK 위치/공개 사진이 아닌 제어 fixture의 실제 Compose UI다.
- 확대1.3/다크 첫 실행은 세 사례 중 초기 실제 host/focus/display 확인 두 곳에서 실패했다. 원래 설정/DB/권한은 독립 정리와 전체 snapshot 대조로 복원됐다. 냉 시작 조건을 분리한 같은 APK 재시도도 첫 host receipt 재확인에서 실패하고 나머지 두 사례는 통과했다. 두 실패는 fixture/source/제어 UI 생성 이전이며 실제 어떤 lifecycle/focus/display 항이 바뀌었는지는 아직 미확인이다. 기존 라이트 cache를 다시 export한 archive는 다크 화면 근거로 세지 않는다. guard를 약화하지 않고 추가 진단을 준비한다. 확대 다크 UI와 해당 mode의 실제 미허용 검증은 완료로 세지 않는다.
- 앞선 실제 메타데이터 관찰 두 창은 사용/복구 완료 상태다. 새 실제 SDK→소유 Room/D/Main STOP의 두 관찰 창은 새 동의 전 미실행이며 실제 허용/위치 저장/이동으로 대신 기록하지 않는다.


## 확대 다크의 실제 전면 확인과 최종 미허용 검증

추가 진단의 원래 main-thread guard 값에서 검사 Activity가 CREATED/focus=false였고, 실제 focused root는 Pleos 기본 DrivingView였다. 기본 내비의 전면/겹침도 실제 플랫폼 창과 화면에서 확인했다. 이후의 동일 Activity/구성 관찰만으로 이전 실패를 통과로 바꾸지 않았다. 설정 전환 뒤 정상 뒤로 가기와 명시적 Main 열기로 기본 내비에서 한길에 돌아온 실제 화면/P를 확인한 뒤 같은 검증을 다시 시작했다. 앱이나 기본 내비 데이터를 삭제하거나 해당 앱을 강제 종료하지 않았다.

검사 host 준비는 전체 조건/expected 구성/known-own focus를 같은 Activity에서 연속 확인한 뒤에만 host를 발급하도록 보강했다. 본문/최종 관찰의 known foreign/unknown/상태 이탈 즉시 실패는 유지했다. 이 보강만으로 최초 다크 실패가 성공한 것은 아니며, 실제 전면 DrivingView 확인과 정상 복귀가 필요했다. Compose Activity cache 추정은 현재 캐시 sources/bytecode와 대조해 부정했고 원인으로 기록하지 않는다.

최종 동일 제품 APK와 최종 제어 source로 기본1.0/라이트 세 흐름과 실제 미허용, 전면 복귀를 확인한 확대1.3/다크 세 흐름과 실제 미허용을 모두 통과했다. 새 1.3/true UUID의 9개 실제 UI metadata/PNG를 라이트 두 그룹과 구분했고, 모두 최소24sp/실제 resource 및 Compose fontScale/전체 TextLayout/ellipsis와 glyph/viewport 조건을 통과했다. 실제 제어 화면도 비공개로 읽기와 버튼 도달을 확인했다. 실제 SDK의 D 수신을 뜻하지 않는다.

모든 실행 뒤 원래 font/night/proxy/IME, 전체 원본 행/값/SQLite 타입/순서/sequence와 실제 runtime 권한을 expected schema7 snapshot과 정확히 대조했다. 설치 제품/최종 계측 APK hash도 고정 파일과 대조했고 소유 AVD만 종료했다. 제품 바이너리는 실측 B의 `4a6db65`와 동일하다. 과거 실패/미실행, 성공한 라이트와 새 다크 자료, 실제 SDK 위치 표본이 아닌 제어 자료를 계속 분리한다.

실제 SDK 위치 표본을 새 테스트 전용 로컬 DB에서 비교한 뒤 소유 DB만 삭제하는 검증, 실제 D와 Main STOP은 여전히 새 FUSED_LOCATION 동의 전 미실행이다. 현재 권한은 미허용이며 이전 두 관찰 창 동의는 재사용하지 않았다. PR #74는 미병합이고 최신 head CI/Codex와 별도 SDK 수동 검증의 전체 완료를 기다린다. 필수19/54, 선택3개 제외를 유지한다.


최종 준비 경계와 실제 수동 근거를 포함한 상태에서 JDK17 `./scripts/verify.sh` 전체를 다시 통과했다. cache/up-to-date 재사용은 별도이며 JUnit/CI/Gradle 소요 시간을 앱 성능으로 해석하지 않는다. 실제 미허용 권한과 데이터가 복구된 소유 AVD 및 임시 DDS 탭을 정리했다. 새 위치 수신 동의 요청은 이 최종 head의 필수 CI/Codex가 실제 완료된 뒤 구체적 두 창/테스트 소유 저장 범위로 진행한다.


## 새 명시 동의와 독립 전용 기기의 검증 재개

사용자가 이번 최대20초씩2회의 기기 내 임시 저장 검증을 명시 승인했다. 동의 원문과 불변 receipt는 ignored 로컬 기록에만 보존한다. 현재 실제 권한 허용, 관찰 창과 임시 좌표 저장은0이며 승인 사실을 실행 성공으로 계산하지 않는다. 앞선 PR73의 메타데이터 두 창 동의는 재사용하지 않는다.

원래 보호 AVD와 별도 PR75 기기는 모두 실제 종료와 자료/근거 보존을 확인한 뒤, 설치된 공식 Pleos API34 이미지로 새 UUID 전용 기기만 만든다. 다른 기기의 userdata/DB/snapshot을 복사하지 않는다. 정상 초기 설정/font1.0/라이트/앱 설치/실제 미허용 상태를 확인한 뒤 새 빈 schema7와 위치 table0의 grant 전 기준을 고정한다. 기존 보호 기기의 개인 설정은 변경하지 않는다.

새 기기/owner/lease/source/APK/native/Dex를 고정하고, Android에서 기기 내 필터와 전체 실제 권한 flags/settings 조회를 grant 전에 검증한다. snapshot이 Main을 정지시키면 정상 재실행 후 실제 Main/P/UX를 새로 확인하며 이전 준비 값을 재사용하지 않는다. 권한 허용 뒤에는 성공/실패와 무관하게 원문 DB/WAL/SHM, cache tar, 원문 로그/stack, 좌표나 화면을 호스트로 반출하지 않는다. 실제 비교는 기기 안에서만 수행하고 허용된 종류/개수/상태/보존 metadata만 전달한다.

첫 창은 P에서 명시 시작/중지, 둘째 창은 P 시작 후 실제 D 수신과 상세 잠금, 실제 Main STOP/복귀/자동 재시작 없음을 확인한다. 전체 최대2창/각20초와 SDK/Room 소유권, local cleanup/원격 ACK 미확정 구분을 유지한다. 실패/미지원/Empty/미실행은 별도로 보존하고 실제 Sample이나 정상0좌표로 대체하지 않는다. listener/저장 종료 확인 뒤 임시 소유 파일을 삭제하고 실제 revoke/원래 flags/설정 및 깨끗한 기준을 대조한다. 실제 Hilt SDK+production controller+UUID Room 검증은 실제 제품 시작 버튼으로 production DB에 저장했다는 근거와 구분한다.

기존 단일사용 검증 도구가 원래 보호 기기를 가리키고 사후 원문 DB를 반출할 수 있어 그대로 실행하지 않았다. 새 private 도구는 별도 새 기기와 기기 내 허용 목록 필터, 실패/timeout의 recovery-only와 canonical import를 준비한다. 순수 모의 검사/compile-only를 실제 Android/SDK 성공으로 쓰지 않는다. 동일 승인 receipt의 authoritative ledger는 복사 위치와 무관한 하나의 ignored 경로에 고정하고, 임의 복사본/새 device/run으로 동의 예산을 초기화하지 않는다. 실제 구현/필터/복구 runtime은 grant 전 확인한다.

PR75의 다크 실제 기기 준비 실패와 최종 병합 미완료는 이번 독립 검증의 기술적 의존성이 아니다. 현재 PR74 dd9895b의 코드/검증 계약을 그대로 사용하고 미병합 PR75를 먼저 통합하지 않는다. 이후 실제 병합 순서가 정해지면 변경된 main과의 실제 소스/수동 경계는 별도로 검증한다. 새 앱 기능/성능 최적화 코드 변경은 없으며 추가 검증 도구/문서 준비는 앱 실행 성능 계측 적용 제외다. 기존 실제 A/B 관찰과 한계를 보존하고 새 수집 성능으로 전용하지 않는다. README4개는 실제 더 강한 SDK 결과가 확보되기 전까지 현재 내용/순서를 유지한다.


## 새 전용 기기 사전 검증과 인증 잠금 인계

이번 승인 범위는 새 전용 기기의 FUSED_LOCATION만 임시 허용하는 최대20초씩2회이며, 원래 동의는 아직 미소비다. 실제 허용0회, positive 계측 호출0회, 위치 관찰 창0회이고 위치 수집과 임시 좌표 저장은 시작하지 않았다. 전용 기기를 바꾸거나 준비 문서를 다시 고정해도 원래 동의 하나의 단일사용 예산을 초기화하지 않는다.

공식 설치 이미지로 만든 새 기기에서 제품과 계측 APK bytes/hash를 대조했고, 실제 Android의 권한 미허용 native 검증과 device-side metadata SELFTEST를 통과했다. grant 전 새 빈 schema7 DB, 위치 table0, writer0 및 원래 전체 grants/flags/settings/runtime을 실제로 고정했다. 기기 내 typed baseline, canonical hash와 길이, 실제 설치 APK, metadata Dex 및 private 도구 source가 일치하는 최종 준비도 확인했다. 기존 보호 기기와 PR75 기기는 종료 상태와 자료를 보존했고 이번 위치 권한 범위에 포함하지 않았다.

사전 도구에서 실제 CRLF, 설정 key의 slash와 Android/Python JSON slash 표현, permission 정렬 방식 및 Android14의 Dex 파일 접근 모드에 따른 실패를 각각 구분하고 순수 검증과 실제 Android metadata readback으로 확인했다. native 본문 종료 뒤 finally에서 다시 발행하는 실제 P복귀 신호는 본문5단계와 별도 정리1단계로 구분했다. 본문 P복귀/완료를 정리 신호가 대신할 수 없고 중복/순서 오류와 JUnit 미완료는 성공이 아니다. private 도구 검사225개 통과는 도구 정확성 근거이며 실제 위치 Sample이나 앱 성능 성과가 아니다. 앱 제품 코드는 이번 준비 과정에서 변경하지 않았다.

SDK 상태 모델 읽기는 실제 P/UX 허용을 한 차례 확인했지만, 후속 제한 시간 초과와 미확인 상태는 성공으로 치환하지 않았다. 최근 모델은 DISCONNECTED, 기어 UNKNOWN과 주차/UX UNKNOWN이었다. 실제 브라우저 DDS ONLINE/P 표시만으로 앱 SDK 수신을 대신하지 않았다. 전체 window dump에는 LAST ANR의 과거 창과 현재 창이 함께 있어 전역 Main 수를 현재 전면 수로 단정할 수 없다는 점도 확인했다.

현재 새 기기의 정책 metadata는 잠금 표시, secure=true와 input restriction을 보고했다. 자동 승인 검토가 일반 잠금 닫기 요청을 인증 경계 우회 가능성 때문에 거부했으며, 명령은 실행 전 차단됐다. PIN/인증정보 입력, 우회 또는 보안 설정 변경은 하지 않았다. 사용자에게 해당 전용 기기를 직접 확인하고 잠금을 해제해 달라고 요청했으며 기존 위치 임시 저장 동의는 유지한다. SDK가 미지원이라고 판정하지 않았고 위치 검증은 미실행 상태다.

인계 직전에 실제 FUSED_LOCATION 거부, 모든 원래 grants/flags/settings/runtime 일치, canonical hash/길이 일치, WAL/SHM 없음, 소유 임시 저장소 없음과 앱/계측 writer0을 다시 확인했다. 좌표/원문 DB/로그/화면을 반출하지 않았다. 기기는 사용자 확인을 위해 보존했고 기존 기기 데이터도 유지했다. 잠금 해제 후 정상 Main의 실제 전면/RESUMED/P/UX와 최신 CI/리뷰를 다시 확인한 뒤 원래 예산 내에서만 이어간다.

최신 PR74 dd9895b와 PR75 91e9025의 필수 CI4개 및 각 head의 지적 없는 Codex 완료를 재확인했다. PR74 실제 SDK 두 창과 PR75 최신 다크 수동 경계는 각각 미완료이므로 둘 다 미병합으로 유지한다. PR8/10의 원래 최종 리뷰를 대신 완료했다고 기록하지 않는다. 필수 체크리스트는19/54이며 선택3개는 분모에서 제외했다.

README는 대표 사례 정확4개, 문제/원인/해결/결과/대체안/회고/코드 순서와 실제 발췌를 유지했다. 이번 새 근거는 private 검증 도구의 호환성과 인증 잠금 진단이며 제품의 새로운 수집/저장 결과를 확보한 사례가 아니다. 기존4개의 최신 상태 차단, Room 삭제 원자성, 지도 비동기 수명, 기록 소유권 반환 사례보다 앱의 비동기/데이터 경계 구현 역량을 더 설득력 있게 입증한다고 볼 근거가 없어 교체하지 않았다. 새 성능 원값이나 개선율도 만들지 않았다. 사전 도구와 인계 문서 수정은 앱 실행 성능을 바꾸지 않아 별도 앱 성능 전후 측정 적용 제외이며, 기존 실제 A/B와 한계를 보존한다.


## 사용자 잠금 해제 확인 뒤 사전 상태 읽기 재개

사용자의 직접 잠금 해제 답변을 받았고, 당시 실행 기기가 없어 동일 전용 AVD의 보존된 userdata와 설정으로 정상 재시작했다. 실제 고정 사용자, 부팅과 새 PID, keyguard/input restriction 해제, FUSED_LOCATION 거부 및 원래 canonical/sidecar 일치를 확인했다. 처음 부팅 확인은 계획의 사용자 임시값을 실제 사용자로 비교해 실패했으며, 기존 실제 bound user를 두 번 읽어 같음을 별도 확인했다. 이를 부팅/앱 실패나 새 데이터로 치환하지 않았다.

기기 내 metadata SELFTEST/전체 기준 일치와 실제 Main/P/UX seed 읽기는 성공했다. 다음 grant 전 상태 재확인에서는 platform/APK/미허용 확인 뒤 기존 JDI 모델 읽기가 host6초를 두 번 넘겼다. 승인 ledger와 intent는 아직 없고 권한 허용/positive/위치 창은0이다.

SDK 모델의 field-only 관찰 도구만 Java 전체 상한5→8초, host6→12초로 조정한다. 동일 타입/필드/enum 검사, VM release 확인과 최대2회 metadata 시도, 실제 읽기 뒤 admission10초 조건은 유지한다. 실제 위치 리스너의 최대20초씩2회, 원래 단일사용 승인과 기기 내 임시 저장 범위를 바꾸지 않는다. 메타데이터의 실패한2회는 실제 위치 창이나 성능 표본이 아니며 앱/SDK 성능 향상을 주장하지 않는다. 제품과 native APK의 구현 변경도 없다.


### 재개 뒤 실제 사전 확인 결과와 추가 진단 승인 경계

비공개 field-only 확인 도구의 Java 전체 상한8초/host12초 후보를 JDK17로 컴파일했고 기존 타입/필드/enum/VM release 검사와 단일 읽기 최대2회 경계를 보존했다. 실제 후속 grant 전 확인은 정상 완료되지 않았고 원래 동의 ledger와 host/device intent가 생기기 전에 중단됐다. 컴파일과 합성 검사는 실제 기기 성공이 아니며 위치 권한 허용, positive 호출, 위치 관찰 창과 좌표 저장은 모두0이다.

어느 field-only 단계에서 멈추는지 고정 코드로 구분하는 추가 단일 JDI 연결 진단은 자동 승인 검토가 최대2회 metadata 시도와 추가 재시도 금지 조건 초과를 이유로 실행 전에 거부했다. 실제 연결/VM 정지는 수행하지 않았고 사용자에게 최대12초의 단일 진단만 별도 요청했다. 기존 최대20초씩2회 위치 임시 저장 동의는 미소비로 보존한다. 추가 승인이 오기 전 JDI 연결을 재시도하지 않는다.

인계용 소유 앱 종료도 프로세스 상태를 바꾸고 단순 읽기로 대체할 수 있다는 이유로 자동 승인 검토에서 실행 전에 거부됐다. 종료를 우회하지 않고 읽기 전용 확인을 수행했다. 현재 FUSED_LOCATION 거부와 원래 전체 grants/flags/settings/runtime 일치, DB 파일 hash/길이 일치, 테스트 프로세스 없음, 소유 위치 저장소 없음과 device/host intent 및 원래 동의 ledger 없음을 확인했다. 앱은 실행 중이고 DB sidecar가 있어 이번 시점의 닫힌 canonical 전체 재검증과 writer0은 확인하지 않았다. 이전 시점의 성공으로 대체하지 않는다. 프로세스, AVD와 기존 자료를 유지한다.

제품/native 소스와 APK는 바꾸지 않았고 앱 성능 변화에 대한 전후 측정 적용 제외다. 이번 도구 실패와 인계 근거는 기존 README 네 사례의 제품 영향, 비동기/상태/데이터 경계 구현 근거보다 강한 새 제품 결과가 아니어서 네 사례의 내용과 순서를 보존한다. 필수19/54와 선택3개 제외, PR74/75 미병합을 유지하며 구현 완료와 필수 수동 검증 완료를 구분한다.


### 별도 허용된 추가 상태 진단 1회 결과

사용자가 일반 메시지로 추가 상태 진단1회만 명시 허용했다. 위치 임시 저장의 원래 동의와 별도로 최대12초, 단일 host Java child, 신규 SDK client/좌표 조회/저장/권한 변경 없음과 재시도 금지를 기록했다. 단일 사용 intent를 원자적으로 먼저 저장한 뒤 실제 JDI child를1회만 실행했다. 동일 소스에 고정 단계 코드 출력만 추가했고 JDK17 경고 없이 컴파일했다. 기존 Java 전체 읽기 예산8초, 타입/필드/enum 검사와 finally의 resume/dispose 흐름은 바꾸지 않았다.

실제 고정 단계는 attach, vehicle/restrictions instance 확보, VM suspend 반환 뒤 resume/dispose 반환 순서였다. 첫 VehicleState 필드 완료 표시는 없고 READ_BUDGET_EXCEEDED로 종료했다. 따라서 정지가 끝난 직후의 예산 검사와 첫 상태 조회 내부 중 정확한 원격 호출 지점을 더 좁혔다고 주장하지 않는다. host 경과8.39초는 단일 실패 도구 진단의 실행 시간이며 앱/SDK 성능 개선 전후값이 아니다. child 종료와 소유 forward 제거를 확인했다. 허용한 추가 진단1/1회는 소비했고 같은 승인으로 추가 JDI/재시도를 수행하지 않는다.

진단 후 별도 JDI 없이 읽기 전용으로 동일 Main PID/UID/start identity, 실제 전면/RESUMED/같은 display를 확인했다. 위치 권한은 거부 상태이고 원래 전체 permissions/flags/settings/runtime은 일치했다. DB 파일 hash/길이도 같고 소유 위치 저장소는 없었다. 앱은 실행 중이며 sidecar가 있어 닫힌 canonical 전체 검증은 이번에도 미실행으로 남긴다. 앱/기기 종료, 설정/권한 변경, 신규 SDK client나 위치 등록은 하지 않았다. 실제 위치 권한 허용/positive/위치 관찰 창/좌표 저장은0이고 원래 두 창 동의 ledger와 실행 intent는 없다. 현재 SDK P/UX 모델은 확정하지 못했으므로 실제 위치 두 창과 PR74 병합을 완료로 기록하지 않는다.

노션과 PR74의 오래된 사용자 잠금 해제 대기 설명을 위 결과와 남은 조건으로 정정했다. 제품/native 소스와 APK는 변경하지 않았으며 이 진단만을 위해 전체 Gradle verify를 재실행하지 않았다. 기존 JDK17 전체 검증/최신 head의 CI4개와 Codex 결과는 과거 검증 시점 그대로 보존하고 이번 진단 성공으로 바꾸지 않는다. 필수19/54, 선택3개 제외와 README 네 사례를 유지한다. 새 근거는 private 검증 도구의 안전한 실패 위치와 해제 확인이며 기존 네 제품 사례보다 기술 기여/제품 해결/재현 결과가 강하다는 근거가 없어 교체하지 않았다.


## PR74 실제 검증 재개를 위한 native 권한 대기 경계

소비한 외부 JDI 진단은 재시도하지 않는다. 기존 positive native 시나리오에 명시적인 host 권한 대기 인자를 추가한다. 같은 실제 Main/Hilt 차량 상태와 UX 모델이 현재 P/제한 해제를 확인하고 같은 display의 전면 상태를 확인한 뒤에만 고정 단계 코드를 보낸다. 이 단계는 위치 SDK 등록, 소유 Room 생성과 관찰 창 시작 전이다. host는 원래 단일 승인 ledger와 intent를 먼저 예약하고 같은 실행에서 FUSED_LOCATION만 한 번 허용한다. native는 대기 중 주차/UX/전면 상태 이탈을 즉시 실패로 처리하며, 권한이 실제로 허용된 뒤에도 같은 조건을 다시 확인한다. 대기는 최대30초이며 자동 권한 허용, 새로운 JDI, 공급자 주입과 새 위치 SDK client를 추가하지 않는다.

기존 인자를 쓰는 미허용 시나리오와 이미 허용된 실행 경로는 유지한다. 첫 P 시작/중지와 둘째 P 시작/D 수신/상세 잠금/실제 Main STOP/복귀 후 재시작 방지, 각 최대20초의 원래 두 창 예산과 소유 저장소 경계는 바꾸지 않는다. 좌표는 기기 안에서만 비교하고 원문 DB/cache/log/화면을 반출하지 않는다. 실패와 무관하게 host가 권한 회수, flags/설정/기준 DB 보존과 닫힌 소유 DB 삭제를 확인한다. 원래 승인 ledger를 새 기기나 실행으로 초기화하지 않는다.

변경 전 근거는 외부 field-only 읽기의 실패와 실제 위치 실행0회다. 이 변경은 앱 제품 코드나 SDK 성능을 바꾸지 않는 수동 계측의 준비 경계이므로 앱 성능 전후 측정 적용 대상에서 제외한다. 기존 A/B 원값과 미확정 결론을 유지하며 도구 시간이나 테스트 개수를 성능으로 쓰지 않는다. 완료는 JDK17 전체 verify, native APK 빌드, 최신 head의 CI4개/Codex, 실제 두 창 및 전체 복구가 모두 끝난 뒤에만 기록한다. 현재는 구현 전 계획이며 실제 수신 성공이 아니다.


### native 사전 확인 후보의 현재 검증과 남은 연결

최신 정리된 origin/main을 일반 merge했고, JDK17.0.21 spotlessApply, 전체 ./scripts/verify.sh와 :app:assembleDebugAndroidTest를 모두 exit0으로 완료했다. 실제 권한 미허용 상태에서 현재 Main/P/UX를 확인한 뒤 host 허용을 기다리는 선택적 native 인자와 SDK 확인 시각/현재 process/display의 고정 숫자 metadata를 추가했다. 메타데이터는 위치 값이 아니며 공개 로그/문서로 반출하지 않는다. native 자체의 권한 허용/회수, 위치 주입이나 원문 좌표 출력은 없다.

기존 단일 승인 ledger를 native 호출 전에 예약하고 새 실제 native proof 이후에만 같은 host 허용1회를 실행하는 비공개 연결 후보는 아직 실행을 노출하지 않은 초안이다. 순수 parser에서 stale/future/user 불일치/형식과 추가 coordinate 필드 거부를 확인했으나 전체 transport/finally 복구와 실제 기기 성공을 대신하지 않는다. 이 연결과 같은 후보의 실제 두 창은 미완료다. 원래 동의, ledger와0회 소비 상태를 보존하며 JDI를 재시도하지 않는다.

현재 화면 제어 도구는 Mac 잠금을 감지해 직접 수동 해제를 요구했다. 잠금을 우회하지 않고 사용자에게 실제 잠금 해제만 요청했다. PR75의 새 준비 후보와 두 테마 ROOT_ACK/실제 UI는 별도 대기며, 이 상태를 PR74/75 병합 완료로 쓰지 않는다. PR74의 실제 위치 권한 허용, positive 실행과 관찰 창은 여전히0이다. 새 후보의 CI/Codex는 push 후 별도로 확인한다.

이번 근거는 native 수동 검증 준비와 환경/상태 확인에 한정한다. 기존 README의 네 제품 사례보다 더 강한 실제 SDK 저장/수명 결과를 확보하지 못해 기존 사례 내용/순서와 지정 여섯 항목/실제 코드를 보존했다. 고정 A/B 원값과 미확정 성능 결론을 유지하고 새 성능 수치나 테스트 개수로 성과를 만들지 않는다. 필수19/54와 선택3개 제외를 유지하며 실제 수신/복구 전에는 관련 항목을 추가 체크하지 않는다.


### 2026-11-03 PR75 병합 main과 전면 위치 기능의 통합

PR75의 실제 두 테마/SDK/원형 검증과 최종 필수 CI/Codex 종료를 확인해 main0567808에 병합했다. PR74는 그 main을 일반 merge하며 기존 이력과 PR을 유지한다. 충돌은 Main의 전면 위치 컨트롤러와 exact 종료 원본 탐색, 이력 화면의 위치 기록 행동과 exact 탐색 제한이 겹친 두 파일에서 발생했다. 어느 기능도 지우지 않고 현재 RESUMED 탐색 항목/메모리 원본과 기존 P/UX/reader/source 소유권을 함께 보존한다. 일반/명시 복습 두 route 모두 실제 위치 이력 callback을 내부 content에 전달하며 같은 원본의 일회성 전달과 pause/resume stale action, D/UX 잠금, 원본 변경 뒤 차단을 기존 Compose 회귀에 추가한다.

이는 기능 통합 정확성 변경이며 속도 개선이 아니다. 같은 기능의 전후 앱 성능을 주장하지 않고 기존 frozen M1/원형 자료와 미확정 결론을 유지한다. 필요한 검증은 JDK17 전체 verify/native APK 빌드, 실제 Compose/Room 경계와 관련 화면, 최신 head CI/Codex 및 원래 승인된 두 창의 실제 SDK 수신/복구다. PR75의 schema6 실기를 통합 schema7 후보의 실기 성공으로 쓰지 않는다.

실제 위치 승인 소모 전에 비공개 host 검증 도구를 별도 감사했다. 기존 원장의 strict 필드 불일치, 원장 소비 뒤 native proof 이전 중단 복구 누락, verifier/baseline 세대 혼합과 중단 canonical 전송 복구 경계를 발견해 실기 진입 차단을 유지했다. 원래 단일 원장과 승인 횟수를 보존하고 복구 provenance 및 metadata 세대를 분리하는 수정은 실제 Android SELFTEST와 현재 후보 재바인딩을 통과한 뒤에만 적용한다. 합성 검사/컴파일을 위치 수신이나 물리 복구 성과로 계산하지 않는다. 실제 grant/positive/위치 창은0, 추가 JDI는1/1 소비와 금지를 유지한다.

README는 자동 병합 후에도 정확 네 사례와 지정 여섯 항목을 유지한다. PR75에서 이미 강화한 원본/조회 소유권 사례1과 나머지 세 제품 사례를 보존하며 검증 도구 자체의 수정을 더 강한 제품 성과로 교체하지 않는다. 새 화면과 미측정 성능 수치, 테스트/CI 집계를 README에 추가하지 않는다.


통합 첫 verify는 자동 병합이 놓친 HomeRouteContent의 onLocationRecording 전달 누락으로 실제 컴파일 실패했다. 기존 HomeRoute와 onFinishedReview를 받는 새 overload 양쪽이 내부 필수 callback으로 전달하도록 정정했다. 내부 no-op 기본값으로 오류를 숨기지 않았고 기존 Compose 회귀에 새 overload의 실제 버튼 전달과 현재 D/UX 거부를 추가했다. 위치 이력도 Explore와 exact route 모두 같은 원본 callback을 전달한다.

수정 후 JDK17.0.21 spotlessApply, 전체 ./scripts/verify.sh와 :app:assembleDebugAndroidTest가 모두 exit0이었다. 전체 verify는 네 BUILD SUCCESSFUL을 확인했고 이전 컴파일 실패는 별도 보존했다. 이 결과는 자동 검사/빌드 성공이며 추가한 Compose 회귀의 실제 Android 실행, 통합한 제품의 실제 화면과 원래 승인된 SDK 두 창은 아직 별도 미실행이다. 새 후보의 CI/Codex도 push 뒤 별도 확인한다.


### 2026-11-03 통합 후보의 실기 사전 확인과 안전한 인계

통합 소스296c584의 필수 CI4개 성공과 같은 head Codex 완료/지적 없음을 실제 확인했다. 새 Compose callback 회귀와 통합 native APK는 빌드됐으나 실제 Android 실행 성공으로 계산하지 않는다. PR75의 완료 계획은 실제 병합과 main 동기화/브랜치 정리 근거를 추가해 completed로 옮겼고 측정 기록의 링크를 갱신했다.

같은 승인 전용 AVD를 기존 userdata/기기 소유권으로 정상 재개했다. 실제 콘솔 정체성, 부팅과 원래 고정 사용자 응답은 확인됐지만 전체 권한/flags/설정 사전 읽기가45초에 종료되지 않았다. 새 APK 설치 이전의 실패다. 비공개 userdata COW 백업을 보존하고 이미 설치한 공식37.2.13과 정상 부팅 옵션으로 동일 기기를 재개했으며 GPS config는 변경하지 않았다. 이후 전체 사전 읽기도90초에 시간 초과했다. 두 시도 모두 새 제품/테스트 APK 설치, positive native, grant와 위치 관찰/임시 저장은 시작하지 않았다.

후속 읽기에서는 앱 소유 파일의 정규 파일/크기/nonce 일치를 확인했지만 현재 사용자의 직접 조회가 activity 시스템 서비스를 찾을 수 없다는 응답으로 실패했다. 앞서 부팅/사용자/패키지 읽기가 성공한 시점과 이후 시스템 서비스가 없어진 시점을 구분한다. 로컬의 실제 package 표본을 원래 awk와 전체 정렬 파이프로 재생했을 때 종료됐으므로 로컬 파서 무한루프는 재현하지 못했다. 이는 Android awk의 정상성이나 전체 사전 확인 성공을 증명하지 않는다. 현재 실패의 유일 원인을 GNSS, SDK, 권한, 사용자 잠금이나 앱 코드로 확정하지 않는다. 과거 PR75의 GNSS watchdog 근거도 이번 기기의 원인으로 차용하지 않는다.

현재 사용자/권한 상태가 확인되지 않는 동안 guard를 완화하거나 다른 사용자로 retarget하지 않았다. 앱의 실제 SDK 위치 권한 허용, positive 호출과 두 관찰 창은 계속0이며 원래 승인 원장도 미소비다. 추가 JDI는 재실행하지 않았다. 이 재부팅 이후 전체 typed canonical/원래 전체 권한/flags/설정 동등성 재검증은 미완료이며 과거 성공으로 대신하지 않는다. 기존 canonical과 userdata, 원래 상태 및 별도 COW 백업을 보존했다. 전용 기기는 정체성을 확인해 정상 종료했고, 잠시 종료했던 원래 보호 기기는 기존 SDK/옵션/userdata로 재개한 뒤 실제 콘솔/부팅/사용자 응답을 확인해 시작 당시 실행 상태를 복구했다.

host bridge의 원장 스키마/중단 복구/metadata 세대/canonical 재전송 수정은 원본 백업 뒤 비공개 tooling에 적용했고 Java17/D8 컴파일을 통과했다. 실제 CI/Codex 원래 필드와 최신 소스/APK/dex/lease를 읽는 disabled 재바인딩 초안을 만들었으며 원래 원장, scope, 횟수와 device/nonce를 유지했다. Android SELFTEST/현재 후보 설치 대조/두 번 prepare와 실제 물리 복구는 미실행이므로 preparation과 native 실행 차단은 계속 유지한다. 이 합성 결과와 컴파일/기기 재시작을 SDK 수신 성공이나 앱 성능 개선으로 쓰지 않는다.

README의 네 사례와 여섯 항목, 실제 코드/현재 유효 링크와 기존 기능 화면은 보존한다. 이번 추가 근거는 검증 환경과 도구 경계이므로 기존 제품 사례를 새 성과로 교체하지 않는다. 별도 성능 개선을 구현하지 않았으므로 이 통합/환경 확인/문서 기록은 전후 앱 성능 계측 적용 제외다. 공식 안내/양식8개의 현재 원문은 기준 hash와 같았으며 필수19/54와 선택3개 제외를 유지한다. 새 위치 동의나 JDI 동의를 반복 요청하지 않고 남은 실제 검증/복구와 병합을 미완료로 인계한다.


### 2026-11-03 사용자 요청에 따른 동일 기기 시스템 복구 재개

사용자가 PR74 마무리를 다시 요청했다. 기존 두 창 승인과 미소비 원장을 유지하고 동일한 전용 AVD/userdata에서 원인을 좁힌다. 원래 기기와 PR75 자료는 보호하고 추가 JDI, 좌표 로그/반출/스크린샷, 원장/nonce 변경에 의한 횟수 초기화와 안전 guard 완화는 하지 않는다. 변경 전 근거는 전체 사전 읽기의45/90초 실패, 그 사이 정상 반환된 패키지/사용자 응답과 이후 activity 서비스 부재이며 실제 SDK 관찰은0회다.

다음 재개는 단순 부팅 표식 대신 같은 guest boot 식별/uptime과 system_server 생존, activity/package 서비스 및 기존 고정 사용자 응답의 시간 순서를 관찰한다. 좌표가 통과할 수 없는 on-device 필터로 framework 예외 클래스/코드 위치와 고정 오류 종류만 수집해 guest 재부팅과 framework 재시작을 구분한다. 서비스가 안정적으로 유지되기 전에는 APK 설치나 권한 허용/positive를 하지 않는다. 같은 기기의 비파괴 설정/실행 옵션 변경이 필요한 경우 현재 원형과 차이를 기록하고 해당 차이만 복구한다. userdata 삭제/초기화로 실패를 숨기지 않는다.

완료 조건은 실제 원인 근거와 동일 기기 복구, 현재 고정 사용자/전체 원래 권한 및 canonical 확인, 통합 APK 설치 hash와 Compose/Room/실제 미허용/화면 검증, 실제 Android metadata SELFTEST/현재 prepared binding, 원래 두 창과 최종 물리 복구 및 최신 CI/Codex/정책이다. 환경 진단과 host 도구 경계는 앱 제품 성능 변경이 아니므로 전후 앱 성능 측정 적용 제외다. 도구 응답 시간/재부팅 횟수를 앱 성능 성과로 쓰지 않는다.

### 통합 후 Android 검증의 실제 실패와 수정 범위

- 기준 head `3b8ca04`의 같은 전용 검증 기기에서 SDK 위치를 수집하지 않는 Android 화면/Room 시나리오를 실행했다. 호스트 실행은 300초에 시간 초과했고 성공으로 집계하지 않는다. 실제 위치 권한 허용과 양성 관찰은 아직 0회다.
- Room 시나리오 3개의 첫 실패 위치는 seed의 `schemaVersion() == 6`이었다. PR #74의 현재 실제 저장소는 schema 7이므로 이 검증과 finally의 고정값을 현재 스키마로 수정한다. 실제 Room 파일, 외래 키와 종료 출처 검증은 유지한다.
- 별도의 화면 실패는 실제 Catalog의 focus 소실 또는 첫 화면 대기 시간 초과로 발생했다. 화면 본문의 연결 결함으로 확정하지 않으며 원인과 단독 재현을 분리한다. 실제 RESUMED, focus와 display 조건을 완화하지 않는다.
- 이 변경은 잘못된 회귀 검증 전제의 수정이며 앱 실행 성능과 무관하므로 전후 성능 측정 대상에서 제외한다. 실패 원본, 변경 후 동일 시나리오와 전체 JDK17 검증, 최신 head CI/Codex 결과를 별도로 보존한다.

- Room 검증 고정값을 수정한 소스에서 JDK17 `./scripts/verify.sh`와 `:app:assembleDebugAndroidTest`가 각각 exit 0이었다. 이것은 Android 수동 시나리오 성공의 대체가 아니다.
- 실제 권한 거부 단독 테스트는 정확한 method 시작 status 1, 실패 status -2와 최종 instrumentation 종료 -1을 확인했다. 최종 종료 -1만으로 테스트 통과를 판단하지 않는다. 앞선 실행의 빈 필터 결과와 이번 실패를 보존하고, 과거 앱 보고서의 `passed`를 이번 성공으로 재사용하지 않았다.
- 같은 기기의 Activity/Package 서비스 등록은 확인됐지만 뒤이은 실제 instrumentation 상태/새 보고서 조회가 제한 시간 내 반환하지 않았다. 원인을 확정하지 않고 데이터 보존 상태로 같은 전용 AVD를 정상 종료했다. 추가 JDI, 새 기기, 데이터 초기화, 위치 권한 부여와 실제 위치 수집은 하지 않았다.
- 새로 확인한 것은 환경과 회귀 검증 전제의 문제이므로 기존 README 4개보다 채용 관점에서 더 강한 제품 문제 해결 사례로 보지 않는다. 기존 4개와 순서를 보존한다.

### 실제 Main 초기 상태의 재현과 검증 준비 수정

- 동일 사용자 데이터를 보존한 재부팅 뒤 최근 실패 보고서를 확보했다. 첫 실패는 `initial-environment`의 실제 Main `RESUMED` assertion이었다. 원본 snapshot과 격리 저장소를 만들기 전이므로 이후 원본 비교 항목도 확인 실패로 구분한다. 이 보고서는 실제 권한 미허용 시나리오이며 위치 구독과 권한 부여의 성공 근거가 아니다.
- PR #75의 실제 화면 검증에서 사용한 방식과 같이 최초 Main 객체를 캡처하고, 초기 준비 단계에서 같은 ActivityScenario에 RESUMED를 요청한 뒤 객체 동일성을 확인한다. 기존 실제 lifecycle, display, focus, SDK P/UX와 권한 검사는 그대로 실행한다. 준비 이후의 host 소실에 자동 재포커스하거나 다른 Activity를 대신 사용하는 복구는 추가하지 않는다.
- 앱 동작 변경이나 성능 최적화가 아닌 instrumentation 초기 준비의 수정이므로 앱 전후 성능 측정에서 제외한다. 새 native APK의 전체 JDK17 검사, 실제 권한 거부 단독 시나리오, 본래 위치 2회와 복구 검증, 최신 head 필수 CI/Codex를 다시 구분해 기록한다.

- 같은 Main 초기 준비 수정 뒤 JDK17 `./scripts/verify.sh`와 `:app:assembleDebugAndroidTest`가 다시 각각 exit 0이었다. 실제 Main의 준비/포커스와 SDK 거부 시나리오의 재실행은 별도로 남아 있다.

### 최신 검증과 인증/사용자 상태 인계

- 제품/native 소스 `20fda0f`의 JDK17 전체 verify와 native APK 빌드, 같은 head 필수 CI 4개 및 Codex 완료/지적 없음, 미해결 스레드 0을 확인했다. 실제 설치 제품 APK와 native APK의 hash, 설치 전후 전체 권한/flags/설정의 원래 값 일치를 각각 확인했다. 이 결과가 실제 위치 수집 성공을 뜻하지는 않는다.
- 새 Main 준비 소스의 권한 미허용 단독 실행은 JUnit 시작/완료가 없고 instrumentation shortMsg와 종료 코드 0만 남겨 실패로 분류했다. 종료 코드 0을 JUnit 성공으로 세지 않는다. 앱 target crash block만 통과시키는 읽기 전용 필터에서 해당 target block은 발견하지 못했으므로 앱 crash나 구체적 startup 원인은 미확정이다.
- 메타데이터 prepare도 TransportError로 중단됐고 actual Android SELFTEST 및 첫/최종 prepared receipt는 생성되지 않았다. 이어 identity 단계만 분리해 같은 실패를 재현했다. 실제 콘솔 이름과 ADB 연결은 일치했고 두 QEMU property와 부팅 완료를 확인했지만 현재 사용자가 원래 승인 때 고정한 검증 사용자와 달랐다. 원래 사용자의 시작 상태 조회는 확인하지 못했다. 이를 다른 사용자/새 nonce/새 기기/새 승인 원장으로 바꾸어 통과시키지 않는다. 물리 canonical/권한/설정 최종 재대조와 수동 검증은 여전히 미완료다.
- 화면 제어 도구가 현재 Mac 잠금을 감지해 사용자 수동 해제를 요구했다. 기존 잠금 해제 응답을 현재 인증 성공으로 재사용하지 않고 이 채팅에서 수동 해제를 요청했다. 현재 기기의 실제 UI와 원래 프로필을 확인하기 전에는 관련 실기와 병합을 완료하지 않는다.
- 원래 승인된 grant 1회/positive invocation 1회/최대 20초씩 2개 창 원장은 미소비이며 실제 위치 권한 부여와 양성 위치 관찰은 각각 0회다. 추가 JDI는 실행하지 않았고 과거 추가 진단 1/1 소비와 재시도 금지를 유지한다. 이전 실패와 이번 실패, 새 준비 계획과 실제 실행 결과를 분리한다.
- 사용자가 Mac 잠금을 해제한 뒤 같은 전용 기기와 원래 검증 프로필을 다시 확인하고, 새 active lease/현재 PID/최신 head와 원래 승인 연결을 검토한 binding으로 재개한다. 현재 prepared/permit은 없으며 과거 활성 binding의 실행 허가를 재사용하지 않는다. 원래 프로필과 거부 경로를 확인하고 actual SELFTEST/두 번 prepare/물리 복구가 끝난 다음에만 기존 두 창 검증을 진행한다.
- 이번 변경은 검증 준비와 전제 수정이며 앱 성능 개선이 아니다. README는 기존 4개 사례와 순서를 보존하고 신규 성능 수치나 실기 성공을 추가하지 않는다. APP-04 전체와 PR #74는 미완료/미병합으로 유지한다.
