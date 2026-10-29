# 내비 최초 연결 기술 확인

코스 좌표와 실제 길안내 구현에 앞서 NaviHelper 3.0.5의 최초 서비스 연결만 검증한다.
SDK는 `core:vehicle`의 계측 의존성에만 포함한다. 제품 `NavigationGateway`의 미연결 표시와 앱 APK의 내비 의존성, 권한은 바꾸지 않는다.

## 검증 범위

- 공식 AAR의 최소 서비스 조건과 실제 설치 서비스의 조건 충족을 먼저 확인한다.
- `initialize(onConnected, onFailed)`에서 최초 연결 callback을 기다리고 실패와 시간 초과를 성공으로 바꾸지 않는다.
- `release()` 뒤 실제 bind/unbind의 균형과 정리 실패 부재를 확인한 다음 새 SDK 객체로 다시 초기화한다.
- 계측 앱은 차량, 위치와 내비 권한을 선언하거나 허용하지 않는다. 목적지, 경유지, 경로 탐색, 내비 화면, 정보 조회 API를 호출하지 않는다.
- SDK의 내부 서비스 callback 등록은 초기화 동작에 포함된다. 사용자 이벤트 리스너는 추가하지 않으며 실제 경로나 차량 위치를 조회한 것으로 해석하지 않는다.

최초 연결 callback은 이후의 지속 연결, 권한 허용, 경로 요청 성공이나 길안내 가능을 뜻하지 않는다.
일반 Android의 미설치/낮은 버전, 초기화 실패와 timeout은 별도 실패로 기록한다.

## 실행과 보존

JDK 17로 전체 검증 뒤 `:core:vehicle:assembleDebugAndroidTest`를 빌드한다.
전용 Pleos에서 기존 앱의 데이터, 권한과 설정을 비공개로 보존하고, 같은 이름의 기존 계측 패키지가 없을 때만 새 테스트 APK를 설치한다.
실행 대상은 `NaviInitialConnectionTest`이며 실제 두 인스턴스의 연결/해제를 확인한다. 건너뜀은 통과로 계산하지 않는다.
실패해도 외부 실행의 finally에서 이번에 설치한 계측 패키지만 제거하고 기존 앱 데이터와 설정을 대조한다.

원문 SDK 진단, 개인별 설치와 기기 정보는 비공개 검증 기록에서 관리한다.
경로 정보 API를 호출하지 않으므로 전후 실제 안내 경로를 readback 검증했다고 기록하지 않는다.

## 공식 근거

- [SDK 설치](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/importing-sdk)
- [초기화와 해제](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/initialization-and-release)
- [기능별 권한](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/declaring-permissions)
