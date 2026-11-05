# Pleos 마지막 위치 1회 조회 검증

- 상태: MAP-01 최종 환경과 채택 범위 확인 대기

## 목표와 현재 범위

주차와 UX 허용을 확인한 debug 지도에서 사용자가 요청한 마지막 위치만 1회 조회한다. 이후 연결한 전면 위치 표본 기록과는 별도 진단이다. 현재 계약, 결과 구분과 권한 검증 절차는 [마지막 위치 1회 조회](../../development/fused-location.md)를 따른다.

## 남은 검증

- [ ] MAP-01 최종 환경 검증과 채택 범위를 [출품 계획](2026-10-27-contest-delivery.md)의 미완료 기준에서 확인한다.
- SDK `timestampMillis`의 시간 기준과 `confidence` 단위는 미확인이다. 현재 위치, 최신 여부나 정확도로 해석하지 않는다.
- 실제 Empty 응답과 늦은 SDK callback 강제 재현은 미관찰이다. 자동 검사의 빈 결과/늦은 결과 검증과 구분한다.
- 독립 UX 조합과 플랫폼 오류 주입의 미확인은 [ENV-06 계획](2026-10-28-ux-restriction-combinations.md)에서 추적한다.

## 기존 근거

구현과 과거 실제 조회 결과는 [PR10](https://github.com/HanGilLab/Android/pull/10), 시스템 권한 허용과 회수 결과는 [PR25](https://github.com/HanGilLab/Android/pull/25)에 남아 있다. 과거 LastKnown 관찰과 권한 회수 결과를 미실행으로 되돌리거나 다른 환경의 성공으로 확대하지 않는다.

검증은 승인된 범위에서만 수행하고 원래 데이터/권한/설정을 보존한다. 계측 건너뜀은 실제 검증 성공이 아니며 좌표와 원문 로그는 공개하지 않는다.
