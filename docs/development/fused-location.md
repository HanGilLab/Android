# 마지막 위치 1회 조회

MAP-01의 위치 검증 단계다. 주차와 UX 허용을 함께 확인한 개발 화면에서 사용자가 버튼을 눌렀을 때만 조회한다.
연속 추적, 백그라운드 수집, 현재 위치 표시, 경로 기록은 이 단계에서 구현하지 않는다.

## SDK와 실제 환경

- 공식 한국어 가이드, Maven metadata의 FusedLocation 1.1.3을 사용한다. 이전 영문 문서의 1.1.2 표기와 구분한다.
- 공식 AAR의 SHA-256과 공개 ABI를 확인했으며 위치 모델을 위해 runtime 전용 데이터 artifact를 직접 compile 의존으로 추가하지 않는다.
- 현재 검증한 Pleos AVD에는 FusedLocation 서비스 1.0.0과 dangerous 권한이 있다. 설치, isSupported 결과만으로 연결, 입력 성공을 보장하지 않는다.
- 권한은 `pleos.car.permission.FUSED_LOCATION`이며 Android의 일반 위치 권한과 별개다.

## 사용 흐름과 데이터 경계

1. 주차와 UX 허용 상태에서 지도 화면의 개발 확인 영역을 연다.
2. ‘마지막 위치 확인’은 현재 권한으로 1회 조회하며 권한을 자동 요청하지 않는다.
3. 권한이 없으면 명시적인 ‘위치 권한 요청’ 버튼으로 시스템 요청을 연다. 허용, 거부는 사용자 선택이다.
4. 권한 결과 직전과 조회 결과 반영 직전에 주차와 UX를 다시 확인한다. 화면 이탈, 정지, 주행 전환에서는 결과를 지우고 작업을 취소한다.

마지막 위치, 빈 결과, 미지원, 권한 거부, 시간 초과, 오류를 구분한다. 좌표를 0으로 채우지 않는다.
SDK timestampMillis의 시간 기준과 confidence의 단위는 확인되지 않았다. 원값과 별도 수신 시각을 보존하며 현재 epoch와 차감하거나 m, %로 바꾸지 않는다.
SDK 결과는 고정 시연 지도에 반영하지 않고 현재 위치라고 표현하지 않는다. 좌표 원문은 UI 캡처, 공개 로그에 내보내지 않으며 모델의 기본 문자열 출력도 가린다.

## 취소와 정리 경계

SDK 1.1.3의 내부 bind retry Job은 release에서 취소되지 않는 경로가 sources, 실제 바이너리에 있다. 실제 장치에서 누수를 재현한 것으로 표시하지 않는다.
SDK 인스턴스마다 전용 ContextWrapper를 전달해 실제 bind, unbind, 현재 proxy callback을 잠금으로 직렬화하고 close 이후 플랫폼 재바인딩을 막는다.
bind가 false이거나 예외여도 생성된 dispatcher를 정리하며 이미 끝난 요청의 callback 참조를 제거한다.
취소와 정리가 겹친 실패도 감추지 않는다. 정상 결과 대신 Error를 반환하거나 실제 취소 원인의 예외 인과관계에 정리 실패를 보존한다.
내부 coroutine 자체를 취소했다고 주장하지 않는다. 20초 응답 제한은 이미 시작된 동기 Binder 호출을 강제 중단하는 보장이 아니다.

## 실제 장치 검증

전용 AVD와 P+UX 허용 상태에서 아래 시나리오를 개별 실행한다.
일반 계측 발견 실행에서는 임시 허용 옵션을 전달하지 않는다. 명시 옵션이 없는 허용 계측의 건너뜀은 실제 허용 경로 통과가 아니다.
현재 DB v2의 준비, 세션 데이터를 보존하려면 main의 마이그레이션을 포함한 APK를 사용한다. v1 전용 과거 APK로 되돌리거나 데이터를 지우지 않는다.
권한이 없는 상태에서 `missingPermissionIsExplicitAndRecreationClearsTheProbe`, 사용자가 권한을 허용한 뒤 `grantedPermissionIdentifiesTheActualSdkResponse`를 실행한다.
이어 `grantedReadThenLeaveAndRetryUsesFreshUiState`로 읽기 직후 이탈, 재진입, 재시도를,
`grantedResponseThenRecreationReturnsIdleWithoutAutomaticQuery`로 응답 후 재생성의 초기 상태를 확인한다.
위치 1건 수신과 Empty 응답은 관찰한 결과만 각각 기록하며, 건너뛴 검사를 통과로 취급하지 않는다.
이탈 직전 로딩 상태 관찰은 늦은 SDK 콜백을 강제로 재현했다는 뜻이 아니다.

