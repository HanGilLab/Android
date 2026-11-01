# FusedLocation 업데이트 등록과 해제 기술 검증

- 상태: 진행 중
- 요구사항: MAP-01과 APP-04의 위치 입력 기술 선행 검증. 기존 LastKnown 1회 조회와 구분한다.

## 목표와 범위

현재 main91ff1c1c738d4c2231eabe84bdd1040a6a27eb85의 FusedLocation1.1.3은 마지막 위치 조회만 연결한다. 공식 문서에서 registerFusedLocationCallback/unregisterFusedLocationCallback과 같은 FUSED_LOCATION 권한을 확인했다. SDK 업데이트 등록의 실제 callback/Empty/error/timeout와 동일 listener 해제를 별도 debug/native 프로브로 확인한다. 실제 이동 경로/현재 위치의 정확성, 연속/백그라운드 저장, 새 위치 계산, 지도 표시나 신규 권한을 구현/완료로 쓰지 않는다.

release/production 코드, 기존 LastKnown UI와 계약, DB/앱 원본 자료/manifest/의존성 버전은 바꾸지 않는다. core:vehicle의 debug 전용 프로브는 기존 SdkBindingContext를 재사용해 앱 AndroidTest에서 같은 전용 debug 앱 권한과 실제 Main/P+UX 아래 opt-in할 때만 호출한다. 자동으로 권한을 부여하지 않고 기존 권한/외부 복구 조건을 먼저 확인한다. 이전 임시 동의의 범위를 확장하거나 원형 권한/플래그를 덮어쓰지 않는다. 실제 허용 검증의 승인 경계가 충족되지 않으면 그 부분만 미실행으로 보존하고 코드/미허용/제어 검증을 독립 진행한다.

## 완료 조건

- [x] 공식 문서와 설치된1.1.3 AAR의 실제 등록/해제 ABI, 같은 listener/권한/연결 조건을 대조한다.
- [x] debug 전용 프로브의 bounded owner/등록/해제/닫기/late callback/권한과 live gate 폐기, 후속 정리 실패를 제어 검사로 확인한다. release/기존 앱 흐름과 데이터는 그대로다.
- [x] 실제 Main/SDK/P+UX/미허용 선행 확인과 opt-in 절차를 준비하고 실기 결과/미실행을 구분한다. 성공 기본값/위치 주입/실제 좌표 공개를 하지 않는다.
- [ ] 실제 허용 검증이 가능한 경우 callback/Empty/error/timeout를 있는 그대로 기록하고 같은 listener 해제와 새 인스턴스 재시도를 확인한다. 미충족이면 이 조건은 체크하지 않는다.
- [ ] 기존 원본/권한/runtime flags/font/night/IME/proxy와 소유 AVD/forward/DDS 정리, JDK17 전체 verify/native 컴파일/관련 실기와 최신 PR CI/Codex/미해결 지적을 확인한다.
- [x] 새 근거를 README4개와 비교하고 더 강한 원인/판단/실제 결과가 없으면4개/순서/6항목/22실제 발췌/18기존 이미지를 유지한다.

## 구현 단계

1. 최신 기획/AGENTS/진행 계획/실제 Git/PR/CI/환경을 읽고 같은 채팅의 별도 실행 token lease를 확보한다.
2. test/debug에만 SDK 등록 프로브를 추가하고 제어 검사/명시 native opt-in을 준비한다.
3. JDK17 전체 검사와 native APK 컴파일 뒤 무거운 빌드/AVD는 순차 실행한다. 미허용 원형을 먼저 확인하고 동의 범위가 충족된 실제 검증만 수행한다.
4. 실제 결과/실패/미실행/승인 경계/원본 보존을 기록하고 최신 PR 검사/리뷰와 정상 병합 조건을 별도로 지킨다.

## 변경 전후 측정

