# 한길 앱 전체 사용 흐름

이 도식은 2026-11-02의 [main 기준 7ccfea6](https://github.com/HanGilLab/Android/commit/7ccfea6684e5b9b3dcad8364c96186af6bc251c8)의 화면 연결과 사용자 행동을 설명한다. 새 앱 실행이나 이번 실기 성공을 증명하는 화면은 아니다. 기본 내비, 실제 SDK 차량 관찰, 고정 시연 코스와 사용자 메모의 출처를 구분한다.

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
    recent --> recentReview["기록 복습과 내 메모"]
    recentReview --> nextPractice["다음 과제 준비"]
    nextPractice -->|"홈에서 새로 시작"| activeSession
    home --> courseMap["시연 코스 지도"]
    home --> history["지난 연습과 관리"]
    courseMap --> home
    history --> home
```

별도의 회원가입, 로그인이나 첫 실행 온보딩 화면은 현재 연결되어 있지 않다. 앱은 홈에서 시작한다. 준비와 지도, 지난 연습, 다음 연습은 별도 목적지이며 연습 시작/종료와 최근 기록, 기본 내비 요청은 홈 안의 행동이다.

## 준비와 지도

```mermaid
flowchart TD
    prepareOpen["홈에서 연습 준비 또는 준비 바꾸기"] --> selectGoal["목표 선택"]
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

준비 저장은 연습 시작과 별개다. 지도 지점은 고정 시연 자료이며 실제 주행 경로, 현재 위치나 길안내 경로를 의미하지 않는다. 지도 조회 실패나 미확인 상태를 빈 정상 지도나 실제 경로로 바꾸지 않는다.

## 연습, 내비와 차량 기록

```mermaid
flowchart TD
    readyHome["유효한 준비가 있는 홈"] -->|"연습 시작"| sessionStarted["연습과 시작 시각 저장"]
    sessionStarted --> foregroundRecord["한길 화면에서 차량 기록"]
    sessionStarted -->|"내비 중에도 기록하기를 직접 선택"| backgroundRecord["배경 차량 기록 켜기"]
    foregroundRecord --> navigationRequest["주차 후 기본 내비 요청"]
    backgroundRecord --> navigationRequest
    navigationRequest -->|"전환되면"| nativeNavigation["Pleos 기본 내비 화면"]
    navigationRequest -->|"미지원, 권한 없음, 응답 없음 또는 오류"| navigationFeedback["요청 상태를 확인하고 새로 선택"]
    nativeNavigation -->|"배경 기록을 켠 경우"| continueRecord["내비에서도 차량 기록"]
    nativeNavigation -->|"배경 기록을 켜지 않은 경우"| pauseRecord["이탈 중 전면 기록 중단"]
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

기본 내비 요청은 화면 열기만 요청하며 목적지, 경유지와 시연 코스를 전송하지 않는다. 요청 반환을 실제 화면 전환이나 길안내 성공으로 표시하지 않는다. 배경 기록은 차량 상태에 한하며 위치와 경로를 수집하지 않는다. 중간 상태와 앱이 종료된 동안의 수집은 보장하지 않는다.

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
    nextEntry["최근 연습의 다음 준비"] --> noActive{"진행 중 연습이 없는가"}
    noActive -->|"없음"| nextSource["같은 목표, 코스와 버전 확인"]
    noActive -->|"있음"| keepActive["진행 중 연습 유지"]
    nextSource --> nextTask["다음 과제를 직접 선택"]
    nextTask --> nextSave["다음 연습 준비 저장"]
    nextSave --> readyAgain["홈에서 새 연습 시작"]
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

다음 연습은 최근 종료 연습을 바탕으로 같은 목표와 코스의 과제를 다시 고르는 준비 기능이다. 지난 임의 연습이나 실제 도로 구간에서 다음 과제로 바로 이어지는 기능으로 확대해 해석하지 않는다. 배경 실행 시각은 실제 연속 수집 기간을 증명하지 않는다.

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

이 진단은 연속 위치 수집이나 주행 경로 저장 기능이 아니다. 이번 도식 작성 중에는 앱 실행, 권한 요청과 위치 조회를 수행하지 않았다.

## 아직 main에 없는 연결

아래 점선은 검토 중이거나 후속 구현이 필요한 연결이다. 위의 현재 사용 흐름과 별도로 읽는다. PR의 구현 존재, CI/리뷰와 실제 수동 검증 완료는 각각 구분한다.

```mermaid
flowchart TD
    currentFinish["현재 연습 마무리"] -.-> finishReview["PR #75: 같은 종료 원본 복습"]
    currentSession["현재 진행 중인 연습"] -.-> locationRecord["PR #74: 전면 위치 표본 저장"]
    locationRecord -.-> storedLocation["PR #74: 주차 후 표본 열람"]
    storedLocation -.-> roadReview["후속: 실제 도로 복습 지도"]
    roadReview -.-> roadNext["후속: 실제 구간의 다음 과제"]
    currentMap["현재 시연 코스 지도"] -.-> routeMatch["후속: 내비 경로 일치"]
```

[PR #74](https://github.com/HanGilLab/Android/pull/74)와 [PR #75](https://github.com/HanGilLab/Android/pull/75)는 이 기준 시점에 미병합이다. 실제 도로 복습 지도, 목적지/경유지 전달, 안내 경로 일치와 실제 구간 기반 다음 연습은 완성 기능으로 표시하지 않는다.

## 코드 연결

| 사용자 행동 | 현재 구현 |
|---|---|
| 홈과 별도 목적지 이동 | [MainActivity](../../app/src/main/kotlin/io/github/hangillab/hangil/MainActivity.kt), [HomeScreen](../../feature/home/src/main/kotlin/io/github/hangillab/hangil/feature/home/HomeScreen.kt) |
| 목표, 코스, 브리핑과 과제 준비 | [CourseScreen](../../feature/course/src/main/kotlin/io/github/hangillab/hangil/feature/course/CourseScreen.kt) |
| 연습 시작/마무리와 최근 연습 | [SessionPanel](../../feature/home/src/main/kotlin/io/github/hangillab/hangil/feature/home/SessionPanel.kt), [SessionViewModel](../../feature/home/src/main/kotlin/io/github/hangillab/hangil/feature/home/SessionViewModel.kt) |
| 지도와 고정 시연 지점 선택 | [MapScreen](../../feature/map/src/main/kotlin/io/github/hangillab/hangil/feature/map/MapScreen.kt) |
| 기본 내비 화면 요청 | [NavigationScreenRoute](../../feature/home/src/main/kotlin/io/github/hangillab/hangil/feature/home/NavigationScreenRoute.kt) |
| 명시적인 배경 차량 기록 | [BackgroundRecordingRoute](../../feature/home/src/main/kotlin/io/github/hangillab/hangil/feature/home/BackgroundRecordingRoute.kt), [VehicleRecordingService](../../app/src/main/kotlin/io/github/hangillab/hangil/VehicleRecordingService.kt) |
| 지난 연습, 차량 관찰과 배경 실행 | [SessionHistoryScreen](../../feature/home/src/main/kotlin/io/github/hangillab/hangil/feature/home/SessionHistoryScreen.kt) |
| 최근/이전 연습의 메모 | [SessionMemoEditor](../../feature/home/src/main/kotlin/io/github/hangillab/hangil/feature/home/SessionMemoEditor.kt), [HistorySessionMemoEditor](../../feature/home/src/main/kotlin/io/github/hangillab/hangil/feature/home/HistorySessionMemoEditor.kt) |
| 과정 마무리 선언 | [HistoryCourseCompletionRoute](../../feature/home/src/main/kotlin/io/github/hangillab/hangil/feature/home/HistoryCourseCompletionRoute.kt) |
| 다음 연습 준비 | [NextPracticeScreen](../../feature/course/src/main/kotlin/io/github/hangillab/hangil/feature/course/NextPracticeScreen.kt) |
| 상세 조작 허용 조건 | [PracticeInteraction](../../core/domain/src/main/kotlin/io/github/hangillab/hangil/core/domain/PracticeInteraction.kt), [VehicleState](../../core/domain/src/main/kotlin/io/github/hangillab/hangil/core/domain/VehicleState.kt) |
| debug 마지막 위치 진단 | [LocationProbeRoute](../../feature/map/src/main/kotlin/io/github/hangillab/hangil/feature/map/LocationProbeRoute.kt), [LocationProbeViewModel](../../feature/map/src/main/kotlin/io/github/hangillab/hangil/feature/map/LocationProbeViewModel.kt) |
