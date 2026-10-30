<div align="center">
  <img src="design/brand/hangil-guide.svg" alt="한길졸업 길잡이" width="96" />
  <h1>한길졸업</h1>
  <p><strong>내가 매일 가야 하는 길 하나부터, 내 힘으로.</strong></p>
  <p>생활 경로 연습을 준비하고 기록하는 Pleos 차량용 앱</p>
</div>

<br />

## 📄 목차

- [✍ 프로젝트 개요](#overview)
- [🚘 핵심 기능](#features)
- [⚙️ 기술 스택](#technology)
- [🏛️ 시스템 아키텍처](#architecture)
- [🔎 문제 해결](#problem-solving)
- [👤 개발자 소개](#developer)

<br />

<a id="overview"></a>

## ✍ 프로젝트 개요

운전이 익숙하지 않은 성인이 매일 가야 하는 길을 스스로 연습할 수 있도록 돕습니다.
주차 중에 목표와 코스를 준비하고, 연습의 시작과 마무리를 기록합니다.

| 항목 | 내용 |
|---|---|
| 개발 기간 | 2026.10.26 ~ 현재 |
| 구성 | 1인 개발 |
| 담당 | 기획, 디자인, Android 개발 |
| 출품 | Pleos 차량용 앱 공모전 출품 준비 중 |

<br />

<a id="features"></a>

## 🚘 핵심 기능

**목표 선택 → 코스와 과제 준비 → 연습 시작 → 마무리 기록 → 주차 후 메모 → 다음 과제 준비**

실제 Pleos 에뮬레이터에서 동작을 확인한 앱 화면입니다. 코스와 과제는 고정 시연 내용입니다. 버튼을 누른 시각과 실제 SDK 차량 상태 관찰은 별도로 기록하며, 차량 상태는 활성 연습 중 앱 화면이 켜져 있을 때만 기록합니다. 위치와 실제 경로는 수집하지 않습니다. 사진을 누르면 원본 크기로 볼 수 있습니다.

| 홈과 연습 기록 | 목표 선택 |
|:---:|:---:|
| [<img src="docs/images/screenshots/hangil-home.png" alt="현재 행동과 최근 마무리 기록을 보여 주는 실제 한길 홈 화면" width="400" />](docs/images/screenshots/hangil-home.png) | [<img src="docs/images/screenshots/hangil-goal.png" alt="생활 속 연습 목표를 고르는 실제 한길 화면" width="400" />](docs/images/screenshots/hangil-goal.png) |

| 시연 코스 선택 | 오늘의 과제 |
|:---:|:---:|
| [<img src="docs/images/screenshots/hangil-course.png" alt="고정 시연 코스 두 가지 중 선택하는 실제 한길 화면" width="400" />](docs/images/screenshots/hangil-course.png) | [<img src="docs/images/screenshots/hangil-task.png" alt="오늘의 과제를 고르고 준비 내용을 저장하는 실제 한길 화면" width="400" />](docs/images/screenshots/hangil-task.png) |

| 생활권 코스의 시연 지점 | 교차로 코스의 시연 지점 |
|:---:|:---:|
| [<img src="docs/images/screenshots/hangil-map-neighborhood.png" alt="생활권 코스에 연결된 공개 장소 세 곳과 번호를 보여 주는 실제 한길 지도" width="400" />](docs/images/screenshots/hangil-map-neighborhood.png) | [<img src="docs/images/screenshots/hangil-map-intersections.png" alt="교차로 코스로 바뀐 공개 장소 세 곳과 지도 범위를 보여 주는 실제 한길 화면" width="400" />](docs/images/screenshots/hangil-map-intersections.png) |

지도 번호는 공개 장소의 고정 대표점입니다. 실제 이동 경로, 방문 순서나 차량 목적지를 뜻하지 않습니다.

| SDK 차량 상태 기록 | 주행 중 상세 조작 잠금 |
|:---:|:---:|
| [<img src="docs/images/screenshots/hangil-recording.png" alt="시연 코스와 구분해 SDK 차량 상태 관찰을 안내하는 실제 활성 연습 화면" width="400" />](docs/images/screenshots/hangil-recording.png) | [<img src="docs/images/screenshots/hangil-driving-locked.png" alt="실제 DDS의 D 상태에서 상세 화면을 잠그고 주차 후 조작을 안내하는 한길 화면" width="400" />](docs/images/screenshots/hangil-driving-locked.png) |

| 주차 후 저장된 차량 기록 확인 |
|:---:|
| [<img src="docs/images/screenshots/hangil-vehicle-review.png" alt="최근 종료 세션의 실제 SDK 관찰을 저장 순서대로 확인하는 한길 상세 영역" width="600" />](docs/images/screenshots/hangil-vehicle-review.png) |

| 지난 연습의 당시 준비 | 확대 글자와 다크 |
|:---:|:---:|
| [<img src="docs/images/screenshots/hangil-history.png" alt="종료 이력의 당시 코스와 목표, 과제를 저장 순서대로 확인하는 실제 한길 화면 영역" width="400" />](docs/images/screenshots/hangil-history.png) | [<img src="docs/images/screenshots/hangil-history-dark.png" alt="확대 글자와 다크 테마에서 지난 연습의 당시 준비를 확인하는 실제 한길 화면 영역" width="400" />](docs/images/screenshots/hangil-history-dark.png) |

| 같은 코스에 남긴 마무리 기록, 기본/확대 다크 |
|:---:|
| [<img src="docs/images/screenshots/hangil-history-summary.png" alt="고정 시연 코스의 저장된 마무리 기록 수와 과제별 펼침을 보여 주는 실제 한길 요약 영역" width="800" />](docs/images/screenshots/hangil-history-summary.png) |
| [<img src="docs/images/screenshots/hangil-history-summary-dark.png" alt="확대 글자와 다크에서 저장된 마무리 기록 수를 확인하는 실제 한길 요약 영역" width="800" />](docs/images/screenshots/hangil-history-summary-dark.png) |

| 같은 코스의 다음 과제 선택 | 확대 글자와 다크 |
|:---:|:---:|
| [<img src="docs/images/screenshots/hangil-next-practice.png" alt="최근 마무리한 시연 코스에서 다음 과제를 직접 고르는 실제 한길 화면" width="400" />](docs/images/screenshots/hangil-next-practice.png) | [<img src="docs/images/screenshots/hangil-next-practice-dark.png" alt="확대 글자와 다크에서 선택 전 저장을 막고 다음 과제를 고르는 실제 한길 화면" width="400" />](docs/images/screenshots/hangil-next-practice-dark.png) |

| 메모 입력, 확대 글자와 다크 | 재실행 후 저장 메모 복원 |
|:---:|:---:|
| [<img src="docs/images/screenshots/hangil-memo-input.png" alt="직접 입력한 검증용 다섯 줄 초안을 저장 전에 보여 주는 실제 한길 메모 영역" width="400" />](docs/images/screenshots/hangil-memo-input.png) | [<img src="docs/images/screenshots/hangil-memo-restored.png" alt="프로세스 재실행 후 검증용 저장 메모와 수정/지우기 버튼을 보여 주는 실제 한길 영역" width="400" />](docs/images/screenshots/hangil-memo-restored.png) |

메모 화면의 문장은 동작 확인을 위해 직접 입력한 예시입니다. SDK 차량 기록과 별도로 저장하며, 주차와 UX 허용을 확인한 경우에만 열고 편집합니다.

| 기능 | 할 수 있는 일 |
|---|---|
| **목표와 코스 선택** | 생활 속 연습 목표를 고르고 두 가지 시연용 코스의 설명을 살펴봅니다. |
| **출발 전 브리핑과 오늘의 과제** | 선택한 코스의 확인 사항을 읽고 이번 연습에서 집중할 과제 하나를 정합니다. |
| **준비 내용 저장과 복원** | 목표, 코스와 과제를 기기에 저장하고 앱을 다시 열어 이어서 확인합니다. |
| **연습 시작과 마무리** | 시작과 종료 시각, 당시 코스와 과제를 저장하고 홈에서 최근 연습과 다음 행동을 확인합니다. |
| **활성 연습의 차량 상태 관찰** | 앱 화면이 켜진 동안 SDK가 전달한 기어, 방향지시등과 브레이크 상태를 세션에 기록합니다. 미확인, 미지원, 거부와 오류를 보존하고, 화면 이탈과 종료 뒤에는 수집하지 않습니다. |
| **주차 후 차량 기록 확인** | 최근 종료 세션의 저장된 SDK 관찰을 한 번에 하나씩 확인합니다. 당시 기어, 방향지시등과 브레이크, 연결과 앱 관찰 시각을 보고 이전/다음으로 저장 순서를 따라갑니다. |
| **지난 연습 열람** | 종료 이력을 저장 순서대로 한 번에 하나씩 확인합니다. 당시 목표, 코스, 과제와 시작/마무리 버튼 시각을 보여 주며, 이탈과 주행 잠금 후에도 같은 기록으로 돌아옵니다. |
| **기기 내 종료 기록 삭제** | 지난 연습에서 종료 기록을 직접 골라 확인 후 연결된 차량 관찰과 메모를 함께 지웁니다. 진행 중 연습, 다른 기록과 준비는 보존합니다. |
| **코스별 마무리 기록 요약** | 저장 당시 목표, 코스, 버전과 표시 정보가 같은 마무리 기록을 모아 과제별 저장 기록 수를 확인합니다. |
| **같은 코스의 다음 과제 준비** | 최근 마무리한 연습의 목표와 시연 코스를 다시 확인하고 다음 과제 하나를 직접 골라 저장합니다. 선택이나 취소만으로 기존 준비를 바꾸지 않으며 새 연습은 별도로 시작합니다. |
| **주차 후 사용자 메모** | 최근 마무리한 연습에 짧은 메모를 직접 입력하고 저장, 수정, 명시적으로 지웁니다. 저장 내용은 앱을 다시 열어도 복원하며 미저장 초안과 구분합니다. |
| **코스별 시연 지도** | 준비한 코스에 맞춰 서울 시청 또는 광화문 주변의 공개 장소 세 곳을 번호로 표시합니다. 시연 출처와 지도 출처를 구분하고 실제 이동 경로나 현재 위치로 표현하지 않습니다. |
| **주행 중 상세 조작 잠금** | 주차와 UX 허용을 모두 확인해야 상세 조작을 허용합니다. 주행이나 상태 미확인에서는 준비 화면과 지도 조작을 잠급니다. |

<br />

<a id="technology"></a>

## ⚙️ 기술 스택

| 영역 | 기술 |
|---|---|
| 언어 | Kotlin 2.2.21 |
| 화면 | Jetpack Compose, Material 3 |
| 상태와 비동기 | ViewModel, Coroutines 1.10.2, StateFlow |
| 의존성 주입 | Hilt 2.57.2 |
| 로컬 저장 | Room 2.8.5, SQLite |
| 차량 상태 | Pleos Vehicle SDK 2.3.2 |
| 지도 표시 | MapLibre Android 13.5.0 |
| 빌드 구성 | Gradle Kotlin DSL, Version Catalog, convention plugin |

<br />

<a id="architecture"></a>

## 🏛️ 시스템 아키텍처

화면은 domain 계약을 사용하고, 차량 SDK와 저장 구현은 각각의 어댑터 모듈에 둡니다.
`app`은 진입점과 의존성 연결을 담당합니다.

```mermaid
flowchart TB
    App["app<br/>진입점과 DI 연결"]
    Features["feature:home / course / map<br/>화면과 ViewModel"]
    Domain["core:domain<br/>순수 Kotlin 모델과 계약"]
    Design["core:designsystem<br/>브랜드와 공통 UI"]
    Database["core:database<br/>Room 저장 구현"]
    Vehicle["core:vehicle<br/>Pleos 차량 구현"]

    App --> Features
    App --> Database
    App --> Vehicle
    Features --> Domain
    Features --> Design
    Database --> Domain
    Vehicle --> Domain

    classDef mint fill:#A7DAC4,stroke:#126B59,color:#073D31
    classDef cream fill:#FFF6E6,stroke:#126B59,color:#073D31
    class App,Domain mint
    class Features,Design,Database,Vehicle cream
```

<br />

<a id="problem-solving"></a>

## 🔎 문제 해결

상태 경합과 데이터 보존 문제를 해결한 실제 구현 사례 4개입니다.

### 1. 화면 갱신보다 먼저 도착한 주행 상태로 상세 조작 차단

**문제**

주차 중 표시한 버튼이 화면에 남아 있는 순간 D 상태가 도착하면, UI 갱신 전에 눌린 상세 조작이 실행될 수 있습니다. 접힌 이력 상세를 펼치거나 화면에 다시 들어왔을 때 옛 버튼이 새 기록을 여는 경계도 함께 다뤘습니다. UX 제한 해제만으로 주차를 추정하지 않습니다.

**원인**

차량 기어와 UX 제한은 서로 다른 상태 스트림이라 화면이 마지막으로 조합한 P와 최신 D 값이 잠시 다를 수 있었습니다. 펼침 콜백에서 로컬 expanded만 바꾸는 흐름을 추적하니 자식의 재구성이 부모 Route의 주차 판정을 다시 실행한다는 보장도 없었습니다. 정상 종료한 자료 스트림이 UI에 새 실패 상태를 발행하지 않으면 열린 상세가 남는 문제도 확인했습니다.

**해결**

주차 확인과 UX 제한 해제를 모두 요구하는 판정을 domain에 두었습니다. 상세를 여는 순간과 내용을 구성하기 직전에 최신 차량 값, 표시한 원본과 조회 세대를 확인합니다. 화면의 STARTED 수명마다 새 소유권을 발급하고 STOP/폐기 뒤 옛 콜백은 다시 허용하지 않습니다. 거부한 펼침 의도는 버리고 접기는 항상 허용합니다. 자료 스트림의 정상 종료도 실패 상태로 발행하되 화면 이탈에 따른 취소는 오류로 바꾸지 않습니다.

**결과**

화면이 이전 주차 상태를 표시해도 최신 D, 미확인, 연결 끊김이나 UX 제한에서는 상세를 새로 펼치지 않습니다. 다른 기록 선택과 화면 재진입 뒤에도 옛 버튼이 새 상세를 열지 않으며, P와 UX 허용으로 돌아와도 거부했던 요청이 자동 실행되지 않습니다. 현재 유효한 기록은 사용자가 다시 선택해 읽을 수 있습니다.

**대체안/회고**

버튼 비활성화나 부모 화면의 재구성에만 의존하면 처리 사이의 틈이 남습니다. rememberUpdatedState로 콜백을 최신 것으로 교체하는 방법은 옛 동작이 새 원본의 허용 조건을 빌릴 수 있어 사용하지 않았습니다. 열 때의 원본/수명과 현재 허용 조건을 함께 확인하는 방식을 택했습니다. 이미 수신한 상태를 기준으로 제한하며 아직 전달되지 않은 상태 전환을 예측하거나 차량을 제어하지는 않습니다.

**코드**

[주차와 UX 조건을 함께 판단하는 상태](core/domain/src/main/kotlin/io/github/hangillab/hangil/core/domain/DrivingRestrictions.kt#L3-L9)

```kotlin
data class DrivingRestrictions(
    val parkingState: ParkingState = ParkingState.UNKNOWN,
    val uxRestrictionState: UxRestrictionState = UxRestrictionState.UNKNOWN,
) {
    val allowsDetailedInteraction: Boolean
        get() = parkingState == ParkingState.PARKED && uxRestrictionState == UxRestrictionState.UNRESTRICTED
}
```

[최신 차량 상태와 제한 상태 재확인](core/domain/src/main/kotlin/io/github/hangillab/hangil/core/domain/PracticeInteraction.kt#L4-L9)

```kotlin
fun allowsPracticeInteraction(
    vehicle: VehicleState,
    restrictions: DrivingRestrictions,
): Boolean =
    restrictions.allowsDetailedInteraction &&
        vehicle.toDrivingRestrictions(restrictions.uxRestrictionState).allowsDetailedInteraction
```

[표시한 원본과 조회 세대를 최신 reader에서 재확인](feature/home/src/main/kotlin/io/github/hangillab/hangil/feature/home/SessionHistoryViewModel.kt#L104-L117)

```kotlin
fun canRevealSummary(
    expectedSessionId: String,
    expectedGeneration: Long,
): Boolean =
    synchronized(lock) {
        val owner = reader ?: return@synchronized false
        val query = owner.query ?: return@synchronized false
        if (!currentQuery(owner, query) || !canRead()) return@synchronized false
        val current = published.value as? SessionHistoryUiState.Ready ?: return@synchronized false
        val records = ended ?: return@synchronized false
        !selectionLost && selectedId == expectedSessionId &&
            current.session.sessionId == expectedSessionId && current.generation == expectedGeneration &&
            records.getOrNull(current.index) == current.session && current.summary.source == current.session
    }
```

[열 때의 수명과 허용 조건을 직접 캡처한 펼침 처리](core/designsystem/src/main/kotlin/io/github/hangillab/hangil/core/designsystem/ParkedDisclosure.kt#L76)

```kotlin
val clickOwner = startedOwner

fun ownsStartedContext(): Boolean = clickOwner.get() && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

fun mayRender(): Boolean = ownsStartedContext() && openingGuard?.invoke() == true && canExpand()
val toggle: () -> Unit = {
    if (expanded) {
        // Closing is always permitted, including after a live denial or STOP.
        collapse()
    } else if (ownsStartedContext() && canExpand()) {
        openingGuard = canExpand
        expanded = true
    } else {
        collapse()
    }
}
```

[내용 구성 직전 재검사와 거부한 의도 폐기](core/designsystem/src/main/kotlin/io/github/hangillab/hangil/core/designsystem/ParkedDisclosure.kt#L119)

```kotlin
val renderExpanded = visibleExpanded && mayRender()
if (renderExpanded) Column(verticalArrangement = Arrangement.spacedBy(HanGilSpacing.lg), content = content)
SideEffect {
    // A read-only guard failure must discard intent, not resurrect it after P returns.
    if (expanded && !renderExpanded) collapse()
}
```

### 2. 확인한 종료 기록만 연결 자료와 함께 원자적으로 삭제

**문제**

기존 이력에는 종료 기록을 지우는 경로가 없었습니다. 삭제 기능을 연결할 때는 사용자가 확인한 뒤에도 메모 수정, 다른 기록 선택이나 주행 상태 전환이 끼어들 수 있습니다. 세션 ID만으로 삭제하면 확인하지 않은 최신 메모까지 지우거나, 이전 화면의 확인이 재진입한 화면에서 실행될 수 있습니다. 세션과 연결된 차량 관찰, 메모를 서로 다른 쓰기로 나누어 지우면 일부 자료만 남을 수도 있습니다.

**원인**

확인창을 여는 조회와 실제 삭제는 서로 다른 시점입니다. 메모는 같은 문장을 다시 저장해도 변경 토큰이 바뀌며, 메모가 없던 상태도 새 입력으로 달라집니다. 목록 갱신, 화면 수명과 DB 작업의 완료 순서도 같지 않아 행이 사라졌다는 통지만으로 삭제 성공을 판단할 수 없었습니다.

**해결**

확인 조회에서 종료 세션 전체 스냅샷, 연결 관찰 수와 메모 버전(null 포함)을 고정했습니다. 명시 확인은 하나의 nonce에 연결하고 최신 P/UX, reader/조회 세대/선택 원본과 STARTED 소유권을 다시 확인합니다. Room 트랜잭션 안에서 같은 스냅샷을 재비교한 뒤 부모 세션을 지우고, 기존 FK CASCADE로 연결 자료를 함께 삭제합니다. 쓰기 전후에 허용 조건과 취소를 확인해 commit 전에 거부되면 연결 삭제도 롤백합니다.

자기 삭제로 행이 사라진 통지는 처리 중인 요청에 연결해 보존하고 저장소의 명시적 결과를 기다립니다. 이미 없음, 진행 중, 변경 충돌과 차단은 완료로 바꾸지 않습니다.

**결과**

확인 뒤 메모, 선택, 조회나 화면 수명이 바뀌면 옛 확인을 버리고 새 확인을 요구합니다. 쓰기 뒤 차단된 요청은 부모와 연결 자료를 되돌리고, 다른 종료 기록, 진행 중 연습과 준비는 보존합니다. 저장소의 `Deleted` 결과를 받은 경우만 완료로 표시하며 다른 기록을 자동 선택하지 않습니다.

같은 이력 진입을 Android14/API34 Pleos/JDK17 debug/font1.0/라이트에서 전후 각각 워밍업5회 뒤 웜30회 측정했습니다. 중앙값263.03→260.93ms, 차이−2.11ms(−0.80%)로 관찰됐지만 사분위 분포가 겹치고 순차 실행의 캐시/발열을 통제하지 못해 성능 향상은 확정하지 않았습니다. 입력/동기화/표시 확인 비용을 포함한 [조건과 원값](docs/development/ended-session-deletion.md)을 남겼습니다. 이 값은 기존 이력 진입의 관찰이며, 이전에 없던 삭제 동작의 속도 전후 비교나 개선율을 뜻하지 않습니다.

**대체안/회고**

UI 버튼 잠금이나 프로세스 내부 Mutex만으로는 다른 저장 경로의 메모 변경을 통제하지 못해 DB 트랜잭션 안의 재비교를 선택했습니다. 자식을 각각 삭제하는 방식 대신 기존 FK CASCADE를 활용해 부모 삭제와 연결 자료 정리를 묶었습니다. 행 소실 관찰과 성공 응답을 구분하고, 확인할 때의 원본/수명과 쓰기 요청의 nonce를 함께 확인했습니다. commit 이후 화면을 닫았다고 이미 끝난 삭제를 소급 복구한다고 보장하지는 않습니다.

**코드**

[확인 조회에서 고정하는 종료 원본과 연결 자료 버전](core/domain/src/main/kotlin/io/github/hangillab/hangil/core/domain/SessionDeletionTarget.kt#L4-L8)

```kotlin
data class SessionDeletionTarget(
    val session: PracticeSession,
    val observationCount: Long,
    val memoVersion: String?,
)
```

[같은 확인 nonce의 중복 실행과 바뀐 선택을 차단](feature/home/src/main/kotlin/io/github/hangillab/hangil/feature/home/SessionHistoryViewModel.kt#L165-L183)

```kotlin
fun confirmDeletion(nonce: Long): Boolean {
    val work =
        synchronized(lock) {
            val action = deletion?.takeIf { it.nonce == nonce } ?: return@synchronized null
            if (action.phase != DeletionPhase.CONFIRMING) return@synchronized null
            if (!currentDeletionContextLocked(action)) {
                invalidateDeletionLocked()
                return@synchronized null
            }
            val target = action.target ?: return@synchronized null
            val job = viewModelScope.launch(start = CoroutineStart.LAZY) { deleteSession(action, target) }
            action.phase = DeletionPhase.DELETING
            action.job = job
            deletionPublished.value = SessionDeletionUiState.Deleting(nonce, target)
            job
        } ?: return false
    work.start()
    return true
}
```

[쓰기 순간의 job, reader/조회/선택과 최신 차량 조건 재확인](feature/home/src/main/kotlin/io/github/hangillab/hangil/feature/home/SessionHistoryViewModel.kt#L321-L350)

```kotlin
private fun canCommitDeletion(
    action: DeletionAction,
    job: Job,
): Boolean =
    synchronized(lock) {
        action.phase == DeletionPhase.DELETING && action.job === job && job.isActive && currentDeletionContextLocked(action)
    }

private fun currentDeletionOwnerLocked(action: DeletionAction): Boolean =
    deletion === action && currentQuery(action.owner, action.query) &&
        selectedId == action.session.sessionId && canRead() && action.canKeepOpen()

private fun currentDeletionContextLocked(action: DeletionAction): Boolean =
    currentDeletionOwnerLocked(action) && !selectionLost &&
        currentSelectionLocked(action.session.sessionId, action.generation)?.session == action.session

private fun currentSelectionLocked(
    expectedSessionId: String,
    expectedGeneration: Long,
): SessionHistoryUiState.Ready? {
    val owner = reader ?: return null
    val query = owner.query ?: return null
    if (!currentQuery(owner, query) || !canRead() || selectionLost || selectedId != expectedSessionId) return null
    val current = published.value as? SessionHistoryUiState.Ready ?: return null
    val records = ended ?: return null
    return current.takeIf {
        it.session.sessionId == expectedSessionId && it.generation == expectedGeneration &&
            records.getOrNull(it.index) == it.session && it.summary.source == it.session
    }
}
```

[실제 DB에서 확인 스냅샷을 재비교한 뒤 연결 자료까지 삭제](core/database/src/main/kotlin/io/github/hangillab/hangil/core/database/RoomSessionDeletionRepository.kt#L40-L64)

```kotlin
override suspend fun delete(
    target: SessionDeletionTarget,
    canCommit: () -> Boolean,
): SessionDeletionResult =
    try {
        database.withTransaction {
            requirePermission(canCommit)
            val actual = findCheckedSession(target.session.sessionId)
            val result =
                when {
                    actual == null -> SessionDeletionResult.Missing
                    actual.isActive -> SessionDeletionResult.Active
                    actual != target.session -> SessionDeletionResult.Changed
                    readTarget(actual) != target -> SessionDeletionResult.Changed
                    else -> {
                        check(database.practiceSessionDao().deleteFinished(actual.sessionId) == 1)
                        SessionDeletionResult.Deleted
                    }
                }
            requirePermission(canCommit)
            result
        }
    } catch (_: WriteBlocked) {
        SessionDeletionResult.Blocked
    }
```

[쓰기 전후 허용 조건과 코루틴 취소 확인](core/database/src/main/kotlin/io/github/hangillab/hangil/core/database/RoomSessionDeletionRepository.kt#L88-L93)

```kotlin
private suspend fun requirePermission(canProceed: () -> Boolean) {
    currentCoroutineContext().ensureActive()
    val allowed = canProceed()
    currentCoroutineContext().ensureActive()
    if (!allowed) throw WriteBlocked()
}
```

[자기 삭제의 행 소실 통지를 결과 도착 전까지 보존](feature/home/src/main/kotlin/io/github/hangillab/hangil/feature/home/SessionHistoryViewModel.kt#L368-L384)

```kotlin
private fun preserveDeletionReceiptLocked(
    owner: Reader,
    query: Query,
    records: List<PracticeSession>,
): Boolean {
    val action = deletion ?: return false
    if (action.owner !== owner || action.query !== query || !currentDeletionOwnerLocked(action)) return false
    val source = records.singleOrNull { it.sessionId == action.session.sessionId }
    return when {
        action.phase == DeletionPhase.DELETING && source == null -> {
            action.sourceAbsent = true
            true
        }
        action.phase == DeletionPhase.DELETED && (source == null || source == action.session) -> true
        else -> false
    }
}
```

[저장소가 반환한 명시적 삭제 결과에 따른 완료/실패 분기](feature/home/src/main/kotlin/io/github/hangillab/hangil/feature/home/SessionHistoryViewModel.kt#L269-L283)

```kotlin
when (result) {
    SessionDeletionResult.Deleted -> {
        action.phase = DeletionPhase.DELETED
        action.job = null
        selectionLost = true
        // Preserve the deleted cursor until the user explicitly accepts another record.
        selectedId = action.session.sessionId
        published.value = SessionHistoryUiState.SelectionChanged(nextGenerationLocked())
        deletionPublished.value = SessionDeletionUiState.Deleted(action.nonce, action.session)
    }
    SessionDeletionResult.Missing -> failDeletionLocked(action, SessionDeletionUiState.FailureReason.MISSING)
    SessionDeletionResult.Active -> failDeletionLocked(action, SessionDeletionUiState.FailureReason.ACTIVE)
    SessionDeletionResult.Changed -> failDeletionLocked(action, SessionDeletionUiState.FailureReason.CHANGED)
    SessionDeletionResult.Blocked -> failDeletionLocked(action, SessionDeletionUiState.FailureReason.BLOCKED)
}
```

### 3. 지도 조회, 렌더 완료와 실제 입력의 소유권 연결

**문제**

코스 선택이나 재조회가 바뀐 뒤 지난 결과와 View의 완료가 새 지도를 정상으로 만들 수 있었습니다. 렌더 완료를 제한해도 이미 남아 있는 네이티브 MapView는 별도로 입력을 처리했습니다. 화면이 P로 남은 순간 최신 D 값이 도착하면, 지도의 직접 조작과 출처 버튼도 같은 안전 조건을 따라야 했습니다.

**원인**

조회 취소, Compose 재구성과 SDK의 입력/예약 동작은 함께 끝나지 않습니다. 기존 조회 세대와 View 토큰은 완료 콜백을 구분했지만 네이티브 dispatch에는 연결되지 않았습니다. 실제 Route의 MapView에 같은 수평 drag를 전달하고 SDK camera를 읽으니, P 표시를 유지한 합성 D 입력에서도 이동했습니다. 차량 값은 수신 시점을 제어한 명시적 합성이며 실제 DDS나 주행 상황을 재현했다는 뜻은 아닙니다.

**해결**

수신한 선택 변경은 해석 전에 기존 지도를 무효화하고, reader/조회 세대/준비/렌더 식별자를 같은 상태로 갱신하는 원리를 유지했습니다. 이를 네이티브 입력까지 연결해 현재 attempt와 View token, 살아 있는 준비 소유권, 최신 P와 UX 허용을 dispatch 직전에 다시 판단합니다.

STARTED와 허용 상태의 입력 수명을 구분하고, 허용된 DOWN의 시각/장치/입력 출처가 같은 MOVE/UP만 전달하도록 했습니다. 거부한 DOWN도 기억해 P 복귀가 지난 입력을 새 요청으로 바꾸지 않게 했습니다. 주행 잠금, STOP과 폐기에서는 CANCEL과 공개 취소 API로 진행 중 터치와 취소 가능한 SDK 동작, 출처 창을 정리합니다. 접근성/나침반/출처 동작도 캡처한 입력 소유권을 확인하도록 연결했습니다.

**결과**

기존 수신 경계에서는 선택 변경과 재조회 뒤 이전 조회나 View의 결과가 현재 지도를 정상으로 만들지 못하게 했습니다. 오류 뒤 늦은 완료도 실패 상태를 덮지 않습니다. 이 경계는 앱이 수신한 준비 결과에 적용하며 Room이 아직 방출하지 않은 변경까지 즉시 감지한다고 보장하지 않습니다.

같은 실제 MapView에 옛 P 표시가 남아 있어도 최신 D에서 drag와 wheel이 camera를 바꾸지 않으며, P의 직접 이동과 pinch는 유지했습니다. STOP 전의 입력과 출처 창 콜백은 재개한 화면의 권한을 빌려 실행되지 않습니다. 실제 DDS의 P-D-P에서도 지도 제거와 주차 후 복귀, 코스 교체/화면 재생성/닫기와 재진입을 기본/확대 다크에서 확인했습니다.

동일 지도 진입 계측은 각 워밍업5회 뒤30회, Android14/API34 Pleos/JDK17 debug/font1.0/라이트에서 중앙값2127.52→2000.33ms로 관찰됐습니다. 차이는−127.19ms(−5.98%)지만 사분위 분포가 겹치고 캐시/실행 순서 영향이 남아 있어 성능 향상은 미확정입니다. 입력 전달과 실제 렌더 확인/스크롤/검사 비용을 포함한 [측정 조건과 시각 원값](docs/development/map-live-input-boundary.md)을 함께 남겼습니다.


**대체안/회고**

OnTouchListener나 Compose 버튼 잠금만으로 자식 입력까지 막을 수 없어 native dispatch 앞에서 검사했습니다. flatMapLatest의 취소나 버튼 잠금만으로 네이티브 View와 SDK에 남은 작업을 중단할 수는 없었습니다. 조회/렌더와 입력의 소유권을 따로 식별하되 같은 준비 상태에 연결했습니다. SDK 내부 예약 객체를 수정하는 방법은 버전 의존이 커서 선택하지 않고 공개 dispatch, CANCEL과 취소 API를 사용했습니다.

직접 drag/pinch와 지연 없는 휠 경로는 지원 대상으로 두었습니다. 관성, 더블탭/퀵줌과 keyboard/trackball 카메라 제어는 이번 입력 정책에서 껐습니다. 새 허용 세대보다 먼저 시작한 gesture는 보수적으로 거부할 수 있습니다. BACK과 출처/저작권 열람의 동작은 별도로 보존하는 범위입니다. 이 작업은 입력 정확성 경계이며 처리 속도 향상으로 설명하지 않습니다. 표시한 지점은 고정 공개 장소의 시연용 중심점으로, GPS 위치나 실제 주행 경로를 대표하지 않습니다.

**코드**

[현재 View와 준비 소유권을 연결한 입력 허용 판정](feature/map/src/main/kotlin/io/github/hangillab/hangil/feature/map/MapViewModel.kt#L102-L110)

```kotlin
fun canInteractWithMap(
    attempt: Int,
    token: Long,
): Boolean {
    if (token <= 0) return false
    val current = render.value
    return current.id == attempt && current.token == token && current.load == MapLoadState.READY &&
        canRender(current) && render.value === current
}
```

[reader와 조회 세대의 소유권](feature/map/src/main/kotlin/io/github/hangillab/hangil/feature/map/MapViewModel.kt#L158-L162), [입력 DOWN과 후속 이벤트의 소유권](feature/map/src/main/kotlin/io/github/hangillab/hangil/feature/map/MapInputEpoch.kt#L50-L79)도 같은 경계에서 확인합니다.

[SDK로 전달하기 직전의 실제 touch 진입점](feature/map/src/main/kotlin/io/github/hangillab/hangil/feature/map/ParkedMapView.kt#L173-L217)

```kotlin
private inline fun deliverTouch(
    event: MotionEvent,
    deliver: () -> Boolean,
): Boolean {
    if (released) return true
    recheckInput()
    if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
        cancelNativeTouch()
        input.endTouch()
        return true
    }
    val allowed = livePermission() && input.owns(input.epoch)
    val accepted =
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            cancelNativeTouch()
            input.beginTouch(
                event.downTime,
                event.eventTime,
                SystemClock.uptimeMillis(),
                event.deviceId,
                event.source,
            )
        } else {
            input.ownsTouch(
                event.downTime,
                event.eventTime,
                SystemClock.uptimeMillis(),
                event.deviceId,
                event.source,
            )
        }
    if (!allowed || !accepted) {
        if (!allowed) recheckInput()
        return true
    }
    rememberTouch(event)
    return try {
        deliver()
    } finally {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            input.endTouch()
            forgetTouch()
        }
    }
}
```

[주행 잠금과 수명 종료에서 공개 SDK 작업 정리](feature/map/src/main/kotlin/io/github/hangillab/hangil/feature/map/ParkedMapView.kt#L370-L394)

```kotlin
private fun cancelNativeInput() {
    if (released || revoking) return
    revoking = true
    try {
        cancelNativeTouch()
        input.endTouch()
        cancelPendingInputEvents()
        val now = SystemClock.uptimeMillis()
        val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        cancel.source = InputDevice.SOURCE_TRACKBALL
        try {
            // Bypass our unsupported-input entrypoint to cancel any SDK timeout safely.
            super.onTrackballEvent(cancel)
        } finally {
            cancel.recycle()
        }
        currentAttribution?.onStop()
        boundMap?.let {
            it.cancelTransitions()
            it.cancelAllVelocityAnimations()
        }
    } finally {
        revoking = false
    }
}
```

### 4. SQLite의 묵시적 변환이 손상된 차량 관찰 값을 정상 값으로 만드는 문제 차단

**문제**

관찰 기록의 브레이크 값에 REAL이나 TEXT가 저장되면 Room의 Long 읽기가 0이나 1로 축약할 수 있습니다. 이 값을 Boolean으로 바꾸면 손상 데이터가 정상적인 미입력/입력 기록처럼 보이게 됩니다.

**원인**

SQLite의 선언 자료형과 실제 저장 자료형은 같다고 보장되지 않습니다. Kotlin 모델에서 0/1과 enum만 검사하면 Room이 이미 변환한 뒤라 원래 REAL/TEXT였다는 증거를 잃습니다. 누락, 미지원, 권한 거부와 실제 false를 같은 기본값으로 처리해도 기록의 의미가 바뀝니다.

**해결**

조회 시 `typeof()`를 값과 함께 읽고 브레이크와 관찰 시각의 실제 저장 자료형을 먼저 검사합니다. 읽기 상태와 nullable 값을 함께 보존하며 출처, enum과 상태/값 조합이 잘못되면 정상 값으로 복원하지 않습니다. 세션과 관찰의 외래 키, 삽입 순서와 이전 스키마 마이그레이션도 저장소 경계에서 검증합니다.

**결과**

묵시적 변환으로 정상 값처럼 보일 수 있는 손상 자료를 복원 오류로 구분합니다. 이전 스키마의 기록은 보존하면서 새 관찰 저장 경계를 추가했습니다.

**대체안/회고**

Long을 읽은 뒤 0/1만 검사하는 방법은 이미 일어난 묵시적 변환을 감지할 수 없어 물리 자료형 검사를 선택했습니다. 잘못된 행을 건너뛰거나 미확인을 0으로 바꾸는 대안은 기록 누락을 숨깁니다. 손상은 명시적인 오류로 전달하며 정상 기록과 구분합니다. 전면 앱의 SDK 수집 연결은 별도 기능으로 구현했으며, 이 사례는 저장 무결성 경계에 해당합니다. 내비 전환 중 지속 수집과 실제 주행 경로 복습은 후속입니다.

**코드**

[값과 원래 SQLite 자료형을 함께 조회](core/database/src/main/kotlin/io/github/hangillab/hangil/core/database/VehicleObservationDao.kt#L8-L22)

```kotlin
@Dao
internal interface VehicleObservationDao {
    @Query(
        "SELECT vehicle_observation.*, typeof(brakeValue) AS brakeStorageType, " +
            "typeof(observedAtEpochMillis) AS observedTimeStorageType " +
            "FROM vehicle_observation WHERE sessionId = :sessionId ORDER BY id ASC",
    )
    fun observe(sessionId: String): Flow<List<VehicleObservationRow>>

    @Query("SELECT COUNT(*) FROM vehicle_observation WHERE sessionId = :sessionId")
    suspend fun count(sessionId: String): Long

    @Insert
    suspend fun insert(entity: VehicleObservationEntity): Long
}
```

[실제 자료형 확인 뒤 관찰값 복원](core/database/src/main/kotlin/io/github/hangillab/hangil/core/database/VehicleObservationRow.kt#L7-L17)

```kotlin
internal data class VehicleObservationRow(
    @Embedded val entity: VehicleObservationEntity,
    val brakeStorageType: String,
    val observedTimeStorageType: String,
) {
    fun observation(): SessionVehicleObservation {
        check(observedTimeStorageType == "integer") { "Invalid observation time storage type" }
        check(brakeStorageType == if (entity.brakeValue == null) "null" else "integer") { "Invalid brake value storage type" }
        return entity.observation()
    }
}
```

<br />

<a id="developer"></a>

## 👤 개발자 소개

| 개발자 | 담당 |
|---|---|
| [오인성](https://github.com/ois0886) | 기획, 디자인, Kotlin과 Compose 기반 Android 개발 |
