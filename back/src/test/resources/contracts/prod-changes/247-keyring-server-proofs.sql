-- Additive schema prerequisite only. Does not enable development settings signing.
ALTER TABLE keyring_sync_run ADD COLUMN IF NOT EXISTS server_proofs jsonb;
