# 명시적으로 마친 같은 연습의 복습 연결

- 상태: 제품 구현과 JDK17 전체 검사, 현재 C 기본 라이트/확대 다크 native 및 실제 화면 검증 완료. 다크 최초 복구 대조 실패와 별도 전체 복구 성공을 구분해 보존했으며 최종 문서 head 검사/리뷰와 병합 대기
- 요구사항: APP-06과 DES-06의 주차 후 복습 흐름 중 마침 결과와 같은 기록을 여는 연결. 실제 도로 구간, 위치 표본, 전체 항목 완료는 범위 밖이다.
- 기준 main: dd4897880b24776c1599bb18096abc5bcc0a2219

## 목표와 범위

기준 main의 SessionViewModel.finish는 Finished/AlreadyFinished에 들어 있는 종료 세션을 버리고 enum 피드백만 남긴다. 일반 이력은 진입 당시 최신 종료 기록을 고른다. 마침 뒤 다른 종료 자료가 전달되거나 원본이 바뀌면 방금 마친 대상을 그대로 복습한다는 요청을 표현할 수 없다. 마침 응답의 전체 종료 원본을 보존하고 사용자가 명시적으로 같은 기록의 복습을 열도록 연결한다. 자동 화면 전환을 추가하지 않는다.

기존 이력의 관찰, 메모, 저장 실행과 수동 마무리 열람을 재사용한다. 조회 owner를 만들기 전에 전체 expected source를 고정하고 최신 기록으로 fallback하지 않는다. 삭제, 같은 ID의 교체/재생성, active 복귀, read 실패와 종료 미확인을 나눈다. P와 UX 해제, 실제 RESUMED, reader/query/generation, 일회성 클릭과 기존 메모 CAS를 유지한다. 프로세스 재생성으로 메모리 원본을 잃으면 unavailable로 돌아가며 현재 최신 기록으로 복원하지 않는다.

새 DB/schema/SDK/권한 계약이나 배경 수집, 실제 위치/경로, 다음 과제 저장 제약 확대는 없다. 마침 쓰기에는 expected fullsource overload를 fail-closed 기본 Blocked로 추가하고 Room의 같은 트랜잭션에서 실제 원본을 비교한다. 기존 ID 기반 호출과 PracticeSession 생성자/copy ABI를 유지한다. 후반 receipt 비교만으로 이미 발생한 다른 원본의 종료 쓰기가 복구됐다고 주장하지 않는다. domain에 물리 rowId/version이 없으므로 관찰 없이 모든 논리 필드가 동일한 삭제/재삽입 ABA까지 검출한다고 주장하지 않는다. PR74의 새 명시적 임시 저장 동의는 승인됐지만 아직 소비하지 않았으며, private rebind 준비 후 실제 실행은 별도로 대기한다. 이전 동의를 재사용하거나 이 schema6 작업의 실행과 합치지 않는다. 기존 schema7 후보 AVD에 main/schema6 APK를 설치하지 않는다. 이미 설치된 Pleos 이미지로 별도 test-owned 초기 AVD만 구성하고 기존 AVD/userdata/config/snapshot을 변경하지 않는다.

## 완료 조건

- [x] Finished와 AlreadyFinished의 검증한 full ended source, 예외/취소/불명확 응답을 구분한다.
- [x] 현재 source와 실제 주차/UX/RESUMED에서만 일회성 명시 복습을 열고 옛 action을 재사용하지 않는다.
- [x] cold exact-source 이력은 최신/다른 원본을 잠깐 표시하거나 삭제/교체 뒤 다른 행을 차용하지 않는다.
- [x] 기존 일반 이력, 출처/Empty/Failed, 메모 CAS와 다음 과제의 latest/no-active 제약을 보존한다.
- [x] JDK17 전체 verify와 변경 동작의 단위/실제 Room/Compose/실제 Main 기본, 확대 다크를 확인한다.
- [x] 같은 frozen 기존 메모 disclosure 계측 A1/B/A2의 실제 원값, 원형/조건 대조와 한계를 기록한다.
- [ ] 최신 PR head 필수 CI, Codex, 실제 관련 수동 검증과 미해결 지적/정책을 확인한 뒤 병합/동기화/브랜치를 정리한다.
- [x] README 기존 네 사례와 실제 근거를 비교하고 여섯 항목/실제 펼친 코드/기능과 이미지를 보존한다.

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
| 제품 구현 | 구현 완료, 실제 UI 전체 검증 중 | A1 완료 뒤 strict finish, receipt와 cold exact review, 메모리 토큰 탐색을 연결했고 거부/취소 cold collector의 owner 덮어쓰기를 실제 RED/GREEN으로 수정 |
| JDK17 전체 자동 검사와 native 경계 | 기록된 실행 통과 | 전체 verify와 실제 Compose/Room 경계 통과를 확인했다. 과거 실패와 수정 전후, 이후 C-only 품질/기기 빌드는 아래에 분리 |
| 실제 Main 기본/확대 다크 | C027 기본 라이트 전체 완료, 확대 다크 미완료 | 실제 UI 시작/마침/같은 원본 복습, D 잠금/P 새 선택, 구성 재생성과 원형 보존을 확인했다. 다크 시작 실패와 최초 입력 전 허용 조건 소실은 별도 보존했다. 강화된 C는 현재 상태 진단과 더 엄격한 첫 입력 전 획득만 바꾸며 두 테마를 다시 검증한다 |
| 고정 M1 A1/B/A2 | 실제 완료, 성능 효과 미확정 | 세 유효 arm 각각5 warm/30 main과 원형/권한/설정 대조 완료, 실패/누락/제외0. 실패한 두 A1과 첫 A2는 별도 보존했고 같은 A 반복 편차를 코드 개선으로 해석하지 않는다 |
| 최신 PR 검사/리뷰와 병합 | 최종 head 판정 대기 | 과거 head의 검사/리뷰 결과는 보존한다. 최신 C027 두 테마와 최종 문서 반영 뒤 최신 필수 검사/리뷰를 별도 확인하며 병합은 아직 완료하지 않았다 |

현재 고정 M1의 본30 중앙값은 A1 2426.938980ms, B 933.1852715ms, A2 928.8730215ms다. A2는 A1과 같은 제품 APK이며 A2-A1도 -1498.0659585ms로 크게 달라졌다. B-A1 -1493.7537085ms를 코드 성능 향상으로 확정하지 않는다. B-A2는 A2를 분모로 +4.31225ms / +0.4642453705%이며 B/A2의 IQR은 겹친다. 각5 warm과 본30의 원래 ns, inclusive 분포, 실패 시도와 순서/ART/cache/환경 한계는 [측정 기록](../../development/measurements/finish-source-review-memo-open-results.md)에 분리했다. 이는 기존 메모 펼침 관찰이며 새 마침 복습 동선의 전후 성능 측정이 아니다.

## README 사례 비교와 남은 문제

새 마침 원본 연결과 무효 cold collector 차단 근거를 기존1의 일반 주차/UX fencing 설명과 비교해 사례1을 교체했다. 사용자 의도를 DB fullsource CAS, ACK/관찰 순서, 조회 소유권과 실제 탐색까지 연결한 근거를 선정했으며 나머지2/3/4의 내용과 순서는 유지했다. 단순 최신성/코드량/검사 수로 선정하지 않았고, 앞선 native 재진입 실패의 유일 원인으로 collector 결함을 확정하지 않는다. 여섯 항목/실제 연속 코드/현재 소스 링크와 기존 이미지는 보존했으며 최종 문서 리뷰는 최신 C027 결과와 함께 별도 확인한다. 문서/발췌 정합은 앱 실행 성능과 무관하므로 측정 적용 제외다.

