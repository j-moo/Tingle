# 키링 내부 유저 플로우 전체 대조 — 0.7.2-owner-flow

## 2026-11-12 / 0.9.5 배터리 보강과 현재 연동 재점검

기존 표는 0.7.2 당시 검토 기록이다. 현재 SIS D3.1 Y3에는 외부 LED·모터·중지 버튼 구동 및 XIAO 내장 충전 RGB가 구현돼 있어 아래의 과거 “출력 배선 제외” 설명과 구분한다. `origin/develop`의 `f6bdd6f6` 및 후속 `d85cc55d` 기준으로 전원·NUS·저장·앱 기록 전송·서버 플래그·CI를 대조하고 MR !326 작업 브랜치에 통합한다. 이번 작업은 펌웨어 빌드/호스트 회귀이며 실물·개발 서버 종단 검증 결과가 아니다.

| 점검 | 조치·근거 | 남은 확인 |
| --- | --- | --- |
| 저전압에서 무선/모터가 계속 배터리를 소모함 | `power.c`의 다회 측정·회복 구간·부팅 전 검사, main에서 쓰기 종료 후 System OFF | ADC 오차·부하 전압·대기 전류·USB 기상 실측. 물리 배터리 차단은 불가 |
| 상태 조회와 출력 작업의 동시 ADC 사용 | `board.c` 전원 잠금으로 직렬화, 임계 판정 간격은 호출 빈도와 분리 | 실물 BLE 부하 중 측정 지연 |
| 모터 시작 후 전압 강하 | 구동 중 전압 재확인, USB 충전 확인 2초 단발·취소·같은 연결 재실행 억제 | 실제 모터 전류·구동 시간·SW1 OFF/배터리 미연결 오진동 여부 |
| 절전 중 늦은 NUS 응답이 peer 재개를 예약함 | quiescing 이후 `kr_peer_after_response` 실행 차단 | 실제 저장/응답 도중 전원 변화 시험 |
| 키·소유 정보·설정·원본 보존 | 기존 NVS 주소/레코드 형식 유지, 별도 전원 플래그 NVS 쓰기 없음, 동기 쓰기 잠금 완료 후 정지 | 업데이트 전후 ENROLL/version/epoch/기록 수 대조 |
| CHG 비활성을 완충으로 오해할 가능성 | 완충 확정 없이 `STOPPED_UNCONFIRMED`/장시간 USB 연결 진단 제공 | 실제 팩과 충전 IC 타임아웃·온도. 온도센서 없는 제어는 불가 |
| 앱 수동 한 건 업로드라는 과거 지적 | 현재 `KeyringForegroundSync`→`KeyringEncounterViewModel`에서 연결/새 확인 run 시 자동 전송, `scanSendable`로 순차 조회, 접수증 없는 항목은 원본 유지하고 계속 처리 | 같은 연결·같은 run에서 새로 생긴 기록의 추가 자동 전송 시점, 백그라운드/다중 기기 순회는 FE와 확인 |
| 서버 기능 플래그 | `infra/docker-compose.yml`, `back/src/main/resources/application.yml` 기본 false 유지 | 개발 서버 실제 활성화·서명/규칙 게시 값은 이번 읽기 범위 밖이며 미확인 |
| IoT CI | 현재 Jenkins IoT 단계는 `.ino` 검색/안내만 수행 | Zephyr SDK·호스트 검사 실행 단계 추가를 인프라 담당자와 조율. CI 초록을 펌웨어 통과 근거로 사용하지 않음 |
| 메모리 여유 | D3.1 빌드 RAM 256,516/262,144B(97.85%, 정적 스택/힙 예약 포함) | 주변 다수 키링·동시 BLE·서명·저장 시 스택/힙 고수위와 장시간 시험. 링크 여유를 런타임 여유로 해석하지 않음 |

전원 정책 호스트 검사는 `tests/test_power.c`: 저/고전압·ADC 오류·히스테리시스·빠른 중복 조회·2초 출력 기산·재충전/USB 흔들림·취소·장시간 연결 상태를 확인한다. 통합 `tools/test-host.ps1`은 기존 등록/설정/초기화/성향/10개 peer/3개 큐/소유 양도/서명 원본·ACK/APPLIED/공개 이름 회귀를 포함한다. 하드웨어 어댑터의 실제 System OFF·전류·진동·RF는 호스트 검사로 확인할 수 없다.

## 기존 0.7.2 점검 기록

2026-11-06 · IoT 김종무 · 로컬 코드·호스트 검사·UF2 빌드 범위

