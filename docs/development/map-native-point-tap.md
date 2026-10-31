# 고정 시연 지점의 native 탭 선택

## 범위와 현재 상태

준비한 코스의 지도 위 번호를 짧게 누르면 기존 목록 선택과 같은 고정 시연 지점을 선택하도록 연결한다. 목록은 접근성 대체 경로로 유지한다. native 강조 filter가 적용됐다는 응답을 받은 뒤 같은 지점의 목록 표시와 설명을 확정하며, 이 응답은 GPU나 특정 프레임 완료 측정이 아니다.

지점은 고정된 공개 OSM 장소 경계 중심이다. 도로 구간, 출입구, 실제 위치와 내비 목적지가 아니다. 실제 도로 구간 선택, 경로/내비 연결과 MAP-03 전체 완료는 이번 범위에 포함하지 않는다. DB 스키마, 원래 준비/세션/관찰/메모, 위치 API와 권한은 변경하지 않는다. 선택 자체는 카메라를 움직이지 않는다.

이 문서는 구현된 처리 경계와 실행 절차를 설명한다. 제품 변경 전 실제 SDK 기준 계측은 완료했다. 초기 후보 제품의 전체 검증은 통과했지만 그 뒤 현재 native source 목록 대조를 추가했으므로 최종 제품의 전체 재검증은 아직 미완료다. 초기 제어 native 실행은 10개 중 8개 통과/2개 실패였으며, 같은 제품 APK에서 하네스의 엄격한 입력 준비와 무방출 P 복귀 절차를 보강해 재검증 중이다. 이를 최종 성공으로 기록하지 않는다. 변경 후 계측, 실제 SDK 기본/확대 다크 시나리오는 아직 미실행이며 최신 PR은 미생성이다.

## SDK 콜백 대신 DOWN의 의도를 고정하는 이유

