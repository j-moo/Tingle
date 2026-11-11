# 키링 구매 안내와 본인 기기 조회

## 현재 176 이관 완료 범위 · 2026-10-28

!92의 실행 코드 `35166ea` 기준으로 구매 안내·본인 키링 조회, 푸시 설치 등록·해제 및 기존 로그아웃 연결 해제를 새 22표·176컬럼·36논리관계에 연결했다. 숫자 `deviceId`, 현재 소유/REGISTERED와 유효 설정 ACK의 투영, `boundAt` 비노출, 푸시 `binding_id`·`linked_at` 보존을 실제 저장/HTTP 회귀와 전체 앱 clean build 422개에서 확인했다. Checkstyle·패키징도 통과했다. 세부 증거는 [전체 실행 검증](../../verification/erd22-storage-175.md)과 [176 최신 체크포인트](../../verification/keyring-push-176.md)를 따른다.

이 완료 범위는 70의 새 전체 연결 검증 전 !92 로컬 코드 단위다. 사람의 교차 승인·MR 병합, Notion 일치, 기존 원문 serial/owner/ACK 자료의 실제 이관, 공유 DB·실제 기기/FCM·배포는 별도다. 아래 2026-10-22·경로만 이관하던 시점의 UUID/binding 설명과 미완료 표시는 당시 이력이며 현재 조회 구현을 뜻하지 않는다.

등록67/100·동기화68/69의 최종 소유 증명/저장 ACK 규격은 별도 확정본이 없다는 사용자 확인을 반영한다. 현재 `DeviceProof`의 `protocolVersion/payload`만으로 실제 검증 규칙이 확정된 것은 아니다. 기기 키 발급·검증키 대응, 서명/MAC 알고리즘과 정확한 바이트/인코딩, 등록 nonce·회원·serial 결합, ACK의 소유세대/run/version/payloadHash 결합 및 단회 소비·당시 payload의 영속 위치가 필요하다. 현재 22표에는 이 정의가 없으며 새 API·칼럼이나 임의 증명을 추가하지 않았다.

## 2026-10-28 저장 이관 단위 · S15P31E106-175

현재 목록의 `deviceId`는 `keyring.id`의 양수 int64 숫자다. DTO는 deviceId/deviceSerial/status/firmwareVersion 네 필드이며 검증 자격·owner·추정 등록 시각을 반환하지 않는다. `deviceSerial`(333)은 본인 키링의 일련번호로, 앱이 연결한 BLE 기기를 내 목록과 대조하는 데 쓴다. 남의 키링 일련번호는 반환하지 않는다. 조회는 현재 본인 소유 `REGISTERED` 행만 읽고 숫자 ID 오름차순으로 반환한다. 구매 안내도 같은 현재 소유/상태를 기준으로 한다.

기기별 READY는 양수의 현재 `user_setting.settings_version`과 저장된 `keyring.synced_settings_version`이 같은 경우다. 설정 또는 ACK가 있으나 현재 버전이 아니면 SYNC_PENDING, 둘 다 없으면 REGISTERED다. 타인·해제 기기·과거/미래/음수 ACK나 synced_at 시각을 현재 확인으로 추정하지 않는다. 회원 전체 준비 여부와 BLE 온라인 상태를 대신하지 않는다.

이 checkout에는 등록/challenge/sync endpoint 구현이 처음부터 없었으며 이번 단위도 그 기능을 추가하지 않는다. 구 serial_hash에서 원래 serial_number를 역산하지 않는다. 기존 DB의 verified serial/소유·ACK 증거를 확인한 명시적 이관 자료가 없으면 자동 복사/삭제를 하지 않고 사전 차단해야 한다. 공유 DB SQL 적용은 없다. 아래 176 경로 단위 및 2026-10-22 UUID/binding 설명은 이전 이력이다. [175 검증 기록](../../verification/erd22-storage-175.md).

## 이전 경로 이관 단위 · 2026-10-28 S15P31E106-176

현재 구현 경로는 `GET /api/keyrings/purchase-info`·`GET /api/keyrings`다. 이전 `/api/v1` 경로는 별칭으로 제공하지 않는다. 기존 인증·세션·KEYRING_REQUIRED 가입 검사·no-store와 응답 동작을 유지한다. 등록 challenge·등록·동기화 API는 해당 이슈 범위다.