사용자의 최신 요구인 **한 계정 여러 키링**, 준비 후 연결 없는 지속 확장 광고/스캔, 세 큐·임의 체류 시간 없음, 원본/기기 키 보존을 우선한다. 대조 원문은 `docs/specifications/functional-spec-v3/{key,mat,prop,verification,keyring-reset-v1}.md`, 압축 MVP·UX 유저 플로우와 IoT 계약이다. 변경되지 않은 기존 검토 문서를 재사용하고 현재 코드 및 신규 경계 사례와 대조했다. Jira 225·226·228·230·251을 읽었으며 상태·담당·승인을 바꾸지 않았다. 과거 계정당1개 및 오래된 미구현 설명은 최신 사용자 결정으로 대체한다.

제외: 정확한 배터리 잔량 계측, 제품 LED·진동 구동 및 그 알림 중지용 물리 버튼 배선/실측(앞서 사용자 보류). 등록 모드는 별도 버튼 대신 현재 합의한 전원 부팅 후60초 창이다. 배터리 전압·충전 신호와 미확인 값 처리는 포함한다.

**판정:** 아래 정의한 내부 처리 경로는 코드와 검사로 대조·보강했다. 실물 탑재·RF/전력·실제 FE/BE 종단은 미확인이다. FE 화면·BE API/DB/제안·AI 개인 분석·정식 규격 승인을 펌웨어 구현과 혼동하지 않는다. 이는 알려진 요구 범위의 구현 대조이며 모든 환경에서 결함이 없다는 보증은 아니다.

검사 표기: C=해당 C 호스트 회귀, 독립=JDK/Node/OpenSSL/골든 검산, 어댑터=Zephyr 코드 검토·실제 대상 빌드(보드 실측은 아님).

