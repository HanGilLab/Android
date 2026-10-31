# 고정 시연 지점 선택

## 제품 범위와 선택 수명

준비한 현재 코스의 고정 시연 지점 목록을 선택하면 같은 point_id에 native 테두리를 적용하고 같은 지점의 설명을 보여 준다. 최초 진입에는 선택이 없다. native style filter를 적용한 응답 뒤에만 선택 표시와 설명을 확정한다. 이 응답은 GPU나 특정 프레임의 완료 측정이 아니다.

각 목록 노드는 attempt, renderer token, preview 전체와 STARTED 소유자별 key를 가지며, callback은 해당 값을 고정한다. Compose delegated State를 callback 안에서 다시 읽지 않고 구성 시 plain 값으로 읽어 캡처한다. 같은 preview의 과제만 변경된 경우도 이전 tuple을 새 렌더로 바꾸지 않는다. 현재 reader/query와 READY, 최신 실제 P와 UX 허용, 살아 있는 host를 선택/적용/응답마다 확인한다. native 입력에서 거부를 처음 읽어도 해당 선택을 폐기한다. 다른 지점을 빠르게 선택하면 새 nonce가 이전 적용과 응답을 거부한다. 로딩, 오류, 재시도, 준비 교체, 화면 이탈, STOP과 재생성에서 선택을 저장하거나 복원하지 않는다.

강조 레이어는 기존 source와 좌표/순서/출처를 그대로 사용하고 filter만 바꾼다. 카메라를 움직이지 않으며 native 포커스를 입력 허용의 별도 조건으로 요구하지 않는다. 기본 라이트와 확대 다크 실기에서 지도 일부가 가려진 조건과 native 비포커스에서 선택/강조/설명 일치를 확인했다. 두 환경 모두 완전 비노출은 관찰하지 못했으므로 해당 실기 범위는 미실행이다.

지점은 고정된 공개 OSM 장소 경계 중심이다. 도로 구간, 출입구, 현재 위치나 내비 목적지가 아니며 실제 도로/경로 검토 완료를 뜻하지 않는다. native marker 탭, 도로 구간 선택과 MAP-03 전체 완료는 이번 범위에 없다. 위치/내비 API, DB 스키마, 새 권한과 카메라는 변경하지 않았다.

## 기존 지도 진입 비용의 동일 절차 비교

느리다는 병목을 관찰해 시작한 최적화가 아니라 기존 목록의 선택 누락을 연결한 기능이다. 비교할 과거 선택 동작이 없으므로 새 선택의 처리속도 개선율을 만들지 않는다. 추가 UI/레이어가 기존 지도 진입 비용에 미치는 관찰값만 아래에 남긴다.

- 기준 제품: `009a5571bd662269d53b2ba35c7fe953ed42de39`
- 초기 비교 제품: `4a34b6b351900b310bb655e315db39d85d59b048`. 콜백 캡처 결함 수정 전의 완료 관찰값을 버리지 않고 보존한다.
- 최종 비교 제품: `6b001b9b33504de1ba35a7d5e299adaf139dcd5f`. plain tuple 캡처를 수정한 실제 최종 앱이며 새 선택 하네스의 동기화 변경은 제품 코드 변경과 구분한다.
- 고정 harness: `75b37af92e15d6f2a54b0a49ade2ec49adb32f22`의 MapOpeningMeasureTest. 저장 namespace만 새로 나누고 세 단계에서 동일 파일을 사용했다. 완료된 초기 comparison cache는 새 소유 archive 이름으로 byte 그대로 옮겼으며 baseline과 초기/최종 원값을 덮지 않았다.
- Android14/API34 Pleos, debug, Temurin17.0.21, font1.0/라이트, 같은 실제 준비/카탈로그/전체 자료와 실제 SDK P+UX. 각 로컬/설치 제품 및 계측 APK SHA와 baseline hash/contract/catalog/source/environment를 대조했다.
- 단계별 새 process/Activity, 5회 워밍업 후30회 웜 반복, home_map 클릭 직전부터 실제 MapView/공식 style/source/3지점/fit camera와 Compose idle 재확인까지 elapsedRealtimeNanos를 ms로 변환했다. endpoint30초이며 동기 Compose blocking을 선점 중단하는 보장은 아니다.
- 클릭 dispatch, 지도 pre-scroll, 공개 타일/렌더/native 조회와 Compose 동기화가 포함된다. 홈 pre-scroll, 원본/SDK 사전검사, JSON, 닫기와 앱/Activity 시작은 제외된다. 콜드 앱, 순수 GPU/프레임/SDK 또는 운전자 속도가 아니다.
- 기준→초기 비교→최종 비교 순서다. 최종 비교는 실제 선택 검증과 원래 설정 복원 뒤 시행했고 타일/네트워크/OS/JIT 캐시는 지우지 않았다. 환경 readback과 원래 자료를 고정했으나 순서/발열/캐시/네트워크의 인과 편향을 제거한 실험은 아니다.

