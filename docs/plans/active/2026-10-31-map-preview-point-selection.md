# 고정 시연 지점 선택과 지도 강조

- 상태: 진행 중
- 요구사항: MAP-03의 독립 제품 연결, APP-02와 검증된 MAP-02 고정 시연 preview

## 목표와 범위

주차와 UX 허용에서 이미 준비한 코스의 번호 지점을 큰 목록으로 선택하고 같은 native 지도 지점의 강조와 설명을 연결한다. 기존에는 번호/이름 Text만 있어 어느 지점을 살펴보는지 선택할 수 없었다. 고정 OSM 경계 중심은 도로 구간, 출입구, 목적지와 현재 위치가 아니다. native 지점 탭, 길안내, 실제 구간 선택, 위치 권한, 카메라 이동과 DB 변경은 이번 범위에 없다. MAP-03 전체는 미완료로 유지한다.

## 완료 조건

- [x] 두 현재 preview의 목록 선택, native ID 강조와 설명이 같은 지점을 가리킨다.
- [x] READY/live P+UX/STARTED와 고정 reader/query/attempt/token/source를 재확인하며 옛 callback과 거부 뒤 복귀를 새 의도로 승격하지 않는다.
- [x] source/View/retry/이탈/STOP과 재생성에서 선택을 폐기하고 로딩/오류/미확인 상태를 선택 성공으로 표시하지 않는다.
- [x] 기본 라이트와 확대 다크의 실제 Pleos 선택/P-D-P 및 원래 데이터/권한/설정을 확인한다.
- [x] JDK17 전체 verify와 관련 자동/실기를 확인한다.
- [ ] PR 최신 head의 필수 CI와 Codex 리뷰, 미해결 지적 없음과 정상 병합을 확인한다.

## 구현 단계

1. main 009a557과 명세, 진행 계획, 정확한 리뷰/CI를 확인했다. 새 지적 없음, 기존 PR8/10 완료 미확인은 별도 관리한다.
2. 아래 고정 지도 진입 비용의 기준값을 실제로 측정한 뒤 제품 코드를 변경한다.
3. 선택 경계와 native 강조를 연결하고 동일 측정 및 정확성 검증을 수행한다.
4. PR과 노션에 실제 결과/한계를 기록한다.

## 변경 전후 측정

- 관찰과 가설: 기존 번호 목록은 선택되지 않는 제품 연결 누락이다. 느리다는 관찰은 없어 속도 최적화로 주장하지 않는다. 새 선택 UI/렌더 속성의 기존 지도 진입 비용 영향을 관찰한다. 새 선택 자체는 기존 비교 동작이 없으므로 처리속도 개선율을 만들지 않는다.
- 지표/단위: home_map 클릭 직전부터 실제 native style/source/세 지점/fit camera와 Compose endpoint 확인까지 elapsedRealtimeNanos 기반 ms. 기존 MapOpeningMeasureTest 절차 그대로 사용한다. 작을수록 좋지만 인과 성능 향상은 분포/한계와 함께 판단한다.
- 기준 제품: 009a5571bd662269d53b2ba35c7fe953ed42de39. 지원 harness는 저장 namespace만 새 map-point-selection-entry로 분리해 제품 변경 전 커밋으로 고정한다. 비교 제품 커밋은 구현 후 기록한다.
- 동일 조건: 기존 실제 준비/카탈로그/전체 DB 고정, 원래 활성 세션 없음, Pleos API34/debug/JDK17.0.21, font1.0/라이트, 실제 SDK P+UX. 기존 로컬/설치 APK hash와 harness revision을 외부 기록하고 앱 내부 선언과 대조한다.
- 순서: 기준 이후 비교, 각 단계 별 새 Activity/process에서 5회 워밍업과 30회 웜 반복. 타일/네트워크/OS/JIT 캐시는 삭제하지 않고 절차/환경 readback과 함께 보존한다. 콜드 앱 시작/순수 GPU/SDK 시간과 혼동하지 않는다. 순차 실행의 캐시/열/네트워크 편향을 한계로 남긴다.
- 실패/제외: endpoint 30초, live gate/source/environment 불일치나 데이터 변경은 실패. 실패/미실행/누락/제외를 별도 집계하고 유리한 표본만 재선택하지 않는다. 완료 원값을 덮지 않으며 새 실행 namespace에 원값과 SHA를 보존한다.
- 원값/재현: ignored 실행 기록의 baseline/comparison JSON, 공개 문서에는 식별자를 뺀 timing-only CSV와 표본 수/중앙값/Q1/Q3/IQR/min/max/절대차/상대차만 기록한다. baseline0이면 개선율 없음.