### 시스템 요청 취소와 임시 허용

`deniedSystemRequestReturnsExplicitResultAndAllowsRetry`는 실제 권한 미허용 상태에서 명시적인 요청 버튼을 누른다.
시스템 권한창을 관찰하면 뒤로가기로 닫으며 허용 항목을 선택하지 않는다. 창의 관찰 여부를 별도로 기록하므로,
창 없이 즉시 거부된 결과를 실제 권한창 취소 검증으로 해석하지 않는다. 거부 안내, 재시도와 재진입 초기 상태를 확인한다.

`approvedSystemGrantReadsActualSdkResponseAndAllowsFreshRetry`는 시스템창에서 임시 허용 항목을 선택하는 별도 시나리오다.
전용 debug 앱과 승인된 `pleos.car.permission.FUSED_LOCATION` 검증 범위에만 사용한다.
이미 확보한 임시 허용 동의와 아래 외부 복구 전제를 모두 갖춘 단일 메서드 실행에만
`-e allowTemporaryFusedLocationGrant true` 계측 인자를 전달한다. 전체 클래스나 일괄 계측의 공통 옵션으로 설정하지 않는다.
옵션이 없거나 값이 정확히 `true`가 아니면 이 메서드는 건너뛰며, 실제 권한이 이미 허용된 상태에서는 시작 조건에 실패한다.

허용 계측은 실제 시스템창의 대상 앱과 허용 항목을 확인한 뒤 선택하고, 권한 readback과 LastKnown 또는 Empty를 확인한다.
이어 닫기/재진입의 초기 상태와 새 요청을 검사한다. 특정 SDK 응답을 가정하거나 빈 결과를 만들지 않으며 관찰한 응답만 기록한다.
외부에서 권한을 부여한 뒤 실행하는 기존 조회 계측은 시스템창과 ActivityResult의 허용 결과 경로를 검증한 것으로 계산하지 않는다.

허용 계측 자체에는 권한 회수가 들어 있지 않다. 외부 실행자는 시작 전에 대상 AVD와 앱, 미허용 상태와 기존 자료를 확인하고,
실패 경로를 포함한 `finally`에서 이번에 임시 허용한 권한을 회수해야 한다.
원래 다른 권한과 현재 사용자의 권한 플래그, 설정을 대조하고 실제 미허용 검사도 다시 실행한다.
복구가 중단되거나 대조하지 못했다면 완료로 기록하지 않고 비공개 복구 기록에서 이어간다.
권한 원문, 좌표와 개인별 실행 기록은 공개 문서나 계측 로그에 붙이지 않는다.

| 시나리오 | 시작 조건 | 확인 범위 |
|---|---|---|
| `missingPermissionIsExplicitAndRecreationClearsTheProbe` | 실제 미허용, 허용 상태이면 건너뜀 | 미허용 안내, 자동 권한 요청 없음, 재생성 초기 상태 |
| `deniedSystemRequestReturnsExplicitResultAndAllowsRetry` | 실제 미허용 | 명시적 요청과 취소/거부, 권한 미허용, 재시도와 재진입. 실제 시스템창 관찰은 별도 기록 |
| `grantedPermissionIdentifiesTheActualSdkResponse` | 승인 범위에서 이미 허용, 미허용이면 건너뜀 | 실제 마지막 위치 또는 빈 결과 |
| `grantedReadThenLeaveAndRetryUsesFreshUiState` | 승인 범위에서 이미 허용, 미허용이면 건너뜀 | 읽기 직후 이탈, 재진입 초기 상태와 새 요청 |
| `grantedResponseThenRecreationReturnsIdleWithoutAutomaticQuery` | 승인 범위에서 이미 허용, 미허용이면 건너뜀 | 응답 후 재생성, 이전 결과 제거와 초기 상태 |
| `approvedSystemGrantReadsActualSdkResponseAndAllowsFreshRetry` | 실제 미허용, 정확한 명시 옵션과 승인된 외부 복구 | 실제 시스템 허용 결과, SDK 응답, 재진입 초기 상태와 재시도. 권한 회수와 회수 후 검사는 외부 실행자의 책임 |

이번 검증만을 위해 외부에서 임시 권한을 부여했다면 시스템 권한 대화상자, 결과 콜백의 실기 검증과 구분한다.
검증 후 임시 권한을 회수하고 실제 권한 상태를 다시 읽은 뒤 미허용 시나리오를 다시 실행한다.
DB 스키마, 기존 준비/세션의 전체 행, 원래 설정, 다른 권한 상태도 전후 대조한다.
전면 패키지 확인만으로 중앙 앱이 보인다고 판단하지 않고 실제 전체 캡처를 함께 확인한다.

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
