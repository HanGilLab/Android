# Pleos 마지막 위치 1회 조회 검증

- 상태: 진행 중
- 요구사항: [공모전 체크리스트 MAP-01](https://app.notion.com/p/40586a7a8f8a800092c1caea490e8a09)

## 목표와 범위

주차와 UX 허용 상태에서 사용자가 요청한 경우에만 Pleos FusedLocation의 마지막 위치를 한 번 조회한다.
연속·백그라운드 위치 수집, 현재 위치 보장, 코스 좌표·실제 경로 안내는 이번 범위에 포함하지 않는다.
마지막 위치·빈 결과·미지원·권한 거부·시간 초과·오류를 구분하고 원본 SDK 시각·신뢰도의 기준을 임의 추정하지 않는다.
기존 고정 시연 지도와 SDK 입력을 분리하고 좌표 원문은 공개 로그·캡처에 노출하지 않는다.

## 완료 조건

- [x] 순수 domain 계약과 core:vehicle의 실제 FusedLocation 1.1.3 어댑터를 연결한다.
- [x] null·권한 거부·미지원·오류·시간 초과를 0 좌표나 정상 상태로 대체하지 않는다.
- [x] 취소·시간 초과·화면 이탈 뒤 늦은 SDK 재바인딩과 callback 전달을 차단하고 정리한다.
- [ ] 주차·UX 동시 확인과 사용자 버튼·시스템 권한 결과에 따라 1회 조회하며 반복·늦은 결과를 차단한다.
- [ ] 관련 자동 검사·전체 검증·실제 Pleos 시나리오와 최신 PR CI·리뷰를 확인한다.

## 구현 단계

1. 한국어 최신 가이드·공식 metadata·실제 AAR ABI와 설치된 서비스/권한을 확인한다.
2. 마지막 위치 계약·단일 조회·SDK 수명주기 경계와 회귀 검사를 추가한다.
3. 주차 화면의 개발 확인 UI와 사용자 권한 요청을 연결한다. 사용자 동의 없이 권한을 자동 부여하지 않는다.
4. JDK 17 자동 검사 후 실제 장치의 권한 거부·정상/빈 결과·취소·빠른 재진입을 구분해 기록한다.

## 검증 결과

| 명령 또는 시나리오 | 결과 | 근거 |
|---|---|---|
| 공식 SDK 버전·AAR | 확인 | 한국어 가이드·metadata 1.1.3, 공식 SHA-256 일치·공개 ABI 확인. 영문 1.1.2 표기와 구분 |
| Pleos 서비스·권한 정의 | 확인 | 실제 AVD 서비스 1.0.0 설치, FUSED_LOCATION dangerous. 설치만으로 연결·입력 성공을 선언하지 않음 |
| SDK release 이후 내부 재연결 | 위험 경로 확인·실기 미재현 | 1.1.3 sources·bytecode에서 retry Job 미보관 및 release의 Job 취소 없음. 앱의 바인딩 경계로 대응 |
| 전체 자동 검사 최초 실행 | 실패 후 수정 | 취소와 정리 중 실패가 겹치면 coroutine 복구 예외에만 근거가 붙고 원래 취소 원인에서 유실되는 회귀 1개 발견 |
| 취소·정리 실패 보존 재검증 | 통과 | 원래 caller 취소 원인과 await 예외 인과관계에 정리 실패가 유지되는 검사 8개 |
| `./scripts/verify.sh` 최종 | 통과 | JDK 17.0.9, 품질·모듈 경계·Lint·자동 검사 87개(실패·오류·건너뜀 0), debug·unsigned release |
| UI 검증용 테스트 APK 빌드 | 통과 | 최종 source의 assembleDebugAndroidTest 성공 |
| 실제 권한 미허용·화면 재생성 | 통과 | 실제 AVD에서 명시적 미허용 상태·권한 버튼·자동 권한 요청 없음, 재생성 후 Idle 복귀. UI 시나리오 1개와 캡처 확인 |
| 권한 허용 이후 실제 SDK 수신·취소 | 미실행 | 사용자 동의 전 권한을 자동 부여하지 않음. 미실행을 SDK 미지원·정상 입력으로 대체하지 않음 |
| 최신 PR CI·Codex 리뷰 | 대기 | 생성 후 최신 커밋으로 확인 |

## 남은 문제와 인계

confidence의 단위·범위 및 timestampMillis의 시간 기준은 공식 문서·sources에서 정의되지 않았다.
신뢰도를 m·%로 변환하거나 SDK 시각을 현재 epoch와 차감해 최신 위치로 표시하지 않는다.
SDK 내부 retry coroutine 자체 취소와 앱에서 실제 플랫폼 재바인딩을 막는 것은 구분한다.
20초 제한은 이미 시작된 동기 Binder 호출을 강제 중단한다는 보장이 아니다.
지도 렌더링은 PR #9에서 검증·병합했으며 MAP-01 전체는 위치 입력의 실제 결과까지 확인한 뒤 판단한다.

## 공식 근거

- [FusedLocation 설치](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/project-setup)
- [권한](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/declaring-permissions)
- [수명주기](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/initialization-and-release)
- [마지막 위치 의미](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/location-data-access)
