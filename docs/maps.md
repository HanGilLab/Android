# 온라인 지도 표시 검증

MAP-01의 첫 단계는 주차 상태에서 고정 시연 지역의 실제 온라인 지도를 표시하는 것이다.
현재 위치·코스 좌표·경로 안내·위치 수집은 이 화면의 구현 범위에 포함하지 않는다.

## 표시와 사용 조건

- 렌더러: MapLibre Native Android 13.5.0, `android-sdk-opengl` artifact와 명시적인 OpenGL 선택.
- 지도 스타일: OpenFreeMap Liberty. 지도 화면에 OpenFreeMap·OpenMapTiles·OpenStreetMap 출처와 Native attribution을 유지한다.
- 온라인 지도 요청만 사용한다. 별도 영역 다운로드·사전 수집·오프라인 기능은 구현하지 않는다.
- 온라인 서비스는 가용성·정확성을 보장하지 않으므로 로딩·실패·재시도를 표시한다.
- 고정 중심은 공개된 서울 시연 지역이며 사용자의 현재 위치나 검토된 연습 코스가 아니다.
- MapLibre의 BSD 2-Clause 라이선스 원문을 앱의 `assets/licenses/maplibre-native.txt`에 포함한다. 추가 에셋·의존성 사용 조건은 DES-08에서 계속 정리한다.

## 검증 경계

스타일 callback과 실제 타일·한글 표시는 별도로 확인한다. 화면이 빈 경우 준비 완료로 기록하지 않는다.
MapView는 Activity 수명주기와 저장·해제를 연결하고 주차/UX 상태가 허용되지 않으면 화면에서 제거한다.
P+UX 동시 허용·D 전환·상태 미확인·화면 재생성·반복 진입·네트워크 실패를 실제 Pleos에서 확인한다.
FusedLocation 호환성·권한·실제 입력 확인이 남아 있으므로 지도 표시만으로 MAP-01 전체를 완료 처리하지 않는다.

## 공식 근거

- [MapLibre artifact와 렌더러 선택](https://maplibre.org/maplibre-native/android/examples/artifacts/)
- [MapView 수명주기](https://maplibre.org/maplibre-native/android/examples/getting-started/)
- [OpenFreeMap 모바일 사용](https://openfreemap.org/quick_start/)
- [OpenFreeMap 이용 조건](https://openfreemap.org/tos/)
- [MapLibre 13.5.0 라이선스](https://github.com/maplibre/maplibre-native/blob/android-v13.5.0/LICENSE.md)
