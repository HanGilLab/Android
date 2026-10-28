# 실제 기어와 UX 제한 독립 조합 검증

- 상태: 검증 대기 — 독립 UX 조합은 현재 이미지에서 차단
- 요구사항: [공모전 체크리스트 ENV-06](https://app.notion.com/p/40586a7a8f8a800092c1caea490e8a09)

## 목표와 범위

실제 Pleos 기어와 AAOS UX 제한을 독립적으로 바꿔 두 조건 모두 확인되어야 상세 조작이 열리는지 검증한다.
공식 `driving_regulation on/off`와 Virtual Console을 사용하며 테스트에서 상태를 대체하지 않는다.
실제 SDK/AAOS를 사용하는 프로브와 현재 앱 화면을 함께 검사한다. 제품 동작·권한·DB 스키마는 변경하지 않는다.
권한 거부·서비스 단절·미지원 오류 주입은 공개 제어 방법이 확인되지 않아 이번 범위에서 제외하며 ENV-06의 미실행으로 남긴다.

## 완료 조건

- [x] 기존 전용 AVD·설정·앱 데이터와 기어/UX 상태를 확인하고 보존한다.
- [ ] 실제 P+제한 해제에서 상세 조작이 열리고 P+제한 적용에서는 잠긴다.
- [ ] 실제 D+제한 해제에서도 잠기며, D+제한 적용의 기존 안전 동작을 유지한다.
- [x] 실제 SDK 기어·AAOS 기반 상태·화면을 대조하고 원래 상태 및 정상 P/D 자동 제한 전환을 복원한다.
- [x] 기존 준비·종료 세션을 보존하고 자동 검사·최신 PR CI·Codex 리뷰를 확인한다.

## 구현 단계

1. 공식 제어 명세와 현재 플랫폼의 읽기 가능한 상태를 확인한다.
2. 실제 생산 어댑터와 화면을 검사하는 외부 제어용 계측 시나리오를 추가한다.
3. 무거운 Gradle 검사와 AVD 시나리오를 순차 실행하고 실제 화면을 확인한다.
4. 결과·실패·남은 수동 항목을 구분해 노션에 기록한다.

## 검증 결과

| 명령 또는 시나리오 | 결과 | 근거 |
|---|---|---|
| 공식 독립 UX 제어 명세 | 확인 | `driving_regulation on/off`, 미문서화 reset/default 명령은 사용하지 않음 |
| 현재 이미지의 독립 UX 명령 | 실패/차단 | Android 14/API34 user 이미지에 `hmg_car_service`가 없어 공식 on 명령 exit20. `car_service help`도 non-user build 요구로 거부됨. 우회·권한 변경 없음 |
| P+제한 적용 프로브 시도 | 실패 — 선행 미충족 | 제어 명령 실패 후 기대 상태를 기다리다 25초 timeout. 실제 제한 적용·앱 결함으로 해석하지 않음 |
| P+UX 해제 → D+UX 제한 → P+UX 해제 | 통과 | 실제 생산 어댑터를 사용하는 독립 SDK/AAOS 프로브·앱 화면 3회, 건너뜀0. DO/UxR false/0 → true/80 → false/0 직접 대조, 중앙 앱의 전체 화면 PNG 확인 |
| P+UX 제한 / D+UX 해제 | 차단 / 미실행 | 현재 이미지의 지원 제어 경로 미확인. 정상 P/D 검사로 대신하지 않음 |
| 데이터·원래 상태 복원 | 통과 | 전후 DB v2의 준비1개·종료 세션3개 전체 행 동일, 원래 P/UX와 폰트·야간 설정 동일. 이번 AVD와 포워딩만 종료. 실제 override·새 권한 부여 없음 |
| `./scripts/verify.sh` | 통과 | Temurin17.0.21 기본 JIT, 품질·모듈 경계·Lint·84개 테스트(실패/오류/건너뜀0)·debug/unsigned release. 기존 결과 up-to-date 포함, 별도 계측 APK 빌드 통과 |
| PR CI·Codex 리뷰 | 통과·부분 개선 병합 | [PR #15](https://github.com/HanGilLab/Android/pull/15)의 33b24ca 커밋 CI4개·Codex 리뷰(지적 없음) 확인 후 병합. main ff-only 동기화·검토/병합 내용 일치·해당 로컬/원격 브랜치 정리 확인. 독립 UX 조합은 미완료 유지 |

## 남은 문제와 인계

명령 성공·빈 UI 계층·다른 앱의 로그만으로 검증 성공을 추정하지 않는다.
공식 명령의 override 지속기간은 명시되지 않았으므로 원래 P/UX와 정상 D 전환을 다시 확인한다.
실제 플랫폼 오류 주입과 독립 UX 조합을 구분하고 ENV-06 전체는 미완료로 유지한다.
현재 독립 UX 조합은 지원 이미지·공개 제어 경로 확인이 필요하다. user build 보호를 우회하거나 기존 AVD를 교체·삭제하지 않는다.
일반 P/D 실기 성공·현재 플랫폼 명령 실패·아직 미실행인 단절/권한/미지원을 서로 구분한다.
검증 명령·관찰 기준은 [계측 시나리오 안내](../../development/vehicle-restrictions-validation.md)에 기록했다.

## 공식 근거

- [Pleos 공식 ADB 명령](https://document.pleos.ai/en/docs/pleos-only/vehicle-app-developer-guide/adb-command-reference-guide#enable--disable-driving-restrictions)
- [Pleos 주행 제한 연동](https://document.pleos.ai/en/docs/pleos-only/vehicle-app-developer-guide/driving-restriction-development-guide)