| ID | 유저 플로우에서 키링이 할 일 | 구현 근거 | 확인 범위 |
| --- | --- | --- | --- |
| UF01 | 기기 고유번호/Ed25519 키 최초 생성·기존 키 재사용·손상 시 자동 재발급 금지 | board identity, registration-store key1/2 | C 키 보존/변조, 어댑터 |
| UF02 | USB ENROLL·DIAG와 BLE 기기 정보의 공개 식별값 제공 | board/nus/request | C DEVICE_INFO, 어댑터. seed/개인키 로그 없음 |
| UF03 | NUS 탐색·60초 등록 창·등록 후 재연결 광고 | main/nus | 코드·대상 빌드. UUID와 scan response 이름 분리 |
| UF04 | 서버 등록 challenge 서명·reset 이후 epoch 포함 UNOWNED 증명 | proof/request | C 등록·epoch, 독립 실제 BE v1/JDK v2 |
| UF05 | 한 계정 여러 키링과 타 계정 주변 기기 처리 | peer/product ownerRef | C 4개/2계정, 10개 peer. 계정 등록 개수 제한은 BE/FE |
| UF06 | 같은 계정 키링끼리 자기 매칭 금지 | peer AUTH ownerRef | C 자기 계정 거부 |
| UF07 | 등록 후 서버 소유 바인딩·미준비/OFF 상태 저장 | SYNC_SETTINGS 서명된 OFF 또는 전체 설정 | C 1바이트 OFF/전체 설정. 서버 등록 사실만으로 기기가 인터넷 상태를 추측하지 않음 |
| UF08 | UTF-8 JSON/LF·20B 분할·초과/중복 필드/타입 오류 거부 | frame/json/nus/request | C 프레임/최대 크기/타입, 어댑터의 특성 혼합 거부 |
| UF09 | 개인 데이터 암호화 특성 probe·동일 연결 전송 | nus RX4 L2, per-frame protected flag | C SECURE_TRANSPORT_REQUIRED, 어댑터 |
| UF10 | nonce·고정 서버 공개키·device/owner/epoch/run/version/hash 결속 검증 | settings_auth/proof/peer_auth | C 정상·위조·재생·오래된 버전, 독립 |
| UF11 | 성향·최소 개인 조건·각 값 상태·메타데이터 정확히 저장 | profile/policy | C/Python codec·보존, 골든 입력. 이름/사진 원문/원본 설문 저장 안 함 |
| UF12 | 완전 수신 후 설정 원자 저장·전체 재읽기·실패 시 보류 | board NVS·proof | C 전원/실패/이전 값 보존, 어댑터 |
| UF13 | 저장된 version/hash에 대한 서명 STORED | proof/request | C 실제 SYNC_SETTINGS→STORED, 독립. 서버 ACK는 FE 중계 |
| UF14 | 동일 저장 payload 재연결·새 deviceNonce/서명·새 임시 키로 재개 | kr_store/peer_after_response | C test_same_payload_resume. 새 payload hash면 새 version 필요 |
| UF15 | 재부팅 후 기록/설정 보존·신뢰 시간 전 새 매칭 보류 | storage/board/product/peer | C 재시작/시간 미확인/새 동기화 |
| UF16 | 저장 후 LAB ENABLE 없이 ON 광고/스캔, OFF/불가/불완전 정책 중지 | board peer plan/main/product | C OFF/정책/준비 판정, 어댑터. 단순24시간 미동기화 일괄 중단 없음 |
| UF17 | peer 연결 없는 HELLO/AUTH/암호화 PROFILE/control 교환 | peer/peer_auth/main | C 10개·유실·중복·MAC/서명 변조·재시작, 어댑터 |
| UF18 | 여러 주변 peer 공정 처리·받는 기기가 아닌 패킷 조기 필터 | main RX·peer cursor/16 sessions | C 다중 peer·캐시 혼잡, 어댑터. 2대는 상한 아님 |
| UF19 | 양방향 계산·성별/나이/사진·알 수 없는 입력 보류 | matching/profile/peer calculate | C·PC 참조 골든37건·누락/버전 거부. 운영 AI 승인 별도 |
| UF20 | 알려진 제한·정책 완전성·기기 허용·서명 기한 적용 | policy/product | C timed/permanent/self/expiry. 오프라인 최신 차단 즉시 인지는 보장 불가 |
| UF21 | 알고리즘/확정 전달/오류 검증 세 큐, 조건순 처리·임의 대기 없음 | engine | C FIFO·세 큐·backpressure |
| UF22 | 처리 차례에서 현재 인증·소유 세대·근접·제한 재확인, 떠난 후보가 다른 후보를 막지 않음 | product candidate_ready/engine drain | C test_candidate_and_completion |
| UF23 | 양쪽 동일 이벤트·당시 버전/기한 합의와 영속 확정 | engine control | C handshake 각 단계 재시작·유실·서버 접수 전후. 미래 OFFER 시각은 허용 skew 이내 |
| UF24 | 자기/상대 설정 변경 시 미확정 후보 분리·실패 입력 재계산·확정 기록 보존 | engine drain/product cache_peer/failed_input_changed | C test_failed_input_change: ACK·재부팅 후 새 입력 계산, 옛 REVIEW hash 유지 |
| UF25 | 상대 계정 다중 기기의 중복 억제·미확정 경쟁 결정 | product admit/ownerRef | C 4개 경쟁·retired 진단 접수 후 정상 후보 유지. 사용자 쌍 전역 합치기는 BE |
| UF26 | 같은 기기가 양도되어도 이전 세대와 신규 소유자 기록/억제/제어 구분 | peer scope/product/engine scoped lookup | C test_owner_transition: 같은 HW·프로필·revision으로 epoch1→2→3 |
| UF27 | 관측·보존 중인 더 높은 세대에서 옛 AUTH/같은 epoch 다른 owner로 회귀 금지 | cache_peer + frozen scopes | C 재부팅 후 옛 임시 개인키 보유 replay actor 거부. 서버의 미전파 폐기를 추측하지 않음 |
| UF28 | 원래 성향·기한·상대 인증서·증명 동결 | product KEV2/TER2 | C 설정 변경/상대 양도/재시작 후 옛 envelope hash 유지, 독립 |
| UF29 | 앱 소유자 READ/ACK/RELEASE/CONTROL 인증과 연결/세대 경계 | owner session/product/nus | C nonce·기한·권한·단절·현재 소유 변경. NUS 요청 연결 epoch 고정 |
| UF30 | 실제 저장 용량·미접수/검증/후보 수·FULL/UNKNOWN 표시 | storage_space/product + FE STATUS 수신 | C 집계/미권한, 실제548B C STATUS→Kotlin NUS/파서 회귀. 어댑터 NVS 계측·미측정 잔량 null |
| UF31 | 기록 번호 영속 예약·조회 epoch·동일 기록의 분할/재전송 | engine sequence/storage/product | C 원본 전부 삭제 후 재부팅·번호 실패·커서 불일치·조각 hash |
| UF32 | 앱 읽기만으로 삭제하지 않고 서명된 정확한 서버 접수만 반영 | product receipt/export | C 권한·서명·submission/hash·중복 ACK·다른 기록 유지 |
| UF33 | 억제 먼저 저장 후 원본 삭제·부분 실패 복구 | product_suppression/storage/engine ack | C 원본 삭제 실패 후 재시작/재시도·계정/세대 저널 복원 |
| UF34 | 신뢰 시간에서 원래 기한만 만료 처리·재전송/정책 변경으로 연장 금지 | product tick/frozen profile | C 3일/기존7일·clock unknown 보존·동결 원본 |
| UF35 | 서버 허가·원본 삭제·해당 소유 세대 이탈 관측 후 반복 억제 해제 | product release/engine release | C 이탈 전 거부·옛 세대 해제가 새 세대 원본에 영향 없음 |
| UF36 | 초기화 허가/epoch/기기/현재 소유자·미접수/후보/오류 동의 확인 | reset/board begin_reset/kr_pending | C require-synced/discard 동의·미완료 후보 포함 |
| UF37 | RESETTING 저널·무선 중지 후 선택 삭제·키/serial/조회 counter 보존·페어링 정리 | reset/board/storage/main/service | C 서명된 초기화→공유 메인 조정→CLEARED, 재부팅·중지 실패·동시 초기화·키 보존. 어댑터 bond 삭제/재읽기 |
| UF38 | CLEARED 영속 증명·응답 유실/재부팅 재조회·새 소유자 등록·옛 설정/초기화 거부 | reset/request/proof | C test_reset/JDK. 기기 완료와 BE 소유 해제는 별개 |
| UF39 | 저장/최소 억제 용량 부족 시 새 작업 보류·유효 원본 덮어쓰기 금지 | kr_can_insert/engine/storage | C 큐·최소 상태 예약·NVS 실패. 서버 release 전 임의 원본 정리 없음 |
| UF40 | 오래된 비동기 완료가 새 슬롯을 갱신하지 않음·개인 버퍼 정리 | kr_sent_guarded/nus/frame/product | C 재사용 슬롯 완료 거부·framing, 어댑터/코드 검토 |
| UF41 | 예전 데이터 안전 이관·불완전 증명/소유 정보 복구 | load_accounts/RESTORE_ACCOUNT_LOCK v2 | C 옛 레코드 보존·서명 복구/변조/멱등, 유효 설정에서 복구 후 자동 재개·OFF/만료/저장 오류 보류. 미확인을 임의 값으로 채우지 않음 |

