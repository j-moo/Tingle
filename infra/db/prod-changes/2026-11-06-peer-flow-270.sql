-- BE270 peer-flow schema (required before deploy). Manual review/apply only, before the prod deploy.
-- Additive and rerunnable. Existing tables are not rewritten or historical ownership backfilled.
-- Verify existing table definitions before applying. No automatic startup or CI application.
BEGIN;
-- Peer-flow schema (required before deploy). Explicit review/application is required before the prod deploy.
-- No historical ownership is backfilled from mutable current keyring rows.
CREATE TABLE IF NOT EXISTS keyring_peer_owner (
    user_id bigint primary key,
    owner_ref uuid not null,
    created_at timestamptz not null,
    constraint uq_keyring_peer_owner_ref unique(owner_ref)
);

CREATE TABLE IF NOT EXISTS keyring_peer_ownership (
    keyring_id bigint not null,
    owner_generation uuid not null,
    ownership_epoch bigint not null,
    owner_user_id bigint not null,
    owner_ref uuid not null,
    device_serial varchar(64) not null,
    credential_key_id varchar(64) not null,
    credential_public_key varchar(128) not null,
    valid_from timestamptz not null,
    valid_until timestamptz,
    primary key(keyring_id, owner_generation),
    constraint uq_keyring_peer_epoch unique(keyring_id, ownership_epoch),
    constraint ck_keyring_peer_epoch check (ownership_epoch >= 0),
    constraint ck_keyring_peer_interval check (valid_until is null or valid_until >= valid_from)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_keyring_peer_current on keyring_peer_ownership(keyring_id) where valid_until is null;
CREATE INDEX IF NOT EXISTS ix_keyring_peer_serial_generation on keyring_peer_ownership(device_serial, owner_generation);
CREATE INDEX IF NOT EXISTS ix_keyring_peer_user on keyring_peer_ownership(owner_user_id);

-- Manual review/apply only. No application startup execution or shared DB changes.
CREATE TABLE IF NOT EXISTS keyring_peer_owner_grant (
    user_id bigint NOT NULL,
    keyring_id bigint NOT NULL,
    request_key uuid NOT NULL,
    family_id uuid NOT NULL,
    owner_generation uuid NOT NULL,
    ownership_epoch bigint NOT NULL CHECK (ownership_epoch >= 0),
    credential_public_key varchar(128) NOT NULL,
    request_hash varchar(64) NOT NULL,
    response_json text NOT NULL,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    PRIMARY KEY (user_id, keyring_id, request_key),
    CHECK (expires_at > created_at AND expires_at <= created_at + interval '300 seconds')
);

-- Stores only retry identifiers/times. Peer credentials and signatures are rebuilt from non-retired evidence.
CREATE TABLE IF NOT EXISTS keyring_peer_record_command (
    user_id bigint NOT NULL,
    keyring_id bigint NOT NULL,
    request_key uuid NOT NULL,
    command_kind varchar(32) NOT NULL,
    receipt_id uuid NOT NULL,
    grant_id uuid NOT NULL UNIQUE,
    owner_generation uuid NOT NULL,
    ownership_epoch bigint NOT NULL,
    signer_key_id varchar(64) NOT NULL,
    issued_at timestamptz NOT NULL,
    PRIMARY KEY (user_id, keyring_id, request_key)
);

-- No public API inserts review approval. An authorized review process must supply this decision separately.
CREATE TABLE IF NOT EXISTS keyring_peer_review_resolution (
    receipt_id uuid PRIMARY KEY,
    reviewed_at timestamptz NOT NULL,
    disposition varchar(32) NOT NULL
);

-- Optional peer evidence store. Apply explicitly before enabling peer encounter intake.
-- Nullable evidence columns permit consent retirement without reusing an old event/submission identity.
CREATE TABLE IF NOT EXISTS keyring_peer_encounter (
    receipt_id uuid primary key,
    owner_user_id bigint not null,
    peer_user_id bigint,
    device_id bigint not null,
    owner_generation uuid not null,
    ownership_epoch bigint not null,
    event_id uuid not null,
    submission_id uuid not null,
    received_at timestamptz not null,
    occurred_at timestamptz not null,
    expires_at timestamptz not null,
    encounter_status varchar(32) not null,
    raw_payload bytea,
    envelope_hash varchar(64),
    peer_credential bytea,
    receipt_json text,
    retired boolean not null default false,
    constraint uq_keyring_peer_submission unique(device_id, submission_id),
    constraint uq_keyring_peer_event unique(device_id, owner_generation, event_id),
    constraint ck_keyring_peer_encounter_epoch check(ownership_epoch >= 0)
);
CREATE INDEX IF NOT EXISTS ix_keyring_peer_encounter_pair on keyring_peer_encounter(event_id,owner_user_id,peer_user_id);
CREATE INDEX IF NOT EXISTS ix_keyring_peer_encounter_owner on keyring_peer_encounter(owner_user_id,peer_user_id);

COMMIT;
