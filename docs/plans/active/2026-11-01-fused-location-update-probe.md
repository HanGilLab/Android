# FusedLocation 업데이트 등록과 해제 기술 검증

- 상태: 진행 중
- 요구사항: MAP-01과 APP-04의 위치 입력 기술 선행 검증. 기존 LastKnown 1회 조회와 구분한다.

## 목표와 범위

현재 main91ff1c1c738d4c2231eabe84bdd1040a6a27eb85의 FusedLocation1.1.3은 마지막 위치 조회만 연결한다. 공식 문서에서 registerFusedLocationCallback/unregisterFusedLocationCallback과 같은 FUSED_LOCATION 권한을 확인했다. SDK 업데이트 등록의 실제 callback/Empty/error/timeout와 동일 listener 해제를 별도 debug/native 프로브로 확인한다. 실제 이동 경로/현재 위치의 정확성, 연속/백그라운드 저장, 새 위치 계산, 지도 표시나 신규 권한을 구현/완료로 쓰지 않는다.

release/production 코드, 기존 LastKnown UI와 계약, DB/앱 원본 자료/manifest/의존성 버전은 바꾸지 않는다. core:vehicle의 debug 전용 프로브는 기존 SdkBindingContext를 재사용해 앱 AndroidTest에서 같은 전용 debug 앱 권한과 실제 Main/P+UX 아래 opt-in할 때만 호출한다. 자동으로 권한을 부여하지 않고 기존 권한/외부 복구 조건을 먼저 확인한다. 이전 임시 동의의 범위를 확장하거나 원형 권한/플래그를 덮어쓰지 않는다. 실제 허용 검증의 승인 경계가 충족되지 않으면 그 부분만 미실행으로 보존하고 코드/미허용/제어 검증을 독립 진행한다.

## 완료 조건

- [ ] 공식 문서와 설치된1.1.3 AAR의 실제 등록/해제 ABI, 같은 listener/권한/연결 조건을 대조한다.
- [ ] debug 전용 프로브의 bounded owner/등록/해제/닫기/late callback/권한과 live gate 폐기, 후속 정리 실패를 제어 검사로 확인한다. release/기존 앱 흐름과 데이터는 그대로다.
- [ ] 실제 Main/SDK/P+UX/미허용 선행 확인과 opt-in 절차를 준비하고 실기 결과/미실행을 구분한다. 성공 기본값/위치 주입/실제 좌표 공개를 하지 않는다.
- [ ] 실제 허용 검증이 가능한 경우 callback/Empty/error/timeout를 있는 그대로 기록하고 같은 listener 해제와 새 인스턴스 재시도를 확인한다. 미충족이면 이 조건은 체크하지 않는다.
- [ ] 기존 원본/권한/runtime flags/font/night/IME/proxy와 소유 AVD/forward/DDS 정리, JDK17 전체 verify/native 컴파일/관련 실기와 최신 PR CI/Codex/미해결 지적을 확인한다.
- [ ] 새 근거를 README4개와 비교하고 더 강한 원인/판단/실제 결과가 없으면4개/순서/6항목/22실제 발췌/18기존 이미지를 유지한다.

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
| JDK17 전체 verify/native/실제 callback | 미실행 | 코드 추가 전 계획이며 성공으로 세지 않음 |

## 남은 문제와 인계

SDK timestampMillis 시간 기준과 confidence 단위/범위는 미정의다. 현재 위치나 실제 이동으로 추정하지 않는다. 단순 등록 함수 반환과 callback/위치 사용 가능성은 다르다. 같은 listener 해제와 SdkBindingContext 폐쇄를 SDK 내부 retry Job 자체 취소/영구적인 미수신 보장으로 바꾸지 않는다. 원래 준비1/종료13/관찰145/메모1/terminal run15/선언0/활성0/schema6 원형과 권한을 보존하며 location/provider 상태를 주입하거나 원래 좌표를 바꾸지 않는다. APP-04/MAP-01/ENV-06 및 전체 필수19/54(선택3제외)는 별도 미완료다.

## 공식 근거

- [위치 업데이트 수신과 중지](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/location-data-access)
- [동일 FUSED_LOCATION 권한의 API별 요구](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/declaring-permissions)
