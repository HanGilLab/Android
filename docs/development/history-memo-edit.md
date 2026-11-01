# 선택한 과거 연습의 기존 메모 수정

과거 종료 기록에서 실제로 읽은 사용자 메모를 수정하고 다시 읽는 기능이다. 제품 `ec7868499097405d30eb95d7a3e4de1d1fc8072c`의 전체 자동 검사와 제어 Compose/소유 Room을 확인했고, native v3 `565529c`에서 실제 Main/SDK/IME의 라이트와 확대 다크 취소 흐름을 확인했다. 공통 조회 계측 A1/B/A2와 최종 B 설치/원본/설정 복원을 완료했다. 최신 PR 검증과 병합은 아직 별도 조건이며 [작업 계획](../plans/active/2026-11-01-history-memo-edit.md)은 active다.

## 제품 범위와 저장 계약

선택한 종료 기록의 Ready인 기존 메모에만 ‘수정’을 제공한다. 입력한 내용을 명시적으로 저장하거나 취소하며 빈 메모 신규 작성, 메모 삭제와 자동 저장은 없다. 사용자 메모 출처와 선택한 코스/과제 맥락을 유지한다. 기존 준비, 연습, SDK 관찰, 배경 실행과 과정 선언을 변경하지 않는다. 실제 도로 구간 복습이나 APP-06/08 전체 완료를 뜻하지 않는다.

[HistorySessionMemoWriter](../../core/domain/src/main/kotlin/io/github/hangillab/hangil/core/domain/HistorySessionMemoWriter.kt)의 update(expectedSource, expectedMemo, text, canCommit)는 nonnull 전체 메모 원형을 받는다. 기존 [SessionMemoRepository](../../core/domain/src/main/kotlin/io/github/hangillab/hangil/core/domain/SessionMemoRepository.kt)의 최신 종료 메모 save/delete/observe 계약은 그대로다. 최신 전용 ViewModel을 과거 session ID로 우회 호출하지 않는다.

[Room writer](../../core/database/src/main/kotlin/io/github/hangillab/hangil/core/database/RoomHistorySessionMemoWriter.kt)는 기존 schema6/[MemoDAO](../../core/database/src/main/kotlin/io/github/hangillab/hangil/core/database/SessionMemoDao.kt)를 사용한다. 같은 트랜잭션에서 다음을 검사한다.

- 실제 session 존재/종료와 전체 source 일치, 모든 활성 연습의 부재를 확인한다. UI가 받기 전 새 활성 연습도 저장소에서 거부한다.
- 실제 메모의 session ID/text/version 전체가 expectedMemo와 같아야 한다. 부재/변경은 Conflict이고 insert하지 않는다. 저장 자료형 손상과 SQL 예외는 부재로 대체하지 않는다.
- [본문 정책](../../core/domain/src/main/kotlin/io/github/hangillab/hangil/core/domain/SessionMemoPolicy.kt)의 비어 있지 않은 최대280 codepoint/Unicode 검증을 유지한다. trim/truncate 없이 원문을 저장하고 같은 본문도 새 불투명 UUID version을 발급한다.
- 쓰기 전후 현재 coroutine과 canCommit을 검사하며 COMMIT 전 취소/거부는 롤백한다. 결과 전달 취소만으로 COMMIT 실패를 추정하지 않는다.

[기존 삭제 CAS](../../core/database/src/main/kotlin/io/github/hangillab/hangil/core/database/RoomSessionDeletionRepository.kt)는 memo version에 묶여 있어 삭제 확인 이후 수정된 메모를 Changed로 거부한다. 새 테이블이나 migration은 없다. 저장된 수정 시각 필드도 없으므로 새 시각을 만들거나 표시하지 않는다.

## 읽기, 편집과 취소의 수명

[부모 ViewModel](../../feature/home/src/main/kotlin/io/github/hangillab/hangil/feature/home/SessionHistoryViewModel.kt)은 이미 받은 전체 session 목록의 활성 여부를 사용한다. 미수신/실패를 활성 없음으로 바꾸지 않으며 상세에서 sessions를 추가 구독하지 않는다. 다른 펼침의 조회와 부모 query/generation을 유지한다.

