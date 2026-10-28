# JDK 17 선택과 런타임 검증

프로젝트의 Gradle은 JDK 17로 실행한다. 기본 `java`가 다른 버전이거나, 설치된 JDK가 여러 개일 수 있으므로 명령별로 선택하고 실제 버전을 확인한다.
전역 셸 설정, 기존 JDK, AVD 데이터는 덮어쓰지 않는다.

## macOS 명령줄

```bash
HANGIL_JDK17="$(/usr/libexec/java_home -v 17 -F)" || exit 1
JAVA_HOME="$HANGIL_JDK17" "$HANGIL_JDK17/bin/java" -version
JAVA_HOME="$HANGIL_JDK17" ./scripts/verify.sh
JAVA_HOME="$HANGIL_JDK17" ./gradlew :app:assembleDebugAndroidTest
```

`-F`는 요청한 JDK를 찾지 못했을 때 다른 버전으로 넘어가지 않도록 한다. Apple Silicon에서 arm64를 명시할 때는 `java_home -v 17 -a arm64 -F`로 선택한다.
`JAVA_HOME`은 각 명령에만 전달한다. 버전 확인이 17이 아니면 검증을 진행하지 않는다.

Linux에서는 설치한 JDK 17의 홈을 `HANGIL_JDK17`에 지정한 뒤 동일하게 명령별 `JAVA_HOME`을 전달한다. macOS 검색 명령을 사용하지 않는다.

## 패치 런타임 준비

ENV-09에서 확인하는 후보는 **Eclipse Temurin 17.0.21+9 / macOS arm64**다.
공식 archive, SHA-256, GPG 서명과 공식 fingerprint를 확인하고, 경로 탈출, 특수파일, 예상하지 못한 링크가 없는지 확인한 뒤 추가한다.
macOS archive는 `Contents/Home` 구조를 포함한다. 사용자 JDK 검색 영역인 `~/Library/Java/JavaVirtualMachines/`에 별도 `.jdk` 번들을 추가한 뒤 `java_home`이 실제로 선택하는지 확인한다.
설치 파일이 있다는 사실과 선택, 실행 성공은 구분한다. 기존 번들이 있으면 덮어쓰지 않고 버전, 출처를 먼저 확인한다.

확인 항목:

- SHA-256과 유효 GPG 서명이 공식 배포 근거와 일치한다.
- 실제 `java -version`과 `os.arch`가 선택한 JDK 17, 호스트 아키텍처와 맞는다.
- Gradle Launcher/Daemon과 새 테스트 worker가 해당 런타임을 사용한다.
- JIT 활성 상태에서 이전에 실패한 분석, 컴파일, 테스트, 빌드 경로를 새로 실행한다.
- 전체 검증 명령과 테스트 APK 빌드가 종료 코드 0이며 테스트 실패, 오류, 건너뜀을 구분한다.

설치, 선택 위치와 원문 JVM 진단은 ignored 로컬 기록에서만 관리한다. 개인 절대 경로, 장치 식별자, 인증정보를 PR, 노션, 공개 로그에 붙이지 않는다.
검증 캐시를 재사용한 성공과 `--rerun-tasks`로 실제 새로 실행한 결과도 구분한다.
Android Studio의 **Gradle JDK**는 IDE에서 별도로 확인한다. 명령줄 선택 성공만으로 IDE 설정까지 변경됐다고 기록하지 않는다.

## C1 치명 오류와 임시 우회

한 환경의 JDK 17.0.9 arm64에서 C1의 `Field too big for insn` 오류로 Gradle이 세 번 종료됐다.
같은 제목의 OpenJDK backport는 17.0.16에 수정 기록이 있으나 이 장비와 정확히 같은 결함이라고 확정하지 않는다.
앱 결함, 메모리 부족, 검증 성공으로 단정하지 않고 원문 로그와 실패 시점을 비공개로 보존한다.

명령 한정 `-Xint`는 당시 검사 완료를 위한 임시 우회였다. 패치 런타임의 안정성 확인은 이 옵션과 임시 worker/메모리 조정 없이 수행한다.
검증에 성공해도 모든 호스트, 모든 JVM 경로에서 오류가 없다는 보장으로 확대하지 않는다.
복구가 필요하면 해당 런타임을 사용하는 작업을 먼저 종료하고 자신이 추가한 번들만 분리한다. 기존 JDK와 전역 셸 설정은 유지한다.

## 공식 근거

- [Temurin 17.0.21+9 배포](https://github.com/adoptium/temurin17-binaries/releases/tag/jdk-17.0.21%2B9)
- [Archive 설치, 무결성](https://adoptium.net/installation/archives)
- [GPG 검증](https://adoptium.net/installation/ci-scripts)
- [AArch64 C1 수정 기록](https://bugs.openjdk.org/browse/JDK-8354685)

## 프로젝트 Gradle daemon 기준

`gradle/gradle-daemon-jvm.properties`는 Java 17을 요구한다. 이 기준은 Gradle daemon에서 `JAVA_HOME`보다 우선하므로 IDE가 생성한 Java 21 설정을 그대로 커밋하지 않는다.
기존 로컬 JDK를 삭제하지 않으며 명령줄에서는 위의 JDK 17 선택과 버전 확인을 유지한다.
설정 단계의 Foojay resolver 플러그인은 `settings.gradle.kts`에서 버전을 고정한다. Gradle settings 단계는 프로젝트 Version Catalog 생성보다 앞서므로 이 부트스트랩 플러그인은 settings에서 관리한다.
호환 JDK 17을 먼저 설치해야 한다. 이 daemon 기준 파일에는 다운로드 URL이 없어 daemon 부트스트랩을 자동 설치하지 않는다. Foojay는 프로젝트 toolchain용 resolver이며 설치 경로, 인증정보, 사용자별 SDK/IDE 설정은 공유하지 않는다. 별도 `-p build-logic check`에도 명령별 `JAVA_HOME` 선택을 유지한다.
