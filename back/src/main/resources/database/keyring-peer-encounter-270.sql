-- TSV3 만남 증거 표(엔티티 아님). 개발·운영 모두 필요하다. 운영 적용본: infra/db/prod-changes/2026-11-17-env-parity-407.sql
-- Nullable evidence columns permit consent retirement without reusing an old event/submission identity.
create table if not exists keyring_peer_encounter (
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
create index if not exists ix_keyring_peer_encounter_pair on keyring_peer_encounter(event_id,owner_user_id,peer_user_id);
create index if not exists ix_keyring_peer_encounter_owner on keyring_peer_encounter(owner_user_id,peer_user_id);
