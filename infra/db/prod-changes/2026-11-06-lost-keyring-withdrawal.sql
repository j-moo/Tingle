-- Apply only after 2026-11-06-member-withdrawal.sql. Manual review/application required.
BEGIN;
ALTER TABLE keyring ADD COLUMN IF NOT EXISTS permanently_blocked_at timestamptz;
create table if not exists lost_reauthentication (
    id uuid primary key,
    user_id bigint not null,
    keyring_id bigint not null,
    owner_generation uuid not null,
    session_id uuid not null,
    request_key uuid not null,
    request_hash varchar(64) not null,
    verified_at timestamptz not null,
    expires_at timestamptz not null,
    constraint uq_lost_reauthentication_request unique(user_id,request_key)
);
create index if not exists ix_lost_reauthentication_expiry on lost_reauthentication(expires_at);

create table if not exists lost_withdrawal_request (
    case_id bigint primary key,
    user_id bigint not null,
    request_id uuid not null,
    request_hash varchar(64) not null,
    withdrawal_id uuid not null,
    keyring_id bigint not null,
    owner_generation uuid not null,
    session_id uuid not null,
    reauthentication_id uuid not null,
    reauthenticated_at timestamptz not null,
    expires_at timestamptz not null,
    reviewed_at timestamptz,
    confirmed_at timestamptz,
    info_request_id bigint,
    info_delivered_at timestamptz,
    constraint uq_lost_case_request unique(user_id,request_id),
    constraint uq_lost_case_withdrawal unique(withdrawal_id)
);
create index if not exists ix_lost_case_member on lost_withdrawal_request(user_id);

create table if not exists lost_case_command (
    id uuid primary key,
    case_id bigint not null,
    request_key uuid not null,
    request_hash varchar(64) not null,
    created_at timestamptz not null,
    constraint uq_lost_case_command unique(case_id,request_key)
);
create index if not exists ix_lost_case_command_case on lost_case_command(case_id);

COMMIT;
