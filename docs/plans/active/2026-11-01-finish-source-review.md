# 명시적으로 마친 같은 연습의 복습 연결

- 상태: 변경 전 A1 완료와 원형 복원 확인 후 제품 구현 중
- 요구사항: APP-06과 DES-06의 주차 후 복습 흐름 중 마침 결과와 같은 기록을 여는 연결. 실제 도로 구간, 위치 표본, 전체 항목 완료는 범위 밖이다.
- 기준 main: dd4897880b24776c1599bb18096abc5bcc0a2219

## 목표와 범위

현재 main의 SessionViewModel.finish는 Finished/AlreadyFinished에 들어 있는 종료 세션을 버리고 enum 피드백만 남긴다. 일반 이력은 진입 당시 최신 종료 기록을 고른다. 마침 뒤 다른 종료 자료가 전달되거나 원본이 바뀌면 방금 마친 대상을 그대로 복습한다는 요청을 표현할 수 없다. 마침 응답의 전체 종료 원본을 보존하고 사용자가 명시적으로 같은 기록의 복습을 열도록 연결한다. 자동 화면 전환을 추가하지 않는다.

기존 이력의 관찰, 메모, 저장 실행과 수동 마무리 열람을 재사용한다. 조회 owner를 만들기 전에 전체 expected source를 고정하고 최신 기록으로 fallback하지 않는다. 삭제, 같은 ID의 교체/재생성, active 복귀, read 실패와 종료 미확인을 나눈다. P와 UX 해제, 실제 RESUMED, reader/query/generation, 일회성 클릭과 기존 메모 CAS를 유지한다. 프로세스 재생성으로 메모리 원본을 잃으면 unavailable로 돌아가며 현재 최신 기록으로 복원하지 않는다.

새 DB/schema/SDK/권한 계약이나 배경 수집, 실제 위치/경로, 다음 과제 저장 제약 확대는 없다. 마침 쓰기에는 expected fullsource overload를 fail-closed 기본 Blocked로 추가하고 Room의 같은 트랜잭션에서 실제 원본을 비교한다. 기존 ID 기반 호출과 PracticeSession 생성자/copy ABI를 유지한다. 후반 receipt 비교만으로 이미 발생한 다른 원본의 종료 쓰기가 복구됐다고 주장하지 않는다. domain에 물리 rowId/version이 없으므로 관찰 없이 모든 논리 필드가 동일한 삭제/재삽입 ABA까지 검출한다고 주장하지 않는다. PR74와 이전 실제 위치 동의 대기는 그대로 보존한다. 기존 schema7 후보 AVD에 main/schema6 APK를 설치하지 않는다. 이미 설치된 Pleos 이미지로 별도 test-owned 초기 AVD만 구성하고 기존 AVD/userdata/config/snapshot을 변경하지 않는다.

## 완료 조건

- [ ] Finished와 AlreadyFinished의 검증한 full ended source, 예외/취소/불명확 응답을 구분한다.
- [ ] 현재 source와 실제 주차/UX/RESUMED에서만 일회성 명시 복습을 열고 옛 action을 재사용하지 않는다.
- [ ] cold exact-source 이력은 최신/다른 원본을 잠깐 표시하거나 삭제/교체 뒤 다른 행을 차용하지 않는다.
- [ ] 기존 일반 이력, 출처/Empty/Failed, 메모 CAS와 다음 과제의 latest/no-active 제약을 보존한다.
- [ ] JDK17 전체 verify와 변경 동작의 단위/실제 Room/Compose/실제 Main 기본, 확대 다크를 확인한다.
- [ ] 같은 frozen 기존 메모 disclosure 계측 A1/B/A2의 실제 원값, 원형/조건 대조와 한계를 기록한다.
- [ ] 최신 PR head 필수 CI, Codex, 실제 관련 수동 검증과 미해결 지적/정책을 확인한 뒤 병합/동기화/브랜치를 정리한다.
- [ ] README 기존 네 사례와 실제 근거를 비교하고 여섯 항목/실제 펼친 코드/기능과 이미지를 보존한다.

## 구현 단계

1. 최신 체크리스트, AGENTS, 진행 계획, 실제 Git/리뷰/lease/기한을 확인하고 독립 main 기반 작업 폴더를 만든다.
2. 아래 원형과 고정 계측을 실제 실행해 A1을 보존한 뒤에만 제품 구현을 시작한다.
3. 마침 receipt, Home 명시 action, 기존 이력의 cold exact entry와 navigation을 작은 경계로 연결한다.
4. B와 A2를 같은 조건에서 실제 실행하고, 전체 검사와 실제 UI/SDK gate/Room 보존을 확인한다.
5. 실제 결과와 한계를 계획/PR/체크리스트에 기록하고 정상 병합 조건을 확인한다.

