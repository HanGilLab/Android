# 한길졸업 디자인 토큰

기준은 민트와 크림 색, 둥근 길잡이 브랜드다. 주차와 UX 허용 조건과 데이터 출처의 의미를 색으로 바꾸지 않는다.
`HanGilTheme`의 색, Typography, Shapes를 공유하며 기본 `Practice*`와 주차 화면용 `Parked*`의 배치 치수는 구분한다.
토스는 구성과 정보 위계를 참고하며 색상은 한길의 민트와 크림을 유지한다. 글자나 간격을 줄여 설명을 밀어 넣지 않는다.

## 간격, 치수, 모서리

| API | 값 | 용도 |
|---|---|---|
| `HanGilSpacing.xs / sm / md / lg / xl` | 8 / 12 / 16 / 20 / 24dp | 작은 간격, 로고 조합, 선택 목록, 열 내부, 페이지 요소 간격 |
| `panelInset / pageInset / badgeHorizontal` | 28 / 32 / 14dp | 카드, 페이지, 배지 여백 |
| `HanGilDimensions.primaryActionMinHeight` | 76dp | 준비, 시작과 종료의 주요 행동 |
| `secondaryActionMinHeight / compactActionMinHeight` | 72 / 64dp | 지도, 재시도 / 뒤로, 개발용 접기 |
| `actionMinWidth / loadingIndicatorSize` | 64 / 40dp | 공통 버튼 최소 너비 / 로딩 표시 |
| `choiceMinHeight` | 120dp | 선택 카드. 내용이 길면 더 커짐 |
| `pageMaxWidth / wideBreakpoint` | 1440 / 900dp | 페이지 최대 너비, 2열 분기 |
| `diagnosticsMaxWidth / brandMarkSize / mapPreviewHeight` | 720 / 88 / 420dp | 진단 패널, 브랜드 마크, 지도 프레임 |
| `choiceBorderWidth / choiceSelectedBorderWidth` | 1 / 2dp | 선택 테두리 |
| `HanGilShapes` | 8 / 12 / 18 / 24 / 32dp | extraSmall / small / medium / large / extraLarge |

위 표의 페이지, 패널 기본값은 지도와 카탈로그 등 `Practice*`를 사용하는 화면에 적용된다.
홈과 코스 준비는 다음 주차 전용 치수를 사용한다.

| 적용 위치 | 값 | 동작과 범위 |
|---|---|---|
| `ParkedPage` | 최대1600dp, 바깥48dp, 요소 간32dp | 시스템 inset 안에서 가운데 정렬하고 세로 스크롤 |
| `ParkedPanel` | 내부32dp, 강조36dp | 카드 내부 요소 간20dp, 모서리24dp |
| `ParkedChoice` | 내부32dp, 제목/설명 간16dp | 설명 표시 시 최소120dp, 숨김 시 최소72dp. 내용에 따라 커짐 |
| `ParkedButton` | 가로32dp, 세로20dp 패딩 | 주요/보조 최소높이76/72dp, 모서리18dp |
| `ParkedDisclosure` | 최소높이64dp, 패딩16dp | 제목과 펼치기/접기24sp. 기본 접힘 |
| 홈 대시보드 | 열과 카드 간32dp | 가용폭이 `1000dp × fontScale` 이상이면 두 열 |
| 준비 선택지 | 행과 열 간28dp | 같은 폭 조건에서 두 열, 그 외 한 열 |
| 홈/준비 브랜드 마크 | 56dp | `HanGilBrand`의 일반 기본값88dp와 구분 |

현재 상태와 주요 행동, 선택지를 먼저 보여 주고 긴 부가 설명은 필요할 때 펼친다.
정보 순서는 바뀌었지만 저장 콜백, 주차와 UX 제한의 동시 확인 조건은 유지한다.
지도 높이는 앱 프레임 치수이며 실제 코스 길이나 위치 정확도를 뜻하지 않는다. 벡터 내부의 viewport/path 좌표는 그림 원본의 기하 정보다.

## 색과 상태

`HanGilColorSchemes`는 라이트와 다크의 48개 Material 역할을 명시한다.
주요 행동과 선택에는 민트를, 주차 배지에는 크림을 사용한다.
페이지와 카드는 중립 표면색으로 구분하고 다크에서도 같은 정보 위계를 유지한다.

| 역할 | Light 배경 / 글자 | Dark 배경 / 글자 |
|---|---|---|
| 주요 행동 | `126B59 / FFFFFF` | `9FD3BC / 073D31` |
| 선택 컨테이너 | `D5EFE5 / 073D31` | `174D3E / D5EFE5` |
| 일반 `PracticeBadge` | `EEF0F3 / 333D4B` | `303740 / E5E8EB` |
| 크림 `ParkedBadge` | `FFF0D6 / 3E2D11` | `5A4320 / FFF0D6` |
| 페이지, 표면 | `F2F4F6 / 191F28` | `101215 / F9FAFB` |
| 기본 `PracticePanel` | `F9FAFB / 191F28` | `171B21 / F9FAFB` |
| `ParkedPanel` | `FFFFFF / 191F28` | `1B1F26 / F9FAFB` |
| 진단 카드 | `DDE1E6 / 191F28` | `2D343E / F9FAFB` |
| 보조 문구 | `onSurfaceVariant: 4E5968` | `onSurfaceVariant: B0B8C1` |
| 오류 | `B3261E / FFFFFF` | `FFB5B0 / 601410` |

