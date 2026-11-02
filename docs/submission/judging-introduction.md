# 한길졸업 심사용 소개 초안

공식 접수양식의 02 앱 프로젝트 소개에 옮길 본문이다. 2026-11-02의 [main 기준 bf416e0](https://github.com/HanGilLab/Android/commit/bf416e0e0172a9bb374ce0f35638ce000e933e4e)에 맞췄으며 PR #74와 #75의 미완료 검증을 포함해 표시했다. 참가 정보 확정, 동의와 서명, 최종 첨부물과 실제 발송은 별도로 남아 있다.

앱명 / 프로젝트명: **한길졸업**

## 1. 앱 한 줄 소개

한길졸업은 오랜만에 운전하는 면허 소지자가 오늘의 과제를 준비하고 차량 기록을 주차 후 돌아보며 다음 연습을 고르는 Pleos 차량용 앱입니다.

## 2. 기획 배경

주요 대상은 면허는 있지만 운전 공백이 길고 출퇴근이나 생활 이동을 다시 시작하려는 성인입니다. 길안내와 별개로 오늘 무엇을 연습할지 정하고 주차 후 경험을 다음 연습에 연결하기 어렵다는 문제에 주목했습니다. 한 번에 한 과제를 준비하고 관찰과 메모를 남기는 흐름으로 이 부담을 줄이고자 합니다. 대상 고객과 불편은 아직 인터뷰로 검증하지 않은 가설입니다.

## 3. 앱 주요 기능 및 차량 특화 요소

현재 목표, 고정 시연 코스와 과제 준비, 연습 시작과 종료, 지원되는 차량 상태의 저장과 주차 후 열람, 사용자 메모, 같은 코스의 다음 과제 준비를 연결했습니다. Vehicle SDK로 연결, 기어, 방향지시등과 브레이크 상태를 다루며 P와 UX 제한 해제가 모두 확인돼야 상세 조작을 허용합니다. 기본 내비 화면을 요청할 수 있고 사용자가 별도로 켜면 내비 사용 중 차량 상태를 배경 기록합니다. 위치나 경로의 배경 수집은 하지 않습니다. 누락과 중단을 정상 기록으로 채우지 않으며, 시연 지점과 실제 관찰, 사용자 메모의 출처를 구분합니다.

## 4. 주요 사용 시나리오

운전 공백이 있는 사용자가 주차한 뒤 목표와 시연 코스를 보고 오늘의 과제를 하나 골라 준비를 저장합니다. 홈에서 연습을 시작하고 필요하면 배경 차량 기록을 켠 뒤 기본 내비 화면을 요청합니다. 주행 중 상세 입력은 잠깁니다. 한길로 돌아와 P와 UX 허용을 확인한 뒤 연습을 마무리하고 차량 관찰과 메모를 확인합니다. 최근 종료 연습에서 같은 코스의 다음 과제를 골라 준비합니다. 지도는 시연 지점을 보여 주며 실제 주행 경로가 아닙니다. 내비 요청에 목적지나 코스를 전달하지 않습니다.

## 5. 개발 계획

Kotlin과 Compose 화면, 순수 도메인 계약, Pleos와 AAOS 어댑터, Room 저장소를 분리했습니다. Vehicle SDK, CarUxRestrictions와 NaviHelper 화면 요청을 연결했습니다. 현재 main의 FusedLocation은 debug용 마지막 위치 조회 진단이며 연속 경로 저장이 아닙니다. 전면 위치 표본 기록과 종료 원본에 고정한 복습 연결은 각각 PR #74와 #75에서 검증 중이며 미병합입니다. 11월 10일 한국 시간까지 이 경계의 실제 검증, 도로 구간 복습과 다음 과제 연결, 내비 전환, 확대 글자와 다크 화면, 최종 제출 자료를 우선 확인할 계획입니다. 검증되지 않은 연결을 완료 기능으로 포함하지 않습니다.

## 6. 기대효과 및 발전 가능성

기대 가치는 오늘 할 일과 주차 후 되돌아볼 내용을 한 흐름에서 찾도록 돕는 것입니다. 다음 연습 선택의 용이성과 생활 경로 연습 수요는 앞으로 사용자 관찰로 확인합니다. 운전 교육기관의 수업 후 연습에 연결하는 B2B/B2B2C와 새 출퇴근 경로 코스는 확장 가설이며 제휴나 유료 운영 실적은 없습니다. 호환 차량의 실제 체험 기회와 연습 시작, 다음 준비 행동을 확인한 뒤 이용 규모와 재방문을 추정하겠습니다. 현재 MAU, 유지율, 사고 감소와 운전 실력 향상 수치는 없으며 과정 마무리는 사용자의 선언입니다.

## 7. 참고자료

구현 소스와 실제 앱 화면: https://github.com/HanGilLab/Android ; 전체 사용 흐름과 미완료 연결: https://github.com/HanGilLab/Android/blob/main/docs/development/app-usage-flow.md . 최종 시연 영상과 제출용 APK 링크는 이 초안에 아직 첨부하지 않았습니다.

## 8. 숏폼 링크

참여 여부와 게시 링크는 사용자 확인 후 반영합니다.

## 작성 근거와 현재 범위

아래 표는 본문 검토를 위한 근거이며 공식 양식의 별도 입력 항목은 아니다. 구현 존재와 현재 실기 완료를 구분한다. 이번 소개 작성에서는 앱을 실행하거나 권한을 허용하거나 위치를 관찰하지 않았다.

| 본문 주장 | 확인한 현재 구현 | 해석의 한계 |
|---|---|---|
| 목표, 코스와 오늘의 과제 준비 | [CourseScreen](../../feature/course/src/main/kotlin/io/github/hangillab/hangil/feature/course/CourseScreen.kt), [RoomPracticeRepository](../../core/database/src/main/kotlin/io/github/hangillab/hangil/core/database/RoomPracticeRepository.kt) | 코스와 지도 지점은 고정 시연 자료다. 실제 도로 검토를 마친 교육 코스나 길안내 경로로 표시하지 않는다 |
| 연습과 차량 관찰 | [SessionViewModel](../../feature/home/src/main/kotlin/io/github/hangillab/hangil/feature/home/SessionViewModel.kt), [VehicleState](../../core/domain/src/main/kotlin/io/github/hangillab/hangil/core/domain/VehicleState.kt), [RoomVehicleObservationRepository](../../core/database/src/main/kotlin/io/github/hangillab/hangil/core/database/RoomVehicleObservationRepository.kt) | 지원되지 않는 값, 오류와 연결 중단을 정상 관찰로 바꾸지 않는다. 차량 관찰만으로 운전 능력이나 위험을 판정하지 않는다 |
| P와 UX 허용의 교집합 | [PracticeInteraction](../../core/domain/src/main/kotlin/io/github/hangillab/hangil/core/domain/PracticeInteraction.kt), [VehicleState](../../core/domain/src/main/kotlin/io/github/hangillab/hangil/core/domain/VehicleState.kt) | 연결 끊김, 권한 거부와 미확인은 잠금을 유지한다. UX 허용만으로 주차를 추정하지 않는다 |
| 기본 내비 화면 요청 | [PleosNavigationClient](../../core/vehicle/src/main/kotlin/io/github/hangillab/hangil/core/vehicle/PleosNavigationClient.kt) | 화면의 전면 전환을 요청한다. 목적지, 경유지나 코스는 전달하지 않으며 요청 반환은 실제 전환이나 길안내 성공이 아니다 |
| 명시적인 배경 차량 기록 | [BackgroundRecordingRoute](../../feature/home/src/main/kotlin/io/github/hangillab/hangil/feature/home/BackgroundRecordingRoute.kt), [VehicleRecordingService](../../app/src/main/kotlin/io/github/hangillab/hangil/VehicleRecordingService.kt) | 기본 전면 기록과 별도로 켠다. 위치와 경로를 수집하지 않으며 앱 종료와 중간 상태의 연속 수집은 보장하지 않는다 |
| 관찰 복습, 메모와 다음 과제 | [SessionHistoryScreen](../../feature/home/src/main/kotlin/io/github/hangillab/hangil/feature/home/SessionHistoryScreen.kt), [NextPracticeViewModel](../../feature/course/src/main/kotlin/io/github/hangillab/hangil/feature/course/NextPracticeViewModel.kt) | 다음 과제는 최근 종료 연습의 같은 목표, 코스와 버전에 한한다. 실제 도로 구간 기반 추천이나 운전 실력 평가는 아니다 |
| 과정 마무리와 삭제 | [RoomCourseCompletionRepository](../../core/database/src/main/kotlin/io/github/hangillab/hangil/core/database/RoomCourseCompletionRepository.kt), [RoomSessionDeletionRepository](../../core/database/src/main/kotlin/io/github/hangillab/hangil/core/database/RoomSessionDeletionRepository.kt) | 과정 마무리는 선택한 연습의 사용자 선언이다. 삭제는 확인한 종료 연습과 연결 자료에 한한다 |
| 현재 위치 진단과 후속 연결 | [LocationProbeViewModel](../../feature/map/src/main/kotlin/io/github/hangillab/hangil/feature/map/LocationProbeViewModel.kt), [PR #74](https://github.com/HanGilLab/Android/pull/74), [PR #75](https://github.com/HanGilLab/Android/pull/75) | main은 debug용 마지막 위치 조회 진단이다. PR의 전면 위치 표본 저장과 종료 원본 복습은 최신 수동 검증 전 미병합이며 실제 도로 복습 지도와 경로 일치는 후속 범위다 |

## 심사 항목과 확인할 가설

[공식 심사 기준](https://appdevchallenge.auto-hmg.io/guide/judging-awards)의 네 관점에 본문을 연결한다. 심사 점수와 예상 순위를 산출하지 않는다.

| 공식 관점 | 본문에서 설명한 내용 | 앞으로 확인할 사항 |
|---|---|---|
| 차량 특화 경험 | 차량 상태 관찰, 주차/UX 잠금, 내비 화면 요청과 명시적인 배경 기록 | 최종 앱에서 내비 전환과 수집 중단, 복귀와 기록 출처를 실제 확인 |
| 창의성 | 운전 공백 사용자의 오늘 과제와 다음 연습을 연결하는 문제 설정 | 대상 고객의 불편과 기존 방법 대비 가치를 실제 인터뷰와 이용 관찰로 검증 |
| SDK/API 활용 | Vehicle, AAOS UX 제한, NaviHelper와 FusedLocation의 구현 경계 | 미완료 위치와 복습 연결을 현재 검증으로 구분하고 확정 기능만 시연 |
| 사업성과 발전 가능성 | 호환 차량 사용자와 교육기관 연계의 가설, 새 생활 경로의 후속 코스 | 고객 획득 경로, 체험 기회와 다음 연습 행동을 확인한 뒤 규모와 유지율 추정 |

## 최종 양식 반영 전 확인

- 최종 소스와 설치용 APK, 시연 영상의 버전을 맞추고 실제로 검증된 기능만 본문에 유지한다.
- 참가자 정보는 운영 접수란에만 적고 심사용 본문과 공유 자료에는 실명, 연락정보, 비공개 주소, 원문 로그와 실제 위치를 넣지 않는다.
- 최종 링크와 첨부물 전체의 개인정보 점검은 별도 제출 검토에서 수행한다. 현재 공개 소스 링크가 있다는 사실만으로 전체 자료 점검을 완료하지 않는다.
- 선택 숏폼의 참여 여부와 링크, 참가 정보, 동의와 서명, 실제 발송과 접수는 사용자 확인으로 확정한다. 아직 제출 완료를 뜻하지 않는다.

## 공식 작성 기준

- [접수양식과 동의서](https://appdevchallenge.auto-hmg.io/downloads/contest-application-consent.docx): 02의 실제 표시 항목 순서와 운영 개인정보 분리 기준.
- [지원과 제출 안내](https://appdevchallenge.auto-hmg.io/guide/submission): 최종 제출 자료와 선택 첨부물의 확인 기준.
- [심사 기준](https://appdevchallenge.auto-hmg.io/guide/judging-awards): 차량 경험, 문제 설정, 기술 활용과 발전 가능성을 설명하는 기준.
