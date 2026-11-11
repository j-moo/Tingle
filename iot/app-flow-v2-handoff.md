# 앱 등록부터 만남 접수까지 — 펌웨어 v2 연동

<a id="fold6-ble-recovery"></a>

## 2026-11-11 Fold6 검색·페어링 시험 — 0.9.2

[FE299 실물 기록](https://ssafy.atlassian.net/browse/S15P31E106-299?focusedCommentId=322457)에서 검색 응답에 이름이 없는 펌웨어로도 Fold6의 광고 누락이 재현됐다. 동일 이미지에서 휴대폰 어댑터를 다시 시작한 뒤 검색이 복구됐다. 이어 [보호 특성 읽기만 한 시험](https://ssafy.atlassian.net/browse/S15P31E106-299?focusedCommentId=322478)에서 #4가 SMP `Pairing Failed 0x03`을 보냈다. Fold6 요청은 Secure Connections와 16바이트 키 크기를 제시했다. 보드에 해당 휴대폰의 페어링 정보가 있는지와 실제 거부 조건은 실물 재현에서 확인한다.

0.9.2는 기본 휴대폰 광고에 Flags와 NUS UUID를 넣고 일반 Scan Response는 비운다. USB LAB 모드의 진단 광고와 키링 간 확장 광고는 각 경로의 설정을 사용한다. 보호 특성은 연결 암호화 L2를 요구한다.

### #4의 특정 휴대폰 페어링 복구

1. #4만 USB로 연결해 115200bps, DTR ON으로 콘솔을 열고 `DIAG`의 펌웨어 버전·저장 설정 버전과 `ENROLL`의 시리얼을 확인한다. #4의 시험 기준은 `XIAO-7ce081e55efaee51`과 저장 버전 `10`이다. 만남 기록 수는 가능하면 기존 소유자 세션으로 읽어 기준값을 기록한다.
2. Fold6에서 같은 보호 특성 `6e400004-b5a3-f393-e0a9-e50e24dcca9e`을 읽는다. 보드의 `BLE security`, `BLE pairing failed` 및 휴대폰 HCI 로그의 방향과 시각을 대조한다. `error`는 Zephyr 보안 오류 열거값이다. `knownBond=1`은 시도한 연결의 기기 주소와 일치하는 저장된 페어링 항목이 있다는 뜻이다.
3. 인증 요구 오류와 해당 항목이 함께 확인되면 USB 로그가 `BLE FORGET <8자리 토큰>`을 안내한다. Fold6에서 #4 연결을 끊고 자동 재연결을 멈춘 뒤, 같은 USB 터미널에 로그에 나온 줄을 입력하고 Enter를 누른다. 토큰은 그 실패에서 확인한 주소 하나를 지정하며, 새 연결이나 성공한 암호화가 앞선 토큰을 무효화한다.
4. `BLE bond forgotten`을 확인한 뒤 Fold6의 시스템 Bluetooth 목록에 #4 항목이 있으면 그 항목을 등록 해제하고, 다시 연결해 보호 특성 읽기를 시험한다. 보드의 명령은 그 휴대폰의 Bluetooth 키와 GATT 캐시만 대상으로 한다. 서비스 초기화는 별도 명령으로 소유 데이터와 설정을 지운다.
5. 성공 후 같은 시리얼·소유 관계·저장 버전 `10`과 만남 기록 수를 대조한다. 저장 버전이 유지된 경우에도 전원 재시작 뒤에는 설정 적용과 신뢰 시각을 다시 확인한다. 토큰이 없거나 명령이 실패하면 결과를 보존하고 HCI 로그와 대조한다.

### Fold6 시험에서 FE·BE가 확인할 항목

- FE는 같은 #4·APK·UF2로 Fold6와 S20에서 `검색 → GATT 연결 → DEVICE_INFO → 보호 읽기(0x01) → 재연결`을 단계별로 기록한다. Fold6 Bluetooth 재시작 전후를 비교하고, 이름이 없을 때 UUID 검색과 기본 표시 이름으로 동작하는지 확인한다. 연결된 시리얼을 현재 계정의 기기 목록과 대조한 뒤 설정을 보낸다.
- FE는 보호 읽기 실패 시 같은 연결에 TSV3를 쓰기 전에 중단한다. 보호 읽기 성공 후에만 설정 전달, `STORED`, 서버 저장 ACK 및 `APPLIED`를 확인한다. 검색 실패와 보호 통신 실패를 따로 기록한다. 재현 시각, Android/One UI·APK 버전, UF2 SHA-256, `0x03` 송수신 방향, 보드 `knownBond` 결과를 공유한다.
- BE는 `GET /api/keyrings`가 현재 계정의 각 `deviceId`에 대응하는 `deviceSerial`을 제공하는지 확인하고 FE와 필드·배포 시점을 맞춘다. #4·#7의 소유 관계와 개발 환경의 서명·기기 허용 목록을 점검한다. 재페어링 후 동일 소유·버전으로 설정 저장·적용 확인을 이어갈 수 있어야 한다.
- 양쪽 만남 기록 접수 시험은 [BE327](https://ssafy.atlassian.net/browse/S15P31E106-327)의 개발 서버 규칙 조합·평가 워커 설정을 확인한 뒤 실행한다. 한쪽만 접수하면 제안 0건, 양쪽 접수하면 1건, 재전송 후에도 1건을 기대한다. 정책 미준비로 접수만 된 기록의 재평가 경로는 별도 확인한다.

## TSV3 설정 적용 증명 — 0.7.3-applied-proof

앱은 `GET_DEVICE_INFO.supportedFunctions`에 `GET_SETTINGS_APPLIED_PROOF`가 있는 기기에서 호출한다. `SYNC_SETTINGS`의 저장 및 서명된 `SETTINGS_RESULT/STORED` 처리를 유지하고, 적용 확인은 같은 보호된 NUS 연결에서 별도로 요청한다. 요청은 LF로 끝나는 JSON 한 줄이다.

```json
{"protocolVersion":1,"type":"GET_SETTINGS_APPLIED_PROOF","requestId":"apply-1","syncRunId":"22222222-2222-4222-8222-222222222222"}
```

성공 응답 `SETTINGS_APPLIED_PROOF`는 요청의 `requestId`와 실제 적용한 `syncRunId`, `settingsVersion`, `payloadHash`, `proof`를 반환한다. `proof`는 `protocolVersion: "tingle-settings-applied-v1"`, `keyId`, `signature`를 담는다. 기기 키로 생성한 64바이트 Ed25519 서명은 패딩 없는 Base64url로 전달한다. 서버는 아래 원문을 동기화 실행의 값으로 재구성한다.

```text
TINGLE-KEYRING-APPLIED
tingle-settings-applied-v1
<keyId>
<deviceSerial>
<deviceId>
<ownerGeneration>
<ownershipEpoch>
<syncRunId>
<syncRunNonce>
<settingsVersion>
<payloadHash>
APPLIED
```

UTF-8과 LF를 사용하며 마지막 `APPLIED` 뒤에도 LF를 넣는다. 숫자는 10진수, UUID·해시는 소문자다. `syncRunNonce`는 서버 실행의 `nonce`이며 `requestId`와 `deviceNonce`는 원문에서 제외한다.

| `ERROR.code` | 반환 조건 |
| --- | --- |
| `SETTINGS_APPLY_PENDING` | 해당 실행을 저장했고 적용이 완료되기 전 |
| `SETTINGS_APPLY_FAILED` | 해당 실행의 적용 시도가 실패했거나 저장 응답 전송에 실패해 적용이 시작되지 않음 |
| `SETTINGS_RUN_MISMATCH` | 확인할 수 없는 실행, 다른 실행으로 교체됨, 소유 세대 변경, 또는 재부팅 후 미적용 |
| `SECURE_TRANSPORT_REQUIRED` | 보호된 NUS 쓰기 조건을 충족하지 않음 |

설정과 무선 모드 적용을 마치면 매칭 OFF인 실행도 증명을 발급한다. 현재 적용 상태가 유지되는 동안 BLE 재연결 후에도 같은 실행으로 재조회할 수 있다. 적용 상태는 휘발성이라 전원 재시작 뒤에는 기존 저장 설정을 다시 적용한 뒤 조회한다.

## 2026-11-10 PCB 프로필 — 0.9.1

주문한 SIS D3.1 Y3의 기본 빌드 `0.9.1-d31`과 Rev G 선택 빌드 `0.9.1-revg`는 같은 앱·서버 메시지 규격을 사용한다. 전원 재시작 후 기존 저장 설정은 유지된다. `GET_SETTINGS_APPLIED_PROOF`는 이전 부팅의 적용 결과를 반환하지 않으므로 새 설정·시간 동기화를 진행한다. 주문 기판의 배선과 전원 조건은 [SIS D3.1 Y3 PCB 안내](README.md#sis-d31-y3-pcb)를 따른다.

## 2026-11-06 앱·서버 연동 기준 — 0.7.2-owner-flow

**앱·서버 메시지는 `0.7.2-owner-flow` 기준으로 대조한다.** 정상 TSV3/타입4 grant·TER2·기존 초기화/바깥 설정 서명을 사용한다. 전체 내부 요구와 검사 범위는 [41개 항목 대조표](user-flow-coverage-v1.md)에 정리했다. 아래 0.7.2 기록은 당시 하드웨어 제외 범위를 보존한다. D3.1 물리 안내는 위 후속 절과 README를 따른다. 로컬 구현/검사·UF2 빌드와 실물 탑재·실제 앱/서버 종단은 별개다.

바로 전달할 작업 목록: [FE 전달문](#fe-전달문) · [BE 전달문](#be-전달문) · [합동 시험과 완료 기준](#합동-시험과-완료-기준) · [관련 문서와 검산 자료](#관련-문서와-검산-자료).

### !251 리뷰 수정 — 초기화 메인 경로와 FE 상태 호환

- 초기화 요청/재부팅 복구에서 무선 중지 계획의 generation을0으로 남겨 현재 세대와 불일치하던 결함을 수정했다. `service.c`의 공통 조정 함수를 실제 main 루프가 호출하며 중지 후 현재 generation을 계획에 넣는다. 무선 중지/휴대폰 광고 적용이 성공한 뒤에만 기존 `kr_reset_resume`으로 삭제·CLEARED를 진행한다.
- 중지 실패·동시 세대 변경은 원래 초기화 저널을 보존하고 다음 pass에서 재시도한다. 일반 설정 적용 중 새 초기화가 들어오면 다음 pass에서 초기화용 중지를 실제 적용하기 전에는 삭제를 시작하지 않는다. 이 경로를 서명된 RESET_DEVICE·NVS 모의 저장/재부팅과 함께 검사한다. 실제 RF/플래시 관찰은 별도다.
- FE의 기존 GET_STATUS512B 수신 제한을2304B로 확대했다. 공개 합성 C STATUS548B를 `front/app/shared/src/androidHostTest/resources/keyring/status-0.7.json`으로 보존하고 실제 Kotlin NUS의20B 수신·전체 파싱·연결 유지와 옛512B 실패를 검사한다. `verify-app-flow.mjs`가 C 출력과 이 fixture의 byte 일치를 확인한다.
- 이번 FE 변경은 상태 조회 호환과 해당 회귀에 한정한다. 전체 TSV3 발급·중계/소유 세션/기록 접수의 다른 FE·BE 후속은 그대로 남는다. FE 검토는 기존 MR의 이재준/황순철 담당 경계로 요청한다.

### 0.7.2 — 소유 메타데이터 복구 후 재개

`unresolvedAccountLockCount`가 남아 있는 동안 PROFILE/제어 패킷·새 후보 계산·이탈 관측을 보류하되, 유효한 적용 설정 자체는 비활성화하지 않는다. 현재 소유자의 서버 서명 `RESTORE_ACCOUNT_LOCK v2`로 마지막 누락 정보까지 영속 복구하면, 현재 설정 세대·신뢰 시간·서명 기한·ON·저장 상태·peer 준비 조건이 모두 유효할 때 추가 설정 전송 없이 재개한다. OFF/만료/저장 오류/미적용 상태에서는 재개하지 않는다. 재부팅 직후 신뢰 시간과 적용 상태를 새 서명 동기화로 복구해야 하는 조건은 유지한다. 아래0.6의 ‘복구 후 항상 새 설정 동기화’ 설명은 이 범위에서 대체한다.

호스트 검사에서는 실제 적용/peer 시작 후 누락 상태를 tick해도 PROFILE 허가가 차단되는지, 정상 서명 복구와 동일 요청 재전송 후 자동 재개하는지, OFF·저장 오류·만료 시 계속 보류하는지를 확인한다. 실제 보드의 RF 재개·앱/서버 연결 검증과는 구분한다.

0.7.1 최초 수정 검증(c1ac141e 기반): IoT 전체 호스트·초기화 공유 메인 경로·독립 서명 검산 및 PC/C37건 통과. FE `:shared:testAndroidHostTest`652건(실패/오류/생략0, 새 STATUS2건 포함) 통과. UF2 빌드 성공, FLASH399,520B/RAM255,940B이며 해시·기준 커밋은 현재 `build/firmware-manifest.json`을 따른다. 이후 최신 develop 통합 검사 결과는 MR과 `build/mr251-*` 로그를 따른다. 실제 보드 쓰기/실측은 하지 않았다.

검증 중 추가된 최신 develop `411fce89`도 병합했다(FE264·BE257). **196B `tingle-profile-storage-v1`의 서버 발급·바깥 서명 및 앱 보호 중계/버전 변경 복구는 이 후속 코드에서 구현됐다.** 이를 여전히1바이트만 지원한다고 안내하지 않는다. 전체 TSV3/타입4 grant·소유 세션·TER2 접수/서명 receipt·release·복구 v2는 별도 연동 범위다. 아래 `c1ac141e` 관찰은 당시 기록이며 실제 APK/서버 배포·실물 저장 완료는 각각 확인한다.

### 최신 develop 반영과 파트별 후속

2026-11-06 최초 MR 준비 시 `develop`을 `c1ac141e`까지 fast-forward pull한 뒤 작업을 옮겼다. FE의 보호 특성 Read probe/전송 기반(`8f390f88`), 성향 저장 capability 조회(`6aca412a`), 인터넷 복원 후 동기화 재시도가 포함됐다. 이 기반을 다시 구현할 필요는 없다. 당시 설정 발급/중계는1바이트에 제한됐으나 후속 `411fce89`의196B 지원이 이를 보강했다. 실제 개발 APK/서버에 배포된 버전은 별도 확인한다.

| 담당 | 기존 구현을 확장할 작업 | 확인할 완료 결과 |
| --- | --- | --- |
| FE | 보호 전송·capability 기반에 TSV3 허용/보호 요청 연결, 기기별 run/epoch/cursor 관리. 상태 조회2304B 호환은0.7.1 MR 수정에 포함 | 등록→challenge→전체 설정→STORED→서버 ACK, 다른 키링 상태와 분리 |
| FE | 소유자 허가 중계, 조각 기록 hash 확인·업로드, 서명 receipt/release/복구 v2 중계, 앱 재시작 복구 | 읽기만으로 삭제하지 않음, 동일 submission/receipt 재시도, 초기화 양쪽 완료 확인 |
| BE | 실제 AI/최소 개인 입력→TPS1 매핑, run/nonce 먼저 확정→타입4 grant→TSV3→전체 hash→바깥 v2 서명, 입력/기한 갱신 시 버전 증가 | 서명 정상/변조/다른 기기·epoch·같은 버전 충돌 검사, 저장 ACK 검증 |
| BE | 소유 세션 허가 API, TER2 실제 검증기·REVIEW 접수, 내구 저장 후 서명 receipt, 관계 release·복구 v2 API, 당시 소유 구간·사용자 쌍 중복 검증 | 각 API 요청/응답/오류·정확 원문·조회/재시도 예시와 적용 버전을 FE/IoT에 공급 |

한 계정 여러 키링의 기존 목록/등록 흐름은 유지하고 기기별 소유·동기화·ACK를 검증한다. 아래 새 HTTP 경로는 기존 배포 API와 구분하며, BE가 제안 API를 확정하기 전 구현·배포됐다고 가정하지 않는다. 초기화는 기존 계약/코드를 확장·검증하고 실물 완료를 별도 기록한다.

### FE·BE 전달 기준과 현재 완료 범위

2026-11-06 사용자 요청으로 아래 전달문을 기존 인계에 보강했다. 펌웨어 코드 기준은 **`0.7.2-owner-flow`, `2782f60a45adb78355966a5275741fb8481df541`, [MR !251](https://lab.ssafy.com/s15-final/S15P31E106/-/merge_requests/251)**이다. 이번 보강은 기존 계약의 작업·적용·시험 절차를 정리하며 새로운 wire 형식이나 HTTP API의 구현·배포 완료를 선언하지 않는다.

- 현재 정의한 키링 내부41항목을 코드·호스트 검사와 대조했다. 0.7.2의 전체 호스트 검사·UF2 빌드와 같은 FE 소스 트리의659건 검사를 확인했다. 상세 근거·미검증 범위는 [대조표](user-flow-coverage-v1.md)를 따른다.
- 정확한 배터리 잔량·제품 LED/진동·보류한 물리 버튼 외에도 **AI 입력/codec·계산 규격 승인과 실제 보드 RF·전원 복구·장시간 종단 시험은 남아 있다.** FE/BE 연결만으로 제품 전체 완료나 추가 펌웨어 수정이 전혀 없음을 보장하지 않는다.
- FE171·FE264의 등록/보호 전송/196B 저장·버전 복구, BE257의196B 공급·서명, BE249의 초기화 API 기반을 재사용한다. Jira 완료·코드 병합을 실제 APK/서버 배포·보드 통과로 대체하지 않는다.
- 한 계정 여러 키링을 지원한다는 최신 사용자 결정을 적용한다. 오래된 단일 키링 문구로 범위를 줄이지 않는다. 주변16 peer 세션은 기기의 동시 처리 자원이며 계정 등록 상한이나 실물 혼잡 시험 통과 수가 아니다.
- 전체 TSV3 공급·소유 세션·TER2 접수·서명 receipt/release/복구 v2는 추가 연동 대상이다. 현재196B 저장 지원을 전체 매칭 준비와 혼동하지 않는다.

### FE 전달문

재준님·순철님, 키링 펌웨어 `0.7.2-owner-flow` 기준 전체 앱 연동을 부탁드립니다. 기존 FE171·FE264 구현을 확장하면 됩니다. 기준은 이 문서 맨 위0.7.2 절이며, 이전0.5/0.6 설명과 다르면 최신 변경을 우선해 주세요.

1. **탐색·등록과 여러 키링 관리**

   NUS 서비스로 탐색하고 `GET_DEVICE_INFO`에서 serial/keyId, 펌웨어 버전, 지원 명령·payload·증명 버전, `ownershipEpoch`를 확인해 주세요. 서버 challenge → `GET_REGISTRATION_PROOF` → 서버 등록 API 흐름을 사용합니다. 이름·BLE 주소만으로 본인 기기를 확정하지 않고 서버 등록 정보와 기기 증명을 대조해 주세요.

   한 계정 여러 키링의 목록·연결 대상·등록/동기화 상태를 기기별로 관리합니다. 한 기기의 성공을 같은 계정의 모든 키링 성공으로 표시하지 않습니다. 등록 전 기기 신뢰 공급은 BE 계약을 따르며 기기가 제시한 공개키만으로 신뢰 등록을 대신하지 않습니다.

2. **전체 TSV3 설정 중계**

   ON/OFF·`tingle-profile-storage-v1`에 더해 **`tingle-keyring-settings-v3`**를 지원하도록 형식 검사·capability 판단·보호 전송 연결을 확장해 주세요. `peerExchangeProtocol:tingle-peer-v3`, `peerOwnerScopeVersion:1`, `accountLockProofVersion:tingle-account-lock-v2` 등 실제 응답을 확인하고, 펌웨어 이름만으로 새 명령 지원을 추측하지 않습니다.

   서버 payload·hash·버전·서명·시각을 그대로 전달합니다. FE가 성향값을 만들거나 payload를 다시 인코딩하지 않습니다. 개인 설정·소유자/기록 명령은 보호 특성 `6e400004-b5a3-f393-e0a9-e50e24dcca9e`에서 **같은 연결의 Read probe `01` 성공 후** 전송해야 합니다. JSON UTF-8+LF, 최대20B Write With Response 분할, 직전 요청 응답 후 다음 요청 원칙을 유지합니다. 현재 요청4096B·응답2304B·설정2048B 한도를 적용합니다.

3. **등록 후 자동 동기화와 완료 상태 구분**

   `등록 완료 → GET_SYNC_CHALLENGE → 서버 sync-run 발급 → SYNC_SETTINGS → 서명 STORED → 서버 ACK 제출`을 연결해 주세요. 전체 설정 공급이 늦으면 BE의 서명된 최소 OFF 설정으로 소유 바인딩을 먼저 완료하고 전체 설정을 새 버전으로 전달할 수 있습니다.

   BLE 전송 완료·기기 저장 완료·서버 ACK 완료·`applied`·`timeReady`·`matchingReady`를 구분합니다. 저장 성공만으로 가입/매칭 준비 완료를 표시하지 않습니다. 정상 전체 설정 적용 뒤 광고·스캔에 USB `LAB ENABLE`는 필요 없습니다. OFF 상태에서도 앱 재연결용 NUS 광고는 별도로 유지합니다.

4. **소유자 인증 세션**

   상세 상태·기록 조회/ACK/해제/복구는 `GET_OWNER_CHALLENGE → BE 소유자 허가 발급 → AUTHORIZE_OWNER`로 연결해 주세요. READ=1, ACK=2, RELEASE=4, CONTROL=8 중 필요한 권한을 발급받습니다. BLE 암호화는 소유자 인증을 대신하지 않습니다.

   nonce60초·허가 최대300초를 구분합니다. 세션은 연결 단위이므로 단절·재연결·앱 재시작·소유 세대 변경 후 새 challenge부터 다시 받습니다. 허가 API의 실제 경로·오류는 BE 확정안을 사용합니다.

5. **상태 조회와 표시 확장**

   `GET_STATUS`의 저장 용량/상태·후보/미접수/검증 대기 수·`recordCursorEpoch`·`unresolvedAccountLockCount`·준비 상태를 모델과 화면에 연결해 주세요. 기록/NVS 상세는 READ 인증 후 조회합니다. STATUS 수신2304B 수정은 !251에 포함됐지만 기존 UI 모델이 필요한 모든 필드를 읽는다는 뜻은 아닙니다.

   `null`·`UNKNOWN`·`UNCONFIRMED`를0이나 정상으로 바꾸지 않습니다. 전압 추정은 `VOLTAGE_ESTIMATE`로 표시하고 정확한 잔량계 측정과 구분합니다. 서버 등록/가입 상태와 현재 BLE 상태·기기 준비 상태도 분리합니다.

6. **만남 기록 조각 조회·업로드·서명 ACK**

   READ 인증 후 `GET_RECORD`로 기록을 찾고 `READ_RECORD_CHUNK`(조각 최대512B)로 전체 TER2를 조립해 `envelopeHash`를 확인해 주세요. 기기별 `recordCursorEpoch`와 sequence를 사용하고 hash/cursor가 바뀌면 다시 조회합니다. CONFIRMED와 REVIEW를 구분하고 REVIEW는 BE가 확정한 검증 접수/격리 경로에 전달합니다.

   서버 내구 저장 후 받은 **서명된 receipt를 해당 기기의 `ACK_RECORD`로 중계**합니다. 앱 읽기·GATT Write·HTTP 성공·`canDeleteLocalRecord` boolean만으로 원본을 삭제하지 않습니다. 양쪽 기록 접수/검증과 제안 생성 상태를 따로 표시합니다.

7. **재시도와 앱 재시작 복구**

   기기별 serial/deviceId/keyId·ownerGeneration/epoch·syncRunId/version/hash·최신 ACK 상태, 기록의 cursor/sequence/eventId/submissionId/envelopeHash·조각/전체 bytes·서버 receipt, 초기화의 resetRunId·증명·서버 결과를 구분해 관리합니다. 필요한 개인정보/기록은 보호 저장소를 사용하고 원문/nonce/증명을 로그에 남기지 않습니다. 소유 세션은 RAM 연결 단위입니다.

   응답 유실은 같은 업무 ID·멱등 키로 조회/재시도합니다. 서버 업로드 유실은 같은 submissionId로 receipt를 다시 받고, ACK 유실은 같은 receipt를 재전달합니다. 다른 기기·계정·과거 연결의 늦은 응답을 현재 작업에 적용하지 않습니다. 재연결은 새 nonce를 사용하며, 서버가 같은 payload/run/version을 재사용할지 새 payload/version을 발급할지 확정한 규칙을 따릅니다. 같은 버전에 다른 hash를 보내지 않습니다.

8. **억제 해제·과거 자료 복구·초기화**

   BE의 `RELEASE_RECORD`·`RESTORE_ACCOUNT_LOCK v2`를 그대로 중계합니다. 복구 누락이 모두 해결되고 현재 설정/기한/저장 상태가 유효하면0.7.2는 추가 설정 전송 없이 재개합니다. 누락·OFF·만료·저장 오류는 성공으로 숨기지 않습니다.

   초기화는 기존 reset 흐름을 사용하되 **기기 CLEARED와 서버 소유 해제를 모두 확인한 뒤 완료**로 표시합니다. 미접수 기록을 먼저 업로드하거나 명시적 삭제 동의를 받아야 합니다. 응답 유실은 같은 resetRun 조회·`GET_RESET_RESULT`·같은 증명 ACK로 복구하고, 임의의 새 초기화를 만들지 않습니다. 기기 serial/인증키를 재생성하지 않습니다.

### BE 전달문

영빈님·가을님, 키링 펌웨어 `0.7.2-owner-flow` 기준 전체 설정·만남 기록 연동을 부탁드립니다. BE257의196B 성향/최소 프로필 공급·서명과 BE249의 초기화 API를 재사용하고, 전체 TSV3 설정·소유 세션·TER2 검증·서명 접수를 연결해 주세요. 아래 API 중 제안 경로는 구현/배포 완료 경로와 구분합니다.

1. **실제 사용자 데이터로 전체 TSV3 발급**

   TPS1 196B에 타입4 peer grant, ownerRef, ownerGeneration/ownershipEpoch, policy/rule 버전, grant 유효기간·만남 기한, 관계 제한/완전성, deviceAllowed, presenceGapSeconds를 결합해 `tingle-keyring-settings-v3`를 발급해 주세요. 정수 byte 순서·필드 길이·범위는 기존 codec 표와 맨 위 TSV3 변경을 따릅니다.

   VALUE/ANY/UNKNOWN/MISSING을 구분하고 누락된 실제 입력을 임의 기본값으로 채우지 않습니다. AI snapshot/codec·규칙/버전·기대 결과를 AI 담당과 확정합니다. 이름·생년월일·사진 원문·원본 설문·서버 토큰은 payload에 넣지 않습니다. 제한 최대60개를 넘거나 전체 공급이 불가능하면 잘라서 complete=1로 보내지 말고 `restrictionsComplete=0`으로 준비를 보류합니다.

2. **서명 순서와 설정 버전**

   **run/nonce·버전 확정 → TPS1 hash → 타입4 grant 서명 → TSV3 구성 → 전체 SHA-256 → 바깥 설정 서명** 순서입니다. 기존 epoch 포함 바깥 `tingle-dev-settings-proof-v2`와 기기 STORED 증명을 사용합니다. 정확한 원문·바이너리·마지막 LF는 아래 검산 자료로 맞춰 주세요.

   grant/run/기한 변경으로 payload bytes/hash가 바뀌면 settingsVersion도 증가해야 합니다. 재연결 시 동일 저장 payload/run/version에 새 deviceNonce의 바깥 서명을 공급하거나, 새 payload와 새 버전을 공급하는 복구 규칙을 제공해 주세요. 같은 버전에 다른 hash를 발급하면 기기가 거부합니다. 기존196B ACK의 가입 상태 반영과 전체 matchingReady 기준도 FE에 확정해 주세요.

   팀 서버 서명키는 펌웨어의 `dev-ed25519-799e2999c648fda3` 및 [공개키](config/dev-settings-public-key.hex)에 대응해야 합니다. 서버 비밀키는 서버에 유지하며 기기 개인키/seed를 수집하거나 공유하지 않습니다.

3. **여러 키링과 계정 단위 중복 처리**

   한 계정 여러 키링의 등록·목록·기기별 동기화/ACK를 검증해 주세요. 기기당 현재 소유자는 한 명이며 같은 계정의 키링은 동일한 불투명 ownerRef를 사용합니다. 기존 등록/신뢰·멱등 검증을 유지하고 새 기기 공개키의 신뢰 공급을 사용자 소유 등록과 구분합니다.

   펌웨어 억제는 각 키링이 관측한 범위입니다. 서로 통신하지 못하는 장소의 같은 계정 키링까지 포함하는 **사용자 쌍 단위 만남/제안 중복 처리는 BE에서 최종 수행**해야 합니다. 다른 기기의 독립 원본을 한 기기의 ACK로 지우지 않습니다.

4. **소유자 허가 발급 API**

   현재 로그인 사용자·세션·기기 소유·소유 세대·deviceNonce를 검증해 `tingle-owner-session-v2`를 발급해 주세요. READ/ACK/RELEASE/CONTROL 중 최소 권한과 최대300초 기한을 사용합니다. 소유/nonce/권한/만료·다른 기기 오류를 FE가 구분할 수 있게 공급합니다.

   `POST /api/keyrings/{id}/owner-session-grants`는 **이 문서의 제안 경로**이며 현재 배포 API라고 가정하지 않습니다. 실제 API·요청/응답·서명 원문·오류·재시도 규칙을 확정해 FE에 전달해 주세요.

5. **TER2 실제 검증기와 REVIEW 접수**

   기존 `/api/offline-encounters/sync` 구조의 `EncounterSubmissionProofVerifier` 공급 경계에 `tingle-encounter-v2` 실제 검증기를 연결해 주세요. 기기 서명·양쪽 credential의 서버/기기 서명·당시 소유 구간/epoch·event/run/version/hash·원래 만남 시각/기한을 확인하고 양쪽 독립 원본을 대조합니다. 현재 설정으로 과거 동결 증명을 바꾸거나 양쪽 검증 전에 제안을 만들면 안 됩니다.

   REVIEW·불완전 증명·옛 LAB 자료는 정상 만남과 구분할 접수/격리·오류/재조회 방식을 확정합니다. 검증 불가를 정상 성공이나 가짜 증명으로 대체하지 않습니다. `POLICY_UNRESOLVED` 등의 공급 대기는 FE가 실제 상태로 표시할 수 있게 응답합니다.

6. **내구 저장 후 서명 receipt**

   서버 트랜잭션의 내구 저장 완료 후 `tingle-record-receipt-v2`를 발급해 주세요. 기기·소유 세대·eventId·submissionId·envelopeHash·receiptId·접수 시각·`STORED`·`DELETE_ORIGINAL`에 서명합니다. boolean `canDeleteLocalRecord`만으로는 기기가 삭제하지 않습니다.

   동일 submissionId/원문의 재요청이나 응답 유실에도 기존 접수 결과·receipt를 다시 받을 수 있어야 합니다. 원문이 다른 중복 요청은 충돌로 구분하고, 다른 기록·소유 세대의 삭제 허가로 재사용되지 않게 검증합니다.

7. **억제 해제와 과거 정보 복구 서명**

   현재 제안·거절·채팅·차단 정책을 검사한 뒤 `tingle-record-release-v2`를 발급합니다. 키링은 원본 접수와 해당 상대 소유 세대의 이탈 관측 조건까지 확인합니다. 원본 삭제 ACK와 반복 억제 해제는 별도입니다.

   이전 자료에 상대 소유 메타데이터가 부족하면 당시 접수 증거의 `peerCredential`을 사용해 `tingle-account-lock-v2`를 발급합니다. 현재 상대 정보로 과거 소유 세대를 추정하지 않습니다. 복구 허가의 같은 요청 재시도·조회·권한·기한 규칙을 FE에 제공해 주세요.

8. **초기화·양도 계약 통합**

   기존 reset-runs 시작/조회/command 갱신/ACK를 펌웨어와 대조합니다. **CLEARED 증명을 검증한 뒤 서버 소유 연결을 해제**하고 그전에는 다른 계정 등록을 막습니다. 기기의 새 epoch는 삭제 착수 저널과 완료 증명에 기록되며 BE는 검증한 값을 후속 등록/설정에 반영합니다. 이를 별도 임의 증가로 어긋나게 만들지 않습니다.

   같은 증명 재제출·응답 유실·서버 완료 후 재조회는 멱등이어야 합니다. 새 소유자 등록 후 도착한 과거 완료 요청이 현재 소유 연결을 해제하면 안 됩니다. 미접수 기록 업로드/삭제 동의, 완료 이력 조회 권한·보관 정책·기능 활성화 조건은 기존 초기화 계약과 맞춰 주세요. 기기 serial/인증키는 보존합니다.

9. **FE에 제공할 계약 자료**

   확정 API 경로, 정상/오류 JSON, 정확한 서명 원문·검증 예시, 업무/멱등 키, 결과 조회·응답 유실·부분 성공 복구, 기능 활성화 조건·지원 버전을 전달해 주세요. 전체 계약과 실제 배포 상태를 구분하고, 이 문서의 제안 API를 이미 동작하는 서버 API로 안내하지 않습니다.

### 합동 시험과 완료 기준

적용 순서는 **AI/BE/FE 계약 대조 → BE 공급/검증/API → FE 중계/복구 → IoT 실물 합동 시험**이다. 공통 UF2를 사용하고 기존 serial/keyId/기기 키·NVS 저장 영역을 보존한다. 이번 문서 보강으로 펌웨어 재빌드·보드 쓰기·서버 기능 활성화·초기화를 수행하지 않는다.

- [ ] 실제 앱에서 탐색/등록 → 전체 설정 저장 → 기기 STORED → 서버 ACK 확인. 등록/저장/매칭 준비 상태를 분리한다.
- [ ] 전원 재시작 뒤 기기 키·설정 버전/hash·기록 유지. 신뢰 시간 확보 전 보류와 새 서명 동기화 뒤 재개를 확인한다.
- [ ] 휴대폰 BLE/인터넷 없이 여러 키링의 확장 광고·스캔/교환·양방향 판정·3개 큐 확인. 같은 계정 기기끼리 비매칭 및 사용자 쌍 중복 처리를 확인한다.
- [ ] 양쪽 기록 조각 조회·hash 대조 → 서버 검증/내구 접수 → 서명 receipt 중계 → 해당 원본 삭제와 최소 반복 억제 유지 확인. REVIEW는 별도 검증 경로로 확인한다.
- [ ] 단절·응답 유실·중복 요청·늦은 응답·설정/소유 세대 변경·OFF·만료·제한·공간 부족에서 유효 원본 보존과 올바른 복구를 확인한다.
- [ ] 초기화의 미접수 처리·동의·전원 차단/재조회·기기 CLEARED/서버 소유 해제·다른 계정 재등록·옛 세대 거부를 확인한다.
- [ ] 보드/펌웨어/앱/서버/정책 버전과 기대/실제 결과, RF 혼잡·전력/장시간 실측을 기록한다. 가상10대나 CI 성공을 실물 통과로 대신하지 않는다.

정확한 배터리 잔량·제품 LED/진동·보류한 물리 버튼은 위 완료 판정의 제외 범위다. 실물 시험 결과나 최종 계약이 현재 코드와 다르면 영향받는 펌웨어도 보완한다. 체크되지 않은 시험을 완료로 표기하지 않는다.

### 관련 문서와 검산 자료

| 저장소 경로 | 확인할 내용 |
| --- | --- |
| `iot/app-flow-v2-handoff.md` (이 문서) | 최신0.7.2 및 FE/BE 전달문 우선. 아래4~7절의 소유 세션·기록·서명 ACK 원문은 TSV3/타입4·복구 v2 변경과 함께 적용 |
| [iot/user-flow-coverage-v1.md](user-flow-coverage-v1.md) | 내부41항목 코드·검사 근거, 제외/미검증 범위 |
| [iot/dev-settings-signing-handoff-v1.md](dev-settings-signing-handoff-v1.md) | TPS1 196B·기존 바깥 설정 서명·공개키. 예전 공급 대기는 최신 반영 절과 구분 |
| [iot/keyring-app-ble-contract-v1.md](keyring-app-ble-contract-v1.md) | BLE 등록·저장, 6절 초기화·완료 증명·재조회 |
| [iot/keyring-status-ble-v1.md](keyring-status-ble-v1.md) | 최신 상태 필드·응답 한도·미측정 값 표시 |
| [iot/peer-profile-exchange-v1.md](peer-profile-exchange-v1.md) | 확장 광고/스캔 wire·암호화·계산 개발 근거. 정상 전체 모드는 이 문서의 후속 계약 우선 |
| [iot/tests/app-flow-owner-vectors.json](tests/app-flow-owner-vectors.json) | 최신 소유 세대·복구 v2·서명 원문/bytes의 공개 합성 검산 |
| [iot/tests/app-flow-v3-vectors.json](tests/app-flow-v3-vectors.json) | TSV3/타입4·만남 기한 기본 검산. 최신 owner 벡터와 함께 사용 |

검산 예시(저장소 루트):

```sh
node iot/tools/verify-app-flow.mjs iot/tests/app-flow-owner-vectors.json
```

벡터는 팀 서버 실제 키와 다른 공개 합성 시험값이다. 실제 사용자 설정으로 전송하거나 서버 비밀키·기기 seed·개인정보를 추가하지 않는다. 구현 참고 위치는 FE `features/keyring/data/{KeyringSyncRepository,BleProbeProtocol}.kt`, BE `keyring/`·`encounter/EncounterSubmissionProofVerifier.java`, IoT `src/{product,policy,peer_auth,settings_auth,reset}.c`다.

### 양도 후 상대 소유 세대 분리

- 같은 hardwareRef라도 **ownerGeneration·ownershipEpoch·ownerRef**를 당시 서버 서명 인증서에서 얻어 구분한다. `kr_peer_scope`의 내부 tag는 BLAKE2b-128이며 72B 원문은 `"TINGLE-OWNER-V1" + 0x00`(16B), hardwareRef 원본8B, ownerGeneration16B, ownerRef16B, epoch u64LE, 마지막 zero8B다. tag는 BLE/서버에 추가로 서명받는 필드가 아닌 내부 조회 키다.
- 새 소유자가 이전과 같은 TPS1/설정 버전을 받아도 신규 만남이 가능하다. 이전 확정 원본·억제·이탈 관측·ACK/release는 이전 세대에만 적용한다. 미확정 후보의 상대 소유가 바뀌면 reason9 `KR_PEER_OWNERSHIP_CHANGED`, 상대 입력 버전/hash 변경이면 reason10 `KR_PEER_SETTINGS_CHANGED`의 REVIEW로 당시 증거를 보존한다.
- 보존한 원본/억제 및 인증 캐시에서 관측한 더 높은 epoch보다 낮은 AUTH, 같은 epoch의 다른 소유 tag는 거부한다. 재부팅 후에도 적용한다. 다만 유한 캐시의 관측 이력·서버가 발급한 lease와 정책만 사용하므로, **전파받지 않은 양도/차단을 오프라인에서 즉시 아는 기능은 아니다.** BE는 당시 소유 유효 구간과 정책을 기록 접수 시 다시 검증한다.
- 계산 실패/거절 REVIEW는 이미 내보낸 원문을 바꾸지 않는다. 동일 입력은 억제하고, 자기 입력 버전 또는 인증된 상대 입력 버전/hash/소유 scope가 바뀌면 별도 계산을 허용한다. 기존 확정 만남의 관계 억제는 단순 설정 변경으로 풀지 않는다.
- NVS key900..963은 `KAS3`/`KAD3` 88B로 보강한다: magic4 + hardwareRef u64LE + eventId16 + ownerRef16 + epoch u64LE + scopeTag16 + peerRevision u32LE + peerPublicHash 앞16B. 원본 key100..131·기존 suppression ABI·기기 키/저장 구획은 유지한다. ACK 때 이 저널과 억제를 먼저 저장한 뒤 원본을 삭제한다.
- 이전 KAS1/KAD1(44B), KAS2/KAD2(68B)는 삭제하지 않고 읽는다. 남은 당시 원본 인증서가 있으면 scope/입력 메타데이터를 복구한다. 이미 원본이 없고 안전한 판단에 필요한 정보가 누락됐으면 `unresolvedAccountLockCount`로 표시하고 새 매칭을 보류한다. 누락된 epoch·상대 공개키를 현재 값으로 추정하지 않는다.

### 이전 억제 복구 — RESTORE_ACCOUNT_LOCK v2

**v1 요청은 `PEER_SCOPE_REQUIRED`로 거부한다.** 암호화 특성·현재 소유 CONTROL 세션은 그대로 요구하며 다음 JSON을 사용한다. 실제 정상·변조 검산 값은 [공개 합성 벡터](tests/app-flow-owner-vectors.json)의 `accountLockTranscript`/`accountLockSignature`와 `peerCredential`이다. 이 벡터는 팀 서버 실제 키가 아니다.

```json
{
  "protocolVersion": 1,
  "requestId": "restore-1",
  "type": "RESTORE_ACCOUNT_LOCK",
  "proofProtocolVersion": "tingle-account-lock-v2",
  "serverKeyId": "dev-ed25519-799e2999c648fda3",
  "deviceId": 4,
  "ownerGeneration": "<현재 소유 UUID>",
  "ownershipEpoch": 2,
  "eventId": "<기존 억제 이벤트 UUID>",
  "ownerRefHex": "<당시 상대 계정 참조 소문자 hex32>",
  "grantId": "<서버 복구 허가 UUID>",
  "issuedAtEpochSeconds": 1793577900,
  "peerCredential": "<당시 상대 AUTH325B + X25519 공개키32B의 Base64url>",
  "serverSignature": "<아래 원문에 대한 Ed25519 서명 Base64url>"
}
```

서명 원문은 **UTF-8, 각 줄 및 마지막 LF 포함**이다. 마지막 줄은 peerCredential을 디코딩한357B의 SHA-256 소문자 hex64다. peerDeviceRefHex16은 원본 상대 hardwareRef의 16자리 소문자 hex 표기다.

```text
TINGLE-ACCOUNT-LOCK
tingle-account-lock-v2
{serverKeyId}
{deviceSerial}
{deviceKeyId}
{deviceId}
{ownerGeneration}
{ownershipEpoch}
{eventId}
{peerDeviceRefHex16}
{ownerRefHex32}
{grantId}
{issuedAtEpochSeconds}
{peerCredentialSha256Hex64}
```

BE는 과거 내구 접수한 해당 이벤트의 상대 credential을 사용한다. 펌웨어는 당시 만남 시각에서 서버/기기 서명·상대 hardwareRef/account·확정 revision을 검사하고, 기존 scope와 충돌하면 `ACCOUNT_LOCK_CONFLICT`로 거부한다. credential 변조는 `INVALID_PEER_CREDENTIAL`, 서버 서명 변조는 `INVALID_SERVER_PROOF`, 미존재는 `RECORD_NOT_FOUND`다. 시점은 신뢰 시간 ±300초이며 현재 소유 세대에만 적용한다. 정상 응답은 `ACCOUNT_LOCK_RESULT`의 `restored:true`; 같은 값 재전송은 멱등 성공이다. key/epoch 없는 임의 메타데이터로 복구하지 않는다.

### 재연결·등록 직후 준비·추가 경계 처리

1. 등록 API 성공만으로 기기는 서버 소유 연결을 알 수 없다. 전체 입력 공급이 늦으면 BE/FE가 **서명된 최소1바이트 OFF 설정**을 먼저 저장해 소유 바인딩을 완료하고, 전체 TSV3 준비 후 새 version으로 보낸다. OFF/미준비에서도 앱 재연결용 NUS 광고는 유지하며 매칭 광고/스캔과 구분한다.
2. 새 grant/run/기한은 payload bytes/hash를 바꾸므로 BE가 **settingsVersion도 증가**시켜야 한다. 재연결/재부팅 뒤 동일 payload/run/version을 재개할 때는 기기의 **새 deviceNonce**를 받아 바깥 설정 서명만 새로 발급할 수 있다. 같은 저장본을 검증하고 새 X25519 임시 키로 재개한다. 기한이 지난 grant는 새 버전으로 갱신한다.
3. 처리 차례에 상대 인증·현재 scope·근접·제한을 다시 확인한다. 이탈 후보가 다음 후보를 막지 않는다. 큐에 임의 체류 시간을 넣지 않는다. 새 원본마다 접수 후 필요한 최소 억제 공간까지 예약 가능한지 검사하고, 초기화 require-synced는 미완료 후보·오류 backpressure까지 포함한다.
4. 늦은 무선 완료는 당시 eventId/sequence가 맞는 슬롯에만 적용한다. NUS 요청은 수신 당시 연결 epoch를 사용하며 프레임·요청 후보·응답·삭제된 소유자 버퍼를 사용 후 정리한다.

**FE/BE 적용:** DEVICE_INFO의 `peerOwnerScopeVersion:1`, `accountLockProofVersion:"tingle-account-lock-v2"`를 확인한다. 정상 TSV3 전송/TER2·서명 receipt는 그대로이며, 이전 억제 복구 기능은 위 v2로 맞춘다. BE는 당시 소유 구간·사용자 쌍 중복/제한을 최종 검증한다. FE는 기기별 cursor/nonce/run/version과 소유 세션을 구분하고 기기 ACK 및 서버 ACK를 각각 확인한다. 전체 기존 연동 작업은 아래 v2/v3 절을 함께 따른다. 펌웨어가 HTTP로 서버에 직접 접속하지 않는다.

검사: C 엔진/등록/초기화/성향·product 통합, JDK/Node 독립 서명, PC/C37건 골든 대조, XIAO 대상 UF2 빌드. 같은 HW·같은 profile/revision의 epoch1→2→3, 옛 AUTH replay·재부팅, ACK 이후 실패 입력 변경 재계산·옛 증명 불변을 추가했다. 세부 증거와 실제 미검증 범위는 [대조표](user-flow-coverage-v1.md)를 따른다.

---

## 2026-11-06 후속 — 0.6.0-user-flow / v3

**0.6.0 당시 변경 기록이다.** 현재는 맨 위0.7 절을 우선한다. 아래 기존 v2 계약은 유지하며 v3 정상 설정 형식을 보강했다. LED·진동·물리 버튼 배선은 보류 상태다. FE/BE 코드·서버 배포·실물 종단 완료를 뜻하지 않는다.

### 계정별 중복 억제와 이전 자료 복구

- 한 키링이 같은 상대 계정의 여러 기기를 발견하면, 공통 `ownerRef`로 진행 중/확정/접수 후 억제를 검사한다. 인증된 같은 계정의 다른 기기를 별개 신규 만남으로 계속 생성하지 않는다. 미확정 후보가 경쟁하면 더 작은 hardwareRef를 우선하며, 교체된 후보 증거는 `KR_ACCOUNT_DUPLICATE`(reason8) REVIEW로 보존한다. 이미 확정된 기록은 되돌리지 않는다.
- 상대 계정 참조는 당시 서버 인증서에서 얻고 원본 증거에 저장한다. 서버 접수 후에는 억제 레코드와 계정 참조를 별도 NVS 저널 `900..963`에 먼저 저장한 뒤 원본을 삭제한다. 재부팅 후에도 억제가 유지된다. 일반 억제는 KAS1, 교체된 중복 후보의 기기별 억제는 KAD1로 구분하여 중복 후보 업로드가 선택된 정상 상대까지 막지 않게 한다. 기기 키·기존 record/config/suppression ABI는 바꾸지 않는다.
- 이것은 **각 키링의 로컬 억제**다. 같은 계정의 키링들이 서로 통신할 수 없는 장소에서 각각 만난 경우까지 펌웨어 하나가 전역적으로 알 수는 없다. BE는 모든 기기에서 올라온 동일 사용자 쌍의 만남/제안을 최종 중복 처리해야 한다. 여러 기기의 독립 원본을 서로의 ACK로 삭제하지 않는다.
- 0.5 이전 자료 중 원본이 이미 없어 계정 참조를 복구할 수 없는 억제는 버리지 않는다. `GET_STATUS.unresolvedAccountLockCount`(READ 권한)를 표시하고 새 매칭을 보류한다. BE가 보유한 과거 접수 증거로 검증한 상대 계정 참조만 `RESTORE_ACCOUNT_LOCK`으로 복구하며, 이후 새 설정 동기화로 다시 시작한다. 오래된 LAB 원본을 정식 만남 증명으로 바꾸지는 않는다.

`RESTORE_ACCOUNT_LOCK`은 암호화 특성과 owner CONTROL 권한이 필요하다. JSON 필드: `type,proofProtocolVersion:"tingle-account-lock-v1",serverKeyId,deviceId,ownerGeneration,ownershipEpoch,eventId,ownerRefHex(32자리 소문자 hex),grantId(UUID),issuedAtEpochSeconds,serverSignature`. 현재 소유 세대·정확한 기존 억제 이벤트에만 적용한다. 서명 시점은 기기의 신뢰 시간에서 ±300초 이내. 이미 같은 값이면 멱등 성공, 다른 기존 값은 ACCOUNT_LOCK_CONFLICT다. 정상 `ACCOUNT_LOCK_RESULT restored:true`; 미존재 RECORD_NOT_FOUND, 잘못된 원문 INVALID_ACCOUNT_LOCK_PROOF/INVALID_SERVER_PROOF다.

복구 서명 원문은 각 줄과 마지막 LF 포함:

```text
TINGLE-ACCOUNT-LOCK
tingle-account-lock-v1
{serverKeyId}
{deviceSerial}
{deviceKeyId}
{deviceId}
{ownerGeneration}
{ownershipEpoch}
{eventId}
{peerDeviceRefHex16}
{ownerRefHex32}
{grantId}
{issuedAtEpochSeconds}
```

BE는 현재 로그인 소유자와 당시 접수 증거로 이 원문을 발급한다. FE가 임의 계정 참조를 만들거나 BLE 연결만으로 복구하지 않는다. 이 복구 발급 API는 BE 추가 연동 항목이다.

### 기록 번호의 영속성

- keyring-store의 NVS key2에 `KSQ1 + nextSequence(u32LE)`를 저장·재읽기한다. **다음 번호 예약을 먼저 완료한 후** 새 원본을 저장한다. 원본 저장이 실패해도 예약된 번호를 재부팅 후 재사용하지 않는다. `UINT32_MAX`에서 wrap하지 않고 FULL/새 생성 보류다.
- NVS key3에는 CSPRNG `recordCursorEpoch` 16B를 한 번 생성한다. 모든 원본을 접수/삭제하거나 서비스 초기화를 해도 counter/epoch는 보존한다. 이전 버전 첫 이관 때 남은 원본의 최대 sequence를 반영하고 새 epoch를 만들어 오래된 앱 조회 위치를 분리한다.
- DEVICE_INFO·STATUS·RECORD·RECORD_END에 `recordCursorEpoch`를 소문자 hex32로 반환한다. 처음은 `GET_RECORD afterSequence:0`; 이어 조회할 때 **같은 recordCursorEpoch**를 함께 전달한다. 양수 afterSequence에 없으면 CURSOR_EPOCH_REQUIRED, 기기 값과 다르면 CURSOR_EPOCH_MISMATCH다. FE는 epoch 변경 시 조회 위치를0으로 시작하고 기존 미접수 submission/receipt는 별도 보존한다.
- 각 원본은 여전히 submissionId/envelopeHash로 식별한다. 조회 번호만으로 서버 접수나 삭제를 허용하지 않는다. 단절 후 같은 sequence/hash 조각 재조회와 서명 receipt 멱등 재시도는 유지한다.

### 서버가 서명하는 만남 기한 — TSV3 / 타입4

새 payloadFormat은 **`tingle-keyring-settings-v3`**, magic은 `TSV3`다. 기존 v2의487B 기본 크기·나머지 offset·제한25B 항목을 유지하며 다음 값만 변경한다.

응답 최대 크기는2304B로 늘렸으며 DEVICE_INFO의 `maxResponseBytes`로 알린다. 요청4096B·설정2048B·20B Write With Response 분할은 그대로다.

| 위치 | v3 값 |
| --- | --- |
| 전체 payload 483..486 | opportunitySeconds u32LE, 필수. 구현 범위1..31,536,000초(365일). 실제 제품 기본7일은 BE 정책으로 공급하며 범위 상한이 제품 기한 결정은 아님 |
| peer grant offset0 | 타입4 |
| grant21..24 | ownershipEpoch u32LE. v3 peer 형식의 명시적 구현 상한 UINT32_MAX이며 큰 값을 자르지 않고 거부함. 바깥 v2 proof/reset 저장의 int64 필드는 그대로 유지 |
| grant25..28 | opportunitySeconds u32LE. 전체 payload 값과 반드시 같음 |
| grant197..260 | `TINGLE-PEER-GRANT-V3\n` + grant 앞197B 원본 이진 bytes에 대한 서버 Ed25519 서명 |

나머지 서버 바깥 서명·nonce/기기/ownerGeneration/run/revision/hash 검증, 기기 AUTH 서명 및 XChaCha20-Poly1305 프레이밍은 동일하다. peerExchangeProtocol capability는 `tingle-peer-v3`다. BE는 grant를 먼저 서명하고 이를 넣은 TSV3 전체 hash를 계산한 후 기존 epoch 포함 바깥 설정 서명을 발급한다. FE는 새 형식을 허용해 바이트 그대로 중계한다.

로컬 원본은 당시 opportunitySeconds·정책/규칙·설정 버전과 계산한 원래 기한을 동결한다. 다음 동기화가3일로 바뀌어도 기존7일 기록을 줄이거나 늘리지 않는다. 다른 기한의 peer끼리는 VERSION_MISMATCH 검증 대기로 두고 임의 한쪽 값을 선택하지 않는다. v2/type3의 예약0은 기존7일로 해석한다. 기존 v2 원본과 TER2 hash는 바뀌지 않는다. 알 수 없는 계산 policy/rule을 실행하지 않는 검사는 유지한다.

### 배터리 측정의 지원 범위

- 기본 보드는 실제 VBAT ADC를8회 평균하고 nRF52840 VBUS 신호로 USB 유무를 읽는다. `powerSource=USB|USB_ABSENT|UNKNOWN`. USB_ABSENT는 배터리 장착 확인을 뜻하지 않는다.
- USB 연결/충전 중에는 전압만으로 배터리를100%로 추정하지 않는다. 별도 연료계가 없으면 정확한 장착 여부는 UNCONFIRMED이고, USB/충전 중 잔량은 null/UNKNOWN이다. 비충전·정상 전압에서는 기존 VOLTAGE_ESTIMATE를 명시한다.
- 별도 측정기가 있다면 devicetree alias `keyring-fuel-gauge`와 해당 칩 드라이버·`CONFIG_FUEL_GAUGE=y`를 구성하면 표준 Zephyr `PRESENT_STATE`/`RELATIVE_STATE_OF_CHARGE`를 읽는다. 성공한 값만 PRESENT/ABSENT·FUEL_GAUGE로 제공하고 실제0%를 유지한다. 측정기 통신 실패·미지원·비정상값을 ADC 정상값으로 덮어쓰지 않는다.
- **현재 기본 XIAO 구성에는 위 별도 잔량계가 선언되어 있지 않다.** 정확한%·장착 판별을 소프트웨어만으로 새로 측정했다고 보고하지 않는다. 배터리 모델별 환산 보정·실물 전압/USB/충전/잔량 대조는 남아 있다. 미측정 null/UNKNOWN은 상태 계약의 정상 지원 분기다.

### FE/BE 적용 및 검사

1. BE: TSV3/타입4 grant·서버 정책 기한 발급, 기존 사용자 쌍 전역 중복 처리, 필요 시 계정 억제 복구 서명 발급. 기존 v2/epoch와 기기 신뢰를 유지한다.
2. FE: capability 확인 후 v3 바이트 중계, 기기별 recordCursorEpoch 저장/재전달·변경 시 조회 위치 초기화, 계정 복구 대기 표시/중계, 잔량 추정·미측정 구분. ACK·초기화의 서버 검증 흐름은 그대로다.
3. 공개 합성 예시는 `tests/app-flow-v3-vectors.json`, 독립 검산은 `node iot/tools/verify-app-flow.mjs iot/tests/app-flow-v3-vectors.json`이다. 기존 v2 예시도 유지한다. 두 벡터의 키는 공개 합성 시험 키이며 팀 서버 실제 비밀키가 아니다.
4. `tools/test-host.ps1` / `test-product.ps1`은 계정 참조 보존·원본 삭제 후 재부팅·번호 예약 실패·옛 epoch 거부·3일 기한·기존7일 호환·계정 복구 서명/변조/재시도·배터리0%/USB/부재/측정 오류를 검사한다. 실물 탑재와 실제 앱/BE 연동은 별도로 확인한다.

---

2026-11-06 · IoT 김종무 · 펌웨어 `0.5.0-app-flow`

**펌웨어에서 구현한 새 연동 계약이며 FE/BE의 현재 배포 계약이 아니다.** 사용자는 한 계정 여러 키링, 정상 등록 후 설정 저장·자동 확장 광고/스캔, 다중 주변 상대, 세 큐·영구 기록·서버 접수 후 삭제를 요청했다. 외부 모터/RGB/버튼 배선과 실제 구동은 사용자의 후속 작업으로 보류했다. 이 문서가 기존 0.3.1 저장 후보와 0.4.0 peer 개발 문서의 전체 앱 흐름을 보강한다. AI147/148의 운영 승인이나 실제 보드 종단 통과를 주장하지 않는다.

## 1. 사용 흐름과 각 파트 작업

| 단계 | 펌웨어 | FE에서 연결할 일 | BE에서 구현·확인할 일 |
| --- | --- | --- | --- |
| 탐색·등록 | NUS 정보/상태·challenge 서명, 기존 기기 키 유지 | NUS 서비스로 탐색, serial/keyId 대조, 기기별 등록 challenge·서명 중계 | 기존 등록 검증. 기기당 소유자 1명, **계정당 여러 기기 허용**. 기존 보유 기기 때문에 새 기기를 거절하는 제한 제거 |
| 설정 준비 | 새 nonce, epoch 검증, 서명된 TSV2·peer grant 검증 | 기기별 nonce → 해당 기기의 sync-run 생성 | AI 파생 결과·최소 개인 조건·관계 제한을 실제 데이터에서 매핑. 기기별 grant와 전체 설정을 서명 |
| 설정 저장 | 암호화 특성 수신, 단일 NVS 레코드 저장/전체 재읽기, 서명 STORED | STORED를 기존 서버 ACK API에 전달, 최신 run/version/hash 검증 | 기존 기기 서명 ACK 검증. BLE 송신 성공만으로 준비 상태 승격 금지 |
| 주변 교환 | USB LAB 없이 확장 광고와 스캔 병행. 상대 연결 없이 암호화 인증·성향 교환, 양방향 계산 | 앱이 계속 연결되어 있을 필요 없음 | 유효 grant 만료·제한 목록·정책 버전 공급. 만료 후 재동기화 |
| 기록 업로드 | 동결한 원본·당시 양쪽 인증서를 보존, owner session 후 조각 조회 | 재연결·앱 실행·수동 시 기록 조회, hash 확인 후 서버 전송 | 실제 `POST /api/offline-encounters/sync`에 새 증명 검증기 연결. 양쪽 기록 검증 전 제안 금지 |
| 원본 정리 | 서버의 서명된 정확한 접수 증명만 ACK_RECORD로 허용. 반복 억제 유지 | HTTP 응답의 서명 필드를 그대로 기기로 전달 | 내구 저장 트랜잭션 완료 후 `tingle-record-receipt-v2` 발급. 응답 유실 시 같은 접수 결과·서명 재조회 |
| 새 만남 허용 | 서버 release + 실제 이탈 관측 후 억제 해제 | 서버 release를 해당 기기에 중계 | 제안/거절30일/차단 등 현재 관계 정책 검사 후 서명 release 발급 |
| 초기화·재등록 | 기존 reset 저널·키 보존·완료 증명 재조회. 신규 보조 기록도 소유 삭제 범위 포함 | 기존 초기화 흐름 사용, 양쪽 완료 전 완료 표시 금지 | BE249/251의 확정 계약 대조. 기기 증명 확인 후 소유 해제·epoch 증가 |

펌웨어는 서버에 직접 HTTP 요청하지 않는다. 이 절은0.5 당시 범위이며 현재 FE 보호 전송/capability·196B 저장 중계 및 BE 발급 지원은 맨 위 최신 develop 반영 절을 따른다. **전체 TSV3 설정/기록 경로를 연결하지 않으면 UF2 교체만으로 모든 기능이 앱에서 활성화되지 않는다.**

## 2. BLE와 공통 규칙

- 기존 NUS: 서비스 `6e400001-b5a3-f393-e0a9-e50e24dcca9e`, RX `...0002...` Write With Response, TX `...0003...` Notify.
- 개인 설정/소유자/기록: 추가 특성 `6e400004-b5a3-f393-e0a9-e50e24dcca9e`. 앱은 같은 연결에서 **Read probe → `01`**을 먼저 성공시킨 후 이 특성으로 Write With Response한다. Zephyr L2 링크 암호화를 요구한다. L2의 Just Works는 소유 인증을 대신하지 않아 아래 서버 소유 증명도 필요하다.
- JSON UTF-8 한 줄 + LF. 분할 송신은 최대20B, 한 요청의 응답 완료 후 다음 요청. 최대 요청4096B, **현재 응답2304B**(0.5 당시2048B에서 후속 확대), 설정2048B. 요청의 `protocolVersion:1`, `requestId`는 기존 규격. 수신 조각/단절은 영속 저장을 바꾸지 않는다.
- 등록 창60초, 설정 nonce60초, 소유 nonce60초, 서버 등록 challenge5분은 각각 다르다. 큐 대기 시간은 없다.
- 공개 기기 정보의 `publicKeySpkiBase64url`은 공개키를 전달하는 값이다. **기기 자체 제공 공개키만으로 공장 신뢰를 자동 생성하지 않는다.** BE는 기존 공장 신뢰/승인된 개발 enrollment를 유지한다. 서버 비밀키·기기 seed를 FE나 보드에 배포하지 않는다.
- 팀 서버 검증키: ID `dev-ed25519-799e2999c648fda3`, raw32 hex `799e2999c648fda34a67ac0bce7bf353e3ead04b0e0927c3885b0bcc82ff30d9`.

## 3. 설정 payload `tingle-keyring-settings-v2`

바깥 SYNC_SETTINGS·STORED는 기존 `tingle-dev-settings-proof-v2` / `tingle-device-proof-v2`의 epoch 포함 원문을 그대로 사용한다. `payloadFormat`만 새 값이고 `payloadHash`는 **TSV2 전체의 SHA-256 소문자 hex**, payload는 그 전체의 Base64url(no padding)이다. 정확한 바깥 원문은 [설정 서명 인계](dev-settings-signing-handoff-v1.md), 구현은 `src/settings_auth.c`다. v1 바깥 증명으로 새 payload를 받지 않는다.

내부 정수는 별도 표시가 없으면 unsigned little-endian. 기본487B + 제한 항목25B×최대60 = 최대1987B.

| offset | bytes | 내용 |
| --- | ---: | --- |
| 0 | 4 | ASCII TSV2 |
| 4 | 196 | 기존 TPS1 성향·최소 프로필. [저장 인계](dev-settings-signing-handoff-v1.md)의 196B 표 그대로 |
| 200 | 261 | 아래 서버 서명 peer grant |
| 461 | 4 | policy `0x52504331` |
| 465 | 4 | rule `0x10001101` |
| 469 | 8 | validUntil epoch초. grant의 값과 같아야 함 |
| 477 | 2 | 제한 항목 수0..60 |
| 479 | 2 | presenceGapSeconds 1..300. BE가 합의하여 공급할 이탈 관측 간격. 큐 지연이 아님 |
| 481 | 1 | restrictionsComplete 0/1. 서버가 전체 적용 제한을 공급했는지 |
| 482 | 1 | deviceAllowed 0/1. 소유/계정/기기 사용 가능 상태. 사용자 매칭 ON/OFF와 별개 |
| 483 | 4 | 예약0 |
| 487 | 25×N | 상대 계정 ownerRef16 + mode1 + untilEpoch8. mode1=기한 제한(양수), mode2=영구 제한(until=0). 중복 ref 거부 |

196B는 `tools/profile_payload.py`가 참조 codec이다. r2 파생 스냅샷·메타데이터/hash를 매핑하고 unknown/ANY/missing/value를 구분한다. 나이는 성인 나이만, 생년월일/사진 원문/이름/서버 토큰/원본 설문은 넣지 않는다. 현재 참조의 반대 성별·단일 희망 성별 조건을 적용한다. 임의 설문 응답이나 성향을 만들어 실제 사용자 데이터처럼 채우지 않는다.

제한이60개를 넘거나 서버가 완전한 현재 목록을 공급할 수 없으면 `restrictionsComplete=0`으로 저장시키되 새 매칭을 보류한다. 제한을 잘라내면서 complete=1로 보내지 않는다. 앱은 저장 완료와 `matchingReady=false`를 구분한다. 저장된 OFF/사용 불가 설정도 검증 후 저장하고 무선 매칭을 중지한다.

### Peer grant261B

| offset | bytes | 내용 |
| --- | ---: | --- |
| 0 | 1 | 타입3 |
| 1 | 8 | serial `XIAO-` 뒤 소문자 hex16자리의 원본8B |
| 9 | 8 | 서버 deviceId, 1..INT64_MAX |
| 17 | 4 | settingsVersion, 1..UINT32_MAX |
| 21 | 8 | ownershipEpoch, 0..INT64_MAX |
| 29,45 | 각16 | ownerGeneration / syncRunId UUID의 원본 byte 순서 |
| 61 | 32 | 바깥 설정 nonce의 Base64url 복호화 원본 |
| 93 | 16 | **ownerRef**: BE가 생성한 불투명 계정 식별값. 같은 계정의 여러 기기는 동일값, 다른 계정은 다른 값. 사용자 ID를 그대로 넣지 않음 |
| 109,113 | 각4 | policy / rule 위와 동일 |
| 117 | 8 | grant validUntil epoch초, issuedAt보다 크고 현재보다 미래 |
| 125 | 8 | issuedAt epoch초, 바깥 serverTime 이하 |
| 133 | 32 | 공개 TPS1 196B만의 SHA-256. 전체 TSV2 해시와 다름 |
| 165 | 32 | 해당 기기의 Ed25519 공개키 |
| 197 | 64 | 서버 Ed25519 서명 |

**grant 서명 원문** = UTF-8 `TINGLE-PEER-GRANT-V2\n` + 앞197B의 원본 이진 bytes. Base64·hex·JSON으로 바꾸어 서명하지 않으며 바이너리 뒤 LF를 추가하지 않는다. 참조 코드의 `Buffer.concat([Buffer.from('TINGLE-PEER-GRANT-V2\n'), grant.subarray(0,197)])`를 사용한다.

grant를 먼저 서명하고, 이를 TSV2에 넣고, 전체 payloadHash를 계산하여 바깥 설정을 서명한다. 순환 해시 의존은 없다. 바깥 deviceId/epoch/run/version/nonce/기기 공개키와 내부 값이 다르면 거부한다.

무선 AUTH325B는 grant261B + 기기 서명64B다. 기기 서명 원문 = `TINGLE-PEER-AUTH-V1\n` + 임시 X25519 공개키32 + grant261. 전송은 기존 PX1 프레이밍·XChaCha20-Poly1305 암호화를 재사용한다. type3를 모르는 0.4.0과 새 전체 모드는 상호 매칭하지 않는다. [기존 peer 문서](peer-profile-exchange-v1.md)의 wire·계산 기준을 함께 본다.

만료시간은 BE가 공급하며 펌웨어가 임의로24시간을 만들지 않는다. 재부팅 후 저장 버전은 유지하지만 신뢰 시간이 없으므로 **새 nonce와 서명 설정 동기화 전 광고 매칭 보류**. 만료/사용 불가/불완전 제한에서도 기존 미접수 기록을 지우지 않는다. 오프라인 기기는 갱신 전 서버의 새 제한을 즉시 알 수 없으므로 BE가 grant 수명·즉시 동기화 정책을 결정해야 한다.

## 4. 소유자 세션 — 상태 상세/기록 API 인증

기존 설정/초기화 서명과 별도다. BE에 제안하는 새 HTTP API는 `POST /api/keyrings/{id}/owner-session-grants`다. 현재 서버에 있다고 가정하지 않는다. 로그인 소유자·기기·현재 ownerGeneration/epoch·로그인 family를 확인하고 해당 기기의 nonce에 서명한다.

```json
{"protocolVersion":1,"type":"GET_OWNER_CHALLENGE","requestId":"o1"}
{"protocolVersion":1,"type":"OWNER_CHALLENGE","requestId":"o1","deviceNonce":"<43chars>","expiresInSeconds":60}
```

AUTHORIZE_OWNER 요청 필드:

```json
{"protocolVersion":1,"type":"AUTHORIZE_OWNER","requestId":"o2","proofProtocolVersion":"tingle-owner-session-v2","serverKeyId":"dev-ed25519-799e2999c648fda3","deviceId":4,"ownerGeneration":"<uuid>","ownershipEpoch":1,"deviceNonce":"<43chars>","permissions":15,"serverTime":"2026-11-06T03:00:00Z","expiresAtEpochSeconds":1793934300,"serverSignature":"<base64url64bytes>"}
```

위 예시의 시각/ID는 형태 예시이며 실제 nonce·현재 서버 시각·소유 세대에서 생성한다. permissions는 READ=1, ACK=2, RELEASE=4, CONTROL=8의 합. 최소 필요 권한만 발급하며 유효기간은 발급시각부터 최대300초. 성공은 `OWNER_RESULT authorized:true, permissions`다. 같은 연결/같은 증명 재시도는 기한을 연장하지 않는다. 단절·재연결·앱 재시작·소유 세대 변경 시 새 challenge부터 다시 한다.

원문은 다음 각 줄 + **마지막 LF 포함**, 숫자는10진 정수, UUID/hex는 소문자다:

```text
TINGLE-OWNER-SESSION
tingle-owner-session-v2
{serverKeyId}
{deviceSerial}
{deviceKeyId}
{deviceId}
{ownerGeneration}
{ownershipEpoch}
{deviceNonce}
{permissions}
{serverTimeEpochSeconds}
{expiresAtEpochSeconds}
```

GET_STATUS는 미인증이면 기록 용량·개수 null/UNKNOWN이다. READ 후 NVS 실제 전체/사용 bytes와 candidate/pending/review 수를 응답한다. 배터리%는 측정 전압3300..4200mV의 선형 **VOLTAGE_ESTIMATE**이며 실제 연료계나 배터리 존재 측정이 아니다. 미측정·범위 밖은 null/UNKNOWN, batteryPresence는 UNCONFIRMED다. storageState는 AVAILABLE/FULL/ERROR/UNKNOWN, 충전 미확인은 null이다. `applied`(현재 전체 설정 적용), `timeReady`(현 부팅의 서명 시간 확보), `matchingReady`(정책 유효+ON+엔진/세션 준비)는 별도다.

## 5. 기록 조회·동결 증명

READ 권한 후 `GET_RECORD {afterSequence:0}`. CONFIRMED/REVIEW에서 다음 sequence를 반환하며 없으면 RECORD_END다. 성공 RECORD의 필드:

`sequence, kind, eventId, submissionId, deviceId, ownerGeneration, ownershipEpoch, occurredAtEpochSeconds, expiresAtEpochSeconds, policyVersion, matchingRuleVersion, settingsSnapshotId, peerCredential, evidenceProtocolVersion, totalBytes, envelopeHash`.

policyVersion=`tingle-policy-v2`, matchingRuleVersion=`r2-c-20261106`, evidenceProtocolVersion=`tingle-encounter-v2`는 **이번 개발 계약 식별값**이며 AI/BE 운영 승인을 대신하지 않는다. settingsSnapshotId는 당시 own syncRunId다. peerCredential은 당시 상대 AUTH325+임시 공개키32의 Base64url. REVIEW는 상대 증명이 없을 수 있으며 정식 제안 대상이 아니다.

```json
{"protocolVersion":1,"type":"READ_RECORD_CHUNK","requestId":"r2","sequence":1,"offset":0,"length":512,"envelopeHash":"<64hex>"}
```

응답 RECORD_CHUNK는 `sequence,offset,totalBytes,envelopeHash,data`(Base64url)를 준다. length는1..512. 끝까지 결합하여 전체 SHA-256을 비교하고 cursor/hash가 달라지면 처음부터 재조회한다. **읽기만으로 삭제되지 않는다.** 설정을 새 버전으로 동기화하거나 재시작해도 원본 증명·submissionId·hash가 바뀌지 않는다.

TER2 envelope:

| 순서 | 내용 |
| --- | --- |
| 0..23 | ASCII TER2 + recordWireLen u16 + evidenceLen u16(795) + submission UUID16 |
| 다음 | canonical recordWire |
| 다음795 | 동결 evidence |
| 마지막64 | 기기 Ed25519 서명 |

recordWire: version1/kind1/정규화 phase1/reason1 + sequence u32 + eventId16 + peer hardwareRef u64 + occurredAt u64 + expiresAt u64 + ownWireLen u16 + peerWireLen u16 + own profileWire + 원래 peer PROFILE/error packet. own profileWire는 revision/policy/rule/opportunity u32×4 + snapshot16 + dataLen u16 + TPS1 196B다. peer PROFILE은 기존 248B 엔진 패킷이다. export phase는 CONFIRMED/REVIEW에 대해 COMPLETE로 정규화하므로 마지막 무선 영수증이 늦어도 hash는 유지된다.

evidence795: ASCII KEV2(4) + sequence4 + peerRef8 + own TPS1 hash32 + peer RAW packet hash32 + own AUTH325 + own 임시 공개키32 + peer AUTH325 + peer 임시 공개키32 + hasPeer1. 해당 원본과 함께 저장되며 이후 설정으로 채워 넣지 않는다.

submissionId = SHA256(recordWire || evidence)의 선행16B에 UUID version5/variant 비트를 지정. 기기 서명 원문 = `TINGLE-ENCOUNTER-V2\n` || SHA256(마지막 서명64B를 제외한 전체 TER2). envelopeHash는 서명까지 포함한 전체 SHA256이다. BE는 자기 기기 서명, 양쪽 credential의 서버/기기 서명·유효 시각·공개 profile hash·event/run/owner epoch를 검증하고 두 사용자의 독립 원본을 대조한다. 무선 근접 거리 자체를 암호학적으로 증명한다는 주장은 하지 않는다.

기존 `EncounterSyncRequest`의 `deviceId, submissions[{submissionId,eventId,occurredAt,policyVersion,matchingRuleVersion,settingsSnapshotId,peerCredential,evidence:{protocolVersion,payload}}]`로 매핑한다. payload는 TER2 전체 Base64url, occurredAt은 원래 epoch를 ISO UTC로 변환하며 새 업로드 시각으로 바꾸지 않는다. BE의 `EncounterSubmissionProofVerifier`에 이 계약용 실제 verifier가 필요하다. REVIEW는 별도 서버 검증 접수 API/격리 저장을 BE와 확정하고 일반 만남·제안 생성 경로에 넣지 않는다.

0.4/옛 LAB 원본은 당시 공식 증명이 없다. `LEGACY_EVIDENCE_UNAVAILABLE` 오류와 sequence를 반환해 FE가 다음 cursor를 조회할 수 있게 하며 원본은 그대로 남긴다. 데이터가 없다고 표시하거나 현재 설정으로 가짜 증명을 만들거나 강제 초기화하지 않는다. 별도 진단/명시 동의 처리로 이관한다.

## 6. 서버 접수 ACK와 반복 억제 해제

BE의 현재 `canDeleteLocalRecord` boolean만으로 삭제하지 않는다. BE 내구 저장 후 아래 서명을 추가하고 FE가 ACK_RECORD로 중계한다. **중복 접수는 같은 원본의 기존 receipt를 반환**한다.

```json
{"protocolVersion":1,"type":"ACK_RECORD","requestId":"a1","proofProtocolVersion":"tingle-record-receipt-v2","serverKeyId":"dev-ed25519-799e2999c648fda3","deviceId":4,"ownerGeneration":"<uuid>","ownershipEpoch":1,"eventId":"<uuid>","submissionId":"<uuid>","envelopeHash":"<64hex>","receiptId":"<uuid>","receivedAtEpochSeconds":1793934000,"receiptStatus":"STORED","deletePermission":"DELETE_ORIGINAL","serverSignature":"<base64url64bytes>"}
```

서명 원문 각 줄+마지막LF:

```text
TINGLE-RECORD-RECEIPT
tingle-record-receipt-v2
{serverKeyId}
{deviceSerial}
{deviceKeyId}
{deviceId}
{ownerGeneration}
{ownershipEpoch}
{eventId}
{submissionId}
{envelopeHash}
{receiptId}
{receivedAtEpochSeconds}
STORED
DELETE_ORIGINAL
```

원본이 있으면 hash/submissionId까지 일치해야 삭제한다. 반복 억제를 먼저 저장하고 원본을 삭제·재읽기하므로 도중 전원이 꺼져도 새 만남으로 중복 처리하지 않는다. 성공 RECORD_ACK.result=`REMOVED_OR_ALREADY_REMOVED`. 이미 삭제된 원본에 대한 유효한 같은 증명은 멱등 성공하며 다른 슬롯을 삭제하지 않는다. 현재 소유 세대가 다르면 거부한다.

새 만남 재허용이 필요하면 BE가 RELEASE_RECORD를 별도로 서명한다. 필드는 `proofProtocolVersion:tingle-record-release-v2`, owner 필드, eventId, **peerDeviceRefHex**(원본 peerRef를16자리 소문자 숫자 hex 문자열로), receiptId, receivedAtEpochSeconds, result, serverKeyId/serverSignature다. JSON 정밀도 문제를 피하려고 uint64를 숫자로 보내지 않는다.

```text
TINGLE-RECORD-RELEASE
tingle-record-release-v2
{serverKeyId}
{deviceSerial}
{deviceKeyId}
{deviceId}
{ownerGeneration}
{ownershipEpoch}
{eventId}
{peerDeviceRefHex}
{receiptId}
{receivedAtEpochSeconds}
{result}
```

result=`RELEASE_AFTER_DEPARTURE`: 정상 peerRef(high bit0), 원본 삭제 완료·무선 최종 처리 완료(또는 원래 만남 기한 종료)·인증된 상대의 마지막 수신 이후 presence gap을 넘긴 이탈 관측이 있어야 해제한다. gap은 패킷 유실에 따른 관측 기준이므로 실제 거리 보장이 아니다. `ERROR_REVIEWED`: high bit1의 오류 fingerprint만 대상으로 하며 원본 접수 후 오류 반복 억제를 해제한다. 실제 상대의 이탈 검사를 우회하는 용도로 쓰지 않는다. 성공 RELEASE_RESULT.result=`RELEASED`.

서버 접수/원래 기한 만료 전 유효 원본은 공간 부족 때문에 덮어쓰지 않는다. 만남 원래 기한은 최대7일, 신뢰 시간이 있는 경우에만 증명이 있는 기한 지난 원본을 정리한다. 재시작/재전송으로 기한을 연장하지 않는다. 기록·억제 슬롯이 차면 새 작업을 보류하고 앱에 FULL/오류를 노출한다.

## 7. 오류·재시도·앱 보관 값

ERROR는 기존 `protocolVersion,type:ERROR,requestId,code`. 새 코드: SECURE_TRANSPORT_REQUIRED, OWNER_NOT_BOUND, OWNER_CHALLENGE_REQUIRED, INVALID_OWNER_PROOF, OWNER_PROOF_EXPIRED, OWNER_SESSION_REQUIRED, OWNER_MISMATCH, INVALID_PEER_GRANT, OWNERSHIP_EPOCH_REQUIRED, INVALID_RECEIPT, INVALID_SERVER_PROOF, RECEIPT_CONFLICT, INVALID_RELEASE, RELEASE_CONFLICT, DEPARTURE_NOT_CONFIRMED, RELEASE_NOT_READY, INVALID_CURSOR, RECORD_NOT_FOUND, RECORD_READ_CONFLICT, LEGACY_EVIDENCE_UNAVAILABLE, INVALID_RECORD_EVIDENCE, RECORD_TOO_LARGE, STORAGE_ERROR, RANDOM_ERROR.

- FE는 **기기별** serial/deviceId/keyId/ownerGeneration/epoch, syncRunId/version/hash/nonce, 최신 저장 ACK 상태를 관리한다. 한 기기의 성공을 같은 계정의 모든 키링 성공으로 표시하지 않는다.
- 기록 업로드 중에는 sequence/eventId/submissionId/envelopeHash/전체 bytes 또는 조각 상태/서버 receipt를 앱의 보호 저장소에 보관한다. owner session은 RAM 연결 단위이며 앱 재시작 후 새로 받는다.
- LF 이전 단절: 기기는 이전 정상 설정 유지. 재연결 후 새 nonce·서버 실행을 받아 재전송. STORED 유실은 기존 규격의 동일 저장 재확인/서버 ACK 재전송을 사용한다.
- 기록 읽기 단절: 새 owner session → 같은 sequence/hash 재조회·재개. 서버 업로드 유실: 동일 submissionId로 재요청하여 기존 receipt를 받고 ACK_RECORD 재시도. ACK 유실: 동일 receipt 재전송.
- 시간 불확실·공간 부족·서명 오류는 성공으로 꾸미지 않는다. 서버 제한/기한/초기화 완료가 미확인이면 해당 상태를 유지한다.
- STOP_FEEDBACK는 CONTROL 권한 후 기존 보드 LED를 끄는 소프트웨어 명령이다. 제품 외부 RGB 색·양쪽 진동·물리 버튼 중지·이탈 소등 실측은 배선 확정 후 별도 완료한다.

## 8. 검증과 적용 순서

1. BE/AI가 위 필드·source snapshot·policy/rule 식별값·grant 수명·presence gap·제한60개 초과 처리·REVIEW 접수·release 정책을 확인한다. 운영 승인을 받기 전 현재 형식을 운영 확정으로 표기하지 않는다.
2. BE가 새 payload/grant/바깥 서명, owner session, encounter verifier, 서명 receipt/release, 계정 다중 등록과 사용자 쌍 단위 제안 중복 방지를 구현한다.
3. FE가 암호화 특성·기기별 전체 동기화·소유 세션·조각 전송/검증·서명 ACK/release 중계·재시작 복구를 구현한다. 구형 capability 기기에 새 명령을 보내지 않는다.
4. 같은 공통 UF2로 기존 serial/keyId/개인키·NVS 영역을 보존하며 업데이트한다. 전체 erase/초기화/기기 키 재생성은 하지 않는다.
5. 실제 앱에서 등록→성향/개인 조건 저장→STORED→서버 ACK→광고/스캔→서로 다른 계정 매칭→원본 업로드→서명 접수 삭제를 시험한다. 같은 계정 여러 기기끼리 비매칭, 주변10대/패킷 유실/재부팅/동기화 OFF/오래된 epoch/제한/만료/공간 부족도 별도 확인한다.

호스트: `tools/test-product.ps1` 및 통합 `tools/test-host.ps1`. C 합성 기기 + 별도 Node/OpenSSL로 grant·바깥 설정·소유 세션·양쪽 기기 인증서·TER2·서버 receipt/release를 검산한다. **바로 공유할 payload/정확한 원문·서명 예시는 `tests/app-flow-v2-vectors.json`**이다. Node가 있으면 저장소 루트에서 `node iot/tools/verify-app-flow.mjs iot/tests/app-flow-v2-vectors.json`으로 SDK 없이 검산할 수 있다. 이 파일은 실제 팀 서버 키와 다른 **공개 합성 시험 벡터**이며 현재 개발 서버에서 동작할 사용자 설정이 아니다. 실제 사용자/팀 서버 데이터로 바꾸어 전달하지 않는다.

호스트 실행 후 원본은 `build/tests/product/vectors.properties`, 변환기는 `tools/export-app-flow-vectors.py`다. 합성 개인키는 호스트 테스트 소스에만 있으며 펌웨어 CMake에 포함하지 않는다. 실제 팀 서버 비밀키를 사용하는 검사가 아니다.

최종 파일은 `iot/build/zephyr.uf2`, 버전/해시/기준 커밋·dirty 여부는 옆 `firmware-manifest.json`으로 확인한다. 로컬 검사·UF2 생성과 보드 탑재·실제 앱/서버 종단 통과는 별도로 기록한다.
