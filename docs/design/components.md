# 공통 연습 UI 컴포넌트

`core:designsystem`은 표시와 사용자 콜백을 제공한다. SDK와 저장소 상태나 조작 허용 여부를 계산하지 않는다.
호출 화면/도메인은 주차와 UX 제한 해제를 모두 확인한 뒤 상세 내용을 표시하고 동작을 허용해야 한다.
끊김, 미확인, 권한 거부를 빈 결과나 정상 상태로 바꾸지 않는다.

## API와 사용

| 컴포넌트 | 역할과 조건 |
|---|---|
| `PracticePage / PracticeColumns` | 기본 스크롤 페이지와 넓은 화면의 두 열 배치. 기본 바깥32dp, 최대폭1440dp |
| `ParkedPage / ParkedPanel / ParkedBadge` | 홈/준비의 바깥48dp와 최대폭1600dp, 내부32/36dp 카드, 민트/크림 배지. 정확한 역할은 [토큰](tokens.md) 참고 |
| `PracticePanel / PracticeBadge` | 둥근 정보 카드와 문구 배지. 배지 색만으로 상태를 전달하지 않음 |
| `PracticeButton` | Material 주요 버튼, 높이76dp와 너비64dp 이상의 토큰과 `enabled`, `onClick`, `RowScope` 내용 슬롯 |
| `PracticeTextButton` | Material 보조 버튼, 높이72dp와 너비64dp 이상의 토큰과 같은 호출 규칙 |
| `ParkedButton` | 주요 `prominent=true`는 Button, 보조 `false`는 FilledTonalButton. 최소높이76/72dp, 최소너비64dp, 가로32/세로20dp 패딩 |
| `ParkedChoice` | 실제 RadioButton과 selected/disabled 의미, 24dp 모서리 clip. `showDescription`으로 선택한 항목의 설명만 표시 가능. 최소높이는 설명 표시120dp/숨김72dp이며 내용에 따라 커짐 |
| `ParkedDisclosure` | 기본 접힘, 최소높이64dp, 제목과 펼치기/접기24sp, expanded/collapsed 상태 설명. 안전 판단은 호출자가 수행 |
| `ParkedLockedNotice` | 홈/준비의 큰 글자와 카드로 잠금 안내. 실제 허용 판정은 호출자 책임 |
| `PracticeChoice` | 선택 카드, 최소높이120dp, RadioButton/selected 의미. 끝의 `enabled=false`는 선택 변경을 막고 비활성 의미를 제공함 |
| `PracticeLoadingNotice` | 명시적 로딩 문구와 indeterminate progress. 완료, 정상, 0%로 추정하지 않음 |
| `PracticeEmptyNotice` | 확인된 빈 결과의 제목과 설명. 오류나 미확인을 빈 결과로 매핑하지 않음 |
| `PracticeErrorNotice` | 오류 컨테이너/글자색의 제목과 설명과 선택적인 행동 슬롯. 자동 재시도나 성공 전환 없음 |
| `PracticeLockedNotice` | 주차와 UX 확인 필요 문구. 이 컴포넌트 자체가 실제 주차 상태를 확인하는 것은 아님 |

버튼의 문구, 콜백, 비활성 조건은 호출자가 제공한다. 일반 버튼과 같은 Material 동작을 유지한다.
상위 레이아웃은 최소 조작 영역을 확보하며 고정 크기나 한 줄 생략으로 확대 글꼴을 자르지 않는다.
홈과 세션, 코스 준비는 `Parked*`를 사용한다. 지도와 위치 확인 화면은 `Practice*` 기본 페이지와 패널을 사용한다.
홈은 현재 단계의 주요 행동 하나를 먼저 보여 준다. 준비 전/다시 준비는 준비, 준비 완료는 시작, 진행 중은 마무리가 주요 행동이다.
선택지를 먼저 보여 주고 시연 출처는 짧은 배지로 유지하며 긴 안내와 기록 상세는 필요한 경우에만 펼친다.
`ParkedDisclosure` 자체는 `rememberSaveable`로 펼침 상태를 보관한다. 화면 호출자의 `key`가 주차/잠금, 준비 단계, 세션이나 선택 변경 경계에서 상태를 초기화한다.
이 컴포넌트가 차량 상태를 구독하거나 조작 허용을 보장하는 것은 아니다. 상세는 항상 화면의 주차+UX 게이트 안에 둔다.
저장, enabled와 콜백의 안전 조건은 유지하며 복습/완료를 포함한 DES-06 전체는 별도 미완료다.

`PracticeChoice`의 원 장식과 `ParkedChoice` 내부 RadioButton은 접근성에서 중복 낭독하지 않는다. 선택 여부와 조작 가능 여부는 각각 native semantics로 전달한다.
비활성 `PracticeChoice`의 설명은 `onSurfaceVariant`로 읽기 쉽게 유지하고 테두리와 원 장식은 Material 비활성 색을 사용한다.
`ParkedChoice`의 설명도 `onSurfaceVariant`를 사용하며 비활성 선택 테두리는 `outline`, RadioButton은 Material 비활성 색이다. 비선택 카드에는 테두리가 없다.
비활성 버튼의 대비를 활성 글자 대비 합격으로 계산하지 않는다.
오류 패널의 행동은 실제 가능한 경우에만 제공하며, 버튼을 눌렀다고 조회 성공이나 오류 해제를 표시하지 않는다.

## 검증과 적용 경계

`ComponentCatalogFlowTest`는 debug 전용 빈 `ComponentCatalogActivity`에 상태 예시를 주입한다.
호스트는 exported=false이며 런처 항목과 주행 최적화 예외가 없고 release에 포함하지 않는다.
카탈로그는 제품 ViewModel, SDK, DB에 연결하지 않으며 실제 차량/저장소 상태가 아님을 고정 제목 아래에 표시한다.
버튼 콜백, 비활성 터치, 선택, RadioButton 의미, 로딩 progress, 오류 행동을 실제 Compose UI에서 확인한다.
이는 플랫폼 오류 주입, 실제 지도 구간 선택(MAP-03), 복습 기능이나 전체 UI/UX 완료의 증거가 아니다.

기존 공통 카탈로그 검증은 실제 Pleos의 라이트/다크와 글꼴1.0/1.3 설정을 확인하고 화면 전체 PNG에서 표시, 잘림을 검토했다.
캡처는 Compose의 제목 표시, native 활성 패키지, 호스트의 창 포커스를 확인한다.
이 조건만으로 중앙 창의 실제 그리기를 추정하지 않고 저장한 native PNG를 직접 검토한다.
별도 제품 준비 시나리오에서는 실제 선택, 브리핑, 돌아가기를 실행하되 초안을 저장하지 않아 기존 준비를 보존한다.
검증 후 원래 글꼴과 야간 설정, 앱 데이터와 권한을 확인하고 이번에 시작한 AVD만 종료한다.
자동 검사, 실기, 최신 PR 검토의 실제 결과는 해당 [작업 계획](../plans/completed/2026-10-28-shared-practice-components.md)에 기록한다.

PR #32의 `Parked*` 적용은 [가독성 개선 완료 계획](../plans/completed/2026-10-29-parked-ui-polish.md)의 실제 홈/준비 기본/1.6배/다크, DDS P→D→P와 선택 보존, 카드 모서리 검증으로 별도 추적한다.
과거 카탈로그 결과와 새 주차 화면 결과를 합쳐 모든 화면/플랫폼 오류를 확인했다고 해석하지 않는다. 이번 문서 정정에서 새 실기는 실행하지 않는다.
