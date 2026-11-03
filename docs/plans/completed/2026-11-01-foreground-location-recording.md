# 명시적으로 켠 전면 연습의 SDK 위치 표본 기록

- 상태: 완료. 제품 구현과 실제 검증, PR74의 최신 head CI/Codex 확인, 정상 병합, main 동기화와 병합 브랜치 정리를 마쳤다. APP-04 전체와 배경 위치/실제 경로 연결은 별도 미완료다.
- 요구사항: APP-04의 위치 입력/수집 상태 연결, 주차 후 기록 열람. 위치/경로 전체와 APP-05의 배경 위치 수집은 완료 범위가 아니다.
- 기준 main: dd4897880b24776c1599bb18096abc5bcc0a2219

## 목표와 범위

기존 LastKnown 한 번 조회와 차량 상태 FGS는 그대로 둔다. 업데이트 전용 domain 계약/실제 SDK 어댑터와 별도 세션 자식 저장소를 연결하고 사용자가 현재 한 활성 연습에서 P/UX 허용을 확인해 명시적으로 켠 경우에만 전면 앱의 위치 표본을 기록한다. 시작/상세 열람은 P와 UX 해제 모두 필요하고, 시작 뒤 D의 수동 SDK 수신은 상세 조작과 별개다. 실제 D 수신은 별도 수동 검증으로 확인한다.

소유권은 전체 활성 session snapshot과 현재 Main STARTED host에 묶는다. 앱 STOP, source 변경/종료, 권한 상실, SDK/저장 오류와 수집 상한에서 listener/sink를 닫고 자동 재개하지 않는다. 재개는 현재 P/UX와 새 사용자 요청을 다시 확인한다. FGS/내비 배경으로 위치 수집을 확장하지 않는다. 위치 표본을 실제 이동 경로/현재 위치로 추정하지 않고 SDK 원시 시각/confidence의 미정의 의미를 보존한다. 누락/Empty/거부/미지원/오류는0좌표나 정상 상태가 아니다.

원래 schema6 전체 행/값/타입/순서/sequence를 보존하는 additive migration을 사용한다. 새 위치 기록/실행 자료도 종료 기록 삭제의 연결 자료와 stale 확인 경계에 포함한다. 위치 원문, 실제 source 식별자/경로/장치/로그를 공개하지 않는다. 실제 SDK 저장 검증은 별도 명시 동의와 test-owned 자료로만 진행하고 앞선 두 창 동의는 재사용하지 않는다.

## 완료 조건

- [x] 현재 검증된 callback API/기존 계약/실제 기술 의존성을 확인하고 전면 전용 source와 중단 정책을 구현한다.
- [x] SDK 업데이트와 LastKnown 출처를 구분하고 typed 저장/마이그레이션/종료 뒤 append 거부/삭제 연결 자료 CAS를 확인한다.
- [x] 명시 시작/중단과 주차 후 열람을 실제 제품 UI에 연결하고 P/UX 미확인, D 상세 잠금과 source 폐기를 유지한다.
- [x] JDK17 전체 verify와 관련 제어/DB/native 검증, 기본/확대/다크의 실제 UI 흐름을 확인한다.
- [x] 별도 실제 SDK 허용 검증의 새 동의/미실행/실패/수신/원형 복구를 구분하고 실제 검증이 없으면 그 조건은 완료로 세지 않는다.
- [x] 같은 최신 PR head 필수 CI/Codex/미해결 지적/정책과 실제 수동 근거를 확인한 뒤 정상 병합/동기화/브랜치 정리를 수행한다.
- [x] README 기존4개와 새 제품 문제 해결 근거를 비교하고 더 강한 근거가 없으면 내용/순서/여섯 항목/실제 발췌/이미지를 유지한다.


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


## PR75 통합과 검증 전제 수정

