# 네이티브 지도 입력의 최신 주차 조건과 수명 경계

- 상태: 진행 중
- 요구사항: ENV-06, QA-01의 지도 상세 입력, MAP-03 연결 전 안전 선행
- 기준 커밋: d0385bc357831bfef0d2fffb2e428faf5cc0ad65

## 목표와 범위

MapRoute의 표시 P 상태와 실제 수신 D 사이에 남은 MapView가 네이티브 제스처를 처리할 수 있는 경계를 닫는다. 현재 DemoMap은 기본 제스처가 활성인 원래 MapView를 생성하고 input 처리 guard가 없으며 VM은 loading/render 완료만 live 값으로 확인한다. 실제 Route/SDK View의 제어된 native RED와 변경 전 기준값을 먼저 실행한다.

SDK 공개 API로 터치/일반 모션/키/trackball과 자식 접근성/출처 dialog의 실제 지원 범위를 확인한다. 처리 직전의 live P+UX와 고정 renderer attempt/token, STARTED 수명을 확인하고 거부/이탈에 진행 중 입력과 예약된 SDK 동작을 안전하게 정리한다. 지원하지 못하는 입력을 정상 동작으로 위장하지 않는다. 출처와 저작권 표시는 유지하고 BACK/화면 닫기를 막지 않는다. 새 SDK/위치/내비 권한, DB/원본 자료 쓰기, 실제 경로, 새 지점 선택과 삭제 기능은 추가하지 않는다.

APP-08 삭제는 독립 구현 가능하지만 실제 원본 검증에서 검사 소유 세션 생성/삭제도 sequence를 바꿀 수 있으므로 별도 격리 검증 설계가 필요하다. 기존 MAP-02의 고정 공개 지점 선택도 가능하나 native input의 확인된 안전 누락을 먼저 처리한다. 원래 PR8/10의 미확인 리뷰나 MAP-01 전체 미체크를 독립 구현의 전면 대기 사유로 쓰지 않는다. ENV-06의 현재 이미지 독립 UX/플랫폼 오류 주입 차단은 그대로 구분한다.

## 완료 조건

- [x] 제품 수정 전 실제 Route와 실제 MapLibre SDK View에서 합성 무방출 live D/옛 P 표시의 입력 경계를 RED로 재현하고 실제 DDS 재현과 구분한다.
- [x] 고정 source/attempt/token/STARTED와 최신 P/UX를 입력 처리에서 확인한다. 진행 입력/예약 동작/자식 접근성/출처 dialog의 오래된 동작을 차단하며 SDK thread와 destroy 경계를 지킨다.
- [x] 허용 P에서 실제 지도 이동/확대와 출처 표시/읽기를 보존하고, BACK/닫기/STOP/재진입에서 묵은 입력을 실행하지 않는다.
- [x] 단위/제어 native, JDK17 전체 verify와 실제 Pleos 기본/확대 다크 P-D-P, 이전 지도 source/재생성/재시도 관련 흐름을 확인한다.
- [x] 같은 입력/불변 계측의 지도 진입 전후 처리 비용을 수집하고 원래 자료/권한/설정과 측정 원본을 보존한다. 성능 향상은 별도로 입증하지 않으면 주장하지 않는다.
- [ ] 최신 PR의 정확한 head CI/Codex/수동/정책을 충족해 main 반영, 해당 브랜치 정리/노션/README 정확히4사례 비교와 완료 계획을 정리한다.

## 구현 단계

1. 설치13.5.0 AAR ABI/동작을 읽고 제어 native RED와 원본 보존을 확인한다. 실제 지도 진입 기준값을 수집한 뒤 제품을 수정한다.
2. 공개 SDK만 사용하는 최소 입력/정리 수명 경계와 관련 검사를 구현한다. 새 화면 기능을 함께 늘리지 않는다.
3. 같은 조건의 비교/회귀/실제 화면을 검증하고 코드/실측/실패/미지원/과거 검증을 구분한다.
4. 최신 PR 리뷰/CI 이후 정상 병합/정리하고 사례 선정 이유를 기록한다.

## 변경 전후 측정

이번 목표는 확인된 입력 정확성 경계다. 지도 진입에 추가되는 경계의 처리 비용을 관찰하며 속도 향상을 가정하지 않는다. 이력 진입/펼침 계측의 과거 값이나 JUnit/Gradle 시간은 지도 성능으로 대체하지 않는다.

