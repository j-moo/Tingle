-- TSV3 소유 이력 표(엔티티 아님). 개발·운영 모두 필요하다. 운영 적용본: infra/db/prod-changes/2026-11-17-env-parity-407.sql
-- No historical ownership is backfilled from mutable current keyring rows.
create table if not exists keyring_peer_owner (
    user_id bigint primary key,
    owner_ref uuid not null,
    created_at timestamptz not null,
    constraint uq_keyring_peer_owner_ref unique(owner_ref)
);

create table if not exists keyring_peer_ownership (
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

create unique index if not exists uq_keyring_peer_current on keyring_peer_ownership(keyring_id) where valid_until is null;
create index if not exists ix_keyring_peer_serial_generation on keyring_peer_ownership(device_serial, owner_generation);
create index if not exists ix_keyring_peer_user on keyring_peer_ownership(owner_user_id);
