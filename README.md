# 한길졸업 · Android

[![Android CI](https://github.com/HanGilLab/Android/actions/workflows/ci.yml/badge.svg)](https://github.com/HanGilLab/Android/actions/workflows/ci.yml)

> 내가 매일 가야 하는 길 하나부터, 내 힘으로.

운전이 익숙하지 않은 성인이 생활 경로 하나를 연습하도록 돕는 Pleos 차량용 앱입니다.
출발 전 준비부터 주차 후 복습, 다음 연습까지 연결하는 경험을 만들고 있습니다.

## 기술 스택

- Kotlin · Jetpack Compose · Material 3
- Hilt · Room · KSP
- ViewModel · StateFlow
- JDK 17 · Gradle 8.13 · AGP 8.11.1 ([JDK 선택·검증](docs/development/jdk17.md))
- Android SDK: min 28 / compile 36 / target 34

## 프로젝트 구조

| 모듈 | 역할 |
|---|---|
| `app` | 앱 진입점과 모듈 연결 |
| `core:domain` | 공통 모델과 인터페이스 |
| `core:designsystem` | 테마와 공통 UI |
| `core:database` | 로컬 저장 기반 |
| `core:vehicle` | 차량 연동 기반 |
| `feature:home` | 준비 내용 요약·연습 시작/종료 |
| `feature:course` | 목표·코스 선택과 출발 전 브리핑 |
| `build-logic` | 공통 빌드 설정 |

## 실행과 검증

Android Studio에서 JDK 17과 Android SDK Platform 36을 설정합니다.
터미널은 IDE 설정과 별도로 [JDK 17을 선택](docs/development/jdk17.md)해야 합니다. 아래는 macOS 예시이며, Linux에서는 설치한 JDK 17 홈을 `HANGIL_JDK17`에 지정합니다.

```bash
HANGIL_JDK17="$(/usr/libexec/java_home -v 17 -F)" || exit 1
JAVA_HOME="$HANGIL_JDK17" "$HANGIL_JDK17/bin/java" -version
JAVA_HOME="$HANGIL_JDK17" ./gradlew :app:assembleDebug
JAVA_HOME="$HANGIL_JDK17" ./scripts/verify.sh
```

기능 단위 작업 계획 → 구현 → 검증 → PR → Codex 리뷰 → 수정 순서로 개발합니다.
PR 병합 후에는 main을 동기화하고 사용한 작업 브랜치를 로컬·원격에서 정리합니다.
검증 명령과 개발 규칙은 [AGENTS.md](AGENTS.md)를 참고하세요.
차량용 실행 환경은 [Pleos 에뮬레이터 연결 가이드](docs/development/pleos-emulator.md)를 참고하세요.

## 진행 상태

목표·시연용 코스·오늘의 과제를 선택하고, 준비 내용을 저장·복원할 수 있습니다.
주차와 화면 사용 가능 여부를 확인한 경우에만 상세 조작을 허용합니다.
직접 누른 연습 시작·종료 시각과 당시 코스·과제를 기기에 저장하고 앱 재실행 후 복원합니다.
이는 실제 출발·도착이나 주행 경로의 기록이 아닙니다. 실제 경로 안내·위치/차량 이벤트 수집·복습은 후속 구현입니다.
[세션 저장과 검증 범위](docs/development/practice-sessions.md)를 참고하세요.

## 라이선스

[Apache License 2.0](LICENSE)
