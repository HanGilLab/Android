# 전면 활성 세션의 차량 관찰 검증

## 범위

제품은 MainActivity가 STARTED이고 Room의 활성 세션이 하나일 때 VehicleGateway가 전달한 상태를 순차 기록한다.
주행 잠금으로 홈 상세 UI가 사라져도 기록 수명은 종료되지 않는다.
STOPPED 또는 활성 세션 종료/교체 시 이전 기록 작업을 취소하고 정리가 끝난 뒤 다음 실행을 시작한다.
위치, 경로, 내비 앱으로 이동한 동안의 백그라운드 수집은 제공하지 않는다.
StateFlow의 동일/중간 상태 생략과 앱 재진입 시 현재 스냅샷을 전체 SDK 이벤트나 새로운 신호 수신으로 해석하지 않는다.
앱 관찰 시각은 SDK 발생 시각이 아니며 꺼져 있던 구간을 역채우지 않는다.

## 시작 전

- 기존 전용 Pleos AVD와 debug 앱, 저장된 준비를 사용한다. Activity를 실행하기 전에 외부 실행자가 기존 활성 세션이 없는지 확인한다. 기존 활성 세션이 있으면 이 시나리오를 시작하지 않고 보존한다.
- 기존 전체 DB와 권한, runtime 플래그, 글꼴/테마, 차량 상태를 비공개로 보존한다.
- JDK17 전체 검사와 계측 APK 빌드를 끝낸 후 AVD 수동 검증을 순차 실행한다.
- Virtual Console의 실제 브라우저 DDS 브리지와 앱 SDK 수신을 확인한다. HTTP200만으로 연결 성공을 판단하지 않는다.
- 실제 전면 패키지와 화면을 확인한다. 빈 UI 계층이나 에뮬레이터 관리 화면을 앱 성공으로 계산하지 않는다.

## 명시적 단일 시나리오

```sh
adb shell am instrument -w -r \
  -e manualDDS true \
  -e class io.github.hangillab.hangil.ForegroundVehicleRecordingFlowTest#actualSdkSnapshotsStopAndResumeWithForegroundSession \
  io.github.hangillab.hangil.debug.test/androidx.test.runner.AndroidJUnitRunner
```

전용 debug 앱과 외부 DDS 제어/복구를 준비한 단일 실행에서만 선택한다.
기본 발견 실행에서 manualDDS 옵션이 없으면 건너뛰며 실제 성공으로 계산하지 않는다.
새 위치나 다른 권한을 부여하는 검사는 아니다.

1. 실제 P와 UX 허용 상태에서 UI로 새 연습을 시작하고 SDK P 관찰이 같은 활성 세션에 저장되는지 확인한다.
2. `RECORDING_AWAIT_D` 단계에서 콘솔의 기어를 D로 전환한다. SDK D 관찰 저장과 홈 상세 잠금을 모두 확인한다.
3. 검사가 Activity를 CREATED로 내려 제품 수집을 멈춘 뒤 `RECORDING_AWAIT_P_BACKGROUND`를 보낸다. 콘솔을 P로 바꾸고 실제 SDK 수신이 있어도 중지 중 저장 행이 늘지 않는지 확인한다.
4. Activity 복귀 후 새 P 관찰과 같은 활성 세션, 재생성 후 단일 세션 유지를 확인한다.
5. 주차와 UX 허용에서 UI로 연습을 마무리한다. `RECORDING_AWAIT_SIGNAL_AFTER_FINISH` 단계에서 기존과 다른 방향지시등 값으로 전환한다. SDK 변화가 도착해도 종료된 세션의 관찰은 늘지 않아야 한다.
6. `RECORDING_AWAIT_SIGNAL_RESET`에서 방향지시등을 검사 전 값으로 되돌린다. 실제 SDK의 신호 복원을 확인하고 카메라 오버레이가 사라져 앱이 전면에 돌아온 뒤 최종 화면을 확인한다. 신호 켜짐으로 생성된 플랫폼 카메라를 앱 화면으로 해석하지 않는다.
7. `RECORDING_COMPLETED`와 최종 계측 성공을 확인한다. 새 종료 세션 하나만 남기고 기존 준비/세션/관찰의 모든 필드를 대조한다.

검사는 background P 수신 확인을 위해 별도의 실제 SDK 구독을 유지한다.
따라서 제품 DB 쓰기 중단을 증명하며 모든 SDK 구독이나 플랫폼 서비스 해제를 증명하는 검사가 아니다.
정지와 종료의 행 불변은 제한된 안정 구간의 정확성 근거이고 성능 개선 수치가 아니다.

## 실패와 복구

실패한 실행과 성공한 재실행을 별도로 보존한다. 생성한 활성 세션이 남았으면 P와 UX 허용을 실제로 확인한 뒤 그 세션만 정상 UI로 종료한다.
저장 권한 조건을 true로 대체하거나 DB 삭제/덮어쓰기로 종료를 가장하지 않는다.
원래 차량 상태, 권한과 설정을 복원하고 기존 전체 행이 유지됐는지 재확인한다.
본 실행이 시작한 AVD/포워딩만 종료하며 기존 장치와 사용자 자료는 보존한다.
원문 DB, SDK 값과 시각, 식별자, 경로, 화면 원본은 ignored 비공개 기록에만 보관한다.
공개 캡처는 실제 검증이 끝난 앱 영역만 사용한다.

## 완료 판정

전체 자동 검사, 관련 실제 시나리오와 최신 PR CI/Codex 리뷰를 각각 확인한다.
부분 차량 수집 성공을 APP-04 전체, 위치 기록, 내비 전환 후 수집 유지나 복습 완료로 표시하지 않는다.
기존 PR의 원래 최종 리뷰 추적은 이 구현 PR의 검사와 별도로 유지한다.