176·178 사용자 결정에 따라 응답의 `boundAt`은 제거했다. 현재 응답 필드는 `deviceId`·`status`·`firmwareVersion` 3개이며 `syncedAt`이나 현재 시각으로 등록 시각을 대신 만들지 않는다. keyring ERD에는 등록 시각 칸을 추가하지 않았다. 아래 2026-10-22의 UUID·boundAt·binding/snapshot 저장 설명은 과거 이력이다. ERD21 `keyring.id` 숫자 ID·`owner_user_id`·`state`·`synced_settings_version`과 소유 증명·ACK 연동의 조회/JPA 이관은 아직 남아 있다.

FE 적용은 서버/클라이언트 경로를 함께 전환한다. 배포 전 숫자 ID·상태 DTO 및 실제 DB 이관까지 별도로 확인해야 하며 현재 개발 브랜치의 경로 이관을 서비스 완료로 표시하지 않는다. [176번 검증·남은 작업](../../verification/keyring-push-176.md).

## 이전 구현 계약 · 2026-10-22

- 버전: 0.2.0-local, 2026-10-22. 담당 김영빈(BE), S15P31E106-66.
- 기준: [OpenAPI](openapi.json)의 `purchaseInfo`, `listKeyrings`, `PurchaseInfo`, `Keyring`, `Keyrings`.
- 영향: FE 가입·키링 화면, IoT 기기 등록·설정 동기화. 기존 요청·응답 형식을 유지한다. 서버 구현·로컬 검증과 실제 기기·공유 DB·배포 적용은 구분한다.

## 구매 안내

`GET /api/keyrings/purchase-info`는 현재 회원의 Bearer JWT가 필요하다. 서명·만료·현재 세션·계정 상태를 기존 인증 필터로 확인한다. 필수 약관에 동의한 `KEYRING_REQUIRED` 단계부터 허용하며, 기기 등록을 마쳤다고 프로필·설문까지 완료할 필요는 없다.

```json
{"purchaseUrl": "https://example.test/keyring", "registrationRequired": true}
```

- `KEYRING_PURCHASE_URL`은 사용자 인증 정보가 없는 절대 HTTP(S) 주소를 설정한다. 위 주소는 합성 테스트 예시이며 실제 판매처를 확정하지 않는다. 미설정·공백은 `purchaseUrl:null`이다. 잘못된 주소는 서버 시작 시 거부한다.
- `registrationRequired`는 본인에게 현재 연결된 실제 기기의 존재 여부로 판단한다. 타인 기기·해제 이력·실제 기기가 없는 논리 참조는 등록 완료의 근거가 아니다.
- 응답은 기존 두 필드만 포함하며 `Cache-Control:no-store`를 보낸다. 내부 기기 번호·시리얼 해시·검증 키 참조는 포함하지 않는다.
- 인증 없음·만료·폐기·정지 계정은 기존 401 계약, 필수 약관 미동의는 403 `ONBOARDING_REQUIRED`다. 구매 안내 조회는 등록·가입 완료 상태를 변경하지 않는다.

## 본인 기기 목록

`GET /api/keyrings`에도 동일한 인증·가입 단계·no-store 규칙을 적용한다. 회원 번호나 기기 UUID를 요청에 지정해 조회 주체를 바꿀 수 없다. 본인의 `unbound_at IS NULL` 연결과 실제 기기만 결합하며, 미등록·해제한 기기만 있으면 `{"items":[]}`다.

```json
{"items":[{"deviceId":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","status":"REGISTERED","boundAt":"2026-10-22T00:00:00Z","firmwareVersion":"synthetic-1"}]}
```

