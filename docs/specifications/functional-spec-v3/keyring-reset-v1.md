# 키링 초기화 BE v1 — IoT !217 연동 후보

S15P31E106-249 / MR !211. 기기 바이트 계약의 기준은 IoT !217 `f701140d`의
`iot/src/reset.c`, `proof.c`, `settings_auth.c`다. 이 문서는 BE 구현과 적용 순서를 설명하며
계약 담당자의 승인·개발 배포·실기기 완료를 대신하지 않는다. 기능은 운영·개발 모두 늘 켜져 있다(S15P31E106-407).

## 실행 설정과 안전 경계

켜고 끄는 설정은 없고 실제 서명 adapter를 늘 등록한다. 이미 준비한 개발 Ed25519 전용 키 파일과 기존 loader를 재사용한다.
키가 없거나 쌍이 다르면 시작 실패이며 임시 키·JWT 키·기기 개인키로 대체하지 않는다.
운영도 같은 키를 쓴다(11-11 결정 B). 실제 adapter가 없으면 시작은 POLICY_UNRESOLVED다.

## 앱 API

로그인 세션·활성 회원·현재 소유 세대·credential을 사용자→기기 잠금 안에서 재확인한다.
가입 중에도 본인 기기를 초기화할 수 있도록 KEYRING_REQUIRED 이후 접근을 허용한다.

| 동작 | 경로 | 요청 | 응답 |
| --- | --- | --- | --- |
| 시작 | POST /api/keyrings/{deviceId}/reset-runs | Idempotency-Key UUID, discardUnsentConfirmed boolean, deviceChallenge | 최초201, 동일 요청200 |
| 조회 | GET /api/keyrings/{deviceId}/reset-runs/{resetRunId} | 없음 | 본인 실행200 |
| 명령 재발급 | POST /api/keyrings/{deviceId}/reset-runs/{resetRunId}/command | deviceChallenge | 같은 실행200 |
| 기기 완료 | POST /api/keyrings/{deviceId}/reset-runs/{resetRunId}/ack | proof 문자열 | 검증·소유해제200 |

`deviceChallenge`는 GET_RESET_CHALLENGE의 서명된 필드 9개만 전달한다.
`resetProtocolVersion`, `keyId`, `deviceSerial`, `ownershipEpoch`, `localOwnerGeneration`,
`challengeId`, `deviceNonce`, `expiresInSeconds`, `signature`다.
BLE 외피인 `protocolVersion/type/requestId`는 이 객체에 넣지 않는다.
`localOwnerGeneration`은 현재 서버 소유 UUID 또는 아직 설정을 저장하지 않은 기기의 `NONE`만 허용한다.
현재 저장 공개키로 challenge 서명·serial·keyId·기기 epoch·소유 세대를 확인한다.
앱이 보내는 공개키·epoch 숫자·동의 boolean 자체를 기기 증명으로 인정하지 않는다.

응답은 resetRunId, deviceId, ownerGeneration, state(PENDING_DEVICE/COMPLETED),
discardUnsentConfirmed, createdAt, commandExpiresAt, completedAt, deviceCommand,
fromOwnershipEpoch, toOwnershipEpoch, resetNonce다. Cache-Control:no-store를 사용한다.
`deviceCommand`는 서명된 실제 RESET_DEVICE JSON **한 줄 문자열**이며 마지막 LF를 포함한다.
FE는 임의 재구성 없이 UTF-8 LF 프레임으로 BLE에 전달한다. 전송 requestId는 resetRunId다.
COMPLETED에는 deviceCommand가 없다.

ACK `proof`에는 RESET_RESULT의 `proof.signature` 문자열만 넣는다. 서버는 수신 메타데이터를
신뢰하지 않고 영속 실행의 serial/keyId/deviceId/ownerGeneration/runId/from/to epoch/resetNonce/
deletionScope/recordPolicy/LOGICAL/CLEARED를 직접 재구성해 기기 서명을 확인한다.
서명 규격은 `tingle-keyring-reset-v1`, 범위는 `OWNER_DATA_V1`이다.
실제 플래시 물리 소거를 보증하지 않으며 저장 증거의 `LOGICAL` 범위를 넘겨 표시하지 않는다.

## 재시도와 복구

- 첫 요청의 키·본문 hash·run·resetNonce·소유 UUID·동의·from epoch를 고정해 저장한다.
  같은 키의 다른 동의나 challenge는409, 같은 소유 세대의 별도 시작 키도409다.
- 미완료 동안 소유를 유지하고 설정 발급·ACK를 차단한다. 명령 만료로 소유를 자동 해제하지 않는다.
- 같은 challenge 재시도는 원 명령이며 만료를 연장하지 않는다. 만료 후에는 새 기기 challenge가 필요하다.
  기존 명령이 미만료여도 새 서명 challenge라면 /command에서 재발급할 수 있다.
  재발급은 동일 실행/동의/resetNonce/from/to epoch를 유지하고 challenge·서버 서명만 교체한다.
- commandExpiresAt은 BE의 수신/발급 시각에서60초인 재발급 안내 시각이다. AUTH 원문에는 이 절대시각이
  없고, 기기의 실제 허용 기간은 기기가 자신의 uptime으로 측정한 challenge60초다.
  오래된 challenge가 BE에 처음 전달되어도 기기 측 만료 검사는 우회할 수 없다.
