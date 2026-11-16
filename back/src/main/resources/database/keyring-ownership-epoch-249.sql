-- 엔티티 칼럼(Keyring·KeyringSyncRun.ownership_epoch). 운영 적용본: infra/db/prod-changes/2026-11-17-env-parity-407.sql
-- Existing unknown ownership counters remain NULL. Do not infer or backfill them from owner UUIDs.
alter table keyring add column if not exists ownership_epoch bigint;
alter table keyring_sync_run add column if not exists ownership_epoch bigint;