- 응답은 `deviceId`, `status`, `boundAt`, `firmwareVersion` 네 필드만 포함한다. 기기 자격 정보·내부 숫자 ID·회원 번호·소유 연결 번호는 반환하지 않는다. 연결 시각 내림차순, 동률은 공개 UUID 순서다.
- `deviceId`는 사용자 결정에 따라 기존 UUID 계약을 유지하며 새 `keyrings.public_device_id`에 대응한다. 신규 엔티티를 만들 때 서버가 UUID v4를 발급하고 DB의 NOT NULL·UNIQUE 제약으로 보호한다. 재조회·해제/재연결에서 바꾸지 않는다. `device_id`와 `binding_id` bigint는 내부 참조로 유지한다.
- 기존 `ix_keyring_bindings_1` 부분 유일 인덱스가 기기당 활성 소유 연결을 하나로 제한한다. 공개 UUID를 아는 것만으로 다른 회원의 목록을 조회하거나 소유권을 바꿀 수 없다. 목록 조회는 어떤 등록·연결도 생성하지 않는다.
- `REGISTERED`: 활성 등록은 있지만 설정·동기화 기록이 아직 없다. `SYNC_PENDING`: 설정 또는 동기화 기록이 있으나 현재 소유 연결·현재 회원 설정 버전의 저장 ACK가 없다. `READY`: 해당 연결에 현재 회원·현재 설정 버전 스냅샷의 ACKNOWLEDGED와 확인 시각이 있다. 타인 스냅샷·이전 소유 연결·구버전 ACK는 준비 완료로 표시하지 않는다.
- 이 상태는 저장된 동기화 기록이며 BLE 연결 여부나 회원 전체 매칭 자격을 대신하지 않는다. 온라인 관측 정보가 없으므로 `OFFLINE`을 임의 추정해 반환하지 않는다. 실제 기기 증명과 ACK 검증·등록/동기화 실행은 67·69·100번 등의 별도 구현 범위다.

## 적용과 검토

공유 DB에 기존 기기 행이 있으면 공개 UUID 컬럼 추가 → 기존 행별 UUID를 한 번 발급하고 중복·누락 검사 → NOT NULL·UNIQUE 적용 → 새 서버 적용 순서로 준비한다. 기존 내부 ID·소유 연결·동기화 참조는 바꾸지 않는다. JPA update에 기존 행의 UUID 발급을 맡기지 않으며, 설계 SQL은 기존 DB에 실행하는 마이그레이션이 아니다. 이번 작업은 공유 DB나 웹 ERD를 변경하지 않았다.

FE는 기존 UUID 필드·nullable 구매 주소·기기별 상태를 사용한다. 향후 등록 기능도 같은 공개 UUID를 생성·조회하도록 연결하고, 인증·FE·IoT 영향 검토를 거친다. 구매·결제·기기 소유 증명·등록·동기화·배포는 이 조회 API의 성공으로 완료되지 않는다.

검증 기록: [66번 구현·검증](../../verification/keyring-read-66.md).

2026-10-22 후속 웹 대조: 요청받은 채팅·설치·로그아웃8개와 키링2개 API의 노션 현재 계약·저장 검증을 완료했다. 이 기록은 다른 API 전체나 서버/공용 DB/단말 검증으로 확대하지 않는다. [현재 통합 검증](../../verification/mr29-unified-integration-20261022.md)과 [10개 API 증거](../../verification/notion-api-ten-20261022.json)를 따른다.

## ERD 21개 테이블 대조 (2026-10-27, 가을)

2026-10-27 ERD 수정본(21개 테이블) 기준으로 이 문서와 다른 점이다. 위 본문은 당시 구현 기록으로 두고, 아래가 현재 계약에 반영한 내용이다. 경로의 `/v1`은 뺐다.

- `deviceId`는 `keyring.id`(자동 증가 숫자)다. ERD에 공개 UUID 칸(`public_device_id`)과 소유 이력 테이블(`keyring_bindings`)이 없어서 위 "기존 UUID 계약 유지" 결정을 그대로 지킬 수 없다. OpenAPI의 `Keyring.deviceId`·경로 `{deviceId}`를 숫자로 바꿨고, FE·IoT 확인이 필요하다.
- 본인 기기 목록은 `keyring.owner_user_id = 본인`이고 `state = REGISTERED`인 행이다. 한 회원이 여러 개 가질 수 있다.
- `boundAt`을 채울 칸이 없다(`bound_at` 삭제). 응답 필드를 유지할지, 빼거나 다른 값으로 대신할지 합의가 필요하다.
- `status`: `REGISTERED`는 `synced_settings_version`이 비어 있음, `SYNC_PENDING`은 `synced_settings_version ≠ user_setting.settings_version`, `READY`는 둘이 같음. 동기화 실행 기록(`keyring_sync_runs`)·설정 사본은 ERD에 없다.
- 등록 challenge 저장 칸이 없다. 버튼 누름 등록 방식으로 바꿀지 IoT·영빈 담당 확인 전이다. 키링 등록 해제 API는 아직 명세에 없다.
- 소유가 바뀔 때 만남 기록 처리와 설정 반영 규칙은 [인증 명세의 ERD 차이 절](auth.md#erd-21개-테이블-기준-저장-위치와-차이)을 따른다.
