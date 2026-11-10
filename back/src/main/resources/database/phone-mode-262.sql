create table if not exists phone_mode_device (
    id uuid primary key,
    user_id bigint not null,
    installation_id uuid not null,
    active boolean not null
);
create unique index if not exists phone_mode_one_active_device on phone_mode_device(user_id) where active;

create table if not exists phone_mode_encounter (
    id uuid primary key,
    exchange_hash varchar(64) not null unique,
    met_at timestamptz not null,
    expires_at timestamptz not null,
    confirmed_at timestamptz
);

create table if not exists phone_mode_token (
    id uuid primary key,
    device_id uuid not null references phone_mode_device(id),
    token_hash varchar(64) not null unique,
    expires_at timestamptz not null,
    encounter_id uuid references phone_mode_encounter(id)
);

create table if not exists phone_mode_submission (
    encounter_id uuid not null references phone_mode_encounter(id),
    user_id bigint not null,
    peer_user_id bigint not null,
    primary key (encounter_id, user_id)
);

create table if not exists phone_mode_evaluation (
    encounter_id uuid primary key references phone_mode_encounter(id),
    status varchar(32) not null check (status = 'UNAVAILABLE'),
    reason varchar(64) not null,
    created_at timestamptz not null
);

create table if not exists phone_mode_request (
    user_id bigint not null,
    operation varchar(16) not null,
    request_key uuid not null,
    request_hash varchar(64) not null,
    result_id uuid not null,
    expires_at timestamptz not null,
    primary key (user_id, operation, request_key)
);

create table if not exists phone_mode_evaluation_input (
    encounter_id uuid not null references phone_mode_evaluation(encounter_id),
    user_id bigint not null,
    snapshot_id bigint not null,
    settings_version bigint not null,
    primary key (encounter_id, user_id)
);
