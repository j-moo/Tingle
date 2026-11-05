-- Additive SQL for an existing canonical 24-table database.
-- Manual review and explicit application required; preserves existing rows and unknown NULL values.
-- PostgreSQL default NULL-distinct uniqueness permits multiple legacy NULL request keys.
BEGIN;
ALTER TABLE "registration_challenge" ADD COLUMN "registration_request_key" uuid;
COMMENT ON COLUMN "registration_challenge"."registration_request_key" IS '회원별 등록 API 전체 범위에서 최초 성공 요청에 고정한 Idempotency-Key. challenge 발급 키와 구분하며 기존 미상 값은 NULL.';
ALTER TABLE "registration_challenge" ADD COLUMN "registration_request_hash" varchar(64);
COMMENT ON COLUMN "registration_challenge"."registration_request_hash" IS '최초 등록 요청 본문 비교용 SHA-256 소문자 hex. 같은 키의 다른 본문을 거부하며 기존 요청을 추정해 채우지 않는다.';
ALTER TABLE "registration_challenge" ADD COLUMN "registered_firmware_version" varchar(30);
COMMENT ON COLUMN "registration_challenge"."registered_firmware_version" IS '최초 등록 성공 응답의 firmwareVersion. 재시도 시 현재 기기 값 대신 원래 응답을 사용하며 기존 미상 값은 NULL.';
ALTER TABLE "registration_challenge" ADD COLUMN "registered_status" text;
COMMENT ON COLUMN "registration_challenge"."registered_status" IS '최초 등록 성공 응답의 REGISTERED 또는 SYNC_PENDING 상태. 이후 동기화 상태로 원래 응답을 덮어쓰지 않으며 기존 미상 값은 NULL.';
CREATE UNIQUE INDEX "uq_registration_challenge_registration_request" ON "registration_challenge" ("user_id", "registration_request_key");
COMMIT;
