-- Exact known 0255529 chat source tables, for a disposable migration test only.
CREATE TABLE chat_rooms (
    chat_room_id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    pair_id bigint NOT NULL,
    public_id uuid NOT NULL,
    source_proposal_id bigint NOT NULL,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    closed_at timestamptz,
    delete_at timestamptz,
    last_sequence bigint NOT NULL DEFAULT 0,
    CONSTRAINT pk_chat_rooms PRIMARY KEY (chat_room_id),
    CONSTRAINT uq_chat_rooms_public_id UNIQUE (public_id),
    CONSTRAINT uq_chat_rooms_source UNIQUE (source_proposal_id),
    CONSTRAINT ck_chat_rooms_1 CHECK (expires_at=created_at+interval '168 hours'),
    CONSTRAINT ck_chat_rooms_2 CHECK (last_sequence>=0),
    CONSTRAINT ck_chat_rooms_3 CHECK ((closed_at IS NULL AND delete_at IS NULL) OR (closed_at IS NOT NULL AND delete_at>closed_at))
);

CREATE TABLE chat_members (
    chat_room_id bigint NOT NULL,
    user_id bigint NOT NULL,
    left_at timestamptz,
    first_notice_acked_at timestamptz,
    end_notice_acked_at timestamptz,
    CONSTRAINT pk_chat_members PRIMARY KEY (chat_room_id, user_id)
);

CREATE TABLE chat_messages (
    message_id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    chat_room_id bigint NOT NULL,
    sender_id bigint NOT NULL,
    public_id uuid NOT NULL,
    request_key uuid NOT NULL,
    request_hash text NOT NULL,
    client_message_id uuid NOT NULL,
    sequence bigint NOT NULL,
    body text NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT pk_chat_messages PRIMARY KEY (message_id),
    CONSTRAINT uq_chat_messages_public_id UNIQUE (public_id),
    CONSTRAINT uq_chat_messages_request UNIQUE (sender_id, request_key),
    CONSTRAINT uq_chat_messages_1 UNIQUE (chat_room_id, sender_id, client_message_id),
    CONSTRAINT uq_chat_messages_2 UNIQUE (chat_room_id, sequence),
    CONSTRAINT ck_chat_messages_1 CHECK (sequence>0)
);

CREATE TABLE background_jobs (
    job_id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    public_id uuid,
    job_type text NOT NULL,
    business_key text NOT NULL,
    payload jsonb NOT NULL,
    status text NOT NULL,
    run_at timestamptz NOT NULL,
    attempts integer NOT NULL DEFAULT 0,
    lease_until timestamptz,
    last_error_code text,
    result_json jsonb,
    finished_at timestamptz,
    CONSTRAINT pk_background_jobs PRIMARY KEY (job_id),
    CONSTRAINT uq_background_jobs_1 UNIQUE (business_key),
    CONSTRAINT uq_background_jobs_public_id UNIQUE (public_id),
    CONSTRAINT ck_background_jobs_1 CHECK (job_type IN ('CHAT_CREATE','FIRST_ENTRY','END_NOTICE','AI','CHAT_DELETE','WITHDRAWAL','USER_DATA_DELETE')),
    CONSTRAINT ck_background_jobs_2 CHECK (status IN ('PENDING','RUNNING','SUCCEEDED','FAILED','CANCELLED')),
    CONSTRAINT ck_background_jobs_3 CHECK (attempts>=0),
    CONSTRAINT ck_background_jobs_4 CHECK ((status='RUNNING')=(lease_until IS NOT NULL)),
    CONSTRAINT ck_background_jobs_5 CHECK (jsonb_typeof(payload)='object'),
    CONSTRAINT ck_background_jobs_6 CHECK (result_json IS NULL OR (jsonb_typeof(result_json)='object'))
);

CREATE TABLE chat_message_reads (
    receipt_id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    message_id bigint NOT NULL,
    user_id bigint NOT NULL,
    read_at timestamptz NOT NULL,
    CONSTRAINT pk_chat_message_reads PRIMARY KEY (receipt_id),
    CONSTRAINT uq_chat_message_reads_1 UNIQUE (message_id, user_id)
);
