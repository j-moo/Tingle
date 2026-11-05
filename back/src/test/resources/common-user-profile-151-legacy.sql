-- Exact table subset from 0255529 canonical schema.sql; not the diagram importer snapshot.
CREATE TABLE app_users (
    user_id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    provider text NOT NULL,
    provider_subject text,
    status text NOT NULL,
    created_at timestamptz NOT NULL,
    signup_completed_at timestamptz,
    email text,
    withdrawal_due_at timestamptz,
    withdrawn_at timestamptz,
    CONSTRAINT pk_app_users PRIMARY KEY (user_id),
    CONSTRAINT uq_app_users_1 UNIQUE (provider, provider_subject),
    CONSTRAINT ck_app_users_1 CHECK (status IN ('ACTIVE','SUSPENDED','WITHDRAWAL_PENDING','WITHDRAWN')),
    CONSTRAINT ck_app_users_2 CHECK ((status='WITHDRAWAL_PENDING')=(withdrawal_due_at IS NOT NULL)),
    CONSTRAINT ck_app_users_3 CHECK (withdrawn_at IS NULL OR status='WITHDRAWN'),
    CONSTRAINT ck_app_users_4 CHECK (status='WITHDRAWN' OR provider_subject IS NOT NULL)
);
CREATE TABLE user_consents (
    consent_id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    user_id bigint NOT NULL,
    consent_type text NOT NULL,
    document_version text NOT NULL,
    agreed boolean NOT NULL,
    recorded_at timestamptz NOT NULL,
    locale text,
    consent_screen text,
    CONSTRAINT pk_user_consents PRIMARY KEY (consent_id)
);
CREATE TABLE profiles (
    user_id bigint NOT NULL,
    nickname text,
    nickname_key text,
    birth_date date,
    gender text,
    smoking_status text,
    drinking_frequency text,
    religion text,
    introduction text,
    image_url text,
    completed_at timestamptz,
    CONSTRAINT pk_profiles PRIMARY KEY (user_id),
    CONSTRAINT uq_profiles_1 UNIQUE (nickname_key),
    CONSTRAINT ck_profiles_1 CHECK ((nickname IS NULL)=(nickname_key IS NULL))
);