필수19/54, 선택3개 제외를 유지한다. APP06/DES06 전체, 실제 구간/위치/배경 수집과 원래 PR8/10 최종 리뷰를 완료로 바꾸지 않는다. PR74의 새 임시 저장 동의는 승인됐고 아직 소비하지 않았으며 private rebind 후 실제 실행은 대기다. 이 승인을 이미 끝난 실행이나 APP 전체 완료로 해석하지 않는다.

아래 준비와 실패 기록의 미실행/대기 표현은 당시 실행 시점의 상태다. 현재 완료 범위와 남은 판정은 위 요약 및 마지막 고정 M1 결과와 구분한다.

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


## 실제 Main 라이트의 제목 레이아웃 판정 조사

c764 후보의 실제 Main 라이트 재시도는 실제 UI로 연습 시작, SDK P 기록 확인, 마침, 명시 복습과 같은 종료 원본 확인까지 진행했다. 이후 history_course의 기존 assertTextFits가 실패했다. 실제 진단은 width158px/height38px/1행, overflow_width=true, overflow_height=false, 생략 행0이다. 진단 화면에서 제목 전체가 보이므로 사용자에게 보이는 실제 glyph 잘림까지 확정하지 않는다. D/P와 구성 재생성 이후 단계는 이 실행에서 미실행이다. 원형 typed DB/sequence와 권한/설정 exact 복원은 별도로 통과했고 새 위치 권한 허용0이다.

변경 전에 확보한 Compose 1.9.4 소스 근거에서 String Text의 실제 Paragraph와 GetTextLayoutResult가 만드는 별도 MultiParagraph의 폭이 다를 수 있다. size.width와 multiParagraph.width 비교만으로 실제 glyph 잘림인지 판별할 수 없다. 다음 실행은 새 C 테스트의 private report에 기존 size/constraints/paragraph/intrinsic 폭/행 및 glyph 경계를 진단으로만 추가한다. 제품, frozen M1, 공통 helper와 기존 실패 assertion/timeout은 바꾸지 않는다. 실제 glyph가 clip/viewport 범위를 벗어나는지 같은 입력과 기본1.0/라이트 조건에서 확인한 뒤에만 제품 또는 검증기의 원인을 구분한다. 이는 정확성 진단으로 앱 실행 성능 변경이 아니며 진단 소요 시간이나 통과 개수를 성능 개선으로 쓰지 않는다.


실패 조건을 그대로 둔 진단 재실행에서도 같은 지점에서 실패했고 원형 DB/sequence/권한/설정 exact 복원이 성공했다. 실제 제목 node는158×38px, 재구성한 MultiParagraph 폭1440px, maxIntrinsicWidth158px이었다. 모든8개 UTF-16 범위와 단일 행 전체가0..158×0..38px 안에 있고 생략/행 초과가 없었다. 따라서 이 입력에서 hasVisualOverflow의 폭 비교는 실제 글자 잘림이 아닌 검사기 오판이다. 같은 Compose1.9.4의 String Text semantics 재구성 경로와 정합한다.

다음 수정은 C 테스트 전용으로 실제 문자열 일치/모든 line 범위의 연속 coverage/행 초과와 생략 없음/유한한 행 및 비공백 문자 경계를 확인한다. 기존 scrollVisible/실제 host/viewport 검사는 유지한다. 이미 고정한 M1의 textGeometryFits와 같은1px 픽셀 반올림 허용 범위를 적용하고 누락/NaN/0크기/행 초과/ellipsis/coverage 누락은 실패로 둔다. frozen M1과 기존 공통 helper, 제품과 글꼴/여백은 변경하지 않는다. 이는 실제 성능과 무관한 검증기 정확성 수정이며 과거 두 native 실패를 성공으로 바꾸지 않는다. 기본 및 확대 다크 전체 흐름은 다시 수행하고 D/P와 최종 복원까지 실제 완료를 요구한다.


## 실제 근거 확보 뒤 README 사례 선정 변경

실제 Main 라이트의 명시 복습/구성 재생성/SDK D 잠금과 P의 새 공개, 원형 자료 보존까지 확보한 뒤 기존 네 사례를 다시 비교했다. 새 사례는 기존1의 일반적인 최신 P/UX와 callback fencing 설명보다 마침 A라는 사용자 의도를 DB fullsource CAS, ACK/관찰 순서, cold reader 입장과 실제 탐색까지 연결하며, 무효 collector가 정상 reader를 빼앗는 결함의 직접 재현/차단 근거가 있다. 이를 기존1과 교체한다. 단순 최신성, 코드량, 검사 수가 이유가 아니다. 실제 도입 전 같은 복습 버튼이 다른 기록을 열었다고 쓰지 않는다. 그 버튼은 없었고 기존 ACK가 원본을 버린다는 연결 부재와 구현 중 직접 발견한 collector 결함을 구분한다. 앞선 native cold 재진입 실패의 유일 원인으로 이 결함을 확정하지 않는다.

기존2의 원자 다중 자료 삭제와 snapshot/CAS, 기존3의 조회/렌더/실제 입력 소유권, 기존4의 실제 내비 전면/FGS/저장 재개와 반환 순서 문제는 서로 다른 경계와 직접 근거를 갖고 있어 내용과 순서를 유지한다. 새 사례는 여섯 항목 순서와 실제 연속 코드/현재 소스 링크를 사용한다. 실제 라이트만 확인된 시점의 결과를 다크/실제 위치/운전자 효과까지 확대하지 않으며, 전후 성능 미측정을 명시한다. 기존 메모 M1은 새 동선 속도가 아니므로 차용하지 않는다. 관련 정확성 근거 도구의 네 사례 대상도 새1의 finish/exact reader/Room 경계로 갱신하고 기존 주차 회귀는 legacy로 보존한다. README에는 검사 목록/개수/CI 집계/미측정 성과표를 넣지 않는다. 문서와 근거 매핑 변경은 앱 성능 변경이 아니므로 실측 적용 제외이며, 실제 코드 일치/유효 링크/구조/민감 정보와 도구의 보고서 분류를 검증한다.


## 실제 라이트 완료와 다크의 준비/제어 실패 분리

제품0dd6aa6과 byte-identical한 f92 후보의 실제 Main 라이트 전체는 native JUnit/완료 영수증과 독립 원형 DB/sequence/권한/설정 exact 복원까지 통과했다. 실제 UI 시작/마침/명시 같은 종료 원본 복습, 구성 재생성 뒤 원본 유지, DDS D의 SDK 수신과 상세 잠금, P 복귀 뒤 이전 상세 미공개와 새 선택을 확인했다. 이전 준비/레이아웃 실패는 그대로 보존한다.

확대1.3/다크 첫 실행은 초기 실제 전면이 다른 앱이어서 UI 시작 전에 실패했다. 두 번째는 실제 시작/마침/같은 원본/구성 재생성/D 잠금까지 갔지만 첫 P 제어가 낙관적 화면 응답 뒤 실제 DDS D로 되돌아가90초 P 수신 대기에서 실패했다. root가 나중에 새 D 응답을 관찰하고 P를 다시 보내 지속된 PARKED 응답을 확인했으나 제한 시간 뒤이므로 성공으로 바꾸지 않는다. 세 번째는 원형 snapshot 이후 실제 UI 입력0/새 세션0인 초기 P/UX 획득 뒤 foreground 확인에서 실패했다. SDK 구독이 focus 소실을 일으켰다는 인과는 확정하지 않는다. 공통 awaitAllowed는 allowed 획득 직후 foreground를 한 번 검사하는 구조다.

