-- TSV3 소유 세션·기록 명령 표(엔티티 아님). 개발·운영 모두 필요하다. 운영 적용본: infra/db/prod-changes/2026-11-17-env-parity-407.sql
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