- PR75의 병합된 main을 일반 merge해 Main, 일반/명시 복습 이력과 두 Home overload의 위치 기록 callback을 연결했다. 종료 원본, 주차/UX 잠금과 stale action 차단은 유지한다. callback 전달 누락은 컴파일 실패로 확인해 수정했다.
- 통합 Android 검증에서 Room 테스트의 seed와 finally가 schema6을 기대하던 두 곳을 현재 schema7로 수정했다. 실제 저장소와 외래 키 검사는 유지한다.
- native 검증은 최초 Main 객체를 캡처하고 같은 ActivityScenario에 RESUMED를 요청한 뒤 객체 동일성을 확인하도록 보강했다. 기존 실제 lifecycle, display, focus와 SDK/권한 검사는 그대로 실행한다. 준비 이후 host 소실을 자동 재포커스하거나 다른 Activity로 대체하지 않는다.

## 통합 후보 검증 결과

| 구분 | 결과와 범위 |
|---|---|
| JDK17 전체 `./scripts/verify.sh` | 제품/native 소스 `20fda0f`에서 통과 |
| native APK 빌드 | 같은 소스에서 통과 |
| 필수 CI/Codex | 기록 head `de9ae56`의 필수 CI4개 성공, 동일 head Codex 완료/지적 없음, 미해결 스레드0 확인. 이후 문서 정제 head는 별도 최신 검사를 확인한다 |
| 통합 Android 제어 검증 | 시간 초과/실패를 보존. 스키마 전제 수정이 관련 실기 통과를 대신하지 않음 |
| 신규 native 거부 시나리오 | 미통과. JUnit 시작/성공 없이 shortMsg와 instrumentation code0인 결과도 실패로 구분 |
| Android metadata SELFTEST/두 번 준비 | 미완료. 준비 처리 실패와 선행 identity 조건 미충족을 성공으로 바꾸지 않음 |
| 허용 상태 SDK 수신과 최종 복구 | 미실행/미완료. 과거 후보의 성공으로 대체하지 않음 |

구체적인 로컬 실행 상태와 운영 인계는 공개 범위 밖에 보존한다. 공개 기록에는 제품 코드, 일반 재현 조건과 정제한 검증 결과만 유지한다. 원래 source/run/event 소유권과 사전 조건을 완화하거나 다른 대상의 결과로 검증을 대체하지 않는다.

## 남은 완료 조건

- 현재 구현의 거부 경로와 통합 화면 동작, 실제 SDK 입력을 별도로 확인한다. 실제 입력은 주차 시작/명시 중지와 D 수신/상세 잠금, 실제 Main STOP, 복귀 뒤 자동 재시작 방지 및 최종 데이터 보존을 검증한다. 각 관찰 창의 상한은 기존 시나리오대로 20초다.
- 실제 Android SELFTEST, 첫/최종 준비와 복구 검증이 모두 끝난 뒤 수집 검증을 진행한다. 원래 단일 실행 제한과 각 단계의 실제 증거를 유지한다.
- PR 최신 필수 검사와 Codex 리뷰, 관련 수동 검증 및 미해결 지적 처리가 모두 끝나야 완료/병합으로 기록한다. APP-04 전체와 PR #74는 아직 미완료다.

검증 전제와 문서 정제는 앱 실행 성능을 바꾸지 않아 앱 전후 성능 측정 대상에서 제외한다. 실제 위치 수집 성능은 전후 미측정이며 기존 메모 공개 관찰을 위치 수집 개선으로 쓰지 않는다. README의 기존4개는 제품 문제의 영향, 원인 추적과 구현 판단을 더 강하게 입증하므로 그대로 유지하고 성능 향상을 추가하지 않는다.


## 통합 실기 재검증과 확대 화면의 최초 준비 수정 계획

같은 제품/native 소스에서 실제 권한 거부 단일 시나리오와 통합 제어 검증10개를 각각 독립 실행해 통과했다. 각 실행의 JUnit 완료, 기존 DB 파일 보존과 전체 원래 권한/설정 대조를 별도로 확인했다. 기본1.0/라이트의 위치 기록 제어3개와9개 실제 UI anchor의 TextLayout/표시 검증도 통과했다. 이는 제어 입력의 정확성 근거이며 실제 허용 SDK 위치 수신이나 성능 개선 수치가 아니다.