다크 뒤 첫 외부 복원은 font_scale이 원래 null 대신 유효 기본값1.0으로 남아 exact 비교에서 실패했다. 원래 실패 영수증을 보존하고 별도 명시 restoreverify를 다시 실행해 원형 DB/sequence/권한/설정 exact 복원을 확인했다. native/첫 cleanup 실패를 재분류하지 않는다. 별도 측정용 platform-preflight를 원래 null 설정에서 잘못 호출한 준비 오류는 실제 원형 두 번 대조로 구분했다.

다음 C-only 수정은 최초 UI 조작 전 조건 획득을 명시한다. 처음 캡처한 같은 Main/display를 다시 포착하거나 자동 refocus하지 않고,90초 절대 제한 안에서 현재 P+UX/RESUMED/실제 window focus/known-own package를 모두 관찰해야 입장한다. unknown/다른 package/host 미준비/gate 거부는 실제 횟수로 기록하며 조건 충족으로 대체하지 않는다. 최종 strict requireHost/live gate 확인 뒤 원형 자료/owned0을 다시 대조하고 첫 입력을 허용한다. 최초 h.assertEnvironment의 실제 다른 앱 거부, 이후 구성 재생성/D/P 대기/모든 입력과 지속 소유권 검사는 그대로 둔다. 이는 입력 전 준비 획득이며 조작 중 전면 이탈을 허용하는 변경이 아니다. 제품/common/frozen M1은 바꾸지 않으며 성능 변경이 아닌 검증 준비의 정확성 수정이다.

B는 고정 계측 APK와 실제 후보 APK, 원형/측정 환경 대조 및 정상 실행 준비까지만 수행했고 native 측정/표본0이다. 창 선택/포커스 정보와 달리 실제 화면이 기본 내비 loading Surface에 남아 측정을 시작하지 않았다. 현재 소유 app UID/실제 APK를 확인한 비파괴 로컬 JDI 관찰에서 main은 MessageQueue.nativePollOnce/Looper 대기였으며 앱 무한 반복이나 지속 Binder 차단을 확정하지 않는다. main 재개/디버거 분리/정확한 임시 loopback 포워딩 제거를 확인했다. root가 필요한 debuggerd 명령은 확대 권한을 사용하지 않고 중단했다. adb jdwp의 streaming 출력을 일회 조회로 사용한 도구 timeout도 앱 결과가 아니다. 원형과 측정 설정 exact 대조 후 소유 AVD만 userdata를 보존해 종료했다. 재부팅/재준비는 별도 실행 조건으로 기록하고 표본이나 과거 A1과 섞지 않는다.

계획 기록/patch 준비의 UTF-8 stdin 오류 때문에 첫 C 적용은 미실행이었다. 그 뒤 실제 실행된 전체 verify4와 native build는 C0e718 및 새 README/근거 매핑에 대한 성공으로 구분한다. 아래 C 적용 뒤 quality/새 native APK를 다시 검증하며, 이전 빌드를 새 C의 성공으로 바꾸지 않는다.


수정한 초기 admission C 소스의 JDK17 quality와 새 native APK 빌드는 각각 통과했다. 제품 debug APK는 계속0dd6aa6과 byte-identical한지 설치/등록 때 별도로 대조한다. 앞선 전체 verify4는 README 새 사례와 근거 매핑을 포함한 네 단계 성공이고, C 초기 admission은 그 이후 품질/기기 빌드로 구분한다. 새로운 C 실제 라이트/다크 실행과 B/A2는 아직 완료하지 않았다.


## A2의 SDK 조건 소실과 한 번의 전체 재시도 계약

첫 A2는 warmup5와 본17개를 완료한 뒤 본18 준비 단계에서 ComposeTimeoutException으로 실패했다. 계측 시작 전이어서 실패 표본의 elapsedNs/elapsedMs/elapsedWaitNs/observedEndpointElapsedNs는 모두 null이고 남은12개는 미실행이다. 진단은 같은 Main RESUMED/focus/display/known-own, hostIntervened=false지만 차량 DISCONNECTED/gear Unknown/주차 UNKNOWN, UX UNRESTRICTED와 history_locked1이었다. UX만으로 주차를 추정하지 않고 잠금을 유지한 실제 결과이며 앱 성능 저하나 무한 반복의 증거로 바꾸지 않는다. native 원형/복귀/catalog/환경/SDK observer 취소/automation flag 복원과 외부 typed DB/측정 설정 exact는 모두 확인했다.

앞의17개를 다른 arm에 보충하거나 실패 시간을0으로 채우지 않는다. 실제 raw/로그/cleanup, 한 번 읽은 live 상태와 SHA/mtime를 별도 failed arm에 보존한다. 고정 source13bf/같은 test APK/A APK/입력/표본 수/20초 endpoint/1800초 외부 제한은 유지한다. 독립 임시 archive helper는 이 정확한 최초 SDK 조건 소실 실패, A1/B 완료와 동일 입력, writer 부재와 실제 APK/native raw hash를 확인한 뒤 host/native rename과 ACK를 모두 확인해야만 A2 marker2를 예약한다. 어떤 중간 실패도 옛 leg를 차단한 채 journal을 남기며 완료 자료를 덮거나 추가 재시도를 자동 호출하지 않는다. 원형과 측정 설정 대조 뒤 동일 소유 AVD만 userdata를 보존해 새 부팅하고 실제 Main/P/UX/DDS 조건을 다시 확보한 전체5+30 한 번을 수행한다. 유효 A1/B의 fresh boot와 재시도 arm의 boot/ART/cache/순서 한계를 분리하며 causal 개선/동등성/무회귀를 확정하지 않는다.


## 완료한 고정 메모 관찰과 성능 해석

세 유효 arm의5 warm/30 main이 실제 완료됐고 native/외부 원형 보존도 대조했다. 실패한 두 A1과 첫 A2는 그대로 보존하고 합치지 않았다. A1 중앙값2426.938980ms, B933.1852715ms, 같은 A APK의 A2 928.8730215ms다. A2도 A1보다 크게 달라져 B-A1의 약61.55% 관측 감소를 코드 성능 개선으로 확정하지 않는다. 새 마침 복습 동선은 성능 전후 미측정이다.

정제한 원래 ns, 워밍업/본 분리, inclusive 분포, 절대 차이/관측 변화율, 실패/누락/미실행과 fresh boot/ART/cache/순서/검증 비용 포함 한계는 [측정 기록](../../development/measurements/finish-source-review-memo-open-results.md)에 기록했다. 입력/원형/계측/실제 APK 대조는 완료했고 개인정보/실제 위치/원문 메모/로그는 공개하지 않았다. 최신 C의 실제 기본/확대 다크와 PR 최신 검사/리뷰/병합은 아직 완료하지 않았다.


## 최신 C027 라이트 통과와 다크의 실행 전 준비 보강

최신 C027 라이트는 실제 Main UI 시작/마침/같은 원본 복습, 구성 재생성, 실제 SDK D 잠금과 P 새 선택까지 native 전체 완료했다. 같은 실행의 원형/권한/설정 외부 exact와 새 위치 권한 허용0을 확인했고 실제 화면도 대조했다.