| 항목 | 변경 전 | 초기 변경 후4a34b6b |
|---|---|---|
| 실제 표본 | 워밍업5/웜30 실제 성공 | 워밍업5/웜30 실제 성공 |
| 중앙값과 분포 | 1828.1850635ms, Q1 1741.122167, Q3 1871.021584, IQR 129.89941700000008, min 1475.535126, max 2279.401668 | 1822.930397ms, Q1 1756.650209, Q3 1886.699293, IQR 130.049084, min 1636.833334, max 2543.343501 |
| 실패/누락/제외 | 0/0/0, 미실행0, zero-duration0 | 0/0/0, 미실행0, zero-duration0 |

## README 사례 비교

현재 네 사례는 native 입력 세대, 파괴적 삭제의 CAS/원자성, 상태 출처와 마이그레이션 경계를 입증한다. 새 지점 선택은 기존 입력/출처 계약을 사용하는 연결이며 아직 이들보다 강한 문제/원인/재현 결과가 없다. 완료 후 다시 비교하고 근거가 없으면 네 사례와 순서를 보존한다. 새 공개 화면/빈 이미지/테스트 집계는 추가하지 않는다.

## 검증 결과

| 명령 또는 시나리오 | 결과 | 근거 |
|---|---|---|
| 최신 체크리스트/소유권/리뷰/공식 안내 | 확인 | 필수19/54, 선택3 제외, main CI4 성공, 새 지적/공식 변경 없음 |
| ./scripts/verify.sh | 최종 통과 | Temurin17.0.21+9, 품질/경계/Lint/42 suites567개/실패/오류/건너뜀0/debug/unsigned release. cache/up-to-date 포함, 별도 최종 계측 APK 통과 |
| 실제 선택/P-D-P/설정/보존 | 최종 두 환경 통과 | 실제 Main/SDK 기본 라이트와 확대 다크 각1개 건너뜀0. 초기2회 timeout은 별도 실패로 보존. 최종 raw SQLite와 권한/runtime/설정 정확 복원, 소유 AVD/forward 종료 |

## 남은 문제와 인계

MAP-03 실제 도로 구간 선택과 MAP-02 전체, 기존 독립 UX/SDK 오류 주입과 PR8/10의 원래 최종 리뷰는 별도 미완료다. 선택 가능하다는 사실을 실제 도로/능력 검토 성과로 쓰지 않는다.

- 변경 전 실행 완료: 고정 지원75b37af의 계측으로 실제 baseline을 먼저 확보한 뒤 제품을 변경했다. 설치/로컬 debug 및 계측 APK SHA와 완료 원값을 대조해 보존했고 원래 schema4 모든 값/자료형/순서/sequence/권한/설정을 대조했다. baseline 중앙값은 신규 선택의 속도가 아니다.
- 독립 코드 검토의 native 첫 거부 경계를 반영해 canInteractWithMap도 기존 선택을 폐기한다. 선택 노드는 고정 renderer/source/STARTED별 key를 사용하며 옛 semantics action은 새 onClick을 빌리지 않는다. 수정 전 native RED는 아직 실행하지 않았으므로 재현 성공으로 쓰지 않는다.
- JDK17 map JVM 검사88개(기존포함, 실패/오류/건너뜀0), 제품/계측 APK 빌드 통과. 전체 verify와 새 실기는 후속이다.

- 동일 조건 비교 완료: 4a34b6b 제품/고정75b37af harness, 중앙값 변화 -5.2546665ms(-0.287425%). 두 IQR 약130ms와 겹치는 분포/순차 실행 편향으로 성능 향상을 확정하지 않는다. 원값과 재현/한계는 [선택 연결과 측정](../../development/map-preview-point-selection.md)에 기록했다.