확대1.3/다크 첫 흐름은 fixture와 소유 저장소 생성 전에 실패했다. 원래 main-thread guard에서 같은 검사 Activity가 CREATED/focus=false였고 기대 font/dark와 유효 display는 일치했다. 실패와 독립적인 원형 복구 성공을 보존했다. 이를 데이터 또는 위치 수집 결함으로 단정하지 않는다.

수정 범위는 검사 Activity의 최초 준비에 한정한다. 원래 만들어진 같은 ActivityScenario에 RESUMED를 한 번 요청하고 객체 동일성을 확인한 뒤, 기존 lifecycle/focus/display/구성 및 실제 focused window의 연속 두 관찰을 그대로 통과해야 host를 발급한다. 소유권 발급 뒤 재포커스, 다른 Activity로의 교체, 실패 본문의 재시도와 timeout 확대는 추가하지 않는다. 새 APK로 기본/확대/다크와 거부 경로를 다시 검증한다.

이 변경은 Android 검증의 최초 준비와 README의 현재 소스 anchor 보정이며 제품 실행 소스를 바꾸지 않는다. 앱 실행 성능 전후 측정 적용 제외 이유는 검증 준비 자체의 정확성 수정이기 때문이다. 변경 전 실패의 원래 guard와 같은 조건의 변경 후 실제 검증 결과를 구분하고 성능 향상을 주장하지 않는다. 실제 허용 SDK 검증은 여전히0회이며 원래 한 번의 실행 제한을 유지한다. 새로 확인한 제어 근거는 기존4개 제품 사례의 영향, 비동기/저장 경계 판단과 재현 결과보다 강한 교체 근거가 없어 내용과 순서를 유지한다.


## 2026-11-03 현재 검증 환경의 설정 전환과 리소스 범위 분리 계획

변경 전 기준은8818b5e다. 같은 제품과 계측 APK의 기본 글자/라이트 제어 흐름3개와 실제 권한 미허용 시나리오는 이번 실행에서 통과했다. 전역 글자/다크 설정을 바꾸는 준비 중에는 실제 GNSS 상태 수집 종료에서 시스템 Watchdog이 발생했고, 별도 확대/다크 실행은 ActivityScenario 활성화 실패 또는 JUnit 시작 전 계측 종료로 실패했다. 이 결과를 UI 성공이나 SDK 수신으로 세지 않는다. 공식 에뮬레이터의 전송 경로 옵션 한 개를 바꾼 실험도 완료 조건을 충족하지 않아 채택하지 않고 원래 옵션으로 되돌렸다.

복구에서는 설정 쓰기 직후 종료한 첫 시도의 글자 크기가 다음 부팅에 보존되지 않았다. 쓰기 뒤 대기, 재조회, 디스크 동기화와 정상 부팅 뒤 전체 원형 대조를 마친 최종 복구만 성공으로 기록한다. Automotive의 야간 모드는 부팅 영속값으로 가정하지 않는다. 실제 위치 권한 허용/양성 native 실행/관찰 창은 모두0이며 기존 단일 승인과 최대2창/각20초는 그대로 남아 있다. 추가 JDI는 실행하지 않는다.

이번 수정은 제품 실행 성능이나 기능 최적화가 아닌 계측 fixture의 시각 조건 구성이다. 제품 소스와 SDK native 시나리오는 변경하지 않으므로 앱 실행 성능의 전후 측정 적용 대상에서 제외한다. 실행 시간이나 통과 개수를 성능 향상으로 쓰지 않는다.