[전용 reader](../../feature/home/src/main/kotlin/io/github/hangillab/hangil/feature/home/SessionHistoryMemoReader.kt)는 펼쳐진 동안만 메모를 읽는다. full source, 부모 reader/query/generation, STARTED host와 현재 P+UX가 유효해야 하며 새 편집에는 활성 없음이 확인돼야 한다. 입력/취소/저장은 표시 token과 edit nonce에 묶이고 저장은 표시했던 초안도 대조한다. 동일 ID의 source 교체, 선택/부모 실패, STOP/폐기, 관찰된 주행/UX 거부는 옛 동의를 폐기한다. P나 화면이 돌아와도 옛 입력/저장 콜백이나 초안을 자동 복원하지 않는다.

같은 메모 원형의 반복 Flow는 초안과 편집 동의를 유지한다. 다른 원형/version, 조회 오류/유한 종료/조회 Job 취소는 저장 의도를 폐기하고 명시적인 재조회를 요구한다. 같은 유효 owner에서 이미 발행한 첫 실패는 후속 조회 오류가 입력 원문을 지우지 못하게 유지한다. 현재 source/host/gate 폐기 검사는 그 terminal 보존보다 먼저 수행한다.

ACK가 먼저 오면 옛 query identity를 먼저 해제한 뒤 취소하고 새 실제 원형을 조회한다. ACK와 일치한 원형을 받아야 저장 확인을 표시한다. Flow가 먼저 오면 실제 수신한 저장 원형을 우선하며 자신이 쓴 성공이라고 추정하지 않는다. COMMIT 뒤 ACK 취소나 재조회 실패에는 입력 내용과 저장 여부를 구분해 ‘입력한 내용이에요. 저장 여부는 다시 읽어 확인해 주세요.’라고 표시한다. 재조회는 저장 재시도가 아니며 자동 재저장하지 않는다.

편집/저장과 삭제 준비는 상호 배제한다. 삭제 준비에서 폐기한 메모 읽기는 안전한 준비/확인 취소와 준비 실패 뒤 한 번만 새 receipt로 복원한다. 뒤늦은 실패 안내 닫기는 새 편집을 다시 폐기하지 않는다. DELETING 중 기존 취소가 job/commit predicate를 폐기하는 계약은 유지하며 COMMIT/응답 불확실성, Missing/sourceAbsent/Deleted에는 자동 읽기를 만들지 않는다. 기존 과정 선언의 삭제 복원도 유지한다.

[route](../../feature/home/src/main/kotlin/io/github/hangillab/hangil/feature/home/HistoryStoredMemoRoute.kt)와 [입력 UI](../../feature/home/src/main/kotlin/io/github/hangillab/hangil/feature/home/HistorySessionMemoEditor.kt)는 기존 열람 태그/본문을 유지한다. 글꼴/여백을 줄이지 않고 IME bringIntoView, Done의 focus 해제와 keyboard 숨김을 사용한다. Done은 저장하지 않는다. 내부 token/CAS 용어와 식별자를 제품 문구로 표시하지 않는다.

## 자동 검사와 실제 RED/GREEN

처음 새 실제 Room21개와 feature408개(새 편집22개 포함)가 통과했다. 이후 독립 읽기 검토가 같은 child query의 후속 오류/종료/취소에 보존 입력이 사라지는 경계와 refresh cancel-finally 재진입을 발견했다. 제품 수정 전에 새4개를 추가해 실제26개 중4실패/기존22통과를 확인했고, 원래 Reader/테스트/XML을 보존했다.

Reader 한 파일에서 현재 context 확인 후 최초 실패를 유지하고 query identity를 cancel보다 먼저 분리했다. 같은 테스트 source 바이트로26개 모두 통과했으며 기대/게이트를 완화하지 않았다. 이는 제어 coroutine/JVM의 기능 재현으로 실제 Android I/O 경합을 강제 재현한 근거가 아니다. [기능 경계 테스트](../../feature/home/src/test/kotlin/io/github/hangillab/hangil/feature/home/SessionHistoryMemoEditViewModelTest.kt)와 [실제 Room 테스트](../../core/database/src/test/kotlin/io/github/hangillab/hangil/core/database/RoomHistorySessionMemoWriterTest.kt)를 연결한다.

정규화 이후 JDK17 전체 verify4단계가 통과했다. 제품 모듈59 suites/923 JVM와 별도 build-logic1 suite/4개는 각각 실패/오류/건너뜀0이고 native APK 컴파일도 통과했다. cache/up-to-date를 포함할 수 있는 회귀 근거이며 성능 수치가 아니다. 앞선 strict 포맷 실패와 native import 누락 컴파일 실패는 계획에 별도 보존했으며 본문 미실행을 통과로 바꾸지 않았다.

