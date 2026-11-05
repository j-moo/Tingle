CREATE TYPE matching_status AS ENUM ('WAITING','NOT_MATCHED','PENDING','ONE_ACCEPTED','ACCEPTED','REJECTED','EXPIRED','CLOSED');
CREATE TYPE proposal_response AS ENUM ('ACCEPTED','REJECTED');
CREATE TABLE user_pairs (
    pair_id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    user_low bigint NOT NULL,
    user_high bigint NOT NULL,
    declined_until timestamptz,
    declined_proposal_id bigint,
    rematch_banned_at timestamptz,
    CONSTRAINT pk_user_pairs PRIMARY KEY (pair_id),
    CONSTRAINT uq_user_pairs_1 UNIQUE (user_low, user_high),
    CONSTRAINT ck_user_pairs_1 CHECK (user_low<user_high),
    CONSTRAINT ck_user_pairs_2 CHECK ((declined_until IS NULL)=(declined_proposal_id IS NULL))
);
CREATE TABLE encounters (
    encounter_id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    pair_id bigint NOT NULL,
    event_key text NOT NULL,
    occurred_at timestamptz NOT NULL,
    opportunity_expires_at timestamptz NOT NULL,
    counterpart_deadline_at timestamptz NOT NULL,
    status text NOT NULL,
    evaluation_version_code text,
    evaluation_json jsonb,
    CONSTRAINT pk_encounters PRIMARY KEY (encounter_id),
    CONSTRAINT uq_encounters_1 UNIQUE (pair_id, event_key),
    CONSTRAINT ck_encounters_1 CHECK (counterpart_deadline_at<=opportunity_expires_at),
    CONSTRAINT ck_encounters_2 CHECK ((evaluation_json IS NULL)=(evaluation_version_code IS NULL)),
    CONSTRAINT ck_encounters_3 CHECK (evaluation_json IS NULL OR (jsonb_typeof(evaluation_json)='object'))
);
CREATE TABLE encounter_submissions (
    receipt_id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    encounter_id bigint NOT NULL,
    binding_id bigint NOT NULL,
    snapshot_id bigint NOT NULL,
    submission_id uuid NOT NULL,
    proof_json jsonb NOT NULL,
    received_at timestamptz NOT NULL,
    CONSTRAINT pk_encounter_submissions PRIMARY KEY (receipt_id),
    CONSTRAINT uq_encounter_submissions_1 UNIQUE (binding_id, submission_id),
    CONSTRAINT uq_encounter_submissions_2 UNIQUE (encounter_id, binding_id),
    CONSTRAINT ck_encounter_submissions_1 CHECK (jsonb_typeof(proof_json)='object')
);
CREATE TABLE match_proposals (
    proposal_id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    pair_id bigint NOT NULL,
    encounter_id bigint NOT NULL,
    low_decision text,
    high_decision text,
    low_decided_at timestamptz,
    high_decided_at timestamptz,
    low_request_key uuid,
    high_request_key uuid,
    low_cancelled_at timestamptz,
    high_cancelled_at timestamptz,
    low_cancel_request_key uuid,
    high_cancel_request_key uuid,
    status text NOT NULL,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    chat_room_id bigint,
    CONSTRAINT pk_match_proposals PRIMARY KEY (proposal_id),
    CONSTRAINT uq_match_proposals_1 UNIQUE (encounter_id),
    CONSTRAINT ck_match_proposals_1 CHECK (low_decision IS NULL OR low_decision IN ('ACCEPT','DECLINE')),
    CONSTRAINT ck_match_proposals_2 CHECK (high_decision IS NULL OR high_decision IN ('ACCEPT','DECLINE')),
    CONSTRAINT ck_match_proposals_3 CHECK ((low_decision IS NULL)=(low_decided_at IS NULL)),
    CONSTRAINT ck_match_proposals_4 CHECK ((high_decision IS NULL)=(high_decided_at IS NULL)),
    CONSTRAINT ck_match_proposals_5 CHECK (status IN ('PENDING','WAITING_FOR_OTHER','ACCEPTED','REJECTED','EXPIRED','CLOSED')),
    CONSTRAINT ck_match_proposals_6 CHECK (expires_at=created_at+interval '24 hours'),
    CONSTRAINT ck_match_proposals_7 CHECK ((low_decision IS NULL)=(low_request_key IS NULL)),
    CONSTRAINT ck_match_proposals_8 CHECK ((high_decision IS NULL)=(high_request_key IS NULL)),
    CONSTRAINT ck_match_proposals_9 CHECK ((low_cancelled_at IS NULL)=(low_cancel_request_key IS NULL)),
    CONSTRAINT ck_match_proposals_10 CHECK ((high_cancelled_at IS NULL)=(high_cancel_request_key IS NULL)),
    CONSTRAINT ck_match_proposals_11 CHECK (low_cancelled_at IS NULL OR (low_decision='ACCEPT' AND low_cancelled_at>=low_decided_at)),
    CONSTRAINT ck_match_proposals_12 CHECK (high_cancelled_at IS NULL OR (high_decision='ACCEPT' AND high_cancelled_at>=high_decided_at))
);
CREATE TABLE user_blocks (
    block_id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    blocker_id bigint NOT NULL,
    blocked_id bigint NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT pk_user_blocks PRIMARY KEY (block_id),
    CONSTRAINT uq_user_blocks_1 UNIQUE (blocker_id, blocked_id),
    CONSTRAINT ck_user_blocks_1 CHECK (blocker_id<>blocked_id)
);
