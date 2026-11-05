# 키링 개발 설정 서명 v1 · FE/BE 연동 안내

2026-11-04 · 담당 김종무(IoT) · [S15P31E106-225](https://ssafy.atlassian.net/browse/S15P31E106-225) · 검토 FE·BE

`0.2.3-dev-signed`를 기준으로 한 개발 연동 제안이다. 이번 시험 범위는 매칭 ON/OFF 설정의 서버 서명 검증, 플래시 저장과 `STORED` 응답, 확장 광고·스캔 시작과 중지다. 실제 성향 데이터의 기기 간 교환과 매칭 계산은 별도 단계에서 검증한다.

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

공개키 형식은 Ed25519 raw 32바이트 소문자 hex다. 키 ID는 `dev-ed25519-`와 공개키 첫 8바이트의 소문자 hex를 연결한 값이다. 아래 키는 현재 로컬 시험 빌드에 대응한다. 팀 개발 서버가 자체 서명키를 발급하면 서버 공개키에 맞춰 펌웨어를 다시 빌드한다.

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
