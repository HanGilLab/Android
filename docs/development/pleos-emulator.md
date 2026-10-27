# Pleos 에뮬레이터와 앱 연결

공식 문서 확인 기준: 2026-10-26 · Pleos Connect Emulator v2.1.0 / API 34.
아래 터미널 예시는 macOS/Linux의 bash·zsh 기준이다.

## 연결 구성과 현재 상태

| 구성 | 역할 |
|---|---|
| Pleos Connect Emulator | 차량용 시스템과 앱 실행 |
| ADB | 대상 에뮬레이터 선택, APK 설치와 포트 연결 |
| Virtual Console | 브라우저에서 차량 상태·이벤트 변경 |
| Pleos SDK | 앱이 차량 상태를 수신하고 내비게이션 등과 연동 |

Pleos 전용 환경에서 APK 실행과 Vehicle SDK 조회·이벤트 수신을 검증했다.
`core:vehicle`은 기어·방향지시등·브레이크 페달 상태를 읽고, AAOS의 UX 제한을 별도로 확인한다.
P 기어와 UX 제한 해제가 모두 확인된 경우에만 상세 내용을 표시한다.
내비게이션 연동과 연습 기록 저장은 후속 구현이므로 ‘내비게이션 사용 불가’는 유지된다.

## 1. 개발 도구 준비

Android Studio에서 다음 항목을 준비한다.

- Gradle JDK: **17**
- SDK Platforms: **Android API 36** — 이 프로젝트의 컴파일용
- SDK Tools: **Android SDK Build-Tools 35.0.0**, **Android SDK Platform-Tools**, **Android Emulator**

SDK Manager의 **Android SDK Location**을 확인해 `ANDROID_HOME`에 지정하고
`platform-tools`를 PATH에 추가한다. 아래 경로는 설치 위치에 맞게 바꾼다.

```bash
export ANDROID_HOME="<Android SDK Location>"
export PATH="$ANDROID_HOME/platform-tools:$PATH"
adb version
```