- 지표: 실제 MainActivity에서 home_map 클릭 직전→map_view 생성 후 동일 performScrollTo를 포함해 선택한 고정 시연 preview의 실제 SDK 완전 렌더/카메라와 map_ready/map_view가 실제 viewport에서 확인되고 Compose idle 뒤 재확인될 때까지 monotonic ms. 입력 전달/고정 스크롤/semantics/idle/실제 renderer 비용을 포함하며 순수 GPU/SDK 처리나 운전자 속도가 아니다.
- 기준 제품: d0385bc. 재현/계측 고정 revision9a8d8d0c706ce16008f975286b3fd6986b080358. 같은 메서드/endpoint를 수정하지 않고 비교에 사용한다.
- 조건: 기존 Android14/API34 Pleos/JDK17 debug, 원래 준비/종료9/관찰15/메모0과 원문 카탈로그/선택 preview, 실제 SDK P+UX, font1.0/라이트. 캐시를 지우지 않고 무거운 빌드와 동시 실행하지 않는다. MapLibre13.5.0/OpenFreeMap 원래 style/타일/출처를 사용한다.
- 순서/표본: 앱 자료 확인으로 정지된 상태에서 새 Activity,5회 워밍업 뒤30회. 매회 Home→지도/close→Home, 원래 준비/preview/실제 native source/카메라를 확인한다. 캐시/네트워크/OS/발열의 순차 실행 한계는 기록하며 전후 분포가 겹치면 고정 비용이나 개선으로 확정하지 않는다.
- 타이밍 밖: 실제 SDK gate/원본/카탈로그/환경/시연 source와 권한 대조, JSON, 닫기/전체 보존. 표본 endpoint는 실제 renderer 완료와 native source를 확인하며 HTTP200/UI tag만으로 성공을 만들지 않는다.
- 실패/제외: source/환경/원본 불일치, P/UX 거부,30초 endpoint 실패, 예외/원본 복구 실패는 실패로 남기고 중단. 누락0 대체/느린 표본 제거/완료 파일 덮어쓰기 금지. 실패/누락/미실행/제외를 분리한다.
- 비교: 전후 원값/표본 수/중앙값/Q1/Q3/IQR/min/max, 절대 차이/상대 변화와 캐시/타일/계측 한계. 기준0이면 상대율 없음. 대표 지도 진입 관찰을 모든 입력/실제 차량/release의 성능으로 확대하지 않는다.

## 검증 결과

