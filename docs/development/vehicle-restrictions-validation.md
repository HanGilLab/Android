# Pleos 기어·UX 제한 실기 검증

`VehicleRestrictionFlowTest#actualGearAndUxCombination`은 외부에서 설정한 실제 기어·UX 조합과 홈 화면을 함께 검사한다.
테스트는 생산 `PleosVehicleGateway`와 `AaosDrivingRestrictionsProvider`를 별도로 구독한다.
이 프로브는 앱 DI 인스턴스의 내부 상태를 읽거나 값을 주입하지 않으며, 실제 SDK 기어·연결과 AAOS 기반 주차/UX 상태가 기대 조합이 될 때까지 기다린다.

## 준비

- 설치된 전용 Pleos AVD의 이름·QEMU·부팅 완료·현재 사용자를 확인한다. 실차나 다른 장치에는 실행하지 않는다.
- 원래 기어·플랫폼 UX·표시 설정과 앱 DB를 비공개로 보존한다. 기존 AVD·데이터를 지우거나 앱을 다운그레이드하지 않는다.
- 준비를 저장한 debug 앱과 같은 소스의 계측 APK가 필요하다. 준비가 없는 새 설치에서의 온보딩 검사는 이 시나리오 범위가 아니다.
- JDK 17로 전체 검사와 계측 APK를 빌드하고, 무거운 Gradle 실행이 끝난 후 AVD 검증을 시작한다.
- FusedLocation·계정·새 권한은 필요하지 않다. 앱 또는 플랫폼 권한을 추가로 부여하지 않는다.

macOS의 명령별 JDK 선택 예:

```sh
HANGIL_JDK17="$(/usr/libexec/java_home -v 17 -F)" || exit 1
JAVA_HOME="$HANGIL_JDK17" ./scripts/verify.sh
JAVA_HOME="$HANGIL_JDK17" ./gradlew :app:assembleDebugAndroidTest
```

## 외부 제어와 관찰

Virtual Console의 DDS 연결을 실제 브라우저에서 확인하고 P/D를 선택한다. HTTP 200만으로 연결 성공을 판단하지 않는다.
기어와 독립적인 UX 조합은 [Pleos 공식 ADB 안내](https://document.pleos.ai/en/docs/pleos-only/vehicle-app-developer-guide/adb-command-reference-guide#enable--disable-driving-restrictions)의 `driving_regulation on/off`를 제공하는 이미지에서만 시도한다.
명령의 성공 여부와 실제 `CarUxRestrictionsManagerService`의 현재 `DO`·`UxR` 값을 확인한다. 설정표나 과거 변경 로그를 현재 값으로 읽지 않는다.
서비스가 없거나 보호 정책으로 거부되면 해당 조합을 차단으로 기록하며 임의 명령·권한 변경·보호 우회로 대체하지 않는다.

아래 `SERIAL`은 확인한 전용 AVD 식별자를 넣는 예시다. 식별자·원문 dump·DB·화면은 비공개 검증 기록으로만 보관한다.

```sh
adb -s SERIAL shell am instrument -w -r \
  -e expected_gear PARK -e expected_ux UNRESTRICTED \
  -e class io.github.hangillab.hangil.VehicleRestrictionFlowTest#actualGearAndUxCombination \
  io.github.hangillab.hangil.debug.test/androidx.test.runner.AndroidJUnitRunner
```

각 실제 조합에 맞춰 `expected_gear`는 `PARK`/`DRIVE`, `expected_ux`는 `UNRESTRICTED`/`RESTRICTED`로 지정한다.
이 인자는 기대값이며 SDK·플랫폼 상태를 바꾸지 않는다. 제어 선행 조건이 실패하면 테스트를 계속하지 않는다.

| 실제 조합 | 기대 화면 |
|---|---|
| P + UX 해제 | 준비·저장 코스가 보임 |
| P + UX 제한 | 잠금, 준비·지도·세션 조작·장식 캐릭터 숨김 |
| D + UX 해제 | 잠금 유지 |
| D + UX 제한 | 잠금 유지 |

계측 성공과 실제 전면 표시를 구분한다. 테스트가 앱 캐시에 남긴 `screenshots/ux-기어-UX.png` 전체 이미지를 확인해 중앙 영역이 실제 앱인지 검사한다.
Compose 노드·native active-window 패키지만으로는 뒤에 가려진 화면을 검출할 수 없다. 계측 종료 후 내비가 전면에 오는 화면과 계측 중 캡처도 구분한다.
필요하면 계측 중의 창 경계·가시성 정보를 함께 확인한다. 빈 UI 계층은 미확인이다.

## 현재 이미지에서의 결과 — 2026-10-28

- Android 14/API34 `user` 이미지에는 공식 `hmg_car_service`가 없었다. on 명령은 exit20으로 실패했다.
- 읽기 전용 `car_service help`도 non-user build 요구로 거부됐다. 보호를 우회하지 않았다.
- 선행 실패 뒤 한 번 실행된 P+제한 프로브는 25초 timeout으로 실패했다. 제한 적용 성공이나 앱 잠금 결함의 증거가 아니다.
- 정상 P→D→P의 실기 3회는 건너뜀 없이 통과했다. 실제 SDK/AAOS·앱 화면과 플랫폼 현재 DO/UxR `false/0 → true/80 → false/0`을 대조했다.
- 전후 DB v2 전체 행이 동일했다(준비1개·종료 세션3개). 원래 P·UX·폰트/야간 상태를 확인하고 이번에 시작한 AVD만 종료했다. override 적용·새 권한 부여는 없었다.
- **P+UX 제한, D+UX 해제와 실제 연결 단절·권한 거부·미지원은 미완료다.** ENV-06 완료나 모든 오류 시나리오 검증으로 확대하지 않는다.

지원하는 플랫폼·공개 제어 경로가 확보되면 원래 상태를 보존한 뒤 독립 조합을 재실행한다.
override가 실제 적용됐다면 원래 규제를 복원하고 정상 D 제한·P 해제를 다시 확인한다.
