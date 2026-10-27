# 한길졸업 · Android

[![Android CI](https://github.com/HanGilLab/Android/actions/workflows/ci.yml/badge.svg)](https://github.com/HanGilLab/Android/actions/workflows/ci.yml)

> 내가 매일 가야 하는 길 하나부터, 내 힘으로.

운전이 익숙하지 않은 성인이 생활 경로 하나를 연습하도록 돕는 Pleos 차량용 앱입니다.
출발 전 준비부터 주차 후 복습, 다음 연습까지 연결하는 경험을 만들고 있습니다.

## 기술 스택

- Kotlin · Jetpack Compose · Material 3
- Hilt · Room · KSP
- ViewModel · StateFlow
- JDK 17 · Gradle 8.13 · AGP 8.11.1
- Android SDK: min 28 / compile 36 / target 34

## 프로젝트 구조

| 모듈 | 역할 |
|---|---|
| `app` | 앱 진입점과 모듈 연결 |
| `core:domain` | 공통 모델과 인터페이스 |
| `core:designsystem` | 테마와 공통 UI |
| `core:database` | 로컬 저장 기반 |
| `core:vehicle` | 차량 연동 기반 |
| `feature:home` | 초기 화면 |
| `build-logic` | 공통 빌드 설정 |

## 실행과 검증

Android Studio에서 JDK 17과 Android SDK Platform 36을 설정한 뒤 실행합니다.

```bash
./gradlew :app:assembleDebug
./scripts/verify.sh
```

기능 단위 작업 계획 → 구현 → 검증 → PR → Codex 리뷰 → 수정 순서로 개발합니다.
PR 병합 후에는 main을 동기화하고 사용한 작업 브랜치를 로컬·원격에서 정리합니다.
검증 명령과 개발 규칙은 [AGENTS.md](AGENTS.md)를 참고하세요.
차량용 실행 환경은 [Pleos 에뮬레이터 연결 가이드](docs/development/pleos-emulator.md)를 참고하세요.

## 진행 상태

프로젝트 기본 구성과 차량 상태 조회·주행 제한을 연결한 단계입니다.
코스 선택과 연습·복습 기능은 순차적으로 추가합니다.

## 라이선스

[Apache License 2.0](LICENSE)