## 변경 전후 측정

이번 기능은 속도 최적화가 아니라 원본 의도를 잃지 않는 제품 연결이다. 직접 exact-source entry는 변경 전에 없으므로 그 처리 속도나 운전자 소요 시간을 전후 개선으로 주장하지 않는다. 기존 이력은 이미 latest를 열 수 있으므로 UI action 수를 성능 수치로 바꾸지 않는다.

- 관찰/가설: 현재 마침 응답은 full source를 버리고 이력은 최신 행을 선택한다. 새 receipt와 cold query 초기화가 기존 저장 메모 공개를 지연시키는지 사전에 관찰한다. 원본 보존 정확성은 별도 행동/저장 검증이며 시간 개선의 대체물이 아니다.
- 지표와 단위: 기존 M1의 메모 공개 클릭 직전부터 같은 원래 source/version/text와 실제 TextLayout을 확인할 때까지 elapsedRealtimeNanos(ns/ms). 실제 Hilt Main/SDK/Compose/재조회 wall-time이고 순수 DB/SDK 속도, 새 동선 속도나 운전자 성과가 아니다.
- 기준 커밋: 위 main과 같은 제품 소스에 계획/계측 준비만 포함한 commit/APK를 고정한다. SessionHistoryMemoReadMeasureTest source13bf0dca91bdb3f6ec592ef2c09f759745ad8206d5999d0d66a93863b5874c05와 하나의 frozen test APK를 A1/B/A2가 공유한다. 과거 수치/원본/계측 namespace는 덮어쓰거나 새 실행으로 재사용하지 않는다.
- 동일 입력/부하/조건: 별도 test-owned Pleos API34/arm64/2560x1440/density160/RAM4GB/가로, JDK17/debug/font1.0/light, 실제 Main RESUMED/known-own focus/display/P+UX. 원래 schema6의 불변 private backup에서 SQLite backup API로 canonical을 만들고 원래 종료13/관찰145/메모1/terminal15/선언0/active0, 전체 row/typeof/order/sequence/Room identity와 같은 nonlatest 메모/fullsource/version/catalog를 대조한다. 새 검증 AVD의 원래 runtime/grants/flags/설정/locale/timezone을 실제 확인하며 원래 장치와 같다고 추정하지 않는다. FUSED denied와 새 permission grant0을 유지한다. 요청하지 않은 dangerous grant나 provider/GPS injection으로 차이를 보충하지 않는다.
- 워밍업/반복/순서: A1 5warm+30본 → 제품 구현 → B 5warm+30본 → 같은 A APK의 A2 5warm+30본. 같은 canonical을 각 arm 전 새 owned AVD에만 복원하고 같은 schema6/version/signature의 APK만 install-r한다. 기존 schema7 장치는 사용하지 않는다. warm/main을 분리한다. 타일/network/OS/ART/JIT/열/cache는 원래 장치 데이터 삭제로 통제하지 않으며 완전히 통제하지 못한 차이로 기록한다.
- 원값/재현/제외: 원래 private backup은 쓰기 금지, 작업용 사본과 canonical/각 arm의 source/APK/hash/실제 입력/환경/전체 DB typed snapshot과 원값을 private 신규 namespace에 보존한다. 공개에는 식별자/좌표/원문 메모/로그 없이 정제한 ns/ms만 담는다. baseline과 comparison 및 별도 repeat namespace를 사용해 과거 자료를 덮어쓰지 않는다. 성공 표본은 빠르거나 느려도 모두 유지한다. 실제 endpoint/환경/source 미확인, 실패/누락/미실행은 별도로 기록하고 성공 시간으로 치환하지 않는다.

실제 A1을 수집하기 전에는 제품 코드를 바꾸지 않는다. 이후 각 arm의 원값/표본 수/중앙값/Q1/Q3/IQR/min/max와 절대 차이/관찰 변화율, 실패/누락/제외, A2 반복 편차와 한계를 비교한다. 기준0이면 상대율을 계산하지 않는다. 분포가 겹치거나 입력/조건이 같지 않으면 개선/인과적 저하/동등성/무회귀를 확정하지 않는다. 이 M1을 새 마침 복습의 성능 성과로 차용하지 않는다.

## 검증 결과