## native 실제 범위와 실패의 구분

| 범위 | 현재 실제 결과 | 의미와 한계 |
|---|---|---|
| [A 제어 Compose](../../app/src/androidTest/kotlin/io/github/hangillab/hangil/HistoryMemoEditBoundaryTest.kt) | v1에서5PASS | 실제 Compose/Activity와 제어 lifecycle/source/저장소/gate. 옛 입력/저장/취소, 반복 원형, ACK/Flow, 삭제 복원과 중립 문구. 실제 OS PAUSE/SDK 장애 주입이 아님 |
| [B UUID 소유 Room](../../app/src/androidTest/kotlin/io/github/hangillab/hangil/HistoryMemoEditRoomFlowTest.kt) | v1 라이트3PASS, 다크 첫1준비 실패/2PASS 뒤 같은 검사3PASS | production writer와 실제 Room/route, 합성 차량/UX. 저장/새 version/다른 자료 보존/삭제 CAS와 COMMIT 뒤 adapter 응답 취소/재열람. 실제 SDK 저장 경합이 아님 |
| [C 실제 Main/SDK/IME](../../app/src/androidTest/kotlin/io/github/hangillab/hangil/HistoryMemoEditFlowTest.kt) | v3 라이트1PASS/확대1.3 다크1PASS | 실제 CTRL 전체 선택 확인/ASCII 대체/취소, source 변경/Activity 재생성/DDS D→P/옛 편집 폐기와 실제 원형 재조회. 생산 writer/save/delete 호출0 |

B 다크의 첫 실패는 실제 Catalog RESUMED/display/window focus를 확인하지 못한 mount 전 실패다. 원형을 보존하고 같은 APK/기대의 재시도3PASS와 구분한다. C v1 첫 다크는 실제 전체 선택 전제가 성립하지 않아 원문42 codepoint에 입력이 붙어61이 된 exact draft 실패였다. 이를 제품 저장/SDK 실패나 입력 성공으로 쓰지 않는다. v2 라이트는 실제 전체 선택/대체를 통과했지만 main-thread runOnIdle 안의 semantics 조회로 버튼 가시성 준비가 실패했다. 노드 조회만 밖으로 옮긴 v3가 같은 원문/IME/가시성/20초 기대에서 양 테마를 통과했다. 이전 실패를 지우거나 유리한 성공으로 교체하지 않았다.

실제 생산 메모는 읽기/편집 진입/입력 취소에만 사용했다. C의 전체 원형 SQLite 값/물리형/순서/sequence 동일성을 확인했고 저장 성공은 별도 UUID Room의 근거다. 개발 이미지와 실제 메모 원문은 비공개다. 생산 저장 뒤 process death/재실행 성공이나 새로운 SDK/권한 기능으로 확대하지 않는다. A2 뒤 같은 B와 v3 native를 다시 설치해 실제 Main 라이트 C1을 별도 통과했다. 이는 설치 복원 확인이며 새 성능 표본이 아니다. 설치 제품/native APK가 고정 artifact hash와 일치했고 원래 전체 schema6 자료/물리형/순서/sequence/권한/runtime/font/night/IME/proxy를 exact 대조했다. 소유 DB/marker/sidecar 잔존0과 기록 서비스 미실행을 확인하고 이번 소유 AVD/forward/DDS 탭을 정리했다. 이를 모든 가상 차량 신호의 초기값 복원으로 확대하지 않는다.

## 공통 메모 조회의 실제 A1/B/A2 측정

느린 처리 병목의 증거는 없었고 새 편집 저장에는 이전 대응 동작이 없었다. 변경 전에 계획을 고정하고 [M1 읽기 하네스](../../app/src/androidTest/kotlin/io/github/hangillab/hangil/SessionHistoryMemoReadMeasureTest.kt)로 기존 메모 펼침의 공통 흐름만 관찰했다. M0는 실제 Main display/focus/host 계약이 직접 확인되지 않은 기존 하네스여서 A1 실행 전에 제외했으며 이번 표본은0개다. 새 편집 저장 속도는 미측정이고 이전 값을0으로 두거나 개선율을 만들지 않는다.

