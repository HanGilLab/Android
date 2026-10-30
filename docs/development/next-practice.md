# 최근 종료 연습의 다음 과제 준비와 실제 저장 비용 검증

최근 종료 연습의 같은 목표/코스와 버전을 확인하고 사용자가 다음 과제를 직접 선택해 준비를 저장하는 APP-07의 부분 기능이다. 과제를 자동 선택하거나 새 연습을 자동 시작하지 않는다. 당시 source와 현재 카탈로그가 맞지 않거나 활성 연습이 있으면 새 준비를 저장하지 않는다. 일반 준비의 목표 → 코스 → 과제 경로도 유지한다.

실제 도로 구간 복습, 능력 평가와 자동 추천, 실제 주행 경로, 위치/내비 연동 및 APP-07 전체 완료는 이 범위가 아니다. 저장 비용 측정과 실제 화면 검사는 목적과 조건을 나눠 실행한다.

## 현재 확보한 결과와 미실행 항목

| 항목 | 상태 | 확인 범위 |
|---|---|---|
| 제품 변경 전 기존 저장 API 기준 측정 | 실행 성공 | 실제 SDK의 P와 UX 허용, 실제 Room 저장, 원래 domain 데이터 복원 및 APK 해시 일치 확인 |
| 변경 후 기존/새 저장 API 교차 측정 | 미실행 | 아래 절차와 계측 코드가 있으며 수치나 비용 차이는 아직 확정하지 않는다 |
| 다음 과제 화면의 기본 라이트/확대 다크 실기 | 미실행 | 아래 시나리오와 native 검사가 있으며 실제 화면 성공을 주장하지 않는다 |

기준 제품은 `0838c3c2954cc87afe0994590269ec24b6dab3d2`다. 2026-10-31 한국 시간에 정제한 기준 결과는 다음과 같다. 5회 워밍업 뒤 기존 `PracticeRepository.saveSelection`을 30회 측정했으며 각 호출 전에 원래 준비를 한 번 복원했다. 복원, 조회와 SDK 사전/사후 확인은 타이머 밖이다.

| 실제 기존 API 호출 지연 | 값 |
|---|---:|
| 표본 수 | 30 |
| 중앙값 | 3.8583335 ms |
| Q1 / Q3 | 2.655458 / 5.488834 ms |
| 최솟값 / 최댓값 | 1.716708 / 44.420333 ms |
| 측정 성공 / 실패 / 시각 누락 / 미실행 / 제외 | 30 / 0 / 0 / 0 / 0 |
| 워밍업 성공 / 실패 / 시각 누락 / 미실행 | 5 / 0 / 0 / 0 |

사분위수는 정렬한 표본의 아래/위 절반에서 각각 중앙값을 구한다. 느린 표본도 제거하지 않았다. 이 값은 debug Pleos 에뮬레이터에서 기존 저장 API를 호출한 기준값이며 성능 개선이나 사용자 수행 시간의 근거가 아니다. 새 API의 결과가 없으므로 개선폭이나 개선율을 계산하지 않는다. 원문 입력, 기기별 환경과 개별 표본은 비공개 측정 근거에 보존하며 이 문서에는 넣지 않는다.

## 공통 실행 전 조건

1. 같은 checkout과 전용 AVD에 살아 있는 다른 작업이 없는지 lease와 작업 상태를 확인한다. 무거운 Gradle 검증과 AVD 조작은 순차 실행한다.
2. 앱이나 계측 Activity를 시작하기 전에 외부 실행자가 활성 연습이 없고, 유효한 원래 준비와 현재 카탈로그에 맞는 최신 종료 연습이 있는지 확인한다. 검사 조건을 만들려고 기존 기록을 종료/추가/삭제하거나 데이터를 초기화하지 않는다.
3. 원래 준비, 모든 세션/관찰/메모를 비공개로 보존한다. 외부 실행자는 일관된 SQLite 복사본과 전체 행/자료형/순서/스키마/sequence를 대조할 수 있어야 한다. WAL을 무시한 파일 복사만으로 보존을 대신하지 않는다.
4. 권한/runtime 플래그, 글꼴/야간/IME/프록시와 실제 DDS 상태를 비공개로 보존한다. 기존 앱의 서명과 데이터 호환성을 확인하고 데이터를 지우지 않는 업데이트를 사용한다. 테스트는 새 권한을 요청하거나 설정을 변경하지 않는다.
5. 실제 브라우저 Virtual Console의 DDS 제어 브리지와 앱의 SDK 수신을 확인한다. HTTP 200이나 제어 클릭만으로 적용 성공을 판단하지 않는다. 실제 전면 앱도 확인해 기본 내비나 카메라 오버레이를 검사 대상 화면으로 오인하지 않는다.
6. 외부 실행자가 제품/계측 원본, 설치된 APK와 입력 인자의 대응을 확인한다. 계측이 받는 커밋/원본 식별값은 외부 선언이며 기기 안에서 Git 상태를 추론한 값이 아니다.