| 명령 또는 시나리오 | 현재 결과 | 근거 |
|---|---|---|
| 최신 main 기술 의존성 | 읽기 확인 | SessionFinishResult.session과 기존 SessionHistory/Room/Vehicle/UX 계약만 사용, PR74/schema7/위치 API 의존0 |
| 새 검증 환경과 A1 | 실제 완료 | 별도 소유 AVD, 고정 APK/frozen M1, warmup5와 본30 성공, native 보존/취소/복귀 플래그 및 외부 typed DB/권한/설정 exact, 원형 복원 뒤 종료 |
| 제품 구현 | 진행 중 | A1 완료 뒤 strict finish, receipt와 cold exact review, 메모리 토큰 탐색을 구현 중 |
| 검사/실제 UI/B/A2/최신 리뷰 | 미실행 | 구현 이후 같은 입력/계측/원형 대조와 실제 동작 검증 필요 |

## README 사례 비교와 남은 문제

현재 새 연결은 구현/실측/실제 검증 전이므로 기존 네 사례를 대체할 근거가 없다. exact-source handoff/cold query의 원본 경계는 기존 데이터 소유권/비동기 입력 사례와 비교해 실제 영향, 원인 증거, 대안/구현 기여와 재현 결과가 더 강할 때만 교체한다. 단순 최신성/코드량/검사 수로 교체하지 않는다. 문서/발췌 정합은 앱 실행 성능과 무관하므로 측정 적용 제외, 실제 코드/유효 링크/기존 이미지/공개 민감 정보 검사를 수행한다.

필수19/54, 선택3개 제외를 유지한다. APP06/DES06 전체, 실제 구간/위치/배경 수집, PR74 새 임시 저장 동의와 원래 PR8/10 최종 리뷰를 완료로 바꾸지 않는다. 답변 없는 새 위치 동의를 재사용하거나 반복 요청하지 않는다.

## 실제 기준 환경 준비에서 구분한 실패

첫 별도 AVD start는 시작 intent만 기록된 뒤 실제 PID/ADB 연결이 없고 로그가0바이트였으며 boot 확인이 실패했다. 해당 실패와 상태를 보존했다. 독립 프로세스 세션으로 시작하도록 검증 도구를 보강하고, 실제 이전 PID/같은 AVD 사용 프로세스/ADB serial 부재와 양쪽 포트 미사용, 같은 owner를 확인한 뒤 같은 새 userdata를 재시작했다. 두 번째 시도에서 실제 프로세스 생존/로그와 부팅/고정 user를 확인했다. 이 관찰을 앱 결함이나 A1 성공, 성능 개선으로 계산하지 않는다. 기존 schema7 AVD/사용자 자료에는 쓰기0이다.

## 첫 A1의 외부 실행 시간 초과와 불완전 표본

원격 파일 전송의 client 반환만으로 DB 완료를 확인할 수 없어 새 소유 장치에서 DB 파일 부재를 실제 readback으로 발견했다. shell-v2/raw stdin, private temp의 실제 크기/hash, writer 부재와 atomic rename/명시 ACK, host 종료 상태 및 typed readback으로 도구를 보강했고 복원과 사전 대조를 실제 통과했다. 복원 명령 완료 전 다음 검사를 호출한 별도 KeyError도 오케스트레이션 실패로 보존했다. 앱 결과로 세지 않는다.

정상 Android 설치 경로의 tilde를 검증 도구가 거부한 실패는 실제 설치가 실패했다는 뜻이 아니었다. 두 APK의 실제 고정 hash를 확인한 뒤 설치 상태만 확정했고 재설치로 덮지 않았다. 큰 APK의 host 전송 대신 기기 내 SHA-256 계산/실제 sourceDir 대조로 도구 I/O를 바꿨다. 앱/SDK 성능 개선이 아니다. source path가 진단 오류 stdout에 포함된 실수를 구분하고 이후 원문은 private 파일에만 기록한다.

첫 A1은 외부 native 호출의600초 watchdog으로 중단됐다. frozen M1의5 warm과 본27개는 성공했고, 본28번째는 preparing으로 원값이 없으며 남은2개는 미실행이다. native report는 started/최종 보존 플래그 미완료이고 JUnit 완료로 기록하지 않는다. 외부의 독립 typed DB/권한/설정 대조는 exact를 확인했다. 이 전체 arm은 기준 완료 조건5+30을 충족하지 않아 전후 비교 baseline으로 사용하지 않는다. 이미 수집한 원값/실패/누락/미실행과 한 번의 진행 JSON 읽기 시각을 별도 실패 시도에 보존한다. 유리한 성공 표본만 골라30개에 보충하지 않는다.