| 웜 표본 지표(ms) | 기준 | 초기 비교 | 최종 비교 |
|---|---:|---:|---:|
| 실제 성공 표본 | 30 | 30 | 30 |
| 중앙값 | 1828.1850635 | 1822.9303970 | 1857.8786680 |
| Q1 / Q3 | 1741.122167 / 1871.021584 | 1756.650209 / 1886.699293 | 1778.456501 / 1988.665542 |
| IQR | 129.899417 | 130.049084 | 210.209041 |
| min / max | 1475.535126 / 2279.401668 | 1636.833334 / 2543.343501 | 1561.821918 / 2737.530543 |
| 실패 / 누락 / 제외 / 미실행 | 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 |
| 0 duration | 0 | 0 | 0 |

Q1/Q3는 정렬한30개 중 아래15개와 위15개의 중앙값이며 IQR은 Q3−Q1이다. 보간 percentile을 사용하지 않았다.

초기 비교의 중앙값 변화는−5.2546665ms(−0.287425%)였으나 최종 제품의 값으로 대체해 쓰지 않는다. 최종 중앙값 변화(최종−기준)는 **+29.6936045ms, +1.624212%**이며 관찰상 더 느렸다. 기준 IQR129.899417ms/최종 IQR210.209041ms보다 작은 차이이고 분포가 겹친다. 순차 실행 한계도 있으므로 성능 향상, 무회귀/동등성 또는 인과 성능 저하를 통계적으로 확정하지 않는다. 관찰한 조건의 세 완료 실행이며 재현 간 편차, 다른 입력/부하/기기/release/콜드 실행은 미측정이다. 테스트 수나 저장 행 수는 이 수치를 대신하지 않는다.

[모든 timing 원값](measurements/map-point-selection-entry-ui-times.csv)은 식별자를 제거한 실제105행(세 단계 각 워밍업5+웜30)이다. 기존70행의 시각 값/상태를 그대로 보존했고 초기 표본을 유리하게 제외하지 않았다. 전체 입력, 원문 캡처/실행 기록과 무결성 hash는 공개하지 않는 로컬 기록으로 보존했다.

## 재현과 검증 구분

외부 실행자는 원래 활성 세션 없음과 SQLite 전체 값/자료형/순서/sequence, 권한/runtime/글자/테마 등 설정을 먼저 보존한다. 제품 변경 전 고정 harness로 baseline을 실행하고 완료 원값과 설치 APK를 확인한 뒤 제품을 바꾼다. 같은 원본과 환경에서 comparison을 실행해 baseline hash/contract/catalog/source/environment 일치를 확인한다. 완료 원값을 덮거나 유리한 표본만 새로 고르지 않는다.

별도 실제 선택 시나리오는 `MapPointSelectionFlowTest`의 `manualMapPointSelection=true`, `expectedFontScale=1.0` 또는 `1.3`, `expectedDark=false` 또는 `true`를 명시한다. 원래 준비/기록을 바꾸지 않고 실제 SDK P에서 현재 세 지점, native filter/설명/카메라, 화면 소유권과 외부 DDS D/P를 확인한다. 정상 실행/실패/미실행과 합성 JVM 경계/실제 SDK 결과는 [작업 계획](../plans/completed/2026-10-31-map-preview-point-selection.md), PR과 CI에서 구분한다. 독립 UX/SDK 오류 주입과 다른 코스의 실제 UI 실행은 별도 범위다.


