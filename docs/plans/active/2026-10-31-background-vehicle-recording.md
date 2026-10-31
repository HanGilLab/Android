# 내비 전면 중 차량 기록과 중단 표시

- 상태: 진행 중, 변경 전 계획과 기준 계측 지원 고정
- 요구사항: APP-05의 차량 상태 기록 유지/중단 표시. 위치/경로/실제 도로 검증과 기존 활성 안내 보존은 포함하지 않는다.

## 목표와 범위

현재 MainActivity의 STARTED 수명만 수집기를 소유해 내비 전면의 STOP에서 기록이 멈춘다. 현재 domain recorder/Room append의 고정 세션/순차 쓰기/취소/종료 트랜잭션과 singleton 차량 SDK를 재사용한다. 기본은 기존 전면 수집이고, 활성 연습 중 주차와 UX 허용을 확인한 명시 동작으로만 내비 중 기록을 켠다. 서비스는 새 세션을 자동 채택하거나 재시작하지 않는다. 일반 전면 수집과 서비스 수집의 소유권을 하나로 직렬화한다.

Android의 [FGS 종류](https://developer.android.com/develop/background-work/services/fgs/service-types), [시작/승격](https://developer.android.com/develop/background-work/services/fgs/launch), [사용자 앱 종료](https://developer.android.com/develop/background-work/services/fgs/handle-user-stopping)를 확인했다. 차량 IPC는 connectedDevice의 외부 장치 용도를 충족한다고 확인되지 않아 더미 전제 권한을 넣지 않는다. specialUse와 use-case subtype은 적합한 후보라는 추론이며 Pleos/스토어 심사 승인을 뜻하지 않는다. normal FOREGROUND_SERVICE/FOREGROUND_SERVICE_SPECIAL_USE만 추가하고 POST_NOTIFICATIONS/위치/기타 dangerous 권한은 요청하지 않는다. [알림 권한](https://developer.android.com/develop/ui/compose/notifications/notification-permission)과 FGS 실행/알림 표시를 구분한다. 현재 Pleos API34에서 실제 승격/내비 전면/SDK 저장을 검증하기 전에는 지원 성공으로 세지 않는다. [Vehicle 안내](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/Vehicle/intro)는 IPC/initialize/release만 설명하며 백그라운드 수집 보장을 확인하지 못했다.

run marker를 schema5의 별도 FK 자료로 저장한다. OPEN, 정상 중단, 실패, 이전 프로세스 종료 미확인을 구분하고 실제로 알지 못한 종료 시각/누락 수를 만들지 않는다. 이전 프로세스 OPEN은 새 프로세스에서 INTERRUPTED/null end로 표시하고 자동 BG 재개는 하지 않는다. ended 삭제의 확인 snapshot에도 marker를 포함해 확인 뒤 변경과 삭제 뒤 늦은 정리가 자료를 되살리지 않게 한다. 위치와 경로/차량 제어 API, 작업 관리자나 보호 규칙 우회는 추가하지 않는다.

## 완료 조건

- [ ] 기존 전면 동작 유지, P+UX/표시한 세션 snapshot/RESUMED 호스트/요청 token을 확인한 명시 enable만 FGS로 전환한다. 거부/이탈/옛 intent/다른 세션은 새 실행을 빌리지 않는다.
- [ ] 단일 소유권과 cancel/join으로 화면/서비스 중복 수집을 막고, 종료/교체/읽기 실패/사용자 중단 뒤 늦은 append나 자동 BG 재시도가 없다.
- [ ] foreground 승격 성공 뒤만 서비스 수집하며 START_NOT_STICKY, 적절한 알림, 현재 실행/실패/중단 미확인과 실제 SDK/앱 관찰 범위를 표시한다. 실제 DDS D를 기록하되 상세 입력은 계속 잠근다.
- [ ] v4 기존 모든 행/물리형/순서/sequence를 보존하고 FK/실제 타입/업데이트 소유권/취소 rollback/삭제 CAS를 검증한다. null end를 정확한 공백 시간이나0으로 바꾸지 않는다.
- [ ] JDK17 verify와 변경 단위/실제 SQLite/제어 native, 실제 Pleos 기본과 확대 다크의 내비 전면 SDK 기록/복귀/종료, 강제 프로세스 종료 뒤 중단 미확인과 명시 재개를 구분해 확인한다.
- [ ] 기준과 비교의 같은 입력/계측/조건 원값/표본/분포/실패/제외를 보존하고 새 수집의 SDK 속도 향상으로 바꾸지 않는다.
- [ ] README4개와 새 근거를 비교해 근거 있는 사례만 교체하고 지정 여섯 항목/실제 펼침 코드/브랜드/기존 화면을 유지한다. 새 공개 사진은 만들지 않는다.
- [ ] 최신 PR 필수 CI/Codex/관련 실제 검증/미해결/정책 뒤 정상 병합하고 실제 head/tree/main ff-only/브랜치 정리와 Notion readback을 확인한다.

## 구현 단계

1. 최신 체크리스트/AGENTS/진행 계획/소유권/PR/CI/공식 안내/설치 상태를 확인한다.
2. 제품 ebd9ebd의 고정 계측에 격리 namespace만 추가하고 기준 원값을 실행한다.
3. domain/DB의 run marker와 v5 migration/삭제 snapshot, 단일 controller와 FGS/주차 UI를 연결한다.
4. 같은 조건 비교와 관련 검증, 실제 화면/자료 보존/중단 검증 뒤 PR와 Notion에 사실과 한계를 반영한다.

## 변경 전후 측정

- 관찰/가설: 내비 전면에서 STARTED가 끝나 수집이 멈춘다. 속도 병목은 관찰하지 않았다. opt-in하지 않은 기존 이력 진입에 연결 비용이 생기는지만 관찰하고, 새 FGS 수집은 동등한 이전 기능이 없어 속도 개선율을 만들지 않는다.
- 기준/지표/단위: ebd9ebd859b8a90386bb5d74a58fab2e397ba068의 home_history 클릭 직전→같은 Ready/원문/Compose idle endpoint elapsedRealtimeNanos/ns와 ms. dispatch/검사/동기화 비용을 포함하며 앱 전용 frame/GPU/SDK 지연이나 운전자 시간이 아니다.
- 같은 조건: JDK17.0.21+9/debug/PleosAPI34/font1.0/라이트/같은 현재 사용자/기존 준비1/종료11/관찰17/메모1/활성 없음/같은 catalog/source/실제 SDK P+UX 허용. 양쪽 frozen 메서드와 background-recording-entry-v1, 실제 설치 APK/hash/원래 입력을 대조한다. 새 서비스와 검사 소유 세션은 양쪽 측정 뒤에만 켠다.
- 워밍업/표본/순서: B1→F1, 각 fresh process/Activity 후 같은 프로세스5warm+30본의 열기/닫기. 시작/스크롤/닫기/export/캡처/대조는 timer 밖. 정수 ns 원값을 보존하고30본의 중앙값/Q1/Q3/IQR/min/max를 구한다. Q1/Q3는 하위15/상위15 중앙값이다.
- 실패/제외: 고정20초 초과/원본/endpoint/P/UX/입력 불일치는 실패다. 느린 값을 제외하지 않고 실패/누락/미실행/제외를 따로 기록한다. 기준0이면 상대율을 만들지 않는다.
- 한계: 데이터/Tile/DB/코드 캐시를 지우지 않으며 APK/ART/profile/JIT/OS/열/순서/네트워크 영향을 분리하지 못한다. 겹치는 분포로 향상/인과 저하/동등성/무회귀를 확정하지 않는다. 이 비교는 서비스 중 SDK 처리 성능/실제 도로/콜드/release의 측정이 아니다. v5의 새 빈 marker 테이블과 normal FGS 권한은 의도한 제품 차이로 분리한다.
- 보존/공개: 원래 DB/설정/권한/원형/기기/내비 원본과 로그는 ignored 비공개에만 둔다. 공개에는 익명 timing과 재현 조건만 남긴다. 서비스 검증의 새 자료는 명시적인 검사 소유 세션만 사용해 원래 모든 행과 구분한다.

| 항목 | 변경 전 | 변경 후 |
|---|---|---|
| warm/본 표본 | 5/30 | 미실행 |
| 중앙값/분포 | 324.838167ms, Q1/Q3 307.284291/363.237208ms | 미측정 |
| 실패/누락/제외 | 0/0/0 | 미확인 |

문서의 상태/배치는 앱 실행 성능과 무관해 새 측정 적용 제외이며 링크/발췌/렌더/공개 콘텐츠로 검증한다. 검사 수와 저장 행 수는 정확성 근거이며 성능 수치가 아니다.

## 검증 결과

시작 main ebd9ebd clean/동일 프로젝트 다른 활성 채팅 없음/원자 lease 획득을 확인했다. 기존 소유 AVD/forward는 종료/자료 복구 상태다. 전체63PR 담당 누락0/열린 PR0, main 동일 SHA App15368 필수4개 success, PR62/63 exact head Codex completed/지적 없음이다. 원래 PR1/8/10 최종 head 리뷰 미확인은 별도 유지한다. actual SDK의 독립 UX/오류 주입 차단과 기존 활성 길안내 보존은 이 구현의 선행 성공으로 대신 기록하지 않는다.

## README 선정과 남은 범위

기존4개는 live/source/수명, 파괴적 CAS/rollback, 조회/렌더/native 입력, SQLite 물리형 경계다. 새 단일 소유권/프로세스 미확인/SQLite/실제 내비 수집의 근거를 확보한 뒤 영향/원인/대안/본인 판단/재현 결과를 비교한다. 테스트 총수/코드량/최신성으로 교체하지 않으며 확인 전에는 기존4개/순서를 유지한다.

전체 APP04의 위치/실제 경로, MAP04 목적지/경유지/길안내와 기존 활성 안내 보존, 독립 UX 오류/실제 도로/스토어 승인은 미완료다. 시스템 강제 종료 시각/놓친 SDK 이벤트 수와 StateFlow에서 생략된 모든 중간/동일 상태를 추정하지 않는다.

## 변경 전 실제 기준

고정 계측04dc200의 격리 namespace에서 실제5warm/30본, 실패/누락/제외0을 완료하고 원형/설치 APK/hash/frozen 메서드를 비공개로 보존했다. 기준 중앙값324.838167ms, Q1/Q3 307.284291/363.237208ms, IQR55.952917ms, min/max243.064792/853.199667ms다. 느린 값도 제외하지 않았다. 실제 DDS D/RESTRICTED와 P/UNRESTRICTED 프로브를 각각 확인했다. D 프로브 종료 뒤 기본 내비가 전면인 장면은 이후 Main 계측 성공과 구분한다. 전체 원래 DB/물리형/순서/sequence/권한/설정 보존을 대조하고 소유 AVD/forward를 종료한 뒤 제품 구현을 시작한다. 이번 공식7페이지/표시문/링크/3공지/빈 양식55208bytes/SHA가 모두 이전과 같아 요구사항 변경은 식별하지 않았다.

## 초기 대상 검사와 검토

첫 대상 Gradle 실행은 RoomNextPracticeRepositoryTest의 현재 DB 버전 기대값4/실제5에서 실패했다. 새 저장소12개/마이그레이션2개/기존 삭제27개의 XML은 실패/오류/건너뜀0이지만 전체 성공으로 세지 않는다. 같은 현재 버전 fixture와 등록을5로 맞추고 기존 v3 경계 기대값은 유지했다. 다른 모듈의 과거 XML을 이번 명령 실행으로 합산하지 않으며 최신 대상 검사를 다시 실행한다. UI 초기 정적 검토에서는 배경 state의 표시 당시 receipt를 일반 val로 고정해 callback이 delegated 최신 상태를 다시 읽지 않게 했고 FGS 실패 중 일반 전면 retry가 숨은 재개가 되지 않게 구분했다. 실제 native/서비스/비교 검증은 아직 미실행이다.

통합 대상 단위/앱 debug 컴파일은 통과했다. 첫 SpotlessApply와 이어 실행한 전체 verify는 controller/화면 VM의 긴 줄과 마이그레이션 fixture의 한 줄 복합 if 서식에서 중단됐다. 같은 소스의 실제 계측 APK 빌드는 통과했으나 이를 전체 검사나 native 성공으로 세지 않는다. 규칙을 유지해 서식을 정리한 뒤 전체 검사를 재실행한다.

## 제품 연결과 최종 검사 준비

첫 재검사의 품질/단위/debug/unsigned release와 실제 계측 APK 빌드가 모두 통과했다. 이후 마지막 변경으로 현재 기록 조작을 지난 연습보다 먼저 배치하고 안내를 기본 전면/명시 내비 기록으로 구분했다. 저장된 최근 FAILED는 프로세스 복귀 뒤 원인을 새로 추정하지 않는 PREVIOUS_RUN 경고로 표시하며 이전 INTERRUPTED 경고를 유지한다. 현재 source의 과거 경고와 실제 소유 실행 실패를 구분하고 source 교체/STOP에서 옛 경고를 해제한다. 전면 수집과 명시적인 새 시작은 계속 허용하되 자동 BG 재개는 하지 않는다. 이 추가 변경 뒤 전체 검사를 다시 수행하고, 같은 비교 계측을 마치기 전에는 검사 소유 연습이나 서비스를 시작하지 않는다.

실제 검증은 controlled lifecycle/receipt 경계와 actual SDK/Main/서비스/Room을 별도 실행한다. 기본 라이트와 확대 다크는 같은 검사 소유 세션을 재사용한다. 외부 force-stop 준비 fixture는 의도적인 instrumentation abort이며 JUnit 통과로 집계하지 않는다. 그 뒤 fresh process의 INTERRUPTED/null end, 자동 재개 없음, 새 명시 시작/중단/소유 연습 종료만 별도 recovery 검사에서 판정한다. 원본 모든 자료는 설치 직후 schema5 기준과 종료 후 실제 추가 소유 자료를 분리해 물리형/순서/sequence/권한/설정까지 확인한다.

독립 제품 검토에서 active 조회가 null이 되는 정상 종료/조회 실패 뒤 owned run 정리 실패 안내와 stop이 숨는 결함을 찾았다. source가 없어도 남은 owned run의 상태와 정확한 token 정리를 표시하고 새 enable은 숨긴다. 표시 snapshot/P+UX/RESUMED 수명과 receipt는 유지하며 null source의 오래된 enable/stop/gate/PAUSE/복귀를 제어 native로 추가 확인한다. 그 직전 전체760개 XML은 실패/오류/건너뜀0과 양 빌드 성공이나 이 추가 변경의 검증을 대신하지 않는다.
