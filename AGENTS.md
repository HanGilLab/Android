# 한길졸업 개발 지침

Kotlin·Compose 기반 Pleos 차량용 앱이다.
Vehicle SDK 상태 조회와 주차·UX 제한을 연동하며, 연습·복습 기능은 후속 구현이다.

## 개발 루프

1. 관련 코드와 명세를 읽고 `docs/plans/TEMPLATE.md`를 복사해 `active/`에 작업 계획을 만든다.
2. 목표·변경 범위·관찰 가능한 완료 조건을 정한 뒤 작은 단위로 구현한다.
3. `./scripts/verify.sh`로 검증하고 PR의 Codex 리뷰를 확인한다.
4. 발견한 문제를 수정하고 영향을 받는 검증을 다시 실행한다.
5. 결과와 남은 문제를 계획에 기록한다. 완료 조건 충족 후 계획을 `completed/`로 옮긴다.
6. PR 병합 후 main을 동기화하고 병합된 작업 브랜치를 로컬·원격에서 정리한다.

이미 계획이 있으면 이어서 갱신한다. 실패·미실행·미지원과 실제 성공을 구분한다.
main 변경은 작업 브랜치의 PR을 통해 진행한다. 기본 작업 브랜치 접두사는 `codex/`다.

## 병합 후 브랜치 정리

- PR의 병합 여부와 브랜치 끝 커밋을 확인한다. 병합 후 추가된 커밋이나 미커밋 변경이 있으면 보존한다.
- main으로 전환해 `git pull --ff-only origin main`으로 동기화한다.
- 원격 작업 브랜치는 병합 시 자동 삭제하고, `git fetch --prune origin`으로 원격 추적 정보를 정리한다.
- 로컬 작업 브랜치도 삭제한다. squash merge라면 PR의 병합 기록과 main 반영 여부를 확인한 뒤 정리한다.
- main과 아직 병합되지 않은 브랜치는 삭제하지 않는다.

## 모듈 경계

- `core:domain`: 순수 Kotlin 모델과 인터페이스. Android·DB·차량 구현 의존 금지.
- `feature:*`: `core:domain`, `core:designsystem`만 참조. 다른 feature나 구체적인 어댑터 참조 금지.
- `core:vehicle`, `core:database`: domain 계약을 구현하는 계층. SDK·저장소 의존은 해당 모듈 안에 둔다.
- `app`: 진입점과 DI 연결. 기능 로직은 해당 계층으로 보낸다.
- 새 모듈을 추가하면 `build-logic`의 ModuleBoundaries 정책을 명시적으로 갱신한다.
- 의존성 버전은 Version Catalog, 반복 설정은 convention plugin에서 관리한다.

## 검증과 완료 조건

- 전체: `./scripts/verify.sh`
- 품질: `./scripts/verify.sh quality` (Spotless, 모듈 경계, Lint)
- 테스트: `./scripts/verify.sh test` (build-logic 검사, JVM·Android 단위 테스트)
- 빌드: `./scripts/verify.sh build` (debug, unsigned release)
- 포맷 수정: `./gradlew spotlessApply`

바뀐 동작을 검증하는 테스트를 추가한다. 스냅샷이나 성공을 가장하는 기본값으로 검사를 우회하지 않는다.
화면 변경은 동작을 직접 확인하고 검증 결과를 기록한다.
자동 검사, 관련 수동 시나리오, 리뷰 지적 처리가 끝나야 해당 작업을 완료로 기록한다.

## Code Review Rules

리뷰 설명은 한국어로 작성하고, 변경으로 발생하는 구체적인 결함과 영향을 설명한다.
포맷 문제는 CI에 맡기고 다음 제품 경계를 중점적으로 검토한다.

### 주행 상태와 상세 조작

주차 상태 확인과 UX 제한 해제가 모두 확인된 경우에만 상세 입력·복습을 허용한다.
연결 끊김·상태 미확인·권한 거부 시에는 잠금을 유지한다. UX 제한 해제만으로 주차를 추정하지 않는다.

### 데이터 출처와 미지원 상태

실제 SDK 기록·시연 데이터·사용자 메모를 구분한다. 누락·미지원·오류를 0이나 정상 상태로 대체하지 않는다.
차량 이벤트만으로 운전 능력, 위반, 사고 위험을 확정하거나 조향·제동을 제어하지 않는다.

민감한 정보와 로컬 환경 기록은 소스·로그·공개 문서·산출물에 포함하지 않는다.
