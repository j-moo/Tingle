# 키링 구매 안내와 본인 기기 조회

- 버전: 0.2.0-local, 2026-10-22. 담당 김영빈(BE), S15P31E106-66.
- 기준: [OpenAPI](openapi.json)의 `purchaseInfo`, `listKeyrings`, `PurchaseInfo`, `Keyring`, `Keyrings`.
- 영향: FE 가입·키링 화면, IoT 기기 등록·설정 동기화. 기존 요청·응답 형식을 유지한다. 서버 구현·로컬 검증과 실제 기기·공유 DB·배포 적용은 구분한다.

## 구매 안내

`GET /api/v1/keyrings/purchase-info`는 현재 회원의 Bearer JWT가 필요하다. 서명·만료·현재 세션·계정 상태를 기존 인증 필터로 확인한다. 필수 약관에 동의한 `KEYRING_REQUIRED` 단계부터 허용하며, 기기 등록을 마쳤다고 프로필·설문까지 완료할 필요는 없다.

```json
{"purchaseUrl": "https://example.test/keyring", "registrationRequired": true}
```

- `KEYRING_PURCHASE_URL`은 사용자 인증 정보가 없는 절대 HTTP(S) 주소를 설정한다. 위 주소는 합성 테스트 예시이며 실제 판매처를 확정하지 않는다. 미설정·공백은 `purchaseUrl:null`이다. 잘못된 주소는 서버 시작 시 거부한다.
- `registrationRequired`는 본인에게 현재 연결된 실제 기기의 존재 여부로 판단한다. 타인 기기·해제 이력·실제 기기가 없는 논리 참조는 등록 완료의 근거가 아니다.
- 응답은 기존 두 필드만 포함하며 `Cache-Control:no-store`를 보낸다. 내부 기기 번호·시리얼 해시·검증 키 참조는 포함하지 않는다.
- 인증 없음·만료·폐기·정지 계정은 기존 401 계약, 필수 약관 미동의는 403 `ONBOARDING_REQUIRED`다. 구매 안내 조회는 등록·가입 완료 상태를 변경하지 않는다.

## 본인 기기 목록

`GET /api/v1/keyrings`에도 동일한 인증·가입 단계·no-store 규칙을 적용한다. 회원 번호나 기기 UUID를 요청에 지정해 조회 주체를 바꿀 수 없다. 본인의 `unbound_at IS NULL` 연결과 실제 기기만 결합하며, 미등록·해제한 기기만 있으면 `{"items":[]}`다.

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