macOS에서는 실행별 JDK17을 선택하고 버전을 확인한다. 전역 셸 설정을 바꾸지 않는다.

```bash
HANGIL_JDK17_HOME="$(/usr/libexec/java_home -v 17)"
"$HANGIL_JDK17_HOME/bin/java" -version
env JAVA_HOME="$HANGIL_JDK17_HOME" ./scripts/verify.sh
env JAVA_HOME="$HANGIL_JDK17_HOME" ./gradlew --no-daemon --console=plain :app:assembleDebugAndroidTest
```

아래 명령의 `HANGIL_TEST_DEVICE`에는 외부 실행자가 소유한 전용 장치를 비공개로 지정한다. 실제 식별자를 문서나 공개 로그에 적지 않는다. 명령 자체는 사전 보존과 DDS/설정 복구를 수행하지 않는다.

## 기존 저장 API 기준 측정

[NextPracticeStorageMeasureTest](../../app/src/androidTest/kotlin/io/github/hangillab/hangil/NextPracticeStorageMeasureTest.kt)의 `measureExistingPreparationSaveBaselineAndRestoreOriginalData`만 선택한다. `manualNextPracticeMeasure=true`가 없으면 건너뛰며 성공으로 계산하지 않는다. 전용 debug 패키지, Android API34, 글꼴1.0과 라이트를 코드에서도 확인한다. Activity는 열지 않는다.

새로 시작한 앱 프로세스에서 실행하고 임의의 캐시 비우기는 하지 않는다. 원래 준비와 다른 과제를 최신 종료 source의 같은 목표/코스/버전에서 하나 고정한다. 실제 SDK의 P와 UX 허용을 기다리고 5회 워밍업 뒤 30회를 측정한다. 매 호출 전 실제 저장 API로 원래 준비를 한 번 복원하고 source, 카탈로그와 모든 세션의 고정 조건을 확인한다.

아래는 아직 기준 자료를 만들지 않은 승인된 실행의 명령 형식이다. 현재 완료된 기준 파일을 삭제하거나 덮어써 재실행하는 절차가 아니다.

```bash
adb -s "$HANGIL_TEST_DEVICE" shell am instrument -w -r \
  -e manualNextPracticeMeasure true \
  -e baselineProductCommit "$HANGIL_BASELINE_PRODUCT_COMMIT" \
  -e baselineHarnessRevision "$HANGIL_BASELINE_HARNESS_REVISION" \
  -e class 'io.github.hangillab.hangil.NextPracticeStorageMeasureTest#measureExistingPreparationSaveBaselineAndRestoreOriginalData' \
  io.github.hangillab.hangil.debug.test/androidx.test.runner.AndroidJUnitRunner
```

`baselineProductCommit`은 기준 제품의 완전한 40자리 SHA이고 `baselineHarnessRevision`은 해당 계측 원본의 비어 있지 않은 식별값이다. 실제 제품/계측 APK 해시도 비공개 보고서에 기록한다. 클래스 전체를 실행하면 같은 opt-in을 사용하는 두 측정 메서드가 함께 선택될 수 있으므로 반드시 메서드를 지정한다.

