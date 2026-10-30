# 주차 중 종료 연습 이력 검증

종료된 연습을 한 번에 하나씩 읽는 APP-08의 부분 기능이다. `PracticeSessionRepository.observeSessions()`가 제공하는 저장 생성 순서를 유지하며 활성 기록은 제외한다. 시작/마무리 버튼의 벽시계 시각으로 다시 정렬하지 않고, 저장 당시 목표/코스/과제의 제목과 시연 여부를 그대로 표시한다. 현재 준비나 카탈로그를 사용해 과거 기록을 다시 해석하지 않는다.

과정 요약/완료 판정, 기록 삭제, 이전 세션의 차량 관찰이나 메모 열람/편집, 실제 주행 경로와 운전 점수는 이 범위에 포함하지 않는다. APP-07과 APP-08 전체 완료를 뜻하지 않는다. 기존 읽기 계약만 사용하며 DB 쓰기/스키마 변경이나 새 차량/위치/내비 권한은 추가하지 않는다.

## 조회와 선택 경계

현재 P와 UX 허용을 모두 확인한 경우에만 종료 목록을 구독한다. D, 끊김, 미확인과 거부에서는 상세를 숨기고 목록 조회를 중단한다. 로딩, 종료 이력 없음, 읽기 실패/스트림 종료와 선택한 기록의 변경을 정상 기록으로 대체하지 않는다. 손상된 필드/시각과 중복 ID를 임의 보정하거나 제거해 정상 이력처럼 표시하지 않는다.

화면에 남은 이전/다음 버튼은 실제 처리 직전에 live P+UX, 살아 있는 reader/query, 조회 세대와 표시된 기록 ID/generation을 다시 확인한다. 수신된 목록과 커서를 같은 경계에서 갱신하며 이미 전달 중인 이전 조회가 새 조회의 상태를 덮지 못하게 한다. 아직 저장소가 방출하지 않은 변경을 동기 감지한다고 보장하지 않는다.

새 기록이 앞에 추가돼도 선택한 ID를 유지하고 저장 순서의 위치만 갱신한다. 선택한 종료 기록이 사라지면 `SelectionChanged(generation)`으로 멈추며 다른 기록으로 자동 이동하지 않는다. 화면에서 본 generation으로 `resetToLatest(expectedGeneration)`을 명시 실행해야 최신 수신 목록을 수락한다. 이전 선택 변경 화면의 확인 콜백은 이후 다른 선택 변경 상태나 이미 이동한 Ready를 다시 바꾸지 못한다.

선택 ID는 해당 화면의 ViewModel에만 보관한다. Activity 재생성, STOP/복귀와 D/P 전환 후에는 새 조회로 유효성을 확인한 뒤 같은 기록을 표시한다. 프로세스 재시작을 넘겨 커서를 영속 저장하지 않으며, 화면을 닫아 ViewModel이 해제된 뒤 다시 열면 최신 종료 기록부터 시작한다.

구현은 [종료 이력 ViewModel](../../feature/home/src/main/kotlin/io/github/hangillab/hangil/feature/home/SessionHistoryViewModel.kt)과 [화면 상태](../../feature/home/src/main/kotlin/io/github/hangillab/hangil/feature/home/SessionHistoryUiState.kt)를 따른다.

## 실행 전 준비

1. 진행 계획, 실제 checkout과 다른 살아 있는 작업의 lease를 확인한다. 같은 앱/AVD를 다른 실행과 동시에 조작하지 않는다.
2. 제품 앱이나 계측 Activity를 시작하기 전에 기존 준비, 모든 세션/관찰/메모와 SQLite 스키마/sequence, 유효 권한/runtime 플래그, 글꼴/야간/IME/프록시 및 DDS 상태를 비공개로 보존한다. 일관된 SQLite 복사본과 전체 값을 대조할 수 있어야 하며 WAL을 무시한 파일 복사로 대신하지 않는다.
3. 기존 종료 세션이 두 개 이상이고 활성 세션이 없는지 외부 실행자가 먼저 확인한다. 활성 세션이 있으면 앱 실행에 따른 수집이 시작되기 전에 중단하고 인계한다. 이 검사를 위해 기존 세션을 종료/추가/삭제하거나 데이터를 초기화하지 않는다.
4. JDK17 전체 검사와 테스트 APK 빌드를 먼저 마치고 AVD 수동 검증은 순차 실행한다. 기존 SDK/이미지/AVD와 앱 데이터를 재사용한다. 서명과 데이터 호환성을 확인해 기존 debug 앱을 데이터 삭제나 새 권한 없이 업데이트한다.

macOS에서는 전역 셸 설정을 바꾸지 않고 실행별 JDK를 선택한다.

```bash
HANGIL_JDK17_HOME="$(/usr/libexec/java_home -v 17)"
"$HANGIL_JDK17_HOME/bin/java" -version
env JAVA_HOME="$HANGIL_JDK17_HOME" ./scripts/verify.sh
env JAVA_HOME="$HANGIL_JDK17_HOME" ./gradlew --no-daemon --console=plain :app:assembleDebugAndroidTest
```

## 명시적 실기 실행

[SessionHistoryFlowTest](../../app/src/androidTest/kotlin/io/github/hangillab/hangil/SessionHistoryFlowTest.kt)의 `endedHistoryKeepsStoredSnapshotsAndSelectionAcrossLifecycleAndDrivingLock`만 선택한다. `manualSessionHistory=true`가 없으면 건너뛰며 통과로 계산하지 않는다. 대상 제품 패키지는 `io.github.hangillab.hangil.debug`다.

