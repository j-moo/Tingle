# 저장 오류 복구 — 2026-11-13

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
