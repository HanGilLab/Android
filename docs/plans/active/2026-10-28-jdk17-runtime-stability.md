# JDK 17 런타임 안정화

- 상태: 검증 대기
- 요구사항: [공모전 체크리스트 ENV-09](https://app.notion.com/p/40586a7a8f8a800092c1caea490e8a09)

## 목표와 범위

JDK 17.0.9 arm64의 C1 치명 오류로 전체 검증이 세 번 종료된 환경에서 공식 패치 런타임을 추가하고 임시 JIT 우회 없이 검증한다.
기존 JDK·AVD·저장 데이터·전역 셸 설정을 보존한다. 새 JDK 설치와 명령별 선택을 구분하며 Android Studio의 수동 설정을 대신 완료했다고 기록하지 않는다.
앱 동작·모듈·SDK 버전·Gradle 메모리 설정·CI 필수 검사는 변경하지 않는다.

## 완료 조건

- [x] 공식 archive의 SHA-256·GPG와 경로/링크 안전성 및 JDK 17/arm64 메타데이터를 확인한다.
- [x] 사용자 범위의 추가 JDK를 macOS java_home의 fail-fast 필터로 선택하고 실제 버전·아키텍처·JIT 활성화를 확인한다.
- [x] JIT 우회와 임시 작업자/메모리 조정 없이 이전 실패 경로의 품질·테스트·빌드를 새로 실행한다.
- [x] JDK 17의 `./scripts/verify.sh`와 테스트 APK 빌드를 통과하고 실패·캐시 재사용·실제 새 실행을 구분한다.
- [ ] 재현 가능한 명령·환경 보존·복구 기준을 문서화하고 최신 PR CI·Codex 리뷰를 확인한다.

## 구현 단계

1. ignored 준비 체크포인트와 공식 배포본의 무결성·서명·안전성을 다시 확인한다.
2. 기존 설치를 보존해 새 런타임을 추가하고 java_home의 실제 선택을 검증한다.
3. 기본 JIT에서 실제 품질/테스트/빌드를 실행하고 전체 검증 명령으로 확인한다.
4. 개발 안내와 상위 계획을 현재 필수 항목에 맞추고 PR·노션 근거를 갱신한다.

## 검증 결과

| 명령 또는 시나리오 | 결과 | 근거 |
|---|---|---|
| 기존 JDK 종료 | 과거 실패 | JDK 17.0.9 arm64 C1 `Field too big for insn` 3회. OOM·앱 결함으로 단정하지 않음 |
| 공식 archive 재감사 | 통과 | SHA-256 재계산 일치, 준비 단계의 공식 fingerprint/GPG 유효 서명 확인. 551개 항목·루트 하나·경로 탈출/특수파일/중복/링크 없음 |
| 런타임 메타데이터 | 확인 | Temurin 17.0.21+9, Darwin/aarch64, JDK, CommandLine capability, arm64 Mach-O |
| 추가 설치·java_home 선택 | 통과 | 사용자 범위의 공식 Temurin 17.0.21+9 번들 추가, java_home의 17/arm64/fail-fast 필터가 선택. 실제 Eclipse Adoptium 17.0.21/aarch64와 compiler·tiered JIT 활성/level4 확인. 기존 Oracle JDK·전역 셸 설정 보존 |
| 새 품질·테스트·빌드 실행 | 통과 | 선택된 Temurin 17.0.21에서 build-logic check와 품질/Lint/모듈 경계/단위 검사/debug/unsigned release/테스트 APK를 `--rerun-tasks --no-build-cache`로 실행. 기존 프로젝트 기본 worker·메모리 설정을 유지하고 명령 한정 JIT 우회도 제거. 84개 최신 테스트 보고서, 실패/오류/건너뜀 0 |
| 실제 JVM 실행 근거 | 통과 | fresh 실행의 Gradle daemon 2개·테스트 worker 8개의 실행 명령이 선택된 패치 JDK를 사용하고 JIT 우회가 없음을 비공개 로그에서 확인. 기존 JDK를 탐색한 로그와 실제 실행 JVM을 구분 |
| 전체 검증·테스트 APK | 통과 | 같은 JDK17의 `./scripts/verify.sh` 종료0. 공식 명령의 up-to-date 결과와 앞선 cache 없는 실제 실행을 구분. 테스트 APK는 fresh 빌드에서 실제 생성 |
| Android 결과물 | 동일성 확인·신규 실기 미실행 | 새 debug APK는 PR #13에서 실제 Pleos 검증한 보존 APK와 전체 SHA-256 동일. 비서명 payload 220개 항목도 전부 동일. 이 사실을 신규 AVD 실행/실기 시나리오 성공으로 바꾸지 않음 |
| 개발 안내 독립 검토 | 수정 확인 | IDE 설정만으로 터미널 JDK가 선택된다고 오해할 수 있던 README를 명령별 선택/실제 버전 확인으로 수정. 예시의 java_home17 fail-fast 선택·실제 17.0.21 실행 확인 |
| 최신 PR CI·Codex 리뷰 | 미실행 | PR 생성 후 확인 |

## 남은 문제와 인계

같은 제목의 AArch64 C1 오류는 OpenJDK 17.0.16에 수정 기록이 있지만 이 장비의 정확한 동일 버그라고 확정하지 않는다.
이번 실행의 통과를 모든 호스트·모든 JVM 경로의 안정성 보장으로 확대하지 않는다.
Android Studio의 실제 Gradle JDK 선택은 IDE에서 확인하기 전까지 명령줄 검증과 구분한다.
Pleos 실행 안내의 LAUNCHER/중앙 창 확인은 이전 PR #13의 실제 검증 근거를 반영한 문서 정합 수정이며 이번 JDK 작업의 신규 UI 검증이 아니다.
위치 PR #10은 현재 커밋의 리뷰·CI 완료와 별개로 사용자 권한 동의/실제 SDK 검증을 계속 기다린다. 권한을 자동 부여하거나 병합하지 않는다.

## 공식 근거

- [Temurin 17.0.21+9 공식 배포](https://github.com/adoptium/temurin17-binaries/releases/tag/jdk-17.0.21%2B9)
- [Archive 구조·무결성 안내](https://adoptium.net/installation/archives)
- [GPG 검증 안내](https://adoptium.net/installation/ci-scripts)
- [OpenJDK C1 backport](https://bugs.openjdk.org/browse/JDK-8354685)
