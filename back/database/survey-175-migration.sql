-- Manual review candidate for the known 0255529 survey_responses layout only.
-- Not an automatic migration; not applied to a shared/production database.
-- The shipped SIS-Q-1.1-100 catalog has Q001..Q100. Unknown versions/questions,
-- composite answers, or narrowing conflicts require source investigation first.
-- Historical answer times and historical definition hashes are unknown: retain NULL.
BEGIN;
SET LOCAL search_path TO public, pg_temp;

DO $$
BEGIN
    IF to_regclass('public.survey_responses') IS NULL
        OR to_regclass('public.survey') IS NOT NULL
        OR to_regclass('public.survey_answer') IS NOT NULL THEN
        RAISE EXCEPTION 'Expected only the known legacy survey source, without a target table';
    END IF;
    LOCK TABLE public.survey_responses IN ACCESS EXCLUSIVE MODE;
    IF EXISTS (SELECT 1 FROM survey_responses WHERE version_code <> 'SIS-Q-1.1-100'
        OR char_length(version_code) > 30 OR revision < 0) THEN
        RAISE EXCEPTION 'Unknown legacy survey version or invalid revision';
    END IF;
    IF EXISTS (SELECT 1 FROM survey_responses WHERE jsonb_typeof(response_json) <> 'object'
        OR NOT response_json ? 'answers' OR jsonb_typeof(response_json -> 'answers') <> 'object') THEN
        RAISE EXCEPTION 'Legacy answers must be an explicit object';
    END IF;
    IF EXISTS (SELECT 1 FROM survey_responses s, LATERAL jsonb_each(s.response_json -> 'answers') a
        WHERE a.key !~ '^Q(00[1-9]|0[1-9][0-9]|100)$' OR char_length(a.key) > 50
        OR jsonb_typeof(a.value) NOT IN ('string', 'number', 'boolean', 'null')
        OR char_length(CASE WHEN jsonb_typeof(a.value) = 'string' THEN a.value #>> '{}'
            ELSE a.value::text END) > 100) THEN
        RAISE EXCEPTION 'Unknown question, composite answer, or answer outside the storage limit';
    END IF;
    IF EXISTS (SELECT 1 FROM survey_responses WHERE completed_at IS NULL
        GROUP BY user_id, version_code HAVING count(*) > 1) THEN
        RAISE EXCEPTION 'Several open drafts cannot be silently merged';
    END IF;
END $$;

ALTER TABLE survey_responses RENAME TO survey;
ALTER TABLE survey RENAME COLUMN response_id TO id;
ALTER TABLE survey RENAME COLUMN public_id TO legacy_public_id;
ALTER TABLE survey RENAME COLUMN version_code TO survey_version;
ALTER TABLE survey RENAME COLUMN response_json TO response_context;
ALTER TABLE survey ALTER COLUMN survey_version TYPE varchar(30);
ALTER TABLE survey ALTER COLUMN legacy_public_id DROP NOT NULL;
ALTER TABLE survey ALTER COLUMN legacy_public_id DROP DEFAULT;
ALTER TABLE survey ALTER COLUMN client_request_id DROP NOT NULL;
ALTER TABLE survey ALTER COLUMN response_context DROP NOT NULL;
ALTER TABLE survey ADD COLUMN definition_snapshot jsonb;
ALTER TABLE survey RENAME CONSTRAINT pk_survey_responses TO pk_survey;
ALTER TABLE survey RENAME CONSTRAINT uq_survey_responses_public_id TO uq_survey_legacy_public_id;
ALTER TABLE survey RENAME CONSTRAINT uq_survey_responses_1 TO uq_survey_user_request;

CREATE TABLE survey_answer (
    survey_id bigint NOT NULL,
    question_id varchar(50) NOT NULL,
    answer_value varchar(100) NOT NULL,
    answer_type text,
    answered_at timestamptz,
    CONSTRAINT pk_survey_answer PRIMARY KEY (survey_id, question_id)
);

INSERT INTO survey_answer (survey_id, question_id, answer_value, answer_type, answered_at)
SELECT s.id, a.key,
    CASE WHEN jsonb_typeof(a.value) = 'string' THEN a.value #>> '{}' ELSE a.value::text END,
    upper(jsonb_typeof(a.value)), NULL
FROM survey s, LATERAL jsonb_each(s.response_context -> 'answers') a;

-- Move, rather than copy, the original answers. All other root fields remain unchanged.
UPDATE survey SET response_context = response_context - 'answers';
CREATE UNIQUE INDEX uq_survey_draft ON survey (user_id, survey_version) WHERE completed_at IS NULL;

COMMIT;
