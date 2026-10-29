-- Additive SQL for an existing canonical 25-table / 223-column database.
-- Manual review and explicit application required; preserves existing rows and primary request keys.
-- Unknown payload provenance and request aliases remain NULL. No inferred backfill.
BEGIN;
ALTER TABLE "keyring_sync_run" ADD COLUMN "payload_format" varchar(64);
COMMENT ON COLUMN "keyring_sync_run"."payload_format" IS '실행 생성 시 실제 전송 바이트와 함께 고정한 payload 형식 식별자. 이후 형식으로 교체하지 않으며 기존 미상 출처는 NULL로 보존한다. 서버 입력 증거의 형식으로 추정 대체하지 않는다.';
ALTER TABLE "keyring_sync_run" ADD COLUMN "request_key_aliases" jsonb;
COMMENT ON COLUMN "keyring_sync_run"."request_key_aliases" IS '같은 기기·소유 세대·설정 버전의 불변 실행에 추가로 대응시킨 Idempotency-Key의 UUID 문자열 배열(List<String>). 최초 request_key는 유지하고 기기 잠금 아래 검증한 키만 추가한다. 기존 미상 값은 NULL이며 빈 배열과 구분한다.';
COMMIT;
