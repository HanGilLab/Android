# 차량 관찰값 저장 계약

## 저장 범위

`VehicleObservationRepository`는 이미 관찰한 SDK 기반 상태를 활성 연습 세션에 연결하는 저장 계약이다. 제품의 수집기나 화면은 아직 연결하지 않는다.

- 출처는 `SDK_STATE_OBSERVATION`이며 시연 콘텐츠나 사용자 메모와 구분한다.
- `observedAtEpochMillis`는 앱이 스냅샷을 관찰한 벽시계 시각이다. SDK 발생 시각이나 각 값의 수신 시각을 뜻하지 않는다.
- 현재 `VehicleGateway`는 conflated 상태를 제공하므로 중간 값과 동일 값의 반복이 생략될 수 있다. 전체 SDK 이벤트, 조작 횟수나 운전 능력의 근거로 사용하지 않는다.
- 기어, 방향지시등, 브레이크와 연결 상태를 저장한다. 위치, 위치 신뢰도와 조향 입력은 수집하거나 추정하지 않는다.
- 상세 내용을 표시하는 후속 화면은 주차와 UX 제한 해제를 모두 확인해야 한다. 저장 계약을 추가한 것만으로 주행 중 상세 조작을 허용하지 않는다.

## 상태와 순서

`Available`에는 실제 값이 있어야 한다. `Unknown`, `Unsupported`, `PermissionDenied`, `Error`는 값 없이 서로 다른 상태로 저장한다. 실제 브레이크 `false`와 방향지시등 `OFF`는 누락 상태와 구분한다.

알 수 없는 출처, 상태나 enum, 상태와 값의 불일치는 복원 오류로 전파한다. SQLite가 잘못된 실수나 문자열을 정수로 바꿀 수 있으므로 관찰 시각과 브레이크의 원래 저장 타입도 확인한다. 저장 손상을 정상 값이나 SDK의 미지원 상태로 바꾸지 않는다.

관찰 시각이 0 이하이면 `InvalidTime`으로 거부한다. 양수 벽시계가 역행하거나 같은 시각이 반복돼도 원래 시각을 보존하고 자동 증가 ID로 추가 순서를 유지한다. 시각을 보정하거나 정렬해서 발생 순서를 꾸미지 않는다.

세션 조회와 추가는 하나의 Room 트랜잭션에서 실행한다. 없는 세션은 `MissingSession`, 종료 세션은 `FinishedSession`이다. 저장 오류와 취소는 성공으로 바꾸지 않는다. 세션 외래키와 삭제 전파는 연결되지 않은 행이 남는 것을 막는다. 삭제 UI는 후속 기능이다.

## 마이그레이션과 검사

Room v3는 `vehicle_observation` 테이블과 세션 인덱스를 추가한다. v1에서 v2, v2에서 v3으로 명시적으로 전진하며 기존 준비와 세션을 수정하거나 관찰값을 채워 넣지 않는다. 파괴적 마이그레이션은 사용하지 않는다.

모든 Gradle 명령은 JDK17로 실행한다. macOS에서는 기존 JDK를 다음처럼 명령 단위로 선택한다.

```sh
JAVA_HOME="$(/usr/libexec/java_home -v 17)" ./scripts/verify.sh
JAVA_HOME="$(/usr/libexec/java_home -v 17)" ./gradlew :core:database:assembleDebugAndroidTest
```

JVM 검사는 상태 복원, 값 오염, 순서, 세션 경계, 외래키, 취소, 저장 실패, 동시 종료와 v1/v2 자료 보존을 다룬다. Android 계측은 별도 `core.database.test` 패키지의 격리 DB를 사용한다.

- `isolatedRoundTripPreservesStatesAndAppendOrder`: 검사 전용 자료의 저장, 재열기와 복원, 종료 뒤 추가 거부를 확인한다. 실제 SDK 입력을 수신한 검사로 계산하지 않는다.
- `privateVersionTwoCopyMigratesWithoutChangingExistingRows`: 명시적 `allowPrivateDatabaseCopy=true` 옵션이 있을 때만 외부 실행자가 준비한 일관된 v2 복사본을 검사한다. 기본 발견 실행에서는 건너뛰며 마이그레이션 통과로 계산하지 않는다.

복사본 검사의 외부 실행자는 사전 허용된 자료만 사용하고 원본 DB와 WAL의 일관성을 보존해야 한다. 검사 패키지의 기존 설치 여부와 소유권을 먼저 확인하고, 실패 시에도 자신이 만든 패키지와 복사본만 정리한다. 원본 앱, 저장 자료, 권한과 설정은 전후 대조하며 원문 행이나 개인 환경은 공개 기록에 넣지 않는다.

## 후속 구현

앱의 명시적 수집 수명주기, 화면 이탈과 내비 전환 중 동작, 수집 중단의 표시와 복습 화면은 별도 구현과 검증이 필요하다. 저장 기반 완료를 APP-04 전체 완료나 실제 주행 자료 수집 성공으로 해석하지 않는다.
