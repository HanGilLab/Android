# 한길 앱 전체 사용 흐름

이 도식은 현재 코드의 화면 연결과 사용자 행동을 설명한다. 새 앱 실행이나 이번 실기 성공을 증명하는 화면은 아니다. 기본 내비, 실제 SDK 차량 관찰, 고정 시연 코스와 사용자 메모의 출처를 구분한다.

## 전체 동선

```mermaid
flowchart TD
    launchApp["한길 실행"] --> safetyCheck{"연결, P와 UX 허용 확인"}
    safetyCheck -->|"확인됨"| home["홈"]
    safetyCheck -->|"주행 또는 미확인"| locked["상세 조작 잠금"]
    locked -->|"현재 상태 재확인"| safetyCheck
    home --> preparation["목표, 코스와 과제 준비"]
    preparation -->|"홈에서 직접 시작"| activeSession["연습 시작과 차량 기록"]
    activeSession --> recent["주차 후 연습 마무리"]
    recent -->|"복습 버튼 선택"| recentReview["마친 연습 원본 복습"]
    recentReview --> nextPractice["다음 과제 준비"]
    recent -->|"홈 상단에서 같은 코스의 다음 과제"| nextPractice
    nextPractice -->|"홈에서 새로 시작"| activeSession
    home --> courseMap["시연 코스 지도"]
    home --> history["지난 연습과 관리"]
    courseMap --> home
    history --> home
```

별도의 회원가입, 로그인이나 첫 실행 온보딩 화면은 현재 연결되어 있지 않다. 앱은 홈에서 시작한다. 준비와 지도, 지난 연습, 다음 연습은 별도 목적지이며 연습 시작/종료와 최근 기록, 기본 내비 요청은 홈 안의 행동이다.

같은 준비로 연습을 마친 홈은 다음 과제 선택을 주행동으로 제공한다. 같은 과제 반복과 목표/코스 변경은 보조행동이며, 다음 과제를 위해 목표/코스를 처음부터 다시 고르지 않는다. 모든 선택을 한 화면으로 합치지 않고 현재 맥락을 재사용하는 범위만 단축한다.

## 준비와 지도

```mermaid
flowchart TD
    prepareOpen["홈에서 연습 준비 또는 준비 바꾸기"] --> selectGoal["목표 선택, 편집은 유효한 저장 선택 표시"]
    selectGoal --> selectCourse["목표에 맞는 시연 코스 선택"]
    selectCourse --> briefing["브리핑과 구간 설명 확인"]
    briefing --> selectTask["오늘의 과제 한 가지 선택"]
    selectTask --> savePreparation["준비 저장"]
    savePreparation -->|"저장 확인"| preparedHome["홈에 준비한 코스와 과제 표시"]
    savePreparation -->|"실패 또는 조건 변경"| preparationError["실패 표시 후 사용자가 다시 확인"]
    preparedHome --> openMap["코스 지도 열기"]
    openMap --> mapLoading["지도와 시연 지점 불러오기"]
    mapLoading -->|"렌더 완료"| mapReady["고정 시연 코스 표시"]
    mapLoading -->|"불러오기 실패"| mapRetry["사용자가 다시 시도"]
    mapRetry --> mapLoading
    mapReady --> pointPick["지도 지점 탭 또는 지점 목록 선택"]
    pointPick --> pointDetail["선택한 시연 지점 확인"]
    pointDetail --> mapReady
    openMap -->|"준비 없음, 변경 또는 불일치"| prepareAgain["홈에서 준비 다시 확인"]
```

같은 코스/버전/브리핑에서 과제를 고른 뒤 뒤로 갔다가 현재 코스를 다시 눌러도 유효한 과제를 유지한다. 코스나 버전, 과제 유효성이 바뀌면 다시 선택한다.

준비 저장은 연습 시작과 별개다. 지도 지점은 고정 시연 자료이며 실제 주행 경로, 현재 위치나 길안내 경로를 의미하지 않는다. 지도 조회 실패나 미확인 상태를 빈 정상 지도나 실제 경로로 바꾸지 않는다.

## 연습, 내비와 차량 기록