최신 C027 다크 첫 시도는 h.assertEnvironment의 최초 전면 앱 검사에서 known-other를 감지해 L312에서 실패했다. 원형 native 초기화, SDK observer와 제품 UI 입력 전에 중단됐으므로 원값/제품 동작0이며 native 미초기화 cleanup 플래그를 보존한다. 외부 원형 DB 복원은 확인했으나 font_scale 원래 null 키의 첫 exact 복원은 실패했다. 해당 실패를 고치지 않고 별도 restoreverify에서 원래 키/권한/설정과 원형 exact를 다시 확인했다.

다음 명시적 전체 다크 실행 전에 private 외부 준비만 보강한다. 기존 QA verify_data가 Main을 종료한 뒤 font/night를 적용하고 바로 instrumentation을 시작하던 순서 사이에 정상 Main launch를 추가한다. 같은 소유 기기/고정 user와 APK를 검사한 뒤 고정 Main의 am start -W만 호출하며, 그 반환을 실제 전면/SDK P/UX 성공으로 사용하지 않는다. instrumentation 시작 뒤 재실행/재포커스/자동 재시도는 없고 native 최초 known-other 거부/25초, C027 실제 admission/조건/본문/timeout은 바꾸지 않는다. 관찰한 초기 준비 실패의 원인을 제품 결함 해결로 과장하지 않는다. 앱 실행 성능의 개선이 아닌 외부 검증 준비이므로 M1에 합치지 않는다.


정상 Main 사전 실행을 추가한 다음 C027 다크 시도는 instrumentation이 numtests/본문/원본 native 보고서를 만들기 전에 Process crashed로 종료됐다. 제품 UI/SDK 검증 성공으로 세지 않는다. native 보고서 부재와 첫 font exact 복구 실패를 보존했으며 별도 restoreverify는 전체 원형/권한/원래 설정 exact를 확인했다. 진단 수집 첫 보조 호출은 private helper에 Path를 전달한 TypeError로 실패했고 원래 adb 진단은 helper의 private evidence에 이미 보존됐다. 도구 오류를 앱 원인으로 해석하지 않는다. frozen/제품/계측 소스와 기대를 유지하고 동일 test-owned AVD/userdata만 정상 중지/재부팅한 뒤 새 전체 다크를 시도한다. 보호 기기/데이터 삭제/새 권한/타 프로세스 종료는 없다.


## 다크 첫 입력 전 조건 소실과 강화할 관찰

같은 userdata의 새 부팅 뒤 C027 다크는 최초 admission을127ms/1표본에서 통과했다. 당시 고정 Main/display/실제 전면과 SDK P+UX가 모두 true였다. 그러나 첫 session_start 입력 직전 touch의 현재 liveAllowed 검사 L424가 false여서 중단됐다. uiStartRequested는 준비 표시이며 실제 UI 시작/새 source는 발생하지 않았다. native 원형/typed rows/catalog/environment/observer 취소/automation flag 보존을 모두 확인했고 외부 원형 exact, 별도 원래 설정 복구 exact도 확인했다. 실제 실패 화면의 P 표시는 과거 UI 상태일 수 있으므로 현재 허용의 근거로 사용하지 않는다.

아직 실패 순간의 vehicle/UX 값을 확보하지 못했으므로 연결/주차/UX 중 무엇이 변했는지와 원인은 미확정이다. 다음 C-only 수정은 cleanup 전에 현재 vehicle/UX/liveAllowed와 고정 host의 실제 상태를 private 진단으로 남긴다. 최초 입력 전 acquisition은 같은90초 절대 제한/100ms poll 안에서 고정 host/display/known-own/P+UX가 연속1000ms 유지돼야 통과하도록 더 엄격하게 만든다. unknown/false가 하나라도 나오면 연속 구간을 초기화한다. 본문 각 입력의 즉시 host/P+UX 검사, timeout과 원본/실제 SDK 기대는 바꾸거나 실패 뒤 재시도하지 않는다. 초기 조건 확보의 강화이며 앱/SDK 결함을 해결했다고 주장하지 않고, frozen M1 원값/계측/제품 코드에 적용하지 않는다. 실제 성능/사용 흐름 변경이 없는 검사 진단/준비이므로 앱 성능 전후 계측 적용 제외다.

JDK17 전체 verify5는 최신 C027과 제품/README 근거 도구에서 quality/test/debug/unsigned release의 네 BUILD SUCCESSFUL을 확인했다. 이후 위 C-only 변경은 별도 품질/계측 APK 빌드와 실제 두 테마 재검증으로 추적한다.


강화된 C source 6e324dc4c26ec0495029e5f91d1f824385104d3841b61513a1ded173ea1224b9는 독립 소스 검토에서 새 구체 결함이 없었다. 진단은 coroutine 자식 observer가 구조적으로 취소되기 전 body catch에서 확보하고, 원래 예외를 유지한다. JDK17 quality와 실제 app debug/AndroidTest APK 빌드가 통과했다. 제품 APK는 기존0dd6aa6과 byte-identical이며 frozen M1 source/APK/완료 원값은 보존한다. 이 소스 검토/빌드가 새 실제 두 테마나 최신 PR 리뷰 성공을 대신하지 않는다.


## 강화된 C의 실제 전면 소실 확인과 명시적 준비 단계

83a54ee 다크는 첫 입력 전90초 동안571번 모두 같은 Main이 RESUMED/유효한 같은 display였지만 window focus=false이고 알려진 다른 앱이 focused였다. 같은571번의 SDK 허용은 true였고, observer unwind 전 진단에서도 실제 CONNECTED/PARK/PARKED/UNRESTRICTED와 observer active=true였다. 이번 실패 조건은 전면 소실이며 SDK P/UX 소실로 해석하지 않는다. 플랫폼이 왜 전면을 바꿨는지는 미확정이다. 실제 제품/준비 입력0, native 보존6개는 true이며 원형 외부 exact와 별도 원래 설정 복원은 따로 확인한다.

이전 private 준비 계약의 '초기 acquisition 중 외부 재포커스 없음'은 사용자의 금지가 아니라 검증자가 둔 제한이었다. 실제 다른 앱 전면을 확인한 이번 근거에 따라 다음 명시적 전체 시도 전에 변경한다. C-only 첫 제품 입력 전 환경 준비 단계와 단일 root ACK를 추가한다. 이 단계는 원래90초 절대 상한 안에 포함하며 새 host/display를 캡처하지 않는다. root는 실제 전면/화면을 읽고 필요하면 시스템 독에서 한길을 최대1회 선택한 뒤 같은 디렉터리/시도의 ACK만 전달한다. 준비 중 제품 버튼/DB 쓰기/권한 변경은 없고 ACK 뒤에는 재포커스/재실행/재시도하지 않는다. C는 ACK 뒤에도 같은 host/display/현재 실제 전면과 P+UX의1초 표본 안정 및 이후 모든 입력의 strict 조건을 자체 확인한다. ACK/명령 반환을 SDK나 전면 성공으로 대신하지 않는다.