다음 A1/B/A2의 외부 전체 실행 상한만1800초로 맞춘다. frozen source/APK, endpoint20초, 입력/5warm+30본/실패와 제외 기준은 바꾸지 않는다. 실패한 첫 A1의 cache/host 보고서를 별도 시도로 rename 보존하고 같은 canonical을 실제 복원한 뒤 새 A1 전체를 다시 수집한다. 과거 원값과 새 완료 원값을 합치지 않으며 ART/JIT/cache/순서 차이는 계속 한계다. 재수집 완료 전 제품 GO는 false다.


## 두 번째 A1의 실제 전면 확인 실패

두 번째 A1은 frozen guard가 초기 Main의 RESUMED와 window focus를25초 안에 확인하지 못해 초기화 단계에서 실패했다. 원값0개, warmup5와 본30은 모두 미실행이며 최초 성공 arm에 보충하지 않는다. 이 단계는 graph/SDK observer/원형 native snapshot 전이어서 native cleanup 미수행 플래그를 자료 변경 증거로 해석하지 않는다. 외부 typed DB와 권한/설정 exact는 별도로 확인했다. 실제 실패 뒤 화면은 cluster 외 검은 상태였고, 전원Awake/displayON/한길PID생존에도 Main 포커스와 RESUMED를 확인하지 못했다. 원인 미확정이며 앱 회귀나 성능 수치로 계산하지 않는다.

초기화 실패만 받는 별도 명시 archive 명령은 frozen source/APK/전체 host-native JSON hash, 실패 JUnit L482/원값0개/전부 미실행과 외부 exact, writer 부재를 검사한다. host/native 원문과 실패 trace를 rename 보존하고 ACK까지 확인한 뒤에만 새 A1 marker를 예약했다. 실패 두 arm은 각각 보존하며 부분 성공 표본을 합치지 않는다.

같은 새 소유 AVD/userdata만 정상 중지했다. 직후 예약 포트가 EADDRINUSE여서 재시작이 거부된 도구 실패도 남겼다. 기존 listener나 다른 프로세스를 종료하지 않고 실제 포트 반환 후 재시작했다. 고정 두 APK hash, canonical DB typed exact와 권한/설정 두 번 대조를 통과했다. 실제 한길 Main의 own window focus/정상 UI/주차 확인과 잠금 해제, 실제 브라우저 DDS ONLINE/P를 확인한 뒤 세 번째 A1 전체를 시작했다. frozen 기준/표본 수/endpoint와 외부1800초 조건은 그대로다. 결과 수집 전에는 A1 완료나 제품 GO를 기록하지 않는다.


## 완료한 변경 전 A1

세 번째 A1의 native 결과는 completed이고 warmup5와 본30 모두 success다. 실제 endpoint/source 일치와 복귀를 확인했으며 본 측정의 실패/누락/제외0이다. 첫 두 실패 arm은 각각 별도 보존하고 합치지 않는다. 본30의 elapsedNs를ms로 환산한 중앙값2426.938980, Q1 2185.74092775, Q3 2720.9402095, IQR 535.19928175, min1992.903334, max5303.056044다. 분위수는 inclusive 방식이다. 원값30개와 워밍업은 private arm JSON에 별도 보존한다. 이는 기존 저장 메모 공개의 실제 Hilt/SDK/Compose wall-time 기준값이며 새 복습 연결 속도나 성능 개선이 아니다.

native preservedOriginalData/returnedHome/catalogPreserved/environmentPreserved/sdkObserverCancelled/automationFlagRestored를 실제 true로 확인했다. 외부 typed DB와 원래 권한/설정 exact도 독립 대조했고 restoreverify 완료 후 같은 소유 AVD만 종료했다. FUSED_LOCATION denied, 새 위치 권한 허용0을 유지했다. 기준 제품은 main과 같은 제품 소스에 계획만 포함한98347438ce96cae806cfb07f416ae57ab1543f54에서 JDK17로 빌드하고 두 고정 APK hash를 실제 설치본과 대조했다. frozen report의 declaredProductCommit은 선언/빌드 기록이며 sourceToBinaryProof=false다. 임베디드 source-to-binary 증명이나 완전히 통제한 ART/JIT/OS/cache/열 조건으로 과장하지 않는다.

