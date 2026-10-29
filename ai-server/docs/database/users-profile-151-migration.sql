-- Manual review/dry-run only; NOT an automatic migration or shared/production execution.
-- Source: 0255529 canonical app_users/profiles/user_consents. Stop writers and back up first.
-- Confirm a single intended search_path schema. Do not combine existing old/new copies.
-- Existing status/created_at/withdrawal timestamps, IDs/identity and consent history stay intact.
-- nickname_key is retained as unused historical data; application uniqueness uses lower(nickname).
-- If photos exist, set tingle.profile_photo_base_url to the VERIFIED server storage prefix
-- (HTTPS, trailing slash, before profile/{userId}/), e.g. SET tingle.profile_photo_base_url='...';
-- No guessed photo allowlist, aggregate agreed_at, active personality or timestamp backfill.
BEGIN;
DO $guard$
DECLARE
    photo_base text := current_setting('tingle.profile_photo_base_url', true);
BEGIN
    IF to_regclass('app_users') IS NULL OR to_regclass('profiles') IS NULL
       OR to_regclass('user_consents') IS NULL OR to_regclass('users') IS NOT NULL
       OR to_regclass('profile') IS NOT NULL THEN
        RAISE EXCEPTION 'Expected only known legacy app_users/profiles/user_consents; inspect schema';
    END IF;
    LOCK TABLE app_users, profiles, user_consents IN ACCESS EXCLUSIVE MODE;
    IF EXISTS (SELECT 1 FROM app_users WHERE provider NOT IN ('GOOGLE','KAKAO','NAVER')
               OR status NOT IN ('ACTIVE','SUSPENDED','WITHDRAWAL_PENDING','WITHDRAWN')) THEN
        RAISE EXCEPTION 'Unknown provider or account state; no automatic substitution';
    END IF;
    IF EXISTS (SELECT 1 FROM app_users WHERE provider_subject IS NULL OR char_length(provider_subject)>255
               OR char_length(email)>320) THEN
        RAISE EXCEPTION 'Legacy account does not fit required identity/length; no guessed value or truncation';
    END IF;
    IF EXISTS (SELECT 1 FROM profiles WHERE nickname IS NOT NULL AND
               (nickname !~ '^[가-힣a-zA-Z0-9 ]{2,20}$' OR nickname<>btrim(nickname,' ')
                OR nickname<>normalize(nickname,NFC))) THEN
        RAISE EXCEPTION 'Legacy nickname violates canonical format/length; preserve source for review';
    END IF;
    IF EXISTS (SELECT 1 FROM profiles WHERE nickname IS NOT NULL
               GROUP BY lower(nickname) HAVING count(*)>1) THEN
        RAISE EXCEPTION 'Duplicate lower(nickname); no automatic rename';
    END IF;
    IF EXISTS (SELECT 1 FROM profiles WHERE
               (gender IS NOT NULL AND gender NOT IN ('MALE','FEMALE')) OR
               (smoking_status IS NOT NULL AND smoking_status NOT IN
                ('NON_SMOKER','E_CIGARETTE','ONLY_WHEN_DRINKING','SMOKER')) OR
               (drinking_frequency IS NOT NULL AND drinking_frequency NOT IN
                ('NEVER','SPECIAL_OCCASIONS_ONLY','MONTHLY_AT_MOST_ONCE','MONTHLY_TWO_TO_FOUR',
                 'WEEKLY_TWO_TO_THREE','WEEKLY_FOUR_OR_MORE')) OR
               (religion IS NOT NULL AND religion NOT IN
                ('NONE','PROTESTANT','CATHOLIC','BUDDHIST','OTHER','UNDISCLOSED'))) THEN
        RAISE EXCEPTION 'Unknown profile enum; no automatic conversion';
    END IF;
    IF EXISTS (SELECT 1 FROM profiles WHERE image_url IS NOT NULL) THEN
        IF photo_base IS NULL OR photo_base NOT LIKE 'https://%' OR right(photo_base,1)<>'/'
           OR position('?' in photo_base)>0 THEN
            RAISE EXCEPTION 'Existing photos require verified server storage prefix before migration';
        END IF;
        IF EXISTS (SELECT 1 FROM profiles WHERE image_url IS NOT NULL AND
                   (char_length(image_url)>2048 OR position('?' in image_url)>0 OR
                    left(image_url,char_length(photo_base||'profile/'||user_id||'/'))
                    <>photo_base||'profile/'||user_id||'/')) THEN
            RAISE EXCEPTION 'Existing photo URL violates canonical storage/owner/length; preserve source';
        END IF;
    END IF;