- 최초 최종 format/전체 verify는 새 native 검사 한 줄 if의 ktlint wrapping 오류로 실패했다. 계측 APK 컴파일은 별도로 통과했지만 전체 성공으로 계산하지 않았다. 해당 구문을 여러 줄로 정리한 뒤 set-e로 단계별 중단을 보장해 공식 전체 검사를 다시 실행한다.

- 수정 뒤 JDK17 공식 전체 verify의4단계/42 suites567개/실패/오류/건너뜀0/debug/unsigned release와 계측 APK가 통과했다. 이전 검사 cache/up-to-date 포함이며 신규26개VM+2개필터 검사는 실제로 실행했다.
- 실제 기본1.0/라이트 첫 실행은 첫 지점 선택 뒤 native readback30초 timeout으로 실패했다. DDS 단계 전이며 차량 전환은 실행하지 않았다. 실패 직전 실제 foreground 일치/map_ready1/선택 설명1/pending0과 private PNG를 보존했다. native 강조/검사 가정 중 어느 원인인지 미확정이며 이 실행을 성공으로 계산하지 않는다. 제품 predicate를 완화하지 않고 상세 native 진단을 보강해 원인을 확인한다.

- 별도 제어 native RED를 실제 재현했다: 같은 Activity/STARTED/코스/preview에서 synthetic 과제만 변경하고 실제 새 View/attempt/token을 확인했다. 옛 Semantics OnClick 실행 전 선택 없음이었으나 실행 뒤 선택/설명이 생겨 엄격 assertion이 실패했다. 실제 SDK/사람 입력이 아닌 합성 준비와 실제 Route/MapLibre의 경계 재현이다. 원인은 onSelect 내부의 delegated state getter가 호출 시 최신 tuple을 읽는 것이었고, callback 구성 전에 plain displayedAttempt/displayedRendererToken을 고정하도록 수정했다. keyed 노드만으로 이 getter 재평가를 막는다고 가정하지 않는다. GREEN과 새 최종 제품 비교는 후속이다.

- 최종 비교를 한 번 더 수행하도록 계획을 갱신한다. 4a34b6b의 첫 비교35개는 성공한 별도 제품 관찰값으로 원형/해시와 공개 timing을 모두 보존한다. plain tuple 캡처 수정 제품은 아직 측정하지 않았다. 고정75b37af의 계측 파일을 그대로 사용하고 baseline은 변경하지 않는다. 완료된 첫 comparison cache는 소유 archive의 새 이름으로 byte 그대로 옮겨 원값을 덮지 않으며, 최종 제품의 새 comparison을 같은 기본1.0/라이트/실제P+UX/원래자료/새process/5+30/캐시삭제없음 조건으로 수집한다. 첫 비교와 최종 비교는 다른 제품이며 어느 쪽 표본도 유리하게 제외하지 않는다. 추가 실행 순서/캐시/발열 편향을 한계로 남긴다. 최종 소스와 APK를 별도 기록하고 마지막 제품 측정 전에는 기존 측정의 성능 향상을 주장하지 않는다.

- 실제 동일 제어 native GREEN이 통과했다. 옛 callback 뒤 선택/설명/pending 모두 없음과 fresh callback의 실제 native ACK/같은 제목을 확인했다. 실제 bound style probe1개도 즉시와500ms 뒤 setter/같은객체/typed native3경로가 일치했고 원래falsefilter를 복원했다. Java cached getter도 이 probe에서는 함께 일치했으므로 첫 timeout을 cache 불일치 원인으로 확정하지 않는다. ABI의 cache 경로 차이는 사실이며 Flow 검사에서는 current native getLayerAs로 엄격 조회한다. 실제 SDK 시나리오 awaitNative의 Compose 동기화 누락을 별도 확인 중이다.

