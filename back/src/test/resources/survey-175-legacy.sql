-- Disposable test fixture for the exact known legacy survey layout (0255529).
CREATE TABLE survey_responses (
    public_id uuid NOT NULL DEFAULT gen_random_uuid(),
    response_id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    user_id bigint NOT NULL,
    version_code text NOT NULL,
    client_request_id uuid NOT NULL,
    response_json jsonb NOT NULL,
    revision bigint NOT NULL DEFAULT 0,
    completed_at timestamptz,
    CONSTRAINT uq_survey_responses_public_id UNIQUE (public_id),
    CONSTRAINT pk_survey_responses PRIMARY KEY (response_id),
    CONSTRAINT uq_survey_responses_1 UNIQUE (user_id, client_request_id),
    CONSTRAINT ck_survey_responses_1 CHECK (revision >= 0),
    CONSTRAINT ck_survey_responses_2 CHECK (jsonb_typeof(response_json) = 'object')
);
