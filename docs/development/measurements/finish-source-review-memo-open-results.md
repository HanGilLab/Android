# 마침 원본 연결 전후의 기존 메모 공개 관찰

기존 메모 공개의 고정 절차를 실제 Hilt 앱, SDK와 Room에서 관찰했다. A1/B의 큰 시간 차이는 같은 A APK를 다시 실행한 A2에서도 나타났다. 코드 변경의 성능 향상, 동등성 또는 무회귀를 확정하지 않는다. 새 마침 복습 동선과 SDK 수집 속도, 운전자 소요 시간은 측정하지 않았다.

## 대상과 지표

메모 펼침 클릭 직전부터 같은 원본/메모 version/본문, 실제 글자 경계와 현재 주차/UX/전면 조건을 확인할 때까지 SystemClock.elapsedRealtimeNanos로 잰 ns를 사용한다. 선택한 기록까지 탐색과 스크롤하는 준비 단계, 종료 후 원형 대조는 이 구간 밖이다. 구간 안에는 Compose 테스트의 클릭/idle 대기/조회/검증과 Room 재조회 비용도 포함된다. 순수 렌더 지연, 순수 DB/SDK 성능이나 JUnit 전체 실행 시간이 아니다.

[실제 고정 계측 구현](../../../app/src/androidTest/kotlin/io/github/hangillab/hangil/SessionHistoryMemoReadMeasureTest.kt)

| arm | 제품 기준 | 조건 |
|---|---|---|
| A1 | 98347438ce96cae806cfb07f416ae57ab1543f54 | 변경 전 기준 |
| B | 027f3a0a56d3cda27fd6c64cbb7799e54d1471d5 | 제품 APK는 0dd6aa67ea8581b4f02ddf9cd8f0e6a13b04496e와 byte-identical |
| A2 | A1과 같은 APK | 첫 실패 arm을 보존한 전체 재시도 |

Git 선언은 외부 실행 기록이다. 실제 설치 APK와 고정 계측 APK의 해시를 각 arm에서 대조했지만 임베디드 source-to-binary 증명은 아니다. 계측 파일 SHA256은 13bf0dca91bdb3f6ec592ef2c09f759745ad8206d5999d0d66a93863b5874c05이고 세 arm이 동일한 고정 계측 APK를 사용했다.

## 입력과 실행 조건

- Pleos API34/arm64, 2560x1440/density160/RAM4GB/CPU4, JDK17/debug, font1.0/라이트.
- 동일 schema6 원형, 종료 기록13/관찰145/메모1/terminal 실행15/수동 완료 선언0/active0. 같은 nonlatest 원본/메모/version/catalog와 typed 행/순서/sequence/Room identity를 대조했다. 이 행 수는 입력 크기와 보존 조건이며 성능 성과가 아니다.
- 실제 Main RESUMED와 같은 유효 display/known-own focus, SDK P와 UX 해제, 브라우저 DDS ONLINE/P를 확인했다. FUSED_LOCATION은 denied, 새 위치 권한 허용0이었다.
- 각 유효 arm은 새 부팅 뒤 원형을 복원하고 정상 Main을 먼저 실행했다. 최초 A1 이후 많은 준비/수동 검증이 있었으며 guest userdata/ART profile, host/OS/그래픽/디스크 cache, 열과 시스템 부하는 초기화하거나 완전히 통제하지 않았다. 완전한 cold-start 동등성을 가정하지 않는다.
- A1 → B → A2 순서다. 각 arm의 워밍업5와 본30을 분리했다. 실패한 A2의 부분 표본은 별도 보존한 뒤 같은 고정 절차 전체5+30을 다시 수행했다. 느린 성공값을 제외하거나 다른 arm으로 보충하지 않았다.
- 원본 데이터와 식별자/원문 메모/환경 로그는 공개하지 않는다. 정확히 같은 입력의 재측정에는 비공개 고정 자료가 필요하며, 공개 절차만으로 개인 자료를 복원할 수 없다.

## 본 측정 결과

단위는 ms이고 n은 각 arm의 실제 본 표본 수다. 표시값은 0.001ms까지 반올림했으며 원래 ns는 [원값 CSV](finish-source-review-memo-open-times.csv)에 있다. 워밍업도 같은 CSV의 별도 phase로 보존했다.

| arm | n | median ms | Q1 | Q3 | IQR | min | max |
|---|---:|---:|---:|---:|---:|---:|---:|
| A1 | 30 | 2426.939 | 2185.741 | 2720.940 | 535.199 | 1992.903 | 5303.056 |
| B | 30 | 933.185 | 921.412 | 1045.404 | 123.992 | 898.373 | 1464.451 |
| A2 | 30 | 928.873 | 915.924 | 963.169 | 47.245 | 867.148 | 1100.727 |

