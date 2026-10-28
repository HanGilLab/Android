# 마지막 위치 1회 조회

MAP-01의 위치 검증 단계다. 주차와 UX 허용을 함께 확인한 개발 화면에서 사용자가 버튼을 눌렀을 때만 조회한다.
연속 추적·백그라운드 수집·현재 위치 표시·경로 기록은 이 단계에서 구현하지 않는다.

## SDK와 실제 환경

- 공식 한국어 가이드·Maven metadata의 FusedLocation 1.1.3을 사용한다. 이전 영문 문서의 1.1.2 표기와 구분한다.
- 공식 AAR의 SHA-256과 공개 ABI를 확인했으며 위치 모델을 위해 runtime 전용 데이터 artifact를 직접 compile 의존으로 추가하지 않는다.
- 현재 검증한 Pleos AVD에는 FusedLocation 서비스 1.0.0과 dangerous 권한이 있다. 설치·isSupported 결과만으로 연결·입력 성공을 보장하지 않는다.
- 권한은 `pleos.car.permission.FUSED_LOCATION`이며 Android의 일반 위치 권한과 별개다.

## 사용 흐름과 데이터 경계

1. 주차·UX 허용 상태에서 지도 화면의 개발 확인 영역을 연다.
2. ‘마지막 위치 확인’은 현재 권한으로 1회 조회하며 권한을 자동 요청하지 않는다.
3. 권한이 없으면 명시적인 ‘위치 권한 요청’ 버튼으로 시스템 요청을 연다. 허용·거부는 사용자 선택이다.
4. 권한 결과 직전과 조회 결과 반영 직전에 주차·UX를 다시 확인한다. 화면 이탈·정지·주행 전환에서는 결과를 지우고 작업을 취소한다.

마지막 위치·빈 결과·미지원·권한 거부·시간 초과·오류를 구분한다. 좌표를 0으로 채우지 않는다.
SDK timestampMillis의 시간 기준과 confidence의 단위는 확인되지 않았다. 원값과 별도 수신 시각을 보존하며 현재 epoch와 차감하거나 m·%로 바꾸지 않는다.
SDK 결과는 고정 시연 지도에 반영하지 않고 현재 위치라고 표현하지 않는다. 좌표 원문은 UI 캡처·공개 로그에 내보내지 않으며 모델의 기본 문자열 출력도 가린다.

## 취소와 정리 경계

SDK 1.1.3의 내부 bind retry Job은 release에서 취소되지 않는 경로가 sources·실제 바이너리에 있다. 실제 장치에서 누수를 재현한 것으로 표시하지 않는다.
SDK 인스턴스마다 전용 ContextWrapper를 전달해 실제 bind·unbind·현재 proxy callback을 잠금으로 직렬화하고 close 이후 플랫폼 재바인딩을 막는다.
bind가 false이거나 예외여도 생성된 dispatcher를 정리하며 이미 끝난 요청의 callback 참조를 제거한다.
취소와 정리가 겹친 실패도 감추지 않는다. 정상 결과 대신 Error를 반환하거나 실제 취소 원인의 예외 인과관계에 정리 실패를 보존한다.
내부 coroutine 자체를 취소했다고 주장하지 않는다. 20초 응답 제한은 이미 시작된 동기 Binder 호출을 강제 중단하는 보장이 아니다.

## 실제 장치 검증

전용 AVD와 P+UX 허용 상태에서 아래 시나리오를 개별 실행한다. 권한을 자동 부여하는 테스트는 없다.
현재 DB v2의 준비·세션 데이터를 보존하려면 main의 마이그레이션을 포함한 APK를 사용한다. v1 전용 과거 APK로 되돌리거나 데이터를 지우지 않는다.
권한이 없는 상태에서 `missingPermissionIsExplicitAndRecreationClearsTheProbe`, 사용자가 권한을 허용한 뒤 `grantedPermissionIdentifiesTheActualSdkResponse`를 실행한다.
위치 1건 수신과 Empty 응답은 각각 기록하며, 건너뛴 검사를 통과로 취급하지 않는다.

```sh
adb -s "$PLEOS_SERIAL" shell am instrument -w -e class \
  io.github.hangillab.hangil.LocationProbeFlowTest#missingPermissionIsExplicitAndRecreationClearsTheProbe \
  io.github.hangillab.hangil.debug.test/androidx.test.runner.AndroidJUnitRunner
```

## 공식 근거

- [설치](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/project-setup)
- [권한](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/declaring-permissions)
- [마지막 위치](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/location-data-access)
- [수명주기](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/initialization-and-release)
