# 네이티브 지도 입력 경계와 지도 진입 계측

## 문제와 구현

화면의 P/Ready와 최신 차량 D 값 사이에 MapView가 남으면 렌더 완료 판정과 무관하게 SDK가 직접 입력을 처리했다. 실제 Route/VM/MapLibre에 명시적인 합성 상태를 연결해 P drag의 실제 camera 이동을 먼저 확인하고 복원한 뒤, 옛 P/동일 View에서 live D의 같은 drag가 이동하는 결함을 재현했다. 실제 DDS의 지연 순서를 재현한 증거와 구분한다.

`canInteractWithMap`은 현재 준비 reader/job/query, renderer attempt/token/READY와 최신 P+UX를 확인한다. `ParkedMapView`는 자식 dispatch 전에 이를 읽고 STARTED/허용 입력 세대, downTime/eventTime/장치/출처를 확인한다. SDK 파괴 전에 Android CANCEL/pending input과 공개 camera cancel API로 정리한다. 자식 접근성, Compass와 출처 dialog도 캡처한 세대에 묶으며 각 출처 창의 manager를 별도로 만든다.

직접 touch drag/pinch/rotate/tilt와 공개 즉시 pointer-wheel 경로를 지원한다. 관성 velocity animation, double-tap/quick-zoom, camera/confirm key와 trackball 카메라 입력은 이번 정책에서 제한했다. TAB/BACK/미디어/시스템 키와 접근성 focus는 보존한다. 새 허용 세대를 처음 확인한 시각보다 먼저 시작된 gesture는 보수적으로 거부할 수 있다. 같은 millisecond의 서로 다른 물리 입력을 완전히 식별한다는 보장은 하지 않는다. View/SDK 내부 private API와 reflection은 사용하지 않는다.

첫 View 할당에서 live 거부로 null이 반환되고 D/P가 UI 전달 전에 합쳐지면 spinner가 남을 수 있어 현재 소유 attempt만 FAILED로 전환한다. P 복귀 후 명시적 재시도가 새 attempt를 만들며, FAILED attempt를 단순 재할당 호출로 되살리지 않는다.

## 동일한 지도 진입 계측

- 기준 제품 d0385bc357831bfef0d2fffb2e428faf5cc0ad65, 검사/계측 고정9a8d8d0c706ce16008f975286b3fd6986b080358. 기준 APK는9a8d8d0에서 제품 변경 없이 빌드했다.
- 비교 제품81d06645d055d2e7d75e598e79c3142dcca666c5. `MapOpeningMeasureTest`와 원래 입력 RED 파일은 기준과 byte 동일하다. 추가 native 검사와 제품 코드가 바뀌므로 전체 검사 APK hash는 서로 다르며, 설치 APK와 해당 빌드 산출물의 hash는 각각 대조했다.
- 조건: 전용 Android14/API34 Pleos, JDK17/debug, font1.0/라이트, 실제 Vehicle SDK P와 UX 해제, 같은 원래 준비/고정 preview/카탈로그. 부하는 준비1/종료9/관찰15/메모0이며 원문 값은 공개하지 않는다. 위치/내비 권한을 추가하거나 데이터를 지우지 않았다.
- 각 run은 새 MainActivity 하나에서 Home→지도→close→Home를35회 반복한다. 처음5회는 워밍업이며 다음30회는 웜 화면 진입 표본이다. 매 표본 새 Activity나 콜드 앱 시작을 측정한 것이 아니다. SDK 스타일/타일 캐시는 지우지 않았다.
- 지표(ms): home_map 클릭 직전부터 동일한 map_view 스크롤, 실제 가용 영역의 전체 지도, 완전한 SDK style/고정3점/GeoJSON/layers/projection/fit-camera를 확인하고 Compose idle 뒤 재조회할 때까지. 입력 전달, 검사/스크롤, 네트워크와 native renderer 비용이 포함된다. 순수 GPU/SDK 시간이나 운전자 행동 속도가 아니다.
- 무거운 Gradle 빌드와 AVD를 순차 실행했다. source/catalog/환경/원본 비교와 JSON 저장, 닫기/복원은 표본 타이머 밖이다.30초 endpoint 실패, 실제 gate 거부, 입력/환경/원본 불일치와 예외는 실패로 보존하고 중단하도록 정했다.
- 완료 기준 파일은 덮어쓰지 않고 비교 전후 hash를 확인했다. 원문 JSON에는 비공개 원본과 환경이 있어 ignored 저장소/앱 cache에 보존한다. 공개한 [시각 원값 CSV](measurements/map-opening-ui-times.csv)는 단계/워밍업 여부/순서/시간/성공 상태만 추출했다. CSV 행의 같은 순서는 짝지은 표본을 의미하지 않는다.

