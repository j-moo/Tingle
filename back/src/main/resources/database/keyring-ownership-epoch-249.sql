-- Explicitly reviewed additive DDL only; NEVER automatically run against a shared database.
-- Existing unknown ownership counters remain NULL. Do not infer or backfill them from owner UUIDs.
alter table keyring add column if not exists ownership_epoch bigint;
alter table keyring_sync_run add column if not exists ownership_epoch bigint;