| 명령 또는 시나리오 | 결과 | 근거 |
|---|---|---|
| JDK17 검사용 APK 빌드 | 성공 | 제품 d0385bc 유지, 계측/재현 고정9a8d8d0. 컴파일 경고2개는 플랫폼 getMapAsync의 nullable 해석으로 별도 정리 대상이다. |
| 실제 Route/native 입력 RED | 예상 결함 재현 | 실제 P의 수평 pan 이동과 camera 복원/같은 native View를 먼저 확인했다. 합성 무방출 D/옛 Ready P가 남은 뒤 같은 pan이 이동해 마지막 거부 assertion만 실패했다. 실제 DDS 전이 증거가 아니다. fixture 쓰기0과 이탈 정리 확인, 실패 원문은 비공개 보존. |
| 실제 SDK 지도 진입 기준 계측 | 성공 | 워밍업5/표본30, 실패/누락/미실행/제외0. 중앙값2127.5183965ms, Q1 2022.010292ms, Q3 2212.044752ms, IQR190.03446ms, min1823.575501ms/max2983.734043ms. 원본/환경/source/catalog/APK hash 확인, 완료 기준 raw는 ignored 비공개 파일과 앱 cache에 불변 보존. |
| 기준 실행 전체 원본 보존 | 성공 | 모든 테이블/행/필드/자료형/순서/sequence, grants/runtime/font/night/IME/proxy를 실행 전후 대조했다. 원래 준비/종료 기록/관찰/메모 유지. 소유 AVD와 forwarding을 정리한 뒤 제품 수정 시작. |
| 제품 타깃 첫 빌드 | 실패, 수정 후 재실행 예정 | SDK attribution manager setter의 Kotlin 속성 대입이 val 재할당 오류로 컴파일 중단. 공개 setAttributionDialogManager 직접 호출로 수정했다. 앱 실행 실패나 검증 통과로 기록하지 않는다. |
| 제품 타깃 재실행 | 부분 성공, 검사 APK 컴파일 실패 | 제품 debug와 지도 VM/입력 정책 검사45개는 성공. 추가 native 검사 정리 helper의 nullable SDK manager 접근으로 검사 APK 컴파일 중단. 실제 manager 존재를 checkNotNull로 요구하도록 수정하고 재실행한다. Native 실행 성공으로 기록하지 않는다. |
| 제품 타깃 최종 빌드 | 성공 | JDK17 debug/검사 APK와 지도 단위 검사 성공. 원래 native RED와 지도 진입 계측 소스는9a8d8d0과 byte 동일하다. |
| 동일 native RED 재실행 | 성공 | 제품81d0664에서 같은 실제 P pan 양성/복원/옛 P+live D/동일 View 비교가 통과했다. |
| 추가 제어 native 입력 | 성공 | 실제 P drag/pinch, 직접 native wheel P 양성/D 거부, D DOWN의 P MOVE/UP, STOP/재개 old stream/key/trackball, 실제 자식 접근성 클릭, 실제 dialog ListView의 늦은 URL listener를 확인했다.6개 모두 성공. URL은 ActivityMonitor로 실제 외부 실행을 차단한 시도 집계이며 실제 DDS 순서/하드웨어 mouse/모든 URL item 검증이 아니다. |
| 실제 네트워크 오류 첫 실행 | 미실행/건너뜀 | 별도 manualPreparedMapNetwork 대신 준비 지도 옵션을 전달해 JUnit assumption -4로 건너뛰었다. ignored runner가 OK 토큰만 보고 PASS로 오분류한 사실을 발견해 -4/Assumption도 거부하도록 수정하고 로컬 상태를 정정했다. 다른7개 실행 로그도 감사해 해당 건너뜀이 없음을 확인했다. 원래 로그는 보존하며 올바른 옵션으로 별도 재실행한다. |
| 실제 SDK 준비 지도 기본/확대 다크 | 성공 | 실제2개 준비 코스의 source/3점/카메라 교체, 재생성/닫기와 재진입/P-D-P/실제 잠금과 복귀를 font1.0/라이트와1.3/다크에서 각각 실행했다. 실제 앱 전면/화면을 보고 source 배지/점/출처/닫기 도달을 확인했다. 합성 위치가 실제 주행 경로라는 주장은 하지 않는다. |
| 실제 네트워크 오류 명시 재실행 | 성공 | 올바른 manualPreparedMapNetwork=true로 실제 remote tile request/error, FAILED/재시도 UI와 원래 preview 재렌더, proxy 정확 복원을 확인했다. 첫 건너뜀은 별도로 보존했다. host urllib preflight403은 SDK 실패가 아니며 curl로 공개 style200/JSON을 확인한 뒤 실제 앱 검사로 구분했다. |
| 실제 Main 지도 BACK | 성공 | 실제 전면 Main에서 보이는 지도 버튼과 고정 시연 설명을 확인한 뒤 KEYCODE_BACK으로 원래 홈 복귀를 확인했다. 원래 준비/기록 입력은 변경하지 않았다. |
| 비교 계측과 최종 원본 보존 | 성공 | 비교도 워밍업5/표본30, 실패/누락/미실행/제외0. 중앙값2000.331397ms, Q1 1907.143917/Q3 2107.241292/IQR200.097375/min1661.788917/max2668.382334ms. 차이−127.1869995ms/−5.97818565%이나 분포 겹침/순차 캐시 한계로 성능 향상 미확정. 기준 raw 불변, source/catalog/환경/원본 계약과 설치/빌드 APK hash를 대조했고 정제70행 CSV/재현 문서를 남겼다. 전체 SQLite 값/자료형/순서/sequence/grants/runtime/font/night/IME/proxy가 원본과 동일했고 소유 AVD/forwarding/탭을 정리했다. |
| `./scripts/verify.sh` | 성공 | JDK17 public-content/Spotless/모듈 경계/Lint, build-logic/JVM/Android 단위39suites481개 실패/오류/건너뜀0, debug와 unsigned release를 통과했다. Gradle/cache/JUnit 시간은 앱 성능으로 사용하지 않는다. |
| README와 실측 공개 QA | 성공 | 독립 읽기 검토에서 정확4/여섯 항목/13개 실제 연속 Kotlin 코드/유효 anchor, 다른3사례/브랜드 byte 보존, 정제 CSV의 원값→통계 재계산/비짝표본/잡음 한계/개인정보 비노출을 확인했다. README에 회귀/검사 수/JUnit/CI 설명을 넣지 않았다. |