이 작업은 기존 앱 사용자 흐름이나 처리 속도를 최적화하지 않는 debug/계측 기술 프로브다. 기존 LastKnown은 새 SDK 등록 API의 기능적으로 같은 변경 전 동작이 아니므로 서로의 시간을 전후 성능 비교로 만들지 않는다. release/production 및 기존 UI 코드 불변을 검증해 앱 성능 측정 적용 제외를 입증한다. 새 callback의 제한 시간/수신 순서/실제 app receipt 시각은 진단이며 SDK/앱 성능 개선 수치가 아니다. 기존A1/B/A2 원값은 덮어쓰지 않고 새 측정 성과나 전후 기준값으로 재사용하지 않는다. 미지원/미수신을0지연/정상 위치로 대체하지 않는다. 같은 동작의 전후 성능 측정이 없으므로 성능 향상/인과 저하/동등/무회귀를 완료로 주장하지 않는다.

## 검증 결과

| 시나리오 | 현재 상태 | 구분 |
|---|---|---|
| 최신 main/최근 PR71/72 | 확인 | clean main91ff1c1, 최근 제품/종료PR와 최종 main CI4개 성공. 직전 실행의 검증과 이번 실행을 구분 |
| 기존 PR8/10 | 미완료 보존 | PR8 원래 head review running, PR10 completed는 실제 원래 head와 다름. 새 승인으로 해석하지 않음 |
| 공식 제출 안내7개/빈 양식1개 | 실제 GET/hash8개 동일 | 변경/새 발송/서명/추가 저장소 작업 없음 |
| 공식 FusedLocation callback 안내 | 확인 | 아래 원문 링크. 실제 AAR/등록 성공/실제callback과는 별도 |
| JDK17 전체 verify | 통과 | 제품60 suites/952 JVM, 별도 build-logic1 suite/4, 실패/오류/건너뜀0 |
| native APK 포맷/컴파일 | 통과 | metadata 정정과 IME 정책 수정 후 별도 재검증 |
| 실제 미허용 등록 차단 | 첫 실패 뒤 수정/재실행 통과 | 실제 Main/초기 SDK P+UX 아래 PERMISSION_DENIED, init/register0. 아래 실패 근거와 성공을 분리 |
| 실제 업데이트 허용 수신 | 미실행 | 별도 callback 창의 새 동의가 없고 SDK 수신으로 기록하지 않음 |

## 남은 문제와 인계

SDK timestampMillis 시간 기준과 confidence 단위/범위는 미정의다. 현재 위치나 실제 이동으로 추정하지 않는다. 단순 등록 함수 반환과 callback/위치 사용 가능성은 다르다. 같은 listener 해제와 SdkBindingContext 폐쇄를 SDK 내부 retry Job 자체 취소/영구적인 미수신 보장으로 바꾸지 않는다. 원래 준비1/종료13/관찰145/메모1/terminal run15/선언0/활성0/schema6 원형과 권한을 보존하며 location/provider 상태를 주입하거나 원래 좌표를 바꾸지 않는다. APP-04/MAP-01/ENV-06 및 전체 필수19/54(선택3제외)는 별도 미완료다.

## 공식 근거

- [위치 업데이트 수신과 중지](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/location-data-access)
- [동일 FUSED_LOCATION 권한의 API별 요구](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/declaring-permissions)

## 실제 ABI와 SDK 반환 의미

현재 설치된 공식1.1.3 AAR의 initialize/release/isSupported/registerFusedLocationCallback/unregisterFusedLocationCallback/FusedLocationListener ABI를 javap로 대조했다. 등록/해제는 같은 listener를 사용한다. bytecode에서는 initialize가 비동기 연결을 시작하고 register는 binder가 없으면 queue에 넣은 뒤 반환한다. unregister/release도 일부 remote 오류를 catch/log하며 내부 retry Job의 명시 취소는 확인되지 않는다. Snapshot의 Returned/cleanupComplete는 로컬 호출 반환/필요 정리 시도 완료이며 연결/원격 해제 ACK가 아니다.

업데이트 Binder stub은 null 입력을 listener.onChanged(null)로 전달하지 않고 로그만 남기는 경로가 있다. 제어 Empty 검사와 실제 callback Empty를 구분하고 실제 무수신 timeout을 Empty/미지원으로 추정하지 않는다. 일반 Exception으로 평탄화된 SDK 오류의 메시지로 권한/미지원 원인을 유추하지 않으며 isSupported=false도 정확한 부재 원인까지 확정하지 않는다. 원문 좌표/SDK timestamp/confidence/error message를 debug 공개 metadata에 넣지 않는다.

