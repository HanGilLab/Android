<div align="center">
  <img src="design/brand/hangil-guide.svg" alt="한길졸업 길잡이" width="96" />
  <h1>한길졸업</h1>
  <p><strong>내가 매일 가야 하는 길 하나부터, 내 힘으로.</strong></p>
  <p>생활 경로를 연습하는 Pleos 차량용 앱</p>
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

운전이 오랜만인 면허 소지자가 자주 가는 길을 연습하는 앱입니다. 주차 중 목표와 과제를 정하고, 연습을 마치면 차량 기록과 메모를 보며 다음 과제를 고릅니다.

<a id="features"></a>

## 🚘 핵심 기능

| 홈 | 오늘의 과제 |
|:---:|:---:|
| [<img src="docs/images/screenshots/hangil-home.png" alt="첫 연습 준비 버튼이 있는 홈" width="400" />](docs/images/screenshots/hangil-home.png) | [<img src="docs/images/screenshots/hangil-task.png" alt="시연 코스에서 과제를 고르는 화면" width="400" />](docs/images/screenshots/hangil-task.png) |

| 연습 중 | 연습 마무리 |
|:---:|:---:|
| [<img src="docs/images/screenshots/hangil-recording.png" alt="진행 중인 연습과 차량 상태 기록 안내" width="400" />](docs/images/screenshots/hangil-recording.png) | [<img src="docs/images/screenshots/hangil-practice-finished.png" alt="마친 연습을 복습하는 버튼" width="400" />](docs/images/screenshots/hangil-practice-finished.png) |

| 저장된 차량 기록 | 다음 과제 저장 |
|:---:|:---:|
| [<img src="docs/images/screenshots/hangil-vehicle-review.png" alt="시연 코스와 저장된 SDK 차량 관찰을 구분한 복습 화면" width="400" />](docs/images/screenshots/hangil-vehicle-review.png) | [<img src="docs/images/screenshots/hangil-next-practice-saved.png" alt="새 과제를 저장한 뒤의 홈" width="400" />](docs/images/screenshots/hangil-next-practice-saved.png) |

- **준비** 목표, 코스, 과제를 저장합니다. 판교를 거쳐 양재까지 코스는 경유지와 도착지를 Pleos 기본 내비에 보낼 수 있습니다.
- **연습** 시작과 종료 시각, SDK가 전달한 기어, 방향지시등, 브레이크를 기록합니다. 위치 표본과 내비 사용 중 차량 기록은 사용자가 각각 켭니다.
- **복습** 지난 연습의 차량 기록과 위치 표본을 읽고 메모를 남깁니다. 공개 도로선을 복습 구간으로 골라 다음 과제와 함께 저장할 수 있습니다.
- **주차 확인** 기어 P와 화면 조작 허용을 모두 확인해야 준비, 복습과 상세 조작을 열 수 있습니다.

화면의 코스는 시연용입니다. 복습에서는 시연 코스, 저장된 SDK 관찰, 사용자가 남긴 메모의 출처를 각각 표시합니다.

<a id="technology"></a>

## ⚙️ 기술 스택

Kotlin, Jetpack Compose, Coroutines, Hilt, Room, MapLibre, Pleos Vehicle SDK, FusedLocation, NaviHelper

<a id="architecture"></a>

## 🏛️ 시스템 아키텍처

| 모듈 | 역할 |
|---|---|
| `app` | 앱 진입점, 화면 전환, Hilt 연결 |
| `feature:home`, `feature:course`, `feature:map` | 화면과 ViewModel |
| `core:domain`, `core:designsystem` | Kotlin 모델과 계약, 공통 UI |
| `core:database`, `core:vehicle` | Room 저장소, Pleos SDK와 AAOS 연동 |

기능 모듈은 도메인 계약을 사용합니다. `app`에서 Room과 차량 어댑터를 연결합니다.

<a id="problem-solving"></a>

## 🔎 문제 해결

### 1. 방금 마친 연습을 복습에서 다시 열기

**문제** 방금 마친 연습을 복습하려고 이력을 열어도 다른 기록이 표시될 수 있었습니다.

**원인** 마침 응답에서 종료 기록을 버리고, 이력에 진입할 때 최신 행을 골랐습니다. 조회가 취소돼도 공유 상태를 덮을 수 있었습니다.

**해결** 종료 응답의 원본을 보관하고 현재 저장 기록과 일치할 때 복습 요청을 한 번 소비했습니다. 조회를 시작할 때 화면이 열려 있는지도 확인했습니다.

**결과** 최신 기록이 바뀌어도 방금 마친 연습을 열었습니다. 주행 중에는 잠기고 주차 후 같은 기록으로 돌아왔습니다. 전후 성능은 미측정입니다.

**대체안/회고** ID만 전달하면 같은 ID의 내용이 바뀐 경우를 잡을 수 없습니다. 원본 전체를 대조하는 방식은 앱이 수신한 변경을 기준으로 판단합니다.

