> 보존 문서: 0.2~0.10의 LAB-2 동작이다. 현재 실행 기준은 [LAB-PROTOCOL.md](LAB-PROTOCOL.md)다.

# LAB-2 광고·스캔 시험 프로토콜

무선 버전 2 + 선택적 복구 조각 / 앱 0.10.0 / 2026-10-23 / S15P31E106-124 / 김종무(IOT). FE·BE·실보드 공식 계약을 변경하지 않는다. 0.2는 광고·스캔 병행과 그룹 인증, 0.3은 자동 저장, 0.4는 백그라운드 통신, 0.5는 키링 UI·성공 효과·통신 로그를 추가했다. 0.6은 ON 세션별 새 만남, 0.7은 백라이트 기울기·개별 기록, 0.8은 UUID 탐색 경로·수신 진단, 0.9는 실물의 미완성 메시지에 대응해 XOR 복구 조각을 추가했다. 0.10은 송신·부분 조립 유지 시간을 90초에서 150초로 늘린다. 암호화 envelope·원본 조각·SQLite v4는 유지한다. Story의 최초 범위 이후 요청은 이 독립 시험 앱에 적용한다.

양쪽 시험 APK를 0.10으로 함께 업데이트한다. 원본 조각은 0.2~0.8과 같은 형식이며 이전 수신기는 복구 조각을 무시한다. UUID 탐색 경로는 0.8 이상, 복구 조각 해석은 0.9 이상, 150초 복구 유지 시간은 양쪽 0.10이 필요하다. 혼합 버전에서 긴 복구 시간을 보장하지 않는다. LAB-1과 무선 호환되지 않지만 v1/v2/v3 DB의 기기·이벤트·제출 ID·payload·phase·기존 통신 로그는 v4로 보존한다. [0.1 규격 보존본](LAB-PROTOCOL-V1.md), [설치/시험](README.md), [검증](VERIFICATION.md).

## 광고 전송

- 전용 서비스 데이터 UUID: `f8fd2a22-6039-4854-8897-70fcba711206`.
- 양쪽에서 스캔과 광고 세트를 하나씩 병행한다. GATT 연결·서버·클라이언트·페어링은 사용하지 않는다.
- legacy=true, connectable=false, scannable=true, interval=160(100ms), TX_POWER_MEDIUM. 스캔 응답은 공개 서비스 UUID만 담은 18바이트 AD다. 기기 이름·TX power AD는 제외한다. 확장 광고 지원에 의존하지 않는다.
- 128비트 서비스 데이터 AD 오버헤드 18바이트 + 최대 13바이트 프레임 = 31바이트. 비연결 광고에는 connectable flags 3바이트를 추가하지 않는다.
- 스캔은 서비스 UUID, 기존 서비스 데이터 UUID+첫 바이트 `0x22`, 복구 조각 `0x30/0xf8` 마스크 필터를 OR로 사용한다. LOW_LATENCY/ALL_MATCHES/배치 지연 0이며 개별·묶음 콜백 모두 같은 worker에서 처리한다. 지난 세션의 늦은 콜백은 폐기한다. 무필터 스캔은 사용하지 않는다. 홈 이동·잠금·Activity 재생성 중에도 서비스가 무선을 유지한다.
- 광고 갱신 콜백 뒤 300ms 동안 같은 조각을 광고한다. **같은 nonce·transferId의 원본 묶음과 복구 묶음을 생성 후 최대 150초 동안 반복**한다. 원본 N개 다음에 새 seed의 복구 조각 N개를 보내고 대기 중인 다른 메시지와 HELLO를 순환한다. 같은 대상 세션·본문 재요청은 큐와 위치를 유지한다. 만료·단계 변경은 새 nonce·transferId, 일치하는 PONG/STORED는 남은 요청 취소다. 0.9의 90초 경계보다 복구 시간을 늘리되 180초 메시지 시각 검사는 유지한다. 송신 횟수는 상대 수신·저장 완료가 아니다.

