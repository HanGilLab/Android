# 같은 코스의 마무리 기록 요약과 이력 진입 비용 검증

지난 연습에서 선택한 종료 기록과 같은 당시 목표/코스 문맥의 마무리 기록 수, 과제별 기록 수를 보여 주는 APP-08의 부분 기능이다. 한 번에 하나의 종료 기록을 읽는 기존 이력과 원문/저장 생성 순서는 유지한다. 요약은 시작/마무리 버튼으로 남긴 저장 기록의 수이며 실제 과제 수행 횟수, 과정 완료, 숙련도나 운전 능력의 근거가 아니다.

새 DB 스키마, 자료 쓰기/삭제, 실제 주행 경로, 위치/내비와 차량 제어는 추가하지 않는다. APP-07이나 APP-08 전체 완료도 뜻하지 않는다.

## 원문 그룹과 상태 경계

[SessionCourseSummary](../../core/domain/src/main/kotlin/io/github/hangillab/hangil/core/domain/SessionCourseSummary.kt)는 선택한 실제 종료 source와 같은 수신 목록에서 요약을 계산한다. source의 전체 snapshot이 해당 ID의 원래 기록과 일치해야 하며 중복 ID나 손상된 필드/시각을 제거하거나 보정해 빈 요약으로 만들지 않는다.

그룹에는 아래 값이 모두 같은 종료 기록만 포함한다.

- 목표 ID와 코스 ID
- 코스 버전과 브리핑 버전
- 저장 당시 목표 제목과 코스 제목
- 저장 당시 시연/검토 상태

같은 코스 ID여도 목표, 버전, 제목이나 시연 상태가 다르면 섞지 않는다. 과제는 `(과제 ID, 저장 당시 과제 제목)`으로 구분하고 수신 목록에서 처음 등장한 순서를 유지한다. 같은 과제 ID의 제목이 바뀌었으면 별도 항목이다. 벽시계 시각으로 재정렬하지 않으며 현재 카탈로그나 SDK 차량 관찰로 과거 수행을 다시 해석하지 않는다.

[이력 ViewModel](../../feature/home/src/main/kotlin/io/github/hangillab/hangil/feature/home/SessionHistoryViewModel.kt)은 선택한 원문과 요약을 같은 `Ready`와 generation으로 발행한다. 이전/다음 이동과 새 수신에서도 다른 원문에 속한 요약을 붙이지 않는다. 현재 P와 UX 허용을 모두 확인해야 읽고, D/미확인/거부/단절/이탈에서는 상세와 요약을 숨긴다. 읽기 실패와 스트림 종료, 사라진 선택을 정상/빈 요약으로 바꾸지 않는다. 아직 저장소가 방출하지 않은 변경을 동기 감지한다고 보장하지 않는다.

기존 커서/재시도와 잠금 계약은 [이력 검증 절차](session-history-validation.md)를 따른다. 요약의 과제별 설명은 기본으로 접고 필요한 단계에서만 펼친다.

## 현재 실행 근거와 미실행 항목

| 항목 | 상태 | 실제 확인 범위 |
|---|---|---|
| 제품 변경 전 이력 진입 기준값 | 실행 성공 | 실제 SDK P+UX/Activity/Room, 5회 워밍업과 30회 원값, 원래 domain 보존과 홈 복귀, 설치 APK 해시 일치 |
| 기준 측정의 외부 원본/설정 대조 | 실행 성공 | 전체 SQLite 원래 값/자료형/순서/sequence와 권한/runtime/글꼴/야간/IME/프록시 완전 일치, 이번 소유 AVD 종료 |
| 요약 domain/History ViewModel 단위 경계 | 실행 성공 | 각각14개/49개, 실패/오류/건너뜀0. 실제 차량/UI 성능이나 실기 성공을 대신하지 않는다 |
| 전체 verify/테스트 APK | 실행 성공 | JDK17 품질/모듈 경계/Lint/debug/unsigned release, 단위422개 실패/오류/건너뜀0과 실제 계측 APK 빌드. 실제 요약 화면과 비교 계측은 별도다 |
| 요약 추가 후 이력 진입 비교 계측 | 미실행 | 아래 동일 경로로 실행해야 하며 새 비용이나 개선폭은 아직 없다 |
| 요약 화면의 기본 라이트/확대 다크 실기 | 미실행 | 아래 native 시나리오가 있으며 성공으로 기록하지 않는다 |

