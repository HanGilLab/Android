# 한길졸업 심사용 소개 초안

공식 접수양식의 02 앱 프로젝트 소개에 옮길 본문이다. [main 기준 fbdcee7](https://github.com/HanGilLab/Android/commit/fbdcee7fb680448d06a02ccf17c152959df2e418)의 구현과 검증 범위를 반영했다.

앱명 / 프로젝트명: **한길졸업**

## 1. 앱 한 줄 소개

한길졸업은 오랜만에 운전하는 면허 소지자가 오늘의 과제를 준비하고 차량 기록을 주차 후 돌아보며 다음 연습을 고르는 Pleos 차량용 앱입니다.

## 2. 기획 배경

주요 대상은 면허는 있지만 운전 공백이 길고 출퇴근이나 생활 이동을 다시 시작하려는 성인입니다. 길안내와 별개로 오늘 무엇을 연습할지 정하고 주차 후 경험을 다음 연습에 연결하기 어렵다는 문제에 주목했습니다. 한 번에 한 과제를 준비하고 관찰과 메모를 남기는 흐름으로 이 부담을 줄이고자 합니다. 대상 고객과 불편은 아직 인터뷰로 검증하지 않은 가설입니다.

## 3. 앱 주요 기능 및 차량 특화 요소

목표, 고정 시연 코스와 과제 준비, 연습 시작과 마침, 차량 관찰과 사용자 메모의 저장/열람, 종료 연습에서 다음 과제를 준비하는 흐름을 연결했습니다. 전면 위치 표본과 저장된 중단 원인을 주차 후 확인할 수 있고, 삭제 확인에는 위치 표본과 수집 상태가 포함됩니다. 상세 조작은 P와 UX 허용을 모두 확인해야 하며, 위치 수집에는 위치 권한도 필요합니다. 별도로 켠 차량 상태 기록만 화면 밖에서 이어가며 위치나 경로를 배경 수집하지 않습니다. 시연 자료, 실제 관찰과 사용자 메모의 출처 및 미확인을 구분합니다.

## 4. 주요 사용 시나리오

주차한 뒤 목표와 시연 코스를 보고 과제 하나를 골라 준비를 저장합니다. 준비를 편집할 때는 유효한 저장 선택을 불러옵니다. 주차와 화면 사용 허용이 확인되면 판교를 거쳐 양재까지 코스의 경유지와 도착지를 Pleos 기본 내비에 요청할 수 있습니다.

연습 중 다음 준비를 바꿔도 내비에는 연습을 시작한 코스를 보냅니다. 직접 켠 위치 표본 기록은 한길 화면이 열려 있을 때만 이어지며, 화면 이탈이나 권한 상실, 연습 종료 때 멈추고 자동으로 재개하지 않습니다.

주차와 화면 사용 허용이 확인되면 마침 뒤 표시되는 복습 버튼이나 홈의 최근 연습에서 선택한 종료 기록의 관찰, 위치 표본과 메모를 복습합니다. 같은 앱 실행 중 화면을 다시 만들어도 선택한 위치 기록이 유지됩니다. 새 연습 전에는 방금 마친 기록에 첫 메모를 남기고, 선택한 기록의 목표와 코스로 다음 과제를 준비할 수 있습니다. 마침 복습에서 다음 과제 선택을 취소하면 같은 복습으로 돌아가고, 저장 뒤 연습은 별도로 시작합니다.

## 5. 개발 계획

Kotlin과 Compose 화면, 도메인, Pleos와 AAOS 어댑터, Room 저장소를 분리했습니다. 별도 검증 앱의 실제 Main 화면과 Room에서 제어 입력으로 준비, 시작, 마침, 복습과 다음 과제 저장을 확인했습니다. 이전 Pleos 에뮬레이터 실기에서 내비 안내 시작과 경유지 및 도착지 일치를 별도로 확인했으며, 지도 배경 표시와 실제 도로 경로 검증은 남아 있습니다. 한국 시간 11월 12일까지 검증한 앱과 출품 자료를 정리합니다.

## 6. 기대효과 및 발전 가능성

기대 가치는 오늘 할 일과 주차 후 되돌아볼 내용을 한 흐름에서 찾도록 돕는 것입니다. 다음 연습 선택의 용이성과 생활 경로 연습 수요는 앞으로 사용자 관찰로 확인합니다. 운전 교육기관의 수업 후 연습에 연결하는 B2B/B2B2C와 새 출퇴근 경로 코스는 확장 가설이며 제휴나 유료 운영 실적은 없습니다. 호환 차량의 실제 체험 기회와 연습 시작, 다음 준비 행동을 확인한 뒤 이용 규모와 재방문을 추정하겠습니다. 현재 MAU, 유지율, 사고 감소와 운전 실력 향상 수치는 없으며 과정 마무리는 사용자의 선언입니다.

## 7. 참고자료

주요 사용 흐름은 4번에, 구현과 검증 범위는 5번에 정리했습니다.

## 8. 숏폼 링크

참여 여부와 게시 링크는 사용자 확인 후 반영합니다.

## 작성 근거와 현재 범위

아래 표는 본문 검토를 위한 근거이며 공식 양식의 별도 입력 항목은 아니다. 구현 존재와 현재 실기 완료를 구분한다. 이번 소개 작성에서는 앱을 실행하거나 권한을 허용하거나 위치를 관찰하지 않았다.

| 본문 주장 | 확인한 현재 구현 | 해석의 한계 |
|---|---|---|
| 목표, 코스와 오늘의 과제 준비 | [CourseScreen](../../feature/course/src/main/kotlin/io/github/hangillab/hangil/feature/course/CourseScreen.kt), [RoomPracticeRepository](../../core/database/src/main/kotlin/io/github/hangillab/hangil/core/database/RoomPracticeRepository.kt) | 지도 지점은 준비를 돕는 고정 시연 자료다. 실제 도로 경로의 정확도는 별도 검증 대상이다 |
| 연습과 차량 관찰 | [SessionViewModel](../../feature/home/src/main/kotlin/io/github/hangillab/hangil/feature/home/SessionViewModel.kt), [VehicleState](../../core/domain/src/main/kotlin/io/github/hangillab/hangil/core/domain/VehicleState.kt), [RoomVehicleObservationRepository](../../core/database/src/main/kotlin/io/github/hangillab/hangil/core/database/RoomVehicleObservationRepository.kt) | 지원되지 않는 값, 오류와 연결 중단을 정상 관찰로 바꾸지 않는다. 차량 관찰만으로 운전 능력이나 위험을 판정하지 않는다 |
| P와 UX 허용의 교집합 | [PracticeInteraction](../../core/domain/src/main/kotlin/io/github/hangillab/hangil/core/domain/PracticeInteraction.kt), [VehicleState](../../core/domain/src/main/kotlin/io/github/hangillab/hangil/core/domain/VehicleState.kt) | 연결 끊김, 권한 거부와 미확인은 잠금을 유지한다. UX 허용만으로 주차를 추정하지 않는다 |
| 기본 내비 열기 | [PleosNavigationClient](../../core/vehicle/src/main/kotlin/io/github/hangillab/hangil/core/vehicle/PleosNavigationClient.kt) | 화면 전환 요청과 실제 전면 표시를 구분한다 |
| 지원 코스의 길안내 요청 | [PleosNavigationRouteRequest](../../core/vehicle/src/main/kotlin/io/github/hangillab/hangil/core/vehicle/PleosNavigationRouteRequest.kt) | 주차와 UX 허용 시 지원 코스의 경유지와 도착지를 보낸다. 진행 중인 연습은 시작할 때 저장한 코스를 사용한다. 기존 안내나 탐색을 확인하면 새 요청을 차단한다 |
| 명시적인 배경 차량 기록 | [BackgroundRecordingRoute](../../feature/home/src/main/kotlin/io/github/hangillab/hangil/feature/home/BackgroundRecordingRoute.kt), [VehicleRecordingService](../../app/src/main/kotlin/io/github/hangillab/hangil/VehicleRecordingService.kt) | 기본 전면 기록과 별도로 켠다. 위치와 경로를 수집하지 않으며 앱 종료와 중간 상태의 연속 수집은 보장하지 않는다 |
| 관찰 복습, 메모와 다음 과제 | [SessionHistoryScreen](../../feature/home/src/main/kotlin/io/github/hangillab/hangil/feature/home/SessionHistoryScreen.kt), [NextPracticeViewModel](../../feature/course/src/main/kotlin/io/github/hangillab/hangil/feature/course/NextPracticeViewModel.kt) | 다음 과제는 선택한 종료 연습의 같은 목표, 코스와 버전에 한한다. 실제 도로 구간 기반 추천이나 운전 실력 평가는 아니다 |
| 과정 마무리와 삭제 | [RoomCourseCompletionRepository](../../core/database/src/main/kotlin/io/github/hangillab/hangil/core/database/RoomCourseCompletionRepository.kt), [RoomSessionDeletionRepository](../../core/database/src/main/kotlin/io/github/hangillab/hangil/core/database/RoomSessionDeletionRepository.kt) | 과정 마무리는 선택한 연습의 사용자 선언이다. 삭제는 확인한 종료 연습과 연결 자료에 한한다 |
| 명시적 전면 위치 표본 기록과 열람 | [전면 위치 controller](../../core/domain/src/main/kotlin/io/github/hangillab/hangil/core/domain/ForegroundLocationRecordingController.kt), [위치 기록 화면](../../feature/home/src/main/kotlin/io/github/hangillab/hangil/feature/home/LocationHistoryScreen.kt) | 배경 위치 수집, 현재 위치나 실제 경로가 아니다. SDK 시각과 신뢰도 원본값의 미확인 의미를 유지한다 |
| 마친 연습 원본에 고정한 복습 | [마침 응답 처리](../../feature/home/src/main/kotlin/io/github/hangillab/hangil/feature/home/SessionViewModel.kt), [원본 메모리 토큰](../../app/src/main/kotlin/io/github/hangillab/hangil/ExactSessionNavigationViewModel.kt) | 홈에서는 최근 종료 연습을 다시 열 수 있다. 위치 복습은 같은 프로세스에서 화면을 다시 만들어도 선택한 원본을 유지하며, 프로세스가 재시작되면 다시 선택하도록 안내한다 |

현재 기준의 격리 앱에서 실제 Main과 Room의 준비, 시작, 마침, 복습과 다음 과제 저장을 Pleos API 34 기본 라이트와 1.3배 글자 다크로 확인했다. 차량 상태에는 제어 입력을 사용했고 내비와 위치 SDK를 호출하지 않았다. 과거 API 33에서는 제어 입력으로 첫 메모가 마침 복습의 원본에만 저장됨을 별도로 확인했다. [Main 완주 검증](https://github.com/HanGilLab/Android/pull/129), [첫 메모 검증](https://github.com/HanGilLab/Android/pull/107)

Pleos 에뮬레이터에서는 기본 라이트와 확대 다크에서 내비 전면 전환, 회전 안내 시작, 소프트웨어드림센터 경유지와 양재 42dot 도착지의 일치를 확인했다. 기본 내비의 지도 배경 타일에는 인증 오류가 있었고 실제 도로 경로와 주행 정확도는 미검증이다. [코스 내비 연동 검증](https://github.com/HanGilLab/Android/pull/108)

## 심사 항목과 확인할 가설

[공식 심사 기준](https://appdevchallenge.auto-hmg.io/guide/judging-awards)의 네 관점에 본문을 연결한다. 심사 점수와 예상 순위를 산출하지 않는다.

| 공식 관점 | 본문에서 설명한 내용 | 앞으로 확인할 사항 |
|---|---|---|
| 차량 특화 경험 | 차량 상태 관찰, 주차/UX 잠금, 지원 코스의 내비 요청과 명시적인 배경 기록 | 최종 앱에서 내비 전환과 수집 중단, 복귀와 기록 출처를 실제 확인 |
| 창의성 | 운전 공백 사용자의 오늘 과제와 다음 연습을 연결하는 문제 설정 | 대상 고객의 불편과 기존 방법 대비 가치를 실제 인터뷰와 이용 관찰로 검증 |
| SDK/API 활용 | Vehicle, AAOS UX 제한, NaviHelper와 FusedLocation의 구현 경계 | 전면 위치 표본과 종료 원본 복습의 구현/과거 실기를 구분하고 최종 앱에서 확정한 범위만 시연 |
| 사업성과 발전 가능성 | 호환 차량 사용자와 교육기관 연계의 가설, 새 생활 경로의 후속 코스 | 고객 획득 경로, 체험 기회와 다음 연습 행동을 확인한 뒤 규모와 유지율 추정 |

## 최종 양식 반영 전 확인

- 최종 소스와 설치용 APK, 시연 영상의 버전을 맞추고 실제로 검증된 기능만 본문에 유지한다.
- 참가자 정보는 운영 접수란에만 적고 심사용 본문과 공유 자료에는 실명, 연락정보, 비공개 주소, 원문 로그와 실제 위치를 넣지 않는다.
- 최종 링크와 첨부물 전체의 개인정보 점검은 별도 제출 검토에서 수행한다. 현재 공개 소스 링크가 있다는 사실만으로 전체 자료 점검을 완료하지 않는다.

## 공식 작성 기준

- [접수양식과 동의서](https://appdevchallenge.auto-hmg.io/downloads/contest-application-consent.docx): 02의 실제 표시 항목 순서와 운영 개인정보 분리 기준.
- [지원과 제출 안내](https://appdevchallenge.auto-hmg.io/guide/submission): 최종 제출 자료와 선택 첨부물의 확인 기준.
- [심사 기준](https://appdevchallenge.auto-hmg.io/guide/judging-awards): 차량 경험, 문제 설정, 기술 활용과 발전 가능성을 설명하는 기준.