**코드**

```kotlin
if (mutation.isLocked || operation.value == SessionOperationState.Saving ||
    receipts.value !== receipt || !finishAccessLocked(canKeepOpen) || receipts.value !== receipt ||
    currentRecordsLocked()?.singleOrNull { it.sessionId == receipt.source.sessionId } != receipt.source
) {
    return@synchronized null
}
receipts.value = null
acknowledgedFinish = null
receipt.source
```

```kotlin
owner.job.ensureActive()
if (entry is SessionHistoryEntry.FinishedReview && !hostAllowed(owner)) return@synchronized false
owner.job.ensureActive()
invalidateDeletionLocked()
reader = owner
ended = null
```

### 2. 확인한 종료 기록과 연결 자료를 함께 삭제하기

**문제** 삭제를 확인한 뒤 메모나 연결 자료가 바뀌면 사용자가 확인하지 않은 자료까지 지울 수 있었습니다.

**원인** 확인 화면을 열 때 읽은 값과 DB에서 지울 때의 값 사이에 변경이 들어올 수 있었습니다.

**해결** Room 트랜잭션에서 종료 기록, 연결 자료, 메모 버전을 다시 대조하고 부모 행을 삭제했습니다. 연결 자료는 외래 키로 함께 삭제합니다.

**결과** 확인한 내용이 바뀌면 다시 확인을 요청합니다. 쓰기 후 커밋 검사를 통과하지 못하면 연결 자료도 함께 되돌립니다. Pleos API 34, JDK 17 debug, 기본 라이트에서 예열 5회 후 이력 진입을 전후 각 30회 측정했습니다. 중앙값은 263.03ms에서 260.93ms였고, 분포가 겹쳐 성능 향상은 미확정입니다.

**대체안/회고** 버튼을 잠가도 다른 저장 경로의 메모 변경은 막을 수 없어 DB에서 재비교했습니다.

**코드**

```kotlin
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
```

### 3. 주행으로 바뀐 뒤 이전 지도 입력 차단하기

**문제** 지도에 주차 화면이 잠시 남아 있을 때 주행 상태에서도 드래그가 카메라를 움직였습니다.

**원인** Compose에 그린 잠금보다 네이티브 지도에 도착하는 입력이 빨랐습니다. 이전 지도에서 늦게 온 렌더 콜백도 남아 있었습니다.

**해결** 현재 지도 토큰, 렌더 상태와 최신 주차 조건을 입력 직전에 확인했습니다. 첫 터치와 같은 세대의 후속 입력만 전달했습니다.

**결과** 제어 입력으로 오래된 주차 화면의 드래그와 휠 차단을 확인했습니다. 실제 Pleos에서는 주행 후 주차로 돌아오면 지도가 다시 열렸습니다. 같은 환경에서 각각 예열 5회 후 지도 진입 30회를 측정했습니다. 중앙값은 2127.52ms에서 2000.33ms였고, 분포가 겹쳐 성능 향상은 미확정입니다.

**대체안/회고** 터치 리스너만으로는 자식 View 입력을 모두 확인하기 어려워 네이티브 전달 지점에 검사를 뒀습니다.

**코드**

```kotlin
return current.id == attempt && current.token == token && current.load == MapLoadState.READY &&
    canRender(current) && render.value === current
```

```kotlin
validTime(downTime, eventTime, now) && downTime >= permittedAt &&
    touch?.let {
        owns(it.epoch) && it.downTime == downTime && it.deviceId == deviceId && it.source == source
    } == true
```

### 4. 내비에서 돌아온 뒤 차량 기록 상태 복원하기

**문제** 내비에서 돌아와 배경 기록을 끄면 차량 상태가 다시 저장돼도 화면에 일시 중지가 남았습니다.

**원인** 배경 기록이 소유권을 먼저 반환했습니다. 전면 기록이 즉시 재개된 뒤 이전 정리 코드가 새 상태를 덮었습니다.

**해결** 배경 실행과 화면 상태를 정리하고 마지막에 소유권을 반환하도록 순서를 바꿨습니다.

**결과** 실제 Pleos에서 배경 기록을 끈 뒤 같은 연습의 전면 수집과 화면 표시가 함께 돌아왔습니다. 전후 성능은 미측정입니다.

**대체안/회고** 반환 직후 화면을 강제로 기록 중으로 표시하면 활성 연습이 없는 경우가 틀립니다. 실제 수집기가 다음 상태를 정하도록 맡겼습니다.

**코드**

```kotlin
currentBackground.value =
    if (cleanupFailed) {
        BackgroundRecordingState.Failed(null, request.source.sessionId, checkNotNull(failure))
    } else {
        BackgroundRecordingState.Disabled
    }
currentState.value = VehicleRecordingState.Paused
owner.value = null
```

<a id="developer"></a>

## 👤 개발자 소개

2026년 10월부터 1인 개발로 기획, 디자인과 Kotlin과 Compose Android 구현을 맡았습니다.