기존83 source는 acquiring/admitted 상태를 finally에만 저장해 외부에서 첫 준비 입력 전임을 실시간 증명할 수 없었다. 단순 uiStartRequested 부재는 준비 UI가 먼저 수행될 수 있어 근거가 부족하다. 새 acquiring/ACK/admitted 영수증을 첫 준비 버튼보다 먼저 남겨 외부 준비와 본문을 명확히 구분한다. 불명확하거나 ACK 뒤 외부 입력이 겹친 시도는 유효 성공으로 계산하지 않는다. frozen M1/제품/실제 SDK/D/P/body 기대와 timeout은 유지하고, 앱 성능 개선으로 기록하지 않는다.


ACK 대기용 C에는 원래 elapsed 시작/절대 deadline과 캡처한 display를 그대로 private 보고서에 추가했다. 외부 도구는 시각이나 display0을 추정하지 않고 이 값을 실제 readback과 대조한다. 첫7afe 계측의 JDK17 quality/기기 APK 빌드는 통과했고, 이 metadata 추가 뒤의 현재 파일은 다시 빌드한다. ACK 뒤 금지 대상은 앱 재포커스/재실행과 제품 UI 입력이다. 기존 본문이 명시 요청하는 실제 브라우저 DDS D/P 전환은 별도 허용된 시나리오로 유지하며, 모든 외부 입력이0이었다고 기록하지 않는다. 원래 제품/상세 잠금과90초 상한은 바꾸지 않는다.


최종 C의 private 메타데이터/진단명 정합 뒤 JDK17 quality와 app debug/AndroidTest APK 빌드를 다시 통과했다. 첫 입력 전 단일 ACK companion은 별도 순수 검사23개(기존 cleanup6, 준비 경계17)를 통과했으며 실제 run-as ACK/WM dump/runtime은 아직 검증하지 않았다. app/SDK 성능이나 실제 앱 성공의 대체 근거가 아니다. 실제 네이티브 실행에서 acquiring/ACK/admitted를 구분해 확인하고, 필요한 시스템 독 선택은 ACK 전 최대1회, ACK 뒤에는 앱 재포커스/제품 입력 없이 기존 명시 DDS D/P만 수행한다.


## 최신 첫 입력 전 실패와 독립 작업 인계

최종91e9025 다크는 첫 준비 phase/ACK에 도달하기 전에 초기 Main AX focused 창을 확인하지 못해 실패했다. SDK observer와 제품/준비 UI 입력, 새 원본, ACK, 위치 권한 허용은 모두0이다. 진단의 초기 DISCONNECTED/UNKNOWN은 observer 시작 전 값이므로 실제 SDK 상태 소실의 근거로 사용하지 않는다. 이후 정리 세 명령은 실제 am get-current-user 호출에서 activity 서비스 Broken pipe와 서비스 부재로 실패했다. ADB 콘솔과 getprop은 응답했고, 시스템 서비스의 일시 사용 불능은 확인했지만 원인이나 system_server 종료/재시작을 확정할 로그는 없다.

실패 직후 복원 전 typed JSON은 canonical snapshot과 전체 dict exact였다. 이후 원형 복사/설정 복구의 초기 실패도 보존했고, 같은 소유 AVD/userdata만 정상 종료/새 부팅한 뒤 원형/전체 권한/원래 설정을 독립 두 번 exact 대조했다. 실제 복구와 그 후 정상 종료를 확인했으며 실패한 native 시도를 성공으로 바꾸지 않는다. 원래 보호 AVD와 자료는 건드리지 않았고 새 위치 권한 허용0을 유지했다.

제품 기본 라이트와 고정 M1 완료는 유지하되 최종 다크, 새 ROOT_ACK runtime과 전체 병합 조건은 미완료다. 같은 환경 준비 실패를 전체 후속 작업의 대기로 확대하지 않는다. PR74는 현재 검토된 dd9895b와 별도 새 빈 schema7 기기에서 승인된 실제 SDK 임시 저장 검증을 진행할 수 있으며 PR75 API를 호출하거나 병합에 의존하지 않는다. PR75의 현재 head/source/APK/실패/복구/꺼진 userdata와 도구를 보존하고 독립 작업 뒤 관련 수동 검증을 이어간다. 새 근거가 기존 다른 사례보다 강하지 않아 README의 정확4개와 기존 선정/순서를 유지한다.


## 2026-11-02 현재 C 검증 재개 계획

최신 Notion 체크리스트와 두 checkout의 Git/ignored 상태, 저장소 지침 및 PR74/75 리뷰를 다시 확인했다. PR75의 현재91e9025는 main의 schema6 계약에서 시작한 독립 변경이며 PR74의 미병합 위치/schema7 구현을 포함하거나 호출하지 않는다. 위치 준비 도구와 별도 추가 진단1회는 재사용하지 않는다. 위치 권한 허용, 좌표 읽기/저장, JDI 없이 기존 소유 PR75 AVD/userdata와 실제 설치 후보를 대조해 최종 C의 기본 라이트와 확대 다크를 한 번씩 검증한다. 실패 시 해당 시도의 원형과 플랫폼 복구 결과를 따로 기록하고 본문을 자동 재시도하지 않는다.

현재 C의 첫 입력 전 ROOT_ACK 준비는 실제 acquiring 영수증/절대 deadline/같은 display를 확인한 뒤 필요 시 시스템 독 선택 최대1회와 단일 ACK만 허용한다. ACK 뒤에는 제품 UI 재포커스/재실행 없이 native가 실제 P/UX/전면 안정과 모든 입력 조건을 확인한다. 본문이 요청한 브라우저 DDS D/P는 실제 브리지 online/선택 상태와 native 단계로 구분한다. 미확인 전면이나 빈 UI 계층은 성공으로 사용하지 않는다. 기존 보호 기기와 PR74 기기는 건드리지 않는다.

이 실행은 기존 제품 동작의 정확성과 새 C 준비/해제 경계를 검증하며 제품/기기 SDK 성능을 바꾸는 구현이 아니다. M1과 다른 입력/테마/준비 조건의 시간을 성능 개선 표본으로 만들지 않는다. 제품/native 소스를 바꾸기 전 필요성이 생기면 별도 가설/측정 계약부터 정한다. 실제 C 두 테마, 원형/전체 권한/원래 설정, 최종 head CI4개/Codex와 충돌/정책이 모두 확인되기 전에는 PR75 전체 완료나 병합으로 기록하지 않는다. README 네 사례는 실제 새 근거를 얻은 뒤 비교하며, 근거가 더 강하지 않으면 그대로 보존한다.


### 첫 ROOT_ACK runtime 실패와 UID 경계 수정 계획

현재 C 라이트는 실제 첫 입력 전 preparation phase에 도달했지만 단일 ACK 전달이 실패했다. ACK 예약/실패 기록과 native 보고서를 보존하며 같은 시도의 ACK를 재시도하지 않았다. native는90초 동안 waiting 상태였고 준비/시작/마침 UI 입력0이다. 종료 시 Main RESUMED와 focus는 true였으나 SDK는 CONNECTING/gear와 주차 UNKNOWN/UX UNRESTRICTED여서 이 값을 P/UX 성공으로 대체하지 않는다. native 보존 플래그와 외부 전체 canonical/권한/원래 설정 복구를 별도 확인했다.

실제 ACK shell의 awk 파일 읽기 실패와 같은 표현식의 host 성공을 구분했다. 이후 같은 기기의 읽기 전용 대조에서 shell UID는 /proc/uptime의 숫자 시간을 반환했으나 앱 run-as UID는 returncode2/출력0으로 실패했다. 이를 근거로 현재 ACK private script 안의 시간 조회가 잘못된 UID에 놓인 것으로 좁혔다. 위치/JDI/권한/제품 구현 문제로 해석하지 않는다.