```mermaid
flowchart TD
    readyHome["유효한 준비가 있는 홈"] -->|"연습 시작"| sessionStarted["연습과 시작 시각 저장"]
    sessionStarted --> foregroundRecord["한길 화면에서 차량 기록"]
    sessionStarted -->|"내비 중에도 기록하기를 직접 선택"| backgroundRecord["배경 차량 기록 켜기"]
    foregroundRecord --> navigationRequest["주차 후 기본 내비 열기"]
    backgroundRecord --> navigationRequest
    navigationRequest -->|"전환되면"| nativeNavigation["Pleos 기본 내비 화면"]
    readyHome -->|"지원 코스, 주차와 UX 허용"| courseRoute["경유지와 도착지의 길안내 요청"]
    courseRoute -->|"전환되면"| nativeNavigation
    courseRoute -->|"기존 안내 중 또는 요청 실패"| navigationFeedback
    navigationRequest -->|"미지원, 권한 없음, 응답 없음 또는 오류"| navigationFeedback["요청 상태를 확인하고 새로 선택"]
    nativeNavigation -->|"연습 중 배경 기록을 켠 경우"| continueRecord["내비에서도 차량 기록"]
    nativeNavigation -->|"연습 중 배경 기록을 켜지 않은 경우"| pauseRecord["이탈 중 전면 기록 중단"]
    continueRecord --> returnHome["한길 복귀"]
    pauseRecord --> returnHome
    returnHome -->|"주차와 UX 허용 확인"| parkedSession["현재 연습 확인"]
    parkedSession -->|"내비 중 기록 끄기"| backgroundOff["배경 실행 종료 처리"]
    parkedSession -->|"연습 마무리"| finishedSession["종료 시각 저장과 마무리"]
    backgroundOff --> parkedSession
    finishedSession --> recentSession["최근 마무리한 연습 표시"]
    backgroundRecord -->|"앱 종료 뒤 종료 불명"| interrupted["중단과 종료 시각 미확인"]
    interrupted -->|"직접 정리 또는 다시 켜기"| parkedSession
```

기본 내비 열기는 화면 전환을 요청한다. 별도의 길안내 버튼은 주차와 UX 허용이 확인된 상태에서 판교를 거쳐 양재까지 코스의 경유지와 도착지를 Pleos에 보낸다. 기존 안내 중이면 새 요청을 막고, 화면 전환과 실제 안내 시작을 구분한다.

사용자가 켠 배경 기록의 수집 범위는 차량 상태다. 위치와 경로는 수집 대상에서 제외하며, 중간 상태와 앱이 종료된 동안의 수집은 보장하지 않는다.

주행 중에는 연습 마무리, 메모와 상세 복습을 조작할 수 없다. 상세 화면의 잠금과 이미 시작한 차량 기록 수집은 서로 다른 동작이다.

## 최근 연습, 지난 연습과 다음 준비

```mermaid
flowchart TD
    recentEntry["홈의 최근 종료 연습"] --> recentVehicle["차량 기록 자세히"]
    recentVehicle --> records["저장 순서대로 관찰 열람"]
    recentEntry --> recentMemo["내 메모 열기"]
    recentMemo --> memoDraft["메모 입력 또는 수정"]
    memoDraft --> memoSave["저장 결과 확인"]
    memoDraft -->|"취소"| recentMemo
    recentMemo --> memoDelete["메모만 지우기 확인"]
```

```mermaid
flowchart TD
    nextEntry["홈의 최근 종료, 마침 복습 또는 지난 연습의 선택 기록"] --> noActive{"진행 중 연습이 없는가"}
    noActive -->|"없음"| nextSource["같은 목표, 코스와 버전 확인"]
    noActive -->|"있음"| keepActive["진행 중 연습 유지"]
    nextSource --> nextTask["다음 과제를 직접 선택"]
    nextTask --> nextSave["다음 연습 준비 저장"]
    nextSave --> returnSource["진입한 홈, 복습 또는 이력으로 복귀"]
    returnSource -->|"홈에서 시작 버튼 직접 선택"| readyAgain["새 연습 시작"]
    nextSource -->|"변경 또는 사용할 수 없음"| normalPrepare["일반 연습 준비로 이동"]
```

```mermaid
flowchart LR
    historyEntry["지난 연습 보기"] --> historySelect["이전 또는 최근 기록 선택"]
    historySelect --> storedVehicle["저장된 차량 관찰"]
    historySelect --> storedMemo["해당 연습의 메모"]
    historySelect --> storedRuns["배경 실행 정보"]
    historySelect --> sameCourse["과제별 마무리 기록"]
    historySelect --> manualFinish["사용자 마무리 선언"]
    historySelect --> removeSession["연결 자료 영구 삭제"]
```

