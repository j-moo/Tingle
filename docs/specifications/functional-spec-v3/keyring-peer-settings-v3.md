# 전체 키링 설정·소유 세션·만남 접수 v3

[S15P31E106-270](https://ssafy.atlassian.net/browse/S15P31E106-270)의 기능 동작과 완료 기준이다.
기존 BE257의 196B TPS1 발급과 BE249의 초기화·소유 세대 검증을 확장한다.
기준 펌웨어는 `0.7.2-owner-flow`이며 통합 기준은 `6a46c2a2`다.
이 문서의 구현 설명은 개발 브랜치의 동작을 뜻한다. 병합·개발 배포·실기기 통과·정책 승인을 대신하지 않는다.

## 적용 범위와 사용자 상태

한 계정은 여러 키링을 가질 수 있다. 계정의 `ownerRef`는 동일하지만 기기의 등록·소유 세대·설정 실행·저장 ACK는
각각 독립적이다. 한 키링의 ACK로 다른 키링을 저장 완료로 처리하지 않는다.

다음 상태를 합치지 않는다.

| 상태 | 근거 | 이 근거만으로 확인할 수 없는 것 |
| --- | --- | --- |
| 서버 발급 | 현재 실제 입력·소유·정책을 사용한 서명 설정 실행 | BLE 전송·기기 저장 |
| 기기 저장 | 해당 실행·버전·hash의 기기 STORED 증명 | 설정 적용·신뢰 시각·매칭 준비 |
| 서버 ACK | STORED 검증과 ACK 상태의 영속 반영 | 가입 완료·matchingReady |
| 가입 완료 | 기존 `OnboardingService`의 전체 조건 | 모든 키링의 실시간 RF 동작 |
| 기기 매칭 준비 | 기기의 applied/timeReady/matchingReady 및 현재 조건 | 서버의 양쪽 만남 검증·제안 발행 |

TPS1뿐 아니라 TSV3의 저장 ACK도 가입 완료로 자동 승격하지 않는다. 성향 저장의 성공을 기기 매칭 준비로
표시하지 않는다. 기기 적용/신뢰 시각의 별도 증거를 가입 조건에 반영하는 정책은 추가 확정 대상이다.
deviceAllowed는 matchingEnabled만으로 true가 되지 않는다. 기존 가입 완료와 현재 필수 동의 접근 조건을
충족하지 않으면 false로 발급한다. 새 가입자의 준비 증명 정책을 임의로 변경해 이 조건을 우회하지 않는다.

## 전체 TSV3 설정 발급

기존 sync-runs API에 `payloadFormat: "tingle-keyring-settings-v3"`를 명시해서 요청한다.
형식을 생략한 기존 요청과 `tingle-profile-storage-v1` 요청을 자동으로 TSV3로 바꾸지 않는다.

| 동작 | 실제 경로 | 호출 가능한 가입 단계 | 응답 |
| --- | --- | --- | --- |
| 발급·재연결 | POST `/api/keyrings/{deviceId}/sync-runs` | SYNC_PENDING 이후 | 새 실행201, 재사용200 |
| 실행 조회 | GET `/api/keyrings/{deviceId}/sync-runs/{syncRunId}` | KEYRING_REQUIRED 이후 | 현재 유효 실행200 |
| 저장 증명 접수 | POST `/api/keyrings/{deviceId}/sync-runs/{syncRunId}/ack` | SYNC_PENDING 이후 | 검증된 ACK200 |

발급 요청은 `Idempotency-Key` UUID와 다음 두 필드로 구성한다.

| 필드 | 의미 |
| --- | --- |
| deviceNonce | 현재 기기 challenge의 정규 Base64url 32B. 새 연결의 nonce를 이전 요청 키에 덮어쓰지 않는다. |
| payloadFormat | `tingle-keyring-settings-v3` |

서버는 활성 로그인 세션·현재 기기 소유·credential·확인된 epoch·초기화 진행 여부를 잠금 안에서 재확인한다.
실제 완료 설문 및 활성 성공 분석, 프로필·희망 조건·옵션으로 TPS1을 만들고, 이를 타입4 grant와 전체 정책에 연결한다.
run/nonce·버전을 먼저 고정하고 TPS1 hash → grant 서명 → TSV3 → 전체 hash → 바깥 epoch v2 서명 순서를 지킨다.

`VALUE/ANY/UNKNOWN/MISSING`은 공급 계약에 따라 구분한다. 미측정·불명확 입력을 임의 정상값으로 채우지 않는다.
희망 성별·미측정 척도 등 기존 매핑의 정책 해석과 AI codec/rule 승인은 별도로 확인해야 한다.
정책 승인 flag만 켠 것을 실제 담당자 합의 증거로 취급하지 않는다.

응답은 기존 `SyncRunView`를 사용한다. 기기·소유 세대·epoch, syncRunId, 설정 버전/현재 버전, 상태·stale,
원본 payload의 Base64url·형식·hash, 기기 STORED용 nonce/keyId/proofProtocolVersion와
바깥 서버 서명·serverTime·deviceNonce를 구분한다. 원본 바이트를 임의 JSON으로 재구성하지 않는다.

정책은 현재 회원/매칭 옵션과 서버의 관계 제한에서 공급한다. 상대의 ownerRef가 없거나 제한 정보를 완전하게
공급할 수 없으면 `restrictionsComplete=0`으로 매칭 준비를 보류한다. 60개 초과 제한을 잘라서 완전한 목록이라고
표시하지 않는다. ON/OFF와 deviceAllowed, grant 기한·만남 기한·presence gap은 서로 다른 조건이다.

### 버전·재연결·입력 변경

- 같은 요청 키와 같은 nonce는 이미 저장한 서버 서명 결과를 재사용한다. 같은 요청 키에 다른 nonce나 형식을
  넣으면 충돌이다.
- 새 연결은 새 요청 키와 새 기기 nonce를 사용한다. 기존 payload/run/version이 현재 입력·소유·정책·기한과
  일치하면 이를 재사용하고 바깥 서명만 새로 발급할 수 있다.
- grant/run/기한·실제 입력·관계 정책이 바뀌어 바이트가 달라지면 새 설정 버전과 새 실행을 만든다.
  같은 버전에 다른 hash를 덮어쓰지 않는다.
- 만료·입력 변경·동의 철회 등으로 기존 실행이 현재 조건과 다르면 과거 실행의 조회/ACK/재발급을 유효한
  현재 설정으로 취급하지 않는다. 새 요청 키로 현재 입력을 다시 공급받는다.
- ACK는 요청의 settingsVersion·payloadHash와 실행·기기 keyId/서명·소유 generation/epoch를 대조한다.
  다른 실행·다른 소유 세대의 저장 증명을 재사용하지 않는다.
- 기존 설정 변경/동의 철회의 버전·스냅샷 무효화 경로를 재사용한다. 새 TSV3 실행도 실제 입력을 재검사하며,
  개인 데이터가 포함된 실행 정리는 기존 철회 정책의 적용 범위를 따른다.

주요 오류는 INVALID_REQUEST(400), IDEMPOTENCY_CONFLICT(409), SYNC_VERSION_STALE(409),
INVALID_DEVICE_PROOF(422), POLICY_UNRESOLVED(422), SESSION_REVOKED(401)다.
설정 저장 ACK를 성공시켰다는 이유로 입력 공급 대기나 정책 미확정을 숨기지 않는다.

## 계정 참조와 소유 이력

`ownerRef`는 회원 ID를 노출하지 않는 영속 UUID다. 같은 계정의 여러 키링이 공유하고 다른 계정과 구분한다.
소유 구간은 기기·ownerGeneration·ownershipEpoch·당시 ownerRef/회원·serial·credential·시작/종료 시각을 동결한다.

서명된 epoch를 가진 신규 등록은 등록 시점에 구간을 만든다. 과거 이력이 없는 기존 등록 기기는 첫 인증된 관측
시점부터 기록하며 과거 등록일을 현재 행에서 추정하지 않는다. wire의 초 단위 시각에 맞춰 초 단위로 기록한다.
이력이 없는 과거 증명을 새 소유자의 현재 값으로 보충하지 않는다.

소유 이력은 기존 등록/초기화의 int64 epoch를 자르지 않는다. TSV3/type4 발급은 명시적인 UINT32 epoch/version
상한을 확인하며 초과 값은 거부한다. CLEARED 검증 후 기존 소유 구간 종료와 소유 해제를 같은 트랜잭션에 반영한다.
새 소유자 등록 이후 과거 완료 ACK를 재전송해도 새 소유 구간을 닫지 않는다.
자세한 초기화 동작은 [초기화 기능 명세](keyring-reset-v1.md)를 따른다.

## 소유 세션 허가

| 동작 | 실제 경로 | 응답 |
| --- | --- | --- |
| 허가 발급 | POST `/api/keyrings/{deviceId}/owner-session-grants` | 서명된 허가200 |
| 응답 유실 복구 | GET `/api/keyrings/{deviceId}/owner-session-grants/{requestKey}` | 동일 허가200 |

현재 로그인 사용자·세션·기기 소유·소유 generation/epoch와 명시한 deviceNonce를 확인한다.
필수 동의를 마친 가입 중 사용자도 사용할 수 있도록 KEYRING_REQUIRED 이후 접근을 허용한다.
기본 프로필·설문 미완료를 이유로 본인 기기의 소유 제어를 막지는 않는다.

POST는 `Idempotency-Key` UUID와 다음 필드를 요구한다.

| 필드 | 조건 |
| --- | --- |
| ownerGeneration | 현재 소유 UUID. 생략 불가 |
| ownershipEpoch | 현재 확인된 비음수 epoch. 생략을0으로 취급하지 않음 |
| deviceNonce | 현재 기기 challenge의 정규 Base64url 32B |
| permissions | READ/ACK/RELEASE/CONTROL 비트 조합, 1..15. 작업에 필요한 최소 권한을 요청 |
| lifetimeSeconds | 1..300초. 요청마다 명시 |

허가는 기기·소유·로그인 family·nonce·권한·기한에 결속한다. 같은 키의 재시도/GET은 원래 응답을 반환하고
기한을 연장하지 않는다. 다른 nonce·권한·수명을 같은 키로 요청하면409다. 만료한 허가는410
OWNER_PROOF_EXPIRED이며 새 challenge/요청 키가 필요하다. 로그아웃 세션은 조회/재발급에도 사용할 수 없다.
다른 소유·세대는 OWNER_MISMATCH(409), 준비되지 않은 기기/서명 공급은 POLICY_UNRESOLVED(422)다.
전체 서버 공개키 pin이 없거나 실제 서명 공급자의 공개키와 다르면 허가를 발급하지 않고 POLICY_UNRESOLVED다.
응답에는 Cache-Control:no-store를 적용한다.

응답 필드는 proofProtocolVersion, serverKeyId, deviceId, ownerGeneration, ownershipEpoch,
deviceNonce, permissions, serverTime, expiresAtEpochSeconds, serverSignature다.
허가는 현재 BLE 연결의 소유 인증에 중계하며 앱 재시작 뒤 과거 허가가 새 연결을 자동 인증한다고 가정하지 않는다.

## TER2 검증·내구 접수

TER2 암호 검증기는 전체 원문 hash·길이·기기 서명·양쪽 서버/기기 credential·동결 프로필·run/version/hash·
event/submission·원래 시각/만남 기한을 확인한다. CONFIRMED와 REVIEW를 구분하며, 현재 설정으로 과거 동결
증명을 다시 만들지 않는다. 암호 검증만으로 당시 소유 구간이나 서버의 양쪽 접수/관계 정책이 완료된 것은 아니다.

실제 경로는 POST `/api/offline-encounters/sync`이며 기존 deviceId/submissions 구조를 사용한다.
별도 raw 업로드 경로를 만들었다고 가정하지 않는다. 각 submission은 다음 값을 원본과 대조한다.

| 필드 | TER2 요청에서의 의미 |
| --- | --- |
| submissionId / eventId / occurredAt | 서명된 원문의 제출/이벤트 ID·당시 시각 |
| policyVersion / matchingRuleVersion | 현재 지원하는 `tingle-policy-v2` / `r2-c-20261106` |
| settingsSnapshotId | 해당 기기 credential이 결속한 syncRunId |
| peerCredential | 정상 만남은 원본 상대 credential 357B의 정규 Base64url |
| evidence.protocolVersion | `tingle-encounter-v2` |
| evidence.payload | 전체 TER2 원문의 정규 Base64url. 임의 재구성한 증명을 넣지 않음 |

REVIEW는 peerCredential 빈 문자열을 허용할 수 있지만 실제 서명된 REVIEW 여부와 원문에 상대 credential이
없는 상태를 검증해야 한다. 정상 만남에서 빈 credential을 허용하지 않는다. 진단 자료를 받는 정책은
`review-intake-approved`로 별도 승인하며 기본 OFF다. 빈 문자열 허용만으로 접수나 삭제 허가가 성립하지 않는다.
REVIEW의 상대 원문이 비어 있지 않으면 인증된 상대 credential에 결속된 PROFILE만 지원한다.
상대 인증 없이 PROFILE을 포함하거나 귀속·형식이 확인되지 않는 원문은 POLICY_UNRESOLVED로 보류하며,
저장과 삭제 receipt 발급을 하지 않는다. 미귀속 진단 자료의 보관 정책을 임의로 승인하지 않는다.

접수와 서명 receipt는 다음 경계를 지킨다.

- 원 제출 기기의 당시 소유 구간·epoch·credential을 검증하고 원문을 영속 저장한다.
- 동결 credential의 키 폐기 시각 이후 증명은 거부한다. 이미 양도된 기기라는 이유로 과거 정상 소유 구간을
  현재 소유 값으로 교체하지 않는다.
- REVIEW는 정상 만남과 구분한다(현재는 접수 거절, 아래 활성화·검증 경계). 불완전/옛 LAB 증명을 정상 만남으로 승격하지 않는다.
- 같은 submission/원문 재시도와 응답 유실은 기존 receipt를 복구하고, 같은 제출 ID의 다른 원문은 충돌한다.
- 내구 저장 commit 뒤 해당 원문·기기·소유 세대에 결속된 receipt만 발급한다. boolean
  canDeleteLocalRecord만으로 기기 원본을 삭제하지 않는다.
- 원본을 저장·삭제 허가했다는 사실과 현재 매칭 제안으로 승격할 수 있다는 판단은 별도다. REVIEW 또는
  양쪽 증명 미검증 상태에서 제안을 만들지 않는다. 사용자 쌍 전역 중복/관계 정책을 기존 접수 흐름과 일관되게 적용한다.

서버는 원문 저장을 먼저 commit하고 그 뒤 해당 원문에 대한 삭제 허가 receipt를 서명한다.
서명 공급 장애로 두 번째 단계가 실패해도 이미 저장한 원문은 남으며, 동일 제출 재시도로 receipt 발급을 복구한다.
서명 receipt 없는 응답에서는 기기 원본을 삭제하지 않는다. 개인 입력 철회로 원문이 폐기된 경우에는
ID만 남은 tombstone을 새 정상 원문으로
덮어쓰거나 삭제 허가를 재발급하지 않는다.

응답의 `receiptStatus`와 `encounterStatus`를 구분한다.

| 필드·값 | 의미 |
| --- | --- |
| receiptStatus=STORED | 내구 접수와 서명된 삭제 허가를 반환함. 제안 승인과 다름 |
| WAITING_COUNTERPART | 한쪽 원본을 접수했으며 상대의 독립 원본을 기다림 |
| VERIFIED_BOTH | 양쪽 독립 원본과 당시 credential·프로필을 서로 대조함 |
| REVIEW_QUARANTINED | 서명된 검토 원본을 정상 만남과 분리해 보관함 |
| BUSINESS_POLICY_PENDING | 원본과 receipt는 보존됐지만 현재 업무 정책/평가 공급이 준비되지 않음 |
| BUSINESS_ENQUEUED | 기존 사용자 쌍 접수·평가 흐름에 연결됨. 최종 제안 발행 성공과 다름 |

현재 업무 공급이 미준비면 `receiptStatus=STORED`와 기존 serverProof를 유지하면서
encounterStatus=BUSINESS_POLICY_PENDING, errorCode=POLICY_UNRESOLVED를 반환할 수 있다.
공급 장애는 해당 오류 코드와 재시도 가능 여부로 구분하며 정책 미준비를 제안 성공으로 바꾸지 않는다.
`serverProof`는 서명 receipt 객체이며 기존 구형 응답에는 null을 출력하지 않고 필드를 생략한다.
기기는 응답의 boolean canDeleteLocalRecord 대신 정확한 서명 receipt를 검증한 뒤 해당 원본만 삭제한다.

## 원본 접수 뒤 복구·관계 억제 해제 명령

| 동작 | 실제 경로 | 응답 |
| --- | --- | --- |
| 명령 발급 | POST `/api/keyrings/{deviceId}/record-commands` | 서명된 명령200 |
| 응답 유실 복구 | GET `/api/keyrings/{deviceId}/record-commands/{requestKey}` | 현재 조건에 유효한 동일 명령200 |

POST는 `Idempotency-Key` UUID와 receiptId·command를 요구한다. command는 RESTORE_ACCOUNT_LOCK,
RELEASE_AFTER_DEPARTURE, ERROR_REVIEWED 중 하나다. 현재 로그인·기기 소유·generation/epoch·credential,
DB에 등록된 XIAO 기기·서버 공개키 pin·초기화 guard와 원 접수의 폐기 여부를 다시 확인한다.
과거 소유자의 원본 receipt를 새 소유자의 제어 명령으로 바꾸지 않는다. 같은 요청 키에 다른 receiptId/command는409다.

- RESTORE_ACCOUNT_LOCK은 당시 영속 접수한 상대 credential 357B를 그대로 사용한다. 현재 상대 소유자나
  현재 프로필에서 과거 메타데이터를 추정하지 않는다. 허가 발급 시점으로부터300초 범위만 사용하며 동일 키
  재시도/GET은 발급 시각과 grantId·서명을 고정한다. 만료 후에는 새 요청 키가 필요하다.
- RELEASE_AFTER_DEPARTURE는 양쪽 원본의 VERIFIED_BOTH와 해당 만남
  시각의 실제 NOT_MATCHED 관계가 있어야 하며 제안·참여자 응답/차단·채팅·현재 사용자 차단이 없어야 한다.
  서버 허가와 별개로 기기는 원본 접수와 해당 상대 소유 세대의 이탈 관측 조건을 확인한다.
- ERROR_REVIEWED는 REVIEW_QUARANTINED와 실제 별도 검토 승인 행을 요구한다. 일반 상대를 오류 fingerprint로
  취급해 이탈 조건을 우회하지 않는다. 승인 행을 만드는 외부 검토 주체·운영 절차는 추가 합의 대상이며,
  공개 요청만으로 검토 승인을 만들지 않는다. 기본 상태는 허가 없음이다.
- 입력 철회로 원본/credential/receipt가 폐기되면 이미 발급했던 명령의 재조회도 허용하지 않는다. 기기의 원본 삭제
  ACK와 서버의 반복 억제 해제는 서로 다른 동작이다.

현재 응답은 여러 명령이 공유하는 Command DTO다. RESTORE 응답에는 receivedAtEpochSeconds와 result가 null이고,
RELEASE/ERROR 응답에는 ownerRefHex·grantId·issuedAtEpochSeconds·peerCredential이 null이다.
FE는 이 null 필드까지 그대로 BLE 명령에 넣지 않고, 해당 proofProtocolVersion의 필수 필드만 중계해야 한다.
정확한 원문과 정상/오류 검증 벡터는 아래 실행 fixture 및 Jira의 계약 원본을 따른다.

## 활성화·검증 경계

2026-11-17(S15P31E106-407)부터 TSV3 설정·소유 제어·TER2 접수·업무 연결·release 발급은 개발·운영 모두 늘 켜져 있다.
켜고 끄는 `app.keyring.peer.*` 승인 스위치는 없다. 정책 값(`policy-version`·`rule-version`·`lease-seconds`·
`opportunity-seconds`·`presence-gap-seconds`·`server-public-key-hex`)은 필수이며 비었거나 펌웨어 값과 다르면 서버가 기동하지 않는다.

- REVIEW(검토) 접수는 모든 환경에서 POLICY_UNRESOLVED로 거절한다. 검토 승인 행을 만드는 판정 도구가 생기면 연다.
  그전에는 REVIEW_QUARANTINED가 새로 생기지 않는다.

peer 테이블은 엔티티가 아니라 JPA가 만들거나 검사하지 않는다. 서버는 기동할 때 표가 있는지 확인하고 없으면 멈춘다.
개발·운영 DB 반영본은 `infra/db/prod-changes/2026-11-17-env-parity-407.sql`(이전 `2026-11-06-peer-flow-270.sql` 포함)이며
재실행해도 안전하고 기존 정의를 교정하거나 과거 소유를 추정하지 않는다.
설계 SQL은 `back/src/main/resources/database/`의 keyring-peer-ownership-270.sql,
keyring-peer-commands-270.sql, keyring-peer-encounter-270.sql 세 파일이다(local·test는 시작할 때 실행).
입력 철회의 원문·credential·hash·receipt 정리는 접수 결과와 관계없이 수행한다.
선택 테이블이 아예 없는 기존 배포에서는 테이블 존재를 확인한 뒤 해당 정리를 생략한다.

TSV3 공급에는 DB에 등록된 XIAO 기기 + 폐기되지 않은 credential, 펌웨어에 고정한 서버 공개키와 일치하는 전용 서명 공급,
승인한 policy/rule·lease/opportunity/presence 값 및 실제 사용자 입력이 필요하다.
미승인 정책·미확정 수명을 정상 매칭 설정으로 보정하지 않는다.
서명 키 loader는 dev/test와 함께 prod에서도 같은 개발 서명 키를 읽는다(11-11 결정 B, 펌웨어 고정 공개키 유지).
키는 설정한 파일에서만 읽고 임의 키 생성·기본 키는 계속 금지한다. 운영에서도 1바이트 ON/OFF·TPS1 발급은 열지 않는다.
실서비스로 가려면 운영 전용 키를 새로 만들고 그 공개키로 펌웨어를 다시 빌드해야 한다.

정확한 바이너리·서명 원문은 이 기능 명세에 복사하지 않는다. 관련 Jira에서 외부 인계 기준을 연결하고
실행하는 공개 합성 fixture인 `iot/tests/app-flow-v3-vectors.json`, `app-flow-owner-vectors.json`과
`back/src/test/resources/keyring/`의 검증 fixture를 대조한다. 이 자료는 팀 서버의 실제 키·사용자 데이터가 아니다.

로컬 단위/격리 PostgreSQL·합성 키 검증, 필수 CI·검토/병합, 공유 DB 반영, 실제 서버 배포/활성화,
실물 196B/TSV3 저장 왕복·RF 만남·TER2 업로드/삭제·초기화·양도 회귀를 각각 다른 증거로 기록한다.
앞 단계의 통과로 뒤 단계의 성공을 대신하지 않는다. 이번 Jira의 완료 판단은 사용자가 확정한 최신 API 구현과
관련 검증 충족 여부를 기준으로 하며, MR 병합·서버 배포/활성화·실기기 시험은 별도 상태와 근거로 보고한다.
이 명세의 개발 브랜치 설명만으로 BE270 완료나 병합·배포·실물 성공을 선언하지 않는다.