기준 제품은 `23ea21ff3cacdf1d6164bae8c615aaa05e19e241`, 기준 계측 원본은 `b98f31a98005ae87e7f7f4b23dee97c242716df7`다. 정제한 실제 기준값은 다음과 같다. 단위는 ms이며 느린 표본을 제거하지 않았다.

| 실제 계측된 이력 진입 | 값 |
|---|---:|
| 측정 표본 수 | 30 |
| 중앙값 | 255.634167 |
| Q1 / Q3 | 243.132167 / 290.861000 |
| 사분위 범위(Q3-Q1) | 47.728833 |
| 최솟값 / 최댓값 | 222.689917 / 350.548209 |
| 측정 성공 / 실패 / 시각 누락 / 미실행 / 제외 | 30 / 0 / 0 / 0 / 0 |
| 워밍업 성공 / 실패 / 시각 누락 / 미실행 | 5 / 0 / 0 / 0 |

이 값은 계측이 포함된 기존 이력 진입의 기준이다. 요약 추가 후 값은 아직 없으므로 성능 개선이나 안정적인 추가 비용을 주장하지 않는다. 앱 내부 렌더링/프레임/GPU/SDK 시간, 사용자 읽기/행동 속도로 해석하지 않는다. 원문 source, 실제 시각/기기 환경, 개별 표본과 원시 로그는 비공개 근거에만 보존한다.

## 실행 전 보존과 조건

1. 실제 checkout/계획/lease를 확인하고 같은 앱/AVD를 다른 살아 있는 작업과 동시에 조작하지 않는다.
2. Activity rule이 제품 앱을 시작하기 전에 외부 실행자가 활성 세션이 없고 원래 준비와 종료 이력이 있는지 확인한다. 검사 조건을 만들려고 기존 기록을 추가/종료/삭제하거나 데이터를 초기화하지 않는다.
3. 준비와 모든 세션/관찰/메모, 일관된 SQLite 복사본과 전체 행/자료형/순서/스키마/sequence를 비공개로 보존한다. WAL을 무시한 파일 복사만으로 보존을 대신하지 않는다.
4. 실제 DDS P/UX, 권한/runtime 플래그, 글꼴/야간/IME/프록시를 보존한다. 기존 AVD/이미지와 앱 데이터를 재사용하고 서명/자료 호환성을 확인한 debug 업데이트를 사용한다. 새 권한이나 데이터 삭제는 필요하지 않다.
5. 실제 브라우저 Virtual Console의 DDS 제어 브리지와 앱 SDK 수신을 확인한다. HTTP 200, 제어 클릭이나 빈 UI 계층만으로 적용 성공을 단정하지 않는다. 전면 앱을 확인해 기본 내비/카메라 오버레이를 앱 화면으로 오인하지 않는다.
6. 제품/계측 SHA와 설치된 두 APK의 대응을 외부에서 검증한다. 인자로 받은 SHA는 외부 선언이며 기기 안에서 Git 상태를 추론한 값이 아니다.

JDK17 전체 검사와 테스트 APK 빌드는 AVD 실기와 순차 실행한다. macOS에서는 전역 설정을 덮어쓰지 않고 해당 실행에만 JDK를 전달한다.

```bash
HANGIL_JDK17_HOME="$(/usr/libexec/java_home -v 17)"
"$HANGIL_JDK17_HOME/bin/java" -version
env JAVA_HOME="$HANGIL_JDK17_HOME" ./scripts/verify.sh
env JAVA_HOME="$HANGIL_JDK17_HOME" ./gradlew --no-daemon --console=plain :app:assembleDebugAndroidTest
```