B-A1: -1493.754ms / -61.549%. A2-A1: -1498.066ms / -61.727%. B-A2: 4.312ms / 0.464%.

표시한 X-Y의 절대 차이는 X 중앙값-Y 중앙값이고, 상대 변화율은 (X 중앙값-Y 중앙값)/Y 중앙값×100이다. 예를 들어 B-A2의 분모는 A2 중앙값이다. 양수는 X의 시간이 Y보다 더 길다는 뜻이며, Y 중앙값이0이면 상대율은 계산하지 않는다.

세 유효 arm의 본 표본은 각각30이고 실패/누락/제외0, 워밍업은 각각5다. native 보존/복귀/observer 취소/flag 복원과 외부 원형/권한/설정 대조를 확인했다. 원본 보고서의 사분위수는 median-of-each-sorted-half이며 수정하지 않았다. 위 표는 원래 ns로 모든 arm을 동일한 inclusive 방식으로 다시 계산했다.

동일 A APK의 A2가 A1보다 크게 달라졌고 B/A2의 IQR은 겹친다. A1/B 차이를 제품 변경의 개선율로 사용하지 않는다. 현재 자료로 OS/cache/ART/JIT/열/순서 영향과 코드 효과를 분리할 수 없다. 기존 메모 공개에서의 관찰을 새 마침 복습 속도에 옮기지 않는다.

## 실패와 미실행

| 별도 시도 | 실제 확보와 실패 | 미실행과 보존 |
|---|---|---|
| 첫 A1 | warm5/본27 성공, 본28 원값 없음, 외부600초 중단 | 본29/30 미실행. native 전체 완료/cleanup으로 기록하지 않고 외부 원형 대조는 별도 확인 |
| 두 번째 A1 | 최초 Main focus 확인 실패, 원값0 | warm5/본30 모두 미실행. SDK/원형 native 초기화 전이며 외부 원형 대조를 별도로 확인 |
| 첫 A2 | warm5/본17 성공, 본18은 계측 전 준비 단계 실패 | 차량 DISCONNECTED/기어 미확인, UX 해제와 실제 전면에서도 상세 잠금. 본19~30 미실행. native/외부 원형 보존 확인 |

[실패 시도의 원값과 미실행 슬롯 CSV](finish-source-review-memo-open-failed-attempts.csv)는 계획 슬롯을 포함한다. 행 수를 실제 수집 표본 수로 해석하지 않는다. 없는 시간은 빈 값이고0으로 대체하지 않았다. 이 부분 표본은 위 세 유효 arm에 합치지 않았다. 실패 원인과 SDK 조건 소실은 앱 성능 저하의 판정이 아니다.

## 재현 절차

1. 제품 A와 B를 위 기준의 별도 검증 checkout에서 JDK17로 debug 빌드한다. A 기준에서 만든 계측 APK 하나를 보존하고 세 arm에 같은 실제 bytes를 설치한다.
2. 보호할 사용자 장치를 보존하고 별도 소유 Pleos 테스트 장치를 사용한다. 각 arm 전에 같은 원형 사본과 익명 조건, 실제 설치 APK/고정 계측 해시, 전체 typed 입력과 권한/설정을 대조한다.
3. 같은 Main의 실제 화면/전면/display와 SDK P/UX, 실제 DDS 연결을 확인한다. 측정 중 다른 Gradle/기기 시나리오를 병행하지 않는다.
4. SessionHistoryMemoReadMeasureTest의 manualHistoryMemoReadMeasure=true를 명시한다. A1/B의 measurementSeries는 history-memo-edit-read-v1, A2는 history-memo-edit-read-repeat-v1이다. measurementStage는 A1/A2 baseline, B comparison이고 제품/계측 선언 commit을 각각 고정한다.
5. 각5 warm/30 main과 같은20초 endpoint/1800초 외부 제한을 사용한다. 실패/소실/미실행을 별도 보존하고 전체 arm을 완료한 뒤 원형과 설정을 다시 대조한다.
6. 원래 ns로 중앙값과 inclusive Q1/Q3/IQR/min/max를 계산한다. 기준이0이면 상대율을 계산하지 않는다. 입력/조건 차이, 반복 편차와 불완전 표본을 함께 남긴다.

[작업 계획](../../plans/active/2026-11-01-finish-source-review.md)에 실제 정확성 검증과 남은 작업을 별도로 기록한다.
