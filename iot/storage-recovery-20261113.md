# 저장 오류 복구 — 2026-11-13

## 0.9.10 후속: 만남 기록 격리·QSPI 보호·실물 앱 무반응 원인

2026-11-13, 기준 develop `8c9ea9ed4f4c24129890850c931f0ef62d6d9b05` + 미커밋 수정. 아래가 최신이며 뒤의 0.9.9 기록은 이전 보드 시험 이력이다. FE/BE 제품 소스·앱 내구 기록·서버 DB는 변경하지 않았다.

### 결론과 원인 구분

| 현상 | 확인된 직접 원인 | 아직 확정하지 못한 범위 |
| --- | --- | --- |
| 이전 보드에서 빨간불·BLE 시작 실패 | 설정 CRC 오류를 전체 부팅 실패로 처리. 0.9.9에서 설정 백업·서명 복구 보강 | 최초 Flash 손상 순간의 전원·버스 파형 없음 |
| 이번 #8의 설정 적용 실패 | keyring-store ID 100 한 건 CRC 오류 → engine.storage_error → APPLIED 차단 | 해당 만남 원본의 올바른 내용은 복원할 수 없음 |
| 0.9.10 교체 후 동기화 버튼 무반응 | 앱의 같은 기기 초기화 저널이 PENDING_DEVICE → KeyringForegroundSync가 sync.pause() → useExisting이 active=false에서 즉시 반환 | 서버 reset-run의 실제 현재 상태는 아직 직접 조회하지 않음 |
| 권한 API HTTP 422 | 실물 앱에서 /keyrings/8/owner-session-grants가 422를 반복 반환 | 응답 본문 미수집. BE의 pending-reset 차단 분기와 일치하지만 다른 POLICY_UNRESOLVED 원인도 있음 |
| 재부팅 후 저장 버전·해시가 같으면 동기화 생략 | FE가 DEVICE_INFO의 현재 applied/timeReady/recovery를 판단하지 않는 분기 | 이번 무반응은 이 분기보다 먼저 초기화 저널에서 막힘 |
| 만남 완료 뒤 반복 Flash 쓰기 | 잃어버린 FINAL 응답 재전송마다 COMPLETE→FINAL→COMPLETE를 영속 저장 | 이 쓰기 증가 자체가 CRC 오류의 유일한 원인이라는 증거는 아님 |

**서로 다른 장애다.** 앱의 초기화 대기 기록이 Flash CRC를 만들었다는 증거는 없다. 펌웨어만 바꾸면 앱의 초기화 대기 상태도 없어지는 구조가 아니다.

### Flash 손상 방지 근거와 변경