계획은 첫 화면 구성과 실제 host 소유 전, 명시적인 검증 인자가 있을 때만 Android Resources의 Configuration과 DisplayMetrics를 복사해 확대/다크 리소스를 적용하는 것이다. 실제 제품 Route/Compose 위젯, 실제 TextLayout/글꼴/글리프/잘림/viewport, 동일 Activity/focus/display와 두 번의 관찰 및20초 경계는 유지한다. 관련 Resources가 여러 Context에서 공유될 수 있음을 인정하며, 수집 controller와 소유 저장소 종료 및 콘텐츠 해제 뒤 원래 리소스 구성을 복구하고 전체 OS 설정과 제품 파일 보존을 별도로 대조한다. 결과의 범위는 실제 Android 리소스를 적용한 제어 입력 UI이며 OS 전역 설정 전환이나 SDK Main의 다크 검증이 아니다.

완료 판단에는 JDK17 전체 verify와 계측 APK 빌드, 새 APK의 기본/리소스 확대 다크 실제 제어 흐름, 실제 미허용 및 기존 단일 승인 범위의 별도 SDK 두 창, 원형 복구, 최신 head의 CI/Codex와 미해결 지적 확인이 필요하다. 현재 남은 검증을 성공으로 선기록하지 않는다. README의 기존4개는 제품의 비동기 소유권, 데이터 경계, native 입력과 실행 정리의 판단을 입증한다. 이번 환경/fixture 진단은 이를 능가하는 제품 근거가 아니므로 사례와 순서를 유지한다.


### 리소스 범위 수정의 자동 검사

명시적인 controlled/visual/resource 인자가 모두 있을 때만 첫 Compose 구성 전에 실제 Android Resources를 적용하도록 변경했다. 같은 프로세스에서 공유할 수 있는 관련 Configuration/DisplayMetrics를 기록하고, controller 종료/콘텐츠 해제/소유 저장소 close 뒤 정확한 리소스와 선택한 테마 속성의 복구를 검사한다. 처음 포맷 검사는 긴 줄에서 실패했고 줄을 정리한 뒤 JDK17 전체 verify와 계측 APK 빌드를 모두 통과했다. 실제 빌드의 제품 APK는 기존20f 제품과 바이트 동일하고 실제 SDK native 시나리오 소스도 그대로다. 새 계측 DEX에 리소스 범위 설정/복구 코드가 포함된 것은 확인했지만 새 APK의 실제 UI/SDK 성공으로 대신 기록하지 않는다.


## 2026-11-03 최종 실제 검증과 남은 병합 단계

### 확인한 동작

제품/계측 기준은 `95796dedf80733ce526348fb577aa96340c01311`이다. 이 head의 JDK17 전체 verify와 계측 APK 빌드, 필수 CI4개와 Codex 완료/지적 없음은 확인했다. 같은 최신 계측 APK에서 실제 권한 거부1개, 기본1.0/라이트 제어3개와 Android 리소스 범위1.3/다크 제어3개를 확인했다. 다크는 실제 Configuration/DisplayMetrics를 적용한 제어 UI이며 OS 전역 설정 전환이나 SDK Main의 다크 검증으로 확대하지 않는다.

원래 승인 한 건의 같은 전용 기기/사용자/소유권/namespace/원장으로 FUSED_LOCATION 허용1회와 양성 native 호출1회 안에서 최대20초씩2창을 실행했다. 첫 창은 P에서 명시 시작/중지, 둘째는 P에서 시작해 실제 SDK D 수신과 보이는 상세 잠금, 실제 Main STOP, P 복귀와 자동 재시작 방지를 확인했다. 첫 창19개와 둘째17개의 실제 표본을 수신해 소유 Room 표본과 typed 순서를 기기 안에서 대조했다. 각 창의 등록은1회, 종료 이유는 USER_STOP/HOST_STOPPED, 상한 밖 수신/저장은0이고 Empty/오류 terminal은 이번 입력에서 발생하지 않았다. 이 개수는 수집 정확성 근거이며 성능 개선 수치가 아니다. 미지원/Empty/오류의 실제 발생을 검증했다고 기록하지 않는다.