완료한 A1/cleanup/restoration hash와 baseline commit, 제품 코드 변경 전 Git 상태 및 소유 AVD 종료를 확인한 뒤 private 체크포인트에 production_go=true를 원자 기록했다. 이 시점 이후에만 제품 구현을 시작했다. B/A2와 새 동작/최신 리뷰는 아직 완료하지 않았고 전후 변화나 개선율은 계산하지 않는다.


## 제품 구현과 현재 검증

strict expected-source 종료 overload는 기존 ID API/PracticeSession ABI/schema를 보존하면서 같은 Room 트랜잭션에서 물리형과 전체 원본을 확인한다. 마침 action과 ACK receipt를 분리하고, 실제 ACK와 같은 종료 Flow가 모두 확인된 뒤에만 일회성 명시 복습을 열도록 연결했다. 새 시작/종전 종료 쓰기 수락, source/reader 소실, 실제 P/UX 거부와 host 수명 이탈에서 옛 action을 폐기한다. ACK보다 먼저 오는 자기 종료 Flow는 pending ACK 검증 기회를 유지하며 Flow만으로 성공을 만들지 않는다.

기존 이력의 cold exact entry는 owner/query 생성 전에 expected source를 고정한다. null/process 원본 분실은 Unavailable/query0, 관찰한 삭제나 metadata 교체는 해당 entry의 Unavailable이며 latest/older/newer/reset을 차용하지 않는다. 일반 이력은 별도 Explore 경로로 보존한다. 앱은 full source를 메모리 token으로만 전달하며 이전 close/open은 현재 back-stack entry와 RESUMED를 확인한다. 구성 변경은 Activity VM을 유지하고 프로세스 재생성은 원본 없음으로 닫힌다.

첫 targeted 검사는 새 JVM fixture의 StateFlow에 직접 쓰려던 컴파일 오류4개로 Home 본문이 실행되지 않았다. fixture의 MutableStateFlow와 공개 StateFlow를 분리하고 같은 Home 전체 검사를 재실행해 통과했다. 실제 RoomPracticeSessionRepositoryTest15개와 신규 finish15/exact review13개는 실패/오류/건너뜀0을 XML로 확인했다. 이 숫자는 정확성 검증이며 성능 수치가 아니다. 첫 전체 verify는 앱 탐색 두 파일 포맷에서 실패했고 spotlessApply 후 JDK17 ./scripts/verify.sh 전체4단계(quality/test/debug/unsigned release)가 성공했다. 새 기기 테스트 APK :app:assembleDebugAndroidTest도 JDK17 실제 빌드 성공, 컴파일 오류0이다. 새 native/실제 Main 기본과 확대 다크/B/A2는 아직 실행하지 않았다.

README는 기존 네 사례와 순서를 유지했다. 새 연결은 ACK와 원본 전달의 제품 경계를 보강하지만 기존1의 지연된 UI와 실제 주행 경계, 기존2의 여러 연결 자료를 포함한 원자 삭제/스냅샷 비교, 기존3의 비동기 조회/렌더/실제 입력 소유권, 기존4의 재진입 가능한 StateFlow 반환 순서를 대신할 더 강한 실제 제품 근거를 아직 확보하지 못했다. 기존 M1 baseline을 새 연결의 개선 성과로 차용하지 않는다. 새 다섯 사례나 임의 점수/테스트 총수로 비교하지 않는다. 현재 구현에 추가된 exact 삭제 후 분기만 실제 코드로 갱신하고 이동한 source 줄 링크5개를 맞췄다. 펼친 실제 코드22개를 소스와 대조했으며 여섯 항목 순서/브랜드/기능/실제 기존 이미지와 사례 개수4개를 보존했다. 이 발췌/링크 정합은 앱 실행 성능과 무관한 문서 변경이므로 성능 계측 적용 제외다.


## 기기 실패를 분리하고 소유권 오류를 실제 재현한 수정

첫 run-boundary는 첫 method 시작 뒤 완료 테스트0/7 상태로 프로세스가 종료됐다. startup ANR의 main 스레드는 GC 완료 대기, ClassLinker/MethodVerifier와 Dagger Application component build에 있었다. Java FATAL/LMK나 새 복습 본문의 교착으로 확정하지 않는다. 원문/복원 전 DB를 private에 보존했고 원형 DB/권한/설정 exact 복원은 독립 통과했다. 같은 후보를 정상 실행해 실제 Main 포커스와 P/UX, 실제 브라우저 DDS ONLINE/P를 확인한 뒤 소스/검증 조건을 바꾸지 않은 명시적 새 전체7 시도를 했다.