아래 명령에서 `HANGIL_TEST_DEVICE`는 외부 실행자가 소유한 전용 장치의 비공개 변수다. 실제 식별자나 개인 경로를 공개 문서/로그에 넣지 않는다. 명령만으로 사전 보존이나 DDS/설정 복구가 수행되지는 않는다.

## 같은 경로의 기준/비교 계측

[SessionHistoryOpeningMeasureTest](../../app/src/androidTest/kotlin/io/github/hangillab/hangil/SessionHistoryOpeningMeasureTest.kt)의 `measureHistoryOpeningAndPreserveOriginalData` 하나를 사용한다. `manualHistoryOpeningMeasure=true`, `measurementStage=baseline|comparison`, `declaredProductCommit`과 `declaredHarnessRevision`이 필요하다. 두 SHA는 실제 대응하는 완전한40자리 값이어야 한다. opt-in이 없으면 건너뛰며 통과로 계산하지 않는다.

기준과 비교는 API34 debug/글꼴1.0/라이트, 새로 시작한 프로세스와 Activity, 같은 원래 준비/전체 종료 source/기록/카탈로그/실행 환경을 사용한다. 5회 워밍업 뒤 30회 순차 측정하며 매회 닫아 홈과 동일 최신 source를 확인한다. 기존 준비/원문을 변경하지 않고 캐시도 비우지 않는다. 기준 실행 후 계측 소스는 불변으로 유지하고 새 기능 검증은 별도 실기 파일에서 확장한다.

기준 명령은 아직 기준 파일이 없는 승인된 실행의 형식이다. 현재 완료된 기준을 삭제하거나 덮어써 재실행하는 절차가 아니다.

```bash
adb -s "$HANGIL_TEST_DEVICE" shell am instrument -w -r \
  -e manualHistoryOpeningMeasure true \
  -e measurementStage baseline \
  -e declaredProductCommit "$HANGIL_BASELINE_PRODUCT_COMMIT" \
  -e declaredHarnessRevision "$HANGIL_BASELINE_HARNESS_REVISION" \
  -e class 'io.github.hangillab.hangil.SessionHistoryOpeningMeasureTest#measureHistoryOpeningAndPreserveOriginalData' \
  io.github.hangillab.hangil.debug.test/androidx.test.runner.AndroidJUnitRunner
```

비교도 같은 단일 메서드를 사용한다.

```bash
adb -s "$HANGIL_TEST_DEVICE" shell am instrument -w -r \
  -e manualHistoryOpeningMeasure true \
  -e measurementStage comparison \
  -e declaredProductCommit "$HANGIL_COMPARISON_PRODUCT_COMMIT" \
  -e declaredHarnessRevision "$HANGIL_COMPARISON_HARNESS_REVISION" \
  -e class 'io.github.hangillab.hangil.SessionHistoryOpeningMeasureTest#measureHistoryOpeningAndPreserveOriginalData' \
  io.github.hangillab.hangil.debug.test/androidx.test.runner.AndroidJUnitRunner
```

타이머는 `SystemClock.elapsedRealtimeNanos()`로 홈의 `home_history` 클릭 호출 바로 전부터 `history_ready`와 `history_course`가 실제 가용 앱 viewport에 전체 표시되고 Compose idle/recheck까지 확인한 구간이다. 실제 클릭 전달, Compose 동기화/idle 대기와 semantics 조회 비용을 포함한다. 홈 버튼의 사전 스크롤, SDK/source/원본 대조, JSON 작성, 닫기와 프로세스/Activity 시작은 제외한다. 새 요약 내용을 모두 읽은 시간이나 요약 panel을 펼친 시간은 측정하지 않는다.

클릭 시작부터 endpoint 확인까지 실제20초 제한을 검사한다. Compose blocking 호출의 자체 대기 제한과는 별개이며 blocking이 나중에 반환했어도20초를 넘긴 endpoint를 성공으로 만들지 않는다. 기준/비교의 start/endpoint와 검사 순서는 같다. 새 요약도 같은 Ready 발행에 포함되지만 그 내용/상태 경계는 아래 별도 native 실기로 대조한다.