## 첫 제어 검사와 실제 RED/GREEN

최초 debug 제어27개는 JDK17에서 통과했다. 독립 검토에서 checkAccess가 현재 gate를 읽은 뒤 Driver.hasPermission 외부 호출이 gate를 폐기해도 앞선 true를 빌리는 후보를 찾았다. debug source 수정 전에 신규2개를 실행해 총29개 중2실패/기존27통과, 오류/건너뜀0을 실제 재현했다. 원래 probe/test 바이트와 XML/로그를 비공개로 보존했다. 이는 제어 fixture에서 등록/observer 전달의 경계를 재현한 것이며 실제 SDK/플랫폼 결함 재현은 아니다.

수정은 debug probe 한 파일에서 permission 조회 뒤 closed/deadline/최신 gate를 다시 확인하는 경계다. 조회 예외의 unknown/null과 gate 예외의 단계, false permission의 PERMISSION_DENIED 및 최초 Throwable/정리 suppressed를 유지했다. 동일 테스트 source SHA6a29b2da0862ca3f76bf4bae39e4c79ad7236e3fa4908e8e05cf164a71afd396의29개가 모두 통과했다. 기대/timeout/기존UI/manifest를 완화하지 않았다.

JDK17 엄격 Spotless, 전체 verify4단계와 native APK 컴파일이 통과했다. 제품60 suites/952 JVM와 별도 build-logic1 suite/4개는 실패/오류/건너뜀0이다. cache/up-to-date 가능성을 포함한 회귀 근거이며 SDK/앱 성능 수치가 아니다. 실제 native는 아직 미실행이다. native metadata의 actualSdk라는 두 필드는 실제 adapter 출처와 실제 initialize/register 여부/SDK callback receipt를 구분하도록 정정했고 해당 변경의 포맷/컴파일도 별도 확인한다. 정적 치환 guard의 잘못된 단일 출현 전제 중단1회와 sandbox 파일쓰기 준비 실패는 native/SDK 실패가 아니다.

## release 불변과 성능 적용 제외 근거

기존 before unsigned release APK와 전체 검사 뒤 산출물을 실제 zip entry별 대조했다. 전체 APK hash는 달랐으므로 byte 동일로 기록하지 않는다. 다른 항목은 META-INF/version-control-info.textproto 한 개이며197개 DEX/manifest/resources 등 payload는 같다. 비교한 이전 산출물의 형식적 source-to-binary 재현 증명은 주장하지 않는다. production/release/기존UI/source 및 SDK/권한/DB 버전의 Git diff도 없다. 이 test/debug 프로브가 기존 사용자 흐름의 실행 성능을 개선한 것으로 해석하거나 별도 속도 수치를 만들지 않는다.

## 현재 권한 경계와 실제 검증 대기

이전 일회성 LastKnown 검증을 완료한 동의를 새 업데이트 리스너 검증의 허용으로 자동 갱신하지 않는다. 새 코드에는 권한 요청/부여가 없고 이미 허용된 별도 시나리오는 명시 옵션과 실제 grant를 모두 요구한다. 우선 미허용 등록 차단/원형 보존과 코드 검토 가능한 PR를 준비한다. 새 callback 허용 관찰은 구체적인 조건의 동의를 확인하기 전 미실행이며 이 계획의 해당 완료 조건은 체크하지 않는다.

## 첫 실제 denied 실패와 수정 범위

현재 커밋3f2e9e5의 실제 Main과 SDK 주차/UX 허용 조건에서 미허용 검증1개를 실행했으나 original()의 enabled_input_methods 읽기에서 Android targetSdk33 초과 정책의 SecurityException으로 실패했다. 프로브 인스턴스/SDK 초기화/등록에는 도달하지 않았다. original 미확보 뒤 보존 검사도 성공으로 처리하지 않고 suppressed 실패로 보존했다. 첫 실패 JSON/계측 로그/진단 화면은 ignored 자료로 남겼다.