두 번째 run-boundary는5PASS/2FAIL이다. 같은 원본의 Finished/AlreadyFinished 명시 복습과 실제 UUID Room3은 통과했지만, 캡처한 action 검증의 실제 host 확인과 metadata 교체 뒤 같은 exact entry 재진입에서 실패했다. 이를 전체 성공이나 실제 SDK/OS 수명 검증으로 바꾸지 않는다. 두 arm과 복원 전 typed snapshot, 원형 복원 결과를 각각 보존했다.

현재 cold exact flow는 captured host가 false이거나 이미 취소된 collector도 먼저 공유 reader와 published 상태를 설치할 수 있었다. 정상 A의 Ready와 상세 authority를 유지한 상태에서 거부/취소된 cold collector를 겹쳐 실행하는 JVM 회귀3개를 먼저 추가했다. 제품 코드 미수정 상태의 실제 JDK17 실행은16개 중 바로 그3개 실패/오류0/건너뜀0이었다. 공유 owner 설치 전 active Job과 captured host를 확인하고 거부 collector는 자기 Locked만 발행하게 수정했다. 취소된 collector는 공유 reader/selection/deletion/published에 쓰지 않는다. 이는 정확성 수정이며 테스트 수나 전체 Gradle 시간을 성능 성과로 쓰지 않는다. 실제 GREEN과 native 재검증은 아래 후속 결과로 판정한다.

별도 host 실패의 원인은 아직 확정하지 않았다. Boundary의 기존4 methods/expectations/실제 host 조건/timeout을 유지하고, 실패 cleanup 전에 private 단계/host lifecycle-focus-display와 controlled lifecycle/현재 history tag를 수집하는 진단만 추가했다. 원래 failure가 진단 실패에 가려지지 않게 했다. 이 controlled 진단은 실제 SDK 주행/실제 OS PAUSE 증거가 아니다.


## 소유권 수정 후 다시 확보한 검사

동일 회귀 기대를 유지한 JDK17 GREEN은 exact16개 모두 실패/오류/건너뜀0이며 Home 전체와 Room 종료 경계 명령도 통과했다. 이후 JDK17 전체 verify3의4단계가 모두 통과했다. 진단 전용 Boundary의 불필요한 onAllNodes import1개 때문에 첫 새 계측 APK 컴파일은 실패했고, import만 제거한 재빌드가 성공했다. 제품/검증 기대/조건은 바꾸지 않았다. 수정 후 새 native 전체7과 실제 Main 두 테마 및 B/A2는 아직 실행하지 않았으므로 완료로 기록하지 않는다.


## 최신 후보의 native7 통과와 실제 Main 준비 실패

제품0dd6aa6의 새 native 전체7은 모두 PASS/JUnit 완료이며 원형 DB/sequence/권한/설정 복원 exact와 새 위치 권한 허용0을 확인했다. 이전5PASS/2FAIL과 startup ANR는 따로 보존했다. 호스트 실패가 재발하지 않았다는 사실을 제품 수정으로 원인 해결했다고 확정하지 않는다. JVM RED/GREEN3은 거부/취소 collector의 공유 owner 덮어쓰기를 직접 재현하고 차단한 별도 근거다.

실제 Main 라이트 첫 시도는 UI 시작 전에 canonical-domain-baseline 비교에서 실패했다. frozen M1 원형은 기존5개 domain key 외에 courseCompletions/physicalDatabase를 포함한7개 key이고 재사용한 기존 Harness.dataJson은5개 key만 만들고 있었다. private 실제값 대조로 공통5개 key의 모든 값/타입/배열 순서와 별도 physical DB가 기준과 같은지 확인했다. 제품 데이터 손상으로 해석하지 않고 UI 시작/마침/D-P는 미실행으로 남겼다. 원문과 복원 전 DB 및 독립 원형 복원을 보존했다.

새 Main 테스트 파일만 고쳐 실제 각 세션 순서의 완료 선언 영수증과 실제 physical을 추가해7개 전체를 exact 비교한다. 원래 key를 버리거나 성공 기본값을 넣지 않았다. 기준 파일의 실제 bytes hash는 parse/비교 전에 고정해 미수행과 변경을 구분한다. 제품/기존 M1/common helper/동작 기대/timeout은 그대로다. JDK17 quality와 새 기기 APK 빌드가 통과했다. 제품 debug APK가0dd6aa6과 byte-identical인지 확인한 뒤 재실행하며, 아직 실제 Main 성공이나 B/A2 완료로 기록하지 않는다.
