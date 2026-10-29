-- Additive SQL for an existing canonical 22-table database.
-- Manual review and explicit application required; never recreates existing tables.
-- Existing unknown values remain NULL. No inferred backfill.
BEGIN;
ALTER TABLE "keyring" ADD COLUMN "credential_key_id" varchar(64);
COMMENT ON COLUMN "keyring"."credential_key_id" IS '등록 시 서버가 serial에 대응시킨 기기 검증키 식별자. 원문 개인키는 서버에 저장하지 않으며 기존 미상 행은 NULL.';
ALTER TABLE "keyring" ADD COLUMN "credential_public_key" varchar(128);
COMMENT ON COLUMN "keyring"."credential_public_key" IS '사전 등록한 Ed25519 공개키의 X.509 SPKI 인코딩. 요청에서 받은 공개키를 신뢰하지 않으며 기존 미상 행은 NULL.';
ALTER TABLE "keyring" ADD COLUMN "credential_revoked_at" timestamptz;
COMMENT ON COLUMN "keyring"."credential_revoked_at" IS '이 검증 자격이 폐기된 실제 시각. NULL은 폐기 기록 없음이며 누락된 키의 유효성을 추정하지 않는다.';
ALTER TABLE "keyring" ADD COLUMN "owner_generation" uuid;
COMMENT ON COLUMN "keyring"."owner_generation" IS '현재 소유 등록 세대. 새로 검증한 등록에서만 발급하며 기존 소유자의 미상 세대를 임의 UUID로 채우지 않는다.';

-- 회원별 등록 증명 challenge와 단회 소비·최초 등록 결과. 기기 미지정 발급과 병렬 요청을 보존한다.
CREATE TABLE "registration_challenge" (
    "id" bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    "challenge_id" uuid NOT NULL,
    "user_id" bigint NOT NULL,
    "request_key" uuid NOT NULL,
    "nonce" varchar(43) NOT NULL,
    "protocol_version" varchar(32) NOT NULL,
    "issued_at" timestamptz NOT NULL,
    "expires_at" timestamptz NOT NULL,
    "consumed_at" timestamptz,
    "registered_keyring_id" bigint,
    "registered_owner_generation" uuid,
    "registered_key_id" varchar(64),
    "registration_proof_hash" varchar(64),
    "registration_request_key" uuid,
    "registration_request_hash" varchar(64),
    "registered_firmware_version" varchar(30),
    "registered_status" text,
    "revision" bigint NOT NULL,
    CONSTRAINT "pk_registration_challenge" PRIMARY KEY ("id")
);
COMMENT ON TABLE "registration_challenge" IS '회원별 등록 증명 challenge와 단회 소비·최초 등록 결과. 기기 미지정 발급과 병렬 요청을 보존한다.';
COMMENT ON COLUMN "registration_challenge"."id" IS '등록 challenge 내부 ID';
COMMENT ON COLUMN "registration_challenge"."challenge_id" IS '서버가 발급한 공개 challenge ID. 같은 등록 요청의 재시도는 같은 ID를 사용한다.';
COMMENT ON COLUMN "registration_challenge"."user_id" IS 'challenge를 발급받은 본인 회원 ID';
COMMENT ON COLUMN "registration_challenge"."request_key" IS '회원 범위 등록 challenge 발급 Idempotency-Key';
COMMENT ON COLUMN "registration_challenge"."nonce" IS '기기 등록 서명에 포함하는 32바이트 canonical unpadded base64url nonce. 회원 귀속·단회 소비와 함께 검증한다.';
COMMENT ON COLUMN "registration_challenge"."protocol_version" IS '발급 시 고정한 등록 증명 규격. tingle-device-proof-v1';
COMMENT ON COLUMN "registration_challenge"."issued_at" IS '실제 서버 Clock의 최초 발급 시각';
COMMENT ON COLUMN "registration_challenge"."expires_at" IS '최초 발급 시각부터 5분인 만료 시각. 재시도로 연장하지 않는다.';
COMMENT ON COLUMN "registration_challenge"."consumed_at" IS '검증된 등록과 같은 원자 처리에서 단회 소비한 실제 서버 시각';
COMMENT ON COLUMN "registration_challenge"."registered_keyring_id" IS '최초 성공한 등록 기기. 소비 전에는 NULL';
COMMENT ON COLUMN "registration_challenge"."registered_owner_generation" IS '최초 성공한 소유 세대. 같은 등록 재시도의 결과 검증에 사용하며 새로운 세대로 바꾸지 않는다.';
COMMENT ON COLUMN "registration_challenge"."registered_key_id" IS '등록 성공 시 검증한 서버 대응 검증키 식별자';
COMMENT ON COLUMN "registration_challenge"."registration_proof_hash" IS '등록에 성공한 증명 바이트의 SHA-256 소문자 hex. 상이한 재시도 판정용';
COMMENT ON COLUMN "registration_challenge"."registration_request_key" IS '회원별 등록 API 전체 범위에서 최초 성공 요청에 고정한 Idempotency-Key. challenge 발급 키와 구분하며 기존 미상 값은 NULL.';
COMMENT ON COLUMN "registration_challenge"."registration_request_hash" IS '최초 등록 요청 본문 비교용 SHA-256 소문자 hex. 같은 키의 다른 본문을 거부하며 기존 요청을 추정해 채우지 않는다.';
COMMENT ON COLUMN "registration_challenge"."registered_firmware_version" IS '최초 등록 성공 응답의 firmwareVersion. 재시도 시 현재 기기 값 대신 원래 응답을 사용하며 기존 미상 값은 NULL.';
COMMENT ON COLUMN "registration_challenge"."registered_status" IS '최초 등록 성공 응답의 REGISTERED 또는 SYNC_PENDING 상태. 이후 동기화 상태로 원래 응답을 덮어쓰지 않으며 기존 미상 값은 NULL.';
COMMENT ON COLUMN "registration_challenge"."revision" IS 'JPA 낙관 잠금 버전. 생성자가 0부터 설정하며 DB default로 생성하지 않는다.';
CREATE UNIQUE INDEX "uq_registration_challenge_public" ON "registration_challenge" ("challenge_id");
CREATE UNIQUE INDEX "uq_registration_challenge_request" ON "registration_challenge" ("user_id", "request_key");
CREATE UNIQUE INDEX "uq_registration_challenge_registration_request" ON "registration_challenge" ("user_id", "registration_request_key");
CREATE INDEX "ix_registration_challenge_expiry" ON "registration_challenge" ("expires_at");

