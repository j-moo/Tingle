CREATE TYPE auth_provider AS ENUM ('GOOGLE', 'KAKAO', 'NAVER');
CREATE TYPE gender AS ENUM ('MALE', 'FEMALE');
CREATE TYPE smoking_status AS ENUM ('NON_SMOKER', 'E_CIGARETTE', 'ONLY_WHEN_DRINKING', 'SMOKER');
CREATE TYPE drinking_frequency AS ENUM ('NEVER', 'SPECIAL_OCCASIONS_ONLY', 'MONTHLY_AT_MOST_ONCE', 'MONTHLY_TWO_TO_FOUR', 'WEEKLY_TWO_TO_THREE', 'WEEKLY_FOUR_OR_MORE');
CREATE TYPE religion AS ENUM ('NONE', 'PROTESTANT', 'CATHOLIC', 'BUDDHIST', 'OTHER', 'UNDISCLOSED');
CREATE TABLE users (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    provider auth_provider NOT NULL,
    provider_user_id varchar(255) NOT NULL,
    email varchar(320),
    signup_completed_at timestamptz,
    agreed_at timestamptz,
    is_operator boolean NOT NULL DEFAULT false,
    status text NOT NULL,
    created_at timestamptz NOT NULL
);
CREATE UNIQUE INDEX uq_users_provider_account ON users(provider, provider_user_id);
CREATE TABLE profile (
    user_id bigint PRIMARY KEY,
    nickname varchar(20),
    birth_date date,
    gender gender,
    smoking_status smoking_status,
    drinking_frequency drinking_frequency,
    religion religion,
    introduction text,
    photo_url varchar(2048),
    completed_at timestamptz,
    active_personality_id bigint,
    CONSTRAINT ck_profile_photo_url CHECK (photo_url IS NULL OR photo_url ~ '^profile/[0-9]+/[0-9a-f-]{36}\.jpg$')
);
CREATE UNIQUE INDEX uq_profile_nickname_lower ON profile(lower(nickname));
