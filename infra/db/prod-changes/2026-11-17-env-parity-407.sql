-- 개발·운영 DB 보완 (2026-11-17, S15P31E106-407): 스위치 제거로 TSV3·키링 초기화가 개발·운영 모두 늘 켜진다.
-- 아래 표는 엔티티가 아니라(JDBC 전용) ddl-auto(update·validate)가 만들지도 검사하지도 않는다. 없으면 요청이 500이 되므로
-- 앱이 기동할 때 표·칼럼을 확인하고 없으면 멈춘다(RequiredSchemaCheck). 배포 전에 먼저 실행한다.
-- 실행 대상: 운영 DB tingle 과 개발 DB tingle_dev 둘 다. (개발은 KEYRING_RESET_DEV_ENABLED 기본값이 false 라 keyring_reset_run 이 없을 수 있다.)
-- 이 파일 하나로 끝나게 앞선 파일(247-keyring-server-proofs, 2026-11-06-keyring-ownership-epoch, 2026-11-06-peer-flow-270)과
-- 같은 DDL, keyring-reset-249 를 모두 담았다. 이미 있는 것은 건너뛴다(IF NOT EXISTS). 다시 실행해도 안전.
-- 표·칼럼 추가만 하므로 운영 중인 앱에 영향 없음.
BEGIN;

-- 엔티티 칼럼(247 · 2026-11-06-keyring-ownership-epoch 와 같음)
ALTER TABLE keyring_sync_run ADD COLUMN IF NOT EXISTS server_proofs jsonb;
ALTER TABLE keyring ADD COLUMN IF NOT EXISTS ownership_epoch bigint;
ALTER TABLE keyring_sync_run ADD COLUMN IF NOT EXISTS ownership_epoch bigint;

-- 키링 초기화 (back/src/main/resources/database/keyring-reset-249.sql)
CREATE TABLE IF NOT EXISTS keyring_reset_run (
    run_id uuid primary key,
    keyring_id bigint not null,
    owner_user_id bigint not null,
    owner_generation uuid not null,
    request_key uuid not null,
    discard_unsent boolean not null,
    nonce varchar(43) not null,
    command text not null,
    created_at timestamptz not null,
    expires_at timestamptz not null,
    from_ownership_epoch bigint not null,
    challenge_id uuid,
    device_nonce varchar(43),
    request_hash varchar(64) not null,
    challenge_hash varchar(64) not null,
    proof_hash varchar(64),
    completed_at timestamptz,
    constraint uq_keyring_reset_generation unique(keyring_id, owner_generation),
    constraint uq_keyring_reset_request unique(owner_user_id, keyring_id, request_key),
    constraint ck_keyring_reset_complete check ((completed_at is null) = (proof_hash is null))
);

-- TSV3 소유·소유 세션·기록 명령·만남 증거 (2026-11-06-peer-flow-270.sql 과 같음)
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