고정한 MapLibre Android 13.5.0의 `onSingleTapConfirmed`는 탭 지점을 변환한 뒤 현재 등록된 `OnMapClickListener`에 `LatLng`만 전달한다. 앱의 DOWN 시각, 입력 세대와 당시 renderer를 콜백 인자로 복원할 수 없다. 이 지연 콜백에서 현재 P나 renderer만 읽으면 STOP/START 또는 D/P 뒤 새로운 소유권을 빌리는 설계가 될 수 있다. 따라서 앱은 지연 SDK click listener를 선택 진입점으로 사용하지 않고, canvas에서 승인한 짧은 단일 포인터 DOWN부터 UP까지 해당 의도를 고정한다. 이는 확인한 API 계약에 따른 설계 판단이며 SDK 자체의 결함이나 탭 지연 성능 개선을 주장하는 것은 아니다. [고정 버전의 탭 전달](https://github.com/maplibre/maplibre-native/blob/android-v13.5.0/platform/android/MapLibreAndroid/src/main/java/org/maplibre/android/maps/MapGestureDetector.java#L330-L340), [listener 전달 인자](https://github.com/maplibre/maplibre-native/blob/android-v13.5.0/platform/android/MapLibreAndroid/src/main/java/org/maplibre/android/maps/MapGestureDetector.java#L920-L925).

SDK의 일반 터치 처리는 그대로 전달한다. canvas 경로에서만 지점 의도를 추적하므로 compass와 attribution 자식 터치는 지점 선택으로 바꾸지 않는다. 빈 곳을 누르거나 여러 다른 지점이 겹치면 새 지점을 임의로 고르지 않는다. 직접 drag/pinch/rotate/tilt는 지도 제스처로 유지하며, 기존 정책대로 관성, double-tap, quick-zoom, 카메라 키와 trackball 카메라 조작은 지원하지 않는다.

[ParkedMapView](../../feature/map/src/main/kotlin/io/github/hangillab/hangil/feature/map/ParkedMapView.kt)는 현재 실제 P와 UX 허용, STARTED, attached View와 renderer의 입력 가능 여부를 SDK dispatch 전에 읽는다. [MapPointTapTracker](../../feature/map/src/main/kotlin/io/github/hangillab/hangil/feature/map/MapPointTapTracker.kt)는 다음 조건을 유지한다.

- DOWN에서 epoch, downTime, pointer ID, 시작 좌표와 당시 선택 callback을 캡처한다. 재구성에서 callback이 바뀌어도 이미 승인한 DOWN의 callback은 교체하지 않는다.
- 실제 경과가 `ViewConfiguration.getLongPressTimeout()` 미만인 단일 포인터 입력만 허용한다. 이것은 `getTapTimeout()` 기준과 다르며 일정한 100ms를 제품 경계로 사용하지 않는다.
- 현재 좌표뿐 아니라 MotionEvent의 과거 이동 표본도 touch slop과 대조한다. 한 번 slop 밖으로 나갔다가 되돌아와도 탭으로 복구하지 않는다. 다중 포인터, 비정상/미래 시각, CANCEL과 보이지 않는 canvas 좌표도 의도를 폐기한다.
- 최종 UP에서 일회용 의도를 SDK 호출 전에 소비한다. 중복 UP은 같은 callback을 재사용하지 못하며, SDK 처리 중 재진입한 새 DOWN은 옛 dispatch 실패의 `discard(expected)`나 승인된 UP의 finally 정리로 폐기하지 않는다.

## 일회용 입력과 선택 표시의 수명 분리

짧은 DOWN/UP 입력은 한 번 소비되지만, 허용된 선택 표시는 해당 STARTED/renderer 소유권이 살아 있는 동안 유지해야 한다. UP 정리 때 입력 토큰을 없애는 것만으로 선택 표시까지 즉시 무효화해서는 안 된다. 반대로 다음 P로 복귀했다고 옛 선택 소유자를 다시 활성화해서도 안 된다.

이를 위해 native 허용 세대는 별도의 `AtomicBoolean` 소유자를 캡처한다. 허용을 회수하거나 STOP, detach, destroy가 발생하면 옛 소유자를 영구 false로 바꾸고 새 허용에는 새로운 소유자를 만든다. 선택의 `canPresent`는 이 atomic 소유자와 Route의 고정 STARTED 소유자를 읽는다. ViewModel에서 View를 거쳐 다시 ViewModel을 호출하는 재귀 검증 경로를 만들지 않는다.

[MapRoute](../../feature/map/src/main/kotlin/io/github/hangillab/hangil/feature/map/MapScreen.kt)는 구성 시 읽은 attempt, renderer token과 preview를 callback에 고정한다. [MapViewModel](../../feature/map/src/main/kotlin/io/github/hangillab/hangil/feature/map/MapViewModel.kt)은 선택, native filter 적용과 응답마다 현재 reader/query, READY, 실제 P와 UX, 해당 tuple과 선택 nonce를 대조한다. 같은 preview에서 과제만 바뀌어도 이전 callback이 새 준비를 채택하지 않는다. 표시 응답은 현재 nonce에만 적용한다.

닫기, STOP과 재생성에서는 선택을 저장하거나 자동 복원하지 않는다. 명시적인 ACTION_CANCEL, 권한 회수와 `cancelNativeInput`은 현재 입력 전체를 보수적으로 정리한다. identity 보호는 승인된 UP finally의 `endTouch`/`lastTouch` 정리와 SDK dispatch 실패의 `discard(expected)`에 적용한다. 해당 정리에서는 자신이 처리한 epoch/event 또는 의도와 같은지 확인해 재진입한 새 DOWN을 지우지 않는다. 실제 lifecycle을 거친 오래된 UP 검사는 입력 유지 시간이 길어져 시간 제한 거부와 겹칠 수 있으므로, 그 결과만으로 소유권 하나의 효과를 분리했다고 주장하지 않는다.

## 현재 renderer의 화면 좌표와 지점 대조

[MapPointHitTest](../../feature/map/src/main/kotlin/io/github/hangillab/hangil/feature/map/MapPointHitTest.kt)는 Main thread에서 현재 `MapLibreMap`의 fully loaded style을 확인하고, `hangil-course-preview-circles` 하나에 한정해 `queryRenderedFeatures(PointF(x, y), layerId)`를 호출한다. 해당 layer의 source가 기존 `hangil-course-preview-points`인지 확인하고 Java source 캐시 대신 현재 native source 목록에서 유일한 GeoJsonSource인지 대조하며 조회/투영 뒤에도 같은 style인지 다시 확인한다. SDK 조회 실패나 사라진 구조는 no-hit으로 숨기지 않고 현재 renderer의 실패 경계로 전달한다. [고정 버전의 rendered feature 조회 API](https://github.com/maplibre/maplibre-native/blob/android-v13.5.0/platform/android/MapLibreAndroid/src/main/java/org/maplibre/android/maps/MapLibreMap.java#L2061-L2073).

좌표는 MapView 왼쪽 위를 원점으로 한 Android 화면 px다. dp나 전체 Activity 좌표를 그대로 전달하지 않으며 density를 임의로 한 번 더 곱하거나 나누지 않는다. SDK projection이 자신의 pixel ratio를 처리한다. 실제 Activity 입력 하네스는 JNI projection으로 구한 View-local px에 현재 View의 window 위치를 더해 dispatch 좌표로 변환한다. [고정 버전의 projection 좌표 계약](https://github.com/maplibre/maplibre-native/blob/android-v13.5.0/platform/android/MapLibreAndroid/src/main/java/org/maplibre/android/maps/Projection.java#L17-L20).

조회된 모든 후보에서 point ID, 제목, preview ID/version, 순서와 Point geometry를 현재 고정 preview와 대조한다. 다른 ID가 함께 조회되거나 후보 하나라도 오염됐으면 일부만 버리고 다른 지점을 선택하지 않는다. 같은 유효 ID의 타일/월드 복제본은 허용한다. 숫자 metadata는 실제 JSON number를 기대값과 대조하고 문자열 숫자로 대신하지 않는다.

타일로 양자화된 geometry는 source의 원래 위경도와 완전히 같지 않을 수 있다. 두 좌표를 현재 camera로 투영해 유클리드 화면 거리 **2px 이하**인지 대조한다. 이 한도는 geometry 정합성 정책이며 터치 반경을 2px로 넓히는 기능이나 지리적 정확도/GPS 오차 측정이 아니다. 현재 정책은 넓은 줌 범위, 모든 밀도/기기에서의 허용 오차를 실측해 확정한 값이 아니다. 2px를 넘으면 새 선택을 하지 않는다.

강조는 [기존 GeoJSON source와 selection layer](../../feature/map/src/main/kotlin/io/github/hangillab/hangil/feature/map/CoursePreviewLayers.kt)의 filter만 변경한다. 선택 ID, 제목과 카메라를 별도로 읽어 대조하는 실기 절차를 아래에 두며 filter ACK만으로 실제 픽셀 강조나 GPU 완료를 대신하지 않는다.

## 제품 변경 전 기준 계측

새 탭 기능에는 동등한 과거 선택 동작이 없다. 탭 속도의 개선율은 계산하지 않고, 입력 handler 추가가 기존 지도 진입에 미치는 비용만 고정 절차로 비교한다. 현재 앱/SDK가 느리다는 병목을 확인한 성능 최적화 작업은 아니다.

- 기준 제품: `b559c6d3cbe79d63e18b238c97cb44e05b46f0a7`
- 고정 계측 지원: `6f0338670f73cc87cb1753df4338e2062447fdc2`
- 계측 메서드: [MapOpeningMeasureTest.measureMapOpeningAndPreserveOriginalData](../../app/src/androidTest/kotlin/io/github/hangillab/hangil/MapOpeningMeasureTest.kt)
- 별도 저장 namespace: `map-point-tap-entry`. 실행 인자가 아니라 지원 커밋의 고정 파일명과 kind로 분리한다. 이전 지점 목록 선택의 [105개 timing 원값](measurements/map-point-selection-entry-ui-times.csv)과 기존 제품/계측 근거는 변경하지 않는다.
- 실제 SDK P와 UX 허용, 같은 준비/카탈로그/원래 전체 자료, API34 Pleos debug, JDK17, font1.0/라이트를 유지한다. 단계별 새 process/Activity에서 시작해 캐시를 지우지 않고 5회 워밍업 뒤 같은 프로세스에서 30회 지도를 열고 닫는다.
- 시작은 `home_map` 클릭 직전이다. `map_view`까지 같은 scroll을 수행하고, `map_ready`와 전체 물리 viewport, 실제 native style/source/3지점/fit camera, Compose idle와 native 재확인을 endpoint로 삼는다. 시계는 `SystemClock.elapsedRealtimeNanos`, 단위는 ms다.
- 클릭 dispatch, 지도 스크롤, 공개 style/타일 로딩과 native/semantics 조회, Compose 동기화가 포함된다. 홈 사전 스크롤, SDK/원본/환경 검사, JSON, 닫기와 Activity/process 시작은 제외한다. 순수 renderer/GPU/프레임/SDK 또는 운전자 속도 측정이 아니다.
- endpoint는 클릭 시작부터 실제 30초 이내여야 한다. 동기 Compose blocking 호출을 coroutine timeout이 중간에 선점한다는 뜻은 아니며, 복귀 뒤 실제 경과도 확인한다.

| 기준 웜 표본 지표 | 실제 결과 |
|---|---:|
| 워밍업 완료 / 본 표본 성공 | 5 / 30 |
| 중앙값(ms) | 1759.1112925 |
| Q1 / Q3(ms) | 1721.844001 / 1842.231542 |
| IQR(ms) | 120.387541 |
| min / max(ms) | 1544.623459 / 2126.438376 |
| 실패 / 누락 / 제외 | 0 / 0 / 0 |

Q1/Q3는 정렬한 본 표본 30개 중 하위 15개와 상위 15개 각각의 중앙값이며 IQR은 Q3−Q1이다. 워밍업은 별도 보관하고 본 표본 통계에 섞지 않는다. 현재 변경 후 값과 변화율은 미측정이다. 기준만으로 개선폭, 성능 향상이나 추가 비용을 주장하지 않는다.

기준→제품 구현→관련 검증→비교의 순차 실행이다. 캐시/네트워크/JIT/발열/OS와 순서 영향을 모두 통제하지 못하므로, 이후 작은 차이나 겹치는 분포가 나와도 향상, 인과 저하 또는 동등성/무회귀를 확정하지 않는다. 동일 동작의 전후 paired 측정으로 부르지 않는다.

완료 기준 원문은 앱의 비공개 cache 상대 경로 `measurements/map-point-tap-entry-baseline.json`, 비교는 `measurements/map-point-tap-entry-comparison.json`에 별도 저장한다. 원문에는 고정 입력/전체 domain snapshot/환경/설치 APK hash가 포함되므로 공개하지 않는다. 기존 파일이나 `.bak`/`.new` sidecar가 있으면 덮어쓰지 않는다. 비교는 완료된 같은 kind/contract의 기준을 읽어 원본 자료, source, catalog와 환경의 동일성을 확인하고, 실행 뒤 기준 파일 SHA가 그대로인지 다시 확인한다. 각 표본의 원값, 실패/누락/미실행/제외와 실제 설치된 제품/계측 APK SHA를 보존한다. SHA 인자는 외부 실행자의 선언이며 기기에서 소스 커밋을 자동 증명한 것으로 기록하지 않는다.

## 재현 전제와 명시적 실행

외부 실행자는 승인된 전용 환경의 실제 DDS 브리지 Online, 실제 앱 전면과 SDK P/UX를 먼저 확인한다. Virtual Console HTTP 응답만으로 수신을 가정하지 않는다. 활성 연습이 없는지 확인하고 원래 SQLite의 모든 값/자료형/순서/sequence와 권한/설정 snapshot을 비공개로 보존한다. 하네스의 domain 필드/순서 비교는 이 외부 raw 검사와 구분한다. 기존 자료를 삭제하거나 시험용 준비로 덮어쓰지 않는다.

다음 명령은 절차 예시다. `HANGIL_TEST_SERIAL`과 `HANGIL_TEST_USER`는 현재 승인된 전용 장치/AAOS 사용자를 비공개 변수로 지정한다. 실제 식별자나 개인 경로를 공개 로그에 출력하지 않는다. APK 출처/설치 일치와 기준 cache의 존재 조건을 먼저 확인하고, 완료 파일이 있으면 해당 기준 명령을 다시 실행해 덮어쓰려 하지 않는다. 설정 전환과 복원은 외부 소유자가 수행하며 하네스가 권한이나 설정을 바꾸지 않는다.

```sh
HANGIL_JAVA_HOME="$(/usr/libexec/java_home -v 17)"
"$HANGIL_JAVA_HOME/bin/java" -version
JAVA_HOME="$HANGIL_JAVA_HOME" ./scripts/verify.sh
JAVA_HOME="$HANGIL_JAVA_HOME" ./gradlew :app:assembleDebugAndroidTest
```

아래 baseline 명령은 제품 변경 전에 실행한 고정 출처를 나타낸다. comparison은 동일 계측 파일/절차를 유지한 검증된 제품의 완전한 SHA를 `HANGIL_COMPARISON_PRODUCT_SHA`에 지정해야 하며 현재는 미실행이다.

```sh
adb -s "$HANGIL_TEST_SERIAL" shell am instrument --user "$HANGIL_TEST_USER" -w -r \
  -e class io.github.hangillab.hangil.MapOpeningMeasureTest#measureMapOpeningAndPreserveOriginalData \
  -e manualMapOpeningMeasure true -e measurementStage baseline \
  -e declaredProductCommit b559c6d3cbe79d63e18b238c97cb44e05b46f0a7 \
  -e declaredHarnessRevision 6f0338670f73cc87cb1753df4338e2062447fdc2 \
  io.github.hangillab.hangil.debug.test/androidx.test.runner.AndroidJUnitRunner

adb -s "$HANGIL_TEST_SERIAL" shell am instrument --user "$HANGIL_TEST_USER" -w -r \
  -e class io.github.hangillab.hangil.MapOpeningMeasureTest#measureMapOpeningAndPreserveOriginalData \
  -e manualMapOpeningMeasure true -e measurementStage comparison \
  -e declaredProductCommit "$HANGIL_COMPARISON_PRODUCT_SHA" \
  -e declaredHarnessRevision 6f0338670f73cc87cb1753df4338e2062447fdc2 \
  io.github.hangillab.hangil.debug.test/androidx.test.runner.AndroidJUnitRunner
```

## 새 native 제어와 실제 SDK 시나리오

[MapInputLifecycleTest](../../app/src/androidTest/kotlin/io/github/hangillab/hangil/MapInputLifecycleTest.kt)는 ComponentCatalogActivity에 실제 MapRoute/ViewModel과 MapLibre를 연결한다. 차량/UX 상태와 준비 저장소는 명시적 합성 fixture다. 실제 Room이나 Vehicle SDK 수신을 검사한 것으로 쓰지 않는다. 공개 SDK API와 실제 Activity/View dispatch로 아래 동작을 제어하며 원래 앱 그래프를 열거나 저장소에 쓰지 않는다.

| 현재 메서드 | 구현된 확인 범위 | 초기 후보 실행 / 현재 재검증 |
|---|---|---|
| `nativeCanvasPointTapsSelectAllThreeCurrentPointsWithoutMovingCamera` | 현재 preview 세 지점의 실제 native 탭, 선택/강조/설명과 camera 보존 | 통과 / 재검증 중 |
| `nativeCanvasPointTapsUseAllThreePointsOfTheSecondCatalogPreview` | 두 번째 공개 catalog preview의 세 지점 | 통과 / 재검증 중 |
| `backgroundCancelLongPressDragReturnAndMultiplePointersNeverBecomePointChoices` | 빈 곳, CANCEL, 긴 누름, slop 밖 왕복과 같은 지점의 다중 포인터 접촉의 선택 오인 거부 | 해당 음성 흐름 실패 / 하네스 보강 후 재검증 중 |
| `outsideAndInvisibleCanvasSamplesNeverBecomePointChoices` | 실제 화면 경계 밖/숨김 View의 제어 입력 거부. 실제 사용자 전달 증거와 구분 | 통과 / 재검증 중 |
| `actualCompassAndAttributionChildTouchesNeverBecomePointChoices` | 실제 자식 compass/출처 dialog와 canvas 선택 분리. 외부 URL 항목은 실행하지 않음 | 통과 / 재검증 중 |
| `nativePointUpDeniedBySilentDDoesNotReviveAfterSilentP` | 표시된 옛 Ready를 유지한 무방출 D에서 UP 거부, 무방출 P 뒤 옛 UP 부활 거부 | fresh tap 대기 실패 / 하네스 보강 후 재검증 중 |
| `nativePointDownCannotCrossStopAndRestartIntoANewView` | STOP/START의 새 View와 옛 UP 분리, 새 탭 양성 대조 | 통과 / 재검증 중 |
| `nativePointDownCannotBorrowAnotherTaskOfTheSamePreview` | 같은 preview의 과제 교체도 옛 DOWN을 새 renderer로 승격하지 않음 | 통과 / 재검증 중 |
| `freshParkedPanAndPinchChangeTheRealNativeCamera` | 허용된 직접 pan/pinch가 실제 camera를 바꾸는 별도 양성 대조 | 통과 / 재검증 중 |
| `nativeImmediatePointerWheelZoomRequiresTheLiveParkedGate` | 공개 wheel 입력의 허용 P 양성/무방출 D 거부 | 통과 / 재검증 중 |

표는 현재 하네스의 메서드명으로 연결한 범위다. 최초 후보의 음성 흐름과 fresh P 실패를 아래 기록에서 분리하며, 하네스 수정 전후를 하나의 성공 실행으로 합치지 않는다. 초기 10개 실행의 건너뜀은 0이었으나 전체 실행은 실패다. 새 지점 시나리오와 기존 pan/pinch/wheel 양성 대조를 구분한다.

명시 옵션은 `manualMapInputBoundary=true`다. 다음은 첫 양성 시나리오의 절차 예시이며 나머지도 표의 정확한 메서드를 선택해 실행한다. 기존 클래스 전체를 실행하면 이전 입력 경계도 함께 포함되므로 새 시나리오의 결과와 구분해 집계한다.

```sh
adb -s "$HANGIL_TEST_SERIAL" shell am instrument --user "$HANGIL_TEST_USER" -w -r \
  -e class io.github.hangillab.hangil.MapInputLifecycleTest#nativeCanvasPointTapsSelectAllThreeCurrentPointsWithoutMovingCamera \
  -e manualMapInputBoundary true \
  io.github.hangillab.hangil.debug.test/androidx.test.runner.AndroidJUnitRunner
```

[MapPointTapFlowTest.nativePointTapsMatchFixedHighlightAndExpireAcrossOwnerAndDrivingChanges](../../app/src/androidTest/kotlin/io/github/hangillab/hangil/MapPointTapFlowTest.kt)는 실제 MainActivity/그래프/SDK와 원래 준비를 읽기 전용으로 사용한다. 현재 코스 세 지점에 대해 전체 MapView를 물리 viewport에 보이게 스크롤하고 JNI projection과 실제 Activity DOWN/UP로 탭한다. 매번 Compose idle를 진행한 뒤 현재 attached View의 native style/layer/filter/metadata, 같은 라디오/설명과 camera 불변을 확인한다. 목록 대체 선택도 확인한다. 재생성, 닫기/재진입과 STOP/START 후에는 처음 선택이 없고 옛 UP은 거부되며 새 탭만 허용되는지 구분한다.

실제 DDS 제어는 외부 소유자가 `POINT_TAP_AWAIT_D`에서 90초 안에 D를 전달하고, `POINT_TAP_AWAIT_P`에서 P와 UX 허용을 복구한다. D에서는 지도 상세/선택/설명이 없어야 하고 P 복귀에도 자동 선택이 없어야 한다. 모든 절차와 원본 보존이 끝난 뒤에만 `POINT_TAP_COMPLETED`를 보낸다. 기본 font1.0/라이트와 확대 font1.3/다크는 같은 메서드를 서로 다른 명시 조건으로 각각 실행한다. 실제 원래 준비의 한 preview만 검사하며 두 번째 preview의 합성 제어 결과와 혼동하지 않는다.

```sh
adb -s "$HANGIL_TEST_SERIAL" shell am instrument --user "$HANGIL_TEST_USER" -w -r \
  -e class io.github.hangillab.hangil.MapPointTapFlowTest#nativePointTapsMatchFixedHighlightAndExpireAcrossOwnerAndDrivingChanges \
  -e manualMapPointTap true -e expectedFontScale 1.0 -e expectedDark false \
  io.github.hangillab.hangil.debug.test/androidx.test.runner.AndroidJUnitRunner

adb -s "$HANGIL_TEST_SERIAL" shell am instrument --user "$HANGIL_TEST_USER" -w -r \
  -e class io.github.hangillab.hangil.MapPointTapFlowTest#nativePointTapsMatchFixedHighlightAndExpireAcrossOwnerAndDrivingChanges \
  -e manualMapPointTap true -e expectedFontScale 1.3 -e expectedDark true \
  io.github.hangillab.hangil.debug.test/androidx.test.runner.AndroidJUnitRunner
```

원래 준비와 모든 세션/관찰/메모의 domain 필드와 순서를 전후 대조하며 활성 연습을 만들지 않는다. 아직 도달하지 못한 화면이나 skip을 성공으로 세지 않는다. 실패하면 cleanup/Activity 종료 전에 실제 전체 화면과 익명 전면/태그/native 판정 진단을 비공개 cache에 보존한다. 진단 실패는 원래 예외에 suppressed로 남기며 자동 재시도나 성공 대체를 하지 않는다.

캡처와 익명 JSON은 실행별 `screenshots/map-point-tap-<소유 nonce>/`에 저장한다. 원시 PNG에는 원문/시각이 포함될 수 있으므로 공개 사진으로 간주하지 않는다. 실제 전면과 글자/줄/물리 viewport 검사를 통과한 앱 영역 crop도 먼저 비공개로 검토한다. 이 문서에는 미완성 공개 이미지나 빈 사진 자리를 추가하지 않는다. 입력 증거의 `withinProductTapDuration`은 실제 경과가 0 이상이고 long-press 한도 미만인지 나타내며 성능 표본이나 lifecycle 단일 요인 증거가 아니다.

## 현재 검증 기록과 README 판단

현재 실제 기록은 다음처럼 분리한다. 테스트/빌드 개수와 소요 시간은 앱 성능 수치가 아니다.

| 실행 | 실제 결과와 범위 |
|---|---|
| 제품 변경 전 기준 계측 | 고정 제품/지원 출처의 워밍업 5개와 본 표본 30개 통과. 통계는 앞 절에 기록 |
| 기준 제품의 실제 current hit 탭 양성 | 기존 직접 탭 선택 경로가 없어 선택/ACK 대기 30초에서 예상 실패. SDK 결함 재현이나 탭 속도 비교가 아님 |
| 최초 APK hash 읽기 준비 | 추가 quality와 ADB 읽기를 겹쳐 ADB read가 30초 timeout. 계측은 시작하지 않았고 데이터/권한을 바꾸지 않음. 실패 보존 후 Gradle 종료 뒤 순차 읽기에서 hash 대조 성공 |
| 최초 테스트 컴파일 | `MapPointHitTestTest`의 null 인자 오버로드 실패를 명시 `JsonObject` 타입 한 줄로 수정. 실패 자체도 보존 |
| 초기 후보 단위/전체 검증 | tracker 19개, hit 27개, epoch 18개 통과. 이어 JDK17 전체 검증에서 44 suites/614개 단위의 실패/오류/건너뜀 0과 debug/unsigned release, AndroidTest APK 통과. 가시 범위 판정 정리 뒤 quality 재통과 |
| 그 뒤 제품 source 대조 보강 | Java source 캐시 대신 현재 native source 목록을 검증하도록 수정. 이 변경을 포함한 최종 전체 재검증은 후속이며 이전 전체 통과로 대신하지 않음 |
| 최초 후보 제어 native | 10개 중 8개 통과/2개 실패, 건너뜀 0. 원래 실행을 보존하고 성공으로 합산하지 않음 |
| 하네스 보강 후 제어 native | 같은 제품 APK로 재검증 진행 중. 아직 최종 성공 미확인 |
| 실제 SDK 기본/확대 다크, 변경 후 계측 | 미실행 |
| 최신 PR | 미생성 |

실패한 흐름은 `nativePointUpDeniedBySilentDDoesNotReviveAfterSilentP`의 무방출 P 뒤 fresh tap 선택/ACK 30초 대기와, 현재 `backgroundCancelLongPressDragReturnAndMultiplePointersNeverBecomePointChoices`로 연결되는 음성 입력 흐름의 실제 current circle hit 확인이다. 후자의 정확한 실패 호출 단계는 기존 로그만으로 확정하지 못했으므로 카메라 복원, SDK 결함이나 제품 결함 하나로 원인을 단정하지 않는다.

보강한 하네스는 무방출 P가 실제 수신 통지를 생략하는 fixture라는 점을 반영해 공개 무동작 key dispatch로 새 허용 세대의 시각 하한을 먼저 확립한다. 그 뒤 같은 옛 UP의 거부와 새 DOWN의 양성 대조를 수행하며, 제품 timestamp floor나 입력 거부 조건은 약화하지 않는다. 음성 흐름은 입력 전에 같은 실제 current circle hit/metadata/가시 범위를 최대 30초의 엄격한 준비 polling으로 확인한다. 임의 point 이동, null 허용이나 성공 기본값으로 대신하지 않는다. 이 보강의 결과는 아직 재검증 중이다. [작업 계획](../plans/active/2026-10-31-map-native-point-tap.md)에 실제 후속 결과를 따로 남긴다.

README의 네 사례와 순서는 보존한다. 이번 기능은 기존 세 번째 사례인 reader/query/renderer/native 입력 소유권을 재사용하고 확장하는 축이다. 새 탭 연결이나 코드량만으로 기존 네 사례보다 채용 관점에서 더 강한 문제 해결이라고 판단하지 않는다. 실제로 원인을 좁힌 결함, 대안 판단과 전후 실측/실기 근거가 더 설득력 있게 확보됐을 때만 해당 사례를 교체하거나 강화한다. 현재는 그 최종 근거가 없으며 회귀 개수/목록과 JUnit/CI 설명을 README에 추가하지 않는다.

제어 native의 STOP/START와 같은 preview 과제 교체도 새 View/style 준비 대기로 long-press 시간 제한과 소유권 거부가 겹칠 수 있다. 해당 검사만으로 소유권의 효과를 단독 분리했다고 쓰지 않는다. 다중 포인터 음성은 실제 지점 중심에 첫 손가락을 고정하고 둘째 손가락의 접촉/제거만 전달하며 MOVE를 만들지 않는다. 시작/끝 실제 hit, 첫 지점 투영의 x/y 각각 1px 이내 유지와 long-press 한도 미만을 검사해 drag/시간 초과 거부와 구분한다. 완전한 pinch 확대 동작의 별도 양성 대조와 혼동하지 않는다.