## 이번에 추가·수정한 경계 사례

1. 상대의 HW·TPS1 데이터·settingsVersion이 모두 같아도 ownerGeneration/ownershipEpoch가 바뀌면 새 소유자로 분리한다. 옛 확정 원본은 유지하고 새 세대 원본을 함께 저장한다.
2. 옛 세대 ACK·release는 옛 원본/억제만 처리한다. 새 세대 원본이 같은 HW 슬롯을 참조해도 지우거나 가로막지 않는다. 옛 세대는 새 세대의 이탈 관측으로 갱신하지 않는다.
3. 미확정 후보에 대해 더 최신 상대 소유/설정이 인증되면 당시 증거를 REVIEW로 보존하고 새 입력을 별도 처리한다. 오류 fingerprint도 소유 scope에 결속하여 다른 세대의 오류를 같은 것으로 취급하지 않는다.
4. 이탈한 첫 후보는 대기하고 다른 현재 후보를 즉시 처리한다. 임의 큐 체류 타이머를 넣지 않는다.
5. 초기화 require-synced가 확정·검증 큐뿐 아니라 미완료 후보/오류 backpressure도 확인한다. 남은 후보를 동기화할 수 없다면 명시적 삭제 동의 정책을 선택해야 한다.
6. 새 기록마다 최종 접수 후 필요한 최소 억제 슬롯을 예약 가능한지 검사한다. 옛 무선 완료 콜백은 eventId/sequence가 맞을 때만 처리한다.
7. 암호화 BLE 수신 프레임·요청 후보·전송 응답과 초기화된 소유자의 작업 버퍼를 사용 종료 시 정리한다. NUS 소유 세션은 수신 당시 연결 epoch에 결속한다.
8. 계산 실패 원본은 접수 후에도 당시 입력 버전/hash를 KAS3 저널에 유지한다. 같은 실패 입력은 억제하지만 새 서명 입력은 재계산하고, 과거 REVIEW/확정 원문을 고치지 않는다. 메타데이터가 부족한 옛 억제는 서버 v2 증명으로 복구한다.
9. !251 리뷰에서 초기화 중지 계획의 generation 불일치와 기존 FE512B 수신 제한을 발견했다.0.7.1에서 수정하고 공유 main 조정 경로와 C STATUS→Kotlin 전송/파서 검사로 보강한다. 기존41항목 검토만으로 실제 main 어댑터와 FE 종단까지 통과했다고 해석하지 않는다.
10. 후속 리뷰에서 소유 메타데이터 누락을 영구적인 engine 중지와 혼동한 경로를 발견했다.0.7.2는 누락 중 PROFILE/제어·후보·이탈 처리를 보류하고 정상 서명 복구 후 현재 설정이 유효할 때 자동 재개한다. 만료/OFF/저장 오류·미적용 조건은 계속 차단한다.

