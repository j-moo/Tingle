-- Manual review/dry-run only. Never automatically applied to shared/production DBs.
-- Source: known legacy canonical refresh_tokens(token_id, ...), seven columns.
-- Stop writers, back up and inspect search_path/table shape before approved execution.
-- Rename in place: preserve identity high-water, hash bytes, user/family and lifecycle timestamps.
-- No guessed device_name/last_used_at backfill. Existing unknown metadata remains NULL.
BEGIN;
DO $guard$
BEGIN
    IF to_regclass('refresh_tokens') IS NULL OR to_regclass('refresh_token') IS NOT NULL THEN
        RAISE EXCEPTION 'Expected only legacy refresh_tokens; inspect schema before migration';
    END IF;
END;
$guard$;
ALTER TABLE refresh_tokens RENAME TO refresh_token;
ALTER TABLE refresh_token RENAME COLUMN token_id TO id;
ALTER TABLE refresh_token ADD COLUMN device_name varchar(100);
ALTER TABLE refresh_token ADD COLUMN last_used_at timestamptz;
COMMIT;