A1/A2는 `55af9b01b766fef22360de36eb079cb359e2efa3`의 정확히 같은 고정 debug APK, B는 제품 `ec7868499097405d30eb95d7a3e4de1d1fc8072c`의 고정 debug APK다. 구현 전에 고정한 계측 commit `86905edd848e7344a81501d14d75d935f7f83125`의 같은 source 바이트와 실제 test APK를 세 실행 모두 사용했다. native v3 APK와 고정 timing APK는 별개다. 각 보고서의 실제 설치 APK hash, 전체 원형/source/memo/index/catalog/환경/계측 계약과 B가 참조한 A1 원형 hash를 대조했다. source 선언은 실행자 메타데이터이며 source-to-binary 재현 증명은 아니다.

| 단계 | warm/본 | 중앙값 ms | Q1 / Q3 ms | IQR ms | 최소 / 최대 ms | 실패/누락/미실행/제외 |
|---|---:|---:|---|---:|---|---|
| A1 | 5/30 | 929.0149585 | 902.218251 / 936.395042 | 34.176791 | 870.233959 / 1019.2425 | 0/0/0/0 |
| B | 5/30 | 924.2116465 | 900.791126 / 934.713251 | 33.922125 | 865.867209 / 1071.010876 | 0/0/0/0 |
| A2 | 5/30 | 915.8628965 | 904.391667 / 930.308042 | 25.916375 | 866.653125 / 1022.542209 | 0/0/0/0 |

각 실행은 첫 timing 시도에서5warm/30본이 완료됐다. [익명 원값 CSV](measurements/history-memo-edit-read-times.csv)는15warm과90본의105행이며 integer ns와 정확한 파생 ms를 담는다. 본30만 집계하고 Q1/Q3는 정렬한 하위15/상위15 각각의 중앙값이다. 느린 유효 성공도 제외하지 않았다. 원보고서/CSV/통계를 독립적으로 다시 계산해 대조했으며 native 하네스 준비 실패와 timing 성공 표본을 섞지 않았다.

| 비교 순서 | 중앙값 후-전 ms | 상대 변화 |
|---|---:|---:|
| A1 → B | -4.8033120 | 약 -0.517033% |
| B → A2 | -8.3487500 | 약 -0.903337% |
| A1 → A2 | -13.1520620 | 약 -1.415700% |

감소 방향의 관찰값이며 성능 개선율로 확정하지 않는다. A1→B 차이의 크기4.803312ms보다 같은 이전 APK의 반복 차이13.152062ms가 크고 세 Q1/Q3 구간이 겹친다. 인과적인 향상/저하, 동등성이나 무회귀를 판정할 수 없다. 기준값은0이 아니며 상대 변화는 (후-전)/전이다.

지표는 클릭/Compose idle/원문 전체 가시성과 TextLayout, 실제 repository의 memo/version/전체 session 및 Main 전면/P+UX 재확인 비용을 포함한 하네스 wall time이다. 새 편집 저장, 앱 UI 단독, SDK/DB 단독, frame/GPU 또는 운전자 수행 시간으로 확대하지 않는다. 테스트 수, 행 수, JUnit/Gradle/CI 시간도 성능 수치가 아니다.

JDK17/debug/Pleos API34와 기본1.0/라이트, 같은 실제 원본/가용 영역/고정 비latest 메모를 사용했다. 각 실행은 fresh 앱 process/Activity에서5warm 뒤 같은 process30본이다. A1과 B는 다른 기기 부팅 세션이며 B는 native 검증 뒤, A2는 B와 같은 부팅 세션에서 같은 A APK를 재설치한 뒤 실행했다. cold device/OS 부하/ART/JIT/프로필/열/파일 및 DB 캐시 동등성을 보장하지 않으며 원본 자료나 캐시는 지우지 않았다. 이 순서와 캐시 조건을 별도로 통제한 교차 실험이 아니므로 작은 차이에 인과 해석을 붙이지 않는다. 공개 CSV의 통계는 재계산할 수 있지만 같은 입력/환경 재현에는 보존한 비공개 원형과 실행 manifest가 필요하다.

## 재현 절차

JDK17에서 `./scripts/verify.sh`와 native APK 컴파일을 별도로 마친다. 외부 실행자는 원래 전체 schema6 자료, 권한/runtime/설정과 설치 APK/hash를 고정한다. 실제 Main display의 단일 focused window, 같은 source와 SDK P+UX를 확인하고 미확인을 기대값으로 채우지 않는다. 기본1.0/라이트와 확대1.3/다크를 별도 실행하며 원래 설정은 finally에서 복원한다. 아래 변수는 검증한 비공개 artifact/조건을 가리킨다. ANDROID_SERIAL과 ANDROID_USER도 해당 전용 기기와 현재 앱 사용자를 검증한 값이며 실제 값과 개인 경로는 공개하지 않는다.