## 이번 실제 검증과 실패 구분

최종 제품은 실제 Main/SDK와 기존 준비를 사용하는 기본1.0/라이트 및 확대1.3/다크 시나리오 각1개를 건너뜀 없이 통과했다. 각 현재3지점의 선택/native 현재 filter/같은 설명과 카메라5항목 불변, 재생성/닫기와 재진입/STOP 뒤 미선택, STOP 전 semantics action 거부와 fresh 선택, 실제 DDS D 잠금/P 복귀 뒤 미선택을 확인했다. 원래 준비 preview의 Main/SDK 실기는 완료했고 실제 다른 코스로 원래 준비를 바꾸는 UI 검사는 미실행이다. 동일 preview의 과제 교체는 별도 제어 native에서 완료했다. 두 번째 production catalog preview도 합성 준비/P/UX와 실제 Route/MapLibre를 사용하는 별도 native 검사에서 세 지점의 현재 source/filter/설명/ACK와 카메라 불변을 확인했다. 실제 차량 SDK로 두 코스를 모두 검사했다는 뜻은 아니다. 독립 UX/권한 거부/SDK 오류 주입은 실행하지 않았다.

별도 제어 native 재현은 같은 Activity/STARTED/코스/preview에서 synthetic 과제만 바꾸고 새 View/attempt/token을 확인한 뒤 옛 semantics action이 새 선택과 설명을 만드는 RED를 실제 관찰했다. plain tuple 캡처 수정 뒤 같은 절차에서 옛 action은 거부되고 새 action은 native ACK/같은 제목을 만드는 GREEN을 확인했다. 원래 Room/실제 차량 SDK와 사람 입력을 쓰는 검사가 아니다.

최초 기본 라이트2회는 첫 선택 뒤 native readback30초 timeout으로 실패했다. 원래 증거를 보존했고 성공 표본에 넣지 않았다. 하네스의 current native layer 조회와 Compose idle/실제30초 승인 경계를 보강한 뒤 위 최종 시나리오가 통과했다. 두 수정의 단일요인 분리 실험은 미실행이므로 한 원인이나 앱/SDK 성능 결함으로 확정하지 않는다. 설치된 MapLibre13.5.0의 getLayer는 Java layer cache를 우선하고 getLayerAs는 NativeMap의 현재 layer를 직접 조회한다. 별도 실제 SDK style probe에서 setter와 bound3/cached2 조회는 즉시와500ms 뒤 모두 일치하고 원래filter를 복원했다. cache 불일치가 재현됐다고 쓰지 않는다. 이 probe는 생산 선택/차량 SDK/성능 성공을 대신하지 않는다.

테마 전환 직후 기본 내비가 전면인 실제 화면은 계측 시작 전에 확인하고 한길 Main을 다시 열어 실제 P/UX와 전면을 확인했다. 이를 앱 복원 실패나 native 선택 시나리오의 성공으로 계산하지 않는다. 두 최종 실기 뒤 모든 원래 SQLite 값/자료형/순서/sequence, 권한/runtime과 글꼴/야간/IME/proxy 설정의 정확한 보존을 외부 기록과 대조했다. 원문, 실제 위치와 개인 환경은 공개하지 않았다.


제어 native 검사는 `MapInputLifecycleTest`의 `manualMapInputBoundary=true`를 명시해 개별 메서드로 실행했다. `stalePointChoiceCannotBorrowAnotherTaskOfTheSamePreview`는 동일 preview의 과제 교체 RED/GREEN, `nativeSelectionLayerFilterSetterRoundTripsOnTheBoundStyleSdkProbe`는 SDK setter/getter/복원, `secondCatalogPreviewChoicesMatchNativeCurrentSource`는 다른 현재 catalog preview의 세 선택을 확인한다. 기존 이 클래스의 나머지 시나리오 전체를 이번에 재실행한 것으로 집계하지 않는다. 실제 Main/SDK 두 환경과 분리해 검증한다.
