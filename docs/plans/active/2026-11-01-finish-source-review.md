# 명시적으로 마친 같은 연습의 복습 연결

- 상태: 계획과 변경 전 계측 준비, 제품 코드 변경 전
- 요구사항: APP-06과 DES-06의 주차 후 복습 흐름 중 마침 결과와 같은 기록을 여는 연결. 실제 도로 구간, 위치 표본, 전체 항목 완료는 범위 밖이다.
- 기준 main: dd4897880b24776c1599bb18096abc5bcc0a2219

## 목표와 범위

현재 main의 SessionViewModel.finish는 Finished/AlreadyFinished에 들어 있는 종료 세션을 버리고 enum 피드백만 남긴다. 일반 이력은 진입 당시 최신 종료 기록을 고른다. 마침 뒤 다른 종료 자료가 전달되거나 원본이 바뀌면 방금 마친 대상을 그대로 복습한다는 요청을 표현할 수 없다. 마침 응답의 전체 종료 원본을 보존하고 사용자가 명시적으로 같은 기록의 복습을 열도록 연결한다. 자동 화면 전환을 추가하지 않는다.

기존 이력의 관찰, 메모, 저장 실행과 수동 마무리 열람을 재사용한다. 조회 owner를 만들기 전에 전체 expected source를 고정하고 최신 기록으로 fallback하지 않는다. 삭제, 같은 ID의 교체/재생성, active 복귀, read 실패와 종료 미확인을 나눈다. P와 UX 해제, 실제 RESUMED, reader/query/generation, 일회성 클릭과 기존 메모 CAS를 유지한다. 프로세스 재생성으로 메모리 원본을 잃으면 unavailable로 돌아가며 현재 최신 기록으로 복원하지 않는다.

새 DB/schema/SDK/권한 계약이나 배경 수집, 실제 위치/경로, 다음 과제 저장 제약 확대는 없다. PR74와 이전 실제 위치 동의 대기는 그대로 보존한다. 기존 schema7 후보 AVD에 main/schema6 APK를 설치하지 않는다. 이미 설치된 Pleos 이미지로 별도 test-owned 초기 AVD만 구성하고 기존 AVD/userdata/config/snapshot을 변경하지 않는다.

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
| 새 검증 환경과 A1 | 미실행 | 계획 후 실제 준비/환경 확인과 원형 대조가 필요 |
| 제품 구현/검사/실제 UI/B/A2/최신 리뷰 | 미실행 | A1 완료 뒤 시작 |

## README 사례 비교와 남은 문제

현재 새 연결은 구현/실측/실제 검증 전이므로 기존 네 사례를 대체할 근거가 없다. exact-source handoff/cold query의 원본 경계는 기존 데이터 소유권/비동기 입력 사례와 비교해 실제 영향, 원인 증거, 대안/구현 기여와 재현 결과가 더 강할 때만 교체한다. 단순 최신성/코드량/검사 수로 교체하지 않는다. 문서/발췌 정합은 앱 실행 성능과 무관하므로 측정 적용 제외, 실제 코드/유효 링크/기존 이미지/공개 민감 정보 검사를 수행한다.

필수19/54, 선택3개 제외를 유지한다. APP06/DES06 전체, 실제 구간/위치/배경 수집, PR74 새 임시 저장 동의와 원래 PR8/10 최종 리뷰를 완료로 바꾸지 않는다. 답변 없는 새 위치 동의를 재사용하거나 반복 요청하지 않는다.
