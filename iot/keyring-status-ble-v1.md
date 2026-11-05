# 키링 상태 조회 BLE 명세 v1

| 항목 | 내용 |
| --- | --- |
| 버전·변경일 | v1 초안 / 2026-11-04 · 등록 시험 펌웨어 대조 |
| 작성자 | 김종무(IoT) |
| 영향·검토 파트 | FE·IoT, 서버 저장 확인 부분은 BE |
| 관련 기능 | KEY-02, DES06·DES07·DES13 |
| 관련 Story | [S15P31E106-227](https://ssafy.atlassian.net/browse/S15P31E106-227) |
| 상태 | 시험 빌드에 상태 조회와 전압·충전 신호 응답 구현. 실기기 측정·FE 검증 필요 |

이 문서는 `0.1.0-registration`(`ec51e05`)의 `GET_STATUS`·`STATUS` 응답을 기준으로 한다. 배터리 전압·충전 신호를 읽어 전달하며 잔량 비율과 만남 기록 집계는 미확인 값을 반환한다. 시험 보드·등록 진입·서버 흐름은 [BLE 연동 계약 초안](keyring-app-ble-contract-v1.md)을 참조한다. [등록 시험 펌웨어 MR !172](https://lab.ssafy.com/s15-final/S15P31E106/-/merge_requests/172)

## 1. 조회 범위

연결 여부는 앱의 BLE 연결 이벤트로 확인한다. 배터리·저장 공간·미전송 기록 수는 연결한 본인 키링에 조회해 받는다.

```text
본인 키링 연결
    → 서비스 탐색
    → TX Notify 구독 완료
    → 앱: GET_STATUS 전송
    → 키링: STATUS 또는 ERROR 응답
    → 앱: 실제 수신값과 상태 수신 시각 표시
```

BLE 연결 성공이나 상태 조회 성공만으로 설정 동기화·사용 준비 완료를 표시하지 않는다. 기기 식별·소유 확인·인증의 세부 절차는 별도 계약에서 정한다. 서비스 UUID나 기기 이름만으로 본인 기기 인증을 완료한 것으로 처리하지 않는다.

## 2. BLE 서비스와 특성

시험 빌드의 NUS를 기준으로 한다. 앱은 Central, 키링은 Peripheral이다. 아래 RX·TX 명칭은 키링 기준이다.

| 용도 | UUID | 앱에서 사용하는 방식 |
| --- | --- | --- |
| UART 서비스 | `6E400001-B5A3-F393-E0A9-E50E24DCCA9E` | 서비스 탐색 |
| RX — 앱 → 키링 | `6E400002-B5A3-F393-E0A9-E50E24DCCA9E` | Write with response |
| TX — 키링 → 앱 | `6E400003-B5A3-F393-E0A9-E50E24DCCA9E` | Notify 구독 |
| TX CCCD | `0x2902` | 연결마다 Notify 활성화 |

앱은 TX를 구독한 뒤 RX에 `GET_STATUS`를 쓰고 TX Notify로 응답을 받는다. 상태 조회용 GATT Read는 지원하지 않는다. [시험 펌웨어 GATT 정의](https://lab.ssafy.com/s15-final/S15P31E106/-/blob/ec51e0573ae739fe038040a7c9c1778595702225/iot/xiao-registration/src/main.c)

GATT Write 성공은 BLE 전송 결과다. 상태 조회 성공은 해당 요청에 대응하는 완전한 `STATUS` 응답을 받아 확인한다.

## 3. 상태 필드

| 항목 | 필드 | 타입·값 | 의미 |
| --- | --- | --- | --- |
| 연결 상태 | `connectionState` | `UNKNOWN`, `CONNECTING`, `CONNECTED`, `DISCONNECTED` | 앱에서 관리. BLE 연결 이벤트 기준이며 기기 응답에는 넣지 않음 |
| 배터리 | `batteryPercent` | 정수 `0~100` 또는 `null` | 키링이 측정·산출한 잔량. 측정 미지원·미확인이면 `null` |
| 배터리 전압 | `batteryMillivolts` | 정수 또는 `null` | 시험 빌드의 측정 전압, 단위 mV |
| 충전 신호 | `charging` | boolean 또는 `null` | 시험 빌드의 충전 신호. `true`는 충전 감지, `false`는 충전 미감지, `null`은 미확인 |
| 기록 저장 용량 | `storageTotalBytes` | 양의 정수 또는 `null` | 만남 기록 저장 영역의 사용 가능한 전체 용량. 단위 byte |
| 기록 사용 공간 | `storageUsedBytes` | 0 이상 정수 또는 `null` | 같은 저장 영역에서 현재 사용 중인 공간. 단위 byte |
| 저장 상태 | `storageState` | `AVAILABLE`, `FULL`, `ERROR`, `UNKNOWN` | 새 만남 기록 저장 가능 / 공간 부족 / 저장소 오류 / 미확인 |
| 미전송 기록 수 | `pendingRecordCount` | 0 이상 정수 또는 `null` | 키링이 서버 저장 완료를 아직 확인하지 못한 확정 만남 기록 수 |
| 상태 수신 시각 | `statusReceivedAt` | UTC 시각 또는 `null` | 앱에서 관리. 유효한 상태 응답을 받은 시각이며 설정 동기화 완료 시각과는 별개 |

현재 시험 응답에는 기기 측 일곱 필드를 포함한다. 확인할 수 없는 값은 `null`, 미확인 기록 저장 상태는 `UNKNOWN`으로 전달한다. `connectionState`·`statusReceivedAt`은 앱에서 관리한다. 설정 저장소 상태와 버전은 `DEVICE_INFO`에서 확인한다.

### 배터리·저장 공간

- 배터리 잔량 산출 방식은 미정이며 시험 빌드의 `batteryPercent`는 항상 `null`이다. `batteryMillivolts`만으로 잔량을 환산하거나 `charging=false`를 완충으로 해석하지 않는다.
- 저장 용량은 보드 전체 Flash 용량을 그대로 사용하지 않고 실제 만남 기록 저장 영역을 기준으로 한다.
- 전체 용량과 사용 공간은 같은 시점·같은 영역을 기준으로 조회한다. 두 값이 확인됐다면 `0 ≤ storageUsedBytes ≤ storageTotalBytes`여야 한다.
- `FULL`은 새 만남 한 건을 안전하게 저장할 공간이 부족한 상태다. 예약 공간 등이 필요하므로 단순히 사용률이 100%인지로 판단하지 않는다.
- `ERROR`는 저장소 조회·접근 오류다. 확인할 수 없는 용량 값은 `null`로 전달한다.

### 미전송 기록 수

- 앱이 기록을 읽거나 서버 전송을 시도한 것만으로 건수를 줄이지 않는다.
- 서버 저장 완료 확인이 해당 키링에 반영되면 집계에서 제외한다. 신뢰할 시각으로 만료를 확인해 실제 삭제한 기록도 집계에서 제외한다.
- 서버 저장 확인 후 원본 삭제가 실패하면 미전송 수와 사용 공간이 서로 다를 수 있다. `0건`만으로 공간 확보 완료를 판단하지 않는다.
- 후보·미확정 만남, 서버에서 관리하는 상대 기록 대기·제안·채팅 수는 이 건수에 포함하지 않는다.
- 재시작 후 저장소 복구·집계를 확인하기 전에는 `null`을 반환한다. 재시작했다는 이유로 `0`으로 초기화하지 않는다.

## 4. 요청·응답

### 상태 조회 요청

앱은 요청마다 새로운 `requestId`를 만든다. `protocolVersion`은 정수 `1`, `type`은 `GET_STATUS`, `requestId`는 UTF-8 1~36바이트 문자열이다. 키링은 응답에 같은 `requestId`를 반환한다.

```json
{
  "protocolVersion": 1,
  "type": "GET_STATUS",
  "requestId": "s-001"
}
```

### 시험 빌드 정상 응답 예시

전압과 충전 신호를 읽은 경우의 응답 형태다. `4020`은 설명용 값이다.

```json
{
  "protocolVersion": 1,
  "type": "STATUS",
  "requestId": "s-001",
  "batteryPercent": null,
  "batteryMillivolts": 4020,
  "charging": true,
  "storageTotalBytes": null,
  "storageUsedBytes": null,
  "storageState": "UNKNOWN",
  "pendingRecordCount": null
}
```

### 측정·집계 미확인 응답

상태 조회 메시지를 지원하지만 측정·저장 기능이 아직 준비되지 않은 항목은 다음처럼 반환한다. 일부 항목만 확인됐다면 그 항목만 실제 값을 넣는다.

```json
{
  "protocolVersion": 1,
  "type": "STATUS",
  "requestId": "s-001",
  "batteryPercent": null,
  "batteryMillivolts": null,
  "charging": null,
  "storageTotalBytes": null,
  "storageUsedBytes": null,
  "storageState": "UNKNOWN",
  "pendingRecordCount": null
}
```

`GET_STATUS`를 지원하지 않는 펌웨어에서 응답이 없으면 앱은 조회 실패·미확인으로 처리한다.

### 오류 응답

조회 자체가 실패하면 다음 형식을 사용한다. 사용자 안내 문구는 앱에서 관리한다.

```json
{
  "protocolVersion": 1,
  "type": "ERROR",
  "requestId": "s-001",
  "code": "UNSUPPORTED_PROTOCOL"
}
```

| 오류 코드 | 의미 |
| --- | --- |
| `INVALID_MESSAGE` | 요청 형식 오류. 요청 ID를 해석하지 못하면 `requestId=null` |
| `UNSUPPORTED_PROTOCOL` | 지원하지 않는 프로토콜 버전 |
| `UNSUPPORTED_TYPE` | 지원하지 않는 메시지 종류 |

위 코드는 현재 시험 빌드의 값이다. 개별 측정 실패는 정상 `STATUS` 안의 `null` 또는 `UNKNOWN`으로 표시한다.

## 5. 메시지 경계·실패 처리

- UTF-8 JSON 한 줄 뒤에 LF(`\n`)를 붙인다. 전송 시 보기용 들여쓰기·줄바꿈은 제거한다.
- 한 번에 최대 20바이트씩 순서대로 전송한다. 수신 측은 LF까지 바이트를 합친 뒤 UTF-8 JSON을 해석한다. Notify 한 번을 JSON 하나로 가정하지 않는다.
- 메시지는 종료 LF를 포함해 최대 512바이트, 동시 상태 조회는 한 건으로 제한한다. 메시지 사이에 다른 메시지의 조각을 섞지 않는다.
- 앱 → 키링의 분할 Write는 이전 Write 결과를 확인한 뒤 다음 조각을 전송한다.
- 요청 전송 완료 후 10초 동안 완전한 응답이 없으면 조회 실패로 처리한다. 재시도 시 새 `requestId`를 사용한다.
- 현재 연결·현재 요청에 대응하는 응답만 반영한다. 지원하지 않는 버전이나 이전 요청의 늦은 응답으로 현재 값을 갱신하지 않는다.
- 연결이 끊기면 수신 중이던 조각을 버리고 재연결 후 다시 조회한다. 이때 버리는 것은 통신 수신 조각이며 키링에 저장된 만남 기록이 아니다.

512바이트와 10초는 상태 조회용 앱 기준 초안이다. 시험 펌웨어 공통 수신 프레임은 최대 4,096바이트(LF 포함)이며, 펌웨어 내부의 10초는 Notify 버퍼 부족 재시도 예산이다. 다른 기능의 한도와 시간은 [BLE 계약 §7](keyring-app-ble-contract-v1.md#7-공통-ble-메시지전송오류)을 참조한다.

## 6. 앱 표시 기준

| 상황 | 표시 기준 |
| --- | --- |
| 최초 조회 전 | 배터리·저장 공간·미전송 기록 수는 미확인 |
| 유효한 실제 값 수신 | 받은 값을 표시. 실제 수신한 `0%`·`0건`은 유효한 값 |
| `null`·필드 누락·범위 오류 | 해당 항목을 미확인으로 표시. 임의로 `0`으로 보정하지 않음 |
| 미연결·조회 실패 | 과거 값을 현재 값으로 단정하지 않음. 보존한다면 마지막 확인 값·시각을 함께 표시 |
| 저장 공간 부족 확인 | 공간 부족으로 새 매칭이 대기 중임과 연결·동기화 안내 표시 |
| BLE 연결·상태 조회 성공 | 설정 저장·동기화 완료나 새 매칭 준비 완료로 표시하지 않음 |

`statusReceivedAt`은 응답 수신 시각이며 모든 항목의 측정 성공 시각을 뜻하지 않는다. `null`인 항목의 마지막 유효 값·확인 시각을 새로 갱신하지 않는다. 설정 동기화 성공 시각과도 구분한다.

서버의 현재 등록 상태는 휴대폰과 키링의 실시간 BLE 연결 여부를 뜻하지 않는다. 상대 기록 대기·제안 수는 서버에서 확인하는 별도 값이다.

## 7. 적용·호환성과 남은 확인

적용 순서는 FE·IoT의 파서·표시 대조 → 시험 보드의 실제 응답·충전 신호·재연결 확인 → 앱 표시 연결이다. 가상 응답과 실물 측정 결과는 구분해 기록한다. 서버 저장 확인과 미전송 집계 기준은 BE와 함께 확인한다. 필드 의미나 타입을 호환되지 않게 바꾸면 버전과 관련 파트의 적용 순서를 맞춘다.

남은 항목은 다음과 같다.

- 배터리 전압·충전 신호의 실물 검증과 잔량 산출 방식.
- 실제 기록 저장 영역 크기·용량 집계·공간 부족 판정.
- 서버 저장 확인 반영·원본 삭제 실패·만료 정리에 따른 미전송 집계.
- 본인 기기 식별·인증 절차.
- 메시지 길이·타임아웃·분할 송수신·조회 주기의 실기기 확인.
- FE·IoT·BE 검토 기록.

지원 펌웨어 적용과 실제 응답 확인 전에는 앱에서 임의 값을 생성하지 않고 미확인 상태를 유지한다.