END;
$guard$;
DO $types$
BEGIN
    IF to_regtype('auth_provider') IS NULL THEN
        CREATE TYPE auth_provider AS ENUM ('GOOGLE','KAKAO','NAVER');
    END IF;
    IF to_regtype('gender') IS NULL THEN
        CREATE TYPE gender AS ENUM ('MALE','FEMALE');
    END IF;
    IF to_regtype('smoking_status') IS NULL THEN
        CREATE TYPE smoking_status AS ENUM ('NON_SMOKER','E_CIGARETTE','ONLY_WHEN_DRINKING','SMOKER');
    END IF;
    IF to_regtype('drinking_frequency') IS NULL THEN
        CREATE TYPE drinking_frequency AS ENUM ('NEVER','SPECIAL_OCCASIONS_ONLY','MONTHLY_AT_MOST_ONCE',
            'MONTHLY_TWO_TO_FOUR','WEEKLY_TWO_TO_THREE','WEEKLY_FOUR_OR_MORE');
    END IF;
    IF to_regtype('religion') IS NULL THEN
        CREATE TYPE religion AS ENUM ('NONE','PROTESTANT','CATHOLIC','BUDDHIST','OTHER','UNDISCLOSED');
    END IF;
END;
$types$;
ALTER TABLE app_users RENAME TO users;
ALTER TABLE users RENAME COLUMN user_id TO id;
ALTER TABLE users RENAME COLUMN provider_subject TO provider_user_id;
ALTER TABLE users ALTER COLUMN provider TYPE auth_provider USING provider::text::auth_provider;
ALTER TABLE users ALTER COLUMN provider_user_id TYPE varchar(255);
ALTER TABLE users ALTER COLUMN provider_user_id SET NOT NULL;
ALTER TABLE users ALTER COLUMN email TYPE varchar(320);
ALTER TABLE users ADD COLUMN agreed_at timestamptz;
ALTER TABLE users ADD COLUMN is_operator boolean NOT NULL DEFAULT false;
ALTER TABLE profiles RENAME TO profile;
ALTER TABLE profile RENAME COLUMN image_url TO photo_url;
-- The old nickname/key null-pair constraint cannot constrain a field the new application no longer writes.
ALTER TABLE profile DROP CONSTRAINT ck_profiles_1;
ALTER TABLE profile ALTER COLUMN nickname TYPE varchar(20);
ALTER TABLE profile ALTER COLUMN photo_url TYPE varchar(2048);
ALTER TABLE profile ALTER COLUMN gender TYPE gender USING gender::text::gender;
ALTER TABLE profile ALTER COLUMN smoking_status TYPE smoking_status USING smoking_status::text::smoking_status;
ALTER TABLE profile ALTER COLUMN drinking_frequency TYPE drinking_frequency
    USING drinking_frequency::text::drinking_frequency;
ALTER TABLE profile ALTER COLUMN religion TYPE religion USING religion::text::religion;
ALTER TABLE profile ADD COLUMN active_personality_id bigint;
ALTER TABLE profile ADD CONSTRAINT ck_profile_photo_url
    CHECK (photo_url IS NULL OR (photo_url LIKE 'https://%' AND position('?' in photo_url)=0));
CREATE UNIQUE INDEX uq_profile_nickname_lower ON profile(lower(nickname));
-- user_consents is deliberately not collapsed, rewritten or dropped.
COMMIT;