실제 전용 장치는 비공개 실행 환경의 `HANGIL_TEST_DEVICE`로 지정한다. 아래 명령은 단일 메서드만 실행하며 기존 자료의 사전 보존/복구를 대신하지 않는다.

```bash
adb -s "$HANGIL_TEST_DEVICE" shell am instrument -w -r \
  -e manualSessionHistory true \
  -e class io.github.hangillab.hangil.SessionHistoryFlowTest#endedHistoryKeepsStoredSnapshotsAndSelectionAcrossLifecycleAndDrivingLock \
  io.github.hangillab.hangil.debug.test/androidx.test.runner.AndroidJUnitRunner
```

외부 실행자는 실제 브라우저 Virtual Console의 DDS 제어 브리지와 앱의 SDK 수신을 확인한다. HTTP 200, 제어 클릭이나 빈 UI 계층만으로 상태 적용을 추정하지 않는다. 실제 전면 앱과 중앙 앱 화면을 확인하고 기본 내비/카메라 오버레이를 앱 화면으로 오인하지 않는다.

기본 글자1.0/라이트와 확대 글자1.3/다크에서 같은 메서드를 각각 실행한다. 글꼴과 테마는 외부 실행자가 원래 값을 비공개로 기록한 뒤 검증 조건만 적용하고, 실패해도 원래 설정으로 복원한다. 테스트 자체는 설정/권한을 변경하지 않는다.

## 화면과 DDS 시나리오

- 실제 P와 UX 허용을 확인하고 홈의 `home_history`로 진입한다. 최신 종료 기록부터 실제 저장된 모든 종료 기록을 이전 방향으로 읽고, 다시 새 기록 방향으로 이동한다. 카탈로그 대신 원래 저장한 제목/버튼 시각/시연 표시와 위치를 비교한다.
- 각 기록의 텍스트 레이아웃에 시각적 overflow나 말줄임이 없고, 스크롤 후 원문과 이전/다음/닫기 버튼 전체가 실제 가용 viewport 안에 보이는지 확인한다. 모든 상세가 한 화면에 동시에 보인다는 의미는 아니다.
- 지난 기록을 선택한 채 Activity를 재생성하고 CREATED에서 RESUMED로 복귀한다. 매번 새 검증 뒤 같은 선택과 저장 원문을 확인한다.
- `HISTORY_AWAIT_D`가 나오면 외부 DDS를 D로 전환한다. 실제 SDK의 연결된 D 수신과 `history_locked`, 상세 태그/조작의 제거를 함께 확인한다.
- `HISTORY_AWAIT_P`가 나오면 DDS를 원래 P로 복원한다. 실제 P와 UX 허용을 모두 확인한 뒤 같은 지난 기록으로 돌아오는지 확인한다.
- `history_close`로 닫아 홈으로 돌아가면 상세가 없어야 한다. 다시 진입하면 최신 종료 기록부터 시작하고 끝 기록에서는 해당 이동 버튼이 비활성화돼야 한다.

`history_ready`, `history_course`, `history_goal`, `history_task`, `history_started`, `history_finished`, `history_demo`, `history_position`, `history_older`, `history_newer`를 사용해 상세와 잠금 경계를 대조한다. 캡처는 실제 전면 앱을 확인한 뒤 앱 영역을 잘라 비공개 cache에만 남긴다. 원문 제목, 실제 시각과 다른 사용자별 정보가 들어갈 수 있으므로 검증 캡처를 그대로 공개하지 않는다.

## 보존과 결과 해석

네이티브 검사는 실제 domain 저장소에서 시작 전/후 준비 선택, 모든 세션의 필드와 생성 순서, 각 세션의 차량 관찰과 메모를 대조한다. 기록을 만들거나 수정/삭제하지 않는다. 실패해도 가능한 보존 검사를 수행하지만, 이것이 원본 DB의 모든 물리 행/자료형/sequence나 권한/설정까지 검사했다는 뜻은 아니다.

외부 실행자가 별도로 전체 SQLite의 행/필드/저장 순서/스키마/sequence, 유효 권한과 runtime 플래그, 글꼴/야간/IME/프록시, 원래 P/UX를 대조하고 복원한다. SDK 기록이 생기지 않아야 하는 사전 조건과 실제 보존 결과를 확인한 뒤 이번에 소유한 AVD/포워딩/임시 콘솔만 정리한다. 실패 원인과 원본 증거를 보존하며 사용자 자료를 임의로 되돌리거나 삭제하지 않는다.

검사는 STOP 중에도 외부 DDS 단계를 관찰하려고 별도 SDK 구독을 유지한다. 이 결과로 제품의 SDK 구독 해제나 모든 플랫폼 callback 정리를 검증했다고 주장하지 않는다. 실제 빈 이력, 선택 제거와 오류 주입, 독립 P/UX 조합이나 플랫폼 단절/권한 거부 주입을 수행한 것으로 확대하지 않는다. 제어된 단위 경계와 실제 원본 이력 실기는 구분한다.

`HISTORY_COMPLETED`는 메서드 안의 시나리오가 끝났다는 표시다. 외부 보존/복구와 최신 PR 검증까지 완료됐다는 뜻은 아니다. 실제 성공/실패/미실행/미지원과 남은 조건은 작업 계획 및 PR에 기록하며 이 절차 문서에는 실행 결과를 소급 확정하지 않는다.