수정 전 순수 companion 검사에 모든 앱 UID script의 시간/현재사용자 API 금지와 shell 시간 검사 두 번을 검증하는 회귀를 추가해 RED를 확인한다. 수정은 시간/고정 사용자 확인을 shell UID의 app-private 원자 commit 호출 전후로 옮기고, stdin payload가 앞선 시스템 조회에 소비되지 않도록 분리한다. 앱 UID는 검증한 새 디렉터리에 같은 네 필드 ACK의 no-overwrite 임시 쓰기/hash/원자 rename만 수행한다. 부모 shell이 원래 절대 deadline 안에서 전체 commit의 반환을 확인해야 성공 영수증을 내며, native의 원래90초/1초 안정 및 모든 입력 strict 검사는 그대로다. commit이 늦거나 불명확하면 실패/재시도 없음이며 native가 늦은 ACK로 입력을 시작할 수 있게 바꾸지 않는다.

GREEN과 source/pin 대조 뒤 새 namespace의 명시적 C 라이트/확대 다크를 실행한다. 기존 실패를 성공 자료와 합치지 않는다. phase를 관찰한 동일 root companion은 실제 같은 시도/원본/현재 Main을 재확인해 독 선택0의 단일 ACK만 즉시 전달하며, 실패 시 재시도하지 않는다. 전면이 다른 앱이면 자동 재포커스나 ACK를 하지 않는다. 본문의 실제 DDS D/P 전환만 root가 수행한다. 검증 도구의 UID 정확성 수정이므로 앱 실행 성능 전후 측정 적용 제외이고 제품/native 기대, APK와 M1 원값은 보존한다.


### 현재 창과 과거 ANR 창의 분리, 화면 제어 차단

시계 UID 수정 뒤 라이트의 새 시도는 numtests/본문/보고서 전에 Process crashed로 종료됐고 원형과 플랫폼 복구는 성공했다. Java crash buffer에서 해당 앱의 새 예외를 확보하지 못했으며 호스트 자원 관찰만으로 원인을 확정하지 않는다. 확대 다크는 실제 preparation에 도달했지만 ACK 예약/쓰기 전에 현재 창 metadata 검증에서 거부됐다. 동일 시도의 ACK를 다시 쓰지 않았고 native 입력0/원래90초 대기 실패로 보존했다. 최초 복구 판정 실패 뒤 독립 restoreverify는 전체 원형/원래 권한/설정 일치를 확인했다.

정확한 두 transport 결과를 재생하니 WindowManager 전체 출력에는 과거 LAST ANR과 현재 출력의 top-focused-display 값이 함께 있었고 display section도 중복됐다. 기존 전역 검색은 이를 현재 미확인으로 판정했다. 과거 영역을 제외한 실제 현재 창은 여전히 Main이 아닌 다른 창이므로 이 거부를 성공으로 바꾸지 않는다. 현재 P/UX가 true였던 native 값도 실제 전면 성공을 대신하지 않는다. 별도 순수 lock 대조에서 같은 프로세스의 실행 guard는 통과했으므로 lock 원인 가설은 제외했다.

[AOSP Android14 WindowManagerService](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/services/core/java/com/android/server/wm/WindowManagerService.java)의 ANR 저장과 기본 dump 순서를 대조했다. 과거 기록은 windows/display를 저장하며 기본 출력은 LAST ANR 뒤에 현재 policy/display/windows를 출력한다. 수정 전 현재 창 해석 회귀에 과거의 다른 창+현재 Main 허용, 과거 Main+현재 다른 창 거부, 누락/중복 history-policy 경계와 실제 현재 중복/timeout 거부를 추가해 RED부터 확인한다. 유일한 정규 LAST ANR 시작과 다음 유일한 정규 policy 경계가 모두 확인될 때만 과거 영역을 제외하고 기존 실제 display/고정 user/Main/포커스/root-task/resumed 검사를 그대로 유지한다. 경계가 모호하면 실패하며 과거 창이나 임의 마지막 값을 현재에 빌려주지 않는다. private metadata parser 수정이므로 앱 성능 전후 계측 적용 제외다.

실제 화면을 직접 확인하지 못해 새 native 시도/독 선택을 완료하지 않았다. 마지막 미확인 항목은 실제 화면과 관련 수동 검증으로 유지하고 이전 실패의 원인을 소급해 확정하지 않는다. 화면 제어가 복구되기 전 새 native 시도/독 선택을 시작하지 않고, 원형 복구된 소유 PR75 AVD를 정상 종료해 보존한다. source/JDK 검사와 기록을 마친 뒤 실제 화면의 수동 재개 조건만 인계한다. 제품/native 기대/소스/APK/M1 원값과 README 네 사례는 유지한다.


### 2026-11-02 검증 결과와 안전한 수동 인계

현재91e9025에서 JDK17 전체 ./scripts/verify.sh가 exit0과 네 BUILD SUCCESSFUL로 끝났다. 캐시와 UP-TO-DATE를 포함한 소스, 품질, 테스트 및 빌드 검증이며 앱/SDK 성능 개선 수치로 사용하지 않는다. 이후 변경은 ignored 외부 검증 도구와 이 계획뿐이며 제품/native 소스와 APK, frozen M1 원값은 유지했다.

시계 UID와 과거/현재 창 경계 수정은 각각 수정 전 RED와 수정 후 순수 GREEN으로 확인했다. 실제 shell UID의 시간 읽기 성공과 app run-as UID의 실패는 같은 기기의 읽기 전용 대조 근거다. 부모 shell의 기존 절대 시간 제한과 앱 UID의 원자 ACK 저장을 분리했고, live/만료 시간 경계의 추출 shell 검사도 통과했다. 실제 root ACK 전체 전달의 성공은 아직 미검증이다. 과거 영역을 제외한 실제 다크 시도의 현재 다른 창은 계속 거부되며, 이 거부를 Main 전면 성공으로 바꾸지 않았다. 기존 다섯 함수 AST 추출 계약도 순수 대조에서 호환됨을 확인했다. 과거 보조 도구 pin이나 일회성 JDI 승인을 새 실행의 승인으로 재사용하지 않는다.

이번 native 라이트 두 시도와 확대 다크 한 시도는 모두 실패로 보존했다. 첫 ACK 실패, 본문 시작 전 Process crashed, 실제 현재 창 미확인에 따른 ACK 전 거부를 구분하고 제품 입력이나 전체 C 성공으로 세지 않는다. 다크의 첫 복구 판정 실패도 보존한 뒤 독립 restoreverify에서 전체 canonical, 원래 권한과 설정 일치를 확인했다. 소유 PR75 AVD와 자체 DDS forward는 정상 종료했으며 PR74 기기와 승인된 위치 수신 검증은 건드리지 않았다. 새 위치 권한 허용, 위치 관찰/저장과 JDI는 모두0이다.

실제 화면 확인과 현재 C의 기본/확대 다크 전체 시나리오가 남아 있다. 화면을 확인할 수 없는 동안 외부 도구의 native 실행 진입을 차단하고 독 선택이나 새 시도를 자동 반복하지 않는다. 수동 화면 재개 조건만 인계하며 독립 소스/JVM/빌드 작업의 전체 대기로 확대하지 않는다. 현재 PR75 head의 필수 CI4개와 Codex 완료/구체 지적 없음, 이번 JDK17 전체 성공을 확인했지만 수동 완료를 대신하지 않으므로 PR75는 열린 미병합 상태다. PR8/10의 원래 최종 리뷰 미확인도 별도로 유지했다.