Nordic의 [nRF52840 anomaly 244](https://docs.nordicsemi.com/r/bundle/errata_nrf52840_rev3/page/err/nrf52840/rev3/latest/anomaly_840_244.html)는 HFINT/HFXO 전환 중 QSPI 전송 손상을 설명한다. 사용 중인 Zephyr 4.2.0 nRF52 QSPI 경로는 BLE가 사용하는 HFXO의 공유 참조를 보유하지 않았다. 이 조건은 소스에서 확인했으나, 과거 손상이 이 현상 때문이었다고 실측 확정하지는 않았다.

- 모든 제품 NVS read/write/erase와 USB 파티션 해시 읽기에 공유 HFXO 참조를 확보한다. 클록 시작 완료 후 QSPI를 접근하고 종료 후 참조를 반환한다. BLE 소유 클록 레지스터를 직접 켜고 끄지 않는다.
- 접근은 mutex로 직렬화한다. 클록 확보가 실패하거나 시간 초과면 Flash 작업을 실행하지 않는다.
- 기존 256바이트 페이지 쓰기·즉시 재읽기 검증을 유지한다. erase도 전체 0xFF 재읽기로 확인한다. 오류를 무시하거나 무제한 재시도로 마모를 늘리지 않는다.
- 동일 레코드는 재저장하지 않는다. 완료 후 DONE/COMMIT에 대한 무선 재응답은 RAM 플래그로 처리한다. 최초 확정 기록·반복 억제는 기존 영속 저장을 유지한다.
- 테스트에서 완료 원본 및 서버 접수 후 억제 기록 각각에 1,000회 재전송을 가해 **추가 저장·중복 알림 없이 응답**하는 것을 확인했다.

### 이미 손상된 만남 원본의 보존

[Zephyr NVS](https://docs.zephyrproject.org/latest/services/storage/nvs/nvs.html)는 전체 읽기 후 CRC를 검사한다. 실패한 읽기도 목적지에 잘못된 바이트를 남길 수 있으므로, 격리 슬롯의 RAM은 비우고 원본은 NVS에 그대로 둔다.

- 개별 ID 100~131의 CRC/길이/스키마 실패는 슬롯별 격리한다. 이후 정상 슬롯도 계속 읽는다. 원본·독립 증명 ID 300~331은 자동 삭제/덮어쓰기하지 않는다.
- 격리 슬롯은 빈 슬롯으로 재사용하지 않는다. 미전송 원본 수와 공간 제약에 포함한다. 오래된 phase로 되돌려 재매칭·재알림하지 않는다.
- 독립 증명이 CRC와 서버/기기 서명, 기기 식별, 현재 소유 generation/epoch, sequence를 통과하면 해당 상대 기기·계정의 반복 억제에만 사용한다. 정상 만남 증명으로 수리하거나 서버로 허위 제출하지 않는다.
- 상대를 안전하게 확인할 수 없다면 새 매칭은 보류한다. 정상 설정 저장·적용과 정상 기록 조회는 별개로 유지한다. 소유 키·초기화 상태·sequence·cursor·반복 억제 자체의 손상은 전체 안전 차단을 유지한다.
- 손상 기록 한 건은 여전히 손상된 원본이다. 그 원본의 성공 복원·서버 접수까지 완료했다고 표시하지 않는다. 정책에 맞는 서명된 정리/복구 계약은 후속 협의 대상이다.

### FE 계약 추가와 호환성

NUS JSON·UUID·기기 키·서명 원문·파티션·영속 구조체는 유지한다. 신규 필드는 추가형이다.

```json
{
  "type": "STATUS",
  "storageState": "AVAILABLE",
  "pendingRecordCount": 1,
  "reviewRecordCount": 0,
  "applied": true,
  "timeReady": true,
  "matchingReady": true,
  "quarantinedRecordCount": 1,
  "recordsRecoveryRequired": true,
  "recordRecoveryBlocksMatching": false
}
```

위는 상태 예시이며 실물 앱 종단 통과 결과가 아니다. 실제 값은 현재 설정과 소유 인증 상태에 따라 달라진다.

- DEVICE_INFO에도 위 격리 관련 3개 필드를 추가하고, 격리 기록이 있으면 storageHealthy=false다. 설정의 settingsRecoveryRequired와 만남 기록의 recordsRecoveryRequired를 구분한다.
- 인증된 STATUS의 `storageState`는 기존 기준에 따라 `AVAILABLE` 또는 `FULL`을 반환한다. `pendingRecordCount`와 `reviewRecordCount`는 읽을 수 있는 기록의 집계다. 격리 기록은 `quarantinedRecordCount`로 별도 전달한다.
- 정상 기록 조회 후 RECORD_END는 유지하고 quarantinedRecordCount/recordsRecoveryRequired를 함께 반환한다. **읽을 수 있는 목록의 끝**이며 모든 원본의 삭제/접수 완료 증명이 아니다. 기존 앱이 오류 하나 때문에 수집한 정상 헤더까지 버리지 않도록 새로운 ERROR로 바꾸지 않았다.
- 최신 FE는 자동 기록 순회와 접수증 없는 기록 건너뛰기를 이미 구현했다. 예전의 “수동 1건만 전송” 지적을 다시 적용하지 않는다.
- 기존 앱은 같은 `storageState` 값으로 정상 기록 수를 표시할 수 있다. 격리 원본이 남아 있는지 안내하려면 FE에서 `recordsRecoveryRequired`와 `quarantinedRecordCount`를 표시한다.
- 동일 version/hash라도 TSV3 applied!=true, timeReady!=true, settingsRecoveryRequired=true이면 새 challenge와 서버 서명으로 재적용한다. matchingReady=false는 OFF 설정에서도 정상일 수 있어 재적용 조건과 혼동하지 않는다.
- GET_SETTINGS_APPLIED_PROOF 실패 후 원인을 구분한다. 재부팅/미적용이면 새 challenge 동기화, 저장 장애면 진단 안내, 응답 유실이면 기존 증명/ACK 멱등 재시도를 유지한다.

### 실물 휴대폰에서 찾은 별도 차단 — FE·BE 우선 전달

시험 기기 #8 `XIAO-5be68c1703284704`. 휴대폰 SM-S931N, 개발 앱 `com.ssafy.tingle.dev` 설치 갱신 2026-11-13 14:07:40 KST. APK 정확한 빌드 commit은 확인하지 못했다.

개인키·토큰을 읽지 않고 앱의 해당 키링 복구 상태 필드만 확인했다.

- 설정 저널: v15, TSV3, ownershipEpoch=0, complete=true, bleAttempted=true.
- 초기화 저널: resetRunId=`f28315bb-6c99-4862-8a4c-57fcb409627e`, state=PENDING_DEVICE, commandAttempted=true, fromOwnershipEpoch=0.
- 시작: 2026-11-13 14:59:19 KST. 명령 만료: 15:00:19 KST.
- **명령 만료가 초기화 작업 취소를 뜻하지 않는다.** 현재 FE는 COMPLETED가 아닌 저널이 하나라도 있으면 자동 동기화를 중지한다. BE KeyringResetGuard도 completed_at IS NULL인 실행을 막으며 만료 시각으로 해제하지 않는다.
- 실물 앱 HTTP 로그: 15:46:47, 15:46:52, 15:46:58 KST에 owner-session-grants가 422. 수집 구간에 sync-runs 발급 요청은 없다.
- 현재 화면은 최신 설정 확인·동기화 버튼이 활성화돼 있으나 진행/차단 이유가 없다. useExisting은 active=false이면 상태 표시 없이 반환한다. 이것이 현재 무반응의 코드 경로다.
- 저장 오류 진단과 달리, 위 문제는 FE의 복구 UX와 BE 초기화 실행 수명 관리가 함께 관련된다.

**FE에서 할 일**
1. KeyringForegroundSync의 차단 이유를 화면에 전달한다. 초기화 진행 중, 저널 읽기 오류, 앱 비활성, 권한 미준비를 구분한다.
2. 초기화 대기 중 동기화 버튼을 무반응 상태로 두지 않는다. 실행 조회/안내를 제공한다. 단, 조회 버튼이 RESET_DEVICE를 자동 재전송하면 안 된다.
3. 서버 GET reset-runs로 상태만 재조회하고, 확인된 완료·향후 합의할 취소 결과로 저널을 갱신한다. 파일 삭제·앱 데이터 삭제로 안전장치를 우회하지 않는다.
4. 다중 키링 요구에 맞춰 다른 기기의 초기화가 무관한 모든 기기의 동기화까지 차단하는 현재 계정 단위 any 조건도 점검한다. 진행 중 초기화 대상만 막는 계약으로 BE와 맞춘다.
5. 앞 절의 상태 파싱과 재부팅 후 재적용 생략을 수정한다. 재현 테스트 reviewRebootWithSameStoredHashMustReapplyTsv3는 두 번째 SYNC 전송 기대 2, 실제 1이었다. 현재 develop의 KeyringSyncViewModel은 재현본과 해시가 동일하다.

**BE에서 할 일**
1. 위 resetRunId를 GET/DB 읽기 전용으로 대조한다. 실행 상태, completed_at, 현재 소유 generation/epoch만 공유하고 개인키/로그인 토큰은 전달하지 않는다.
2. owner-session-grants 422의 실제 errorCode/서버 차단 분기를 대조한다. pending reset 차단이면 일반 POLICY_UNRESOLVED 대신 초기화 대기임을 식별 가능한 계약으로 제공한다.
3. 기존 데이터 보존 요구이므로 초기화 실행 완료를 위해 강제로 기기를 지우면 안 된다. 현재 코드는 초기화 취소 API/상태가 없다. 미적용 초기화를 안전하게 취소하는 계약이 필요하다.
4. 취소는 DB 행 삭제·completed_at 위조로 처리하지 않는다. 이미 기기가 삭제했거나 응답만 유실된 경우를 먼저 구분하고, 아직 실행하지 않은 경우에도 발급된 옛 명령의 재실행을 막는 기기 증명/무효화 절차를 정해야 한다. 취소·완료·미확인을 서로 다른 상태로 두고 멱등 조회를 제공한다.
5. 서버에서 정상 완료가 확인된다면 기존 완료 결과를 FE에 재조회시켜 복구한다. 미완료이고 사용자가 데이터를 보존하려는 경우에는 계약 없는 임의 완료/소유 해제를 하지 않는다.

이 작업에서는 서버 접근용 기존 PEM 경로가 존재하지 않아 서버 DB/실제 reset GET을 직접 조회하지 못했다. 앱 로그의 HTTP 상태와 로컬 저널은 직접 확인했지만 서버 분기는 아직 추정이다.

### 최상위 유저플로우의 안전한 상태 설계

| 단계 | 다음 단계로 넘어가는 조건 | 오류/유실 때 보존할 것 |
| --- | --- | --- |
| 연결·기기 식별 | serial/keyId·보호 연결·현재 owner epoch 확인 | 기존 키·소유 정보. 검색/연결 실패와 저장 손상을 구분 |
| 초기화 상태 조정 | 현재 대상의 초기화가 없음 또는 서버/기기가 합의한 종료 상태 | 진행 중 reset-run/기기 결과. 무응답으로 막지 않고 사유 표시 |
| 설정 수신 | fresh deviceNonce에 대한 서버 서명·기기/owner/epoch·버전 검증 | 기존 정상 설정. 부분 프레임은 폐기하고 새 challenge |
| 설정 저장 | 페이지 검증 + NVS 전체 CRC/내용 재읽기 + 정상 백업 | 검증된 정상본, 손상 원본. 실패 시 STORED 발급 금지 |
| 설정 적용 | 같은 syncRunId의 현재 적용 확인 | 저장 상태와 RAM 적용 상태를 구분. 재부팅 후 fresh 시간 재적용 |
| 확장 광고·수신·매칭 | 유효 설정/시간/정책·서명 peer·반복 억제·공간 확인 | 세 큐·미접수 기록. 격리 상대 재매칭 금지, 임의 체류 시간 없음 |
| 만남 앱 업로드 | 정상 원본·증명만 순회, 서버 접수증 검증 | 서버 접수 전 원본 삭제 금지, 격리는 별도 표시 |
| 제안·채팅 | BE의 양측 기록 검증·정책·상호 수락 | 키링의 APPLIED/LED와 서버 제안 완료를 혼동하지 않음 |
| 초기화·양도 | 서명 명령→영속 삭제 완료 증명→서버 소유 해제 | 고정 기기 키 보존, 동일 실행 재조회, 옛 epoch 거절 |

“오류가 전혀 없는 완벽한 상태”는 현재 완료 판정이 아니다. 외부 Flash 물리 결함/전원 차단까지 소프트웨어만으로 없앨 수 없고, FE의 대기 초기화 복구·BE 취소 계약·다중 실물 부하 검증이 남아 있다. 손상 한 건을 정상 성공으로 숨기는 것은 해결로 취급하지 않는다.

### 0.9.10 검사·탑재 증거

- 전체 C/Python/Java/Node 호스트 검사 통과: record-recovery-host-final-20261113.log. 실제 BE의 서명 검증기와 STORED/등록/초기화 상호 검산도 통과.
- 신규 검사: 손상 읽기가 이미 버퍼를 덮은 경우, 뒤 정상 슬롯 복원, 격리 슬롯 재사용 금지, 원본 무변경, 소유/서명 불일치 차단, 정상 기록 조회, 적용과 매칭 준비 분리, 완료 재전송 2,000회 추가 쓰기 없음.
- UF2: `iot/build/zephyr.uf2`, `0.9.10-d31`, SHA-256 `e1ddfec5b5b760d2a1ea25d44a667e0a670b96824224f64d48259d38f52144e9`, 838144바이트. sourceDirty=true이며 위 develop commit 자체의 순정 빌드가 아니다.
- 빌드 FLASH 419028바이트, 정적 RAM 256516/262144바이트. 실제 동시 부하 RAM 여유·BLE 누락 측정 결과가 아니다.
- 보드 #8에 앱 영역만 전송. DIAG 0.9.10-d31, radio-ready error=0, 기존 serial/keyId/SPKI 동일, 설정 v15 정상. keyring ID100은 격리 1건, 전역 recordStorageError=0, quarantineUnresolved=0.
- 교체 전후 registration SHA-256 `edda176eae23442d04ae3967dd1a4229a834069e034a60df8728f2b4e91ef03d`, keyring SHA-256 `84abd380f5c23ecdab740b900fceae386d517aee653d56c8f821ddc35b14b4d7`가 동일하다. 원본 삭제/키 재생성 없음.
- 실물 앱은 BLE 연결·서비스 검색·암호화 성공까지 확인했다. **새 SYNC_SETTINGS→APPLIED→서버 ACK는 초기화 대기 때문에 미완료**다. 이를 성공으로 보고하지 않는다.
- 별도 BE Gradle 통합 검사 실행은 로컬 JDK 21이 없어 시작 단계에서 실패했다. BE 테스트 실패/서버 고장으로 해석하지 않는다. 개발 DB에 쓰기 작업은 하지 않았다.
- 원래 손상의 재현 확정, USB/배터리 반복 동기화, 재부팅 재적용, 여러 실물의 광고·스캔·앱 전송 병행, 실제 만남 접수·제안·채팅은 후속 수용 검사다.

## 이전 0.9.9 복구 기록

대상: SIS D3.1 Y3 / 0.9.9-d31. 기준 develop `1b77454d`. 관련 작업은 IOT S15P31E106-322의 후속이며 해당 Story의 완료 상태는 실물 복구 완료 근거가 아니다. FE/BE 소스와 서버 데이터는 이번 변경에서 수정하지 않는다.

2026-11-13 후속 확인: 작업본을 최신 develop `f57c61670c98d84232e5ae3a473f49db3d6854db`까지 fast-forward 했다. 해당 구간에는 IoT/명세 변경 없이 FE 변경만 있었고, 기존 UF2 manifest의 모든 펌웨어 입력 해시와 현재 소스가 같다. 이미 실물에서 확인한 UF2와 그 빌드 기준 commit `1b77454d` 기록은 그대로 유지한다. Git 기준만 바뀌었다고 새 바이너리를 탑재했다고 표시하지 않는다.

## develop 백업·복구 로직과의 관계

develop에 반영된 `afb12d3f`의 정상 설정 백업·서명 복구 코드를 기반으로 보강했다. 이 기능을 별도 구형 구현으로 대체하지 않는다.

| develop에서 유지한 동작 | 0.9.9에서 보강한 동작 |
| --- | --- |
| 새 설정 저장 전에 직전 정상본을 ID 11에 저장하고 재검증 | 첫 동기화와 서명 소유 참조 복구에도 검증된 새 설정을 먼저 백업 |
| 최신 설정 손상 시 정상 이력·ID 11을 소유 참조로 유지 | 정상본이 전혀 없으면 TSV3 내부 서명으로 소유 참조만 검증. 검증 불가 시 진단 상태 유지 |
| 오류 설정을 자동 적용하지 않고 새 서버 서명 설정을 검증·저장 | 최신 서명 소유 참조는 같은 version/run/nonce/hash/format도 fresh deviceNonce 서버 서명으로 복구. 이전 정상 이력 복구는 상위 버전 요구 유지 |
| 저장 후 전체 읽기/CRC/내용 검증에 성공해야 STORED 발급 | 각 Flash 페이지 재읽기와 별도 커밋 슬롯 ID 12 추가. 검증 실패를 성공으로 처리하지 않음 |
| 초기화로 삭제한 설정·이전 소유자의 데이터를 되살리지 않음 | RESETTING 상태에서 ID 12도 삭제·검증한 뒤 기존 초기화 완료 흐름 진행 |

확인 경로: `iot/src/settings_storage.c`, `iot/src/board.c`, `iot/src/proof.c`, `iot/src/reset.c`. 신규 페이지 검증은 `iot/src/flash_guard.c`다. 최신 develop 대조로 누락된 펌웨어 코드는 발견되지 않았으므로 이번 후속 확인에서 기능 코드를 중복 추가하거나 UF2를 불필요하게 재교체하지 않았다.

## 쉽게 설명하면

앱에서 받은 설정을 키링이 저장한 뒤 확인할 때, 내용과 확인값(CRC)이 맞지 않는다. 기존 펌웨어는 정상 예비본마저 없으면 블루투스 시작 전에 종료했다. 따라서 앱의 `ConnectFailed`는 현재 보드에서 관찰된 결과이고, 앱이 설정을 깨뜨렸다는 증거가 아니다.

새 펌웨어는 키·소유 확인과 설정 적용을 분리한다. 확인 가능한 소유 정보로 앱 연결을 복구하되, 오류 설정으로 매칭하지 않는다. 서버가 새 서명 설정을 발급하고 앱이 전달하면 검증·저장·적용을 다시 진행한다. 업데이트만으로 성향 데이터가 자동 수리됐다고 표시하지 않는다.

## 확인된 사실과 미확인 원인

| 항목 | 판정 | 근거/한계 |
| --- | --- | --- |
| 현재 XIAO-982253fb17614b66의 설정 v19 CRC 불일치 | 확인 | 원본 2324바이트, 저장 CRC `a1029fea`, 계산 CRC `e5c71489`, payload 487바이트, 내용 검사는 통과 |
| 해당 보드의 기기 키·초기화 기록·만남 저장소 | 검사 통과 | 기기 키/하드웨어 일치. 키 재생성·서버 재등록할 근거 없음 |
| 정상 설정 이력·ID 11이 없을 때 BLE 이전 종료 | 확인된 IoT 결함 | `kr_settings_storage_load` 실패 → main 종료. 현재 `ConnectFailed`의 직접 경로 |
| 첫 동기화에 예비본이 없음 | 확인된 IoT 취약점 | 기존 저장 함수는 이전 설정이 있을 때만 ID 11 작성 |
| 처음 기록할 때부터 손상됨 | 미확정 | 재부팅 뒤 두 번 같은 값이 읽혔다고 발생 시점을 알 수 없음. 정상 기록 후 변형·고정된 읽기 문제도 배제 못 함 |
| 큰 쓰기의 미사용 영역 누락·Flash 절전 타이밍 | 가설 | 미사용 영역 통계와 쓰기 시점 로그로 확인해야 함. 전원/칩/드라이버 중 하나로 단정하지 않음 |
| 당시 앱이 STORAGE_ERROR를 받음 | 미확인 | 당시 NUS 응답/서버 ACK 로그 필요. 진단 파일 이름은 근거가 아님 |
| 진단 때 USB/전압 정상 → 저장 순간도 정상 | 성립하지 않음 | 진단 시점과 고장 발생 시점이 다름 |
| 초기화 ID 20 기록 3개 → 사용자 초기화 3번 | 성립하지 않음 | 초기화 저널의 단계 전환도 별도 기록을 남김 |
| FE가 재부팅 후 같은 버전/해시이면 재적용 생략 | 별도로 재현 | 검토용 `KeyringSyncTest.reviewRebootWithSameStoredHashMustReapplyTsv3`: 두 번째 전송 기대 2, 실제 1. 현재 CRC의 발생 원인으로 확정하지 않음 |
| BE가 Flash CRC를 잘못 생성 | 근거 없음 | CRC는 펌웨어/NVS가 생성. 실제 개발 서버의 해당 실행/ACK 상태는 BE 로그 대조 필요 |

기존 11/12~13 진단에서는 XIAO-5be6…의 v9는 정상 v7/v8로 격리 복구 가능했고, XIAO-68b6…의 유일한 v14와 현재 보드의 유일한 v19는 정상 예비본이 없어 부팅이 차단됐다. 후자의 두 설정은 내용 검사만 통과했으며, 이것만으로 서버 서명이 유효하다고 보지 않는다.

## 펌웨어 변경과 보존 범위

1. 정상 이력/백업은 이전처럼 소유 참조로만 유지한다. 오류 레코드를 지우지 않는다.
2. 정상본이 전혀 없을 때만 최신 CRC 실패 레코드의 TSV2/TSV3 서버 서명을 별도로 검사한다. 하드웨어 serial, 현재 기기 공개키, deviceId, ownerGeneration, ownershipEpoch, version, run, nonce, profile hash가 일치해야 한다. 초기화 저널은 먼저 정상 검사돼야 한다.
3. 만료된 과거 grant는 서명된 발급 시점으로 **과거 소유 참조만** 검증한다. 현재 시계/lease/외부 정책의 유효성을 만들지 않고, 광고·스캔·APPLIED 발급은 차단한다. 원본 전체를 정상 백업으로 승격하지 않는다.
4. 정상 이전본으로 복구한 경우 기존처럼 더 높은 설정 버전을 요구한다. **최신 설정의 소유 참조가 서버 서명으로 직접 확인된 경우에는 동일 version/run/nonce/hash/format도 허용**한다. 이때에도 새 deviceNonce에 대한 현재 서버 서명을 정상 검증하고 반드시 다시 저장한다. 같은 버전이라는 이유로 쓰기를 생략하거나 과거 APPLIED를 반환하지 않는다. 이후 적용 성공을 별도 APPLIED로 확인한다.
5. 첫 동기화/서명 소유 참조 복구도 새 정상본을 ID 11에 먼저 검증 저장한다. 기존 정상본이 있으면 그 정상본을 먼저 백업한다. ID 10 쓰기/검증이 EIO로 실패하면 **별도 커밋 슬롯 ID 12**에 저장·검증한다. NVS는 데이터가 같으면 CRC가 손상됐어도 쓰기를 생략할 수 있기 때문이다. 두 슬롯 중 검증된 최신 동일 소유 설정을 사용하며 같은 버전의 서로 다른 내용/소유 충돌은 차단한다. 두 슬롯 모두 실패하면 STORED를 발급하지 않는다. 전송/저장 실패 시 키 재생성·설정 삭제·자동 롤백·만남 기록 삭제를 하지 않는다.
6. 소유 참조 검증 실패: BLE 정보/상태만 확인 가능하며 `ownerState:UNKNOWN`, `storageHealthy:false`, `settingsRecoveryRequired:true`. 임의 등록·새 소유 설정 수신은 차단한다. 추가 서버 복구 계약 없이 자동 소유 복원하지 않는다.
7. 기기 키/초기화 기록 오류: 서명 기능을 열지 않고 충전 표시·저전압 보호·USB `STORAGE CHECK`를 유지한다. 내장 RGB 오류 점멸은 충전 잔량 색과 구별한다.
8. 두 IoT NVS 파티션의 쓰기를 기존 Flash 드라이버에 전달하기 전에 256바이트 페이지 경계로 나누고 각 페이지를 바로 재읽기 대조한다. 성공 반환 뒤 실제 데이터가 누락돼도 NVS가 정상 완료 메타데이터를 쓰지 못하도록 EIO를 반환한다. 자동 erase/재시도/임의 지연을 넣지 않는다. 물리 Flash 드라이버/SDK 파일은 수정하지 않는다. 정상 NVS의 기존 공간 회수는 유지한다.

외부 Flash `keyring-store` 0+0x40000, `registration-store` 0x40000+0x20000과 기존 레코드 레이아웃은 그대로다. 기존 기기 키·serial·서버 신뢰 등록·초기화 완료 증명·만남 기록을 보존한다. UF2 업데이트 자체로 서버 소유 연결을 변경하지 않는다. NVS는 로그형 저장소이므로 향후 정상 저장/GC는 파티션 해시를 변경할 수 있다. 단순 펌웨어 교체 전후와 앱이 새 설정을 저장한 이후를 구분한다.

ID 12는 새 커밋 슬롯이므로 기존 펌웨어는 읽지 못한다. ID 12에만 정상 설정이 있는 상태에서는 이전 버전으로 임의 다운그레이드하지 않는다. 소유자 인증된 제품 초기화가 실행되면 RESETTING 기록이 영속 저장된 상태에서 ID 12→10→11을 지우고 검증한 다음 완료 처리한다. 이번 복구에서는 이 초기화 경로를 실행하지 않는다.

## FE 전달 — 이재준·황순철

현재 연결 오류 보드는 IoT의 설정 검사 실패로 BLE가 시작되지 않았다. 복구 펌웨어는 연결과 상태 확인을 살리지만, 앱의 설정 재전송 처리가 필요하다.

- `DEVICE_INFO`의 `ownerState`, `storageHealthy`, `settingsRecoveryRequired`, `applied`, `timeReady`를 파싱/보존한다. `BleDeviceInfo`·`BleProbeProtocol`에서 누락된 필드를 반영한다. 상태 미수신을 정상/0으로 바꾸지 않는다.
- `settingsRecoveryRequired:true` + `ownerState:BOUND`: 현재 등록된 계정/기기로 연결한다. 보안 특성 → 새 `GET_SYNC_CHALLENGE` → 새 requestKey로 현재 서버 서명 발급 → 원문 그대로 `SYNC_SETTINGS` → 서명된 STORED 서버 ACK → `GET_SETTINGS_APPLIED_PROOF` 및 서버 applied 확인. 최신 설정의 서명 소유 참조 복구는 같은 버전도 가능하다. 이전본 참조 복구가 `SETTINGS_RECOVERY_REQUIRED`로 거부되면 상위 버전이 필요하다. 기존 소유 등록을 삭제하거나 신규 등록 화면으로 보내지 않는다.
- `ownerState:UNKNOWN` + 저장 오류: 자동 신규 등록/초기화를 하지 않고 진단 필요를 안내한다. 동기화 요청을 반복하면 해결되지 않는다.
- `KeyringSyncViewModel`의 ACKED ticket + 동일 serial/epoch/version/hash만으로 최신 완료 처리하는 분기를 고친다. 재연결/재부팅 때 실제 적용 상태를 확인하고, `applied:false`/`timeReady:false`/복구 필요이면 새 challenge와 서명을 받아 재전송한다. 매칭 OFF도 적용 성공이므로 `matchingReady:true`를 완료 조건으로 쓰지 않는다.
- 저장 도중 `STORAGE_ERROR`: 성공 ACK나 APPLIED를 제출하지 않는다. 재부팅 후 다시 상태 조회하도록 안내하고, 같은 소비된 nonce/서명 요청을 무한 반복하지 않는다. 새 nonce에는 새 requestKey를 쓴다. 단순 BLE 응답 유실과 저장 실패를 구분한다.
- Android `ConnectFailed`에 원본 GATT status·연결 단계·기기 serial/펌웨어·요청 ID를 개인정보 없이 남긴다. 이 문자열만으로 페어링 실패/앱 오류/저장 오류를 확정하지 않는다.
- 재현 시험: 정상 저장→전원 재시작→같은 저장 버전·해시 + applied/timeReady false→동기화가 생략되지 않음. CRC 복구 상태, 저장 실패, 응답 유실, ON/OFF도 확인한다.

검토 코드: `front/app/shared/src/commonMain/kotlin/com/ssafy/e106/features/keyring/ui/KeyringSyncViewModel.kt`의 최신 상태 생략 분기, `BleProbeProtocol`/`BleDeviceInfo`, Android `AndroidKeyringBle.kt`의 GATT 오류 매핑. 실제 경로는 저장소 검색으로 대조한다. 검토용 재현 테스트는 Git 제외 `iot/build/sync-review-20261113/`에 있으며 제품 FE 소스는 이번 작업에서 바꾸지 않았다.

## BE 전달 — 김영빈·가을

기기 키 교체·DB 직접 재등록은 이번 보드 복구에 필요하지 않다. 0.9.9는 최신 TSV3의 서버 서명 소유 참조가 확인된 경우 동일 실행/버전의 새 서버 서명으로도 복구하므로, **이 경우 새 복구 API나 강제 버전 상승은 필요하지 않다.** 실제 발급·ACK는 QA로 확인한다.

1. XIAO-982253fb17614b66의 v19 실행에 대해 현재 소유자/epoch, syncRunId, payload hash, 발급 시각, STORED/ACK/APPLIED 존재를 대조해 달라. 기기 키는 `ed25519-19e770cdda7e092f`이며 개인키/seed는 요청하지 않는다.
2. `KeyringSyncService.createPeer`는 재사용 가능한 payload/grant의 **같은 settingsVersion/run**을 반환할 수 있다. 0.9.9 최신 서명 참조 복구는 이 동작을 지원한다. 새 deviceNonce에 새 requestKey로 outer signature를 받아야 하며 만료/변경된 grant는 기존 로직대로 새 설정을 발급한다. 오래된 정상 이력만 참조한 복구는 여전히 상위 버전이 필요하다. 이 경우만 별도 상위 버전 발급이 필요한지 검토해 달라.
3. 현재 소유자 인증·기기/키/epoch 결합과 동일 requestKey 멱등성을 유지해 달라. 동일 버전 복구라도 새 nonce 없이 과거 outer signature를 재전송하면 안 된다. 과거 이력만 남아 상위 버전 발급이 필요한 예외에 대해 새 API를 만들 경우 FE·BE 합의 후 적용한다. 클라이언트의 임의 큰 버전을 그대로 수용하거나 DB를 임의 수정하는 것은 해결책이 아니다.
4. STORED 전에는 저장 완료로, APPLIED 전에는 기기 사용 준비로 처리하지 않는다. 멱등 ACK·응답 유실 조회를 유지한다. ON/OFF는 적용 여부와 매칭 준비를 분리한다.
5. 서명 규격·공개키 교체는 이번 수정에 필요 없다. 손상 설정의 소유 참조까지 검증되지 않는 보드는 별도 인증 복구 계약이 필요하며, 초기화/새 키 발급으로 우회하지 않는다.

## 원인 확정 시험과 검증 기록

- 저장 직전 `SETTINGS WRITE begin`: key/version/길이/CRC/배터리 mV/USB 여부. 종료 `end`: nvs_write 반환값, 입력 RAM CRC가 변했는지. `SETTINGS COMMIT`: 전체 재읽기 검증 결과. 원문 payload/비밀키는 로그 금지.
- `SETTINGS READ`: 읽기 오류·schema 여부·계산 CRC·미사용 payload 영역의 0/FF/기타 개수. FF만 발견했다고 Flash 칩 자체 결함으로 단정하지 않는다. 쓰기 직전 CRC와 실제 flash readback을 대조한다.
- 실물 반복 시험은 사용 가능한 앱/현재 소유 계정·테스트 보드로 USB와 안전한 배터리 전원 각각 수행한다. 정상 저장/전원 재시작을 먼저 반복하고, 저장 시점 전원 차단 시험은 기존 사용자의 원본 데이터가 없는 시험 보드에서만 한다.
- 실제 서버의 fresh nonce 서명이 필요하므로 공개키만으로 임의 운영 설정을 만들지 않는다. 호스트 합성 키 테스트와 개발 서버 실물 종단 시험은 구분한다.
- v19 보드의 미사용 payload 영역은 0이 1305바이트, FF가 256바이트, 기타 0바이트였다. CRC 차이는 256바이트의 0→FF 변형과 일치하는 오프셋 후보를 갖는다. 이는 페이지 단위 누락 가설을 뒷받침하지만, 실제 연속 구간·발생 시점·칩/드라이버 원인까지 확정한 것은 아니다. 재읽기 통계만으로 쓰기 시점 원인을 확정하지 않는다.

### 0.9.9 빌드·탑재 확인

- 기준 commit `1b77454daba39efacd843c62300e01890f387b7e` + 미커밋 수정(`sourceDirty:true`). 기존 루트의 미커밋 작업을 보존하기 위해 `iot/build/history/worktrees/storage-repair-20261113`에서 작업했다. 보드 배포 파일은 기존 단일 경로 `iot/build/zephyr.uf2`다.
- UF2 SHA-256: `18f1c81978b457631c02fef0d7dccc2747e3cd17ac8610a20a09b8bd4e200324`, 833024바이트. 앱 영역만 전송했다. 전체 erase·키 재발급·서버 DB 변경 없음.
- 전체 호스트 검사 통과: 저장/초기화/소유 이전/설정 서명/실제 BE 검증기 호환/만남·큐·전원/적용 증명. 추가 Flash 검사는 64개 정렬, 각 페이지 누락, read/write 실패, 범위 넘침과 실패 후 다음 페이지 중단을 확인했다. `storage-repair-host-099-20261113.log`, `storage-repair-flash-guard-20261113.log` 참조.
- UF2 빌드 통과: FLASH 416280바이트, 정적 RAM 256516/262144바이트. 정적 수치를 실제 동시 BLE 부하 중 RAM 여유 측정으로 표현하지 않는다.
- XIAO-982253fb17614b66에서 `DIAG 0.9.9-d31`, `stage=radio-ready error=0`, 기존 keyId/공개키 일치 확인. 앱 동기화 전 `settingsRecoveryRequired=1`, storedVersion=19. 이전 손상 설정을 적용하지 않은 정상 복구 대기 상태다.
- UF2 교체 전후 registration SHA-256 `be65ba0bcd052681103ec0a1474add4dc009fcf8e828814eadc2f3ac9f3d645a`, keyring SHA-256 `3470b5a2419d49aba83b8867e2f12dd6ad55a0a34df453455464543c5c826401`가 동일했다. 근거: `iot/build/storage-repair-board-099-20261113.log`.
- 0.9.8 앱 시험에서 사용자는 “키링 설정을 보내지 못했어요”를 확인했다. USB에는 연결/정보/challenge/오류 응답이 있었고 설정 쓰기 로그는 없었다. 오류 프레임 길이는 `SETTINGS_RECOVERY_REQUIRED`와 일치하나 당시 코드 문자열을 직접 기록하지 않아 확정하지 않는다. 0.9.9는 오류 코드를 USB에 명시한다.
- 최초 0.9.9 수집 구간(`storage-repair-app-sync-099-20261113.log`)에는 새 NUS 요청/설정 쓰기가 없었다. 이후 사용자가 기존 등록 계정의 앱에서 **“설정 동기화 됨”**을 확인했다. 저장 순간은 수집 구간 밖이어서 당시 쓰기 반환값이나 STORED/APPLIED 원문은 확보하지 못했다.

### 앱 동기화 후 실물 관찰

- `iot/build/storage-repair-post-sync-099-20261113.log`: `storedVersion=19`, `settingsRecoveryRequired=0`, `storageError=0`. 같은 버전으로 복구 상태가 해제됐다. 서버 버전 강제 상승·키 교체·등록 삭제 없이 앱 재동기화가 가능함을 확인했다.
- serial/keyId/SPKI 공개키는 교체 전과 같다. `stage=radio-ready error=0`, `Peer proof=1 exchange=0 rejected=0`이 반복됐다. 이 출력은 서비스 스캔/peer 증명 처리 재개 근거이며 양방향 만남 확정·서버 제안 완료 근거는 아니다.
- 재저장 후 registration 해시는 `065bbd4c3e239a6c775b4e8ed521ab5660bd621909fa4097f5cc0903ac7f7d9a`, keyring 해시는 `cc6006aba73273da6eaff9a7e9e85794f807ef9e8d36caa744d5a5e6ecd0d30a`다. 정상 설정 저장·서비스 상태 기록에 따른 변경이므로 UF2 교체 전후 보존 검사와 구분한다. 이 해시만으로 개별 기록 추가/삭제 여부를 판정하지 않는다.
- 앱 성공 표시는 사용자 확인이며 서버 DB/ACK/APPLIED 원문을 독립 조회한 것은 아니다.

### 앱 종료 후 재부팅 검증 — 통과

- 사용자에게 앱 종료를 요청했고, 보드가 부트로더 상태여서 USB 재연결 후 정상 실행으로 복귀했다. 검사 과정에서 UF2를 다시 복사하거나 초기화하지 않았다.
- `iot/build/storage-repair-reboot-099-20261113.log`: `BOOT 0.9.9-d31 stage=storage`부터 새 부팅, `storedVersion=19; settingsRecoveryRequired=0`, `stage=radio-ready error=0`, `STORAGE CHECK ... storageError=0` 확인. 이 값과 해시는 로그의 첫 `BLE connected`보다 앞서 확인했으므로 앱 재전송 없이 저장된 설정을 정상적으로 읽은 결과다. 수집 후반에는 앱 연결·NUS 응답이 있었으며 전체 수집 시간 동안 앱 연결이 없었다는 뜻은 아니다.
- serial/keyId/SPKI 공개키가 동일하며 위 동기화 후 registration/keyring 해시 두 개가 재부팅 후에도 정확히 일치했다. 새 부팅만으로 설정·기기 키·만남 저장 영역을 다시 쓰거나 지우지 않았음을 확인했다.
- 이 결과는 정상 저장/재부팅 유지 검증이다. 재부팅 후 신뢰 시간·실행 적용을 위한 앱 재동기화, 서버 ACK/APPLIED 원문 확인, 여러 실물 키링의 반복 부하 시험과 최초 손상 발생 원인 확정은 별도다.
