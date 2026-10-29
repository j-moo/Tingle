-- Manual review/dry-run only. NOT an automatic migration and NOT applied to shared/production DBs.
-- Legacy source: 0255529 canonical push_installations (GENERATED ALWAYS IDENTITY, text platform).
-- Before use: stop writers, back up, confirm search_path and inspect original ownership/generation data.
-- Renames preserve physical rows, identity sequence, IDs, UUIDs, encrypted bytes and AAD user associations.
-- Do not run if both source and target exist. Do not merge two copies or fabricate a binding backfill.
BEGIN;
DO $guard$
BEGIN
    IF to_regclass('push_installations') IS NULL OR to_regclass('push_installation') IS NOT NULL THEN
        RAISE EXCEPTION 'Expected only legacy push_installations; inspect schema before migration';
    END IF;
    IF EXISTS (SELECT 1 FROM push_installations
               WHERE (binding_id IS NULL) <> (linked_at IS NULL)) THEN
        RAISE EXCEPTION 'Partial connection-generation evidence requires manual review; no automatic repair';
    END IF;
    IF EXISTS (SELECT 1 FROM push_installations WHERE platform NOT IN ('ANDROID', 'IOS')) THEN
        RAISE EXCEPTION 'Unknown platform code requires manual review';
    END IF;
END;
$guard$;

DO $enum$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_type t JOIN pg_namespace n ON n.oid=t.typnamespace
                   WHERE t.typname='device_platform' AND n.nspname=current_schema()) THEN
        CREATE TYPE device_platform AS ENUM ('ANDROID', 'IOS');
    END IF;
END;
$enum$;

ALTER TABLE push_installations RENAME TO push_installation;
ALTER TABLE push_installation RENAME COLUMN installation_id TO id;
ALTER TABLE push_installation ALTER COLUMN user_id DROP NOT NULL;
-- Drop only the known legacy enum-like text CHECK before changing the column type.
ALTER TABLE push_installation DROP CONSTRAINT IF EXISTS ck_push_installations_1;
ALTER TABLE push_installation ALTER COLUMN platform TYPE device_platform USING platform::text::device_platform;
COMMIT;