- FE는 최초 응답의 resetRunId를 저장하고 앱 재시작 뒤 원 계정 GET으로 같은 실행을 복구한다.
  원 계정의 과거 COMPLETED 조회·동일 ACK 성공은 현재 등록/초기화 권한을 뜻하지 않는다.
  다른 계정은 과거 실행을 조회할 수 없다. runId를 완전히 잃었을 때의 실행 목록/검색 API는 없다.
  기기에는 GET_RESET_RESULT(protocolVersion1,
  requestId 임의 UUID, resetRunId, resetNonce)로 완료 증명을 복구한다. 기기 초기화를 다시 실행하지 않는다.
- 기기가 영속 삭제·최소 완료 기록을 저장한 후의 CLEARED 서명만 받는다. 늦은 ACK는 명령 만료와 무관하다.
  실행 완료·소유 해제·기기 epoch 증가를 한 DB 트랜잭션에서 커밋한다.
- 완료 후 동일 proof ACK는200, 다른 proof는409다. 완료 실행 조회 권한은 원 실행 소유자에게 남는다.
  재등록 이후 원 소유자의 ACK 재시도는 새 소유를 수정하지 않는다.

## ownership epoch와 등록·설정 v2

`keyring.ownership_epoch`는 검증한 기기 카운터다. 기존 미확인 행은 NULL로 남긴다.
NULL인 최초 기기는 서명된 epoch0 challenge/등록 v2 증명으로만0을 확인하며, 양수 epoch를 추정 채우지 않는다.
초기화는 from→from+1이고 Long.MAX_VALUE에서는 거부한다. 재등록에서도 카운터는 유지한다.
고정 serial·기기 인증키·폐기 정보·회원/프로필/설문/설정/채팅은 지우지 않는다.

발급하는 등록 challenge는 `tingle-device-proof-v2`다.
이 선택은 신규 등록 전체에 적용되므로 기존0.2.3 등 v1-only 앱/펌웨어 조합의 신규 등록과 호환되지 않는다.
FE와 IoT가 challenge v2·ownershipEpoch·UNOWNED를 지원하는 배포 버전·적용 순서를 합의하기 전에는
배포하지 않는다. 구형과의 혼재 운영을 자동 해결한 것으로 표시하지 않는다.
등록 요청은 기존 필드에 ownershipEpoch와 ownerState=`UNOWNED`를 함께 넣는다.
기기 서명 REGISTER 원문 끝에 epoch와 UNOWNED가 결속되며 현재 기기 카운터와 같아야 한다.
양수 epoch 기기는 등록 v1을 거부한다. 기존 v1 challenge/미확인 epoch 행은 별도 호환 경계로 보존한다.

기기 epoch를 확인한 이후의 sync run에는 불변 ownershipEpoch와 device-proof-v2를 저장한다.
응답에도 ownershipEpoch를 추가하고 서버 설정 서명은 `tingle-dev-settings-proof-v2`로 epoch를 결속한다.
STORED ACK v2의 마지막 epoch는 실행 값으로 검증하며 현재 소유 UUID·카운터·설정 버전도 재검사한다.
기존 NULL epoch 실행을 임의로 v2로 덮어쓰지 않는다. 초기화 후 새 소유는 새 run으로 동기화한다.
개발1바이트 STORED는 전체 성향 적용·신뢰 시각 준비를 보증하지 않는다.

## 적용과 확인 범위

- 기본 JPA 저장 구조와 테스트 설계 원본 `back/src/test/resources/database/schema.sql`·`tables.json`은
  소유 카운터 두 컬럼을 포함한 30테이블·274컬럼이다. `tables.json`의 nullable bigint·default 없음과
  실제 전체 앱 JPA 생성·설계 DDL을 필드 단위로 대조한다. 미확인 카운터를 자동 생성하지 않는다.
- `keyring_reset_run`은 JPA 엔티티가 아닌 명시적 JDBC 실행 저장소다. 별도 초기화 DDL까지 적용하면
  17컬럼 테이블 하나가 추가되어 31테이블·291컬럼이 된다. 기본 JPA가 자동 생성하는 것으로 보지 않는다.
- `back/src/main/resources/database/keyring-reset-249.sql`: 초기화 실행 영속 테이블 후보.
- `keyring-ownership-epoch-249.sql`: keyring와 keyring_sync_run의 nullable epoch 추가 후보.
- JPA validate에는 추가 컬럼이 필요하므로 공유 DB 검토·명시적 DDL 반영이 배포보다 먼저다.
  이 MR 작업에서 공유 DB DDL·서버 설정·기기·배포는 변경하지 않는다.
- 기존 초안 스키마/실행이 있다면 신규 필드의 자동 backfill이나 proof 추정을 하지 않고 별도 조정한다.
- 자동 검증은 합성 키·격리 PostgreSQL·IoT C 공개 fixture로 구분한다. 실제 팀 키와 실기기 E2E는 별도다.
- FE는 위 challenge 요청/실제 명령 전달/증명 복구/ACK와 재등록 v2를 연결해야 한다.
  BE·IoT·FE 계약 승인 후 개발 DDL·환경/키 마운트·실기기 순서로 검증한다.

초기화 실행은 현재 DB에 영속 저장하며 자동 정리 잡은 없다. 보관 일수·기한 만료 후 원 계정 조회 정책은
별도 합의 대상이며, 영구 보관이나 특정 기간의 보관 보장을 구현한 것으로 표시하지 않는다.