서비스 UUID 응답은 기기 식별자·그룹 키·사용자 데이터를 포함하지 않는다. 스캔 응답은 BLE 탐색 동작이며 GATT 연결이나 만남 합의 성공이 아니다. 하드웨어/OS별 필터 경로 차이를 줄이기 위한 보완이며 S20+의 실제 장애 원인이라고 확정하지 않는다. 근거: [AdvertisingSetParameters.Builder](https://developer.android.com/reference/android/bluetooth/le/AdvertisingSetParameters.Builder), [ScanFilter](https://developer.android.com/reference/android/bluetooth/le/ScanFilter), [ScanCallback](https://developer.android.com/reference/android/bluetooth/le/ScanCallback).

## 그룹 인증·암호화

첫 기기에서 SecureRandom으로 16바이트 그룹 코드를 생성하고 32자리 hex로 표시한다. 양쪽에 동일한 전체 코드를 입력한다. 공통 키를 소스/APK에 넣지 않는다. 코드는 앱 전용 SharedPreferences에 저장하며 백업·기기 전송·로그·JSON에서 제외한다. 그룹 변경은 무선을 중지하고 기존 기록을 보존한다.

AES-128-GCM, 메시지마다 무작위 nonce 12바이트, 인증 태그 16바이트, AAD=`E106 virtual-keyring LAB-2`다. 광고 메시지는 `nonce || ciphertext || tag`이며 암호화 전 필드는 아래와 같다. 모두 필수, 정수는 big endian, UUID는 상위/하위 i64 순이다.

| 필드 | 타입 | 의미 |
| --- | --- | --- |
| source | UUID | 설치 시 생성한 가상 기기 ID |
| sourceSession | i64 | 무선 시작마다 생성한 0이 아닌 무작위 값 |
| target | UUID | 상대 ID, 발견 HELLO만 모두 0 |
| targetSession | i64 | 상대의 현재 무선 세션, 발견 HELLO만 0 |
| sentAt | i64 | 생성 시각 UTC epoch milliseconds |
| packet | 2~512바이트 | 애플리케이션 메시지 |

그룹 인증 실패, 다른 대상, 이전 대상 세션, 형식 오류에는 응답하지 않는다. 180초보다 오래되거나 현재보다 30초 초과 미래인 메시지를 거부한다. 양쪽 자동 날짜·시간을 권장한다. 인증된 nonce는 무선 세션 동안 최대 512개/180초 중복 제거한다. 재시작 이후 이벤트의 멱등 처리는 영속 eventId로 수행한다.

0.8은 인증과 형식 검사 뒤 시각 제외를 미래/만료로 구분한다. 시각 허용 범위는 변경하지 않는다. 예를 들어 태블릿이 휴대폰보다 60초 앞서면 태블릿은 휴대폰 메시지를 받을 수 있지만 휴대폰은 태블릿 메시지를 미래로 거부하므로 한쪽 수신만 가능한 상태가 된다. 날짜·시간 자동 설정 후 OFF→ON으로 새 메시지를 생성하도록 안내한다. 다른 그룹 메시지의 시각은 신뢰하거나 진단에 사용하지 않는다.

진단 숫자는 ON마다 초기화한다. 필터 응답, 서비스 데이터 수신 조각, 전체 조립, 인증·현재 대상 통과, 인증/형식 제외, 미래/만료 제외, 다른 대상/세션 제외를 분리한다. 중복 nonce는 수신 행을 추가하지 않는다. 수신 진단 복사는 앱 버전·현재 UTC·기기/OS·권한·자동 시간·카운터만 포함하며 그룹 코드·MAC·만남 내용을 포함하지 않는다. 실패/누락을 가짜 RX나 발견 기록으로 만들지 않는다.

HELLO만 broadcast target을 허용하고 source와 HELLO.deviceId가 같아야 한다. 직접 대상을 지정한 메시지는 현재 발견한 sourceSession과도 일치해야 한다. MAC은 조각 조립 구분에만 일시 사용하며 DB·화면에 보관하지 않는다. 현재 상대 세션보다 sentAt이 오래된 다른 세션 HELLO는 무시한다. 새 상대 세션을 수락하면 그 상대의 이전 세션 송신 큐를 제거한다.

광고 전파 자체는 주변 장치에 도달한다. 그룹 코드를 아는 참여자는 그룹 내용을 해석할 수 있고, 코드를 가진 임의 프로그램까지 차단하는 앱 설치 증명은 없다. 내부 가상 데이터용 그룹 인증이며 운영 기기별 키·소유권·인증서·안티릴레이·운영 재전송 방지 계약을 대체하지 않는다.

## 분할·조립과 자원 제한

프레임: `marker:u8=0x22, transferId:u24, index:u8, count:u8, chunk:1..7바이트`. 마지막 외 chunk는 7바이트다. 암호문 최대 596바이트/86조각이며 무선 주소+transferId로 묶는다. 순서 변경·반복·누락 후 재수신을 허용한다. 동일 위치의 다른 내용·잘못된 크기/개수는 버린다. 전부 조립하고 GCM 인증을 통과하기 전에는 메시지를 처리하지 않는다.

0.9 복구 프레임은 `marker:u8=0x30..0x36, transferId:u24, seed:u8, count:u8, xorChunk:7바이트`다. marker는 `0x30 + 마지막 원본 chunk 길이 - 1`이므로 정확한 암호문 길이를 복원할 수 있다. 원본의 마지막 조각만 0으로 패딩해 식을 만들며 패딩은 GCM 입력에 넣지 않는다.

선택 계수는 공개 `SHA-256(transferId:u32 || seed:u8 || count:u8)`의 하위 count비트다. transferId의 상위 8비트는 0이며 모두 big endian이다. 전부 0이면 seed modulo count 위치를 1로 한다. 선택된 원본 암호문 조각들을 XOR한 값이 xorChunk다. 이 해시는 인증이나 비밀키가 아니며 전송 중 조각을 섞는 규칙만 정한다. seed는 0~255 순환하고, 같은 seed의 같은 식은 진행도를 늘리지 않는다.

수신기는 원본 조각을 단위 식, 복구 조각을 XOR 식으로 취급하고 GF(2) 소거로 독립 식을 모은다. rank가 count에 도달하면 역대입해 원래 암호문을 만들고 정확한 길이로 자른다. 이후 기존 AES-GCM·출처/대상·세션·시각·Packet 검사를 그대로 적용한다. 모순된 식/길이는 해당 조립을 폐기하며 인증되지 않은 데이터로 RX·발견·저장·진동을 만들지 않는다. 원본 조각만 받는 경로도 유지한다.

진단의 `가장 많이 모은 데이터`는 미완성 메시지 하나의 독립 식 rank/count와 대기 묶음 수를 뜻한다. 같은 조각의 반복 수를 진행도로 표시하지 않는다. 복구 완료 후에는 완성 캐시가 같은 transfer의 반복 처리를 막는다. 높은 유실·간섭에서 완료 시간의 상한을 보장하지는 않는다.

한도: 미완성 조립 32개/150초, 완성 transferId 256개/180초, 송신 메시지 16개, 한 번의 ON에서 상대 세션 상태 최대 8개. 송신·부분 조립 시간은 같은 `RECOVERY_WINDOW_MS`를 사용한다. 마지막 유효 메시지 수신 후 120초를 넘기면 그 상대를 현재 발견·자동 재시도 대상으로 보지 않는다. 상태 캐시는 OFF까지 보존해 늦은 이전 세션 HELLO가 새 세션을 되돌리지 못하게 한다. 모두 시험 앱 기술 한도이며 제품 만남 기회·관계 제한 기간이 아니다. 송신 큐는 수신자·이벤트 ID로 구분해 과거 이벤트 응답이 새 만남 전송을 덮어쓰지 않는다.

## 메시지와 새 만남

DB 호환성을 위해 내부 Packet 헤더는 `version:u8=1, type:u8`로 보존했다. LAB-2의 외부 광고 형식·UUID·암호화는 분리되어 있다. 아래 필드만 허용하고 추가 바이트를 거부한다.

| 코드 | 메시지 | 필수 본문 |
| --- | --- | --- |
| 1 | HELLO | Peer: deviceId UUID, snapshotId UUID, settingsVersion i64, ready u8(0/1) |
| 2 / 3 | PING / PONG | 같은 correlation UUID |
| 4 / 5 / 6 / 7 | PROPOSE / READY / COMMIT / STORED | Encounter: eventId UUID, peerA Peer, peerB Peer, occurredAt i64 |
| 8 | ERROR | Java writeUTF, 1~80자 영문 대문자·숫자·밑줄 |
| 9 | NEW_REQUEST | 새 eventId로 쓸 요청 UUID |

가상 UUID 문자열이 사전순으로 앞선 기기가 새 이벤트 생성을 조정한다. 사용자 A/B 선택은 없고 어느 기기에서든 요청한다. 조정자에서 직접 생성하거나 상대가 NEW_REQUEST를 광고한다. 중복 요청은 같은 ID로 재전송하며 동시에 누르면 처리 중 기록 하나로 합쳐진다. 새로운 이벤트는 양쪽 현재 설정 준비를 요구한다.

인증된 HELLO 이후 양쪽 준비 상태와 이번 ON의 기록을 확인한다. 자동 eventId는 기기 UUID 사전순으로 정렬한 `UUID:radioSession/UUID:radioSession` 앞에 `E106-lab-on-1/`을 붙인 UTF-8 바이트를 `UUID.nameUUIDFromBytes`로 변환한다. 양쪽이 같은 ID를 얻고 어느 한쪽의 ON 세션이 바뀌면 새 ID를 얻는다. 이는 멱등성 키이며 인증 수단이 아니다. 조정자만 원래 발생 시각과 설정 사본을 생성·저장하고 PROPOSE를 보낸다. 수신자는 PROPOSE를 저장한다. 같은 ON 세션의 발견 상태를 다시 만들어도 DB의 같은 ID가 반복 생성을 막는다.

미완료 기록은 새 세션의 이벤트보다 먼저 원래 ID·시각·사본·제출 ID로 복구한다. 인증된 발견 때와 서비스의 2초 점검에서 확인하되 최근 120초 내 상대 메시지가 있어야 하며, 실제 재시도는 monotonic elapsed time 기준 최소 30초 간격이다. 진행 중인 같은 메시지는 송신 큐에서 합쳐 조각 전송을 방해하지 않는다. 저장 실패에도 같은 간격을 적용한다. 복구 후 이번 세션 ID의 완료 기록이 없다면 새 만남을 시작할 수 있다. 상대 부재·서비스 종료 중에는 실행하지 않는다.

## 서비스 수명과 화면 꺼짐

`KeyringService` 하나가 `LabController`·SQLite·무선 세션을 소유한다. 화면은 보이는 동안만 상태를 관찰하고, 홈·다른 앱 이동·화면 꺼짐·회전은 통신 중지나 새 세션 생성 사유가 아니다. 메인 뒤로가기 종료와 최근 앱 제거는 명시적 종료로 처리한다. 사용자가 화면에서 시작하면 started foreground service로 전환하며 외부 앱에는 서비스를 공개하지 않는다. 시작 요청 중과 실제 광고 준비 완료를 구분한다.

Android 12 이상은 `connectedDevice` 유형과 BLE 런타임 권한을 사용한다. Android 10~11은 기존 BLE 위치 권한·위치 서비스 요구에 맞춰 `location` 유형으로 시작하며, Android 8~9는 기본 foreground service를 사용한다. Android 13 이상 알림 권한을 요청하되 거부가 BLE 실행 성공을 뜻하거나 시작 자체를 차단하지는 않는다. 알림에는 그룹 코드·상대 ID·만남 세부 정보를 넣지 않고, 앱으로 돌아오기와 명시적 중지만 제공한다.

서비스는 광고 조각을 300ms 단위로 갱신해야 하므로 활성 통신 중에만 `PARTIAL_WAKE_LOCK`을 사용한다. 각 획득은 10분 한도, 서비스가 활성인 동안 5분마다 갱신하며 시작 실패·중지·Bluetooth 꺼짐·권한 문제·서비스 종료 때 해제한다. 알림을 보여주는 것만으로 CPU 실행이 유지되지는 않으며, 깊은 Doze에서 계속 시험하려면 OS의 배터리 최적화 예외가 필요하다. 앱은 현재 상태와 설정 진입점을 제공하고 예외 설정을 강제로 변경하지 않는다. 이 주기는 시험 앱 자원 관리 값이며 제품 매칭·알림 정책이 아니다.

`START_NOT_STICKY`로 동작한다. 명시적 중지·강제 종료·재부팅·시스템 프로세스 종료 뒤 몰래 새 서비스를 시작하지 않는다. 종료 후에는 기존 기록·그룹을 보존하고 OFF로 시작한다. 사용자의 ON 요청에서 고정 시험 설정을 내구성 있게 다시 적용한 뒤 통신을 시작한다. 이는 서버 동기화나 실제 키링 재시작 후 운영 준비 완료를 뜻하지 않는다. 다른 앱에서 복귀할 때는 기존 ON을 유지한다. 최근 앱에서 제거하면 `onTaskRemoved`가 통신을 중지한다. 메인의 뒤로가기는 통신 중지 후 task를 종료하며, 기록 화면의 뒤로가기는 메인으로만 돌아온다.

근거: [백그라운드 BLE](https://developer.android.com/develop/connectivity/bluetooth/ble/background), [서비스 유형](https://developer.android.com/develop/background-work/services/fgs/service-types), [필터 스캔과 화면 꺼짐](https://developer.android.com/reference/android/bluetooth/le/BluetoothLeScanner), [Doze와 전원 유지 예외](https://developer.android.com/training/monitoring-device-state/doze-standby), [알림 권한](https://developer.android.com/develop/ui/compose/notifications/notification-permission).

**0.6부터 이전의 “새 시험 버튼에서만 다음 만남 생성” 선택을 2026-10-23 최신 사용자 요청으로 대체했다.** 계속 ON인 같은 세션 쌍은 한 번만 자동 저장하고, 한쪽 또는 양쪽 OFF→ON·앱 종료 후 재실행→ON에서 새 기록을 허용한다. 오래 켜 두기·잠깐 이탈·화면 전환만으로는 새 만남을 만들지 않는다. 과거 기록을 삭제하거나 내용·시각·제출 ID를 변경하지 않는다. 운영 서비스의 미처리 만남·관계 제한 정책은 그대로다.

`새 시험 만남 저장`은 완료된 과거 기록을 보존하면서 새 ID·시각을 추가한다. 미완료 기록이 있으면 먼저 복구한다. `마지막 만남 재시도`는 미완료 기록 우선, 없으면 가장 최근 생성된 기록을 재전송한다. 과거 응답 수신이 최신 기록의 순서를 바꾸지 않는다. 이는 추가 시험 기능이며 운영 관계 제한·미처리 만남 억제를 해제하는 정책이 아니다.

## 양쪽 저장·오류·마이그레이션

생성 측 PREPARED 저장 → PROPOSE → 상대 PREPARED 저장 → READY → 생성 측 COMMITTED 저장 → COMMIT → 상대 CONFIRMED 저장 → STORED → 생성 측 CONFIRMED 저장 순이다. 전송은 모두 광고이고 애플리케이션 저장 응답이 Bluetooth 직접 연결을 뜻하지 않는다.

| 경우 | 결과 |
| --- | --- |
| 정상 새 만남 | 양쪽 같은 ID·시각·사본, 기기마다 다른 고정 제출 ID |
| STORED 유실 | 생성 측 COMMITTED, 상대 CONFIRMED. 기존 이벤트 재시도로 복구 |
| 저장 실패 | 해당 단계 성공 ACK 없음, 원본 보존 |
| 같은 ID·다른 내용/참여자 | 거부, 기존 증거 유지 |
| 준비된 같은 그룹의 첫 발견 | 버튼 없이 새 만남 자동 저장 진행 |
| 같은 ON 세션의 반복 광고·재발견·화면 전환 | 새 기록 추가 없음, 미완료 기록만 복구 |
| 한쪽 또는 양쪽 OFF→ON·종료 후 다시 ON | 미완료 기록 우선 복구 후 이번 세션의 새 이벤트 한 개 추가 |
| 새 버튼·이전 만남 완료 | 새 이벤트 행 추가 |
| 다른 그룹·변조·다른 수신 대상 | 발견/응답/기록 없음 |
| Bluetooth/권한 중지 | 무선 정리, 기록 유지 |

완전히 동시인 완료는 보장하지 않는다. `양쪽 저장 확인`은 기기 저장 확인이며 서버 접수·제안·동의와 별개다.

SQLite v2는 event_id PK, submission_id UNIQUE, 미완료 phase에만 pair_key UNIQUE 인덱스를 둔다. v1 테이블을 한 트랜잭션에서 복사해 rowid·payload·ID·phase를 보존하며 실패 시 전체 마이그레이션을 롤백한다. synchronous=FULL, 커밋 이후 ACK, 단계 후퇴 금지를 유지한다. 한도 256건에서 자동 삭제하지 않는다.

SQLite v4의 `traffic(id,direction,peer_id,recorded_at,payload,message_key)`는 `(direction,peer_id,message_key)`를 고유하게 둔다. v3의 본문 고유 조건을 제거하고 기존 행 전체를 같은 트랜잭션에서 복사한다. 이전 행의 key는 `legacy:<id>`로 표시하며 과거 TX는 `전송 요청 (이전 기록)`으로 보존한다. 과거 수신이나 발견·완료 행을 새로 꾸며내지 않는다.

- `DISCOVERY`: 인증된 HELLO로 첫 발견, 상대 새 ON 세션 또는 최근 메시지 이후 120초를 넘긴 재발견. 같은 ON에서 계속 보이면 발견 행을 반복하지 않는다.
- `TX`: HELLO 방송을 포함해 메시지의 첫 조각을 Android 광고 시작/갱신 성공 콜백으로 확인한 때. 화면에는 `송신 시작`으로 표시하며 상대 수신이나 전체 메시지 전송 완료를 뜻하지 않는다. 콜백 대기 중 큐가 바뀌어도 원래 메시지 정보를 사용한다.
- `RX`: 완전 조립 후 그룹 인증·시각·현재 대상 세션 검사를 통과한 메시지. 아직 송신자의 HELLO를 못 받은 경우에도 수신 사실은 보존하지만 만남 처리는 HELLO와 송신 세션 확인 후에 한다. 알려진 과거 세션 HELLO는 무시한다.
- `SAVED`: 새 CONFIRMED 전이 확인. `saved:<eventId>`로 한 번 기록한다. 원본 만남 기록과 제출 ID는 별도 테이블에서 그대로 유지한다.

신규 TX/RX와 발견 행의 `message_key`는 공개 nonce 12바이트의 Base64다. 같은 암호화 메시지의 조각·반복 순환은 한 행이며 새 nonce로 재전송하면 본문이 같아도 새 행이다. 양쪽의 같은 메시지는 같은 key로 비교할 수 있다. TX의 broadcast peer는 모두 0인 UUID이며 화면에는 `같은 그룹`으로 표시한다. 통신 로그 저장 실패는 상태 로그에 남기고 만남의 내구성 ACK 규칙과 분리한다. 로그를 자동 삭제하지 않으며 화면은 최근 80개만 표시한다.

0.8의 성공 알림은 새 `CONFIRMED` 전이 또는 일치하는 PONG 왕복 확인 뒤 `FeedbackGate`가 `(내 radioSession, 상대 UUID)`를 처음 승인한 때에만 발생한다. 상대가 OFF→ON해 새 만남이 생겨도 내 ON이 유지되면 추가 알림이 없다. 내 OFF→ON·서비스 종료 후 다시 ON은 새 로컬 세션이므로 상대별 한 번을 다시 허용한다. 그룹 변경·무선 실패 후 새 ON도 새 세션이다. 다른 상대는 독립적으로 한 번 알린다. HELLO만 수신하거나 광고 성공 콜백만 받은 상태에서는 성공 알림을 만들지 않는다.

서비스가 화면 표시와 무관하게 `[0,500,500,500]ms`, 진폭 `[0,255,0,255]`, 반복 `-1` 파형을 요청한다. Android 12 이상은 VibratorManager의 기본 모터를 사용한다. 화면은 같은 monotonic 시작 시각에 맞춰 500ms LED·흔들림, 500ms LED 소등·정지, 500ms LED·흔들림을 그린 뒤 기본 ON 표시로 돌아간다. 시스템 진동·방해 금지를 우회하지 않으며 모터 지원은 BLE 진단에서 보고한다. 뒤늦은 화면 복귀는 과거 효과를 반복하지 않는다. 다른 상대의 새 성공은 순서대로 실행하고 OFF는 대기 효과·진동을 취소한다. 시험용 효과이며 운영 서비스의 양쪽 알림 설정·후보 처리 정책을 구현했다고 간주하지 않는다.

기존 만남 내보내기 `virtual-keyring-lab-3`는 유지한다. `virtual-keyring-received-1`은 `format`, `productionUploadAllowed:false`, `exportedAt`, `deviceId`, `receivedMessages`를 담는다. 각 메시지는 `direction:RX`, `recordedAt`, `peerDeviceId`, `type`, `data`이며, 0.7은 `messageId`를 추가한다. v3 이전 로그의 messageId는 null이다. `data`는 위 메시지 표의 필드를 JSON으로 풀어 쓴 것이다. ISO-8601 UTC 시각·UUID 문자열을 사용한다. `recordedAt`은 개별 메시지 수신 시점의 기기 시계이며 `data.occurredAt`은 합의한 원래 만남 시각이다. 그룹 코드·MAC·운영 개인정보는 포함하지 않는다. 공유 선택창은 캐시의 개별 JSON 복사본에 대한 임시 읽기 권한만 전달하며 자동으로 외부 앱에 보내지 않는다.

Android 근거: [광고 크기/시작](https://developer.android.com/reference/android/bluetooth/le/BluetoothLeAdvertiser), [광고 세트 설정](https://developer.android.com/reference/android/bluetooth/le/AdvertisingSetParameters.Builder), [광고 데이터 갱신](https://developer.android.com/reference/android/bluetooth/le/AdvertisingSet). 실기기 RF 결과는 에뮬레이터 결과와 구분한다.