### 파일 예약과 비교 정합

앱 내부 cache의 `measurements/history-opening-baseline.json`과 `measurements/history-opening-comparison.json`은 각 실행의 소유 예약과 원값 보고서다. 선택한 출력 파일 또는 AtomicFile의 `.bak`/`.new`가 이미 있으면 시작을 거부한다. 보고서는 첫 시도 전, 워밍업 후와 최종 정리 때 저장하고 타이머 안에서는 파일을 쓰지 않는다. 각 원값에는 반복 순서, 실제 경과 시간과 endpoint/source/홈 복귀 확인을 남긴다. 실패/시간 누락/미실행/제외도 별도로 센다.

comparison은 완료된 baseline의 실제5회/30개 원값/통계를 재계산해 검증하고 전체 domain 값/순서, 고정 source, 카탈로그, 환경/debug/viewport와 계측 계약을 동일 비교한다. 조건이 다르면 기준을 새 값에 맞춰 수정하지 않고 실패한다. 기준 파일 해시도 실행 전/후 같아야 한다. 원래 파일과 개별 원값은 비공개로 보존하며 정제한 통계만 공개한다.

변경 후에는 중앙값/Q1/Q3/사분위 범위/min/max와 원값30개, 중앙값의 절대 차이 및 상대 변화를 남긴다. 양수는 비교 실행이 더 느렸다는 뜻이고 기준 중앙값이0이면 상대율을 계산하지 않는다. 순차 전후 실행이므로 실행 순서, 캐시, 발열과 환경 이동을 제거한 시험이 아니다. 차이가 분포의 편차와 겹치면 방향이나 인과적 비용을 확정하지 않는다. 새 정보를 추가하는 기능을 동일 기능의 성능 최적화로 표현하지 않는다.

## 요약 화면의 실제 검증

확장한 [SessionHistoryFlowTest](../../app/src/androidTest/kotlin/io/github/hangillab/hangil/SessionHistoryFlowTest.kt)의 `endedHistoryKeepsStoredSnapshotsAndSelectionAcrossLifecycleAndDrivingLock`만 선택한다. 이 시나리오는 기본 글꼴1.0/라이트와 글꼴1.3/다크에서 각각 실행한다. 외부 실행자가 원래 설정을 보존하고 검사 조건을 적용한 뒤 실패해도 복원한다. native 검사는 권한/설정을 바꾸지 않는다.

```bash
adb -s "$HANGIL_TEST_DEVICE" shell am instrument -w -r \
  -e manualSessionHistory true \
  -e class 'io.github.hangillab.hangil.SessionHistoryFlowTest#endedHistoryKeepsStoredSnapshotsAndSelectionAcrossLifecycleAndDrivingLock' \
  io.github.hangillab.hangil.debug.test/androidx.test.runner.AndroidJUnitRunner
```

1. 실제 P와 UX 허용에서 `home_history`로 들어가 원래 모든 종료 이력을 저장 순서대로 열람한다. 원문/시각/시연 표시와 이전/다음 위치를 대조한다.
2. 각 기록의 `history_course_summary`, `history_summary_heading`, `history_summary_count`와 기본 접힌 `history_summary_tasks`를 확인한다. 기대값은 테스트 시작 전 실제 snapshot에서 그룹 키를 직접 비교해 독립 계산하며 제품의 summary factory를 호출하지 않는다.
3. 과제별 기록을 펼쳐 `history_summary_scope`, `history_summary_task_$index`와 `history_summary_task_count_$index`의 제목/개수/첫 등장 순서를 대조한다. 다른 종료 기록으로 이동해도 그 source에 맞는 요약이어야 한다. 다시 접으면 scope와 동적 과제 태그가 없어야 한다.
4. 지난 기록을 선택한 채 Activity 재생성 및 CREATED → RESUMED를 거친다. 새 조회 뒤 같은 선택과 원문/요약을 확인한다.
5. `HISTORY_AWAIT_D`가 나오면 외부 DDS를 D로 전환한다. 실제 연결된 D 수신과 `history_locked`를 확인하며 원문 상세/조작/요약의 정적/동적 태그가 모두 없어야 한다.
6. `HISTORY_AWAIT_P`가 나오면 원래 P로 복원한다. 실제 P+UX 허용을 확인한 뒤 같은 지난 기록과 올바른 요약으로 돌아오는지 확인한다. 닫으면 요약이 사라지고 재진입하면 최신 기록부터 시작해야 한다.