README는 정확4개와 문제, 원인, 해결, 결과, 대체안/회고, 코드 순서 및 실제 펼침 구현/유효한 소스 링크를 확인했고 HEAD와 동일하게 보존했다. 이번 새 근거는 외부 검증 도구의 UID와 metadata 해석 경계에 관한 것으로, 기존 네 제품 사례가 입증하는 원본/읽기 소유권, 파괴적 DB 쓰기, 비동기 지도 입력 및 상태 반환 순서보다 사용자 영향과 실제 제품 해결을 강하게 입증하지 못한다. 따라서 추가하거나 교체하지 않았고 새 성능 주장을 만들지 않았다. Notion의 해당 PR75 상태/실패/복구와 차단을 갱신했으며 전체 체크박스57개 중 선택3개를 제외한 필수19/54를 유지한다. 공식 안내와 양식8개는 HTTP200/기준 원바이트 동일을 확인했으며 제출/서명/외부 발송은 수행하지 않았다.


### 2026-11-02 현재 main 동기화와 검증 준비

이전 실패/복구 기록은91e9025에서 실행한 과거 근거로 보존한다. 이후 main에 반영된 지도 출처와 전체 사용 흐름/계획 문서는 이 PR의 제품/계측 동작을 바꾸지 않지만, strict 필수 검사 정책상 behind 상태는 병합 준비 완료가 아니다. 현재 main bf416e0을 기존 작업 브랜치에 일반 merge하고, 보존한 미커밋 검증 기록도 같은 계획에 기록한다. 기존 commit/PR 이력의 강제 재작성, rebase, force push와 사용자 변경 삭제는 하지 않는다.

동기화 전후 제품/계측/build/script 소스와 기존 APK/소스 pin을 실제 비교하고 JDK17 전체 검사를 실행한다. 문서와 이미 실행한 사실의 정리만 반영하므로 새 앱 성능 전후 계측은 적용 제외다. 기존 M1 원값과 현재 README의 정확4개/지정6항목/실제 코드/화면은 보존하며, 새 제품 해결 근거가 없어 사례를 교체하지 않는다. APK/source 동일성이 확인되지 않으면 검증 후보 pin을 바꾸지 않는다.

현재 수동 화면 재개 조건은 충족하지 못했다. 이번 범위에서 새 C 실행, 독 선택, AVD 조작, 권한 변경, 위치 관찰/저장과 JDI를 하지 않는다. 과거91e9025의 CI/Codex나 이번 소스 검사 성공을 새 head의 최신 CI/리뷰 또는 미완료인 실제 C 두 테마와 ROOT_ACK runtime 성공으로 대신하지 않는다. 새 head의 필수 CI/Codex/미해결 상태를 따로 확인하고 PR75는 실제 수동 완료까지 열린 상태로 유지한다.


이번 동기화 tree에서 JDK17.0.21 전체 ./scripts/verify.sh는 exit0과 네 BUILD SUCCESSFUL로 끝났다. 캐시/UP-TO-DATE 포함 결과이며 새 앱 성능/실기 표본이 아니다. 제품/계측/build/script와 README가91e9025와 동일하고, 등록한 QA 소스와 candidate/frozen APK의 실제 hash가 유지됨을 대조했다. 기존 UID/과거 창 해석의 수정과 실패/복구는 그 원래 실행의 근거로 보존하며 이번에 native를 다시 실행한 것으로 쓰지 않는다. 공식 공개 원문8개도 모두200/기준 hash 동일이었다. README의 네 사례와 여섯 항목 순서를 보존하고 새로운 제품 해결 사례나 측정 개선율을 추가하지 않았다. 새 head CI/Codex와 미해결 상태는 push 뒤 따로 확인한다.


### 2026-11-02 사용자 요청에 따른 남은 실기 마무리

사용자가 PR74/75 마무리를 우선 요청했다. 현재 실제 Android Studio의 한길 화면과 스크린샷을 확인해 이전 Mac 잠금으로 인한 화면 제어 차단이 해소됐음을 확인했다. 기존 소유 PR75 AVD와 사용자 데이터를 보존한 채 라이트와 확대 다크의 현재 C 및 실제 ROOT_ACK를 각각 실행한다. 새 위치 권한, 위치 조회/저장과 JDI는 사용하지 않는다. 기존 성공과 실패, 고정 M1은 유지하며 새 시도의 실제 결과와 원형/전체 권한/설정 복구를 별도로 남긴다. 확인되지 않은 제품 결함을 추정해 수정하거나 새 성능 수치를 만들지 않는다. 이 검증 준비와 기록은 앱 실행 성능 변경이 없어 전후 계측 적용 제외다. 실기 완료 뒤 최신 main의 변경을 일반 merge로 반영하고 제품/계측 소스와 APK 동일성을 대조한 후 JDK17 전체 검사, 최신 CI/Codex와 정책을 확인해 병합한다.


검증 시작 직전 실제 화면 근거를 C의 입력 전 준비 단계에 추가한다. 전용 Qt 에뮬레이터는 정상 부팅과 창 생성, DDS ONLINE을 확인했지만 CUA 앱 목록과 Studio 미러링에서 직접 선택하지 못했다. 네이티브 계측이 준비 영수증을 보내기 전 실제 display0 화면과 포커스 근거를 비공개 PNG/JSON으로 저장하고 root가 해당 시도의 새 원본을 확인한 뒤만 단일 ACK를 전달한다. 이 사전 화면은 SDK/전면 성공이나 native admission의 근거로 간주하지 않고 별도 출처로 표시한다. 기존90초 절대 상한, 고정 host/display, 실제 P/UX/포커스1초 안정과 본문 모든 입력 검사는 유지한다. 위치 수집/권한/JDI와 제품 코드, M1은 변경하지 않으며 성능 측정 적용 제외다.


## PR75 위치와 독립적인 실제 UI 검증의 환경 분리

실제 C 재개 전 전용 기기의 Android 시스템이 반복 종료됐다. private 원문에서 watchdog이 android.fg의 GnssNative.native_stop_sv_status_collection 정지를60초 기다린 뒤 시스템을 종료한 것을 확인했다. 차량 서비스의 CarServiceHelper 연결 오류는 함께 관찰한 증상이며 유일한 최초 원인으로 확정하지 않는다. 실제 C는 JUnit 시작/ROOT_ACK/제품 입력 이전에 실패했고, 실패한 실행과 각 복구 결과를 분리해 보존했다. Google 공식37.2.13을 별도 경로에 설치한 뒤에도 계측 시작 전 Process crashed가 발생했으므로 새 버전으로 해결됐다고 기록하지 않는다. 원래 SDK는 변경하지 않았다.

PR75는 기존 차량/UX 계약과 schema6 종료 원본 연결만 사용하며 위치 API/리스너를 호출하지 않는다. 기존 기기와 userdata를 보존한 동일 PR75 전용 기기에서 GPS 하드웨어를 일시적으로 미지원으로 구성해 C의 실제 Main/P/UX, 종료 원본 복습과 D/P 잠금 경계를 분리 검증한다. FUSED_LOCATION은 계속 거부하며 주차/UX/전면/ROOT_ACK 본문 조건과90초 상한은 바꾸지 않는다. GPS 미지원 환경의 성공을 GPS 활성 환경, PR74의 실제 위치 수신, 지도 내비나 출품 전체 검증으로 확장하지 않는다. GPS 설정 원문은 private 백업하고 전용 기기 종료 뒤 원래 설정으로 되돌린다. 각 실행에서 canonical DB와 전체 권한/flags/앱 설정 복구를 따로 확인한다. 원래 보호 기기에는 이 설정을 적용하지 않는다.

