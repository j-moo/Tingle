-- Candidate migration. Explicit review/application required; not automatically executed at startup.
create table keyring_reset_run (
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