앱 내부 cache의 `measurements/next-practice-baseline.json` 자체가 소유 예약과 결과 파일이다. 파일 또는 AtomicFile의 `.bak`/`.new`가 이미 있으면 시작을 거부한다. 보고서는 첫 쓰기 전, 워밍업 후와 최종 정리 때 기록하며 각 타이머 안에서 JSON을 쓰지 않는다. 완료된 기준 원문은 교차 측정에 필요한 불변 근거이므로 유지한다.

측정 구간은 Android `System.nanoTime()`을 실제 `PracticeRepository.saveSelection` 호출 바로 앞뒤에 둔 monotonic wall time이다. SDK 연결 대기, 사전/사후 확인, 원래 준비 복원, 재조회, UI, JSON 작성과 JUnit 전체 시간은 제외한다. API 내부의 실제 guard/Room 트랜잭션 비용은 포함한다. 반환값과 실제 저장 target이 일치하고 반환 후 P+UX도 허용된 경우에만 성공 표본으로 기록한다.

## 기존/새 API 교차 측정

같은 파일의 `measurePairedPreparationSaveCostsAndRestoreOriginalData`만 선택한다. 이전 기준 원문이 완료 상태이고 복원/보존 확인, 5회 워밍업/30개 원값과 통계가 모두 유효해야 한다. 기준 파일은 읽기 전후 해시로 변경되지 않았는지 확인하며 덮어쓰지 않는다.

교차 측정은 새 프로세스에서 기준과 동일한 원래 준비, 최신 종료 source의 전체 snapshot, target, 해석된 카탈로그, 전체 domain 데이터와 기록된 실행 조건을 확인한다. 조건이 다르면 기준을 새 값에 맞춰 바꾸지 않고 실패한다. target도 기준 파일에서 읽으며 다시 임의 선택하지 않는다.

```bash
adb -s "$HANGIL_TEST_DEVICE" shell am instrument -w -r \
  -e manualNextPracticeMeasure true \
  -e pairedProductCommit "$HANGIL_PAIRED_PRODUCT_COMMIT" \
  -e pairedHarnessRevision "$HANGIL_PAIRED_HARNESS_REVISION" \
  -e class 'io.github.hangillab.hangil.NextPracticeStorageMeasureTest#measurePairedPreparationSaveCostsAndRestoreOriginalData' \
  io.github.hangillab.hangil.debug.test/androidx.test.runner.AndroidJUnitRunner
```

`pairedProductCommit`은 비교 제품의 완전한 40자리 SHA이며 `pairedHarnessRevision`은 비교 계측 원본 식별값이다. 기존 API와 새 `NextPracticeRepository.saveSelection(seed, target, liveGuard)`의 인스턴스를 타이머 밖에서 준비한다. 새 API는 실제 `NextPracticeSeed.resolve`로 확인한 source를 사용한다.

두 API를 각각 5회 워밍업한 뒤 30쌍을 수집한다. 홀수쌍은 old → new, 짝수쌍은 new → old 순서이며 워밍업도 같은 방식으로 교차한다. 각 호출 전에 동일한 원래 준비를 한 번 저장하고 조건을 재확인한다. 측정은 각 실제 저장 API 호출 구간뿐이며 source seed 준비, VM/화면 처리와 복원은 포함하지 않는다. 기존 API는 `true`, 새 API는 `NextPracticeSaveResult.Saved`를 반환하고 실제 target이 일치해야 한다.

새 보고서는 cache의 `measurements/next-practice-paired.json`에 쓰며 기존 파일이나 `.bak`/`.new`를 덮지 않는다. old/new 각각의 30개 원값, 쌍별 호출 순서와 `new - old` 차이, 중앙값/Q1/Q3/사분위 범위/min/max, 실패/누락/미실행/제외를 남긴다. 차이가 양수면 새 검증 API의 추가 지연이다. 쌍의 old가 0이면 그 쌍의 상대 변화율은 계산하지 않는다. 느린 표본을 제외하거나 원하는 개선 방향으로 값을 바꾸지 않는다.

변경 전 기준의 old 분포와 변경 후 old 분포도 참고값으로 남긴다. 이 기준 이동과 잡음을 확인하기 전 인과적 비용 차이나 성능 개선으로 확정하지 않는다. 두 API는 무결성 검증 범위가 다르므로 이 검사는 동일 기능의 최적화 속도를 입증하는 시험이 아니라 새 원자 검증 경계의 실제 저장 비용을 비교하는 시험이다.

