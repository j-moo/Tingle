-- Manual, additive deployment prerequisite. Review and apply to the intended environment before code deployment.
-- This file does not enable withdrawal, operate an account, or delete existing chat evidence.
BEGIN;

ALTER TABLE matching ALTER COLUMN user_low_id DROP NOT NULL, ALTER COLUMN user_high_id DROP NOT NULL;
ALTER TABLE rule_audit ALTER COLUMN actor_id DROP NOT NULL;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='matching'::regclass
                   AND conname='ck_matching_withdrawn_pair') THEN
        ALTER TABLE matching ADD CONSTRAINT ck_matching_withdrawn_pair CHECK (
            (user_low_id IS NOT NULL AND user_high_id IS NOT NULL)
            OR (user_low_id IS NULL AND user_high_id IS NULL AND status='CLOSED')
        );
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS withdrawal_cooldown (
    provider varchar(16) NOT NULL,
    account_hash varchar(64) NOT NULL,
    rejoin_available_at timestamptz NOT NULL,
    PRIMARY KEY (provider, account_hash)
);
CREATE INDEX IF NOT EXISTS ix_withdrawal_cooldown_expiry ON withdrawal_cooldown(rejoin_available_at);

CREATE TABLE IF NOT EXISTS withdrawal_relation (
    first_hash varchar(64) NOT NULL,
    second_hash varchar(64) NOT NULL,
    expires_at timestamptz NOT NULL,
    PRIMARY KEY (first_hash, second_hash)
);
CREATE INDEX IF NOT EXISTS ix_withdrawal_relation_expiry ON withdrawal_relation(expires_at);

CREATE TABLE IF NOT EXISTS withdrawal_identity_key (
    id integer PRIMARY KEY,
    key_hash varchar(64) NOT NULL
);

CREATE TABLE IF NOT EXISTS withdrawal_operation (
    id uuid PRIMARY KEY,
    user_id bigint,
    session_id uuid,
    request_key uuid,
    kind varchar(10) NOT NULL,
    prepared_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    completed_at timestamptz,
    rejoin_available_at timestamptz,
    device_permanently_blocked boolean NOT NULL,
    CONSTRAINT uq_withdrawal_prepared_member UNIQUE(user_id)
);
CREATE INDEX IF NOT EXISTS ix_withdrawal_operation_expiry ON withdrawal_operation(expires_at);

CREATE TABLE IF NOT EXISTS withdrawal_photo_task (
    id uuid PRIMARY KEY,
    withdrawal_id uuid NOT NULL,
    file_key varchar(255) NOT NULL,
    attempts integer NOT NULL,
    next_attempt_at timestamptz NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_withdrawal_photo_due ON withdrawal_photo_task(next_attempt_at);
CREATE INDEX IF NOT EXISTS ix_withdrawal_photo_operation ON withdrawal_photo_task(withdrawal_id);

COMMIT;