수정은 native 테스트에서 제한된 설정의 앱 조회를 제거하고, harness가 관찰하지 않았으며 외부 실제 shell 전후 대조가 필요함을 명시하는 것이다. null/정상값 대체, targetSDK 하향, shell 권한 채택이나 신규 권한을 쓰지 않는다. 기존 전체 원형이 첫 실패 뒤에도 동일함을 확인한 다음 외부 도구로 해당 IME 설정의 수정 전 기준과 readback을 별도 확보했다. 기존 원형을 덮어쓰지 않았다. 해당 수정 뒤 실제 denied는 다시 실행해야 하며 아직 성공으로 기록하지 않는다.

## README 사례 비교

이번 gate 재검사 결함은 debug 기술 프로브의 명시적 제어 seam에서 재현했고 실제 SDK 업데이트 허용 수신은 미검증이다. 기존1번은 실제 SDK 주행 상태와 상세 조작 허용 시점,2번은 실제 Room 연결 자료의 원자적 삭제,3번은 비동기 지도 렌더/입력 소유권,4번은 실제 기록 소유권 반환과 복귀 화면의 경쟁 경계를 입증한다. 이번 근거가 이들보다 제품 영향과 실제 결과를 더 설득력 있게 입증하지 못하므로 모두 보존한다. README4개/순서/여섯 항목/22실제 발췌/18기존 이미지 checker가 통과했고 anchor 수정도 필요하지 않다. 새 성능 성과나 공개 진단 이미지는 추가하지 않는다.

## 수정 후 실제 미허용 검증과 복구

5d59a1a1096b83029979748249634099894b5d53 소스의 새 APK에서 명시 opt-in 미허용 시나리오가 통과했다. 실제 Main의 lifecycle/display/single focused-window와 초기 SDK 주차/UX 허용을 확인한 뒤 PERMISSION_DENIED로 종료했고 initialize/register/unregister/release 시도는 없다. 필요한 binding context close는 반환했다. SDK 콜백 수신은 false이며 SDK의 허용 수신/권한 오류 콜백을 확인한 것이 아니다.

네이티브는 domain/전체 typed SQLite schema6/catalog/environment/공개 permission settings, SDK gate observer 닫힘, 원래 UIAutomation flag 복원을 확인했다. 외부 도구는 제한된 IME 설정, 모든 기존 행/값/타입/순서/sequence/권한/runtime flags/font/night/IME/proxy의 정확한 일치를 별도로 확인했다. 처음 실패한 보고서와 성공 보고서의 namespace/파일을 보존하고 성공만 첫 시도의 결과처럼 덮어쓰지 않았다.

검증을 마친 뒤 직전 검증된 main 제품 APK와 기존 계측 APK를 데이터 삭제나 downgrade 없이 다시 설치했고 실제 설치 파일 hash까지 대조했다. 전용 debug 앱의 FUSED_LOCATION 권한은 계속 미허용이다. 새 callback 허용 검증은 실행하지 않았고 후보 APK는 ignored 영역에 따로 고정했다. foreground service도 없다. 종료 시 소유 AVD/포워딩/검증 DDS 탭만 정리한다.

계측의 instance/final 스크린샷은 Main의 실제 display/전면을 확인한 진단 자료다. SDK 주차/UX 값이 미확인인 잠금 화면도 관찰됐으므로 이 화면을 항상 허용 상태의 증거나 공개 기능 화면으로 사용하지 않는다. 복구 cold start에서는 기본 내비가 전면에 오는 동작도 관찰해 Main을 다시 명시 실행했다. 원본 데이터/APK 보존과 cold-start 화면 상태를 구분한다. release 앱 동작/성능 향상, 실제 위치, 연속 기록이나 전체 APP-04/MAP-01 완료를 주장하지 않는다.

현재 작업은 새 허용 callback 검증과 최신 PR 필수 CI/Codex가 남아 있어 active 계획으로 유지한다. 전체 완료/병합은 아직 승인하지 않았다.