## 실제 화면과 DDS 검증

[NextPracticeFlowTest](../../app/src/androidTest/kotlin/io/github/hangillab/hangil/NextPracticeFlowTest.kt)의 `nextTaskRequiresExplicitChoiceAndPreservesDataAcrossLifecycleAndDrivingLock`만 선택한다. Activity rule이 실제 MainActivity를 실행하므로 활성 연습 없음과 원본 보존은 계측 호출 전에 외부에서 먼저 확인해야 한다.

```bash
adb -s "$HANGIL_TEST_DEVICE" shell am instrument -w -r \
  -e manualNextPracticeFlow true \
  -e class 'io.github.hangillab.hangil.NextPracticeFlowTest#nextTaskRequiresExplicitChoiceAndPreservesDataAcrossLifecycleAndDrivingLock' \
  io.github.hangillab.hangil.debug.test/androidx.test.runner.AndroidJUnitRunner
```

기본 글꼴1.0/라이트와 글꼴1.3/다크에서 같은 메서드를 각각 실행한다. 외부 실행자가 원래 설정을 보존하고 검증 조건을 적용/복구한다. native 검사 자체는 설정이나 권한을 변경하지 않는다.

1. 원래 준비로 일반 목표 → 코스 → 과제 화면까지 이동한 뒤 저장 없이 취소한다. 원래 준비와 기존 기록이 그대로인지 비교한다.
2. `home_next_practice`로 다음 준비에 들어간다. source 코스와 실제 시연 표시를 확인하고 source 상세를 펼쳐 당시 목표/과제를 대조한다. 모든 과제가 미선택이며 저장 버튼은 비활성화돼야 한다.
3. 한 과제를 직접 선택한 뒤 닫는다. 기존 준비는 바뀌지 않아야 하며 재진입하면 다시 미선택으로 시작해야 한다.
4. 다시 수동 선택하고 Activity 재생성 및 CREATED → RESUMED를 거친다. source를 다시 확인한 뒤 같은 수동 선택을 유지해야 한다.
5. `NEXT_AWAIT_D`가 나오면 외부 DDS를 D로 전환한다. 실제 연결된 D 수신 뒤 `next_practice_locked`를 확인하며 source 상세, 과제와 저장 조작은 없어야 한다. 닫기는 계속 도달 가능해야 한다.
6. `NEXT_AWAIT_P`가 나오면 DDS를 원래 P로 복원한다. 실제 P와 UX 허용을 모두 확인한 뒤 같은 source/선택으로 돌아와야 한다.
7. 저장을 명시 클릭하고 홈의 코스/과제와 실제 Room 준비가 고정 target인지 대조한다. 새 활성 연습이 생기면 안 된다. 이후 검사 소유 target만 원래 준비로 복원하고 모든 기존 기록을 비교한다.

문자 검사는 원문 전체 오프셋, maxLines, 말줄임 여부와 실제 줄/문자 경계를 대조한다. 별도의 물리 viewport 검사로 스크롤 후 글자와 조작 전체가 실제 가용 앱 화면 안에 있는지 확인한다. Compose1.9.4의 semantics 문단 컨테이너 폭 때문에 `hasVisualOverflow`만으로 잘림을 단정하지 않으며, 실제 잘림/말줄임/부분 원문 누락을 거부하는 native fixture도 함께 수행한다. 모든 상세와 버튼이 한 화면에 동시에 보인다는 뜻은 아니다.

실제 전면 앱을 확인한 뒤 앱 영역 캡처를 private cache의 `screenshots/next-practice-ready.png`, `next-practice-source.png`, `next-practice-locked.png`, `next-practice-saved.png`에 저장한다. 실패 직전 진단은 같은 폴더의 `next-practice-layout-failure.png`와 익명 수치 JSON에 남긴다. 원문 제목이나 사용자별 정보가 담길 수 있어 그대로 공개하지 않는다.

## 소유 기록, 실패와 원본 보존