-- 기기·소유 세대·설정 버전에 고정한 동기화 실행과 불변 payload 및 저장 ACK 증거. 복수 실행의 이력을 덮어쓰지 않는다.
CREATE TABLE "keyring_sync_run" (
    "id" bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    "sync_run_id" uuid NOT NULL,
    "keyring_id" bigint NOT NULL,
    "owner_user_id" bigint NOT NULL,
    "owner_generation" uuid NOT NULL,
    "credential_key_id" varchar(64) NOT NULL,
    "protocol_version" varchar(32) NOT NULL,
    "settings_version" bigint NOT NULL,
    "payload_format" varchar(64),
    "payload" bytea NOT NULL,
    "payload_hash" varchar(64) NOT NULL,
    "nonce" varchar(43) NOT NULL,
    "created_at" timestamptz NOT NULL,
    "state" varchar(16) NOT NULL,
    "acked_at" timestamptz,
    "ack_proof_hash" varchar(64),
    "request_key" uuid NOT NULL,
    "request_key_aliases" jsonb,
    "ack_request_key" uuid,
    "revision" bigint NOT NULL,
    CONSTRAINT "pk_keyring_sync_run" PRIMARY KEY ("id")
);
COMMENT ON TABLE "keyring_sync_run" IS '기기·소유 세대·설정 버전에 고정한 동기화 실행과 불변 payload 및 저장 ACK 증거. 복수 실행의 이력을 덮어쓰지 않는다.';
COMMENT ON COLUMN "keyring_sync_run"."id" IS '동기화 실행 내부 ID';
COMMENT ON COLUMN "keyring_sync_run"."sync_run_id" IS '서버가 발급한 불변 공개 실행 ID';
COMMENT ON COLUMN "keyring_sync_run"."keyring_id" IS '동기화 대상 숫자 keyring.id';
COMMENT ON COLUMN "keyring_sync_run"."owner_user_id" IS '실행 생성 당시 소유 회원 ID';
COMMENT ON COLUMN "keyring_sync_run"."owner_generation" IS '실행 생성 당시 소유 세대. 재등록 이전 ACK의 새 소유 반영을 금지한다.';
COMMENT ON COLUMN "keyring_sync_run"."credential_key_id" IS '실행에 고정한 서버 대응 기기 검증키 식별자';
COMMENT ON COLUMN "keyring_sync_run"."protocol_version" IS '실행 당시 증명 규격. tingle-device-proof-v1';
COMMENT ON COLUMN "keyring_sync_run"."settings_version" IS '실행에 고정한 양수 설정 버전. ACK 시 현재 설정과 함께 재검증한다.';
COMMENT ON COLUMN "keyring_sync_run"."payload_format" IS '실행 생성 시 실제 전송 바이트와 함께 고정한 payload 형식 식별자. 이후 형식으로 교체하지 않으며 기존 미상 출처는 NULL로 보존한다. 서버 입력 증거의 형식으로 추정 대체하지 않는다.';
COMMENT ON COLUMN "keyring_sync_run"."payload" IS '실제로 전달하는 당시 payload의 불변 원본 바이트. 현재 설정이나 임의 JSON 재직렬화로 대체하지 않는다.';
COMMENT ON COLUMN "keyring_sync_run"."payload_hash" IS '불변 payload 원본의 SHA-256 소문자 hex';
COMMENT ON COLUMN "keyring_sync_run"."nonce" IS '이 실행에 고정한 32바이트 canonical unpadded base64url nonce';
COMMENT ON COLUMN "keyring_sync_run"."created_at" IS '실제 서버 Clock의 최초 실행 생성 시각';
COMMENT ON COLUMN "keyring_sync_run"."state" IS '동기화 영속 상태 PENDING / ACKED / FAILED. HTTP 응답 투영은 별도 연동';
COMMENT ON COLUMN "keyring_sync_run"."acked_at" IS '저장 증명·소유 세대·실행·현재 버전 검사 후 최초 확정한 ACK 시각';
COMMENT ON COLUMN "keyring_sync_run"."ack_proof_hash" IS '최초 수용한 저장 증명 바이트 SHA-256 소문자 hex. 중복·상이한 ACK 판정용';
COMMENT ON COLUMN "keyring_sync_run"."request_key" IS '기기·소유 세대 범위 실행 생성 Idempotency-Key';
COMMENT ON COLUMN "keyring_sync_run"."request_key_aliases" IS '같은 기기·소유 세대·설정 버전의 불변 실행에 추가로 대응시킨 Idempotency-Key의 UUID 문자열 배열(List<String>). 최초 request_key는 유지하고 기기 잠금 아래 검증한 키만 추가한다. 기존 미상 값은 NULL이며 빈 배열과 구분한다.';
COMMENT ON COLUMN "keyring_sync_run"."ack_request_key" IS '최초 수용한 저장 ACK Idempotency-Key';
COMMENT ON COLUMN "keyring_sync_run"."revision" IS 'JPA 낙관 잠금 버전. 생성자가 0부터 설정하며 DB default로 생성하지 않는다.';
CREATE UNIQUE INDEX "uq_keyring_sync_run_public" ON "keyring_sync_run" ("sync_run_id");
CREATE UNIQUE INDEX "uq_keyring_sync_run_version" ON "keyring_sync_run" ("keyring_id", "owner_generation", "settings_version");
CREATE UNIQUE INDEX "uq_keyring_sync_run_request" ON "keyring_sync_run" ("keyring_id", "owner_generation", "request_key");
CREATE INDEX "ix_keyring_sync_run_owner" ON "keyring_sync_run" ("owner_user_id", "keyring_id");

COMMIT;
