# 고정 시연 지점 선택

## 제품 범위와 선택 수명

준비한 현재 코스의 고정 시연 지점 목록을 선택하면 같은 point_id에 native 테두리를 적용하고 같은 지점의 설명을 보여 준다. 최초 진입에는 선택이 없다. native style filter를 적용한 응답 뒤에만 선택 표시와 설명을 확정한다. 이 응답은 GPU나 특정 프레임의 완료 측정이 아니다.

각 목록 노드는 attempt, renderer token, preview 전체와 STARTED 소유자별 key를 가지며, callback은 해당 값을 고정한다. 현재 reader/query와 READY, 최신 실제 P와 UX 허용, 살아 있는 host를 선택/적용/응답마다 확인한다. native 입력에서 거부를 처음 읽어도 해당 선택을 폐기한다. 다른 지점을 빠르게 선택하면 새 nonce가 이전 적용과 응답을 거부한다. 로딩, 오류, 재시도, 준비 교체, 화면 이탈, STOP과 재생성에서 선택을 저장하거나 복원하지 않는다.

강조 레이어는 기존 source와 좌표/순서/출처를 그대로 사용하고 filter만 바꾼다. 카메라를 움직이지 않고 지도가 일부 또는 전부 화면 밖에 있어도 포커스를 별도로 요구하지 않는다. 실제 완전 비노출/부분 노출 여부는 수동 실행에서 관찰한 조건만 근거로 쓴다.

지점은 고정된 공개 OSM 장소 경계 중심이다. 도로 구간, 출입구, 현재 위치나 내비 목적지가 아니며 실제 도로/경로 검토 완료를 뜻하지 않는다. native marker 탭, 도로 구간 선택과 MAP-03 전체 완료는 이번 범위에 없다. 위치/내비 API, DB 스키마, 새 권한과 카메라는 변경하지 않았다.

## 기존 지도 진입 비용의 동일 절차 비교

느리다는 병목을 관찰해 시작한 최적화가 아니라 기존 목록의 선택 누락을 연결한 기능이다. 비교할 과거 선택 동작이 없으므로 새 선택의 처리속도 개선율을 만들지 않는다. 추가 UI/레이어가 기존 지도 진입 비용에 미치는 관찰값만 아래에 남긴다.

- 기준 제품: `009a5571bd662269d53b2ba35c7fe953ed42de39`
- 비교 제품: `4a34b6b351900b310bb655e315db39d85d59b048` (테스트 전용 변경은 별도 기록)
- 고정 harness: `75b37af92e15d6f2a54b0a49ade2ec49adb32f22`의 MapOpeningMeasureTest. 저장 namespace만 새로 나누고 전후 동일 파일을 사용했다.
- Android14/API34 Pleos, debug, Temurin17.0.21, font1.0/라이트, 같은 실제 준비/카탈로그/전체 자료와 실제 SDK P+UX. 로컬/설치 제품 및 계측 APK SHA를 대조했다.
- 단계별 새 process/Activity, 5회 워밍업 후30회 웜 반복, home_map 클릭 직전부터 실제 MapView/공식 style/source/3지점/fit camera와 Compose idle 재확인까지 elapsedRealtimeNanos를 ms로 변환했다. endpoint30초이며 동기 Compose blocking을 선점 중단하는 보장은 아니다.
- 클릭 dispatch, 지도 pre-scroll, 공개 타일/렌더/native 조회와 Compose 동기화가 포함된다. 홈 pre-scroll, 원본/SDK 사전검사, JSON, 닫기와 앱/Activity 시작은 제외된다. 콜드 앱, 순수 GPU/프레임/SDK 또는 운전자 속도가 아니다.
- 기준 다음 비교 순서이며 타일/네트워크/OS/JIT 캐시는 지우지 않았다. 환경 readback과 원래 자료를 고정했으나 순서/발열/캐시/네트워크의 인과 편향을 제거한 실험은 아니다.

| 웜 표본 지표(ms) | 기준 | 비교 |
|---|---:|---:|
| 실제 성공 표본 | 30 | 30 |
| 중앙값 | 1828.1850635 | 1822.930397 |
| Q1 / Q3 | 1741.122167 / 1871.021584 | 1756.650209 / 1886.699293 |
| IQR | 129.899417 | 130.049084 |
| min / max | 1475.535126 / 2279.401668 | 1636.833334 / 2543.343501 |
| 실패 / 누락 / 제외 / 미실행 | 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 |
| 0 duration | 0 | 0 |

Q1/Q3는 정렬한30개 중 아래15개와 위15개의 중앙값이며 IQR은 Q3−Q1이다. 보간 percentile을 사용하지 않았다.

중앙값 변화(비교−기준)는 -5.2546665ms, -0.287425%다. 이 차이는 두 IQR 약130ms보다 작고 분포가 겹친다. 순차 실행 한계도 있으므로 성능 향상이나 동등성/무회귀를 통계적으로 확정하지 않는다. 관찰한 조건의 두 완료 실행이며 재현 간 편차, 다른 입력/부하/기기/release/콜드 실행은 미측정이다. 테스트 수나 저장 행 수는 이 수치를 대신하지 않는다.

[전후 timing 원값](measurements/map-point-selection-entry-ui-times.csv)은 식별자를 제거한 실제70행(각 워밍업5+웜30)이다. 전체 입력, 원문 캡처/실행 기록과 무결성 hash는 공개하지 않는 로컬 기록으로 보존했다.

## 재현과 검증 구분

외부 실행자는 원래 활성 세션 없음과 SQLite 전체 값/자료형/순서/sequence, 권한/runtime/글자/테마 등 설정을 먼저 보존한다. 제품 변경 전 고정 harness로 baseline을 실행하고 완료 원값과 설치 APK를 확인한 뒤 제품을 바꾼다. 같은 원본과 환경에서 comparison을 실행해 baseline hash/contract/catalog/source/environment 일치를 확인한다. 완료 원값을 덮거나 유리한 표본만 새로 고르지 않는다.

별도 실제 선택 시나리오는 `MapPointSelectionFlowTest`의 `manualMapPointSelection=true`, `expectedFontScale=1.0` 또는 `1.3`, `expectedDark=false` 또는 `true`를 명시한다. 원래 준비/기록을 바꾸지 않고 실제 SDK P에서 현재 세 지점, native filter/설명/카메라, 화면 소유권과 외부 DDS D/P를 확인한다. 정상 실행/실패/미실행과 합성 JVM 경계/실제 SDK 결과는 [작업 계획](../plans/active/2026-10-31-map-preview-point-selection.md), PR과 CI에서 구분한다. 독립 UX/SDK 오류 주입과 다른 코스의 실제 UI 실행은 별도 범위다.