## 실행·남은 실제 연동

- `tools/test-host.ps1`: 기존 C 엔진/등록/초기화/성향 저장, Python·Java 호환, peer 및 product 통합 검사.
- `tests/test_product.c`: 전체 서명 설정/기록 경로, 위 양도·후보·resume 경계와 배터리 미확인 분기. `tools/verify-app-flow.mjs`: OpenSSL 독립 공개 벡터 검산.
- `tools/verify-peer-golden.py`: 외부 원본을 수정하지 않는37건 PC/C 계산 대조.
- 로컬 결과는 `build/owner-flow-{host-tests,product-tests,firmware-build,golden-tests}.log`, `build/firmware-manifest.json`에서 확인한다. 로그·UF2·SDK는 Git 제외다.

최초0.7 구현 시 실행 결과다. 아래 소스 기준/해시는 당시 로컬 검증 스냅샷을 보존한 것이다. 이후 최신 develop 반영·커밋 후 다시 빌드한 현재 산출물의 정확한 기준 커밋·dirty 여부·해시는 `build/firmware-manifest.json` 및 해당 MR 검증 기록을 따른다.

| 항목 | 결과 |
| --- | --- |
| 전체 호스트 회귀 | 통과. C 엔진·등록/저장·초기화·성향·10 peer·product, 실제 BE 검증기/JDK/OpenSSL 대조 |
| 추가 product 회귀 | 통과. 양도·후보 재확인·실패 입력 재계산·복구 v2 credential 변조/구버전 거부 |
| 계산 골든 대조 | PC 참조/C37건 일치. 미지원 버전·유효하지 않은 입력은 거부 |
| 공개 벡터 | 기존 v2/v3 및 새 owner 벡터 모두 Node/OpenSSL 검산 통과. 개인키 포함 없음 |
| UF2 | `build/zephyr.uf2`, `0.7.0-owner-flow`, 799,232B |
| SHA-256 | `87a82c95543ef154eae61e92f2649cfbbe93f222c3c718ecc6b475010f34c1bf` |
| 소스 추적 | 기준 `2ea735bfb325bf9d2d852b4b31d1cc50ef32f3b1` + 미커밋 변경(`sourceDirty:true`). manifest46개 소스 해시 일치 |
| 저장 영역 | 기존 overlay 변경 없음. UF2 내부 코드 주소 `0x27000..0x88900`(끝 제외). 외부 keyring/registration 저장 영역 쓰기 포함 없음 |
| 메모리 | FLASH399,400B, RAM255,940/262,144B(97.63%). 이 값은 지정한 스택/heap arena를 포함한 링크 할당이며 실물 최대 스택/heap 사용량은 측정 전 |
| 파일 검사 | UTF-8/LF(PS1은 CRLF)·문서 링크·diff check 통과. build/AGENTS 미추적·Git 제외 |
| 보드·외부 연동 | 이번 작업에서 쓰기/초기화/키 재생성 없음. 실제 보드 RF·전력·장시간 및 앱/서버 종단은 미실행 |

최초 해시·소스 대조 상세는 `build/owner-flow-verification.json`에도 남겼다. 게시 작업은 최신 develop `c1ac141e`를 pull한 뒤 새 `iot-feature/owner-aware-user-flow-S15P31E106-226` 브랜치에서 수행한다. Jira226을 주 이슈로 연결하고228·251·230은 관련 범위/후속으로 참조한다. LED·진동·실물 종단이 남아 있어 이 MR에 이슈 자동 완료 문구를 넣지 않는다. 기존 `hardware/` 사용자 자료는 커밋 대상에서 제외하고 보존한다.

**FE/BE 필수 연결:** [최신 인계](app-flow-v2-handoff.md) 맨 위0.7 절. 기존 개발 앱의1바이트 중계만으로 전체 성향 경로가 켜지지는 않는다. 등록 직후 전체 입력 공급이 늦으면 서명된 최소 OFF 설정으로 기기 소유 바인딩을 먼저 완료하고, 이후 새 version의 전체 payload를 동기화한다. 새 grant/run/기한으로 payload hash가 바뀌면 version도 올린다. 재연결 시 동일 byte payload를 재사용하면 새 nonce에 대한 바깥 서명으로 검증·재개할 수 있다. 실제 FE/BE 수정·AI 규격 승인과 실물 RF/재시작/장시간 운전은 이 표의 로컬 통과를 대신 사용하지 않는다.