native에는 실제 검증 APK를 설치하고 표의 클래스/명시 옵션 하나를 사용한다. 옵션 없는 건너뜀은 실제 통과가 아니다. C는 보존된 완료 A1 baseline과 기존 생산 자료를 요구하며 최종 저장/삭제를 호출하지 않는다.

| NATIVE_CLASS | NATIVE_OPT_IN |
|---|---|
| io.github.hangillab.hangil.HistoryMemoEditBoundaryTest | manualHistoryMemoEditBoundary |
| io.github.hangillab.hangil.HistoryMemoEditRoomFlowTest | manualHistoryMemoEditRoomFlow |
| io.github.hangillab.hangil.HistoryMemoEditFlowTest | manualHistoryMemoEditFlow |

```sh
adb -s "$ANDROID_SERIAL" install -r "$PRODUCT_APK"
adb -s "$ANDROID_SERIAL" install -r -t "$NATIVE_TEST_APK"
adb -s "$ANDROID_SERIAL" shell am instrument --user "$ANDROID_USER" -w -r \
  -e class "$NATIVE_CLASS" -e "$NATIVE_OPT_IN" true \
  -e expectedFontScale "$EXPECTED_FONT_SCALE" -e expectedDark "$EXPECTED_DARK" \
  io.github.hangillab.hangil.debug.test/androidx.test.runner.AndroidJUnitRunner
```

M1은 같은 고정 test APK로 아래 조합을 사용한다. 각 실행은 fresh 앱 process/Activity에서5warm 뒤 같은 process30본이며 cold device boot 동등성을 뜻하지 않는다. 실제 원래 메모1개의 nonlatest source/원문/version과 같은 가용 영역을 고정한다. 펼침 전 tasks anchor 스크롤은 timer 밖이고 클릭 뒤 추가 스크롤은 없다. 클릭/Compose idle/완전 가시성과 TextLayout, 실제 repository의 전체 memo/version/session 및 전면/gate 재확인까지20초 timer에 포함한다. 실패 latency는 null이고 대기값이나0을 성공으로 쓰지 않는다.

| 실행 | measurementSeries | measurementStage | 제품 |
|---|---|---|---|
| A1 | history-memo-edit-read-v1 | baseline | 고정 A |
| B | history-memo-edit-read-v1 | comparison | 고정 B |
| A2 | history-memo-edit-read-repeat-v1 | baseline | 같은 고정 A |

```sh
adb -s "$ANDROID_SERIAL" install -r "$MEASUREMENT_PRODUCT_APK"
adb -s "$ANDROID_SERIAL" install -r -t "$FROZEN_MEASUREMENT_TEST_APK"
adb -s "$ANDROID_SERIAL" shell am force-stop --user "$ANDROID_USER" io.github.hangillab.hangil.debug
adb -s "$ANDROID_SERIAL" shell am instrument --user "$ANDROID_USER" -w -r \
  -e class io.github.hangillab.hangil.SessionHistoryMemoReadMeasureTest \
  -e manualHistoryMemoReadMeasure true \
  -e measurementSeries "$MEASUREMENT_SERIES" -e measurementStage "$MEASUREMENT_STAGE" \
  -e declaredProductCommit "$DECLARED_PRODUCT_COMMIT" \
  -e declaredHarnessRevision 86905edd848e7344a81501d14d75d935f7f83125 \
  io.github.hangillab.hangil.debug.test/androidx.test.runner.AndroidJUnitRunner
```

완료/실패 JSON과 AtomicFile sidecar, 원형 baseline/설치 APK를 덮어쓰지 않는다. 원문/조건/provenance와 실패/누락/미실행/제외를 함께 회수하고 전체 원본을 다시 대조한다. A2 뒤에는 검증한 B 제품/native를 다시 설치해 읽기/편집 진입/취소와 실제 APK/hash, 원래 자료 및 소유 UUID DB/marker/sidecar 잔존을 확인한다. 이번 최종 B 복원은 실제 설치 hash/읽기/입력 취소/잠금/원본/설정 대조와 소유 자원 정리를 통과했으며 별도 성능 표본으로 세지 않는다. 최신 PR/병합 결과는 실제 GitHub 기록과 작업 계획에서 관리한다.