지난 연습 화면의 기존 메모 수정은 진행 중인 연습이 없어야 하며 현재 주차/UX 조건도 허용되어야 한다. 홈의 최근 메모는 같은 최신 종료 원본과 현재 주차/UX 조건을 확인하는 별도 편집 흐름이다. 빈 메모와 글자 수 제한, 원본 변경, 저장 충돌과 결과 미확인은 별도로 표시한다. 최근 메모만 지우기는 연습과 차량 기록을 삭제하지 않는다.

다음 준비는 홈의 최근 종료 기록 또는 복습에서 선택한 종료 원본의 목표, 코스와 버전을 확인한 뒤 과제를 직접 고르는 흐름이다. 마침 복습에서 취소하면 같은 복습으로 돌아간다. 실제 도로 구간 기반 추천은 후속 범위다. 배경 실행 시각은 실제 연속 수집 기간을 증명하지 않는다.

## 과정 마무리와 삭제

```mermaid
flowchart TD
    selectedHistory["종료 연습 선택"] --> finishDeclaration["과정 마무리 열기"]
    finishDeclaration -->|"선언 없음"| declareConfirm["마무리 선언 확인"]
    finishDeclaration -->|"이미 선언 있음"| showDeclaration["기존 선언과 시각 열람"]
    declareConfirm -->|"직접 확인"| saveDeclaration["사용자 선언 저장"]
    declareConfirm -->|"취소 또는 조건 변경"| noDeclaration["선언 저장하지 않음"]
    selectedHistory --> deleteRequest["이 기록 삭제"]
    deleteRequest --> linkedData["선택한 연습의 연결 자료 확인"]
    linkedData --> deleteConfirm{"영구 삭제를 확인했는가"}
    deleteConfirm -->|"취소"| preserveSession["원래 기록 유지"]
    deleteConfirm -->|"확인"| recheckSource["원본과 조작 조건 재확인"]
    recheckSource -->|"일치"| deleteOwned["연습과 연결 자료 함께 삭제"]
    recheckSource -->|"변경, 진행 중 또는 미확인"| refuseDelete["삭제하지 않고 이유 표시"]
```

과정 마무리는 선택한 연습에 대한 사용자의 선언이다. 자동 운전 능력 평가나 전역 과정의 이수 인증이 아니다. 영구 삭제는 확인한 연습과 연결 자료에 한정되며 삭제 성공 전에는 사라진 것으로 표시하지 않는다.

## 모든 상세 흐름에 적용되는 잠금과 실패

```mermaid
flowchart TD
    detailVisible["허용된 상세 화면이나 입력"] --> liveCheck{"현재 상태와 화면 소유권 유효"}
    liveCheck -->|"유효"| explicitAction["사용자가 고른 행동 처리"]
    liveCheck -->|"D, 끊김, 미확인 또는 화면 이탈"| lockNow["상세 화면과 옛 행동 차단"]
    lockNow -->|"P와 UX 허용으로 복귀"| newIntent["화면과 원본 확인 후 새 선택"]
    newIntent --> liveCheck
    explicitAction --> sourceCheck{"원본과 저장 결과 확인"}
    sourceCheck -->|"확인됨"| showResult["확인한 결과만 표시"]
    sourceCheck -->|"변경, 실패 또는 미확인"| showFailure["오류, 충돌과 미확인 표시"]
    showFailure -->|"사용자의 명시 재시도"| newIntent
```

P로 돌아와도 이전 삭제 확인, 내비 요청이나 거부된 클릭을 자동 실행하지 않는다. 누락, 미지원과 오류를 0이나 정상 값으로 바꾸지 않는다.

## debug 전용 진단

일반 사용 흐름과 구분되는 debug 기능이다. 홈의 차량 진단에서 현재 연결과 SDK 상태를 확인하고, 지도의 위치 진단에서 마지막 알려진 위치를 명시적으로 조회한다. 권한이 없으면 별도 권한 요청을 사용자가 선택할 수 있다.

