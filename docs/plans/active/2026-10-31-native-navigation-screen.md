# 주차 후 기본 내비 화면 열기

- 상태: 명시적 화면 요청은 main에 반영됐다. 기존 활성 안내 보존과 MAP-04 전체는 미완료다.

## 목표와 현재 범위

사용자가 주차와 UX 허용 상태에서 요청할 때만 기본 내비 전면 전환을 요청한다. 현재 RESUMED 호스트, 표시한 요청 세대와 최신 P+UX를 재확인하고, 화면 이탈이나 거부 뒤 자동 재요청하지 않는다.

`REQUESTED`는 SDK 호출 반환이며 실제 전면 전환이나 길안내 성공의 ACK가 아니다. 목적지/경유지/경로/정보/위치/취소 API는 이 화면 요청에서 호출하지 않는다. 구현과 재현 전제는 [현재 계약](../../development/native-navigation-screen.md#제품-경계와-판단)을 따른다.

## 남은 검증

- [ ] 변경 전 활성 목적지와 안내 상태를 확보한 조건에서 기존 안내가 보존되는지 확인한다. 과거에는 해당 사전 상태를 확보하지 못했다.
- [ ] 과거 반복 재검증의 미전환 원인을 확인한다. 본 표본 5개 중 3개는 전환했고 2개는 Main에 남았으며 원격 내부 원인은 미확정이다. 별도 라이트 성공은 이 실패를 취소하지 않는다.
- 목적지/경유지/실제 길안내, 타일 표시와 전체 MAP-04/APP-05의 위치/경로 연계는 이 화면 요청의 완료로 대신하지 않는다.
- 독립 UX 조합과 플랫폼 오류 주입의 미확인은 [ENV-06 계획](2026-10-28-ux-restriction-combinations.md)에서 추적한다.

## 기존 근거

- [실제 검증과 한계](../../development/native-navigation-screen.md#실제-검증과-한계)
- [기존 이력 진입 전후 관찰](../../development/native-navigation-screen.md#기존-이력-진입의-변경-전후-관찰)과 [70행 원값](../../development/measurements/native-navigation-entry-ui-times.csv)
- [전면 전환 재검증](../../development/native-navigation-screen.md#현재-main의-전면-전환-재검증)과 [7행 원값](../../development/measurements/native-navigation-foreground-revalidation.csv). 실패 latency는 비어 있으며 성공 표본의 지연으로 바꾸지 않는다.

실측 조건과 분포, 실패/누락/제외 및 한계는 해당 기록에 유지한다. 단순 요청 반환이나 계획 상태로 성능 향상을 판정하지 않는다.
