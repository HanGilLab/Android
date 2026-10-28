# 공통 연습 UI 컴포넌트

`core:designsystem`은 표시와 사용자 콜백을 제공한다. SDK·저장소 상태나 조작 허용 여부를 계산하지 않는다.
호출 화면/도메인은 주차와 UX 제한 해제를 모두 확인한 뒤 상세 내용을 표시하고 동작을 허용해야 한다.
끊김·미확인·권한 거부를 빈 결과나 정상 상태로 바꾸지 않는다.

## API와 사용

| 컴포넌트 | 역할과 조건 |
|---|---|
| `PracticePage / PracticeColumns` | 스크롤 가능한 페이지와 넓은 화면의 두 열 배치 |
| `PracticePanel / PracticeBadge` | 둥근 정보 카드와 문구 배지. 배지 색만으로 상태를 전달하지 않음 |
| `PracticeButton` | Material 주요 버튼, 높이76dp·너비64dp 이상의 토큰과 `enabled`, `onClick`, `RowScope` 내용 슬롯 |
| `PracticeTextButton` | Material 보조 버튼, 높이72dp·너비64dp 이상의 토큰과 같은 호출 규칙 |
| `PracticeChoice` | 선택 카드, 최소높이120dp·RadioButton/selected 의미. 끝의 `enabled=false`는 선택 변경을 막고 비활성 의미를 제공함 |
| `PracticeLoadingNotice` | 명시적 로딩 문구와 indeterminate progress. 완료·정상·0%로 추정하지 않음 |
| `PracticeEmptyNotice` | 확인된 빈 결과의 제목·설명. 오류나 미확인을 빈 결과로 매핑하지 않음 |
| `PracticeErrorNotice` | 오류 컨테이너/글자색의 제목·설명과 선택적인 행동 슬롯. 자동 재시도·성공 전환 없음 |
| `PracticeLockedNotice` | 주차와 UX 확인 필요 문구. 이 컴포넌트 자체가 실제 주차 상태를 확인하는 것은 아님 |

버튼의 문구·콜백·비활성 조건은 호출자가 제공한다. 일반 버튼과 같은 Material 동작을 유지한다.
상위 레이아웃은 최소 조작 영역을 확보하며 고정 크기나 한 줄 생략으로 확대 글꼴을 자르지 않는다.
코스 준비 화면의 다음/돌아가기 버튼이 공통 래퍼를 사용한다. 기존 문구·저장·enabled·콜백은 유지한다.
전체 화면 적용(DES-06)은 별도이며 다른 화면이 모두 새 패널을 사용한다고 표시하지 않는다.

선택의 원 장식은 접근성에서 중복 낭독하지 않는다. 선택 여부와 조작 가능 여부는 각각 native semantics로 전달한다.
비활성 카드 설명은 `onSurfaceVariant`로 읽기 쉽게 유지하고 테두리·원 장식은 Material 비활성 색을 사용한다.
비활성 버튼의 대비를 활성 글자 대비 합격으로 계산하지 않는다.
오류 패널의 행동은 실제 가능한 경우에만 제공하며, 버튼을 눌렀다고 조회 성공이나 오류 해제를 표시하지 않는다.

## 검증과 적용 경계

`ComponentCatalogFlowTest`는 debug 전용 빈 `ComponentCatalogActivity`에 상태 예시를 주입한다.
호스트는 exported=false이며 런처 항목과 주행 최적화 예외가 없고 release에 포함하지 않는다.
카탈로그는 제품 ViewModel·SDK·DB에 연결하지 않으며 실제 차량/저장소 상태가 아님을 고정 제목 아래에 표시한다.
버튼 콜백·비활성 터치·선택·RadioButton 의미·로딩 progress·오류 행동을 실제 Compose UI에서 확인한다.
이는 플랫폼 오류 주입, 실제 지도 구간 선택(MAP-03), 복습 기능이나 전체 UI/UX 완료의 증거가 아니다.

실제 Pleos의 라이트/다크와 글꼴1.0/1.3 설정을 확인하고 화면 전체 PNG에서 표시·잘림을 검토한다.
캡처는 Compose의 제목 표시·native 활성 패키지·호스트의 창 포커스를 확인한다.
이 조건만으로 중앙 창의 실제 그리기를 추정하지 않고 저장한 native PNG를 직접 검토한다.
별도 제품 준비 시나리오에서는 실제 선택·브리핑·돌아가기를 실행하되 초안을 저장하지 않아 기존 준비를 보존한다.
검증 후 원래 글꼴·야간 설정과 앱 데이터·권한을 확인하고 이번에 시작한 AVD만 종료한다.
자동 검사·실기·최신 PR 검토의 실제 결과는 해당 [작업 계획](../plans/active/2026-10-28-shared-practice-components.md)에 기록한다.