```mermaid
flowchart TD
    debugMap["debug 지도 위치 진단"] --> readButton["마지막 위치 조회 선택"]
    readButton --> readOnce["마지막 위치 1회 조회"]
    readOnce --> lastResult{"조회 결과"}
    lastResult -->|"권한 거부"| permissionChoice["사용자가 권한 요청 선택"]
    permissionChoice --> permissionResult{"OS 권한 결과"}
    permissionResult -->|"허용"| sameRequest{"같은 요청과 조작 조건 유효"}
    sameRequest -->|"유효"| readOnce
    sameRequest -->|"만료 또는 화면 이탈"| discardResult["늦은 결과 폐기"]
    permissionResult -->|"거부 또는 실패"| keepDenied["거부 또는 실패 상태 유지"]
    lastResult -->|"마지막 값 있음"| uncertainLast["과거 값의 불확실성 표시"]
    lastResult -->|"빈 값 / 미지원 / 시간 초과 / 오류"| distinctResult["해당 상태를 구분해 표시"]
```

debug 진단은 마지막 위치를 한 번 조회한다. 제품의 전면 위치 기록은 사용자가 진행 중인 연습에서 따로 켜며, 화면 이탈, 권한 상실 또는 연습 종료 시 멈춘다. 주차 후 해당 연습의 위치 표본과 저장된 중단 원인을 확인할 수 있다.

## 남은 연결과 검증

실제 도로 복습 지도와 실제 구간 기반 다음 연습은 후속 범위다. 지원 코스의 경유지, 도착지와 안내 시작은 Pleos에서 확인했으며, 지도 타일 표시와 실제 도로 경로의 일치는 별도 검증이 필요하다.

## 코드 연결

| 사용자 행동 | 현재 구현 |
|---|---|
| 홈과 별도 목적지 이동 | [MainActivity](../../app/src/main/kotlin/io/github/hangillab/hangil/MainActivity.kt), [HomeScreen](../../feature/home/src/main/kotlin/io/github/hangillab/hangil/feature/home/HomeScreen.kt) |
| 목표, 코스, 브리핑과 과제 준비 | [CourseScreen](../../feature/course/src/main/kotlin/io/github/hangillab/hangil/feature/course/CourseScreen.kt) |
| 연습 시작/마무리와 최근 연습 | [SessionPanel](../../feature/home/src/main/kotlin/io/github/hangillab/hangil/feature/home/SessionPanel.kt), [SessionViewModel](../../feature/home/src/main/kotlin/io/github/hangillab/hangil/feature/home/SessionViewModel.kt) |
| 지도와 고정 시연 지점 선택 | [MapScreen](../../feature/map/src/main/kotlin/io/github/hangillab/hangil/feature/map/MapScreen.kt) |
| 기본 내비 열기와 지원 코스 길안내 | [NavigationScreenRoute](../../feature/home/src/main/kotlin/io/github/hangillab/hangil/feature/home/NavigationScreenRoute.kt) |
| 명시적인 배경 차량 기록 | [BackgroundRecordingRoute](../../feature/home/src/main/kotlin/io/github/hangillab/hangil/feature/home/BackgroundRecordingRoute.kt), [VehicleRecordingService](../../app/src/main/kotlin/io/github/hangillab/hangil/VehicleRecordingService.kt) |
| 지난 연습, 차량 관찰과 배경 실행 | [SessionHistoryScreen](../../feature/home/src/main/kotlin/io/github/hangillab/hangil/feature/home/SessionHistoryScreen.kt) |
| 최근/이전 연습의 메모 | [SessionMemoEditor](../../feature/home/src/main/kotlin/io/github/hangillab/hangil/feature/home/SessionMemoEditor.kt), [HistorySessionMemoEditor](../../feature/home/src/main/kotlin/io/github/hangillab/hangil/feature/home/HistorySessionMemoEditor.kt) |
| 과정 마무리 선언 | [HistoryCourseCompletionRoute](../../feature/home/src/main/kotlin/io/github/hangillab/hangil/feature/home/HistoryCourseCompletionRoute.kt) |
| 다음 연습 준비 | [NextPracticeScreen](../../feature/course/src/main/kotlin/io/github/hangillab/hangil/feature/course/NextPracticeScreen.kt) |
| 상세 조작 허용 조건 | [PracticeInteraction](../../core/domain/src/main/kotlin/io/github/hangillab/hangil/core/domain/PracticeInteraction.kt), [VehicleState](../../core/domain/src/main/kotlin/io/github/hangillab/hangil/core/domain/VehicleState.kt) |
| debug 마지막 위치 진단 | [LocationProbeRoute](../../feature/map/src/main/kotlin/io/github/hangillab/hangil/feature/map/LocationProbeRoute.kt), [LocationProbeViewModel](../../feature/map/src/main/kotlin/io/github/hangillab/hangil/feature/map/LocationProbeViewModel.kt) |
