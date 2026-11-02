# 온라인 지도 자료의 출처와 사용 조건

2026-11-02 07:03~07:04 KST에 공식 서비스 응답과 공개 제작 원본을 확인했다. 앱 기준은 main `dd4897880b24776c1599bb18096abc5bcc0a2219`다. 이 문서는 DES-08 중 실제 지도 자료의 출처 확인 범위이며 앱 전체의 배포 조건 충족을 선언하지 않는다.

## 앱에서 사용하는 자료

| 대상 | 현재 사용 | 출처와 포함 방식 |
|---|---|---|
| 지도 렌더러 | MapLibre Native Android 13.5.0, OpenGL | 라이브러리 의존성. BSD 2-Clause 원문은 [앱 assets](../../app/src/main/assets/licenses/maplibre-native.txt)에 포함한다. |
| 라이트 스타일 | [OpenFreeMap Liberty](https://tiles.openfreemap.org/styles/liberty) | 온라인 style JSON을 읽는다. |
| 다크 스타일 | [OpenFreeMap Dark](https://tiles.openfreemap.org/styles/dark) | 온라인 style JSON을 읽는다. |
| 코스 번호 글자 | Noto Sans Regular | 앱의 SymbolLayer가 fontstack을 요구하며 해당 스타일의 원격 glyph endpoint를 사용한다. |
| 지도 글자 | Liberty: Noto Sans Regular/Bold/Italic, Dark: Regular | 제공 style의 text-font 값. 한글 등 표의문자는 MapView의 로컬 sans-serif fallback 설정도 사용한다. |
| 지도 아이콘 | 공통 sprite `ofm_f384/ofm` | 원격 atlas metadata와 이미지를 렌더러가 사용한다. 앱 자체 아이콘 세트로 복사하지 않는다. |

사용 코드는 [DemoMap](../../feature/map/src/main/kotlin/io/github/hangillab/hangil/feature/map/DemoMap.kt)과 [CoursePreviewLayers](../../feature/map/src/main/kotlin/io/github/hangillab/hangil/feature/map/CoursePreviewLayers.kt)다. 앱 본문에 외부 글꼴 파일을 배포하지 않는다는 [디자인 기록](../design/tokens.md)은 원격 지도 글꼴의 출처 확인을 대신하지 않는다. 이 조사에서는 서비스 타일, glyph 바이너리와 sprite PNG를 다운로드하지 않았다.

## 실제 서비스 응답과 제작 원본의 대응

두 스타일은 모두 `glyphs=https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf`, `sprite=https://tiles.openfreemap.org/sprites/ofm_f384/ofm`을 선언한다. 응답의 `version=8`은 스타일 규격이며 서비스나 글꼴 릴리스 버전이 아니다. `name/id/metadata`에 별도 배포 revision은 없었다.

| 제공 JSON | SHA-256 |
|---|---|
| Liberty | `408245464e76791801a64393c403433d09e8110eb9516040f8dc2b62584eee2a` |
| Dark | `c90dbfd0e428f7ea40740c29e789167c24f79e91327761960e08c44ca8f7795e` |

공식 `openfreemap-styles`의 고정 commit `006ea5796803ba2df4330888836cb0a8f17473b4`에 있는 [Liberty](https://github.com/hyperknot/openfreemap-styles/blob/006ea5796803ba2df4330888836cb0a8f17473b4/styles/liberty/style.json)와 [Dark](https://github.com/hyperknot/openfreemap-styles/blob/006ea5796803ba2df4330888836cb0a8f17473b4/styles/dark/style.json)에서 `__TILEJSON_DOMAIN__`만 `tiles.openfreemap.org`로 치환해 JSON을 파싱하면 각 제공 응답 전체와 같았다. 치환 전 원본과 제공 응답의 바이트가 같다는 뜻이나 서버의 배포 commit 인증은 아니다. URL은 가변이며 이후 내용도 같다고 보장하지 않는다.

[1x sprite JSON](https://tiles.openfreemap.org/sprites/ofm_f384/ofm.json)과 [2x sprite JSON](https://tiles.openfreemap.org/sprites/ofm_f384/ofm@2x.json)은 위 commit의 [같은 디렉터리](https://github.com/hyperknot/openfreemap-styles/tree/006ea5796803ba2df4330888836cb0a8f17473b4/sprites/sprites/ofm_f384) 파일과 각각 바이트가 같았다. 이 대조는 metadata에 한정하며 atlas 픽셀이나 모든 아이콘 원 제작 버전의 동일성은 확인하지 않았다.

## 자료별 조건을 분리한 근거

- **서비스**: [공식 Quick Start](https://openfreemap.org/quick_start/)는 MapLibre Native의 모바일 사용을 설명한다. [이용 조건](https://openfreemap.org/tos/)의 2026-10-14 갱신본은 서비스의 가용성/정확성을 보장하지 않는다. 서비스 이용 조건을 개별 지도 자료의 재배포 라이선스로 대신하지 않는다. 현재 앱에는 별도 영역 다운로드나 오프라인 기능이 없다.
- **스타일 코드와 디자인**: [공식 root LICENSE](https://github.com/hyperknot/openfreemap-styles/blob/006ea5796803ba2df4330888836cb0a8f17473b4/LICENSE.md)는 저장소 자체의 MIT와 포함 자료의 조건을 나눈다. [Liberty 개별 LICENSE](https://github.com/hyperknot/openfreemap-styles/blob/006ea5796803ba2df4330888836cb0a8f17473b4/styles/liberty/LICENSE.md)는 BSD 코드 조건과 OSM Bright/Mapbox 디자인의 CC BY 3.0 유래, OpenMapTiles CC BY 4.0을 기록한다. [Dark 개별 LICENSE](https://github.com/hyperknot/openfreemap-styles/blob/006ea5796803ba2df4330888836cb0a8f17473b4/styles/dark/LICENSE.md)는 코드 BSD 3-Clause, 디자인 CC BY 4.0과 CartoDB Basemaps의 CC BY 3.0 유래를 기록한다. 따라서 포함 자료 전체를 MIT 하나로 표시하지 않는다.
- **글꼴**: 공식 root LICENSE는 Noto Sans를 SIL Open Font License 1.1로 명시한다. 실제 서비스의 [Regular](https://tiles.openfreemap.org/fonts/Noto%20Sans%20Regular/LICENSE.md), [Bold](https://tiles.openfreemap.org/fonts/Noto%20Sans%20Bold/LICENSE.md), [Italic](https://tiles.openfreemap.org/fonts/Noto%20Sans%20Italic/LICENSE.md) 고지도 모두 OFL 1.1 원문이며 SHA-256은 `9f6ffa4d4ceb839c1d12b830273c1d9f01cb0f5e2535f6143b8f7befdd5650d5`였다. [제작 README](https://github.com/hyperknot/openfreemap-styles/blob/006ea5796803ba2df4330888836cb0a8f17473b4/fonts/README.md)와 [수집 스크립트](https://github.com/hyperknot/openfreemap-styles/blob/006ea5796803ba2df4330888836cb0a8f17473b4/fonts/collect.sh)는 `maplibre/demotiles`의 처리된 Noto 자료를 복사하지만 commit을 고정하지 않는다. 확인한 제공 고지와 [demotiles의 고정 고지](https://github.com/maplibre/demotiles/blob/601ae60796ceceda2cbd2ed3d2ea92d17a84be4b/font/Noto%20Sans%20Regular/LICENSE.md)는 바이트가 같다. 이를 실제 glyph의 원본 font 버전/빌드 commit 일치로 확대하지 않는다. Liberty의 예전 개별 고지에는 Roboto 항목이 남아 있어 현재 Noto 사용을 그 문장만으로 설명하지 않는다.
- **아이콘**: 공식 root와 Liberty 고지는 Maki POI의 CC0 1.0과 화살표의 public domain 출처를 명시한다. 다만 [실제 sprite 제작 스크립트](https://github.com/hyperknot/openfreemap-styles/blob/006ea5796803ba2df4330888836cb0a8f17473b4/sprites/create_sprites.sh)는 Liberty의 두 SVG 폴더와 Bright 고유 아이콘을 합친다. 전체 atlas를 단일 원본/단일 조건으로 단정하지 않으며, 앱에 아이콘을 추출해 재배포하는 허용까지 확인했다고 하지 않는다.
- **지도 데이터**: OpenStreetMap과 OpenMapTiles의 귀속은 스타일/글꼴/아이콘 조건과 별도다. [OpenStreetMap copyright](https://www.openstreetmap.org/copyright), [OpenMapTiles LICENSE](https://github.com/openmaptiles/openmaptiles/blob/master/LICENSE.md)와 각 스타일 고지의 출처 표시를 함께 확인한다.

## 앱 표시와 출품 자료의 남은 점검

현재 코드에는 OpenFreeMap/OpenMapTiles/OpenStreetMap 출처 문구와 Native attribution 활성화가 있다. 이번에는 실제 기기 화면이나 attribution 메뉴를 다시 실행하지 않았으므로 두 테마의 최종 표시와 링크 접근을 새로 통과한 것으로 기록하지 않는다.

- [ ] 두 테마의 실제 지도에서 OpenMapTiles/OpenStreetMap 귀속과 Native attribution 접근을 확인한다.
- [ ] Dark 고지가 요구하는 접근 가능한 디자인 출처가 실제 지도에서 이어지는지 확인한다. 저장소 문서에 링크를 추가한 사실만으로 앱 표시를 충족했다고 하지 않는다.
- [ ] 최종 캡처/영상에도 지도 데이터와 스타일의 필요한 귀속을 유지한다. 공식 [출처 안내](https://openfreemap.org/)의 인쇄물/영상 안내를 별도로 적용한다.
- [ ] 글꼴/glyph/스타일/sprite를 앱에 포함하거나 오프라인 배포하는 변경이 생기면 원 copyright, 고지와 조건 원문을 다시 확인한다. 현재 조사만으로 그런 배포를 승인하지 않는다.
- [ ] 실제 glyph 바이너리의 원본 버전/빌드와 전체 sprite 픽셀의 제작 대응은 미확인으로 유지한다.
- [ ] 실제 resolved release 라이브러리와 Pleos SDK의 배포 조건은 별도 목록으로 점검한다. 이 문서로 DES-08 전체를 체크하지 않는다.

## 대조 자료와 재현

[정제한 응답/대조 metadata](map-assets/2026-11-02-source-metadata.json)는 공개 URL, 원문 hash와 비교 범위만 포함한다. 개인 경로/기기/사용자 위치, 원문 타일이나 로그는 포함하지 않는다.

재현할 때는 두 style URL의 실제 응답 bytes를 SHA-256으로 계산하고 JSON의 fontstack/glyphs/sprite를 읽는다. 고정 upstream JSON에서 도메인 placeholder만 치환한 뒤 객체 전체를 비교한다. sprite JSON과 세 제공 LICENSE도 고정 upstream의 대응 bytes와 각각 비교한다. 응답 시각과 hash가 달라지면 이번 결과를 재사용하지 말고 변경된 자료와 조건부터 다시 확인한다. 바이너리나 원본 버전을 읽지 않은 범위는 비교 성공으로 채우지 않는다.