| 지표(ms) | 기준 | 비교 |
|---|---:|---:|
| 본 표본 수 | 30 | 30 |
| 중앙값 | 2127.5183965 | 2000.331397 |
| Q1 | 2022.010292 | 1907.143917 |
| Q3 | 2212.044752 | 2107.241292 |
| IQR | 190.034460 | 200.097375 |
| 최소 | 1823.575501 | 1661.788917 |
| 최대 | 2983.734043 | 2668.382334 |

각각 워밍업5/본 표본30이 성공했고 실패/누락/미실행/제외/0시간 표본은0이다. 사분위수는 정렬한 각 절반의 중앙값이다. 관찰된 중앙값 차이는−127.1869995ms, 기준 대비−5.97818565%다. 사분위 범위와 전체 범위가 겹치며 순차 실행의 캐시/네트워크/발열/OS 편차를 제거하지 못했다. 이 차이를 변경의 성능 향상이나 확정된 처리 비용으로 결론 내리지 않는다. 이번 작업의 완료 대상은 입력 정확성 경계다.

## 재현과 근거 범위

1. 저장소 작업 소유권과 기존 AVD/프로세스를 확인하고 원본 SQLite의 모든 테이블/행/필드/자료형/순서/sequence, grants/runtime/font/night/IME/proxy를 비공개 백업한다. 기존 활성 연습이나 미완료 검사 소유 기록이 있으면 중복 시작하지 않는다.
2. JDK17로 각 해당 commit의 debug와 AndroidTest APK를 빌드하고 기존 debug 앱에 데이터 삭제 없이 갱신한다. 설치/빌드 APK hash와 실제 대상/환경을 대조한다.
3. 실제 브라우저 DDS ONLINE과 앱의 실제 P/UX 수신, 원래 준비, 실제 전면 앱을 확인한다. 기본/라이트에서 다음 명시 옵션으로 한 번씩 실행한다. 기준 원문이 완료되면 재실행으로 덮어쓰지 않는다.
4. `MapOpeningMeasureTest#measureMapOpeningAndPreserveOriginalData`에 `manualMapOpeningMeasure=true`, `measurementStage=baseline|comparison`, 위 전체 `declaredProductCommit`과 `declaredHarnessRevision`을 전달한다. 비교는 기준 JSON을 먼저 검증한다.
5. 원래 `MapInputBoundaryTest#nativePanCannotUseOldDisplayedPAfterSilentLiveD`와 추가 `MapInputLifecycleTest`에는 `manualMapInputBoundary=true`를 전달한다. 실제 Route/VM/MapLibre와 합성 차량/준비를 사용하는 제어 정확성 검증이며 실제 DDS 순서나 성능 증거로 대체하지 않는다. wheel은 공개 native dispatch, URL은 실제 dialog listener를 ActivityMonitor로 가로챈 실행 시도이며 실제 외부 브라우저와 하드웨어 mouse 전달은 확인 대상이 아니다.
6. 실제 SDK 준비 지도 교체/재생성/닫기와 재진입/P-D-P는 `PreparedCourseMapFlowTest#preparedCoursesReplaceNativePointsAndKeepDataAcrossLifecycleAndDrivingLock`의 `manualPreparedCourseMap=true`로 실행한다. 실제 DDS 표식에 맞춰 P/D/P를 조작하고 검사 소유 준비를 복원한다. 원래 source/전체 DB/설정을 최종 대조한다.

실제 오류/재시도는 `PreparedCourseMapFlowTest#injectedRemoteTileFailureRetriesPreparedPreviewWithoutDataChanges`에 별도 `manualPreparedMapNetwork=true`를 전달했다. 첫 잘못된 옵션의 assumption 건너뜀은 미실행으로 보존하고, 명시 재실행에서 실제 remote tile request/error/실패 UI/재시도 재렌더와 proxy 복원을 확인했다. 기본/확대 다크의 실제 준비 지도 흐름과 Main 지도 BACK도 성공했으며 마지막 전체 DB/설정 비교는 동일했다.

검증과 PR/CI의 최종 상태는 [작업 계획](../plans/active/2026-10-31-map-live-input-boundary.md)에 따로 기록한다. `scripts/summarize-case-evidence.py`의 지도 사례 보조 대상에 `MapInputEpochTest`를 연결했으며, 해당 JUnit 집계는 실제 native 실행이나 성능 측정을 대신하지 않는다.
