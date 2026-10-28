# Vehicle SDK 오류 원인 구분

- 상태: 완료
- 요구사항: 공모전 체크리스트 ENV-06 (비공개 작업 명세)

## 목표와 범위

Vehicle SDK의 `VehicleException.errorCode`로 전달되는 권한 거부, 기능 미지원, 빈 결과를 일반 오류와 구분한다.
기존 구현은 표준 예외 타입만 확인하므로 SDK의 권한 오류가 신호 콜백으로 오면 이전 P 상태와 상세 조작이 유지될 수 있다.
권한 거부는 현재 세션을 무효화하고 상세 조작을 잠그며, 미지원, 빈 결과는 정상값으로 바꾸지 않는다.
서비스 연결, 내부 오류, 잘못된 값 타입은 기존 일반 오류 처리를 유지한다.
SDK, Android 의존은 `core:vehicle` 안에 두고 domain의 기존 읽기 상태 계약을 사용한다.

Vehicle 2.3.2의 배포 AAR에서는 문서의 `VehicleException/errorCode`가 public 바이너리 `a/a()`로 난독화되어 있다.
이 버전의 정적 alias 호환 어댑터를 한 파일에 격리하고, runtime 전용으로 노출된 `vehicle-data:1.1.11`을 Version Catalog에서 직접 선언한다.
예외 메시지 파싱, reflection으로 오류 원인을 추정하지 않으며 SDK 변경 시 실제 ABI 계약 테스트를 다시 확인한다.

## 완료 조건

- [x] 실제 SDK `VehicleException`의 권한 코드가 PermissionDenied로 분류된다.
- [x] 속성, 영역 미지원은 Unsupported, 빈 결과는 Unknown이며 값 타입, 서비스, 기타 오류는 Error다.
- [x] 표준 SecurityException, UnsupportedOperationException 처리와 기존 잠금 동작을 보존한다.
- [x] SDK 권한 오류가 신호 콜백으로 전달된 경우 이전 P와 늦은 콜백으로 상세 조작이 다시 열리지 않는다.
- [x] 관련 회귀 테스트와 전체 검증, 최신 PR CI와 Codex 리뷰를 확인한다.

## 구현 단계

1. 공식 오류 명세와 사용 중인 SDK 예외 타입, 생성자를 확인한다.
2. 오류 매핑과 실제 SDK 예외를 사용하는 회귀 테스트를 추가한다.
3. 차량 상태 전파와 잠금 회귀 검증, 전체 검사 및 PR 리뷰를 수행한다.
4. 노션에 해결한 결함과 여전히 미실행인 수동 예외 시나리오를 분리해 기록한다.

## 검증 결과

| 명령 또는 시나리오 | 결과 | 근거 |
|---|---|---|
| 공식 명세, 실제 배포 ABI 확인 | 확인 | 문서의 VehicleException/errorCode와 배포 2.3.2의 public a/a() 차이 확인 |
| 문서 이름 import 최초 컴파일 | 실패 | 공개 sources와 배포 ABI가 달라 문서 이름을 직접 import할 수 없음 |
| 오류 분류, 잠금 회귀 테스트 | 통과 | 실제 배포 SDK 예외를 사용하는 오류 매핑, 차량 상태 검사 18개 |
| SDK 데이터 compile/runtime 의존성 | 통과 | 두 클래스패스의 vehicle-data 해결 버전이 1.1.11로 일치 |
| `./scripts/verify.sh` | 통과 | JDK 17, 품질, 모듈 경계, Lint, 테스트 48개, debug와 unsigned release |
| PR CI와 Codex 리뷰 | PR에서 추적 | [PR #8](https://github.com/HanGilLab/Android/pull/8)의 최신 검사, 리뷰와 미해결 지적 확인 |
| 실제 권한 거부, 미지원, 연결 단절 수동 재현 | 미실행 | 이번 수정의 자동 검사와 구분, ENV-06에 유지 |

## 남은 문제와 인계

실제 플랫폼의 오류 주입 기능과 권한 정책을 확인하지 않고 서비스 종료, 권한 변경을 수행하지 않는다.
자동 테스트 통과를 실제 SDK 수동 재현 성공으로 기록하지 않는다.
지도, 위치 기술 검증은 독립적인 후속 P0 항목으로 남긴다.

## 공식 근거

[Vehicle SDK 오류 전달, 코드](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle-error-code)