이번 변경은 검증 환경 분리이며 제품/고정 M1/측정 소스와 APK는 바꾸지 않는다. 전후 성능 비교로 쓰지 않으며 기존 M1 세 arm과 미확정 결론을 유지한다. C는 라이트/확대 다크 각각 실제 성공과 원형 복구를 확인해야 완료다. 현재는 분리 검증 전이며 수동 완료나 병합으로 기록하지 않는다.


### native 시작 직후의 실제 Main 준비 순서

GPS 장치 분리 조건의 첫 C에서는 JUnit이 실제 시작됐고 이전의 Process crashed와 구분됐다. 제품 입력/ROOT_ACK 이전 Harness.assertEnvironment의 즉시 RESUMED 검사에서 실제 Main이 CREATED여서 실패했다. native 실패 영수증도 CREATED/focus=false를 기록했고, 이후 시스템 조회에서는 기본 내비가 전면이며 keyguard=false, inputRestricted=false, 화면 ON을 확인했다. 실패 당시와 이후 전면 상태를 같은 시점의 증거로 합치지 않는다. 그 실행은 전체 DB/권한/flags/앱 설정 복구를 통과했다.

같은 ActivityScenario의 원래 Main을 명시적으로 RESUMED로 요청한 뒤 기존 환경 검사를 수행하도록 수동 C의 준비 순서만 정정한다. 실제 Activity 객체 동일성, RESUMED/현재 window focus/유효 display, 원래 ROOT_ACK와 실제 SDK P/UX1초 안정 및 기존90초 절대 상한은 모두 유지한다. 상태 값을 대입하거나 외부 내비를 종료/비활성화하지 않는다. 이 준비 요청은 ROOT_ACK 전에만 실행하고 ACK 뒤에는 기존 외부 전면 조작 금지를 유지한다. 테스트 준비이며 앱 성능이나 제품 해결 사례로 기록하지 않는다. 실제 성공 여부는 동일 native 흐름의 새 실행과 원형 복구로 확인한다.

같은 Main 재개 요청을 추가한 후보에서 JDK17.0.21 spotlessApply, 전체 ./scripts/verify.sh와 :app:assembleDebugAndroidTest를 모두 exit0으로 완료했다. 캐시 재사용 포함이며 실제 C 성공과 성능 자료를 대신하지 않는다. 앞선 분리 조건의 native 첫 실패는 JUnit 실제 시작 후의 준비 단계 실패이며 ROOT_ACK/제품 입력은 없었고 전체 canonical/권한/flags/앱 설정 복구는 실제 통과했다. 다음 C 재실행과 두 테마/ROOT_ACK의 실제 성공은 아직 대기다.

이번 새 근거는 환경 GNSS watchdog과 native Main 준비 순서에 관한 것으로 제품의 원본/조회 소유권, 파괴적 DB 쓰기, 지도 비동기 수명과 상태 반환 문제를 새로 해결한 성과가 아니다. 기존 README 네 사례의 내용과 순서, 여섯 항목과 실제 코드를 유지하고 미측정 성능 수치를 추가하지 않았다.


### 2026-11-03 현재 C 두 테마의 실제 검증

5284b95 후보의 제품 APK와 QA APK를 고정하고 같은 전용 Pleos API34 기기에서 기본 글자 라이트와 1.3배 글자 다크를 순차 실행했다. 이 환경은 앞서 계획한 GPS 하드웨어 분리 조건이며 위치 권한은 계속 거부했다. 각 시도의 새 실제 display0 사전 화면을 확인한 뒤 재선택 없이 단일 ROOT_ACK를 전달했고 native가 실제 Main의 RESUMED, 같은 display와 포커스, SDK P와 UX 허용의 연속 안정 조건을 확인했다. 브라우저 DDS ONLINE과 실제 D/P 입력을 앱의 SDK 수신 및 잠금 판정과 함께 대조했다.

두 테마 모두 실제 UI 시작과 마침, 같은 종료 원본의 명시적 복습, 구성 재생성 유지, SDK 차량 기록 출처, D에서 상세 잠금과 P 복귀 뒤 자동 공개 방지 및 새 명시적 선택을 통과했다. 실제 화면의 주요 행동 도달과 글자 배치도 확인했다. 원래 전체 domain과 typed row, 고정 baseline 파일, 카탈로그, 환경 보존과 관찰 취소, 플래그 복귀는 각 native 영수증에서 확인했다. 위치/경로, 백그라운드 수집이나 GPS 활성 환경의 성공으로 확장하지 않는다.

라이트는 JUnit, native 결과, 외부 canonical/권한/flags/설정 복구를 포함한 전체 시도가 통과했다. 다크는 JUnit과 native 본문, 원형 보존 및 외부 canonical 복사까지 통과했지만 최초 original-platform-and-canonical-exact 단계가 RuntimeError로 끝나 해당 시도 전체는 실패로 보존했다. 이 집계는 세부 복구 실패 원인을 저장하지 않아 원인을 확정하지 않는다. 이어 별도 restoreverify에서 전체 typed row/order/sequence/Room identity와 원래 모든 권한/flags/설정을 실제 대조해 통과했다. 이 후속 복구는 최초 실패를 덮어쓰거나 최초 시도를 성공으로 바꾼 결과가 아니다. 실제 D/P는 root가 브라우저에서 P 복귀를 확인했고, 전용 기기를 정상 종료한 뒤 일시 GPS 설정을 원래 바이트로 복구했다.

새 위치 권한 허용과 위치 관찰/임시 저장, JDI는 모두0이다. PR74의 원래 미소비 승인과 별도 원장은 유지했다. 이번 준비 순서와 실기 결과는 앱 실행 성능 변경이 아니므로 전후 성능 측정 적용 제외다. 고정 M1 원값과 미확정 결론은 변경하지 않았다. README는 기존 네 사례와 지정 여섯 항목, 실제 펼침 코드와 유효 링크를 보존했다. 새 근거는 이미 선정한 사례1의 실제 행동을 보강하며 다른 세 사례보다 새로운 제품 경계를 입증하는 교체 근거가 아니므로 추가 교체하지 않았다. 전체 APP06/DES06과 출품 준비 완료는 별도이고 필수19/54를 유지한다.

실기 소스 head의 필수 CI4개와 Codex 완료/지적 없음, 미해결 스레드0을 확인했다. 이 결과 기록만 추가한 뒤 최신 문서 head의 필수 검사와 Codex 종료를 다시 확인하고 저장소 정책대로 병합한다. 병합, main ff-only와 브랜치 정리는 실제 GitHub 결과를 확인한 뒤 수행하며 미리 완료로 기록하지 않는다.

이 결과를 반영한 현재 tree에서 JDK17.0.21 전체 ./scripts/verify.sh가 exit0과 네 BUILD SUCCESSFUL로 끝났다. 변경은 이 계획뿐이며 제품/native/측정 소스와 README는 실기 후보5284b95와 동일하다. 캐시 재사용 포함 자동 검사로, 실기나 성능의 대체 근거가 아니다. 공식 안내와 양식8개도 현재 HTTP200과 기준 원문 hash 동일을 확인했다.