화면 검사는 cache의 `next-practice-flow-owner.json`을 먼저 예약한다. 원래 준비, 검사 target과 보존용 원문은 이 비공개 소유 기록에만 둔다. 파일 또는 `.bak`/`.new`가 남아 있으면 새 실행을 거부한다. 성공한 시나리오와 원래 준비 복원/나머지 데이터 보존을 모두 확인한 뒤 자기 nonce가 맞는 소유 파일만 삭제한다. 실패했지만 복원에 성공한 경우에도 소유 기록은 남겨 인계한다.

두 native 파일은 시작 전/후 모든 세션의 domain 필드와 순서, 세션별 차량 관찰과 메모의 원문/변경 토큰, 준비 선택을 비교한다. 준비를 실제로 변경한 경우에는 현재 값이 검사에서 저장한 target이고 원래 세션/source가 그대로일 때만 실제 P+UX 허용에서 원래 준비를 다시 쓴다. 알 수 없는 새 준비나 변경된 source를 덮어 복원 성공처럼 만들지 않는다.

저장 거부, 예외, source/target 불일치, P+UX 미허용이나 복구 불일치는 실패로 기록하고 중단한다. `finally`의 복구 실패는 원래 실패를 숨기지 않도록 suppressed 오류로 남긴다. 프로세스가 강제 종료되면 `finally`나 최신 표본 기록이 끝나지 않을 수 있으므로 남은 파일을 완료 근거로 쓰지 않는다. 외부 실행자가 원본과 실제 DB를 확인하고 안전한 인계/복구를 마치기 전 재실행하지 않는다. 재시도를 위해 소유 파일이나 이전 기준을 임의 삭제하지 않는다.

native의 domain 비교는 DB의 모든 물리 행, SQLite 자료형/sequence나 설정/권한 보존 검사와 같지 않다. 외부 실행자가 별도로 전체 SQLite와 권한/runtime 플래그, 글꼴/야간/IME/프록시, DDS의 원래 P/UX를 대조하고 복원해야 한다. 기존 AVD와 사용자 데이터는 보존하며 해당 실행이 만든 리소스만 정리한다.

## 출력과 해석의 한계

측정 성공 표시는 각각 `NEXT_PRACTICE_BASELINE_SUCCESS warmups=5 samples=30`, `NEXT_PRACTICE_PAIRED_SUCCESS warmupsPerApi=5 pairs=30`이다. UI 완료 표시는 `NEXT_PRACTICE_FLOW_COMPLETED`다. 원문 입력이나 개별 지연 표본을 성공 로그로 출력하지 않는다. 표시는 해당 메서드의 성공 경로이며 외부 DB/설정 복구, PR 검사와 최종 제품 완료를 대신하지 않는다.

저장 비용은 해당 debug 에뮬레이터의 warm DB 연결 조건에서 측정한 API 구간이다. 실제 차량, 사용자 수행 시간, 화면 렌더링, SDK 수집 성능으로 확대하지 않는다. 별도 read-only SQLite 연결에서 journal/page/자동 checkpoint 값을 읽되 설정을 변경하지 않는다. `wal_autocheckpoint`는 연결별 값이며 domain graph가 Room 연결의 해당 값을 노출하지 않으므로 별도 조회 값을 Room 연결 설정의 증거로 쓰지 않는다. 조회가 지원되지 않으면 미확인 상태를 그대로 남긴다.

UI 검사는 실제 원래 source에 대한 정상 흐름과 P → D → P를 확인하는 절차다. source 교체/잘못된 카탈로그/빈 상태/저장 실패 주입, 독립 P/UX 조합과 권한 거부/플랫폼 단절을 실제 수행한 것으로 확대하지 않는다. STOP 중 외부 DDS를 관찰하는 별도 SDK 구독도 있으므로 제품의 모든 SDK 구독 해제나 callback 정리를 입증하지 않는다.

실제 실행 결과, 원값의 비공개 보존 위치와 검증 실패/미실행/미지원은 작업 계획과 PR에 구분해 기록한다. 성능 해석은 [전후 측정 기준](measured-improvements.md)을 따른다.
