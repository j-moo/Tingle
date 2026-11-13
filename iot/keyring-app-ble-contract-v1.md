# 키링·앱 BLE 연동 계약 v1 초안

**최신 0.7.2-owner-flow 변경:** [전체 연동 인계 맨 위0.7 절](app-flow-v2-handoff.md)과 [유저 플로우41항목](user-flow-coverage-v1.md)을 우선한다. TSV3 정상 계약을 유지하고 상대 소유 세대 분리·실패 입력 재계산·서명 복구 v2·현재 후보/늦은 완료 경계를 보강했다. !251 리뷰의 초기화 중지 계획 generation과 FE 상태 수신 한도도 수정했다. 아래 이전 버전은 당시 기록이다. 실물/서버 종단 완료를 뜻하지 않는다.

| 항목 | 내용 |
| --- | --- |
| 버전·변경일 | v0.1.8-draft / 2026-11-06 · 다중 peer 암호화 확장 광고·계산 개발 경로 연결 |
| 작성자 | 김종무(IoT) |
| 관련 Story | [S15P31E106-251](https://ssafy.atlassian.net/browse/S15P31E106-251) — 초기화·인증키 보존·완료 증명 재조회·복구. 기존 계약 근거: 224 |
| 구현 후속 | 초기화 251·BE249·FE171, 등록·동기화 225, 매칭 226, 상태 조회 227, 기록 전달 228, 실물 통합 검증 230 |
| 영향·검토 | IoT 김종무 · FE 이재준/황순철 · BE 김영빈/가을 · 매칭 입력/규칙 AI 황성현 |
| 우선순위 | 탐색·등록·본인 기기 확인 → 설정·시간 동기화 → 오프라인 매칭·기록·서버 접수 확인 |
| 이번 테스트 | 별도 사용자 버튼이 없는 키링을 가정. 초기화·등록 해제·양도는 후속으로 분리 |
| 문서 상태 | 로컬 협의 초안. 팀 공유 외부 원본의 위치·버전 연결과 관련 파트 승인 필요. 펌웨어 구현·실물 연동 완료를 뜻하지 않음 |

**0.5.0 당시 안내:** 통합 소스는 `iot/`, 보드에 복사할 파일은 `iot/build/zephyr.uf2`다. 전체 앱 흐름의 TSV2·타입3 grant·다중 계정 기기·소유자 인증·기록 전송/서명 접수 삭제는 [전체 앱 연동 v2](app-flow-v2-handoff.md)에 기록했고 현재 변경은 그 문서 맨 위 절을 우선한다. 기존 설정 서명 원문은 [개발 설정 연동 안내](dev-settings-signing-handoff-v1.md), PX 프레이밍·다중 상대·PC 참조 계산 기반은 [peer 개발 명세](peer-profile-exchange-v1.md)를 따른다. 당시 peerExchangeProtocol=`tingle-peer-v2`, maxPeerSessions=16은 지원 능력이며 실물 통과를 뜻하지 않는다. 초기화 구현은 6.10절에 구분한다. 아래 과거 버전·고정 MR은 각 시점의 기록이다. 실제 전송은 JSON 한 줄 뒤 LF, 문자열 값과 payload 원본 바이트를 보존한다.

## 기준과 이번에 확인한 결정

- 제품 기준: [압축 MVP](../docs/specifications/functional-spec-mvp.md), [키링 기능](../docs/specifications/functional-spec-v3/key.md), [매칭 기능](../docs/specifications/functional-spec-v3/mat.md), [ERD 기준 범위](../docs/specifications/reviews/erd-mvp-scope-20261028.md).
- 이전 작성 문서: [통신 계약](../ai-server/docs/contracts/iot/protocol-contract-v1.md), [데이터 구조](../ai-server/docs/contracts/iot/keyring-data-structures-v1.md), [화면 계약](../ai-server/docs/contracts/iot/screen-design-contract-v1.md). 과거 사본이므로 현재 명세와 충돌하는 부분은 그대로 가져오지 않는다.
- 상태 필드: [키링 상태 조회 BLE v1](keyring-status-ble-v1.md). 배터리·공간·미전송 기록 수의 의미와 미확인 기준은 유지한다.
- 구현 확인 기준은 [XIAO 등록 펌웨어 MR !172](https://lab.ssafy.com/s15-final/S15P31E106/-/merge_requests/172)의 `ec51e05`와 서버 `94fc351`이다.

### 시험 보드·빌드

| 항목 | 확인한 내용 |
| --- | --- |
| 실물 후보 | 이전 USB 부팅·BLE 탐색에 사용한 Seeed XIAO nRF52840 Sense 1대. 부트로더 ID `Seeed_XIAO_nRF52840_Sense`, 버전 `0.6.1`. 시험 직전에 보드 사용 가능 여부와 현재 설치 버전을 확인한다. |
| 앱 연동 이미지 | 현재 `0.4.0-dev-peer` / Zephyr 4.2.0 / `xiao_ble/nrf52840`. 빌드 산출물은 `iot/build/zephyr.uf2`. |
| 확인 범위 | UF2 빌드 및 호스트에서 C·Java 서명 호환성 검증 통과. 기존 `Tingle-Lab` 검색은 별도 바이너리 펌웨어의 실물 기록이다. 등록용 UF2의 실물 탑재·앱 종단 연동은 미확인이다. |
| 첫 합동 시험 | IoT는 XIAO·UF2·USB 로그, FE는 테스트 앱·BLE 로그, BE는 테스트 계정과 기기 공개키 등록을 준비한다. 15~20분으로 제안하며 일정은 FE·BE·IoT가 조율한다. |

| 구분 | 반영할 기준 |
| --- | --- |
| 제품 기준 | 계정당 현재 등록 키링 최대 1개, 키링당 현재 소유자 1명. 여러 개 등록·활성 하나 방식도 현재 MVP에는 적용하지 않음 |
| 제품 기준 | 준비된 키링끼리 휴대폰·인터넷 없이 판단·기록·물리 안내. 앱 종료·로그아웃만으로 중단하지 않음 |
| 제품 기준 | 매칭·진동·LED는 별도 설정. 양쪽 진동 ON일 때만 양쪽 진동, LED는 각자 설정 적용 |
| 이번 사용자 선택 | 버튼 없는 테스트에서는 전원 ON 후 60초 등록 대기. 전체 설정 전송, 중단 시 처음부터 재전송, 실패 시 기존 정상 설정 유지 |
| 이번 사용자 선택 | 진동은 `200ms 진동 → 200ms 쉬기 → 200ms 진동`을 초기안으로 사용하고 실물에서 조정 |
| 이번 사용자 선택 | 제안 자동 만료 후 실제 이탈·재접근한 새 만남은 별도 제한이 없으면 허용. 옛 기록 재전송·계속 근접은 새 만남이 아님 |
| 후속 | 초기화·등록 해제·양도. 기능 자체를 삭제하는 것은 아니며 이번 테스트 완료 조건에서 분리 |

**버튼 관련 차이:** KEY-01의 길게 누르기 등록과 KEY-06의 로컬 알림 중지는 정식 제품 요구로 남아 있다. 이번 버튼 없는 시험 구성은 이를 충족하지 않는다. 앞서 검토한 등록 버튼 3초 길게 누르기는 버튼이 추가될 때의 후보이며 이번에는 적용하지 않는다. XIAO RESET 버튼을 등록·중지 버튼으로 취급하지 않는다.

```text
키링 전원 ON ─ 미등록 테스트 기기의 등록 대기 60초
    ↓
앱 탐색 → 후보 연결·서비스 확인 → 기기 정보·증명 획득
    ↓                                  ※ 발견/연결만으로 본인 기기 확인 아님
서버 등록 검증·계정 귀속 → 키링 반영 확인
    ↓
전체 설정·규칙·시간 전송 → 비휘발성 저장·실제 적용 확인 → 서버 ACK 확인
    ↓
사용 준비 → 폰 없이 키링 A ↔ B 판단·기록 → 조건에 따라 진동·LED
    ↓
앱이 기록 읽기 → 서버 업로드 → 내구 저장 확인을 키링에 전달
    ↓
키링 확인·원본 정리 → 앱 상태 재조회
```

## 1. 키링 탐색·연결

| 항목 | 내용 |
| --- | --- |
| 시작 조건 | 앱의 Bluetooth 사용·검색 권한 준비. 최초 등록은 로그인 상태에서 진행 |
| 앱 요청 예시 | 주변 스캔 → 후보 선택 → BLE 연결 → 서비스 탐색 → TX Notify 구독 → `GET_DEVICE_INFO` |
| 키링 응답 예시 | 아래 `DEVICE_INFO`. 소유권은 2절의 서버 검증으로 확인한다. |
| 성공 판단 기준 | 이 단계는 통신 채널 확보까지. 본인 기기 확인과 계정 등록 성공은 2절에서 별도 확인 |
| 실패·재시도 | 연결 실패·Notify 구독 실패는 연결 미완료. 끊기면 조각 버퍼를 비우고 다시 서비스/기기 확인. 연결 제한 시간·자동 재연결 횟수/간격은 미정 |
| 구현 상태 | `0.1.0-registration`에 60초 등록 창·기기 정보·등록 서명·상태 조회를 구현했다. |
| 실물 검증 여부 | 본 문서 기준 탐색→소유 확인까지 미검증. 문자 송수신 실습과 구분 |

### 등록 대기·광고·동시 연결

| 항목 | 정해진 내용 / 남은 내용 |
| --- | --- |
| 등록 진입 | 앱의 스캔을 시작하고 시험 보드 전원을 켠다. 로컬에 유효한 설정이 없는 시험 기기는 BLE 광고 시작부터 60초 동안 등록 증명을 발급한다. |
| 종료 | 60초 뒤 신규 광고가 종료된다. 연결 중에도 증명 요청은 `REGISTRATION_WINDOW_CLOSED`로 거부된다. 같은 조건에서 보드를 재부팅해 다시 시도한다. |
| 등록 안내 | 등록 대기/선택/성공/실패의 LED·진동 패턴은 미정. 매칭 안내 패턴과 혼동하지 않도록 별도 합의 |
| 일반 검색 광고 (0.9.3) | 기본 광고에는 NUS 서비스 UUID가 있고 Scan Response는 기본으로 비어 있다. 소유자가 공개를 켜면 Scan Response에 공개 이름을 넣는다. 앱 검색 필터는 NUS 서비스 UUID를 사용한다. |
| 서비스 UUID | `6E400001-B5A3-F393-E0A9-E50E24DCCA9E`. 이름·UUID는 서비스 후보 필터이며 인증 수단이 아님 |
| 광고 데이터 | 고정 사용자 ID·프로필·성향 원문을 싣지 않음. 정식 광고의 회전 임시 ID·등록 가능 표시·Manufacturer/Service Data 바이트는 미정 |
| 여러 후보 구분 | 첫 합동 시험에서는 보드를 한 대씩 켜고 연결 후 `DEVICE_INFO.deviceSerial`을 USB `ENROLL` 로그의 serial과 대조한다. 출시용 물리 식별 방식은 미정이다. |
| 앱의 여러 기기 연결 | MVP의 계정 등록 대상은 1개다. 이번 펌웨어는 BLE 연결을 1개로 설정한다. 폰 연결과 키링 간 통신 병행 여부는 실물 검증이 필요하다. |
| 같은 키링에 폰 2대 | 소유 등록은 서버에서 한 계정만 성공해야 함. BLE 선연결 유지/두 번째 연결 거절·BUSY 응답 등 무선 경합 정책은 미정. 먼저 연결됐다는 이유로 소유권을 주지 않음 |

현재 구현의 응답 예시다. `XIAO-0123456789abcdef`는 설명용 일련번호다.

```json
{
  "protocolVersion": 1,
  "type": "GET_DEVICE_INFO",
  "requestId": "info-001"
}
```

```json
{
  "protocolVersion": 1,
  "type": "DEVICE_INFO",
  "requestId": "info-001",
  "deviceSerial": "XIAO-0123456789abcdef",
  "firmwareVersion": "0.1.0-registration",
  "supportedProtocolVersions": [
    1
  ],
  "supportedFunctions": ["GET_DEVICE_INFO", "GET_REGISTRATION_PROOF", "GET_STATUS"],
  "maxFrameBytes": 4096,
  "maxPayloadBytes": 2048,
  "settingsPolicy": "UNRESOLVED",
  "storedSettingsVersion": 0,
  "storedPayloadHash": "",
  "storageHealthy": true,
  "applied": false,
  "timeReady": false
}
```

`supportedFunctions`는 펌웨어가 공개한 기능 목록이다. 유효한 설정이 저장되지 않은 상태는 버전 `0`과 빈 해시로 표시한다. 출시용 인증 전 공개 범위는 미정이다.

앱은 `발견 → BLE 연결 → 기기/소유 확인 → 서버 등록 확인 → 설정 반영 확인`을 구분한다. `CONNECTED`나 `DEVICE_INFO` 수신을 등록·사용 준비 완료로 표시하지 않는다.

<a id="public-scan-name"></a>

### 주변에 이름 공개 (0.9.3)

지원 펌웨어는 D3.1 Y3의 `0.9.3-d31`과 Rev G의 `0.9.3-revg`다. 앱은 `DEVICE_INFO.supportedFunctions`의 `GET_PUBLIC_NAME`, `SET_PUBLIC_NAME`, `CLEAR_PUBLIC_NAME`과 `maxPublicNameBytes`를 확인한다. 앱 홈에만 쓰는 별명은 기존대로 앱에 저장한다. 주변에 공개할 이름은 사용자가 공개 옵션을 켤 때 앱이 명시적으로 전송한다.

- 초기값은 공개 OFF다. ON이면 일반 휴대폰 탐색용 연결 가능 광고의 Scan Response에 `Complete Local Name (0x09)` 하나를 넣는다. 기본 광고의 NUS UUID·송신 설정과 키링 간 확장 광고를 유지한다. 광고에 나온 이름은 주변 BLE 스캐너가 읽을 수 있다.
- Scan Response 31바이트 중 길이·타입에 2바이트를 사용한다. 이름은 **UTF-8 1~29바이트**이며 한글 완성형 음절만 쓰면 **최대 9자**다. 혼합 입력은 `name.encodeToByteArray().size`로 검사한다. 비어 있거나 ASCII 공백만 있는 이름, 제어 문자가 있는 이름을 거절한다.
- 각 명령은 암호화된 NUS 쓰기 특성 `6e400004-b5a3-f393-e0a9-e50e24dcca9e`를 사용한다. 같은 연결에서 `GET_OWNER_CHALLENGE` → 기존 `POST /api/keyrings/{deviceId}/owner-session-grants`로 `permissions: 8` (CONTROL) 허가 발급 → `AUTHORIZE_OWNER`를 먼저 완료한다. 재연결하면 새 소유자 세션으로 진행한다.
- SET·CLEAR 성공 응답은 저장·읽기 확인 뒤 반환한다. 같은 소유 세대라면 재부팅 후에도 유지된다. CLEAR·기기 초기화로 저장 이름을 삭제하고, 초기화 진행 또는 다른 소유 세대에서는 이전 이름을 광고하지 않는다. 서버 소유 해제는 기기 초기화 완료 증명을 받는 기존 순서를 사용한다.

켜기 요청과 응답.

```json
{"protocolVersion":1,"type":"SET_PUBLIC_NAME","requestId":"name-1","name":"팅글 키링"}
{"protocolVersion":1,"type":"PUBLIC_NAME_RESULT","requestId":"name-1","enabled":true,"name":"팅글 키링"}
```

끄기 요청과 응답.

```json
{"protocolVersion":1,"type":"CLEAR_PUBLIC_NAME","requestId":"name-2"}
{"protocolVersion":1,"type":"PUBLIC_NAME_RESULT","requestId":"name-2","enabled":false,"name":null}
```

앱 시작 또는 연결 복구 뒤 현재 설정을 읽는 요청과 응답.

```json
{"protocolVersion":1,"type":"GET_PUBLIC_NAME","requestId":"name-3"}
{"protocolVersion":1,"type":"PUBLIC_NAME_RESULT","requestId":"name-3","enabled":false,"name":null}
```

| `ERROR.code` | 처리 |
| --- | --- |
| `SECURE_TRANSPORT_REQUIRED` | 보호된 쓰기 특성과 BLE 암호화 상태를 확인한다. |
| `OWNER_NOT_BOUND` | 유효한 소유자 설정 저장 또는 초기화 완료 후 등록을 확인한다. |
| `OWNER_SESSION_REQUIRED` | 현재 연결에서 새 CONTROL 허가를 받아 인증한다. |
| `INVALID_PUBLIC_NAME`, `PUBLIC_NAME_TOO_LONG`, `INVALID_MESSAGE` | 이름 형식과 UTF-8 바이트 수, JSON 프레임을 확인한다. |
| `STORAGE_ERROR` | 앱 설정 화면에 실패를 표시하고 동일 동작을 다시 요청한다. 저장을 확인할 수 없는 실행 중에는 이름을 숨긴다. |

Android에서 주변 이름은 현재 `ScanResult.scanRecord.deviceName`에서 표시한다. 이름이 빠진 새 스캔 결과에는 기본 후보 표시를 사용한다. BLE 이름은 소유 판정에 사용하지 않는다. 기존 연결을 종료해 키링 광고가 다시 시작된 뒤 다른 단말에서 확인한다. S20·Fold6 각각 OFF 기본 검색 → ON 이름 검색·연결 → 이름 변경 → OFF 후 이름 없음 → 초기화·재등록 후 이름 없음을 확인한다. ON 상태의 검색 호환성은 두 단말에서 반복 시험한다.

## 2. 키링 식별·계정 등록

| 항목 | 내용 |
| --- | --- |
| 시작 조건 | 로그인·등록 동의, 등록 대기 중인 후보 선택. 현재 등록 키링이 없는 계정 |
| 앱 요청 예시 | 서버 challenge 발급 → 키링에 `GET_REGISTRATION_PROOF` → 받은 증명을 서버 등록 API로 전달 |
| 키링 응답 예시 | `REGISTRATION_PROOF`의 `deviceSerial`을 `DEVICE_INFO`와 대조하고 `deviceSerial`·`proof`를 그대로 전달 |
| 성공 판단 기준 | 서버의 소유 등록 성공과 선택 기기의 대응 확인. 키링 반영 미확인은 별도로 표시하고 사용 준비는 동기화까지 보류 |
| 실패·재시도 | 서버 요청 응답 유실은 같은 HTTP 멱등 키·같은 본문으로 재확인. 서버만 등록 성공한 경우 중복 등록/임의 해제 대신 기존 등록 상태와 미완료 기기 반영을 복구 |
| 구현 상태 | 서버 challenge·등록·검증과 시험 펌웨어의 Ed25519 서명 생성이 구현되어 있다. 실물의 신뢰 공개키 사전 등록, 소유 세션과 기기 등록 결과 반영은 별도 작업이다. |
| 실물 검증 여부 | 실제 기기 증명으로 등록·재연결·동시 등록·부분 성공 복구 미검증 |

### 식별자·증명

| 항목 | 현재 확인된 규칙 |
| --- | --- |
| `deviceSerial` | 기기 고유번호. 현재 서버 입력은 영문 대소문자·숫자·`.`·`_`·`-`의 1~64자. 출고 번호 생성·실물 라벨 방식은 미정 |
| 초기화 후 번호 | 영구 사용 불가 기기를 초기화로 우회하지 못해야 함. 서비스 초기화 뒤 동일 기기로 식별할 번호/신뢰키 보존 방식은 후속 계약. 매번 새 번호를 생성하는 방식은 사용하지 않음 |
| `deviceId` | 등록 후 서버가 주는 양수 int64 `keyring.id`. UUID나 BLE MAC 주소가 아니며 소유 인증 수단도 아님 |
| `challengeId`·`nonce`·`expiresAt` | 서버가 로그인 회원에 귀속해 발급. 현재 코드의 유효 시간은 발급부터 5분이고 최초 등록 때 단회 소비. 앱은 응답의 `expiresAt`을 사용 |
| `proof` | 현재 등록 API는 `protocolVersion`·`keyId`·`signature`. 과거의 `{protocolVersion,payload}` 등록 증명과 다름 |
| 서버 검증 방식 | `tingle-device-proof-v1`, Ed25519. 서버에 해당 serial/keyId로 저장된 신뢰 공개키로 검증. 기기에서 받은 임의 공개키를 신뢰하지 않음 |
| 실제 키링 구현 | 첫 부팅에 기기별 키를 생성하고 비휘발성 저장소에 보존한다. USB 콘솔의 `ENROLL serial=… keyId=… publicKeySpkiBase64url=…`에서 공개 정보를 얻어 BE 테스트 신뢰 목록에 사전 등록한다. 출고용 키 주입·폐기·물리 보호는 미정이다. |
| 본인 확인 경계 | 올바른 기기의 서명과 사용자가 실제로 보유한 기기인지는 구분. 버튼 없는 등록에서 주변 타인의 선등록을 막을 실물 확인 수단·소유 세션은 미정이며 테스트 외 사용 준비 완료로 간주하지 않음 |

### 등록 요청·응답과 서버 연결 순서

```text
앱 → 서버: POST /api/keyrings/registration-challenges (빈 본문)
서버 → 앱: challengeId, nonce, expiresAt, proofProtocolVersion
앱 → 키링: 위 challenge를 전달해 등록 증명 요청
키링 → 앱: deviceSerial, proof(protocolVersion, keyId, signature)
앱 → 서버: POST /api/keyrings
서버 → 앱: HTTP 201, deviceId, status, firmwareVersion
앱 ↔ 키링: 서버 등록 결과·소유 상태 반영 확인 [인증/메시지 미정]
앱 → 다음 단계: 프로필·성향·설정 준비 후 3절 동기화
```

다음 JSON은 호스트 서명 테스트 벡터다. 실물 시험에는 서버가 방금 발급한 challenge와 해당 보드의 응답을 사용한다. BLE 요청의 `challengeId`는 소문자 표준 UUID, `expiresAt`은 서버가 반환한 UTC ISO 8601 형식이다. 60초 BLE 등록 창과 서버 challenge의 5분 유효 기간은 별도다.

현재 [서버 서명 원문 코드](../back/src/main/java/com/e106/tingle/keyring/proof/KeyringProofTranscript.java)를 기준으로 등록 서명은 용도·증명 버전·keyId·serial·challengeId·nonce·만료 epoch 초를 순서대로 묶는다. 저장 ACK는 용도·증명 버전·keyId·serial·deviceId·ownerGeneration·syncRunId·nonce·settingsVersion·payloadHash·`STORED`를 묶는다. 필드 사이와 끝은 LF인 UTF-8 바이트이며 JSON 문자열 자체를 서명하는 방식이 아니다. nonce는 32바이트, 서명은 64바이트를 padding 없는 base64url로 표현한다. 이 서버 원문과 다른 임의 서명 예시를 정식 증명으로 사용하지 않는다.

```json
{
  "protocolVersion": 1,
  "type": "GET_REGISTRATION_PROOF",
  "requestId": "reg-001",
  "challengeId": "11111111-1111-4111-8111-111111111111",
  "nonce": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
  "expiresAt": "2026-11-02T00:05:00Z",
  "proofProtocolVersion": "tingle-device-proof-v1"
}
```

```json
{
  "protocolVersion": 1,
  "type": "REGISTRATION_PROOF",
  "requestId": "reg-001",
  "deviceSerial": "synthetic-device",
  "proof": {
    "protocolVersion": "tingle-device-proof-v1",
    "keyId": "test-key-01",
    "signature": "k4WP42MQvI6MV1VjirbdK9z7F-VOXgmHnzAHVmvU0WXpFxzLed20ObaeTF42B23uHaxnsCOCBSbcYcONtaZRCQ"
  }
}
```

서버 등록 요청은 다음과 같다. 로그인 인증으로 호출하고 발급과 등록은 각각 HTTP `Idempotency-Key` UUID를 사용한다. 같은 작업 재시도에는 해당 키와 본문을 유지한다.

```json
{
  "challengeId": "11111111-1111-4111-8111-111111111111",
  "deviceSerial": "synthetic-device",
  "proof": {
    "protocolVersion": "tingle-device-proof-v1",
    "keyId": "test-key-01",
    "signature": "k4WP42MQvI6MV1VjirbdK9z7F-VOXgmHnzAHVmvU0WXpFxzLed20ObaeTF42B23uHaxnsCOCBSbcYcONtaZRCQ"
  }
}
```

현재 서버 코드는 같은 등록 요청의 재전송에 최초 결과를 복구한다. 만료/소비된 challenge로 **새 등록**을 시작하지 않는다. 실패 원인을 확인해 새 challenge가 필요할 때 새 작업으로 발급받는다. 현재 소유 목록은 `GET /api/keyrings`로 확인하되, 목록의 숫자 ID만으로 BLE 상대 기기 인증을 대신하지 않는다.

**남은 복구 계약:** 서버 등록 응답에는 `deviceId`·`status`·`firmwareVersion`만 있으므로, 이 응답을 키링에 단순 전달해 소유자를 덮어쓰는 메시지를 확정하지 않는다. 서버 등록 성공/키링 반영 대기 상태, 인증된 결과 전달과 재확인 방법은 FE·BE·IoT 공동 미정이다.

## 3. 매칭 설정·성향·시간 동기화

| 항목 | 내용 |
| --- | --- |
| 시작 조건 | 본인 소유 기기 확인, 필수 프로필·유효 성향 결과·희망 조건 준비, 연결·알림 채널 준비 |
| 앱 요청 예시 | 서버에서 동기화 실행과 고정 payload 수신 → `SYNC_SETTINGS`로 전체 전송 |
| 키링 응답 예시 | 현재 보드는 유효한 요청에 `ERROR / POLICY_UNRESOLVED`를 반환한다. 합성 승인 조건의 호스트 테스트는 비휘발성 저장 코어 성공 후 `SETTINGS_RESULT`를 검증한다. |
| 성공 판단 기준 | 해당 기기의 최신 설정 저장·실제 적용·신뢰할 시간 확인, 서버의 유효 저장 ACK 확인을 구분해 모두 충족. 단순 수신·Write 성공·서명 존재만으로 완료하지 않음 |
| 실패·재시도 | 미완성 전송은 폐기하고 처음부터 재전송한다. 유효성 검사 실패 시 기존 정상 설정을 유지한다. 저장 오류나 완료 응답 유실은 재연결 후 저장 상태를 재확인한다. |
| 구현 상태 | 설정 해시·버전 검증, 원자적 저장 및 `STORED` 서명 코어가 있다. 서버 기본 payload 공급자와 보드 승인 어댑터는 각각 `POLICY_UNRESOLVED`를 반환한다. 공식 payload·서버/소유 인증·적용·신뢰 시각 연결이 필요하다. |
| 실물 검증 여부 | 분할 전송·전원 차단·저장 실패·버전 경합·재연결·실제 매칭 적용 미검증 |

### 보내야 할 데이터의 의미

아래는 빠지면 안 되는 **논리 항목**이다. 하나의 확정 JSON/바이너리 payload 스키마는 아직 아니며 앱이 임의로 재구성하지 않는다.

| 항목 | 타입·범위 / 기준 |
| --- | --- |
| 서비스 식별 | 기존 `service_id`의 역할인 서비스 공통 식별값. 사용자 식별자·소유 증명과 구분. UART UUID만으로 우리 서비스 기기 인증을 대신하지 않음. 실제 값·바이트·기기 공통 설정/증명과의 연결은 미정 |
| 회원·소유 대상 | 인증된 회원 참조, 양수 int64 `deviceId`, UUID `ownerGeneration`. BLE 공개 광고에 넣지 않음 |
| 매칭·진동·LED | 각각 boolean, 초기 ON. 기존 사용자 저장값 우선. 진동/LED의 최종 BLE 필드명·저장/전달 연결은 미정 |
| 사진 조건 | `requirePartnerPhoto` boolean, 기본 OFF. 본인 `hasProfilePhoto` boolean도 필요. 사진 파일·URL은 키링 판단 데이터로 보내지 않음 |
| 본인/대상 성별·나이 | 서버가 검증한 최소 정보. 양방향 희망 나이 검사, 정수 만 19세 이상·양끝 포함·상한 없음 지원. 오프라인 생일 경계 계산과 인코딩은 미정 |
| 희망 나이 | 현재 [BE 검증](../back/src/main/java/com/e106/tingle/preference/PreferencesRules.java)은 `ANY`이면 min/max 모두 null, `RANGE`이면 min은 만 19세 이상 정수·max는 min 이상 정수 또는 null(상한 없음). 미선택을 ANY로 간주하지 않음. 최종 기기 바이트 매핑은 미정 |
| 성향·엄격 제외 | AI 승인 최소 계산 입력·Q97 제외 조건·Q98 선택 등. 원본 설문/소개/개인 유형 전체를 복사하지 않음. 필드·벡터 차원·정수화·코드북·정밀도·최대 크기는 AI·IoT·BE 미정 |
| 관계 제한 | 미처리 만남·유효 제안·활성 채팅·거절·차단·종료 제한을 판단할 최소 상태와 유효 기간. 상대의 거절자 정보 등은 불필요하게 노출하지 않음. 표현/전파 방식 미정 |
| `settingsVersion` | 사용자 설정의 양의 정수 버전. 현재 서버 동기화 코드 기준. 시각으로 대체하지 않음 |
| `payloadFormat` | 서버 payload 형식/스키마를 구분하는 문자열. 필드 배치·형식 버전은 미정 |
| `policyVersion`·`matchingRuleVersion` | 기간/관계 정책과 계산 규칙 버전. 설정 버전·BLE 버전과 별개 |
| `settingsSnapshotId`·`syncRunId` | 현재 서버는 같은 UUID를 반환. 당시 설정 사본/저장 ACK 연결에 사용. 앱이 새 UUID로 치환하지 않음 |
| `payloadHash` | 서버가 고정한 실제 기기 payload 바이트의 SHA-256, 소문자 64자리 hex. 분석 입력의 `inputHash`와 다른 값 |
| 시간·기한 | 서버 기준 시각과 적용 정책 기간. 신뢰 시각 검증·전송 지연 보정·허용 오차·재시작 후 기준 복구는 미정 |

현재 [서버 설정 DTO](../back/src/main/java/com/e106/tingle/settings/dto/SettingsResponse.java)는 `matchingEnabled`·`requirePartnerPhoto`·`settingsVersion`만 제공한다. 이 응답만 보내면 성향·시간·진동·LED까지 동기화되는 것으로 취급하지 않는다.

### 서버 실행과 BLE 예시

```text
POST /api/keyrings/{deviceId}/sync-runs (빈 본문) → 실행·당시 payload 고정
GET  /api/keyrings/{deviceId}/sync-runs/{syncRunId} → 동일 실행 재확인
앱 → 키링: 서버 payload를 바꾸지 않고 전체 전달
키링: 검사 → 승인 확인 → 비휘발성 저장·읽기 검증 → 저장 증명
POST /api/keyrings/{deviceId}/sync-runs/{syncRunId}/ack → 기기 저장 증명 전달
서버: 최신 버전·실행·소유 세대·해시·서명 검사 → ACKED / 오류
```

서버 응답에서 아래 BLE 필드만 뽑아 전달한다. `deviceId`·`settingsVersion`은 양수 int64 숫자로 보존하고 `syncRunId`·`ownerGeneration`은 소문자 표준 UUID를 사용한다. `payload`는 패딩 없는 base64url 문자열이며 해시는 디코딩한 원본 바이트를 기준으로 한다. 현재 펌웨어는 `serverTime`을 신뢰 시각으로 적용하지 않는다.

아래는 호스트 테스트 벡터다. `YWJj`는 `abc` 3바이트이며 실물·운영 시험에는 서버가 고정한 payload와 해당 기기 keyId를 사용한다. 시험 빌드의 한도는 프레임 4,096바이트(LF 포함), 디코딩한 설정 2,048바이트다.

```json
{
  "protocolVersion": 1,
  "type": "SYNC_SETTINGS",
  "requestId": "sync-001",
  "syncRunId": "22222222-2222-4222-8222-222222222222",
  "deviceId": 9007199254740993,
  "ownerGeneration": "33333333-3333-4333-8333-333333333333",
  "settingsVersion": 9223372036854775807,
  "payloadEncoding": "base64url",
  "payloadFormat": "test-only-opaque-v1",
  "payload": "YWJj",
  "payloadHash": "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
  "nonce": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
  "keyId": "test-key-01",
  "proofProtocolVersion": "tingle-device-proof-v1"
}
```

입력·해시가 정상이고 해당 보드의 `keyId`가 맞으면 현재 실물 빌드는 다음 오류를 반환한다.

```json
{
  "protocolVersion": 1,
  "type": "ERROR",
  "requestId": "sync-001",
  "code": "POLICY_UNRESOLVED"
}
```

다음 성공 응답은 합성 승인 조건으로 검증한 저장 코어 예시다.

```json
{
  "protocolVersion": 1,
  "type": "SETTINGS_RESULT",
  "requestId": "sync-001",
  "syncRunId": "22222222-2222-4222-8222-222222222222",
  "settingsVersion": 9223372036854775807,
  "payloadHash": "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
  "stored": true,
  "applied": false,
  "timeReady": false,
  "proof": {
    "protocolVersion": "tingle-device-proof-v1",
    "keyId": "test-key-01",
    "signature": "ZkMXsDgolh12Kvzw-RQTEPdQ81qSSQ-lk_SUmBXVDVCiEFD475g6d0tNLmSqhAJ9Rg3vfFBBA0CgbzANF8jwAA"
  }
}
```

`STORED` 서명은 비휘발성 저장 증명이다. `applied`와 `timeReady`는 서명 원문에 포함되지 않으며 시험 빌드는 둘 다 `false`다. 실제 설정 적용 완료 ACK의 형식과 인증 방식은 후속 계약이다.

앱은 현재 기기·실행 ID·버전·해시와 일치하는 실제 `stored=true` 응답을 받은 뒤 `POST /api/keyrings/{deviceId}/sync-runs/{syncRunId}/ack`에 아래 세 필드를 전달한다. 이 예시는 위 호스트 테스트 벡터의 HTTP 요청 형태다.

```json
{
  "settingsVersion": 9223372036854775807,
  "payloadHash": "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
  "proof": {
    "protocolVersion": "tingle-device-proof-v1",
    "keyId": "test-key-01",
    "signature": "ZkMXsDgolh12Kvzw-RQTEPdQ81qSSQ-lk_SUmBXVDVCiEFD475g6d0tNLmSqhAJ9Rg3vfFBBA0CgbzANF8jwAA"
  }
}
```

### 완료·복구 기준

- FE는 `전송 중 → 기기 저장·적용 확인 → 서버 ACK 확인`을 구분한다. 최신 실행이 `ACKED`이고 `stale=false`여도 실제 적용·시간 확인이 빠졌다면 준비 완료를 단정하지 않는다.
- 최종 설정 반영 확인 시각과 전송 시도·상태 조회 수신·만남 업로드 시각을 구분한다. 현재 서버 `ackedAt`은 저장 증명을 서버가 확인한 시각이다. 기기 실제 적용 시각과 앱의 표시 필드/기준은 별도 합의하며 다른 작업 시각으로 채우지 않는다.
- 새 설정을 알고 있으나 미반영이면 해당 기기는 새 매칭 대기. 저장 실패 시 기존 설정/기록을 보존하되 구버전으로 계속 매칭해도 된다는 뜻은 아니다.
- 동기화 도중 서버 설정이 바뀌면 `SYNC_VERSION_STALE` 등을 확인하고 최신 실행을 다시 받는다. 과거 성공 응답으로 현재 완료 표시를 덮어쓰지 않는다.
- 서버 ACK만 실패하면 동일 실행과 저장 증명을 재확인한다. 저장 코어는 승인된 동일 설정 재전송에 증명을 다시 생성한다. 설정 프레임을 재전송할 때 실행 ID·버전·해시·nonce를 유지한다. 실물 전원 재시작 후 재현 여부는 검증 대상이다.
- 재연결 후 `DEVICE_INFO`의 `storedSettingsVersion`·`storedPayloadHash`를 서버 실행과 대조할 수 있다. 이 필드는 저장 상태를 나타낸다. 적용 실행·정책/규칙 버전·신뢰 시간 조회는 후속 계약이다.
- 변경 즉시·수동·재연결·한국시간 자정 자동 동기화를 시도하되 자정 정시 완료는 보장하지 않는다. 재시작 후 신뢰 시간·설정 확인 전 새 매칭 대기, 기존 기록 보존. 단순 24시간 경과만으로 전체 중단하지 않는다.
- 기존 계약의 ‘시간 다시 맞추기’도 시간 적용 결과를 확인해야 한다. 시간만 맞췄다는 이유로 설정 동기화까지 완료 표시하지 않는다. 독립 시간 요청/응답·기준 시각 인증 방식은 미정이며 휴대폰의 임의 시각을 신뢰 시각으로 확정하지 않는다.

### 2026-11-06 성향·최소 프로필 저장 확장 후보

`0.3.1-dev-signed`는 기존 1바이트 ON/OFF와 함께 `tingle-profile-storage-v1` 196B payload를 수신·검증·저장한다. 골든 벡터의 파생 성향과 value/unknown/any/missing, 최소 프로필/희망 조건/기기 옵션을 보존한다. 원본 설문·닉네임·생년월일·사진 원문·토큰을 포함하지 않는다.

기존 SYNC_SETTINGS 외피, Ed25519 v1/v2 서명 원문, deviceNonce, 소유 epoch, STORED 검증을 유지한다. 새 format과 원본 payload의 hash를 실제 서버가 서명해야 한다. 현 BE 발급기/서명 생성기와 FE `signed()`는 아직 1바이트 형식에 묶여 있어 각 파트의 수정과 공동 검토가 필요하다. 바이트 계약·코드북 hash·개인 enum 매핑·정상/거부 사례와 수정 파일은 [설정 인계 문서](dev-settings-signing-handoff-v1.md)의 2026-11-06 절에 있다.

DEVICE_INFO에는 supportedPayloadFormats, storedPayloadFormat, profileStored, storedPayloadBytes를 추가한다. 기기 저장 hash/버전과 STORED 증명을 확인하며 진단 boolean을 인증 증명으로 사용하지 않는다. 저장 완료와 성향 계산·만남 처리·READY는 분리한다. 새 형식의 matchingEnabled는 발견용 광고/스캔만 제어하고 실제 성향이나 개인 필드는 광고하지 않는다. 기존 NVS 레코드·영역·키와 초기화 동작은 유지한다.

성향 payload는 추가 특성 `6e400004-b5a3-f393-e0a9-e50e24dcca9e`의 암호화된 Read/Write로 전송한다. 먼저 개인정보 없는 Read에서 `01`을 받아 같은 연결의 암호화를 확인한 뒤 nonce/서버 서명/프로필 Write를 진행한다. 응답은 기존 TX를 쓴다. 기존 RX의 profile 요청은 SECURE_TRANSPORT_REQUIRED로 거부한다. DEVICE_INFO의 profileStorageCharacteristic/profileRequiresEncryption으로 요구 사항을 알린다. 실제 Android/iOS의 암호화·bond·GATT cache 동작은 합동 시험으로 검증한다.

## 4. 키링 간 오프라인 매칭·진동·LED

| 항목 | 내용 |
| --- | --- |
| 시작 조건 | 양쪽 소유·설정·시간·규칙 준비, 매칭 ON, 저장 공간 확보, 대상 조건/알려진 관계 제한 통과 |
| 앱 요청 예시 | 만남마다 앱 요청 없음. 사전 설정 동기화 후 키링 자체 동작 |
| 키링 응답 예시 | 키링끼리 이벤트 합의·기록 저장. 앱은 재연결 후 5절의 기록을 조회하며 연결 상태로 매칭 성공을 추측하지 않음 |
| 성공 판단 기준 | 충분한 인증/최소 입력 교환과 양방향 판정, 같은 이벤트의 내구 기록. 실제 안내는 근접·설정 조건을 추가 확인 |
| 실패·재시도 | 불완전 교환/인증·저장 실패를 정상 확정으로 표시하지 않음. 동일 이벤트 재시도는 새 기록·추가 진동을 만들지 않음. 양쪽 부분 저장·전원 차단 복구의 바이트/상태 규칙은 미정 |
| 구현 상태 | 가상 키링·보드 간 실습은 별도 자료. 공식 최소 payload·인증·양쪽 확정·실물 안내까지 구현 완료로 볼 근거 없음 |
| 실물 검증 여부 | 휴대폰 없는 실제 2대 계산·양쪽 저장·진동/LED·반복 억제 전체 흐름 미검증 |

```text
발견 → 상대 인증 → 최소 입력 교환 → 양방향 조건·계산
    → 조건 확인 완료순 처리 → 근접/현재 조건 확인
    → 동일 eventId·기준 시각·버전 합의 → 양쪽 저장 확인
    → 실제 감지 기기에서만 진동·LED → 기록 업로드 대기
```

위 단계명은 동작 설명이다. 과거 `HELLO/AUTH`, `PREPARE/COMMIT` 이름을 확정된 실제 무선 메시지로 간주하지 않는다. 사진·프로필 원문·원본 설문·비밀키·고정 사용자 ID를 공개 광고로 교환하지 않는다. 인증 후 필요한 최소 성향/조건·버전·상대 증명을 교환하며 키링 간 바이너리 코덱·암호/세션·회전 ID는 미정이다.

서비스 식별로 후보를 좁힌 뒤 상대 기기와 사용자·소유 연결을 인증한다. 현재 상대와의 매칭 가능 여부를 확인하는 데 필요한 최소 값만 교환하고 거절·차단·활성 채팅 목록 전체를 상대에게 보내지 않는다. 기존 광고·탐색 병행 요구를 포함한 키링 간 BLE 역할과 폰 연결 중 병행 동작은 실물 검증이 필요하다. 7절의 앱 Central·키링 Peripheral 구성을 키링 간 역할까지 확정한 것으로 해석하지 않는다.

### 물리 안내

| 상황 | 동작 |
| --- | --- |
| 양쪽 진동 ON | 양쪽 실제 감지 키링에서 짧은 2회를 한 묶음으로 한 번. 초기안은 `200ms ON → 200ms OFF → 200ms ON` |
| 어느 한쪽 진동 OFF | 양쪽 모두 무진동. 매칭·기록 요건이 맞으면 기록은 유지 |
| LED | 각자 설정 적용. 양쪽 ON이면 동일 이벤트 색, 한쪽 ON이면 그쪽만. 점등 시작부터 30초 연속 점등, 이탈 확인 시 조기 종료. 색 목록·공통 배정 방식·이탈 판정은 미정 |
| 버튼 중지 | 제품 요구는 본인 출력만 즉시 중지, 기록·설정·제안 유지 및 재생 금지. 이번 버튼 없는 구성에는 제공 불가. 전원 재시작으로 대신했다고 판정하지 않음 |
| 상대가 이미 떠남 | 충분한 입력 교환 뒤 후속 계산이 가능한 만남은 기록 경로 유지, 늦은 진동·LED 생략. 불완전한 단순 발견은 성공 기록으로 만들지 않음 |
| 매칭 OFF 반영 | 서버 설정 저장 후 새 제안 생성을 중지하고 키링 반영을 시도. 해당 기기에 반영되면 새 매칭과 현재/남은 매칭 진동·LED 중지. 기존 확정 기록·유효 제안·채팅은 유지하고 기기 미반영 상태는 별도로 표시 |
| 앱 종료·휴대폰 없음·로그아웃 | 미리 준비된 키링의 자체 동작 가능. 로그아웃만으로 저장 설정을 OFF로 변경하지 않음. 미연결 상태에 새 서버 제한이 즉시 전달됨을 보장하지 않음 |

진동·LED OFF만으로 만남 기록·제안·앱 제안 푸시를 중단하지 않는다. 앱 푸시는 키링 출력과 별도이며 앱 권한·로그인·전달 조건을 따른다. 양쪽 출력의 시작 시각 오차·한쪽 실행 중 통신 유실·재시작 복구는 미정/실물 검증 대상이다. 같은 이벤트의 출력은 중복 실행하지 않으며 무선 실패까지 포함한 완전 동시 시작을 보장하지 않는다.

기존 통신·화면 계약의 **본인 키링 LED·진동 테스트**도 별도 기능으로 남긴다. 테스트는 만남 이벤트/제안을 생성하거나 저장된 스위치 값을 바꾸지 않는다. 실행 처리 응답과 실제 부품 동작 확인을 구분한다. 테스트 메시지·패턴·알림 OFF 중 허용 여부·이번 연동 시험 포함 여부는 미정이며 매칭 진동 초기안을 테스트 패턴으로 자동 적용하지 않는다.

### 중복·대기

- 같은 상대의 미접수 만남·서버 대기·유효 제안·활성 채팅과 알려진 거절/차단/종료 제한을 새 이벤트·추가 안내 억제에 반영한다. 원본 삭제와 억제 상태 삭제를 분리한다.
- 명시적 거절은 서버 반영부터 해당 쌍 30일 제한이다. 자동 만료에 이 제한을 부과하지 않는다. 한쪽 기기가 유효한 제한을 알고 있어도 새 매칭을 거부하며, 오프라인 상대가 최신 제한/만료를 즉시 알 것으로 가정하지 않는다.
- 제안 **자동 만료** 후 실제 이탈·재접근한 새 만남은 다른 제한이 없으면 추가 대기 없이 현재 조건을 다시 검사한다. 계속 근처에 있음·옛 기록 재전송·재연결은 새 만남이 아니다.
- 매칭 OFF, 확인된 설정 미반영, 재시작 후 시간 미확인, 안전 저장 공간 부족이면 새 매칭·새 안내 대기. 공간 부족 때문에 유효한 미접수 기록을 덮어쓰지 않는다.
- 공간 확보 뒤 현재 준비/제한/스위치를 확인해 재개하며 지나간 안내를 재생하지 않는다. 실제 저장 한도·재접근 시간/거리·억제 상태 수명은 미정이다.
- 여러 키링/동일 계정의 중복 감지는 현재 MVP 밖이다. 현재도 자기 계정 간 매칭과 동일 만남의 중복 생성은 허용하지 않는다. 서버/앱의 제안 생성으로 다른 기기를 원격 진동시키지 않는다.

**제품·서버 설명의 충돌:** MAT-03 후속 구현 설명에는 ‘제안 생성 이후 영구 재제안 금지’가 있으나, 이번 사용자 확인으로 위 자동 만료 후 새 만남 허용 기준을 유지한다. 서버 관계/이벤트 세대 전환과 키링 억제 해제는 BE·IoT가 이 기준으로 정합화해야 한다. 이 문서 작성만으로 서버 코드/서비스 명세가 수정된 것은 아니다.

## 5. 만남 기록 조회·서버 전달·접수 확인

| 항목 | 내용 |
| --- | --- |
| 시작 조건 | 본인 기기 인증·저장소 복구 후 기록 조회. 서버 전달은 로그인·인터넷 필요 |
| 앱 요청 예시 | `LIST_RECORDS` → `GET_RECORD` 제안 → 서버 `/api/offline-encounters/sync` → 유효 서버 접수 확인을 키링에 전달 |
| 키링 응답 예시 | 아래 목록·본문 예시. 서버 접수 반영 요청/응답은 증명 방식 합의 전 미정 |
| 성공 판단 기준 | 앱 수신·서버 내구 저장·키링의 접수 확인·실제 원본 삭제/공간 확보를 각각 확인 |
| 실패·재시도 | 같은 eventId/submissionId·같은 본문 재전송. 서버 응답 유실은 같은 제출로 최초 영수증 복구. 키링 확인 유실은 원본 보존·동일 영수증 재확인. 삭제 실패는 정리 완료로 표시하지 않음 |
| 구현 상태 | 서버 접수 API·기존 영수증 재조회 코드 있음. 미저장 신규 제출은 현재 `NOT_STORED/POLICY_UNRESOLVED`. 키링 기록 조회·서버 확인 반영·삭제는 미구현 |
| 실물 검증 여부 | 재부팅·중복 업로드·서버 성공 후 BLE 유실·삭제 실패·공간 복구 전체 미검증 |

### 이벤트 생성 때 보존할 내용

이벤트 확정 시 **당시 정보와 증빙을 비휘발성으로 함께 보존**한다. 이후 성향/설정 변경·재부팅 후 현재 설정으로 과거 기록을 재구성하지 않는다.

| 필드/항목 | 타입·필수 | 의미 |
| --- | --- | --- |
| `deviceId`·소유 세대 연결 | 양수 int64·UUID / 필수 연결 | 실제 감지한 내 기기와 당시 소유 구간. 현재 API의 deviceId는 배치 바깥 필드 |
| `eventId` | UUID / 필수 | 양쪽이 합의한 같은 만남 ID. 재시도·재부팅에도 유지 |
| `submissionId` | UUID / 필수 | 내 기기의 해당 만남 제출 ID. 양쪽 제출은 서로 다르며 재전송 때 유지 |
| `occurredAt` | UTC date-time / 필수 | 당시 합의한 만남 시각. 업로드 시각으로 대체하지 않음 |
| 원래 기회 기한 | 신뢰 시각으로 비교할 값 / 필수 보존 | 당시 적용 정책의 기한. 이후 정책/전송 시각으로 늘리거나 재계산하지 않음. 저장 필드/인코딩 미정 |
| `policyVersion` | 문자열 / 필수 | 현재 API는 영숫자로 시작하는 영숫자·`.`·`_`·`-` 1~30자 |
| `matchingRuleVersion` | 비어 있지 않은 문자열, 최대 100자 / 필수 | 당시 적용 계산 규칙 |
| `settingsSnapshotId` | UUID / 필수 | 당시 내 기기에 저장 ACK한 설정 실행과 연결. 현재 서버는 syncRunId 사용 |
| `peerCredential` | 비어 있지 않은 문자열 / 필수 | 당시 상대 기기·회원/소유를 검증할 불투명 증명. 고정 광고 이름/MAC만으로 대체 불가. 정확한 형식·길이·수명 미정 |
| `evidence` | 객체 / 필수 | `{protocolVersion,payload}`. 당시 이벤트·참가 기기·시각·버전/입력 근거를 연결하는 증빙. 규격·서명 원문/인코딩·최대 크기는 미정 |
| 양쪽 참가자·기기 연결 | 필수 검증 근거 | 내/상대 회원과 기기를 인증된 증명에 연결. 공개 광고나 임의 입력 ID로 대체하지 않음. 평문 중복 저장 여부/필드명은 증명 계약에서 확정 |

양쪽은 동일 eventId·참가 쌍·기준 시각에 합의하되, 각자의 submissionId·설정 사본·상대 credential·증빙은 기기별로 다르다. 기기 시간이 신뢰되지 않으면 시각을 만들어 확정하지 않는다. 시간 오차·서로 다른 정책/규칙 버전의 허용 기준은 미정이다.

### 목록·본문 조회 제안

```json
{
  "protocolVersion": 1,
  "type": "LIST_RECORDS",
  "requestId": "list-001",
  "cursor": null
}
```

```json
{
  "protocolVersion": 1,
  "type": "RECORD_LIST",
  "requestId": "list-001",
  "records": [
    {
      "submissionId": "55555555-5555-4555-8555-555555555555",
      "eventId": "44444444-4444-4444-8444-444444444444"
    }
  ],
  "nextCursor": null
}
```

```json
{
  "protocolVersion": 1,
  "type": "GET_RECORD",
  "requestId": "record-001",
  "submissionId": "55555555-5555-4555-8555-555555555555"
}
```

```json
{
  "protocolVersion": 1,
  "type": "RECORD",
  "requestId": "record-001",
  "deviceId": 17,
  "submission": {
    "submissionId": "55555555-5555-4555-8555-555555555555",
    "eventId": "44444444-4444-4444-8444-444444444444",
    "occurredAt": "2026-11-02T00:00:00Z",
    "policyVersion": "example-policy-v1",
    "matchingRuleVersion": "example-rule-v1",
    "settingsSnapshotId": "22222222-2222-4222-8222-222222222222",
    "peerCredential": "<이벤트 당시 상대 증명>",
    "evidence": {
      "protocolVersion": "example-only",
      "payload": "RVhBTVBMRS1PTkxZ"
    }
  }
}
```

이는 서버 제출 항목에 대응하는 본문 예시다. 원래 기회 기한·소유 세대 등 로컬 메타데이터를 조회 응답에 추가할 정확한 필드는 미정이다. 예시 문자열은 실제 증명이 아니며 앱의 전송 기본값으로 사용하지 않는다.

페이지 순서·건수·cursor 유효 기간·새 기록/삭제와의 경합·연결을 넘는 재개 위치는 미정이다. 단순 배열 인덱스를 다음 위치로 쓰는 방식을 확정하지 않는다. 다시 받은 같은 제출을 중복 등록하지 않고, 앱이 읽었다는 이유만으로 원본이나 미전송 수를 줄이지 않는다.

### 서버 전달 형태

현재 서버 경로는 `POST /api/offline-encounters/sync`다. 본인 `deviceId`와 위 `submission` 객체들을 전달한다. 앱이 당시 credential/evidence를 현재 설정으로 생성하거나 바꾸지 않는다.

```json
{
  "deviceId": 17,
  "submissions": [
    {
      "submissionId": "55555555-5555-4555-8555-555555555555",
      "eventId": "44444444-4444-4444-8444-444444444444",
      "occurredAt": "2026-11-02T00:00:00Z",
      "policyVersion": "example-policy-v1",
      "matchingRuleVersion": "example-rule-v1",
      "settingsSnapshotId": "22222222-2222-4222-8222-222222222222",
      "peerCredential": "<이벤트 당시 상대 증명>",
      "evidence": {
        "protocolVersion": "example-only",
        "payload": "RVhBTVBMRS1PTkxZ"
      }
    }
  ]
}
```

증명은 의도적으로 유효하지 않은 설명용 값이다. 현재 서버는 인증·현재 기기 소유·요청 형식 검사를 통과한 신규 미저장 제출에 다음 결과를 반환하며, HTTP 200 자체를 저장 성공으로 해석하지 않는다. 인증/소유·형식 오류는 API 오류로 별도 처리한다. BE 배치는 1~100건·전체 1MiB 이하이며 **BLE 메시지 한도와는 별개**다.

```json
{
  "results": [
    {
      "submissionId": "55555555-5555-4555-8555-555555555555",
      "eventId": "44444444-4444-4444-8444-444444444444",
      "receiptStatus": "NOT_STORED",
      "receiptId": null,
      "canDeleteLocalRecord": false,
      "encounterStatus": null,
      "counterpartDeadlineAt": null,
      "retryable": false,
      "errorCode": "POLICY_UNRESOLVED"
    }
  ]
}
```

### 서버 확인 전달·삭제

여러 기록을 올리면 `results`를 대상 기기·eventId·submissionId별로 대조한다. 일부 성공은 그 기록만 접수 확인 대상으로 처리하고 나머지는 보존해 부분 완료로 표시한다. 배치 전체 HTTP 오류·결과 누락/불일치는 해당 기록의 저장 성공으로 간주하지 않는다. `retryable=false`인 정책 미합의/증명 오류는 원본을 보존하고 원인 해결 후 재확인하며 자동 무한 재전송하지 않는다. 구체적인 재시도 횟수·간격은 계속 미정이다.

```text
앱 기록 수신                      → 삭제 불가
서버 NOT_STORED / 전송 실패        → 원본 보존, 원인에 맞게 재확인
서버 STORED 또는 ALREADY_STORED
  + canDeleteLocalRecord=true
  + 대상 기록과 유효 서버 확인 검증 → 키링에 접수 확인 전달
키링 접수 확인 영속 반영           → 미전송 집계에서 제외
해당 원본 실제 삭제               → 공간 확보 확인
```

- 키링에 보낼 `ACK_SERVER_RECEIPT`, 키링의 `RECEIPT_APPLIED`는 **이름만 제안**이다. 대상 deviceId/소유 세대·eventId·submissionId·receiptId와 서버 저장 결과를 위조/재사용 없이 연결할 증명·요청·응답 형식은 미정이다. 현재 API 영수증 필드만으로 키링이 검증 가능한 서명이 생기는 것은 아니다.
- 따라서 삭제를 승인하는 정상 BLE JSON은 증명 합의 후 작성한다. 현재 단계에서는 앱이 `canDeleteLocalRecord=true`를 임의 생성하거나 검증 없는 삭제 명령을 전송하지 않는다.
- 접수 확인 메시지/응답 유실은 동일 영수증의 재반영으로 복구해야 한다. 이미 처리한 영수증은 같은 결과를 확인하고 다른 기록 삭제·새 이벤트·재진동·기한 연장을 만들지 않는다. 재시작 후 확인/삭제 상태 복구 방식은 미정이다.
- 유효 서버 접수를 해당 키링이 확인하면 상대 기록·제안을 기다리지 않고 원본을 즉시 자동 정리한다. 억제 상태는 별도 보존한다. 삭제 실패 시 원본 정리/공간 확보 완료를 표시하지 않는다.
- 별도로 신뢰할 시각으로 원래 만남 기회 만료를 확인한 원본은 서버 미접수여도 자동 정리한다. 시간 불확실 시 보존 후 시간 동기화로 재판정한다.
- 서버의 상대 기록 대기 마감은 `min(당시 만남 시각+기회 기간, 첫 유효 접수+상대 기록 대기 기간)`. 현재 각 기간은 7일, 제안은 생성부터 별도 24시간이다. 서버 원본 보관과 로컬 정리/미전송 건수는 다른 상태다.

## 6. 초기화·등록 해제·양도

| 항목 | 기준 / 남은 내용 |
| --- | --- |
| 시작 조건 | 현재 소유 계정 로그인·대상 실물 보유. 버튼/고유번호만으로 해제 불가 |
| 앱 요청·키링 응답 | `0.3.0-dev-signed`에 초기화 BLE 명령·기기 완료 증명을 로컬 구현했다. BE API·FE 연결·v2 계약/종단 확인 전에는 앱의 초기화를 활성화하지 않는다. |
| 완료 판단 | 기기 초기화 **및** 서버 소유 관계 해제를 모두 확인해야 완료. 그 뒤 다른 계정 등록 허용 |
| 미전송 기록 | 삭제 동의 / 먼저 휴대폰·서버로 동기화 / 취소를 구분. 명시적 삭제 동의가 없으면 먼저 동기화하고, 실패/미연결 상태에서 강제 삭제하지 않음 |
| 삭제 범위 | 기기 내부의 이전 소유 연결·개인 성향/설정·만남 원본의 삭제/보존 범위와 고정 기기 식별/차단 유지 방식은 후속 합의 |
| 계정에 남는 것 | 서버 계정·프로필·설문·저장된 ON/OFF·기존 유효 제안/채팅 유지. 마지막 키링 초기화 후에는 새 매칭만 준비 대기하며 계정 탈퇴로 처리하지 않음 |
| 실패·재시도 | 한쪽만 성공/연결 끊김은 초기화 완료·양도 가능으로 표시하지 않음. 실행 순서·멱등 키·부분 성공 조회·재개 방식은 아래 검토안으로 제안 |
| 연결·물리 확인 | 현재 보유 기기와의 안전한 처리 경로 필요. 연결 필수 조건·버튼 없는 물리 확인 대안·별도 재인증은 후속 미정 |
| 분실 영구 차단 | 서비스팀 확인 후 서버가 영구 사용 불가로 관리. 초기화·재등록·탈퇴로 차단이 사라지면 안 됨. 기기 전파/차단 증명은 미정, 오프라인 즉시 중지 보장 없음 |
| 구현·실물 검증 | IoT 로컬 구현·호스트 검사·빌드는 6.10절에 기록. 실물 초기화·새 소유자 등록의 종단 검증은 미실시 |

XIAO 전원 재시작·RESET은 로컬 설정이 없는 시험 보드의 등록 창을 다시 여는 용도다. 서비스 초기화에는 기기 데이터 처리와 서버 소유 해제를 검증하는 별도 절차가 필요하다.

### 6.1 초기화 계약 제안 v1의 범위와 결정안

2026-11-05 김종무(IoT) 작성. 아래 API·BLE 이름·필드·서명 규격은 **FE 이재준/황순철·BE 김영빈/가을 검토를 위한 제안**이다. `0.3.0-dev-signed`에 이 제안의 IoT 코드를 로컬 구현·빌드했다. BE/FE의 공급·승인과 실제 보드/앱 종단 시험은 후속으로 추적한다. 초기화 구현은 Story 251, BE는 249, FE는 171에서 연결하며 기존 Story 224·225는 선행 근거다. 담당 일정·포인트는 별도 합의한다.

| 항목 | 제안하는 기준 |
| --- | --- |
| 처리 순서 | BE 소유 확인·작업 생성·서명 허가 → FE의 BLE 전달 → 기기 검증·초기화·영구 완료 증명 → BE 증명 검증·소유 해제·실행 완료 |
| 소유자 인증 | 유효한 로그인 세션과 서버의 현재 소유 관계 확인 후, 대상 기기의 새 challenge에 결속된 Ed25519 초기화 허가 발급 |
| 완료 | 기기 삭제 증명과 서버 소유 해제를 같은 resetRunId로 확인한 서버 응답/조회 결과가 COMPLETED |
| 기기 식별 | serial·기기 개인키/공개키·keyId 보존. 서비스 사용자 데이터는 아래 고정 범위로 초기화 |
| 이전 소유자 차단 | 삭제 전 영구 저장하는 단조 증가 ownershipEpoch와 새 ownerGeneration을 등록·설정 서명에 결속 |
| 미전송 기록 | 기본 REQUIRE_SYNCED. 별도 삭제 동의가 있을 때만 DISCARD_WITH_CONSENT. 집계 불명은 UNKNOWN으로 처리 |
| 데이터 삭제 의미 | OWNER_DATA_V1은 서비스가 읽거나 복원할 수 있는 사용자 데이터의 논리적 초기화 범위. 증명에 dataErasure=LOGICAL을 포함 |

현재 NVS에는 기기 키와 설정이 같은 registration-store에 있으므로 해당 파티션 전체 erase를 초기화 명령으로 사용하지 않는다. 논리적 삭제의 완료 조건은 모든 사용자 데이터 접근·부팅 복원 경로가 비활성화/정리되고 읽기 검증을 통과하는 것이다. 플래시의 과거 물리 바이트까지 복구 불가하게 지우는 별도 요건은 저장 구조·이행 설계를 추가 검토한다. UI와 증명은 이 차이에 맞춰 서비스 초기화 완료를 표시한다.

**BE 제안에 대한 IoT 구현:** `src/board.c`는 registration-store의 NVS key 1에 기기 identity, key 2에 생성 마커, key 10에 설정을 저장한다. 키를 보존하는 선택 정리와 별도 초기화 저널/완료 증명 저장, 부팅 복구, 오래된 설정 차단을 추가했다. 앞선 v0.1.3의 서버 해제 토큰·기기 재확인 단계는 이번 제안에서 제외하고, BE의 완료 트랜잭션과 새 등록 증명의 epoch 대조로 등록 허용을 결정한다. 아래는 해당 흐름으로 맞춘 검토안이다.

저장 위치는 파티션과 항목 ID를 함께 식별한다. 현재 합성 엔진의 keyring-store에도 key 1 설정 항목이 있지만 registration-store의 identity key 1과 다른 데이터다. 초기화 구현에서는 registration-store의 1·2는 보존하고 10은 설정 삭제 대상으로 삼는다. keyring-store의 사용자 설정·기록·억제 상태는 별도로 정리한다. 초기화 저널은 registration-store의 key 20, 이행 완료 마커는 key 21이며 별도 magic으로 저장 형식을 식별한다. 이행 이후 저널이 사라지거나 손상되면 epoch=0으로 되돌리지 않고 부팅을 중단한다.

### 6.2 삭제·보존 범위 OWNER_DATA_V1

서버와 펌웨어가 같은 삭제 범위 식별자 OWNER_DATA_V1을 지원해야 한다. 미지원 범위는 변경 전 거절한다.

| 구분 | 초기화 처리 |
| --- | --- |
| 기기 고유번호·hardware ID | 보존. 같은 실물 기기로 계속 식별 |
| 기기 Ed25519 개인키·공개키·keyId, 키 생성 완료 마커 | 보존. 서버의 기존 credential·공개키 신뢰 관계 유지 |
| 부트로더·펌웨어·서버 검증용 공개키 | 보존 |
| 현재 로컬 소유 연결·사용자 토큰·ownerGeneration | 사용자 연결에서 제거. 이전 generation은 재시도 증명용 최소 저널에만 보존 |
| 성향/희망 조건·설정 payload·버전/hash·동기화 실행 정보 | 제거. 저장 버전 없음·applied=false·timeReady=false로 전환 |
| 기기 시간 동기화 준비·임시 인증/challenge·전송 버퍼 | 무효화. 초기화 복구용 최소 nonce·작업 증명만 별도 보존 |
| 후보·알고리즘 큐·확정/검증 대기 큐·원본 기록·재전송 outbox | REQUIRE_SYNCED이면 서버 접수 증명 확인 후 정리. DISCARD_WITH_CONSENT이면 동의한 대상의 미전송 기록도 정리 |
| 이전 소유자의 반복 억제·상대 캐시·사용자별 관계 사본·알림 예약 | 제거. 이전 안내 재생·부팅 시 옛 snapshot 복원 중지 |
| 기기 Bluetooth bond·LTK/IRK 등 저장된 모든 휴대폰 페어링 | 제거. 연결 중 단절될 수 있으므로 결과는 재연결하여 조회 가능하게 저장 |
| BLE 동작 | 삭제 착수 시 새 매칭·peer 광고/스캔·진동/매칭 LED 중지. 새 소유자의 등록과 정상 설정 동기화 전까지 중지 상태 유지. 복구/등록용 앱 NUS 광고·접속·조회는 제공 |
| 최소 초기화 제어 기록 | ownershipEpoch·진행 단계·최근 resetRunId·이전 ownerGeneration·resetNonce·삭제 범위/정책·서명 결과 보존. 다음 유효 초기화가 이를 대체하며 서버에는 작업 이력을 별도 유지 |
| 서버의 사용자 계정·프로필·설문·ON/OFF·제안·채팅 | 유지. 새 매칭 준비는 등록/최신 동기화 조건으로 다시 평가 |
| 서버의 기존 만남 기록·관계 제한·영구 기기 사용 불가 | 기존 보관/제한 정책 유지. 정상 초기화로 영구 기기 차단 해제 금지 |

논리적으로 삭제한 사용자 snapshot을 장애 복구·재부팅·구버전 레코드 탐색에서 다시 채택하지 않는다. 키/저널을 보존하며 사용자 데이터만 정리하는 NVS 이행과 Bluetooth settings 정리도 기기 완료 판정에 포함한다. 저장소 오류 시 임의 format·키 재생성으로 복구하지 않고 작업을 복구 대기로 유지한다.

### 6.3 소유자 인증과 영구 소유 순번

- 서버가 로그인 JWT·현재 유효 세션·기기 소유자·ownerGeneration·기기 credential/차단 상태를 확인한다. 초기화는 가입/매칭 준비가 해제된 회원도 제품 정책에서 허용한 초기화 전용 경로로 접근한다.
- 앱은 연결한 기기의 serial/keyId를 서버 대상과 대조한 뒤 GET_RESET_CHALLENGE로 기기 서명이 붙은 challenge를 얻는다. 서버는 사전 신뢰한 기기 공개키로 이 증명을 검증한다.
- 서버만 초기화 허가에 서명한다. 허가는 resetRunId·기기·현재 소유 세대·ownershipEpoch 전후 값·기기 challenge·삭제 범위·기록 처리 정책에 결속한다. 앱의 사용자 ID 문자열이나 BLE 페어링만으로 삭제 권한을 부여하지 않는다.
- ownershipEpoch는 기기의 비휘발성 0 이상 int64 순번이다. 최초 지원 펌웨어 이행에서 기존 키·유효 설정을 보존하며 0으로 초기화하고 서버와 대조한다. 새 작업은 fromOwnershipEpoch가 현재 값이고 toOwnershipEpoch=fromOwnershipEpoch+1인지 검사한다. 첫 초기화 시 0→1, 다음은 1→2로 증가하며 저장 오류/오버플로 때 중단한다. 같은 작업의 재시도는 저장된 전후 값을 유지한다.
- 기기는 현재 유효 설정이 있으면 허가의 ownerGeneration이 그 값과 같은지 검사한다. 서버 등록 직후 아직 설정이 없는 유효한 빈 기기는 challenge에 localOwnerGeneration=NONE을 서명하고, 서버가 현재 등록 관계를 확인해 허가한다. 손상된 설정을 빈 상태로 추정하지 않는다.
- 초기화 착수의 영구 저널과 증가한 epoch를 삭제보다 먼저 원자적으로 저장한다. 그 이후 부팅은 같은 작업을 재개하며 이전 소유자의 설정·서명을 적용하지 않는다.
- 기기 challenge의 유효기간은 발급 부팅의 monotonic uptime으로 60초를 제안한다. 반복 읽기는 유효한 같은 challenge를 반환하며, 만료·소비·전원 재시작 후 새 challenge가 필요하다. 서버 허가의 유효성도 이 challenge에 결속하므로 신뢰 UTC가 준비되기 전에도 적용 가능하다.

삭제 착수 시점에는 요청 처리·설정 저장·기록 생성·서버 접수 반영을 직렬화하고 대기 중인 이전 명령을 취소한다. REQUIRE_SYNCED는 잠금 상태에서 실제 미접수 원본이 0인지 다시 확인한다. 미접수 기록이 남았거나 집계가 불명확하면 데이터를 보존하고 PENDING_RECORDS 또는 RECORD_STATE_UNKNOWN을 반환한다. 앱은 기록 접수 후 같은 초기화 실행을 이어간다.

### 6.4 서버 API 제안

기본 경로는 `/api`다. 개발 앱은 기존 base URL과 `/dev` 프록시 규칙을 적용한다. 모든 응답은 JSON, Cache-Control은 no-store, 추적은 X-Request-Id를 사용한다. POST는 UUID Idempotency-Key를 필수로 받는다. deviceId는 양수 int64, epoch는 0 이상 int64 숫자, 실행/소유 세대/challenge ID는 canonical 소문자 UUID다.

| API | 입력·역할 | 성공 |
| --- | --- | --- |
| POST /api/keyrings/{deviceId}/reset-runs | ownerGeneration·recordPolicy·discardConfirmed·challenge·선택 resetRunId. 소유 확인·실행 생성·등록/설정 변경 잠금·서명된 초기화 허가 반환 | 최초 201, 기존 실행/멱등 재사용 200 |
| GET /api/keyrings/{deviceId}/reset-runs/{resetRunId} | 작업 상태·기기 삭제 증명 확인 여부·소유 해제 여부·마지막 오류·다음 단계 조회 | 200 |
| POST /api/keyrings/{deviceId}/reset-runs/{resetRunId}/device-ack | 삭제 완료 proof 전달. 원문 대조 후 증명 저장·현재 소유 해제·관련 sync 실행 무효화·epoch 갱신·실행 완료를 한 트랜잭션으로 처리 | 200, COMPLETED |
| GET /api/keyring-reset-runs?state=IN_PROGRESS | 로컬 작업 식별정보를 잃은 앱의 복구 조회. 요청 계정이 시작한 미완료 실행만 반환 | 200, items 배열 |

device-ack의 요청 본문은 다음 proof 객체다. 서버는 URL의 실행에 저장한 불변 필드로 CLEARED 원문을 재구성해 검증한다. 클라이언트가 보낸 상태 플래그나 다른 실행의 필드를 끼워 넣어 완료를 만들지 않는다.

```json
{
  "proof": {
    "protocolVersion": "tingle-keyring-reset-v1",
    "keyId": "ed25519-0000000000000001",
    "signature": "<device Ed25519 CLEARED signature>"
  }
}
```

소유 해제 뒤에도 원래 요청 계정의 유효한 재로그인 세션은 해당 작업 조회·증명 전달을 할 수 있어야 한다. resetRun에 고정한 initiatorUserId와 이전 ownerGeneration으로 권한을 검사한다. 현재 ownerUserId가 null이라는 이유로 이 복구 경로를 끊지 않는다. 작업은 계정과 기기에 귀속하며 새로운 로그인 family로 복구할 때도 현재 세션을 다시 검증한다.

초기화 시작 입력의 구조 예시다. 예시의 `<...>`는 실기기·서버가 발급한 값으로 채우며 서명 검산 벡터와 구분한다.

```json
{
  "ownerGeneration": "11111111-1111-4111-8111-111111111111",
  "recordPolicy": "REQUIRE_SYNCED",
  "discardConfirmed": false,
  "challenge": {
    "protocolVersion": "tingle-keyring-reset-v1",
    "keyId": "ed25519-0000000000000001",
    "deviceSerial": "XIAO-0000000000000001",
    "ownershipEpoch": 0,
    "localOwnerGeneration": "11111111-1111-4111-8111-111111111111",
    "challengeId": "33333333-3333-4333-8333-333333333333",
    "deviceNonce": "<32-byte canonical base64url>",
    "expiresInSeconds": 60,
    "signature": "<device Ed25519 signature>"
  }
}
```

DISCARD_WITH_CONSENT는 discardConfirmed=true를 필수로 요구하고 서버가 요청 계정·대상·동의 시각·고정 삭제 범위를 기록한다. 초기화 시작을 확인하는 일반 동의와 미전송 기록 삭제 동의를 별도로 표시한다. 같은 실행의 정책을 뒤늦게 바꾸지 않는다. REQUIRE_SYNCED로 시작했다면 기록을 접수한 뒤 그 실행을 계속한다.

서버는 실행별 resetNonce 32바이트를 한 번 생성하고 보존한다. 시작/허가 갱신 응답에는 상태 View와 BLE로 옮길 평면 객체 deviceCommand를 포함한다. View의 구조 예시는 다음과 같다.

```json
{
  "resetRunId": "22222222-2222-4222-8222-222222222222",
  "deviceId": 4,
  "state": "AWAITING_DEVICE",
  "fromOwnershipEpoch": 0,
  "toOwnershipEpoch": 1,
  "deviceResetConfirmed": false,
  "ownershipReleased": false,
  "registrationAllowed": false,
  "nextAction": "SEND_RESET_DEVICE",
  "lastError": null
}
```

| 서버 상태 | 확인한 사실·FE 동작 |
| --- | --- |
| AWAITING_DEVICE | 소유 관계와 작업 잠금 유지. deviceCommand를 전달하거나 기기 결과 재조회. 초기화 확인 전 기존 소유자 표시를 제거하지 않음 |
| COMPLETED | 기기 삭제 증명 검증·서버 소유 해제·이전 sync 실행 무효화·새 epoch 저장을 원자적으로 완료. 영구 차단 등의 추가 제한이 없으면 registrationAllowed=true |

중간 실패는 고정 작업 상태에 lastError를 붙여 복구한다. 시간 초과로 소유를 자동 해제하거나 COMPLETED로 전환하지 않는다. 서버는 상태값에서 확인 플래그를 계산하며 FE가 전달한 true만으로 단계를 올리지 않는다.

nextAction은 SEND_RESET_DEVICE, REFRESH_DEVICE_CHALLENGE, SUBMIT_DEVICE_ACK, NONE 중 하나다. 기기의 완료 증명을 앱이 이미 보유하면 같은 proof를 device-ack에 재전송할 수 있다. lastError는 null 또는 code·message 객체다. registrationAllowed는 현재 기기가 소유 해제·같은 epoch이고 활성 초기화/사용 불가 제한이 없을 때만 true다. 이후 다른 등록이나 새 초기화가 진행되면 과거 실행의 COMPLETED 기록은 유지하면서 registrationAllowed를 false로 표시한다.

### 6.5 BLE 명령·응답 제안

기존 NUS 서비스·RX Write with response·TX Notify, JSON 한 줄+LF를 사용한다. 요청은 현재 파서와 맞게 평면 문자열·정수로 전달한다. BLE protocolVersion은 정수 1, resetProtocolVersion은 별도 문자열 `tingle-keyring-reset-v1`이다. 각 요청은 requestId를 가지며 업무 멱등성은 resetRunId로 판단한다.

| 요청 → 응답 | 필수 필드·의미 |
| --- | --- |
| GET_RESET_CHALLENGE → RESET_CHALLENGE | 응답에 deviceSerial·keyId·ownershipEpoch·localOwnerGeneration·challengeId·deviceNonce·expiresInSeconds·signature. 유효한 빈 소유 상태는 문자열 NONE |
| RESET_DEVICE → RESET_RESULT | 아래 deviceCommand의 서버 서명을 검증하고 영구 저널 기록·사용자 데이터 정리. 완료 시 state=RESET_DONE, result=CLEARED와 기기 proof 반환 |
| GET_RESET_RESULT → RESET_RESULT | resetRunId·resetNonce로 최근 작업의 저장된 결과 조회. 동일 작업의 기존 증명만 반환하며 현재 새 소유자 정보를 포함하지 않음 |

`0.3.0-dev-signed`는 DEVICE_INFO.supportedFunctions에 위 세 요청을 추가하고 resetContractVersion=`tingle-keyring-reset-v1`, ownershipEpoch, ownerState(`BOUND`/`UNOWNED`/`RESETTING`/`UNKNOWN`), supportedProofProtocolVersions를 반환한다. epoch=0은 v1·v2, epoch≥1은 v2를 지원한다. `0.2.3-dev-signed`의 목록과 구분하며 FE는 지원 버전 확인 후 실행한다. 삭제 도중 RESETTING에서는 같은 작업의 복구·상태 조회를 제공하고 등록·설정/challenge는 거절한다. RESET_DONE에서는 복구 조회와 새 epoch의 등록/설정 경로를 열되 서버가 소유 해제 전 등록·설정 발급을 잠근다. peer 광고/스캔은 새 소유자의 정상 설정 저장 전까지 중지한다.

GET_RESET_CHALLENGE 요청은 protocolVersion·type·requestId 세 필드다. GET_RESET_RESULT 요청은 이에 resetRunId·resetNonce를 추가한다. 기존 요청 프레임 한도 4,096바이트(LF 포함), 평면 필드 최대 24개·Notify 응답 한도 1,536바이트를 유지하며 새 명령의 최대 필드 길이로 경계를 검증한다.

서버가 공급할 deviceCommand의 구조 예시다. 앱은 protocolVersion·type·requestId를 지정하고 서명 결속 필드는 그대로 옮긴다.

```json
{
  "protocolVersion": 1,
  "type": "RESET_DEVICE",
  "requestId": "reset-01",
  "resetProtocolVersion": "tingle-keyring-reset-v1",
  "serverKeyId": "dev-ed25519-799e2999c648fda3",
  "keyId": "ed25519-0000000000000001",
  "deviceSerial": "XIAO-0000000000000001",
  "deviceId": 4,
  "ownerGeneration": "11111111-1111-4111-8111-111111111111",
  "resetRunId": "22222222-2222-4222-8222-222222222222",
  "fromOwnershipEpoch": 0,
  "toOwnershipEpoch": 1,
  "challengeId": "33333333-3333-4333-8333-333333333333",
  "deviceNonce": "<32-byte canonical base64url>",
  "resetNonce": "<server 32-byte canonical base64url>",
  "deletionScope": "OWNER_DATA_V1",
  "recordPolicy": "REQUIRE_SYNCED",
  "serverSignature": "<server Ed25519 signature>"
}
```

RESET_RESULT의 CLEARED 응답에는 deviceId·resetRunId·ownerGeneration·fromOwnershipEpoch·toOwnershipEpoch·resetNonce·deletionScope·recordPolicy·dataErasure=LOGICAL·result=CLEARED와 proof 객체(protocolVersion/keyId/signature)를 포함한다. 서명은 6.6의 CLEARED 원문에 대응한다. 최초 RESET_DEVICE는 저널 확정 후 state=RESETTING·result=PENDING으로 응답하고 메인 루프에서 무선 중지·삭제를 수행한다. 진행 중 재조회도 PENDING이며 완료 proof는 정리가 끝난 뒤에만 제공한다. 페어링 정리 시 기존 연결을 끊으므로 FE는 재연결한 뒤 GET_RESET_RESULT에서 CLEARED를 확인한다. 완료 후 같은 RESET_DEVICE의 재전송도 저장된 CLEARED를 반환한다.

기기 완료는 RESET_DONE, 현재 로컬 소유 상태는 UNOWNED로 함께 영구 저장한다. 서버는 이 증명을 받은 뒤 자신의 소유 관계를 해제한다. 기기가 서버 반영 여부를 추정하여 매칭을 재개하지 않으며 새 소유자의 등록·서명 설정 저장으로만 다시 준비한다.

최소 완료 기록에는 resetRunId·resetNonce·대상 serial/keyId/deviceId·이전 ownerGeneration·전후 epoch·삭제 범위/정책·dataErasure·CLEARED 서명과 저장 형식/진행 단계를 보존한다. 서명과 단계의 저장·읽기 검증 후 완료 응답을 반환한다. 같은 실행 조회는 이 결과를 반환하고 초기화를 다시 수행하지 않는다. 새 소유자로 결속한 뒤에도 과거 실행의 조회 결과와 현재 소유 상태를 분리한다.

페어링 삭제로 응답 전에 BLE가 끊겨도 FE는 service 탐색·Notify 재구독·serial/keyId 대조 후 GET_RESET_RESULT를 보낸다. resetRunId·resetNonce는 서버의 권한 있는 작업 조회에서 복구한다. 결과 조회는 읽기 기능이며 해당 실행의 nonce가 맞는 경우에만 최소 저장 결과를 반환한다. 공유 로그에는 nonce·토큰·전체 서명을 남기지 않는다.

NUS 광고 조건에는 has_settings·부팅 등록 창 외에 RESETTING 또는 epoch≥1이고 현재 설정이 없는 복구 상태를 추가했다. 복구용 앱 접속과 등록 증명의 60초 창은 분리한다. 매칭용 peer 광고·스캔 중지 상태에서도 완료 증명 재조회가 가능하다. 새 소유자의 설정 저장 뒤에는 has_settings로 광고하며 이전 실행의 저장 결과도 유지한다.

### 6.6 인증·완료 증명 원문

Ed25519를 사용한다. UTF-8, 아래 필드 순서, 줄마다 LF 하나, 마지막 줄 뒤 LF 하나다. UUID는 소문자 canonical 문자열, int64는 선행 0 없는 십진수이며 epoch는 0을 허용한다. keyId·serverKeyId·deviceSerial은 기존 식별자 규칙 `[A-Za-z0-9._-]{1,64}`를 적용한다. nonce는 32바이트, 서명은 64바이트의 패딩 없는 canonical Base64url이다. localOwnerGeneration의 NONE·고정 result 문자열도 서명에 포함한다. requestId·JSON 공백·표시용 시각은 원문에 넣지 않는다. 초기화 원문 버퍼는 필드 최대 길이에 맞춰 별도로 계산하며 현재 등록 증명의 640바이트 제한을 그대로 재사용하지 않는다.

각 원문의 첫 줄이 서명 용도를 구분한다. 기기 증명은 기존 기기 키, 서버 허가는 펌웨어에 고정된 서버 공개키에 대응하는 비밀키로 서명한다. 각 필드의 의미는 6.3~6.5를 따른다.

```text
# 기기 challenge 증명
TINGLE-KEYRING-RESET-CHALLENGE
tingle-keyring-reset-v1
{keyId}
{deviceSerial}
{ownershipEpoch}
{localOwnerGeneration}
{challengeId}
{deviceNonce}
60
```

```text
# 서버 초기화 허가
TINGLE-KEYRING-RESET-AUTH
tingle-keyring-reset-v1
{serverKeyId}
{keyId}
{deviceSerial}
{deviceId}
{ownerGeneration}
{resetRunId}
{fromOwnershipEpoch}
{toOwnershipEpoch}
{challengeId}
{deviceNonce}
{resetNonce}
OWNER_DATA_V1
{recordPolicy}
```

```text
# 기기 삭제 완료 증명
TINGLE-KEYRING-RESET-CLEARED
tingle-keyring-reset-v1
{keyId}
{deviceSerial}
{deviceId}
{ownerGeneration}
{resetRunId}
{fromOwnershipEpoch}
{toOwnershipEpoch}
{resetNonce}
OWNER_DATA_V1
{recordPolicy}
LOGICAL
CLEARED
```

위 코드 블록의 `# 설명` 줄은 안내용으로 서명 바이트에서 제외한다. 동일 실행의 재조회/재전송은 캐시된 CLEARED 증명을 반환하며, 현재 새 소유 연결을 다시 제거하지 않는다. 다른 작업·소유 순번의 허가는 거절한다.

### 6.7 정상 처리·실패 복구·멱등성

1. FE가 대상 기기 정보와 초기화 지원을 확인하고 초기화/기록 삭제 동의를 구분한다. 초기화 실행 전 취소하면 화면을 종료한다.
2. 기기 challenge를 받아 reset-runs를 생성한다. 서버는 현재 소유 세대와 대상 행을 잠그고 기기별 활성 초기화 실행 하나를 보장한다. 이 시점부터 새 등록·일반 설정 발급/저장 ACK를 보류한다. 기존 기록의 서버 접수는 허용한다.
3. FE가 RESET_DEVICE를 전송한다. 기기는 허가·소유·epoch·미접수 기록을 확인한 뒤 새 매칭/출력 중지, epoch 증가와 RESETTING 저널 저장, 사용자 데이터와 페어링 정리, 읽기 검증, CLEARED 증명 저장 순서로 처리한다.
4. FE가 CLEARED 증명을 device-ack에 전송한다. 서버는 현재 실행·기기 credential·원문을 대조하고, 증명 저장·원본 소유 연결 해제·이전 sync 실행 무효화·epoch 갱신·COMPLETED·등록 잠금 해제를 같은 트랜잭션에서 확정한다. FE는 응답/결과 조회에서 두 확인 플래그와 COMPLETED를 확인한 뒤 완료·재등록 가능 상태를 표시한다.

| 실패 지점 | 복구 기준 |
| --- | --- |
| 시작 API 응답 유실 | 같은 Idempotency-Key·같은 본문 재요청. 새 키라도 같은 기기의 같은 소유 세대/정책인 활성 작업을 재사용 |
| 기기 challenge 만료·전원 재시작, 삭제 미착수 | 새 challenge와 새 HTTP 키, 기존 resetRunId로 허가를 갱신. resetNonce·전후 epoch·삭제 정책은 유지 |
| 기기 삭제 중 전원 차단 | 저장된 RESETTING 저널로 재개. 이전 설정/매칭 복원 차단. 완료 전 서명된 CLEARED 반환 금지 |
| 기기 삭제 완료·BLE 응답 유실 | 저장된 동일 작업 결과 재조회. 페어링을 다시 잡아도 기기 키·epoch와 작업은 보존 |
| 기기 삭제 성공·서버 오류/미연결 | 기기는 RESET_DONE/UNOWNED, 서버는 기존 소유 관계와 잠금 유지. CLEARED를 재전송하고 새 소유자 등록은 대기 |
| 서버 해제·완료 성공, HTTP 응답 유실 | 같은 device-ack 재전송 또는 서버 결과 재조회. 새 ownerGeneration/새 epoch를 추가 생성하지 않음 |
| 완료 후 늦은 과거 reset/ack 도착 | 같은 실행에는 저장된 과거 결과를 반환. 현재 새 소유자/설정은 갱신하지 않음. 다른 실행의 과거 epoch는 거절 |

Idempotency-Key의 범위는 요청 계정·HTTP 동작·대상 경로다. 같은 키에 다른 본문은 409 IDEMPOTENCY_CONFLICT다. 앱은 API별 키·deviceId·resetRunId를 실행 완료까지 로컬에 보존한다. requestId는 BLE 요청 대응용으로 매번 새로 생성할 수 있다.

서버는 미완료 작업의 고정 소유 세대·삭제 정책을 변경하지 않는다. 서로 다른 실행이 있으면 409 RESET_IN_PROGRESS와 권한 있는 기존 실행 조회 경로를 반환한다. 시작 이후 취소/자동 시간 만료로 잠금을 푸는 동작은 v1에 포함하지 않으며, FE의 중간 이탈은 같은 작업의 결과 확인/이어하기로 처리한다. 분실이나 계정 접근 불가 복구는 KEY-04의 서비스팀 절차로 연결한다.

### 6.8 재등록과 이전 소유자 설정 차단

- 서버 resetRun=COMPLETED이고 기기 UNOWNED·동일 ownershipEpoch임을 새 등록 증명으로 대조한 뒤 새 소유자 등록을 허용한다. 영구 차단·credential 폐기·타인 소유·활성 초기화가 있으면 해당 제한을 먼저 적용한다.
- 새 소유 연결 때 새 ownerGeneration을 발급한다. 기기 키는 유지하고, 새 서명 설정을 비휘발성 저장한 뒤 매칭 준비 조건을 평가한다. 새 사용자의 설정 버전은 새 소유 세대에 속하며 이전 소유자의 높은 버전으로 새 값을 거부하지 않는다.
- ownershipEpoch가 1 이상인 기기의 등록·설정은 epoch를 서명에 결속하는 신규 규격을 요구한다. 기존 `tingle-device-proof-v1`·`tingle-dev-settings-proof-v1`에는 이 필드가 없으므로 초기화 이후 경로에서는 거절한다.
- 등록/설정 v2 제안은 기존 등록·STORED·서버 설정 원문의 protocol 문자열을 각각 `tingle-device-proof-v2`, `tingle-dev-settings-proof-v2`로 바꾼 뒤, 기존 순서와 마지막 LF를 포함한 문자열 끝에 **ownershipEpoch의 십진수 문자열과 LF를 추가**한다. v2 등록은 이어서 고정 문자열 `UNOWNED`와 LF를 추가하고 해당 기기 상태에서만 발급한다. v1 원문 자체는 변경하지 않는다.
- 새 설정 요청의 ownershipEpoch는 기기의 현재 값과 같아야 한다. 기기가 이미 소유 세대에 연결돼 있으면 ownerGeneration도 같아야 한다. UNOWNED 상태의 첫 정상 v2 서명 설정으로 새 generation·설정을 원자적으로 결속한다. 서버는 현재 새 소유자에게만 해당 epoch의 설정을 발급한다.
- RESETTING에서는 모든 SYNC_SETTINGS를 보류한다. RESET_DONE/UNOWNED에서는 새 epoch의 정상 v2 서명 설정만 결속한다. 서버는 초기화 실행이 COMPLETED되기 전 신규 설정 발급을 막는다. 옛 epoch·옛 generation·옛 v1 서명은 유효한 기기/서버 키로 서명했더라도 이전 소유자 설정으로 판단한다.
- 서버의 늦은 등록/동기화 응답은 그 실행의 소유 세대·epoch·상태에만 적용한다. 과거 STORED 서명으로 새 소유자의 syncedSettingsVersion·준비 상태를 갱신하지 않는다.

이 규격은 reset 기능과 함께 FE·BE·펌웨어가 적용해야 하는 호환성 변경이다. 기존 사용 중인 epoch=0 기기는 현재 등록·개발 설정 v1 경로를 유지하고, 새 초기화 기능을 사용할 때 필요한 v2 공급 여부를 사전 확인한다. RESET 이후 v2 공급이 준비되기 전에는 재등록 가능 완료를 안내하지 않는다.

### 6.9 오류·시간 초과 형식

HTTP 오류 본문은 현재 BE ErrorResponse와 같은 code·message 두 필드다. 추적 ID는 X-Request-Id 헤더로 반환하고 FE는 code로 분기한다. 다음 reset 전용 코드는 제안이며 구현 시 ErrorCode와 함께 추가한다.

```json
{"code":"RESET_IN_PROGRESS","message":"진행 중인 키링 초기화 결과를 확인해 주세요."}
```

| HTTP | 코드 제안·처리 |
| --- | --- |
| 400 | INVALID_REQUEST, INVALID_RESET_PROOF — 입력 또는 서명 원문/필드 불일치 |
| 401 | 기존 인증 오류 — 재로그인 후 같은 계정의 실행 복구 |
| 403 | NOT_DEVICE_OWNER, DEVICE_BLOCKED — 시작 권한/기기 사용 불가 |
| 404 | RESET_NOT_FOUND — 타 계정 실행도 이 응답으로 숨김 |
| 409 | RESET_IN_PROGRESS, RESET_STATE_CONFLICT, OWNER_GENERATION_MISMATCH, OWNERSHIP_EPOCH_MISMATCH, IDEMPOTENCY_CONFLICT |
| 422 | RESET_UNSUPPORTED, RESET_CONTRACT_UNAVAILABLE — 기기 버전·서명/등록 v2·필수 의존 공급 미준비 |
| 500 | 기존 INTERNAL_ERROR — 결과 조회 후 같은 실행의 상태 확인 |
| 503 | RESET_TEMPORARILY_UNAVAILABLE — 일시 장애 후 같은 실행으로 재시도 |

BLE 오류는 기존 공통 ERROR 틀에 요청 ID를 대응시킨다. 성공하지 않은 요청의 응답 구조 예시는 다음과 같다.

```json
{"protocolVersion":1,"type":"ERROR","requestId":"reset-01","code":"RESET_CHALLENGE_REQUIRED"}
```

추가 코드는 RESET_UNSUPPORTED, INVALID_RESET_PROOF, RESET_CHALLENGE_REQUIRED, OWNER_GENERATION_MISMATCH, OWNERSHIP_EPOCH_MISMATCH, PENDING_RECORDS, RECORD_STATE_UNKNOWN, RESET_IN_PROGRESS, RESET_NOT_FOUND, STORAGE_ERROR, PROTOCOL_UPGRADE_REQUIRED다. BOND_CLEAR_PENDING/BOND_CLEAR_FAILED는 현재 메인 루프의 복구 로그이며 결과 조회는 PENDING을 유지한다. RESET_STATE_CONFLICT는 서버 계약 제안이다. 오류별로 기기 삭제 진행 여부는 GET_RESET_RESULT로 확인한다. 재전송 권한을 오류의 message에서 추론하지 않는다.

FE 응답 대기는 기존 조회 기준과 맞춰 완전 요청 전송 후 10초를 초기 제안값으로 사용한다. 삭제가 계속되면 RESETTING/PENDING 응답과 재조회로 진행하며, 응답 유실/시간 초과는 결과 미확인으로 표시한다. 시간 초과를 완료나 삭제 실패의 증명으로 사용하지 않는다. 재연결/수동 재조회에 같은 실행을 유지하고 자동 재시도 횟수·대기 간격은 실물 측정 후 FE와 정한다.

### 6.10 적용 순서·팀별 작업·완료 검증

| 담당 | 후속 작업 |
| --- | --- |
| BE | resetRun 영구 상태·멱등/잠금·소유 해제 트랜잭션·서명 발급/검증·복구 조회·등록/설정 epoch v2·이전 실행 무효화 구현 |
| IoT | 아래 기능 로컬 구현·호스트 검사·UF2 빌드 완료. 실제 보드의 QSPI/bond 삭제·전원 차단·앱 종단 시험과 팀 검토 남음 |
| FE | 지원 확인·대상 대조·미전송 기록 동의·API/BLE 단계 연결·작업 식별자 보존·재로그인/재연결 복구·상태별 완료 판정 구현 |
| 공동 | 서명 정상/변조/개행/소유 세대/epoch 검산 벡터, 실제 앱·서버·보드 순서 시험, 삭제/보존 범위 확인 |

검토와 v2 서명 원문/검산 벡터 확정 → BE 호환 지원·기능 플래그 준비 → IoT 실물 검증 → FE 단계 연결 → 실제 A 초기화/B 재등록 합동 시험 순서로 적용한다. 지원 준비가 확인된 조합에서 초기화 시작 UI를 활성화한다. IoT 로컬 구현을 FE/BE 구현 완료나 실물 탑재 완료로 안내하지 않는다.

**2026-11-05 로컬 구현·검증 현황**

- `src/reset.c`: 기기 서명 challenge·고정 서버 공개키의 허가 검증·epoch 저널·CLEARED 서명·최근 결과 재조회. `src/request.c`/`nus.c`: NUS 명령, 비동기 PENDING/재조회와 등록 창/복구 광고 연결.
- `src/board.c`/`storage.c`/`main.c`: 기기 identity와 기존 설정 레코드 형식 보존, 선택 삭제·읽기 확인, peer 무선 중지, 시간/버퍼/후보/기록/억제 상태 정리. 페어링은 연결 정리와 `bt_unpair` 뒤에 실제 영속 settings의 keys/ccc/sc/cf 부재까지 확인한다. 전체 플래시 erase는 하지 않는다.
- 미전송 판정은 현재 엔진의 CONFIRMED/REVIEW 원본 수를 소유 데이터 잠금 아래 확인한다. 엔진 저장소 오류는 UNKNOWN으로 거절한다. 공개 GET_STATUS.pendingRecordCount의 null을 0으로 간주하지 않는다. 새 제품 기록 저장소가 추가되면 이 집계와 삭제 범위에 함께 반영해야 한다.
- `tests/test_reset.c`: 모의 영속 저장소의 intent/정리/proof 저장 전후 장애, 재부팅 복구, 동일 실행 재조회, 새 소유자 설정 보존, 잘못된 서명/대상/nonce/challenge, 미전송/UNKNOWN, epoch 경계·v2 설정 JSON·최대 길이 검증.
- `tests/ResetInterop.java`: C가 생성한 합성 공개 벡터의 초기화 AUTH/CHALLENGE/CLEARED·등록/STORED v2를 JDK Ed25519로 별도 검산. LF 누락·CRLF·변조 거부. 기존 실제 BE v1 검증기와의 회귀 검사도 유지한다. 합성 테스트 키는 펌웨어 대상에 포함하지 않는다.
- `tools/test-host.ps1` 및 UF2 빌드 통과. 로그는 로컬 `build/reset-host-tests.log`, `build/reset-registration-tests.log`, `build/reset-build.log`, 공개 합성 벡터는 검사 시 `build/tests/registration/reset-vectors.properties`에 생성한다. 최신 버전·해시·기준 커밋/미커밋 여부는 `build/firmware-manifest.json`에서 확인한다.
- 현재 USB에서 XIAO 실행 장치/부트로더 드라이브를 확인하지 못했으므로 이 작업에서 실물 플래시·서비스 초기화·기기 키 재생성은 실행하지 않았다. FE/BE 코드는 수정하지 않았다.

필수 합동 시험은 다음과 같다.

1. 정상 소유자 A의 초기화 완료 후 B 등록/설정 저장 성공, serial/keyId/공개키 동일 확인.
2. 타인 요청·잘못된 기기·변조 서명·옛 challenge·만료 challenge 거부.
3. 미전송 기록 없음/있음/UNKNOWN, 접수 실패, 별도 삭제 동의·취소 조합.
4. 저널 저장·각 데이터 정리·bond 삭제·증명 저장·서버 해제/완료 트랜잭션의 각 경계에서 전원/연결 차단 후 같은 실행 복구.
5. 서버의 원자적 소유 해제/COMPLETED 전 B 등록 차단, 이후 허용. 옛 ownerGeneration·epoch·설정·STORED 및 초기화 허가 재전송으로 B 상태 변경 차단.
6. 서버/기기 중복 요청·응답 유실·앱 종료·재로그인·로컬 실행 ID 유실 복구. 지연 응답과 다른 실행 혼용 거부.
7. 기존 유효 제안·채팅·계정 설정 유지, 마지막 기기 초기화 후 새 매칭 준비 해제, 영구 차단 기기의 재등록 거부.

### 6.11 FE 화면 준비를 위한 연동 확인서

2026-11-05 FE의 다섯 확인 항목에 대한 현재 근거다. **IoT 로컬 구현 사실, BE !211의 선행 구현, 최종 합의가 필요한 항목을 구분한다.** 이 절을 추가한 것만으로 팀 계약·서버 배포·지원 APK·실물 검증이 완료된 것은 아니다. 초기화 수정 소스와 이 문서는 Story 251 작업 브랜치의 검토 대상이며 승인·develop 병합 상태와 구분한다.

#### 6.11.1 HTTP와 BLE 요청·응답

BE 확인 기준은 [MR !211](https://lab.ssafy.com/s15-final/S15P31E106/-/merge_requests/211)의 `d4c3a572`와 `docs/specifications/functional-spec-v3/keyring-reset-v1.md`다. 실제 서명 어댑터는 미구현이고 기본 OFF다. 아래는 그 선행 코드의 API이며, 6.4의 `/device-ack`·`AWAITING_DEVICE`는 IoT가 먼저 작성한 제안이다. **현재 BE 코드의 `/ack`·`PENDING_DEVICE`와 최종 명칭을 FE·BE가 하나로 확정해야 한다.** FE는 두 이름을 임의로 혼용하지 않는다.

| 동작 | BE !211의 현재 경로 | 현재 입력 / 결과 | 실제 연결 전 남은 항목 |
| --- | --- | --- | --- |
| 시작 | POST /api/keyrings/{deviceId}/reset-runs | Idempotency-Key UUID, `{"discardUnsentConfirmed":false}`. 최초 201 / 동일 요청 200 | 기기 서명 challenge·현재 epoch 입력과 검증, 삭제 정책 결속 |
| 결과 조회 | GET /api/keyrings/{deviceId}/reset-runs/{resetRunId} | 기존 실행 View, 200 | resetNonce·전후 epoch·완료 판정/현재 재등록 허용 정보의 제공 규격 |
| 허가 갱신 | POST /api/keyrings/{deviceId}/reset-runs/{resetRunId}/command | 현재 본문 없음, 같은 실행의 명령 갱신 | 새 기기 challenge 입력. 서버 300초 만료 전에도 기기 challenge 만료/재부팅 때 갱신 필요 |
| 기기 증명 제출 | POST /api/keyrings/{deviceId}/reset-runs/{resetRunId}/ack | 현재 `{"proof":"<직렬화 규격 미확정>"}`, 검증 후 200 / COMPLETED | 펌웨어 proof 객체와 문자열 포장 여부·Ed25519 실제 검증 어댑터 |
| 로컬 실행 ID 유실 복구 | 아직 해당 목록 API 없음 | 6.4의 진행 실행 목록 조회는 제안 | 현재 로그인 계정의 실행·resetRunId·resetNonce 복구 조회 필요 |

경로는 `/api` 기준이며 개발 APK의 `/dev` 프록시는 기존 설정에 따른다. HTTP 요청·응답은 JSON이며 인증과 no-store 기준은 BE 명세를 따른다. Start의 false는 REQUIRE_SYNCED, true는 별도 미전송 삭제 동의를 받은 DISCARD_WITH_CONSENT로 대응하는 안이며, 실제 서명 필드 매핑은 BE가 확정한다.

BE 선행 View의 구조 예시다. 아래 `<...>`와 뒤 BLE 예시의 값은 표시용이며 검산 벡터가 아니다. 생략/null 정책과 기기 명령의 문자열 포장도 최종 BE 공급 규격으로 확정한다.

```json
{
  "resetRunId": "22222222-2222-4222-8222-222222222222",
  "deviceId": 4,
  "ownerGeneration": "11111111-1111-4111-8111-111111111111",
  "state": "PENDING_DEVICE",
  "discardUnsentConfirmed": false,
  "createdAt": "2026-11-05T06:00:00Z",
  "commandExpiresAt": "2026-11-05T06:05:00Z",
  "deviceCommand": "<BE 실제 서명 어댑터가 공급할 명령>"
}
```

동일 실행의 완료 조회/ACK 성공은 state=COMPLETED와 completedAt을 반환하고 deviceCommand를 제공하지 않는 것이 BE 선행 구현이다. FE는 기기 증명을 해당 실행에 제출했고 BE가 이를 검증·소유 해제와 함께 커밋했다는 응답/조회 의미가 확정된 뒤 완료 화면에 연결한다. 현재 BE View에 없는 deviceResetConfirmed/ownershipReleased/registrationAllowed를 이미 공급되는 필드로 가정하지 않는다.

BLE는 NUS RX Write with response / TX Notify를 사용한다. UTF-8 JSON 한 줄 뒤 LF, 최대 20바이트 단위 쓰기를 유지한다. 아래 들여쓰기는 가독성용이다. requestId는 요청마다 지정하며 서버 서명에 결속된 필드를 임의로 바꾸지 않는다.

1. GET_DEVICE_INFO의 firmwareVersion·supportedFunctions·resetContractVersion·ownershipEpoch·ownerState를 확인한다. 초기화 세 명령과 버전이 확인되지 않으면 해당 기기에 초기화 명령을 보내지 않는다.
2. 아래 challenge를 받아 BE에 전달한다. expiresInSeconds=60은 기기 uptime 기준의 유효기간이며 작업 전체 제한 시간이 아니다.

```json
{"protocolVersion":1,"type":"GET_RESET_CHALLENGE","requestId":"reset-challenge-01"}
```

```json
{
  "protocolVersion": 1,
  "type": "RESET_CHALLENGE",
  "requestId": "reset-challenge-01",
  "resetProtocolVersion": "tingle-keyring-reset-v1",
  "keyId": "ed25519-0000000000000001",
  "deviceSerial": "XIAO-0000000000000001",
  "ownershipEpoch": 0,
  "localOwnerGeneration": "11111111-1111-4111-8111-111111111111",
  "challengeId": "33333333-3333-4333-8333-333333333333",
  "deviceNonce": "<32-byte canonical base64url>",
  "expiresInSeconds": 60,
  "signature": "<device Ed25519 signature>"
}
```

3. BE의 실제 서명 허가를 6.5의 **전체 RESET_DEVICE 예시**에 맞춰 평면 JSON으로 전송한다. 최초 착수 응답은 다음과 같고 아직 완료 증명이 없다. 페어링 삭제로 이 응답도 유실될 수 있다.

```json
{"protocolVersion":1,"type":"RESET_RESULT","requestId":"reset-01","resetRunId":"22222222-2222-4222-8222-222222222222","state":"RESETTING","result":"PENDING"}
```

4. 연결이 끊기면 같은 serial/keyId의 기기를 다시 찾아 서비스 탐색·Notify 구독을 수행한 뒤 다음 명령으로 조회한다. 저장된 결과 조회에는 새 challenge가 필요하지 않다.

```json
{
  "protocolVersion": 1,
  "type": "GET_RESET_RESULT",
  "requestId": "reset-result-02",
  "resetRunId": "22222222-2222-4222-8222-222222222222",
  "resetNonce": "<server 32-byte canonical base64url>"
}
```

완료 응답의 전체 구조는 다음과 같다. signature는 6.6의 CLEARED 원문에 대한 기기 서명이며 FE가 재생성하지 않는다. 이 결과는 기기 작업 완료이며 서버 소유 해제 완료와 구분한다.

```json
{
  "protocolVersion": 1,
  "type": "RESET_RESULT",
  "requestId": "reset-result-02",
  "deviceSerial": "XIAO-0000000000000001",
  "deviceId": 4,
  "resetRunId": "22222222-2222-4222-8222-222222222222",
  "ownerGeneration": "11111111-1111-4111-8111-111111111111",
  "fromOwnershipEpoch": 0,
  "toOwnershipEpoch": 1,
  "resetNonce": "<server 32-byte canonical base64url>",
  "deletionScope": "OWNER_DATA_V1",
  "recordPolicy": "REQUIRE_SYNCED",
  "dataErasure": "LOGICAL",
  "state": "RESET_DONE",
  "result": "CLEARED",
  "proof": {
    "protocolVersion": "tingle-keyring-reset-v1",
    "keyId": "ed25519-0000000000000001",
    "signature": "<device Ed25519 CLEARED signature>"
  }
}
```

기기 오류는 기존 공통 형식이다. 예를 들어 미전송 원본이 남아 기본 정책으로 착수할 수 없으면 다음을 반환한다.

```json
{"protocolVersion":1,"type":"ERROR","requestId":"reset-01","code":"PENDING_RECORDS"}
```

#### 6.11.2 앱에 보관할 값과 재시도 규칙

| 앱이 계정별로 영속 보관할 값 | 용도 |
| --- | --- |
| 작업을 시작한 계정 식별, deviceId·deviceSerial·keyId | 다른 로그인 계정·다른 실물·다른 실행의 결과 혼용 방지 |
| resetRunId·resetNonce·이전 ownerGeneration·from/toOwnershipEpoch | 서버/BLE에서 동일 작업 복구, 결과 필드 대조 |
| 시작 Idempotency-Key·시작 본문·선택한 recordPolicy/삭제 동의 | 동일 시작 재시도, 정책 변경 여부 확인 |
| 마지막 확인 단계·기기 CLEARED 응답/기기 proof·서버 완료 확인 결과 | 기기 중복 삭제 없이 ACK 재시도와 화면 복구 |

BE는 시작/조회 응답으로 이 값들을 공급하는 최종 규격을 정해야 한다. 로그인 토큰·nonce·증명 전체를 일반 분석/공유 로그에 남기지 않는다. 다른 계정으로 로그인하면 그 계정의 작업으로 이어붙이지 않는다. 원래 계정 재로그인 후 복구하며, 앱 로컬 값을 잃었을 때는 권한 있는 BE 복구 조회가 필요하다.

- 앱 재시작은 먼저 서버의 같은 실행 결과를 조회한다. 해당 실행의 검증·소유 해제 완료가 확인되면 기기 초기화를 다시 실행하지 않는다.
- 서버 완료 미확인이고 로컬 CLEARED가 있으면 같은 증명을 ACK에 다시 제출한다. 없으면 BLE GET_RESET_RESULT를 조회한다. PENDING은 진행 중이며 새 실행을 만들지 않는다.
- 기기 RESET_NOT_FOUND는 삭제 실패/미착수의 확정 증거가 아니다. 대상·실행·nonce와 서버 상태를 먼저 대조한다. 아직 미착수임이 확인되고 같은 실행을 계속할 때만 새 기기 challenge로 허가를 갱신한다. 이미 완료한 과거 실행을 무조건 다시 삭제하지 않는다.
- 기기 RESET_CHALLENGE_REQUIRED는 새 challenge와 같은 resetRunId의 새 서버 허가가 필요하다. resetNonce·전후 epoch·삭제 정책은 변경하지 않는다. 서명·기기·세대·epoch 오류는 자동 재서명/우회하지 않고 계약/상태 확인 대상으로 표시한다.
- 시작의 같은 HTTP 본문 재시도는 같은 Idempotency-Key를 사용한다. BLE requestId는 새로 지정할 수 있지만 업무 실행은 resetRunId로 유지한다. ACK·명령 갱신 API의 별도 멱등 헤더 여부는 최종 BE 규격에 따른다.
- 기존 10초 응답 대기는 완전한 BLE 요청 전송 후 FE의 결과 미확인 판정용 제안값이다. 시간 초과·앱 종료·취소 화면·연결 끊김으로 완료 처리하거나 서버 소유를 해제하지 않는다. 자동 조회 간격·재시도 횟수는 아직 확정하지 않았다.
- 삭제 정책은 시작 전에 선택한다. 현재 제안/BE 선행 구현은 같은 실행의 정책 변경과 진행 작업의 자동 취소를 지원하지 않는다. REQUIRE_SYNCED로 시작한 뒤 DISCARD로 몰래 바꾸거나 다른 키로 새 실행을 만드는 복구를 구현하지 않는다.

#### 6.11.3 서버 완료 응답 유실과 소유 해제 후 조회

BE !211의 현재 서비스는 완료 실행을 원래 실행 계정으로 조회하고 같은 증명을 재제출할 때 현재 소유자 일치를 다시 요구하지 않는다. 기존 계정의 유효 세션과 저장된 실행 귀속은 확인한다. 따라서 **서버 커밋 뒤 HTTP 응답을 잃은 경우 같은 실행 GET 또는 같은 ACK 재시도로 복구하는 구조**다. 현재 검증 근거는 합성 프로토콜 통합 검사이며 실제 기기 서명 경로 검증은 후속이다.

새 소유자가 등록한 뒤 늦게 도착한 옛 ACK는 이미 저장된 과거 실행 결과만 반환해야 한다. 이를 현재 새 소유자의 해제로 다시 적용하지 않는다. 과거 resetRun=COMPLETED는 그 실행의 완료이며, 현재 기기가 여전히 UNBOUND이거나 재등록 가능하다는 뜻은 아니다. FE의 새 등록 가능 표시는 BE가 확인한 현재 상태를 따른다. 서버 실행 이력/증명의 보관 기간은 BE의 보관 정책 확정이 필요하며 무기한 보관을 약속하지 않는다.

#### 6.11.4 기기 완료 증명 재조회·보관·새 소유자 접근

| 항목 | 현재 IoT 구현 |
| --- | --- |
| 재조회 | 같은 기기에 GET_RESET_RESULT(resetRunId, resetNonce). 일치하는 최근 작업만 반환 |
| 보관 수·기간 | 최근 초기화 1건. 날짜/일수 기준 자동 만료 없음. **다음 유효한 초기화의 RESETTING 저널을 영구 기록할 때 이전 결과를 대체** |
| 전원/연결 차단 | 저널·완료 proof는 NVS에 보존. 부팅 복구 후 동일 결과 조회. 저장 오류/손상에서 임의 키 재생성·완료 추정 금지 |
| 새 소유자의 등록·설정 | 최근 과거 결과를 삭제하지 않음. 옛 작업 조회는 옛 ownerGeneration·전후 epoch·증명만 반환 |
| BLE 접근 기준 | 계정 로그인 검증은 기기에서 하지 않음. 기기 근처에서 NUS 접속 및 실행 ID·32바이트 resetNonce를 가진 요청의 일치로 제한. 현재 계정 소유권 인증과 동일한 의미로 안내하지 않음 |
| 서버 접근 기준 | 원래 실행 계정의 유효 세션·작업 귀속 확인. 새 계정에 이전 소유자의 실행/nonce를 공급하지 않는 최종 BE 계약 필요 |
| 옛 명령 재전송 | 최근 실행 ID·nonce 일치 시 저장 결과만 반환. 새 소유자의 데이터를 다시 삭제하지 않음. 다른 과거 실행/epoch는 거절 |

RESETTING과 초기화 이후 설정이 없는 복구 상태에는 NUS 광고를 제공한다. 새 설정 저장 뒤에도 NUS 조회는 가능하다. 새 등록 증명 발급의 60초 창과 완료 결과 조회 가능 시간을 혼동하지 않는다. 다음 초기화로 최근 결과가 교체된 뒤에는 기기에 모든 옛 실행 이력의 조회를 요구할 수 없으며 서버 보관/앱이 이미 보관한 증명으로 복구한다.

#### 6.11.5 미전송 기록과 지원 버전

**실제 앱 NUS의 미전송 기록 열람·제품 서버 업로드·서명된 접수 영수증 반영은 아직 연결되지 않았다.** `src/phone.c`의 바이너리 READ_RECORD/LAB_STORED_RECEIPT는 합성 시험용이고 실제 앱의 인증된 업로드 완료 경로로 사용하지 않는다. 따라서 0.3.0의 초기화 지원을 “먼저 업로드”까지 구현됐다는 의미로 전달하지 않는다.

| 항목 | 현재 상태 / FE 표시 기준 |
| --- | --- |
| 지원 버전 | `0.3.0-dev-signed` 로컬 빌드에 초기화 세 명령·epoch v2 포함. 실제 보드 탑재·BE/FE 종단은 미확인. 0.2.3은 초기화 명령 미지원 |
| 기능 확인 | 버전만 비교하지 않고 DEVICE_INFO의 지원 명령·resetContractVersion·저장 상태와 서버 기능 공급을 함께 확인 |
| 미전송 공개 조회 | GET_STATUS.pendingRecordCount는 현재 null, storageState는 UNKNOWN. 0이나 “없음”으로 변환하지 않음 |
| 기본 정책 | REQUIRE_SYNCED. 초기화 착수 잠금 아래 현재 엔진의 CONFIRMED/REVIEW 원본이 0인지 확인. 남으면 PENDING_RECORDS로 보존 |
| 별도 삭제 동의 | 동의를 서버가 확인하고 DISCARD_WITH_CONSENT를 명령 서명에 결속한 경우에만 미전송 원본 포함 정리. FE boolean만으로 삭제 허가하지 않음 |
| 집계 불명/저장 오류 | RECORD_STATE_UNKNOWN 또는 STORAGE_ERROR. DISCARD 동의가 있어도 UNKNOWN을 0으로 추정하거나 정상 삭제 완료로 표시하지 않음 |
| 먼저 업로드 | 제품용 기록 조회·인증된 업로드·서버 내구 저장 증명·기기 원본 정리 확인 경로의 추가 구현/공동 계약 필요. 완료 전 해당 버튼은 미지원/연결 준비 중으로 표시 |

선업로드를 지원할 때의 완료 조건은 `기기 원본 읽기 → BE 내구 저장 및 검증된 접수 영수증 → 기기 영수증 검증·원본 정리 → 실제 잔여 수 재확인 → 같은 초기화 실행 재개`로 맞춘다. 앱이 읽었거나 HTTP 전송만 성공한 것으로 기기 원본을 지우지 않는다. 정확한 제품 기록 명령·API·서명·지원 버전은 아직 공급되지 않았으므로 새 명령명이나 후속 버전 번호를 확정하지 않는다.

FE 화면의 후보 상태는 다음과 같다. 이 이름은 FE 내부 상태 제안이며 BE/BLE의 실제 state 값을 변경하지 않는다.

| 화면 의미 | 확인 근거·다음 동작 |
| --- | --- |
| 지원/대상 확인 | 펌웨어 기능·서버 준비·serial/keyId·소유 대상 대조 |
| 기록 처리 선택/지원 대기 | 미확인 건수는 그대로 표시. 선업로드 미지원과 별도 삭제 동의를 구분. 실행 생성 전 정책 선택 |
| 기기 초기화 대기/진행 | 서버 PENDING_DEVICE, 기기 PENDING 또는 결과 미확인. 같은 실행 유지 |
| 기기 완료 / 서버 반영 대기 | 일치하는 CLEARED 보관 후 ACK 전달/재시도. 전체 완료 아님 |
| 완료 | 해당 실행의 기기 증명 검증과 서버 소유 해제의 원자적 완료를 BE 응답/조회로 확인 |
| 결과 확인/복구 필요 | 타임아웃·연결 끊김·로그인 만료·저장 오류. 새 작업 생성이나 자동 소유 해제 없이 같은 실행 복구 |

## 7. 공통 BLE 메시지·전송·오류

### 서비스·송수신 방향

시험 펌웨어는 아래 NUS를 사용한다. 앱은 Central, 키링은 Peripheral이다.

| 용도 | UUID | 방향·방식 |
| --- | --- | --- |
| 서비스 | `6E400001-B5A3-F393-E0A9-E50E24DCCA9E` | 앱의 서비스 탐색 |
| 키링 RX | `6E400002-B5A3-F393-E0A9-E50E24DCCA9E` | 앱 → 키링, Write with response |
| 키링 TX | `6E400003-B5A3-F393-E0A9-E50E24DCCA9E` | 키링 → 앱, Notify 구독 |
| TX CCCD | `0x2902` | 연결마다 Notify 활성화 |

기기 정보·상태는 RX에 요청을 쓰고 TX Notify로 받는다. RX/TX 데이터의 GATT Read는 지원하지 않는다. RX는 Write Without Response와 Prepare Write를 거부한다. 계정 귀속은 서버 증명 검증, 설정·삭제 권한은 인증된 소유 세션을 기준으로 별도 판단한다.

| 공통 항목 | 규칙 / 미정 |
| --- | --- |
| 형식 | 앱↔키링은 UTF-8 JSON, 한 줄 뒤 LF. 키링↔키링 바이너리의 상세 코덱은 별도 미정 |
| `protocolVersion` | BLE JSON 버전, 정수 `1` 초안. `proof.protocolVersion`/`evidence.protocolVersion` 문자열이나 계산 규칙 버전과 별개 |
| `type`·`requestId` | 메시지 종류와 UTF-8 1~36바이트 요청 ID. 응답에 같은 ID를 넣으며 형식 오류로 ID를 읽지 못하면 `null`이 올 수 있다. |
| 요청 ID·멱등 처리 | 상태 조회 등 읽기 재요청은 새 requestId. 변경 BLE 메시지의 requestId 재사용 규칙/기기 결과 보존 기간은 미정. HTTP 멱등 키·syncRunId·eventId/submissionId·receiptId는 각각 업무 식별자로 고정하며 requestId 하나로 대체하지 않음 |
| 늦은 응답 | 현재 연결 세대·대상 기기·요청/업무 ID·버전이 맞는 응답만 반영. 끊김 시 조각/대기 요청을 정리하고 과거 응답으로 현재 화면·다른 계정/기기를 갱신하지 않음 |
| 순서 | 양방향 조각 최대 20바이트. 앱은 직전 Write 응답을 받은 뒤 다음 조각을 쓰고, 완전한 Notify 응답을 받은 뒤 다음 요청을 시작한다. |
| 완성 확인 | Notify 1회나 GATT Write 성공을 업무 완료로 보지 않음. LF까지 바이트 결합 후 JSON 해석. 설정은 전체 완성·해시/버전·저장/적용 확인이 추가로 필요 |
| 인증·권한 | 등록 전 허용 명령, 소유 세션 생성·만료·재연결 확인, 암호화·재전송 방지·권한 오류는 IoT·BE·FE 미정 |

시험 빌드의 요청은 문자열과 음이 아닌 정수로 구성된 단일 JSON 객체다. 중첩 객체·배열·boolean·null은 요청에서 지원하지 않는다. 서버 응답 전체를 전달하는 대신 각 BLE 요청에 필요한 필드를 선택한다. `proof` 객체와 `applied` 등은 키링 응답에 포함될 수 있다.

### 상태 조회 수치를 다른 기능에 그대로 적용하는가

| 대상 | 조각·최대 크기 | 제한 시간·재시도 |
| --- | --- | --- |
| 기존 상태 조회 | 조각 최대 20 **바이트**, JSON+LF 최대 512바이트. UTF-8 문자 수가 아님 | 요청 전송 완료 후 완전 응답 10초, 동시 조회 1건. 횟수/간격은 미정 |
| 기기 정보·등록 증명 | 시험 빌드의 요청 프레임 한도 4,096바이트(LF 포함), 조각 최대 20바이트 | 스캔·연결·등록 응답의 앱 타임아웃은 미정. 서버 challenge 만료와 별개 |
| 설정 | 동일 프레임 한도와 디코딩한 설정 2,048바이트 한도 | 앱의 전송 타임아웃과 자동 재시도 횟수는 미정. 중단 후 처음부터 재전송 |

20바이트는 시험 빌드의 조각 한도다. 512바이트와 앱 응답 대기 10초는 상태 조회 초안의 기준이다. 펌웨어에서 쓰는 10초는 Notify 버퍼 부족 재시도 예산이다. 타임아웃·연결 끊김 시 미완성 버퍼와 대기 요청을 폐기하고, 재연결 → 서비스 탐색 → TX 재구독 → 기기 확인 후 다시 요청한다.

### 오류 구분

| 구분 | 코드·내용 |
| --- | --- |
| 시험 BLE 공통 | `INVALID_MESSAGE`(형식), `UNSUPPORTED_PROTOCOL`(버전), `UNSUPPORTED_TYPE`(종류), `REGISTRATION_WINDOW_CLOSED`(등록 창 종료) |
| 시험 BLE 설정 | `INVALID_SETTINGS`, `INVALID_PAYLOAD`, `OWNER_MISMATCH`, `STALE_SETTINGS`, `VERSION_CONFLICT`, `STORAGE_ERROR`, `PROOF_ERROR`, `POLICY_UNRESOLVED` |
| GATT·연결 | 조각 길이·쓰기 방식 오류 또는 처리 중 새 요청은 GATT 오류가 올 수 있다. 초과 프레임이나 전송 불능 시 연결이 끊길 수 있다. |
| 현재 BE 코드 | `DEVICE_ALREADY_BOUND`(409), `IDEMPOTENCY_CONFLICT`(409), `SYNC_VERSION_STALE`(409), `INVALID_DEVICE_PROOF`(422), `POLICY_UNRESOLVED`(422). 세션/권한/리소스 오류는 기존 API 규칙 적용 |
| 출시용 BLE 오류 — 미정 | 인증·권한, 대기/재시도 세부 분류와 최종 코드 합의 |

```json
{
  "protocolVersion": 1,
  "type": "ERROR",
  "requestId": "info-001",
  "code": "UNSUPPORTED_TYPE"
}
```

이는 공통 오류 모양의 예시다. 응답 없는 구 펌웨어가 반드시 이 오류를 반환한다고 가정하지 않는다. 이미 등록됨과 분실/키 폐기 등의 사용자 안내를 구분할 필요는 있으나, 현재 BE가 모든 경우에 별도 공개 오류 코드를 제공하는 것은 아니다. 앱이 고유번호만으로 타인 소유/분실 상태를 추측하거나 소유자 정보를 표시하지 않는다.

## 8. 공동 협의가 필요한 항목

| 우선 | 항목 | 확인할 내용·담당 |
| --- | --- | --- |
| 1 | 발견·실물·소유 확인 | 버튼 없는 시험의 식별 방법, 정식 제품 버튼 차이, 등록된 기기 재연결·동시 폰 정책·인증 세션. IoT·FE·BE |
| 1 | 실물 신뢰키 등록 | 시험 보드 공개키 사전 등록과 등록 서명 실물 검증, 출고 키 주입·보호 절차. IoT·BE |
| 1 | 등록 부분 성공 | 서버 등록 성공 뒤 키링 반영 요청·재확인·실패 복구의 메시지와 서버 확인 수단. IoT·FE·BE |
| 1 | 서비스 식별 | 공통 서비스 식별값의 실제 값·기기 설정/발견/인증 연결. UART UUID와 구분. IoT·FE·BE |
| 2 | 공식 동기화 payload | 최소 성향/조건/관계 상태·코드북·정밀도·형식/정책/규칙 버전·골든 벡터. AI·BE·IoT |
| 2 | 큰 전송·시간·적용 | 최대/조각 길이·프레임/누락 확인·제한 시간/재시도, 신뢰 시각과 재시작 복구, 저장/실제 적용 응답. IoT·FE·BE |
| 3 | 오프라인 확정·새 만남 | 상대 인증·이벤트 합의·부분 저장 복구, 재접근 기준·억제 상태 수명·LED 색. 자동 만료 후 새 만남 허용과 BE 설명 정합화. IoT·AI·BE |
| 3 | 상태·시간·부품 테스트 | 최종 설정 반영 시각, 독립 시간 맞추기, LED·진동 테스트 범위/메시지·OFF 중 허용 여부. IoT·FE·BE |
| 3 | 기록 조회·서버 확인 | cursor/페이지 계약·당시 증명·서버 저장 확인 검증·확인 반영/삭제 응답과 전원 차단 복구. IoT·FE·BE |
| 후속 | 초기화·양도·분실 | 처리 순서·동의·삭제 범위·고정 식별/키·부분 성공/영구 차단 전파. IoT·FE·BE |

미정은 기능 삭제를 뜻하지 않는다. 합의되지 않은 서명·성향 데이터·재시도 값으로 성공 응답을 만들어 연결하지 않는다.

## 9. 적용·호환성·검증

첫 합동 시험은 `부팅·버전 확인 → 60초 등록 창에서 스캔·연결 → TX 구독 → 기기 정보·상태 조회 → 등록 challenge 서명 → 테스트 서버 등록 → 재연결·재구독` 순서로 진행한다. 재연결 전에 광고 창이 닫혔다면 시험 보드를 재부팅한다. 설정 단계에서는 현재 `POLICY_UNRESOLVED`가 앱의 미반영 상태로 표시되는지 확인한다. 공식 계약 반영 뒤 실제 저장, 서버 ACK 검증 및 전원 재시작 후 동일 버전·해시 복구를 이어서 확인한다.

적용 순서는 **이 문서 공동 검토 → 각 미정 항목 확정·시험 벡터 공유 → 등록/동기화 펌웨어·FE/BE 연결 → 실제 2대 매칭/기록 검증**이다. 필드/형식의 호환되지 않는 변경은 버전과 지원 기능을 함께 갱신한다. 현재 문자 중계 펌웨어를 이 계약 지원 버전으로 표시하지 않는다.

동시 수정 대상은 FE의 BLE 파서·등록/동기화/기기 관리 화면, IoT 펌웨어·저장소, BE 등록/동기화·접수 검증, AI 공식 최소 payload/규칙과 골든 벡터다. 이번 문서만으로 이 코드들을 변경하거나 배포하지 않는다. 과거 UUID deviceId·`/api/v1` 경로·등록 proof.payload를 현재 숫자 ID·`/api`·등록 proof.signature 구조와 혼용하지 않는다. 만남 evidence의 `{protocolVersion,payload}`는 별도 구조다.

구버전 기록에 당시 버전·설정 사본·상대 증명·증빙이 빠져 있으면 현재 값으로 채워 정상 기록처럼 제출하지 않는다. 검증 불가/미완료 기록으로 구분해 원본을 보존하고 이관·정리 방법을 BE·IoT가 합의한다. 신뢰할 시각·원래 기한으로 만료를 확인한 원본의 정상 정리는 5절 기준을 유지한다. 판단 대기 큐와 확정 기록 업로드 큐를 구분하며, 업로드 재시도로 다시 판단하거나 지난 물리 안내를 실행하지 않는다.

| 확인 시나리오 | 기대 결과 | 현재 결과 |
| --- | --- | --- |
| 탐색·다른 기기·동시 등록 | 발견과 소유 확인 구분, 소유자 1명. 시간 초과/재연결만으로 등록 완료 표시 금지 | 미실시 |
| 설정 중단·저장 실패·과거 응답 | 기존 정상 설정/기록 보존, 최신 저장·적용/시간 확인 전 완료 표시 금지 | 미실시 |
| 매칭 OFF의 서버/기기 반영 시차 | 서버 새 제안 중지와 기기 반영 대기 구분, 기기 반영 후 현재/남은 출력 중지, 기존 제안·채팅 유지 | 미실시 |
| 폰 없는 2대·알림 조합·이탈 | 양방향 판정, 진동 양쪽 ON 조건, LED 독립 설정·같은 색, 지연 출력 없음 | 미실시 |
| 오프라인 만남→설정 변경→재시작→업로드 | 당시 event/submission/snapshot/버전·증명 보존, 현재 값으로 복원 금지 | 미실시 |
| 서버 성공→BLE 확인 유실→재처리 | 같은 제출/영수증 복구, 중복 제안·다른 기록 삭제·재진동 없음 | 미실시 |
| 배치 일부 성공·실패/결과 누락 | 제출별 접수 확인, 미확인 원본 보존, 부분 완료 표시 및 retryable 구분 | 미실시 |
| 구버전 기록의 필수 증빙 누락 | 현재 값으로 보충 제출/임의 삭제하지 않고 이관·정리 합의 대상으로 구분 | 미실시 |
| 공간 부족·삭제 실패·기한 불확실 | 유효 기록 덮어쓰기 금지, 공간 확보 미확인을 완료 표시하지 않음 | 미실시 |
| 버튼 없음·초기화/양도 | 6절의 IoT 명령·키 보존·proof 재조회, BE/FE 연결과 정식 제품 요구를 구분 | 호스트 검증 통과, 실물·종단 미실시 |

위 표는 테스트 계획이며 실측 결과가 아니다. 가상 응답·정적 JSON 확인, 실물 BLE·전원 차단·Android/iOS 검증 결과를 구분해 남긴다. 배터리 등은 실제 응답이 준비될 때까지 기존 상태 조회 명세대로 미확인으로 표시한다.