프로젝트 컴파일 SDK 36과 에뮬레이터 실행 환경 API 34는 서로 다른 설정이다.
에뮬레이터에 맞추기 위해 프로젝트의 `compileSdk`를 내리지 않는다.
SDK를 활용한 개발은 [Pleos Playground](https://pleos.ai/playground)에서 프로젝트를 생성하고,
필요한 SDK의 제공 상태를 확인한 뒤 진행한다. [공식 프로젝트 안내](https://document.pleos.ai/docs/pleos-only/create-my-project)

## 2. Pleos 시스템 이미지 설치

Android Studio → **Tools → SDK Manager → SDK Update Sites**에서
`Pleos Connect System Image` 사이트를 추가한다. [공식 설치 안내](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator)

| 공식 안내의 OS 구분 | SDK Update Site URL |
|---|---|
| macOS · ARM64 이미지 | `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-arm64-sys-img.xml` |
| Windows / Linux | `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-sys-img.xml` |

**SDK Platforms → Show Package Details → API 34**에서 Pleos Connect 시스템 이미지를 선택해 설치한다.
설치 후 Android Studio를 재시작한다. 일반 휴대폰용 시스템 이미지와 구분한다.

## 3. Automotive 가상 장치 생성과 부팅

Device Manager → **Create Virtual Device → Automotive → Pleos Connect v2.1.0**에서
**API 34 / Pleos Connect v2.1.0 APIs** 이미지를 선택한다.

- AVD RAM: **4 GB**
- Boot option: **Cold / Cold boot**
- 그래픽 설정과 호스트 요구사항: [공식 AVD 설정 안내](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/configure-pleos-connect-emulator) 참고

장치를 생성하고 Device Manager에서 실행한다. Quick boot에서는 VHAL을 사용할 수 없다고
공식 문서에 안내되어 있으므로 Cold boot를 사용한다. RAM 값은 AVD 할당량이며,
호스트에는 Android Studio와 에뮬레이터를 함께 실행할 여유가 필요하다.

## 4. ADB 대상 장치 선택

먼저 연결 목록을 확인한다.

```bash
adb devices -l
```

Device Manager에서 실행한 Pleos AVD의 SERIAL을 선택한다. 아래 `emulator-5554`는 예시이며
실제 목록의 값으로 바꾼다. 이후 장치 명령은 모두 `-s "$PLEOS_SERIAL"`을 지정한다.

```bash
export PLEOS_SERIAL="emulator-5554"
adb -s "$PLEOS_SERIAL" get-state
adb -s "$PLEOS_SERIAL" shell getprop sys.boot_completed
```

기대 결과는 순서대로 `device`, `1`이다. `device`여도 Android 부팅이 끝나지 않았을 수 있으므로
부팅 속성에 `1`이 표시된 뒤 설치한다. 장치가 재시작되거나 SERIAL이 바뀌면 다시 선택한다.
[ADB 장치 선택 안내](https://developer.android.com/tools/adb#directingcommands)

## 5. 한길졸업 APK 설치와 실행

저장소 루트에서 실행한다. Android Studio로 실행할 때도 실행 대상을 Pleos AVD로 지정한다.

```bash
./gradlew :app:assembleDebug
adb -s "$PLEOS_SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$PLEOS_SERIAL" shell am start -W -n \
  io.github.hangillab.hangil.debug/io.github.hangillab.hangil.MainActivity
```

확인할 결과:

- APK 설치 결과 `Success`
- Activity 실행 결과 `Status: ok`와 한길졸업 초기 화면
- SDK 연결 시 ‘차량 연결됨’, 미지원·권한 누락·연결 실패 시 해당 상태 표시
- P 기어와 UX 제한 해제가 모두 확인되면 상세 내용 표시, 그 외에는 잠금 안내
- debug APK에서만 주차·UX 허용 시 개발용 차량 상태 표시

debug 패키지명은 `io.github.hangillab.hangil.debug`이고 Activity 클래스에는 `.debug`가 붙지 않는다.
`install -r`은 기존 앱을 업데이트한다. [ADB 앱 설치 안내](https://developer.android.com/tools/adb#move)

## 6. Virtual Console 연결

Pleos 에뮬레이터를 켠 상태에서 PC의 포트를 에뮬레이터의 콘솔 서비스에 연결한다.
`--no-rebind`로 기존 포워딩을 덮어쓰지 않는다.

```bash
adb -s "$PLEOS_SERIAL" forward --no-rebind tcp:8088 tcp:8088
adb forward --list
```

목록에서 선택한 SERIAL과 `tcp:8088 tcp:8088` 조합을 확인한 뒤,
PC 브라우저에서 [Virtual Console](http://127.0.0.1:8088/)을 연다.
`forward --list`는 모든 장치의 목록을 표시하므로 SERIAL도 함께 확인한다.
[공식 콘솔 연결 안내](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/use-vehicle-emulator),
[ADB 포워딩 명령](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/docs/user/adb.1.md)

8088 포트가 이미 사용 중이면 기존 연결을 지우지 않고 다른 PC 포트를 사용할 수 있다.
에뮬레이터 쪽 포트는 8088을 유지한다.

```bash
adb -s "$PLEOS_SERIAL" forward --no-rebind tcp:18088 tcp:8088
```

이때 브라우저 주소는 [대체 포트의 Virtual Console](http://127.0.0.1:18088/)이다.
이미 올바른 SERIAL의 동일 매핑이 있다면 그 연결을 사용하면 된다.

## 7. 차량 상황 재현과 앱 확인

Virtual Console → **Emulation Controls**에서 Domain과 카드를 골라 값을 변경한다.
아래 항목은 콘솔 제공 기능이며, 앱에서의 수신 가능 여부는 SDK 지원·권한을 별도로 확인한다.
[공식 콘솔 사용 안내](https://document.pleos.ai/docs/pleos-only/app-test-guide/virtual-console-guide)

| Domain / 카드 | 재현할 상태 |
|---|---|
| Motion / Gear Selection | P·R·N·D |
| Motion / Brake Pedal | 브레이크 페달 위치 |
| Motion / Steering Angle | 핸들 각도 |
| Convenience / Turn Signal State | 방향지시등 상태 |
| Safety / Telltales | 지원되는 차량 경고등 |

기어·방향지시등·브레이크는 앱까지 이어서 확인할 수 있다. 조향각 수신과 세션 저장은 후속 작업이다.
debug APK의 개발용 상태는 주차 및 UX 허용이 확인된 경우에만 표시된다.

| 시나리오 | 앱의 기대 동작 |
|---|---|
| SDK 연결 성공 | 실제 연결 결과에 따라 차량 연결 상태 변경 |
| 기어 D 또는 UX 제한 활성 | 상세 입력·복습 조작 잠금 |
| 주차 확인 + UX 제한 해제 확인 | 두 조건이 모두 확인된 경우에만 상세 조작 허용 |
| 연결 끊김·상태 미확인 | 상세 조작 잠금 유지 |
| 방향지시등·브레이크 변경 | SDK 콜백 값 표시, 누락·미지원·수신 오류 구분 |

Vehicle SDK 2.3.2는 공개 연결 상태 콜백이 없어 현재 기어를 1초 간격으로 조회한다.
호출 실패 또는 최대 3초의 응답 대기 초과를 감지하면 이전 값을 지우고 잠근 뒤 재연결한다.
앱이 화면에서 사라지면 리스너와 바인딩을 해제하고, 다시 표시될 때 새 상태를 확인한다.

구현 위치는 `core:vehicle`, 앱에 제공할 계약은 `core:domain`에서 관리한다.
콘솔의 페달 위치가 앱에도 같은 단위로 제공된다고 가정하지 않는다.
코스·위치·내비 연동과 세션 기록은 해당 API 확인 및 기능 구현이 별도로 필요하다.

## 8. 문제 해결과 연결 종료

| 증상 | 확인 순서 |
|---|---|
| `adb`를 찾을 수 없음 | Platform-Tools 설치와 PATH 확인 |
| 장치가 없거나 `offline` | Device Manager의 Pleos AVD 부팅 상태와 Cold boot 확인 후 목록 재조회 |
| `more than one device/emulator` | SERIAL을 다시 선택하고 장치 명령에 `-s` 지정 |
| 콘솔 접속 실패 | v2.1.0 이상 이미지 → 부팅 완료 → 포워딩의 SERIAL·포트 → 브라우저 주소 확인 |
| `cannot rebind` 또는 포트 사용 중 | 포워딩 목록 확인, 이미 맞는 연결을 사용하거나 대체 PC 포트 선택 |
| 콘솔 값은 변하지만 앱은 미연결 | Pleos 시스템 이미지·차량 SDK 서비스·앱의 조회 권한과 재연결 상태 확인 |

작업이 끝나면 자신이 만든 포워딩을 목록에서 확인한 뒤 해당 포트만 제거한다.

```bash
adb forward --list
adb -s "$PLEOS_SERIAL" forward --remove tcp:8088
```

대체 포트로 연결했다면 마지막 명령에서 `tcp:18088`을 제거한다.
이후 Device Manager에서 해당 AVD를 종료한다.

## 연결 확인 체크리스트

- [ ] Pleos v2.1.0 / API 34 이미지로 Cold boot했다.
- [ ] 대상 SERIAL의 ADB 상태가 `device`이고 부팅 완료 값이 `1`이다.
- [ ] 해당 장치에 debug APK를 설치하고 초기 화면을 실행했다.
- [ ] 선택한 장치의 8088 서비스로 포워딩하고 Virtual Console에 접속했다.
- [ ] 콘솔에서 차량 상태 변경을 확인했다.
- [ ] 실제 SDK 수신값과 미확인·미지원 상태를 구분했다.
- [ ] 작업 후 생성한 포워딩과 AVD를 정리했다.