Surface의 `contentColorFor`는 색값으로 역할을 결정한다. 서로 다른 역할의 같은 배경색을 재사용할 때 전경 의미가 달라지지 않도록 한다.
진단 카드와 surfaceVariant의 색을 분리하고, 같은 배경의 고정 배지 역할은 전경을 일치시켜 회귀 검사로 확인한다.
선택 컨테이너 표는 `primaryContainer / onPrimaryContainer` 토큰 쌍이다. `PracticeChoice`는 이 전경과 ●/○ 장식을 사용한다.
`ParkedChoice`는 같은 선택 배경에 `onSurface` 전경을 명시하고 실제 RadioButton과 선택 테두리, selected semantics를 사용한다.
강조 `ParkedBadge`는 선택 컨테이너의 토큰 쌍을 사용한다. 오류, 잠금, 미지원은 문구로 구분하며 정상, 0 값으로 채우지 않는다.

색 대비 검사는 실제 사용하는 텍스트 쌍의 sRGB 대비 4.5 이상, 선택 마커의 패널 대비 3 이상을 확인한다.
이는 실제 기기의 눈부심이나 전체 접근성 적합성 인증이 아니다. 비활성 상태를 일반 활성 글자 대비 합격으로 기록하지 않는다.

## 글꼴과 검증 범위

`HanGilTypography`는 시스템 sans-serif에 15개 역할의 크기, 행간, 굵기, 자간을 명시한다.
기존 Material 기본 크기를 그대로 쓰지 않으며 핵심 본문24sp와 선택 제목28sp를 사용한다.

| 역할 | 크기/행간 (sp) | 굵기 |
|---|---|---|
| display Large / Medium / Small | 57/64, 45/52, 36/44 | Normal |
| headline Large / Medium / Small | 36/46, 32/42, 28/38 | Normal, 호출 화면에서 강조 가능 |
| title Large / Medium / Small | 28/38, 24/34, 22/32 | SemiBold / Medium / Medium |
| body Large / Medium / Small | 24/34, 22/32, 18/26 | Normal |
| label Large / Medium / Small | 20/28, 18/26, 18/26 | SemiBold / Medium / Medium |

자간은 displayLarge의 -0.2sp 외에는0sp다. 플랫폼 font padding과 line-height style은 기본값을 보존하고 브랜드 워드마크의 Bold는 별도로 유지한다.
외부 글꼴 파일은 배포하지 않는다. 크기/행간과 색 역할의 원본은 [Typography](../../core/designsystem/src/main/kotlin/io/github/hangillab/hangil/core/designsystem/HanGilTypography.kt),
[색](../../core/designsystem/src/main/kotlin/io/github/hangillab/hangil/core/designsystem/HanGilColorSchemes.kt),
[주차 컴포넌트](../../core/designsystem/src/main/kotlin/io/github/hangillab/hangil/core/designsystem/ParkedComponents.kt)다.

실제 Pleos 검증은 라이트/다크, 기본/확대 글꼴의 홈, 목표, 선택, 브리핑, 지도 앱 프레임과 잠금 전환을 대상으로 한다.
Compose 조건과 native 활성 패키지 확인을 통과해도 실제 Pleos 중앙 창이 가려질 수 있다. 캡처를 시각 검토하고 중앙 창의 배치, 가시성, 실제 그리기를 함께 확인한다.
단독 Activity 실행으로 만든 전체 배경 창과 정상 앱 목록 실행을 구분한다. 시험 단계의 같은 이름 캡처가 덮어써졌다면 최신 파일을 다시 확인하고, 부적격 캡처를 정상 근거로 재사용하지 않는다.
지도 자체는 기존 Liberty 스타일이며 앱 다크 테마로 타일이 다크로 바뀐다고 표현하지 않는다. 로딩/실패/ready callback은 각각 실제 관찰 상태로 기록한다.
실제 네트워크, 저장소 오류를 새로 모두 주입한 것으로 주장하지 않으며, 상태별 표현은 기존 동작 검사와 새 색 대비 검사로 범위를 구분한다.
장면 일러스트는 [일러스트 안내](illustrations.md), 버튼, 상태 패널은 [공통 컴포넌트](components.md)에 기록한다.
전체 화면 재설계, 최종 사용성 점검은 별도 체크 항목이다.

PR #32의 실제 홈/준비 기본/1.6배/다크와 주차 잠금 전환 검증은 [가독성 개선 완료 계획](../plans/completed/2026-10-29-parked-ui-polish.md)에 기록한다.
이 문서 정정 자체는 새 화면이나 실기 검증 결과가 아니다.
