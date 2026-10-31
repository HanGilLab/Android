# 내비 전면 중 차량 기록과 중단 표시

- 상태: a2cf9e2 실기와 환경 복원 완료, 최신 리뷰의 두 경계 수정/최종 재검증 진행
- 요구사항: APP-05의 차량 상태 기록 유지/중단 표시. 위치/경로/실제 도로 검증과 기존 활성 안내 보존은 포함하지 않는다.

## 목표와 범위

현재 MainActivity의 STARTED 수명만 수집기를 소유해 내비 전면의 STOP에서 기록이 멈춘다. 현재 domain recorder/Room append의 고정 세션/순차 쓰기/취소/종료 트랜잭션과 singleton 차량 SDK를 재사용한다. 기본은 기존 전면 수집이고, 활성 연습 중 주차와 UX 허용을 확인한 명시 동작으로만 내비 중 기록을 켠다. 서비스는 새 세션을 자동 채택하거나 재시작하지 않는다. 일반 전면 수집과 서비스 수집의 소유권을 하나로 직렬화한다.

Android의 [FGS 종류](https://developer.android.com/develop/background-work/services/fgs/service-types), [시작/승격](https://developer.android.com/develop/background-work/services/fgs/launch), [사용자 앱 종료](https://developer.android.com/develop/background-work/services/fgs/handle-user-stopping)를 확인했다. 차량 IPC는 connectedDevice의 외부 장치 용도를 충족한다고 확인되지 않아 더미 전제 권한을 넣지 않는다. specialUse와 use-case subtype은 적합한 후보라는 추론이며 Pleos/스토어 심사 승인을 뜻하지 않는다. normal FOREGROUND_SERVICE/FOREGROUND_SERVICE_SPECIAL_USE만 추가하고 POST_NOTIFICATIONS/위치/기타 dangerous 권한은 요청하지 않는다. [알림 권한](https://developer.android.com/develop/ui/compose/notifications/notification-permission)과 FGS 실행/알림 표시를 구분한다. 현재 Pleos API34에서 실제 승격/내비 전면/SDK 저장을 검증하기 전에는 지원 성공으로 세지 않는다. [Vehicle 안내](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/Vehicle/intro)는 IPC/initialize/release만 설명하며 백그라운드 수집 보장을 확인하지 못했다.

run marker를 schema5의 별도 FK 자료로 저장한다. OPEN, 정상 중단, 실패, 이전 프로세스 종료 미확인을 구분하고 실제로 알지 못한 종료 시각/누락 수를 만들지 않는다. 이전 프로세스 OPEN은 새 프로세스에서 INTERRUPTED/null end로 표시하고 자동 BG 재개는 하지 않는다. ended 삭제의 확인 snapshot에도 marker를 포함해 확인 뒤 변경과 삭제 뒤 늦은 정리가 자료를 되살리지 않게 한다. 위치와 경로/차량 제어 API, 작업 관리자나 보호 규칙 우회는 추가하지 않는다.

## 완료 조건

- [x] 기존 전면 동작 유지, P+UX/표시한 세션 snapshot/RESUMED 호스트/요청 token을 확인한 명시 enable만 FGS로 전환한다. 거부/이탈/옛 intent/다른 세션은 새 실행을 빌리지 않는다.
- [ ] 단일 소유권과 cancel/join으로 화면/서비스 중복 수집을 막고, 종료/교체/읽기 실패/사용자 중단 뒤 늦은 append나 자동 BG 재시도가 없다.
- [x] foreground 승격 성공 뒤만 서비스 수집하며 START_NOT_STICKY, 적절한 알림, 현재 실행/실패/중단 미확인과 실제 SDK/앱 관찰 범위를 표시한다. 실제 DDS D를 기록하되 상세 입력은 계속 잠근다.
- [x] v4 기존 모든 행/물리형/순서/sequence를 보존하고 FK/실제 타입/업데이트 소유권/취소 rollback/삭제 CAS를 검증한다. null end를 정확한 공백 시간이나0으로 바꾸지 않는다.
- [ ] JDK17 verify와 변경 단위/실제 SQLite/제어 native, 실제 Pleos 기본과 확대 다크의 내비 전면 SDK 기록/복귀/종료, 강제 프로세스 종료 뒤 중단 미확인과 명시 재개를 구분해 확인한다.
- [x] 기준과 비교의 같은 입력/계측/조건 원값/표본/분포/실패/제외를 보존하고 새 수집의 SDK 속도 향상으로 바꾸지 않는다.
- [x] README4개와 새 근거를 비교해 근거 있는 사례만 교체하고 지정 여섯 항목/실제 펼침 코드/브랜드/기존 화면을 유지한다. 새 공개 사진은 만들지 않는다.
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
| warm/본 표본 | 5/30 | 5/30 |
| 중앙값/분포 | 324.838167ms, Q1/Q3 307.284291/363.237208ms | 339.4698955ms, Q1/Q3 306.209126/420.552417ms |
| 실패/누락/제외 | 0/0/0 | 0/0/0 |

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

## 동일 조건 비교 완료

제품 de6357f에서 고정된04dc200 계측을 각 제품의 실제 설치 APK hash를 대조하고 같은 원래 입력/환경/endpoint로 실행했다. 양쪽 각5warm/30본이며 실패/누락/제외0이다. 변경 후 중앙값339.4698955ms, Q1/Q3 306.209126/420.552417ms, IQR114.343291ms, min/max256.101333/2886.263751ms다. 중앙값 차이는 +14.6317285ms, 관찰 증가율 +4.50431322%이며 개선율이 아니다. Q1~Q3가 겹치고 변경 후 편차/최댓값이 크다. 느린 값을 버리지 않았으며 B1/F1의 캐시/ART/JIT/profile/열/OS/순서 영향을 분리하지 못하므로 향상, 인과적 저하, 동등성이나 무회귀를 확정하지 않는다. [익명 원값 CSV](../../development/measurements/background-recording-entry-ui-times.csv)에70개 timing을 보존했다. 서비스 중 SDK 수신 성능과 새 수집 기능의 속도 향상은 측정하지 않았다.

전용 AVD의 boot 완료 전 설치 확인1회는 boot assertion에서 중단돼 설치가 실행되지 않았다. 실제 부팅 완료 뒤 갱신/실행을 다시 확인했다. 재부팅 뒤 기본 내비 전면은 Main 복원 증거로 사용하지 않았다. 기존 DDS 오류 탭의 재사용은 실패해 새 소유 탭에서 DDS ONLINE을 확인하고 actual D/RESTRICTED와 P/UNRESTRICTED 프로브를 각각 통과했다. HTTP200만으로 SDK 연결 성공을 세지 않았다. schema5 설치 직후 새 빈 run table/normal FGS2개 외 원래 모든 행/값/물리형/순서/sequence/runtime권한/설정을 정확히 대조했다.

## 제어 native 최초 실패와 검사 범위 분리

실제 ActivityScenario 수명 제어를 사용한 최초6개는5통과/1실패였다. foreign source/폐기 시나리오의 재표시 대기 중 actual host STOPPED가 관찰됐고 finally의 RESUMED 전환도 성립하지 않았다. 동일 APK의 해당 항목 단독 재실행1개는 통과했다. 원래 전체6개를 다시 실행했을 때4통과/2실패로, STARTED 요청 미성립(last RESUMED)과 foreign stop 조회 직후 노드 소실이 있었다. 사후 로그에는 최초 실패 당시의 연속 상태가 없어 환경/UX/본체 원인을 확정하지 않으며 모두 별도 실패로 보존한다. 재시도 결과로 원래 실패를 지우지 않는다.

제어 검사의 단위를 실제 Compose UI와 명시적인 synthetic LifecycleRegistry/source/controller/gate로 좁힌다. ActivityScenario로 실제 OS PAUSE를 만들었다고 주장하지 않고, 같은 여섯 의도/옛 token 거부/gate/source/폐기/정확 run 조건을 유지한다. 실제 OS Main STOP/복귀와 서비스/SDK/Room은 변조하지 않은 별도의 actual 내비 시나리오에서 확인한다. 이 수정은 검사 harness에만 적용하며 제품 APK와 완료된 비교 원형/원값을 바꾸지 않는다. 원래 자료/설정의 schema5 exact 대조와 소유 AVD 종료를 마친 뒤 JDK17로 해당 검사 APK/품질을 빌드한다.

제어 harness v2(a24db27)는 품질/APK 빌드를 통과했고 de6357f 제품 APK hash와 frozen 계측 소스가 그대로임을 확인했다. v2 전체6개 중5개 본문은 통과했고 표시 receipt 항목은 ActivityScenarioRule.before의 launchInternal에서 시작 상태 확인 실패로 본문에 진입하지 못했다. 같은 v2 APK의 해당 항목 단독1개는 통과했다. 따라서 여섯 제어 정책은5개 batch+1개 단독 실행으로 각각 확인했으며, 단일 전체 batch를 모두 통과했다고 쓰지 않는다. 실제 OS Activity 시작의 반복성 한계와 이전 실패들은 남겨 두고 실제 Main/FGS/SDK 시나리오는 별도 실행한다.

## 실제 FGS 부분 확인과 내비 API 분리

실제 라이트 flow 두 시도에서 native FGS1개, Active와 같은 OPEN을 확인했지만 앱 내비 버튼 입력 뒤 실제 내비 전면을 확인하지 못해 두 시도 모두 실패했다. 실패 순간 Main은 RESUMED/전면이고 서비스/OPEN은 유지됐다. 실패 정리는 그 소유 run만 STOPPED로 닫고 연습을 종료하지 않았다. 소유 캐시가 있으면 새 세션 생성 분기에 들어가지 않고 같은 소유 세션을 재사용함과 원본 행 보존/서비스 부재를 읽기 전용으로 확인한 뒤 재시도했다. 자동 승인 검토의 첫 재실행 거절은 중복 세션/오염 우려였으며, 이 소유권/재사용 근거 확인 뒤 재실행이 허용됐다. 삭제나 검사 성공으로 바꾸는 우회는 하지 않았다.

시간 제한이 없는 실제 수동 UI에서 P/주차 확인과 현재 소유 연습을 확인하고 기록 켜기/끄기를 실행해 세 번째 진단 run의 native 시작/명시 중지를 확인했다. 내비 버튼을 화면 안에 완전히 둔 native tap은 SDK의 REQUESTED 안내를 표시했지만 Main이 전면에 남았다. SDK 요청과 실제 화면 전환을 구분하며 MAP04의 이번 실제 API 전환은 미확인이다. 원인과 동작을 최종 SDK 성공으로 쓰지 않는다.

APP05의 차량 상태 수집은 이 내비 SDK API를 호출하지 않아 독립적으로 확인할 수 있다. 후속 actual 검사에서는 외부에서 확인한 실제 내비 component를 명시한 OS launcher intent만 실행하고 실제 package/Main STOP/FGS/Room 새 D/P/복귀/잠금/명시 중지/중단 복구 조건을 유지한다. 새 queries/권한/제품 구현은 추가하지 않는다. 이 OS 경로를 앱 내비 SDK 전환 성공이나 APP04/MAP04/APP05 전체 체크 완료로 대체하지 않는다. 제품 APK와 완료된 비교 원형은 그대로다.

세 번째 진단 뒤에는 원본 준비1/종료11/관찰17/메모1의 전 필드/물리형/행순서와 모든 설정/권한을 대조했다. 원래 자료와 별도로 정확히1개 소유 활성 세션, 그 세션에만 연결된 관찰,3개 STOPPED 진단 run과 정확한 sequence 증가를 보존했다. 세션/실패 자료를 지우지 않고 소유 AVD를 중지한 뒤 검사 harness만 빌드했다. 최종 복구/종료/전체 보존은 아직 완료하지 않았다.

## 전면 기록 표시 덮어쓰기: 수정 전 근거와 계획

실제 system launcher 라이트는 Main STOP/FGS/같은 source의 새 D/P/복귀 D잠금/명시 중지/일반 전면 Recording을 모두 확인했다. 확대 다크의 첫 시도는 D수신 후 전면 accessibility package가 null인 관찰 미확인이었다. 실제 PNG는 내비 시작 화면이고 Main CREATED/FGS1/OPEN이었다. 이를 실제 다른 앱 전환이나 복원 실패/성공으로 단정하지 않는다. null만 제한 재관찰하고 다른 nonnull package는 즉시 실패하도록 검사 관찰을 보강했다. 뒤의 ADB 일시 무응답/회복/transport 재연결과 복구 완료 전 검사 빌드가 겹친 운영 실수도 분리했다. 같은 소유 process/생성 시각과 단독 활성 채팅을 확인해 잘못 덮인 소유권 flag를 복구했고 원본/설정과5개 종료 구간을 다시 대조했다.

같은 제품 de6357f와 보존한 활성 연습에서 확대 다크를 다시 실행했을 때 실제 내비/Main STOP/FGS1/새 Room D/P/복귀 D잠금/명시 STOPPED/Disabled/FGS0까지는 성공했다. 이후 일반 전면 Recording을 기다리는15초 조건에서 실패했다. 실패 순간 Main RESUMED, foreground 실제 한길, background Disabled, recording Paused였고 마지막 run 종료 이후의 SDK 관찰도 존재했다. 이는 수집 자체는 다시 동작했는데 표시가 Paused로 덮인 정확성 문제를 좁히는 근거다.15초는 검사 실패 제한이며 측정한 앱/SDK 성능 값이나 개선율이 아니다. 최초 실패/후속 확인/새진단자료는 지우지 않는다.

수정 전 가설은 finish의 owner=null 발행이 즉시 foreground collector를 재진입시켜 Recording을 발행한 뒤, cleanup이 Paused를 뒤늦게 쓰는 순서다. 먼저 즉시 재진입 foreground dispatcher를 사용하는 회귀에서 같은 source의 append/subscription과 상태를 분리해 현재 코드를 실패 재현한다. 정상 opt-out, 실패 뒤 opt-out, cleanup 실패, recovery 실패, 세션 종료의 소유권 반환을 확인한 뒤 상태/배경 표시를 먼저 정하고 소유권을 마지막에 넘기는 수정을 검토한다. 실제 SDK/플랫폼 시간 최적화가 아니라 표시 정확성 수정이며 가짜 저장소/제어 dispatcher/JUnit 시간을 앱 성능으로 쓰지 않는다.

현재 v1 전후 timing은 initial 제품 de6357f까지의 실측이다. 후속 정확성 수정의 최종 앱 성능 비교로 대신하지 않으며 최종 SDK 처리/전면 복귀 성능 개선은 미측정으로 남긴다. 활성 소유 자료가 추가된 현재 입력을 원래11개 종료 입력과 같다고 취급하지 않는다. 성능 향상을 완료하거나 상대 개선율을 만들지 않고 실제 동작/데이터 보존과 별개로 기록한다.

수정 전 targeted controller36개에서 새5개만 실패했고 Recording4/Idle1 기대가 모두 Paused로 덮였다. 기존31개와 수집/구독 단일 소유권 조건은 통과했다. 실제 종료 뒤 관찰 지속과 함께 즉시 재진입 상태 덮어쓰기 가설을 지지한다. 소유권 반환 네 분기에서 background/state를 먼저 정하고 owner 해제를 마지막에 발행하도록 최소 수정했으며, 같은 source/gate/ACK/취소 join/명시 시작 조건과 회귀5개를 유지한다. 전체 검사와 실제 양 테마/복구는 수정 후 다시 판정한다.

## 최종 앱과 전면 창 관찰 경계

정확성 수정 a2cf9e2에서 JDK17 전체 verify/debug/unsigned release와 계측 APK 빌드가 통과했다. 단위 XML765개는 실패/오류/건너뜀0이며 성능 수치가 아니다. 수정된 실제 라이트는 내비 전면/Main STOP/FGS1/새 Room D/P/복귀 D잠금/명시 중지/일반 Recording 복원을 모두 통과했다. 확대 다크의 다음 시도는 내비 시작 뒤 global rootInActiveWindow의 package null로 초기 전면 관찰에서 실패했다. 실제 Main CREATED/FGS1/OPEN 근거와 실패 자료를 보존했으며 이를 앱 복원 실패나 내비 렌더 완료로 해석하지 않는다.

테스트만 Main RESUMED 시점의 실제 display를 확보하고 UiAutomation interactive-windows flag를 읽고 제한적으로 설정한 뒤 해당 display의 정확히 하나인 focused window의 실제 root/package를 관찰하도록 바꿨다. display 미확인/불일치, 다중 focus, root 없음과 flag 실패는 사유가 있는 unknown으로 남기며 예상 package를 대신 넣지 않는다. 원래 flag bit는 다른 flags를 보존해 종료 시 복원한다. focused package는 완성 렌더나 앱 Navi SDK 전환 성공의 증거가 아니다. 기존 Main STOP/Active/OPEN/native FGS/새 D/P/잠금/Recording assertion은 유지했다. 독립 검토에서 구체 결함을 찾지 못했고 JDK17 quality/계측 APK 빌드를 통과했다. 제품 APK hash는 동일하다. 테스트 관찰 범위 수정은 제품 실행 성능을 바꾸지 않아 추가 성능 측정 대상이 아니며 실제 재실행 결과는 별도로 판정한다.

재빌드 전에 원래 모든 필드/물리형/행순서/sequence/권한/설정을 대조했다. 원본과 별도로 소유 활성 연습1개, 그 연습의 관찰58개와 STOPPED8개를 보존했고 소유 AVD를 정지했다. 최종 다크/외부 종료 복구와 최종 자료 보존은 아직 대기다.

## 최종 실제 검증과 보존

같은 a2cf9e2 제품 APK에서 final 라이트는 fixed-test, 확대 다크는2d77775의 display-test로 각각 전체 실제 시나리오를 통과했다. 같은 run/process/source, Main STOP, 양쪽 실제 내비 전면 package(다크만 Main display focused 관찰), FGS1/Active/OPEN과 해당 run에서 새로 추가된 실제 CONNECTED D/P 관찰을 독립 대조했다. 복귀 D잠금/명시 STOPPED/서비스0/같은 연습 유지/일반 Recording도 확인했다. 누적 completed_themes나 구 runtime 라이트를 최종 증거로 쓰지 않았다. 두 test APK는 서로 다르며 root의 당시 설치 hash 대조와 종료 후 현재 제품/test hash 재조회 근거를 구분했다.

첫 interrupt는 초기 known-other 전면 조건에서 실패해 새 run을 만들지 않았다. Main을 명시적으로 다시 열고 같은 소유 자료/서비스 부재를 확인해 재실행했다. READY의 OPEN/FGS1을 부모가 확인한 뒤 Main 전면에서 해당 앱만 force-stop해 실제 instrumentation abort/서비스 부재를 확인했다. 원 driver는 부모 receipt가 쓰이기 전에 읽어 FileNotFoundError가 났다. 실패 로그를 보존하고 같은 READY/abort/완료된 부모 receipt를 사후 읽기 전용으로 대조했다. 이 준비 fixture는 JUnit PASS가 아니며 같은 종료를 반복하지 않았다.

별도 fresh-process recover는 실제1개 PASS다. 이전 run/시작/nonce/source가 같은 INTERRUPTED/null, 자동 새 run/FGS 없음, 새 ID/새 process nonce의 명시 enable→STOPPED, 이전 중단과 다른 실행 보존, 소유 연습만 종료/최종Idle/Disabled/FGS0를 확인했다. native 캐시와 최종 SQLite 전 필드/물리형/순서/sequence/원형을 별도로 대조했다. 원본 준비1/종료11/관찰17/메모1 모두 보존, 소유 종료1/관찰89/run11(10STOPPED+1INTERRUPTED/null)만 추가됐다. 추가7개 역할은 실패5/구 runtime 검증1/수동 진단1이며 최종 light/dark/interrupted/recovery와 분리했다. 최초 fixture 실패는 새 marker가 없어 run 수를 늘리지 않았다. 개수는 정확성/보존 근거이며 성능 수치가 아니다.

명시 manifest의 offline exact PASS 후 최종 expectation을 별도 checkpoint로 고정했다. 원래 grants/runtime/font/night/IME/proxy와 새 SQLite 복사를 다시 exact 대조하고 소유 AVD/forward/임시 DDS 탭만 정리했다. 원본 checkpoint를 덮지 않았고 기존 자료/실패 run/소유 연습을 삭제하지 않았다. 실제 기본/확대 다크 캡처의 출처/미확인/잠금 문구와 버튼 도달을 확인했으며 공개 사진은 추가하지 않았다.

## README 사례 선정 결과

기존4번은 SQLite 묵시적 변환을 차단한 저장 경계의 근거다. 새4번은 내비 전면의 SDK 저장과 복귀 화면 상태가 갈라지는 실제 영향을 확인하고, 저장 행/서비스/화면/marker를 분리해 원인을 즉시 재진입 쓰기 순서로 좁혔다. 지연이나 무조건 Recording 대입 대신 소유권 반환을 마지막에 발행한 판단, 단일 recorder의 잠금/취소 join, native ACK와 프로세스 손실의 미확인을 실제 양 테마/복구로 설명한다. 비동기 상태 전이/OS 서비스/데이터 경계를 함께 다룬 판단의 근거가 기존4번보다 강해 교체한다. 최신성/코드량/검사 총수를 이유로 쓰지 않는다. 기존1/2/3 내용과 순서, 정확히4개/여섯 항목/연속 실제 코드/유효 링크/브랜드/기존 이미지18개를 유지한다. 교체한 과거 관찰/마이그레이션 회귀는 집계 도구의 legacy 보조로 계속 검사한다.

최종 상태 순서 수정과 SDK 처리의 같은 조건 성능 전후 값은 미측정이다. 초기v1은 de6357까지의 과거 관찰로 유지하고 최종 성능 향상으로 사용하지 않는다. README/집계 문서 배치는 앱 실행 성능과 무관해 추가 실측 대상이 아니며 코드/링크/종료 조건/공개 콘텐츠와 JDK17 verify로 검증한다. 최신 PR CI/Codex/정책과 병합/Notion은 별도 완료 조건으로 남긴다.

## 최신 Codex 지적과 수정 전 계획

최신2d77775의 Codex completed는 미해결P2 두 개를 포함했다. 이전 지적 없음 상태를 최신 승인으로 사용하지 않고 병합을 보류한다. 원래 리뷰/comment4432064183은 Room open COMMIT 뒤 호출자 결과 전달 전에 취소되면 request.opened가false로 남아 명시 중지 뒤에도 OPEN slot이잔존할 수 있다고 지적했다. comment4432064188은 저장소가 같은 고정 세션의 FinishedSession을 먼저 반환하고 세션 Flow 갱신이 늦으면 정상 종료를 FAILED로 고정한다고 지적했다. 실제 종료/취소 순서 경계를 targeted회귀로 먼저 실패 재현한다.

첫 가설은 open과 commit acknowledgement 사이 prompt cancellation이다. 원래 준비 context의 취소/허용 gate는 commit 전후까지 유지하고, commit 결과와 소유 영수증 기록만 취소에 유실되지 않게 보호한 뒤 service dispatch 전 원 context를 다시 검사한다. commit 전 취소 rollback과 commit 후 자기 run 정리를 구분하고 다른 run/source는 건드리지 않는다. 둘째 가설은 append의 FinishedSession이라는 authoritative 종료가 지연된 세션 Flow보다 먼저 도착하는 순서다. 이 경우만 정상 STOPPED/명시 배경 소유권 반환으로 분기하고 Missing/Invalid/Write/SourceChanged의 오류 경계는 유지한다.

이는 정확성 수정이며 속도 병목을 관찰한 것이 아니다. 제어 dispatcher/가짜 저장소와 JUnit 시간을 성능으로 쓰지 않으며 최종 앱의 같은 입력/조건 성능 전후 값은 미측정이다. a2cf9e2 실제 성공을 후속 제품 검증으로 대신하지 않는다. 앞선 검사 소유 연습은 이미 종료됐고 원형을 보존했다. 후속 실기는 별도 명시 namespace의 소유 캐시/새 검사 연습만 사용하며 이전 최종 자료/권한/설정 전체를 기준으로 보존하고 양 테마/중단 복구를 재검증한다.

후속 domain RED는42개 중 정확히 새2개만 실패했고 오류/건너뜀0, 나머지40개 통과다. COMMIT 결과 dispatch 취소는 STOPPED 대신 OPEN, FinishedSession 선행은 STOPPED 대신 FAILED로 재현했다. 원 prepare context를 캡처하고 open 결과+opened 게시만 NonCancellable로 보호하며 canCommit에서 원 context 취소/isStarting/현재 gate를 계속 확인했다. 보호 밖에서 원 context를 확인한 뒤만 dispatch한다. FinishedSession만 기존 정상 SessionEnded와 같은 STOPPED/knownEnd/optOut으로 정리한다. 원래 실패 cleanup과 state-before-owner를 유지했다. 같은42개 GREEN을 확인했으며 첫 GREEN 문법 컴파일 실패도 별도 보존했다.

실제 Room 경계2개는 COMMIT 뒤 adapter 결과 전달 지연, 실제 finish COMMIT 뒤 controller 통지 지연을 각각 제어한다. 수정 패치를 정확히 백업한 뒤 committed 원형 controller로 순차 실행해2개 모두 AssertionError RED(오류/건너뜀0)를 확인하고 패치를 원형 그대로 복원했다. 첫 경우 실제 OPEN 잔존, 둘째 실제 FAILED 저장을 대조한다. 이는 실제 Room와 production adapter를 쓰지만 내부 특정 dispatcher 결함이나 실제 SDK/FGS 재현으로 과장하지 않는다. 같은 테스트 GREEN과 전체 verify/native APK/후속 실제 양 테마/외부 종료 복구를 새 제품에서 다시 확인한다. 원본 기록의 cancellation 계약도 commit 전 rollback과 commit 후 영수증 보존으로 명확히 구분했다.

후속 실제 Room2개 GREEN(실패/오류/건너뜀0)과 JDK17 전체 verify/debug/unsigned release/native APK 빌드를 통과했다. 독립 읽기 검토에서도 원 context 취소 gate/dispatch 보호 밖/cleanup failure/state-before-owner/이전 캐시 불변과 새 소유1개 제한에 새 구체 결함을 찾지 못했다. 새 제품 APK와 test APK를 immutable hash로 고정했고 실제 후속 회차는 아직 미실행이다. 앞선 a2cf9e2 성공은 그 revision으로 명시하고 최신 성공으로 대신 쓰지 않는다.
