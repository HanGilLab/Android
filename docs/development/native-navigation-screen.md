# 주차 후 기본 내비 화면 연결

홈의 명시적인 ‘기본 내비 열기’ 행동을 실제 NaviHelper3.0.5로 연결했다. [작업 계획](../plans/active/2026-10-31-native-navigation-screen.md)에 최초 실패와 검증, 최신 PR 조건을 관리한다. [제품 PR62](https://github.com/HanGilLab/Android/pull/62)는 최신 CI/Codex/관련 실기와 보호 정책 확인 뒤 정상 병합했다. 이번 정리 문서 PR의 최신 검사는 별도다. 전체 MAP-04/APP-05 완료를 뜻하지 않는다.

## 제품 경계와 판단

[NavigationScreenViewModel](../../feature/home/src/main/kotlin/io/github/hangillab/hangil/feature/home/NavigationScreenViewModel.kt)은 현재 RESUMED 호스트, 표시한 상태 객체/요청 세대와 최신 P+UX 허용을 묶는다. 클릭 전과 SDK 연결 뒤 dispatch 직전에 다시 확인하고 PAUSE/STOP/화면 폐기/주행 거부에서 요청을 폐기한다. P 복귀만으로 재요청하거나 옛 콜백이 새 호스트를 빌리지 않는다. 현재 목적지가 아닌 entry도 STARTED일 수 있어 외부 화면 전환은 [공식 수명 설명](https://developer.android.com/guide/navigation/use-graph/programmatic)에 맞춰 RESUMED로 한정했다. 차량 수집의 기존 STARTED 계약은 바꾸지 않았다.

[NavigationScreenOperation](../../core/vehicle/src/main/kotlin/io/github/hangillab/hangil/core/vehicle/NavigationScreenOperation.kt)은 한 번에 한 요청을 허용하고 경쟁 요청을 대기열에 넣지 않는다. 새 클라이언트의 지원/권한/초기 연결을 확인한 뒤 NavigationScreenRequest.Foreground만 한 번 호출한다. 시간 초과, 거부, 미지원과 오류를 구분하며 취소 시 원래 취소를 보존하고 정리한다. 기존 LocationBindingContext는 [SdkBindingContext](../../core/vehicle/src/main/kotlin/io/github/hangillab/hangil/core/vehicle/SdkBindingContext.kt)로 이름만 일반화해 인스턴스별 바인딩 소유권을 재사용한다. 닫힌 context는 플랫폼 재바인딩과 늦은 callback을 차단하며 SDK 내부 Job 자체의 취소를 보장하지 않는다.

[실제 어댑터](../../core/vehicle/src/main/kotlin/io/github/hangillab/hangil/core/vehicle/PleosNavigationClient.kt)는 화면용 NAVI_CUSTOM_MAP만 선언/확인한다. 현재 이미지에서 normal임을 실제 정의로 확인했고 외부 dangerous grant는 하지 않았다. 다른 플랫폼에서 거부되면 요청하지 않는다. 연결 전용 계측 APK는 기능 권한을 제거한 기존 조건을 유지한다. 경로/경로 검색/정보/위치/취소 API는 호출하지 않는다.

REQUESTED는 void SDK 함수를 호출했다는 결과다. 실제 전면이나 목적지/길안내 성공의 ack가 아니며 이미 전달한 요청을 앱 이탈이 소급 취소한다고 약속하지 않는다. 동기 오류는 확인하지만 해제 뒤의 비동기 오류를 모두 관찰한다고 보장하지 않는다. 호출/정리 오류도 이미 전환이 전달되지 않았음을 뜻하지 않는다. 현재 화면은 목적지 미전송을 표시한다. 전면 수집은 한길 이탈 시 중단하며, 이후 연결한 [명시적 배경 차량 기록](background-vehicle-recording.md)을 켠 실행은 별도 수명으로 구분한다.

## 실제 검증과 한계

| 구분 | 이번 실행 결과 |
|---|---|
| 자동 | JDK17.0.21+9 전체 verify4단계, JVM715개/48suite 실패/오류/건너뜀0, debug/unsigned release와 계측 APK 통과. 새 요청16개/VM10개 포함 |
| 제어 native | 실제 Compose/수명과 합성 차량/UX/게이트웨이로3PASS/건너뜀0. 무방출 D/P, PAUSE/STOP/재진입의 옛 클릭, 연결 대기 중 거부와 자동 재시도 차단. 실제 SDK 오류 주입과 구분 |
| 비교 전 실제 조건 | 변경 전/후 각각 실제 브라우저 DDS ONLINE의 D/RESTRICTED와 P/UNRESTRICTED SDK/AAOS 수신 프로브2개씩 통과 |
| 최초 실제 기본 | 패키지/자료 검사1PASS이나 전면 전환 직후 PNG2장은 이전 Main frame이었다. 실제 화면의 시각 성공으로 세지 않고 원형을 보존 |
| 안정 캡처 기본1.0/라이트 | 실제 Main/SDK/Room1PASS/건너뜀0. 내비 전면 유지/복귀/옛 클릭 차단/실제 D 잠금/P 복귀 시 자동 요청 없음/새 클릭 전환 |
| 안정 캡처 확대1.3/다크 | 같은 실제 범위1PASS/건너뜀0. scope glyph/ellipsis/overflow/화면 경계와 버튼 도달 확인 |

두 테마 각각6개의 비공개 PNG를 직접 확인했다. 실제 기본 내비의 검색/2D/설정 UI와 한길 복귀/잠금을 확인했으며 지도 바탕의 타일이 비어 있었다. 이것을 지도 렌더, 목적지/경로/길안내 성공으로 기록하지 않는다. 두 요청에서 활성 안내 경로가 표시되지 않았고 새 경로를 시작하지 않았다. 변경 전의 활성 목적지/안내 상태를 확보하지 않았으므로 기존 활성 안내의 보존은 미검증이다. 내비 내부 데이터의 byte 일치도 주장하지 않는다. 실제 SDK 강제 지연/연결 끊김/독립 UX 오류 주입과 실제 도로 검증은 미실행이며 제어 검사를 그 근거로 대신하지 않는다.

전면 패키지와 compositor frame의 시차는 계측에서 idle/안정 대기를 추가해 재확인했다. 대기는 모든 성능 timer 밖이다. 제품4b29088의 앱 APK는 그대로이며 수정한 계측 APK/hash를 고정 전후 계측 APK와 따로 보존했다. 초기에 Spotless/fixture 컴파일/어댑터 모듈 권한 Lint가 실패한 기록도 계획에 분리했다. 권한을 호출 모듈 manifest에 선언해 해결했고 검사 억제나 성공 기본값을 쓰지 않았다.

이번 실행은 새 세션/관찰/메모를 만들지 않았다. 원래 준비1/종료11/관찰17/메모1의 모든 행/값/물리 자료형/순서/sequence와 기존 grants/runtime/font/night/IME/proxy를 정확히 대조했다. 의도한 normal NAVI_CUSTOM_MAP 선언/자동 허용 하나만 제품 변경으로 따로 확인했다. 원래 테마/글자/환경으로 복구하고 이번 소유 AVD/forward/DDS 탭만 종료했다. 원문/식별자/위치/내비 캡처/개인 경로는 ignored 비공개에 보존한다.

## 기존 이력 진입의 변경 전후 관찰

속도 병목은 관찰하지 않았고 새 내비 행동에는 동일한 변경 전 기능이 없다. 변경 전 계획/기준값을 확보하고 기존 접힌 이력 진입의 추가 비용만 같은 endpoint로 관찰했다. 고정 지원6bf134267af174344b7bc1f00c4dd99e2d47f221의 native-navigation-entry-v1, frozen 소스/hash와 양쪽 실제 설치 APK를 확인했다. fixedSource/originalData/catalog/environment/measurementContract가 같았다.

조건은 JDK17.0.21+9/debug/PleosAPI34/font1.0/라이트/같은 현재 사용자/원래 준비1/종료11/관찰17/메모1/같은 카탈로그/실제 SDK P+UX 허용이다. 각 단계 fresh process/Activity 뒤5warm+30본의 이력 열기/닫기이며 B1→F1이다. timer는 home_history click 직전부터 같은 Ready/원문/Compose idle endpoint까지 elapsedRealtimeNanos다. dispatch/검사/동기화를 포함하고 앱 전용 frame/GPU/SDK 지연이나 운전자 시간이 아니다. 시작/사전 스크롤/닫기/export/캡처/대조는 밖이며 새 내비 SDK는 양쪽 측정 뒤에만 호출했다.

[70개 익명 원값](measurements/native-navigation-entry-ui-times.csv)은 정수 ns와 정확히 유도한 ms를 순서대로 보존한다. 통계는30개 본 표본이며 Q1/Q3는 정렬 하위15/상위15의 중앙값이다. 고정20초 endpoint/원본/P/UX/입력 실패는 실패로 정했고 느린 값도 제외하지 않았다.

| 지표 | 변경 전 | 변경 후 |
|---|---:|---:|
| 제품 | 05b89481cce6088764b8635894fc922de8a75905 | 4b29088cdc9c4da27a97c7b7f3d21e8686005b17 |
| warm/본 표본 | 5/30 | 5/30 |
| 중앙값(ms) | 306.897833 | 272.9259795 |
| Q1/Q3(ms) | 272.925333/373.48275 | 257.689459/288.796833 |
| IQR(ms) | 100.557417 | 31.107374 |
| min/max(ms) | 209.513292/1319.774792 | 222.036291/688.840625 |
| 실패/누락/제외 | 0/0/0 | 0/0/0 |

관찰 차이는 -33.9718535ms/-11.0694341397%다. IQR이 겹치고 데이터/Tile/DB/코드 캐시를 지우지 않았다. APK 교체/ART/profile/JIT/OS/발열/순서/네트워크 영향을 분리하지 못해 향상/인과 저하/동등성/무회귀를 모두 확정하지 않는다. release/다른 기기/콜드 실행/새 내비 전환으로 일반화하지 않는다. 검사 수/보존 행 수/JUnit/CI/Gradle 시간은 성능 수치가 아니다.

## 재현 전제

1. JDK17 verify와 계측 APK를 빌드한 뒤 무거운 Gradle 종료를 확인한다. 기존 AVD/모든 원래 자료/권한/설정을 비공개로 보존하고 활성 세션이 없음을 확인한다. 실제 전면과 브라우저 DDS/SDK P+UX를 확인한다.
2. [SessionHistoryOpeningMeasureTest](../../app/src/androidTest/kotlin/io/github/hangillab/hangil/SessionHistoryOpeningMeasureTest.kt)에 manualHistoryOpeningMeasure=true, measurementSeries=native-navigation-entry-v1, measurementStage=baseline|comparison, 실제 제품 완전 SHA와 declaredHarnessRevision=6bf134267af174344b7bc1f00c4dd99e2d47f221을 명시한다. 양쪽 같은 입력과 설치 hash를 대조하고 완료한 원형을 덮어쓰지 않는다.
3. [NavigationScreenBoundaryTest](../../app/src/androidTest/kotlin/io/github/hangillab/hangil/NavigationScreenBoundaryTest.kt)는 manualNavigationScreenBoundary=true로3개 제어 경계를 실행한다. SDK/차량 값이 합성임을 구분한다.
4. [NavigationScreenFlowTest](../../app/src/androidTest/kotlin/io/github/hangillab/hangil/NavigationScreenFlowTest.kt)는 manualNativeNavigationScreen=true, 실제 확인한 expectedNavigationPackage, expectedFontScale=1.0/expectedDark=false 또는1.3/true로 실행한다. NAVI_SCREEN_AWAIT_D/P에서 실제 DDS를 조작한다. opt-in skip은 성공이 아니다. 패키지 외에 안정 캡처의 실제 전면을 직접 확인한다.
5. 외부 SQLite/sequence/권한/runtime/설정을 별도로 대조하고 원래 환경을 복구한다. 이 실행은 새 자료를 생성하지 않으며 소유 프로세스/forward/탭만 정리한다.

README의 기존4개는 그대로 유지했다. 이번 전환은 기존1번/3번의 live/source/수명 판단을 재사용하지만 더 강한 독립 원인/성능 개선을 입증하지 않았다. 기존2번의 CAS/트랜잭션/롤백과4번의 SQLite 물리 자료형도 그대로 필요하다. 모듈 권한 Lint와 캡처 시차는 로컬 준비/검증 문제이며 그것만으로 더 강한 제품 사례라고 교체하지 않는다. 기존 브랜드/이미지18개/실제 펼친 코드17개를 유지하고 새 공개 캡처나 placeholder를 만들지 않는다. 문서 정리는 런타임 성능 측정 적용 제외이고 링크/발췌/렌더/공개 콘텐츠로 확인한다.

## 제품 PR62 반영 기록

최종 head7e1ae499b7d2ebbb43c7fb76006a9e7deefef525의 [CI41196869030](https://github.com/HanGilLab/Android/actions/runs/41196869030)/attempt1과 같은 SHA App15368의 quality/test/build/ci-required4개 success, [Codex6542768713](https://github.com/HanGilLab/Android/pull/62#issuecomment-6542768713)의 completed/지적 없음/미해결 없음, native/정식 리뷰0과 active strict/no bypass를 확인했다. 정상 squash mergea5651033fd4ef8110060a6bd5a21d32029d0eee7의 실제 head/tree/parent/noreply와 main ff-only/양쪽 작업 브랜치 정리를 확인했다. 별도 main push41197339886와 이번 문서 PR의 검사는 이 시점에 완료로 기록하지 않으며 PR/Notion/비공개 체크포인트에서 마지막 실제 상태를 관리한다.

Notion readback은19/54/선택3개 제외이며 전체 MAP04/APP05는 미체크다. 활성 안내 보존이라는 원래 계획의 미검증 항목을 남겨 계획을 active에 유지한다. 화면 전환 제품의 반영과 전체 계획/길안내 완료 판정을 구분한다. 이번 문서 변경은 runtime/계측/README/원값을 바꾸지 않는다.


## 현재 main의 전면 전환 재검증

기준4838db6436d745e3df5c64aadb1b800adc08e134의 제품 코드는 바꾸지 않았다. 이전 검사의 global root 대신 실제 Main RESUMED에서 확보한 유효 display의 단일 focused window를 읽고 Main이 STARTED 아래인지 함께 확인하도록 [실기 하네스](../../app/src/androidTest/kotlin/io/github/hangillab/hangil/NavigationScreenFlowTest.kt)를 보완했다. 다른 Main 재생성/파괴, 빈 root, 중복 focus와 관찰 오류는 성공 기본값으로 대체하지 않는다. 원래 interactive-windows bit는 다른 flags를 유지한 채 복원하고 schema5 실행 마커까지 자료 대조에 포함한다. 실패 증거는 Main 복귀 정리 전에 남긴다.

같은 debug 제품, JDK17/PleosAPI34/font1.0/라이트, 실제 DDS ONLINE P+UX 허용, 준비1/종료13/관찰145/메모1/terminal 실행15/활성 없음 조건에서 fresh process 뒤2warm+5본을 순서대로 실행했다. 클릭 직전부터 같은 display의 실제 내비 focused package와 Main<STARTED를25초 안에 함께 관찰할 때까지 elapsedRealtimeNanos를 기록했다. 입력/동기화/AX polling을 포함한 관찰 지연이며 SDK 단독 처리나 렌더 완료 시간이 아니다. 준비/복귀/스크롤/캡처는 timer 밖이다.

[7개 정제 원값](measurements/native-navigation-foreground-revalidation.csv) 중 warm2개는 실제 전환을 관찰했다. 본5개 중3개는546.525875/547.059959/589.590625ms에 관찰했고2개는25초 제한 동안 Main RESUMED/전면에 남았다. 실패 latency는 비어 있으며 실제 기다린 elapsed_wait_ns를 별도로 남겼다. 성공3개의 중앙값547.059959ms/min546.525875ms/max589.590625ms는 성공 조건부 기술 통계일 뿐 전체5개 지연 분포나 개선 성과가 아니다. 실패2개를 제외한 값으로 전체5개의 Q1/Q3/IQR을 계산하지 않는다. 본 표본 성공3/실패2/누락0/제외0, warm 성공2/실패0이며 느린 성공을 제외하지 않았다. unknown 관찰10회는 요청 손실 수가 아니다. 변경 후 비교가 없고 캐시/ART/JIT/OS/순서 영향도 미분리여서 개선율을 계산하지 않는다.

비공개 SDK/플랫폼 로그의7개 요청 시간 구간 모두 원격 foreground 처리까지 관찰했고 실제 내비 대상 Activity 시작은 성공5개 구간에서만 관찰했다. per-attempt requestId 직접 조인이나 원격 앱 내부 분기 원인은 확인하지 못했다. local release 뒤 provider callback이 오는 순서는 성공과 실패에 공통이므로 즉시 release나 별도 요청 Job 취소를 원인으로 확정하지 않는다. 전역 예외 중 AppWidget/profile/SystemUI 등의 다른 경로는 이번 Navi 오류로 세지 않는다. 앱의 REQUESTED는 실제 전환 ACK가 아니라는 계약을 유지한다. 지연 추가나 연결 수명 변경을 제품 해결로 꾸미지 않고 원격 foreground 처리 이후의 조건을 부분 미검증으로 남긴다.

추가 기본 라이트 실제 흐름은1PASS/건너뜀0으로 내비 전면+Main STOP, 복귀, 옛 클릭 차단, 실제 D 잠금, P 복귀 자동 요청 없음과 새 명시 요청을 확인했다. 이는 반복 실패2개를 취소하지 않는다. 이번 확대 다크는 미실행이며 PR62의 과거 성공과 구분한다. 전체 MAP04, 기존 활성 안내 보존, 목적지/경유지/길안내와 앱의 연속 위치 기록도 미완료다. 새 데이터/권한/위치 요청 없이 현재13개 종료 연습과 실행15개의 전 필드/물리형/순서/sequence/기존 권한/환경은 외부 fresh snapshot으로 대조한다.