원문 전체 오프셋/maxLines/줄/문자 경계와 말줄임을 검사하고 스크롤 후 글자와 조작이 실제 가용 viewport 안에 있는지 확인한다. 큰 펼침 panel 전체를 한 화면에 억지로 맞추지 않고 각 텍스트와 버튼을 따로 스크롤해 대조한다. Compose1.9.4 semantics의 넓은 문단 컨테이너 때문에 `hasVisualOverflow`만으로 잘림을 단정하지 않으며 기존 양성/음성 fixture와 엄격한 실제 경계 검사를 유지한다.

기존 원문/시각이 있는 전체 앱 캡처와 실패 직전 화면/익명 레이아웃 진단은 private cache에만 둔다. 새 `screenshots/history-summary.png`는 실제 전면 앱을 확인한 뒤 접힌 summary panel의 semantics 경계만 실제 원본 화면에서 crop한다. 독립 기대 개수와 고정 제목/보기 버튼 이외의 텍스트가 없어야 한다. 이 파일도 공개 전 실제 픽셀 검토가 필요하며 생성됐다는 사실만으로 개인정보 비노출이나 기능 검증 완료를 확정하지 않는다. 실기 미실행 단계에서는 공개용 이미지나 빈 자리표시자를 만들지 않는다.

## 보존, 실패와 해석의 한계

두 native 검사는 실제 domain 저장소에서 준비 선택, 모든 세션의 필드와 저장 생성 순서, 세션별 관찰의 전체 필드/순서와 메모의 원문/변경 토큰을 시작 전/후 대조한다. 자료를 생성하거나 저장/삭제하지 않는다. 실패해도 가능한 보존 검사를 수행하고 정리 오류가 원래 실패를 숨기지 않게 남긴다. 검증을 위해 사용자 데이터를 임의로 복구하거나 초기화하지 않는다.

native domain 비교는 DB의 모든 물리 행/SQLite 자료형/sequence나 권한/설정 보존 검사와 같지 않다. 외부 실행자가 전체 SQLite와 유효 권한/runtime 플래그, 글꼴/야간/IME/프록시, 원래 DDS P/UX를 별도로 대조하고 복원해야 한다. 원본과 설정 대조를 마친 뒤 이번 실행이 소유한 AVD/포워딩/임시 콘솔만 정리한다. 실패하거나 프로세스가 강제 종료돼 미완료 파일이 남으면 원본 근거를 보존해 인계하고 완료로 계산하지 않는다. 재실행을 위해 기존 기준/실패 파일을 임의 삭제하지 않는다.

측정 성공 표시는 `HISTORY_OPENING_MEASUREMENT_SUCCESS stage=baseline|comparison warmups=5 samples=30`, 실기 완료 표시는 `HISTORY_COMPLETED`다. 이는 해당 메서드의 성공 경로이며 외부 원본/설정 복구나 PR/CI/Codex 리뷰 완료를 대신하지 않는다. 결과/실패/미실행/미지원은 계획과 PR에 각각 기록한다.

실제 이력/요약 실기는 정상 원본과 P → D → P에 한정된다. 빈 자료/선택 제거/읽기 오류, 잘못된 source/독립 P/UX 조합과 권한 거부/플랫폼 단절을 실제 주입한 것으로 확대하지 않는다. STOP 중 외부 DDS를 관찰하려고 별도 SDK 구독을 유지하므로 제품의 모든 SDK subscriber/callback 해제를 입증하지 않는다. 실제 UI 검사와 제어한 단위 경계의 근거를 구분하며 성능 해석은 [전후 측정 기준](measured-improvements.md)을 따른다.
