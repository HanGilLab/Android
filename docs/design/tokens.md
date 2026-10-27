# 한길졸업 디자인 토큰

기준은 민트·크림과 둥근 길잡이 브랜드다. 주차·UX 허용 조건과 데이터 출처의 의미를 색으로 바꾸지 않는다.
모든 화면은 `HanGilTheme`의 색·Typography·Shapes와 아래 공통 치수를 사용한다.

## 간격·치수·모서리

| API | 값 | 용도 |
|---|---|---|
| `HanGilSpacing.xs / sm / md / lg / xl` | 8 / 12 / 16 / 20 / 24dp | 작은 간격·로고 조합·선택 목록·열 내부·페이지 요소 간격 |
| `panelInset / pageInset / badgeHorizontal` | 28 / 32 / 14dp | 카드·페이지·배지 여백 |
| `HanGilDimensions.primaryActionMinHeight` | 76dp | 준비·시작·종료의 주요 행동 |
| `secondaryActionMinHeight / compactActionMinHeight` | 72 / 64dp | 지도·재시도 / 뒤로·개발용 접기 |
| `choiceMinHeight` | 120dp | 선택 카드. 내용이 길면 더 커짐 |
| `pageMaxWidth / wideBreakpoint` | 1440 / 900dp | 페이지 최대 너비·2열 분기 |
| `diagnosticsMaxWidth / brandMarkSize / mapPreviewHeight` | 720 / 88 / 420dp | 진단 패널·브랜드 마크·지도 프레임 |
| `choiceBorderWidth / choiceSelectedBorderWidth` | 1 / 2dp | 선택 테두리 |
| `HanGilShapes` | 4 / 8 / 12 / 16 / 28dp | 기존 Material 모서리 수치를 명시 |

텍스트·선택·버튼의 순서와 저장 동작은 이 토큰 이전으로 바뀌지 않는다.
지도 높이는 앱 프레임 치수이며 실제 코스 길이나 위치 정확도를 뜻하지 않는다. 벡터 내부의 viewport/path 좌표는 그림 원본의 기하 정보다.

## 색과 상태

`HanGilColorSchemes`는 라이트·다크의 48개 Material 역할을 명시한다.
라이트의 주요 행동·페이지·일반 글자색을 유지하고, 기존 기본 진단 카드 배경과 보조 글자색은 녹색 역할로 통일했다.
다크의 보라색 기본 컨테이너·대응 글자색을 민트/초록으로 지정했으며 tertiary에는 따뜻한 크림 계열을 쓴다.

| 역할 | Light 배경 / 글자 | Dark 배경 / 글자 |
|---|---|---|
| 주요 행동 | `126B59 / FFFFFF` | `9FD3BC / 073D31` |
| 선택 컨테이너 | `D5EFE5 / 073D31` | `174D3E / D5EFE5` |
| 배지 | `E3EDE6 / 29473A` | `2E4A3C / E3EDE6` |
| 페이지·표면 | `F7FAF6 / 172E28` | `101E19 / E3EEE7` |
| 일반 패널 | `EDF3ED / 172E28` | `15271F / E3EEE7` |
| 진단 카드 | `DCE6DD / 172E28` | `2D4237 / E3EEE7` |
| 보조 문구 | `onSurfaceVariant: 4B6157` | `onSurfaceVariant: BDCEC3` |
| 오류 | `B3261E / FFFFFF` | `F2B8B5 / 601410` |

Surface의 `contentColorFor`는 색값으로 역할을 결정한다. 서로 다른 역할의 같은 배경색을 재사용할 때 전경 의미가 달라지지 않도록 한다.
진단 카드와 surfaceVariant의 색을 분리하고, 같은 배경의 고정 배지 역할은 전경을 일치시켜 회귀 검사로 확인한다.
선택은 기존 ●/○·테두리·selected semantics를 유지한다. 오류·잠금·미지원은 문구로 구분하며 정상·0 값으로 채우지 않는다.

색 대비 검사는 실제 사용하는 텍스트 쌍의 sRGB 대비 4.5 이상, 선택 마커의 패널 대비 3 이상을 확인한다.
이는 실제 기기의 눈부심이나 전체 접근성 적합성 인증이 아니다. 비활성 상태를 일반 활성 글자 대비 합격으로 기록하지 않는다.

## 글꼴·검증 범위

`HanGilTypography`는 시스템 sans-serif와 Material3 1.4.0의 기존 15개 크기·행간·굵기·자간을 명시한다.
플랫폼 font padding·line-height style은 기존 값을 보존하고, 브랜드 워드마크의 Bold는 별도로 유지한다. 외부 글꼴 파일은 배포하지 않는다.

실제 Pleos 검증은 라이트/다크·기본/확대 글꼴의 홈·목표·선택·브리핑·지도 앱 프레임과 잠금 전환을 대상으로 한다.
Compose 조건과 native 활성 패키지 확인을 통과해도 실제 Pleos 중앙 창이 가려질 수 있다. 캡처를 시각 검토하고 중앙 창의 배치·가시성·실제 그리기를 함께 확인한다.
단독 Activity 실행으로 만든 전체 배경 창과 정상 앱 목록 실행을 구분한다. 시험 단계의 같은 이름 캡처가 덮어써졌다면 최신 파일을 다시 확인하고, 부적격 캡처를 정상 근거로 재사용하지 않는다.
지도 자체는 기존 Liberty 스타일이며 앱 다크 테마로 타일이 다크로 바뀐다고 표현하지 않는다. 로딩/실패/ready callback은 각각 실제 관찰 상태로 기록한다.
실제 네트워크·저장소 오류를 새로 모두 주입한 것으로 주장하지 않으며, 상태별 표현은 기존 동작 검사와 새 색 대비 검사로 범위를 구분한다.
장면 일러스트·새 상태 컴포넌트·전체 화면 재설계·최종 사용성 점검은 별도 체크 항목이다.