- 최종 실제 기본/확대 다크 각각1개가 건너뜀 없이 통과했다. 3지점/native filter/설명/카메라불변, lifecycle/옛STOPaction거부/fresh선택/실제DDS D-P를 확인했다. 각 환경 map 일부 clip/비포커스 조건은 실제 관찰했고 완전 offscreen은 미실행이다. private 실제20PNG 중 지도/선택/설명6개를 직접 시각 검토했다. 두 환경의 원래 SQLite 전체 값/자료형/순서/seq와 권한/runtime/설정의 정확 보존도 확인했다.
- 초기 native timeout2회는 새 하네스의 current-native조회/Compose idle/승인deadline 보강 전 실패로 보존한다. 두 변경의 단일요인 분리 실험은 실행하지 않았으므로 특정 원인 또는 앱성능결함으로 확정하지 않는다. 실제 getter probe에서는 cache도일치했다. 기본 내비 전면은 dark계측 시작 전에 관찰/재실행해 앱전면/실제P+UX를 확인한 전처리이며 성공/실패시나리오로 임의 집계하지 않는다.

## 최종 제품의 추가 동일 조건 비교

최종6b001b9를 고정75b37af harness로 실제 재측정했다. 각5회 워밍업/30회 웜 성공, 실패/누락/제외/미실행/0 duration은 모두0이며 baseline/환경/카탈로그/입력/원래전체자료/로컬/설치APK hash 일치를 확인했다. 초기4a의70행은 값/상태를 그대로 두고 최종35행을 추가해105행으로 보존했다.

| 최종6b 관찰 지표(ms) | 값 |
|---|---:|
| 중앙값 | 1857.8786680 |
| Q1 / Q3 / IQR | 1778.456501 / 1988.665542 / 210.209041 |
| min / max | 1561.821918 / 2737.530543 |
| 최종−기준 중앙값 변화 | +29.6936045ms / +1.624212% |

관찰상 최종 중앙값은 더 느렸고 초기4a의−5.25ms를 최종 성과로 쓰지 않는다. 분포 겹침과 IQR/순차 실행/추가 검증 뒤 캐시/발열/네트워크 편향으로 성능 향상, 인과 저하와 동등성/무회귀를 확정하지 않는다. 다른 부하/release/콜드는 미측정이다. 자세한 조건/원값은 개발 문서에 남겼으며 README4사례의 기존 성능 수치를 이번 선택 속도로 바꾸지 않는다.

- 두 번째 production catalog preview의 실제 제어 native1개가 통과했다. 합성 준비/P/UX이지만 실제 현재 source/세 지점/bound filter/같은 제목/ACK/같은View/카메라불변을 모두 확인했다. 실제 차량 SDK 두 코스를 대신하지 않는다. 초기 current preview Main/SDK 두 환경, 동일 preview 과제 교체 GREEN, SDK style probe와 구분한다. 기존 MapInputLifecycleTest 전체를 이번 실행 통과로 소급하지 않는다.
- 마지막 full verify4단계와42 suites567개/실패/오류/건너뜀0/빌드/계측 APK가 통과했고, 실제로 측정/실기한 최종 앱 APK와 Main 선택 하네스 파일이 그대로임을 hash로 대조했다. 모든 최종 실행 뒤 원래 물리 데이터/권한/설정을 다시 확인하고 소유 AVD/forward/DDS탭만 정리했다. PR의 최신 CI/리뷰/병합은 아직 별도 미완료다.
- 최종 README 비교: 새 delegated State 사례는 actual tuple을 통제한 native RED/GREEN으로 원인 규명을 보강하지만 기존3번과 같은 소유권 축이다. 단독 교체하면 현재3번의 gesture/child dispatch/SDK 예약 취소/접근성 판단 범위가 줄어든다. 1번의 상세 입력 fail-closed, 2번의 파괴적 CAS/Room 원자성, 4번의 SQLite 자료형 보존이 입증하는 별도 역량을 대체할 근거도 없다. 네 사례/순서/나머지 문장은 유지하고 바뀐 실제 guard 발췌/앵커만 맞췄다. 독립 감사에서4개/여섯순서/실제 연속코드17개/유효앵커/기존이미지18개 및 비노출을 확인했다. 단순 최신성/코드량/테스트수로 새 사례를 더 낫다고 선언하지 않는다.
- 문서 링크/배치와 검증 기록 정리는 앱 실행 성능 변경이 아니므로 별도 성능 측정 적용에서 제외한다. 기존70개 timing 값/상태 보존, 최종105개 raw와 통계 재계산, 문서 링크/실제 source/형식/민감정보를 독립 확인했다. 측정과 정확성 근거는 이 계획/개발 문서/PR/CI에 두고 README에는 회귀 집계를 넣지 않는다.