실제 Hilt SDK, 제품 controller와 테스트 소유 Room의 연결을 확인한 범위다. 제어 UI 입력과 실제 SDK 수신을 구분하며, 제품 시작 버튼으로 production DB에 실제 좌표를 저장한 별도 실기나 실제 도로 경로, 배경 위치 수집을 검증한 것은 아니다. 로컬 listener/store 종료는 공급자의 원격 종료 ACK를 뜻하지 않는다. SDK 시각 기준과 신뢰도 단위는 미확인으로 유지한다.

### 실패와 엄격한 결과 재검증

기존 호스트에서 시스템 초기 사용자 설정/CarService 연결 대기가 남아 있던 실패는 보존했다. 같은 userdata와 원래 부팅 옵션을 공식 설치 호스트에서 실행한 뒤 원래 사용자, 전체 권한/설정과 실제 Main/P/UX, 시스템의 연속 안정성을 확인했다. 호스트 버전만으로 영구 해결이나 성능 향상을 확정하지 않는다.

검사기의 쓰기 가능한 DEX가 Android14에서 Java SELFTEST 전에 종료되던 문제는 같은 pin의 바이트를 확인하고 읽기 전용으로 바꾼 뒤 동일 SELFTEST로 확인했다. 읽기 전용 SQLite 조회 뒤 생긴 sidecar의 엄격한 거부, 재준비 중 같은 context의 이전 zero-marker 이력 충돌도 실제 고정 오류와 소유 metadata로 좁혔다. 기존 canonical import와 일치하는 이력의 별도 보존을 사용했으며 unknown 자료 삭제, 검사 완화나 원장/예산 초기화를 하지 않았다. 실제 두 번의 준비와 권한/SDK 없는 복구 검사를 통과한 뒤에만 원래 양성 실행에 진입했다.

실제 양성 실행의 원래 전체 도구는 결과 요약기의 허용 목록에서 현재 producer의 `nativePreparationMainResumeRequested` Boolean을 빠뜨려 SCHEMA로 종료했다. 원래 `passed=false`와 exit2를 보존했다. 원래 pin의 드라이버가 엄격한 JUnit 완료 파싱 뒤에만 요약 단계로 진행하고, 그 이전 예외를 별도로 누적하는 제어 흐름과 실제 원본 결과/단계를 독립 검토해 JUnit 완료 근거를 확인했다. 이를 새 JUnit 실행 영수증으로 바꾸지 않았다.

정확한 Boolean/실패 단계만 보강한 별도 REPORT 전용 decoder를 원래 소유 폴더에서 실행했다. 원래 request, generation, baseline, native APK, 기존 verifier와 소비 원장은 그대로 검증하고 새 decoder 자체의 source/DEX pin은 별도로 고정했다. 알 수 없는 키, 거짓/문자열 Boolean과 좌표 키의 거부를 유지한 실제 SELFTEST 및 원래 보고서의 REPORT가 종료0으로 통과했다. 보고서는 PASSED와 두 창, D 잠금/STOP/복귀, 실행 중 production domain/typed 보존을 확인했다. 원래 전체 도구의 실패를 성공으로 고치거나 위치를 다시 수집하지 않았다.

별도 보조 wrapper는 성공 자료 저장 뒤 자신의 `sys.exit(0)`를 잡는 후처리 오류로 exit1을 남겼다. 이 종료도 보존하고, 실제 SELFTEST/REPORT 원문이 아닌 허용 metadata와 원래 protocol의 순수 재파싱, summary 완전 일치 및 기존 native 조건 검사로 결과를 다시 대조했다. 현재 canonical 일치는 복구 근거이고, 실행 중 원형 보존은 native 보고서의 복구 전 domain/typed 비교와 원래 JUnit 완료 근거로 별도 확인했다.

### 최종 보존과 범위