## 구현 판단

직접 touchscreen pan/pinch/rotate/tilt를 유지하되 SDK 공개 UiSettings로 관성 velocity animation 전체, doubleTap/quickZoom을 비활성화한다. 늦게 실행되는 key/trackball camera 입력은 지원하지 않으며 BACK은 통과시킨다. 공개 즉시 wheel zoom 경로와 접근성 정보 탐색은 실제 SDK 동작에 맞춰 분리한다. compass는 guarded 즉시 bearing 복원으로 처리하고 출처 dialog는 매번 새로운 고정 owner를 가진 manager를 사용한다. 이 제약을 모든 입력 지원이나 SDK 예약 작업 전체의 무조건 최신 guard로 과장하지 않는다. 직접 입력은 처리 직전 live 값을 확인하고 수신 변화/STOP에서 Android pending input과 SDK 공개 cancel API를 정리한다.

로컬 독립 검토에서 첫 생성의 `onMapLoading=null`도 remember에 남는 복구 결함을 찾았다. D/P 수신이 UI 전달 전에 합쳐져 같은 attempt가 유지되면 native View 없이 spinner만 남을 수 있었다. 현재 reader/query/준비/attempt가 유효한 첫 할당만 거부 결과를 FAILED로 발행해 P 복귀 후 명시 재시도를 노출한다. FAILED attempt는 같은 할당 호출로 복구시키지 않고 retry가 만든 새 attempt만 허용한다. 관련 VM 검사는 미실행 상태로 추가했으며 성공 결과는 실제 실행 뒤 별도로 기록한다.

## 남은 문제와 인계

현재 PR 최신 head의 필수 CI/Codex 리뷰와 정상 병합/브랜치 정리가 남았다. 완료/승인으로 기록하지 않는다. 원래 PR8/10의 최종 리뷰 미확인과 ENV-06 독립 UX/플랫폼 오류 주입 차단은 이 수정의 전체 완료로 대신하지 않는다. 이번에는 위치/기본 내비/구간 선택/삭제를 구현하거나 권한을 추가하지 않았다. 실제 release 기기 실행/하드웨어 mouse 전달/모든 출처 URL item/같은 millisecond의 모든 물리 입력 구분은 확인 대상이 아니며 debug 측정을 일반 차량 성능으로 확대하지 않는다. 병합 뒤 실제 최종 상태를 확인해 이 계획의 마지막 조건을 닫고 completed로 이동한다. 최종 문서 이동은 런타임과 무관하여 새 성능 측정 대상에서 제외하고 경로/링크/공개 내용/필수 CI/리뷰를 검증한다.

## README 사례 선정

기존3번의 reader/query/renderer 소유권 설명에 실제 SDK 입력 경계를 연결한 사례로 강화했다. 기존3번은 취소/늦은 callback의 상태 오염을 설명했지만 실제 native camera의 P 양성 대조와 D 재현, 자식 접근성/출처 URL과 수명 경계의 직접 구현 판단은 없었다. 이번에는 같은 Route/SDK View를 관찰해 원인을 좁혔고 공개 SDK로 처리할 범위와 지연 camera 입력을 제한한 이유, 동일 계측의 분포/한계까지 확인해 플랫폼과 비동기 경계 판단을 더 구체적으로 보여 준다. 기능 최신성이나 코드/검사 총수로 우수성을 주장하지 않는다.1/2/4와 브랜드/목차/개요/실제 화면/스택/모듈/담당/나머지 순서는 byte 그대로 보존했다. 최종4개와 여섯 항목 순서, 실제 연속 코드3블록/현재 anchor, 기본 펼침을 확인했다. 지도 사례의 보조 JVM 집계 대상은 MapInputEpochTest로 갱신했으며 native 근거는 계획/PR에 분리한다.
