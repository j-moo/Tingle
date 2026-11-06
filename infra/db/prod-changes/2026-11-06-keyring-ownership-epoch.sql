-- 운영 적용: 2026-11-06 (S15P31E106-259 에서 기록)
-- 운영 DB 보완 (2026-11-06): 키링 초기화 서명·소유 epoch(S15P31E106-249)이 추가한 칸. 빈 값 허용 칸 추가만 하므로 운영 중인 앱에 영향 없음.
-- 다시 실행해도 안전(IF NOT EXISTS).
BEGIN;

ALTER TABLE keyring ADD COLUMN IF NOT EXISTS ownership_epoch bigint;
COMMENT ON COLUMN keyring.ownership_epoch IS '기기 서명으로 확인한 소유 카운터. 기존 미확인 값은 NULL로 유지하며 임의 backfill하지 않고 재등록 시 보존한다.';

ALTER TABLE keyring_sync_run ADD COLUMN IF NOT EXISTS ownership_epoch bigint;
COMMENT ON COLUMN keyring_sync_run.ownership_epoch IS '이 실행에 고정한 서명 v2 기기 카운터. 기존 v1 실행은 NULL이며 소급 변환하지 않는다.';

COMMIT;
