# 키링 개발 설정 서명 v1 · FE/BE 연동 안내

2026-11-04 · 담당 김종무(IoT) · [S15P31E106-225](https://ssafy.atlassian.net/browse/S15P31E106-225) · 검토 FE·BE

**2026-11-06 후속:** 현재 통합 버전은 `0.7.2-owner-flow`이며 [전체 앱 연동 인계 맨 위](app-flow-v2-handoff.md)의 TSV3·타입4 grant·소유 세대/기록 계약을 우선한다. 아래0.3.1/0.4.0 및 인계의0.5/0.6 설명은 각 단계의 기록이다. 기존 바깥 설정 서명 원문과196B codec은 재사용하며 전체 성향/개인 조건 공급·조각 기록 전송·서명 ACK 중계의 FE/BE 변경은 여전히 필요하다.

설정 v1은 `0.2.3-dev-signed`에서 도입했으며 현재 통합 빌드는 `0.4.0-dev-peer`다. 기존 ON/OFF·등록·서명 저장 흐름을 유지한다. 2026-11-06의 `0.3.1` 저장 후보는 아래에 기록하고, 이후 추가한 **키링 간 암호화 광고 교환·PC 참조 계산 개발 경로**는 [peer 개발 명세](peer-profile-exchange-v1.md)를 따른다. 아래 표와 저장 후보 절의 “계산/교환 후속”은 0.3.1 범위의 기록이며, 현재 운영 관계 제한·공식 만남 업로드·실물 종단 검증은 여전히 후속이다. BE/FE의 새 payload 공급/중계도 아직 필요하다. 기존 epoch=0 기기는 v1 원문을 유지하고 초기화 후 v2는 [BLE 계약 6절](keyring-app-ble-contract-v1.md#6-초기화등록-해제양도)을 따른다.

## 2026-11-06 성향·최소 프로필 저장 후보 — BE/FE 공동 적용 필요

상태: IoT 수신·검증·저장 구현 후보. 팀의 운영 codec/계산 승인, 앱/서버 배포, 실물 시험 완료를 뜻하지 않는다. 사용자 요청으로 준비했으며 BE/FE 코드는 이번 IoT 변경에서 수정하지 않았다. 기존 ON/OFF v1과 새 저장 형식을 구분한다.

### 현재 프로젝트에서 확인한 차이

| 영역 | 실제 코드와 남은 연결 |
| --- | --- |
| BE 발급 | `back/src/main/java/com/e106/tingle/keyring/DevSettingsIssuer.java`는 settingsVersion과 matchingEnabled만 읽어 1바이트를 만든다. `back/src/main/java/com/e106/tingle/keyring/proof/ServerSettingsTranscript.java`도 그 형식/길이만 허용한다. |
| BE 기본 공급자 | `KeyringPayloadConfiguration.java`의 기본 공급자는 POLICY_UNRESOLVED다. `VerifiedSettingsInputService.java`에는 나이·성별·생활 정보·사진 여부·옵션/희망 나이의 검증 자료가 있지만, 실제 성향 값 대신 분석 결과 hash를 보관하며 `ANALYSIS_MATCHING_ARTIFACT_MAPPING_UNAPPROVED`를 남긴다. 이 자료 자체를 매칭 payload로 사용하지 않는다. |
| FE 중계 | `front/app/shared/src/commonMain/kotlin/com/ssafy/e106/features/keyring/data/KeyringSyncRepository.kt`의 `signed()`는 `tingle-dev-settings-v1`만 허용한다. payload는 서버 바이트를 그대로 중계하는 구조다. |
| IoT 0.3.1 | 아래 196B payload를 기존 SYNC_SETTINGS로 검증·영구 저장하고, 기존 STORED 증명을 반환한다. 실제 광고에는 계속 발견용 UUID/난수만 넣는다. 성향·개인 필드는 광고로 내보내지 않는다. |

### 저장 범위와 수신 계약

- payloadFormat: `tingle-profile-storage-v1`; payloadEncoding: `base64url`; 원본 길이: **196B**.
- 전달 외피: 기존 `SYNC_SETTINGS` 필드를 유지한다. NUS JSON 한 줄 + LF, 최대 20B씩 Write with Response. `KR_PAYLOAD_MAX=2048`, `KR_FRAME_MAX=4096`은 그대로다.
- 프로필 전송은 NUS 서비스 안의 **추가 보호 특성 `6e400004-b5a3-f393-e0a9-e50e24dcca9e`**를 사용한다. Read/Write 모두 BLE 암호화(L2 이상)를 요구한다. 기존 등록·ON/OFF RX `6e400002...`와 TX `6e400003...`는 유지한다. 프로필을 기존 RX로 보내면 암호화 여부와 관계없이 SECURE_TRANSPORT_REQUIRED다.
- FE 순서: 서비스 재탐색 → 보호 특성 **Read 성공·응답 1바이트 `01` 확인** → GET_SYNC_CHALLENGE → 서버 발급 → 같은 연결의 보호 특성으로 profile JSON 조각 전송 → 기존 TX의 STORED → 서버 ACK. 암호화 협상을 실제 프로필 Write로 시작하지 않는다. probe read에는 개인정보가 없다. 연결이 바뀌면 다시 probe하며 BOND_BONDED 표시만으로 암호화를 확정하지 않는다. Android/iOS의 실제 암호화 협상·bond/cache 복구는 실물 확인 대상이다.
- 서버 공개키: `dev-ed25519-799e2999c648fda3`, raw hex `799e2999c648fda34a67ac0bce7bf353e3ead04b0e0927c3885b0bcc82ff30d9`. 서버 개인키를 IoT/FE에 전달하지 않는다.
- 기기 측은 기존 `tingle-dev-settings-proof-v1/v2`의 **동일 필드 순서·마지막 LF**로 검증한다. 새 payloadFormat과 payloadHash도 서명에 포함하므로 기존 1바이트 서명을 재사용할 수 없다. BE는 새 형식을 명시적으로 허용하고 새 바이트의 hash에 서명해야 한다.
- epoch=0의 v1/v2, 초기화 후 epoch≥1의 필수 v2를 유지한다. v2는 원문 마지막에 ownershipEpoch 한 줄을 추가한다.
- `settingsVersion`이 같은데 payloadHash/format이 바뀌면 VERSION_CONFLICT다. **ON/OFF → profile 형식 전환과 저장할 필드 변경 시 서버 설정 버전을 증가**시킨다. 동일 run의 bytes/hash/format/소유 세대를 뒤늦게 바꾸지 않는다.
- 기존 NVS key10의 레코드 구조/크기·기기 키 key1/marker2·초기화 저널·저장 영역은 변경하지 않는다. 전송 조각이나 검증 실패를 저장하지 않고, 원자 저장/읽기 확인 후 STORED를 만든다. 저장 후 응답 유실은 재조회/재전송으로 확인한다.

새 DEVICE_INFO 진단 필드:

```json
{
  "supportedPayloadFormats": ["tingle-dev-settings-v1", "tingle-profile-storage-v1"],
  "storedPayloadFormat": "tingle-profile-storage-v1",
  "profileStored": true,
  "storedPayloadBytes": 196,
  "profileStorageCharacteristic": "6e400004-b5a3-f393-e0a9-e50e24dcca9e",
  "profileRequiresEncryption": true,
  "applied": false,
  "timeReady": false
}
```

위는 일부 필드만 보인 예시다. 저장 버전·hash·STORED 기기 서명과 함께 확인한다. 진단 필드와 applied/timeReady는 서명 대상이 아니며 true 표시만으로 서버 ACK나 매칭 READY를 승인하지 않는다. 새 profile의 matchingEnabled는 기존 발견 ON/OFF만 제어한다. 진동/LED 값은 저장되지만 실제 출력/계산 연결은 후속이다. 재부팅 후 저장 payload·버전은 복원하고, 새 서명 설정 동기화 전 peer 광고/스캔을 재개하지 않는다.

### 값 출처와 개인정보 최소화

| 데이터 | BE가 준비할 출처/규칙 |
| --- | --- |
| 성향 | AI147/148의 논리 snapshot: E/A/C/I, Q30~60 actual, Q61~95 wishes, Q96 priority, Q97 strict, Q98 tolerance. 원본 100문항이나 분석 요약 문장을 전송하지 않는다. |
| 값 상태 | value/unknown/any/missing을 그대로 보존한다. actual에 any 금지, Q98에 any 금지. 누락을 0/false/제한 없음으로 채우지 않는다. |
| 프로필 | 실제 서버 프로필에서 만 나이·성별·흡연·음주·종교·사진 유무만 파생한다. 나이는 BE의 Asia/Seoul 기준 계산값으로 보내며 생년월일을 보드에 저장하지 않는다. |
| 희망 조건 | desiredGender, desiredAge mode/range, requirePartnerPhoto. 나이 상한 없음은 max=null로 표현한다. |
| 기기 옵션 | matchingEnabled, vibrationEnabled, ledEnabled. 세 boolean이 모두 확인돼야 한다. 미측정·미선택을 false로 바꾸지 않는다. |
| 제외 | nickname, userId 원문, birthDate, introduction, 사진 파일/URL, 토큰, 원본 설문, 채팅 내용. 참조 encoder는 정의 밖의 키를 거부한다. |

현재 서버의 사진 공개/생활 정보 API 정책을 이 저장 codec으로 변경하지 않는다. 이 형식은 내 기기 로컬 저장용이며 peer 인증·공개 범위를 정한 것이 아니다. 관계 제한/신뢰 시간/credential의 제품 교환 규격도 별도 후속이다.

### 196B 저장 codec — 운영 BLE 교환 규격과 구분

정본 구현은 `iot/tools/profile_payload.py`, 기기 검증은 `iot/src/profile.c`다. 첫 4B는 ASCII `TPS1`, 이후 다음 순서의 TLV만 허용한다. TLV는 tag:u8 + length:u16 little-endian이며 중복·미지 tag·다른 순서·뒤쪽 여분 바이트를 거부한다.

| tag | payload 길이 | 내용 |
| --- | ---: | --- |
| 0x01 | 46 | schema byte=1 + ASCII SIS-Q-1.1-100(13B) + question-map.json SHA-256(raw32B) |
| 0x10 | 9 | E/A/C/I: u16 little-endian×4, scale100 + 4개 2bit 상태 |
| 0x11 | 39 | Q30~60: 값31B + 상태8B |
| 0x20 | 5 | Q61~64: 값4B + 상태1B |
| 0x21 | 39 | Q65~95: 값31B + 상태8B |
| 0x30 | 3 | Q96 상태1B + 하위9bit u16, 선택 최대3개 |
| 0x31 | 2 | Q97 상태1B + 하위5bit, 선택 최대2개. smoking/children/religion/tattoo/**age** 모두 보존 |
| 0x32 | 2 | Q98 상태1B + close=1/some=2/wide=3 |
| 0x40 | 16 | 아래 개인/희망 조건 |
| 0x41 | 1 | bit0 matching, bit1 vibration, bit2 LED, 나머지 bit=0 |

codebook SHA-256은 `ee4357d4248e89619845e7ca99ad30542e37d0766454e65a2de5d85c12cba641`다. 2bit 상태는 value=0/unknown=1/any=2/missing=3, 먼저 나오는 필드부터 하위 bit에 둔다. 미사용 상태 bit와 비-value의 값 바이트는 0이어야 한다. 정수 code는 qmap enum 순서의 1부터, 복수 선택은 enum 순서의 bitset이다. 정수 1~5 응답은 같은 숫자를 사용한다.

E/A/C/I는 0..100을 scale100으로 **정확히 표현 가능한 경우만** 저장한다. 소수 세 자리 이상을 임의 반올림하지 않고 거부한다. 이 저장 형식의 선택이 MCU 계산 오차/임계값 합의를 뜻하지 않는다. 기존 PC 후보가 Q97의 age를 제외한 것과 달리 저장 후보는 5번째 bit로 보존한다. 실제 peer wire에 이 후보를 그대로 쓰지 않는다.

0x40의 byte offset:

| offset | 값 |
| --- | --- |
| 0..1 | actualAge u16 (value이면 19..65535, 전송 폭의 기술 상한) |
| 2 | actualGender: MALE=1/FEMALE=2 |
| 3 | desiredGender bitset: MALE=bit0/FEMALE=bit1 |
| 4,5 | hasProfilePhoto / requirePartnerPhoto: value이면 0=false/1=true |
| 6 | smoking: NON_SMOKER=1/E_CIGARETTE=2/ONLY_WHEN_DRINKING=3/SMOKER=4 |
| 7 | drinking: NEVER=1/SPECIAL_OCCASIONS_ONLY=2/MONTHLY_AT_MOST_ONCE=3/MONTHLY_TWO_TO_FOUR=4/WEEKLY_TWO_TO_THREE=5/WEEKLY_FOUR_OR_MORE=6 |
| 8 | religion: NONE=1/PROTESTANT=2/CATHOLIC=3/BUDDHIST=4/OTHER=5/UNDISCLOSED=6 |
| 9..10 | 위 8개 필드의 2bit 상태, 순서 동일. any는 desiredGender에만 허용 |
| 11 | desiredAge mode: missing=0/unknown=1/any=2/range=3 |
| 12..13 | desiredAgeMin u16; range이면 19 이상 |
| 14..15 | desiredAgeMax u16; range의 0은 상한 없음, 나머지는 min 이상 |

개인 enum 번호는 이 후보의 명시적 매핑이다. Java Enum.ordinal()로 만들지 않는다. religion의 unknown/missing과 UNDISCLOSED를 구분한다. desiredAge의 range 이외 mode에서는 min/max 바이트가 0이어야 한다.

참조 도구 입력은 `golden-cases.json`의 `logicalSnapshot.left/right`와 `iot/tests/profile-personal.example.json` 형식의 개인 자료다. 후자는 **가상 27세의 합성 예시**이며 실제 사용자 값이 아니다. 4상태 필드는 `{"state":"value","value":...}` 또는 value 없는 상태 객체로 전달한다. `desiredAge`는 `{"mode":"range","min":25,"max":null}`처럼 쓴다.

```powershell
python iot/tools/profile_payload.py --golden <handoff>/golden-cases.json --case NORMAL --side left --personal iot/tests/profile-personal.example.json --output iot/build/tests/profile/example.bin
```

출력은 길이/hash만 표시한다. 원본 payload의 Base64url과 SHA-256은 BE가 발급 응답에 담는다. 앱은 바이너리를 재인코딩하지 않는다. 서버 서명 원문은 다음 순서이며 UTF-8, 각 줄 LF, 마지막 LF도 필수다.

```text
TINGLE-KEYRING-SERVER-SETTINGS
{tingle-dev-settings-proof-v1 또는 v2}
{serverKeyId}
{deviceSerial}
{deviceKeyId}
{deviceId}
{ownerGeneration}
{syncRunId}
{settingsVersion}
tingle-profile-storage-v1
{sha256_of_original_196_bytes_lowercase_hex}
{nonce}
{deviceNonce}
{serverTime_epoch_seconds}
```

v2이면 위 마지막 줄 뒤에 `{ownershipEpoch}\n`를 추가한다. serverTime JSON은 기존 ISO UTC이며 서명에는 epoch 초를 사용한다. 현재 BE 서명 생성기는 새 형식을 거부하므로 **BE 수정·검토 후에만 팀 키의 유효한 새 서명이 발급된다**. 합성 RFC8032 테스트 서명을 팀 키의 발급 결과로 사용하지 않는다.

### 영빈님·가을님에게 전달할 작업

1. `DevSettingsIssuer`/`KeyringPayloadProvider`에서 실제 active 분석/설문·프로필·희망 조건·기기 옵션을 검증해 위 스냅샷을 만든다. 현재 `resultHash`만 있는 서버 입력 증거를 성향 값으로 대체하지 않는다. AI148과 매핑·상태·codebook을 확인한다.
2. 새 payloadFormat을 opt-in으로 공급하고 `ServerSettingsTranscript`의 1바이트 전용 검증을 **두 형식의 명시적 검증**으로 확장한다. 기존 ON/OFF 1바이트와 v1/v2 원문은 보존한다. 서버 키 재발급은 필요 없다.
3. 저장할 프로필·성향·옵션 변경과 형식 전환을 settingsVersion 증가·새 sync run에 연결한다. 이미 발급한 run은 동결하고, 새 deviceNonce 재서명·멱등 재시도·소유 epoch 경계를 유지한다.
4. 기존 sync-runs 응답 필드에 새 format/196B Base64url/hash와 실제 팀 키 서명을 제공한다. STORED/ack는 기존 기기 증명 규격을 유지한다. 저장 ACK만으로 전체 매칭 READY를 올리지 않는다.
5. 정상·unknown·누락·잘못된 enum/서명/hash·구버전·초기화 후 v2 검증 예시와 개발 배포 여부를 공유한다. FE/IoT 합동 적용 후 앱→서버→기기 저장→전원 재시작→같은 hash/버전→서버 ACK를 확인한다.

### 재준님·순철님에게 전달할 작업

1. `KeyringSyncRepository.kt`의 format 제한과 `BleProbeProtocol` 기기 정보 모델에 새 supportedPayloadFormats/storedPayloadFormat/profileStored/storedPayloadBytes를 반영한다. 지원 기기가 아니면 성향 저장을 성공으로 표시하지 않는다.
2. `front/app/shared/src/commonMain/kotlin/com/ssafy/e106/core/ble/NusBleConnection.kt`와 `front/app/shared/src/androidMain/kotlin/com/ssafy/e106/core/ble/AndroidKeyringBle.kt`에 보호 특성 탐색·Read callback/암호화 확인·profile 전용 Write 경로를 추가한다. profileStorageCharacteristic/profileRequiresEncryption을 확인하며, 프로필 바이트를 보내기 전에 위 Read probe를 성공시킨다. 새 UF2 후 GATT 서비스/캐시 재탐색도 확인한다.
3. 서버가 내려준 새 format의 payload/hash/서명·소유 epoch를 그대로 SYNC_SETTINGS에 중계한다. FE의 프로필 API 결과를 임의로 합쳐 서명 없는 payload를 만들지 않는다. 기존 JSON+LF/20B Write with Response와 4096B frame 한도를 유지한다.
4. SETTINGS_RESULT의 run/버전/hash/stored/기기 proof를 검증하고 기존 서버 ack API에 제출한다. 복구 시 DEVICE_INFO와 저장 증명을 재확인한다. applied=false/timeReady=false를 전체 매칭 준비 완료로 표시하지 않는다.
5. 미지원 format·서버 공급 전·암호화/페어링 실패·SECURE_TRANSPORT_REQUIRED·재서명·중단/응답 유실·서버 ACK 재시도를 구분한다. 같은 버전에서 ON/OFF로 임의 downgrade하지 않는다. 기존 초기화/등록 v2 복구도 유지한다.
6. 새 BE 발급 응답과 0.3.1 기기를 함께 사용해 저장/재부팅/ON·OFF를 확인한다. 실제 성향 계산·만남 전송은 이번 저장 시험과 분리한다.

### 검증 근거와 한계

- `iot/tools/test-host.ps1`: 기존 큐/엔진·등록·설정·초기화 회귀 + 새 profile codec·C 저장·독립 JDK 서명/실제 BE STORED 검증기를 실행한다.
- 2026-11-06 외부 handoff의 원본 hash 33개를 대조했다. 지원 surveyVersion의 스냅샷 9개는 196B 인코딩/복원 및 C 서명·저장·재시작 복구를 통과했고, 미확정 surveyVersion 1개는 거부했다. 결과는 Git 제외 파일 `iot/build/tests/profile/golden/verification.json`에 남긴다. 이 검산은 수치 매칭 계산/실물 시험이 아니다.
- signed SYNC_SETTINGS의 저장·재시작·실패 복구는 호스트 모의 NVS로 검증한다. 보호 특성의 암호화 연결 상태는 호스트에서 모의하며, 실제 보드의 SMP/암호화 협상·앱/서버 종단 시험은 아직 수행하지 않았다.
- 외부 골든 자료와 실제 사용자 데이터·서버/기기 개인키를 펌웨어에 고정하거나 저장소에 복사하지 않는다.

## 단일 펌웨어 프로젝트와 현재 빌드 경로

2026-11-05부터 등록용·키링용 소스를 `iot/`의 Zephyr 프로젝트 하나로 통합했다.

| 용도 | 저장소 기준 경로 |
| --- | --- |
| 빌드 진입점 | `iot/CMakeLists.txt`, `iot/prj.conf`, `iot/app.overlay` |
| 통합 실행·등록·서명·저장·무선 코드 | `iot/src/`, `iot/include/` |
| 팀 개발 서버 공개키 | `iot/config/dev-settings-public-key.hex` |
| 빌드 명령 | `iot/tools/build-firmware.ps1` |
| **보드에 복사할 유일한 UF2** | **`iot/build/zephyr.uf2`** |
| SHA-256·기준 커밋·소스 변경 여부·소스별 해시 | `iot/build/firmware-manifest.json` |
| 임시 컴파일 결과 / SDK | `iot/build/firmware/` / `iot/build/toolchain/` |
| 통합 호스트 검사 | `iot/tools/test-host.ps1` |
| Android 진단 앱 / 가상 키링 | `iot/tools/phone-test/` / `iot/tools/virtual-keyring/` |
| 과거 Arduino 실습 | `iot/examples/` (제품 펌웨어 빌드에 포함하지 않음) |

`build/`는 Git 제외 대상이다. 루트 `build/`, `iot/xiao-keyring/`, `iot/xiao-registration/`은 더 이상 빌드 경로로 사용하지 않는다. 빌드 스크립트는 성공한 UF2만 고정 경로로 이동하며, 실패하면 직전 UF2를 유지한다. 실패 후 남아 있는 파일을 새 빌드로 판단하지 말고 종료 코드와 manifest를 확인한다. ELF·맵·캐시 등은 재빌드·분석에 필요한 자동 생성 파일이며 보드에 복사하지 않는다.

기본 빌드는 팀 서버 키 `dev-ed25519-799e2999c648fda3`를 포함한다. 공식 Zephyr 4.2.0 및 SDK 0.17.2를 사용한다. 실행 파일 경로는 각 개발 PC의 설치 위치를 지정한다.

```powershell
$env:PYTHONPATH = (Resolve-Path iot/build/toolchain/site).Path
./iot/tools/build-firmware.ps1 -Python <python.exe> -CMake <cmake.exe> -Ninja <ninja.exe>
./iot/tools/test-host.ps1 -Zig <zig.exe> -Python <python.exe> -Javac <javac.exe> -Java <java.exe>
```

SDK가 다른 경로에 있으면 `-ZephyrBase`와 `-ZephyrSdk`로 지정한다. 소스 통합으로 BLE 계약·기기 키 생성/복원·NVS 저장 영역은 바꾸지 않았다. 이번 빌드 검사는 실제 보드 탑재나 앱 종단 시험을 대신하지 않는다.

## 단일 펌웨어 프로젝트와 현재 빌드 경로

2026-11-05부터 등록용·키링용 소스를 `iot/`의 Zephyr 프로젝트 하나로 통합했다.

| 용도 | 저장소 기준 경로 |
| --- | --- |
| 빌드 진입점 | `iot/CMakeLists.txt`, `iot/prj.conf`, `iot/app.overlay` |
| 통합 실행·등록·서명·저장·무선 코드 | `iot/src/`, `iot/include/` |
| 팀 개발 서버 공개키 | `iot/config/dev-settings-public-key.hex` |
| 빌드 명령 | `iot/tools/build-firmware.ps1` |
| **보드에 복사할 유일한 UF2** | **`iot/build/zephyr.uf2`** |
| SHA-256·기준 커밋·소스 변경 여부·소스별 해시 | `iot/build/firmware-manifest.json` |
| 임시 컴파일 결과 / SDK | `iot/build/firmware/` / `iot/build/toolchain/` |
| 통합 호스트 검사 | `iot/tools/test-host.ps1` |
| Android 진단 앱 / 가상 키링 | `iot/tools/phone-test/` / `iot/tools/virtual-keyring/` |
| 과거 Arduino 실습 | `iot/examples/` (제품 펌웨어 빌드에 포함하지 않음) |

`build/`는 Git 제외 대상이다. 루트 `build/`, `iot/xiao-keyring/`, `iot/xiao-registration/`은 더 이상 빌드 경로로 사용하지 않는다. 빌드 스크립트는 성공한 UF2만 고정 경로로 이동하며, 실패하면 직전 UF2를 유지한다. 실패 후 남아 있는 파일을 새 빌드로 판단하지 말고 종료 코드와 manifest를 확인한다. ELF·맵·캐시 등은 재빌드·분석에 필요한 자동 생성 파일이며 보드에 복사하지 않는다.

기본 빌드는 팀 서버 키 `dev-ed25519-799e2999c648fda3`를 포함한다. 공식 Zephyr 4.2.0 및 SDK 0.17.2를 사용한다. 실행 파일 경로는 각 개발 PC의 설치 위치를 지정한다.

```powershell
$env:PYTHONPATH = (Resolve-Path iot/build/toolchain/site).Path
./iot/tools/build-firmware.ps1 -Python <python.exe> -CMake <cmake.exe> -Ninja <ninja.exe>
./iot/tools/test-host.ps1 -Zig <zig.exe> -Python <python.exe> -Javac <javac.exe> -Java <java.exe>
```

SDK가 다른 경로에 있으면 `-ZephyrBase`와 `-ZephyrSdk`로 지정한다. 소스 통합으로 BLE 계약·기기 키 생성/복원·NVS 저장 영역은 바꾸지 않았다. 이번 빌드 검사는 실제 보드 탑재나 앱 종단 시험을 대신하지 않는다.

## FE팀에 전달할 메시지

재준님, 순철님, FE171의 키링 등록 흐름에 설정 동기화를 연결하기 위한 개발 규격을 공유드립니다.

1. 키링 등록 후 계정이 `SYNC_PENDING`에 도달하면 키링에 `GET_SYNC_CHALLENGE`를 보내 `SYNC_CHALLENGE.deviceNonce`를 받아 주세요. 키링에서 발급한 nonce는 60초간 유효합니다.
2. `POST /dev/api/keyrings/{deviceId}/sync-runs`에 `{"deviceNonce":"<키링에서 받은 값>"}`를 보내 주세요. 기존 빈 본문 API에 이 필드를 추가하므로 BE와 함께 반영합니다.
3. 응답의 설정 필드와 `serverProofProtocolVersion`, `serverKeyId`, `deviceNonce`, `serverTime`, `serverSignature`를 유지해 `SYNC_SETTINGS`를 구성합니다. 아래 전체 요청 예시를 기준으로 NUS에서 UTF-8 JSON 한 줄 뒤에 LF를 붙이고, 최대 20바이트씩 Write with Response로 전송해 주세요. `deviceId`와 `settingsVersion`은 int64 숫자로 전달합니다.
4. `SETTINGS_RESULT`의 `requestId`, `syncRunId`, `settingsVersion`, `payloadHash`가 요청과 같고 `stored=true`이면 `POST /dev/api/keyrings/{deviceId}/sync-runs/{syncRunId}/ack`에 `settingsVersion`, `payloadHash`, 키링이 반환한 `proof`를 전달합니다.
5. BLE 재전송이 필요하면 새 challenge를 받아 서버 서명을 다시 발급받습니다. HTTP에서 같은 본문을 재시도할 때는 같은 `Idempotency-Key`를 사용합니다. 서버의 저장 ACK와 성향 적용·매칭 준비 상태는 화면에서 구분해 주세요.

## BE팀에 전달할 메시지

영빈님, `POST /dev/api/keyrings/{deviceId}/sync-runs`에 키링이 발급한 `deviceNonce`를 받아 주시고, 사용자 세션과 현재 기기 소유권을 검증한 뒤 아래 규격에 맞춰 서명해 주세요.

1. 등록된 기기에서 `serial`과 `keyId`를 조회하고, 현재 `ownerGeneration`과 설정 버전을 확인합니다. `syncRunId`, 서버 `nonce`, `payloadHash`를 확정한 뒤 아래 원문에 서명합니다.
2. 설정 응답에 `serverProofProtocolVersion`, `serverKeyId`, `deviceNonce`, `serverTime`, `serverSignature`를 추가합니다. `serverTime`은 UTC ISO 8601이고, `serverSignature`는 Ed25519 서명 64바이트를 패딩 없는 Base64url로 인코딩한 값입니다.
3. 키링에서 `STORED`를 받은 앱은 기존 `/ack` API로 저장 증명을 전달합니다. 개발 서버의 서명키를 발급한 뒤 **공개키 raw 32바이트 hex**를 IoT에 보내주시면 그 키로 UF2를 다시 빌드하겠습니다.

## 공통 설정과 서명 원문

`payloadFormat`은 `tingle-dev-settings-v1`, `payloadEncoding`은 `base64url`이다. `payloadHash`는 디코딩한 원본 1바이트의 SHA-256 소문자 hex다.

| 매칭 설정 | 원본 바이트 | `payload` | `payloadHash` |
| --- | --- | --- | --- |
| ON | `0x01` | `AQ` | `4bf5122f344554c53bde2ebb8cd2b7e3d1600ad631c385a5d7cce23c7785459a` |
| OFF | `0x00` | `AA` | `6e340b9cffb37a989ca544e6bb780a2c78901d3fb33738768511a30617afa01d` |

아래 순서의 문자열을 UTF-8로 인코딩한다. 각 줄과 마지막 줄 뒤에 LF 한 개를 넣는다. 정수는 선행 0 없는 십진수다. 마지막 값은 `serverTime`을 변환한 epoch 초다.

```text
TINGLE-KEYRING-SERVER-SETTINGS
tingle-dev-settings-proof-v1
{serverKeyId}
{serial}
{keyId}
{deviceId}
{ownerGeneration}
{syncRunId}
{settingsVersion}
{payloadFormat}
{payloadHash}
{nonce}
{deviceNonce}
{serverTimeEpochSeconds}
```

## 현재 UF2에 포함된 개발 검증용 공개키

공개키 형식은 Ed25519 raw 32바이트 소문자 hex다. 키 ID는 `dev-ed25519-`와 공개키 첫 8바이트의 소문자 hex를 연결한 값이다. 현재 기본 빌드에는 BE247에서 전달받은 팀 개발 서버 공개키를 사용한다. 서버 비밀키는 펌웨어에 포함하지 않는다.

```text
serverKeyId = dev-ed25519-799e2999c648fda3
publicKey   = 799e2999c648fda34a67ac0bce7bf353e3ead04b0e0927c3885b0bcc82ff30d9
```

아래 키는 이 문서의 과거 오프라인 검산 예시에만 해당한다. 현재 UF2가 허용하는 서버 키가 아니므로 예시 요청을 실기기로 보내면 검증에 실패한다. 실제 연동에서는 팀 서버로부터 새 서명을 받아야 한다.

```text
serverKeyId = dev-ed25519-704492dbf4e64e2b
publicKey   = 704492dbf4e64e2b3d488f0f1657d9c451c84ad67c5942b8ee50c4c809ea6c9f
```

## 서명된 전체 BLE 요청 예시

가상 기기 `XIAO-0000000000000001`로 만든 오프라인 검산 벡터다. 실기기에서는 해당 키링이 발급한 새 `deviceNonce`와 서버가 조회한 실제 등록 정보로 서명한다. 이 예시의 서명 원문 SHA-256은 `770134688d23de96289f2d398a88cf951f6c05ba1aa748562ad7c7dc0c79499d`다.

```json
{
  "protocolVersion": 1,
  "type": "SYNC_SETTINGS",
  "requestId": "fixture-01",
  "deviceId": 42,
  "settingsVersion": 7,
  "proofProtocolVersion": "tingle-device-proof-v1",
  "keyId": "ed25519-0000000000000001",
  "syncRunId": "22222222-2222-4222-8222-222222222222",
  "ownerGeneration": "33333333-3333-4333-8333-333333333333",
  "payloadEncoding": "base64url",
  "payloadFormat": "tingle-dev-settings-v1",
  "payload": "AQ",
  "payloadHash": "4bf5122f344554c53bde2ebb8cd2b7e3d1600ad631c385a5d7cce23c7785459a",
  "nonce": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
  "deviceNonce": "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE",
  "serverTime": "2026-11-04T00:00:00Z",
  "serverProofProtocolVersion": "tingle-dev-settings-proof-v1",
  "serverKeyId": "dev-ed25519-704492dbf4e64e2b",
  "serverSignature": "LaINWp6XUQAYLaUGSJUl3zBnPOt6KZ2L1hYPXcmUwx7Hs3uhRn5fU8tOtaHcaPULMmVmHUP9k9GqXzkX4SgBDw"
}
```

정상 서명으로 설정을 저장하면 `STORED`가 반환되고 ON에서는 확장 광고와 스캔이 시작된다. 서명이 변조되면 `INVALID_SERVER_PROOF`, 기기 challenge가 만료되거나 이미 사용됐으면 `SYNC_CHALLENGE_REQUIRED`를 반환한다.