권한을 회수하고 전체 원래 grants/flags/설정, P 복귀, 소유 writer0와 임시 소유 DB 삭제를 확인했다. decoder 조회 후에도 기존 guarded 복구를 다시 실행해 schema7/위치 행0의 typed 대조와 마지막 동일 canonical byte import/sidecar 부재를 확인했다. 원래 root/원장/sidecar/준비 영수증은 변하지 않았다. 권한 허용 후에는 원문 좌표/DB/cache/log/화면을 반출하지 않았으며 추가 grant/native/관찰 창/JDI는0이다. 이번 승인 예산은 소모 완료로 고정해 추가 실제 위치 실행을 금지했다.

소유 검증 AVD/forward와 임시 표시 포인터를 정리하고 userdata를 보존했다. 시작 전에 실행 중이던 원래 보호 AVD는 원래 옵션으로 실행 상태를 복원했다. 이 복원은 console/boot/current-user 응답 확인 범위이며 새 사용자 선택이나 보안 잠금 해제를 대신하지 않았다.

README의 기존4개는 종료 원본/조회 소유권, 원자 삭제, 실제 native 지도 입력과 배경 기록 소유권 반환의 제품 결함과 판단을 입증한다. 이번 새 위치 연결과 검사기 호환성 복구만으로 이 사례들보다 강한 제품의 원인 축소/대안/재현 근거를 확보했다고 판단하지 않아 네 사례의 내용과 순서, 코드/링크/이미지를 유지했다. 구현 기능 설명의 위치 미수집 문구만 현재 전면 표본 계약에 맞췄다. 새 성능 개선을 주장하지 않으며 이전 메모 열람 A/B 관찰을 SDK 수집 성능으로 전용하지 않는다. 이번 최종 문서 변경은 앱 실행 성능과 무관하므로 전후 성능 측정 적용 대상에서 제외하고 내용/링크/개인정보/README 구조와 전체 verify를 확인한다.

최종 문서 커밋의 필수 CI와 Codex 종료, 미해결 지적/충돌/정책을 실제 최신 head로 확인한 뒤에만 PR74를 병합한다. 병합/ff-only/브랜치 정리 완료 기록은 실제 결과 뒤에 남기며 APP-04 전체, 배경 위치나 실제 경로 연결을 대신 완료 처리하지 않는다.


## 2026-11-03 PR74 병합과 계획 종료

[PR74](https://github.com/HanGilLab/Android/pull/74)의 최종 head `b71dc54287df4bafa647779c54d8046524135826`에서 필수 CI 4개와 Codex 검토 완료, 미해결 지적 없음, 충돌과 병합 정책을 확인한 뒤 정상 squash 병합했다. 실제 병합 커밋은 `fcf450142f69d3a1d5c837c5a1198a5dc791becb`이며 최종 PR tip과 병합 커밋의 트리가 같다.

기존 main 작업 공간에서 `git pull --ff-only origin main`으로 같은 병합 커밋까지 동기화했다. 추가 커밋과 미커밋 변경이 없음을 확인한 병합 브랜치만 로컬과 원격에서 정리했고, 기존 작업 공간은 모두 보존했다. 작업 체크아웃도 같은 main 커밋에 깨끗한 detached 상태로 맞췄다. 이 실제 결과를 반영해 마지막 완료 조건을 체크하고 계획을 completed로 옮긴다.

이번 후속 변경은 계획의 상태와 종료 근거만 정리한다. 제품/계측 소스와 README의 네 사례는 바꾸지 않으며 이전 실패, 미실행과 실제 성공의 구분을 유지한다. APP-04 전체와 APP-05 배경 위치 수집, 실제 경로 연결을 완료로 확장하지 않는다. 앱 실행 성능과 무관한 문서 변경이므로 전후 성능 측정 적용 대상에서 제외한다.

이 문서 후속 변경은 JDK17 `./scripts/verify.sh quality`가 실제 통과했다. 계획의 이동 링크, 과거 본문 보존, README 불변과 문서 diff도 확인했다. 제품/계측 소스가 PR74 병합 트리와 같으므로 앞서 같은 제품 소스로 통과한 전체 verify를 문서 종료만을 위해 반복하지 않았다.
