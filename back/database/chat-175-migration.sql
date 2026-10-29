-- Manual review candidate: known 0255529 chat sources, after verified matching migration.
-- Stop old application workers before applying. Not automatic; no shared DB execution.
-- Refuse unsupported jobs/receipts instead of discarding them to meet a table count.
BEGIN;
SET LOCAL search_path TO public, pg_temp;

DO $$
BEGIN
    IF to_regclass('public.chat_rooms') IS NULL OR to_regclass('public.chat_members') IS NULL
        OR to_regclass('public.chat_messages') IS NULL OR to_regclass('public.chat_message_reads') IS NULL
        OR to_regclass('public.background_jobs') IS NULL OR to_regclass('public.matching') IS NULL
        OR to_regclass('public.chat') IS NOT NULL OR to_regclass('public.chat_participant') IS NOT NULL
        OR to_regclass('public.chat_message') IS NOT NULL THEN
        RAISE EXCEPTION 'Expected known legacy chat sources and verified canonical matching only';
    END IF;
    LOCK TABLE public.chat_rooms, public.chat_members, public.chat_messages,
        public.chat_message_reads, public.background_jobs IN ACCESS EXCLUSIVE MODE;
    LOCK TABLE public.matching IN SHARE MODE;
    IF EXISTS (SELECT 1 FROM chat_rooms r LEFT JOIN matching m ON m.id=r.source_proposal_id
        WHERE m.id IS NULL OR m.legacy_state->'pair'->>'pair_id' IS DISTINCT FROM r.pair_id::text
        OR m.legacy_state->'proposal'->>'proposal_id' IS DISTINCT FROM r.source_proposal_id::text) THEN
        RAISE EXCEPTION 'Original relationship/proposal mapping evidence is missing';
    END IF;
    IF EXISTS (SELECT 1 FROM chat_rooms r JOIN matching m ON m.id=r.source_proposal_id
        WHERE (SELECT count(*) FROM chat_members p WHERE p.chat_room_id=r.chat_room_id)<>2
        OR NOT EXISTS (SELECT 1 FROM chat_members p WHERE p.chat_room_id=r.chat_room_id AND p.user_id=m.user_low_id)
        OR NOT EXISTS (SELECT 1 FROM chat_members p WHERE p.chat_room_id=r.chat_room_id AND p.user_id=m.user_high_id))
        OR EXISTS (SELECT 1 FROM chat_members p LEFT JOIN chat_rooms r ON r.chat_room_id=p.chat_room_id
            WHERE r.chat_room_id IS NULL) THEN
        RAISE EXCEPTION 'Chat membership cannot be safely mapped to the canonical relationship';
    END IF;
    IF EXISTS (SELECT 1 FROM chat_messages m WHERE m.request_hash !~ '^[0-9a-fA-F]{64}$'
        OR NOT EXISTS (SELECT 1 FROM chat_members p
            WHERE p.chat_room_id=m.chat_room_id AND p.user_id=m.sender_id)) THEN
        RAISE EXCEPTION 'Message sender or original SHA256 evidence is invalid';
    END IF;
    IF EXISTS (SELECT 1 FROM chat_message_reads a LEFT JOIN chat_messages m ON m.message_id=a.message_id
        WHERE m.message_id IS NULL OR a.user_id=m.sender_id
        OR NOT EXISTS (SELECT 1 FROM chat_members p
            WHERE p.chat_room_id=m.chat_room_id AND p.user_id=a.user_id)) THEN
        RAISE EXCEPTION 'A read receipt is not owned by a receiving participant';
    END IF;
    IF EXISTS (SELECT 1 FROM background_jobs j WHERE j.job_type NOT IN ('FIRST_ENTRY','END_NOTICE')
        OR j.public_id IS NULL OR jsonb_typeof(j.payload)<>'object'
        OR j.payload->>'roomId' !~ '^[1-9][0-9]*$' OR j.payload->>'userId' !~ '^[1-9][0-9]*$'
        OR NOT j.payload ? 'roomId' OR NOT j.payload ? 'userId'
        OR j.status NOT IN ('PENDING','RUNNING','SUCCEEDED','FAILED','CANCELLED') OR j.attempts<0
        OR (j.status='RUNNING') IS DISTINCT FROM (j.lease_until IS NOT NULL)) THEN
        RAISE EXCEPTION 'Unsupported job scope or missing notice identity: preserve the original job source';
    END IF;
    IF EXISTS (SELECT 1 FROM background_jobs j WHERE NOT EXISTS (SELECT 1 FROM chat_members p
        WHERE p.chat_room_id=(j.payload->>'roomId')::bigint AND p.user_id=(j.payload->>'userId')::bigint)
        OR j.business_key <> 'chat-notice:' || (j.payload->>'roomId') || ':'
            || (j.payload->>'userId') || ':' || j.job_type) THEN
        RAISE EXCEPTION 'Notice ownership/business key is not proved';
    END IF;
    IF EXISTS (SELECT 1 FROM background_jobs j, LATERAL jsonb_object_keys(j.payload) k
        WHERE k NOT IN ('roomId','userId','entryKey','ackKey')) THEN
        RAISE EXCEPTION 'Unknown notice payload must be investigated before migration';
    END IF;
    IF EXISTS (SELECT 1 FROM background_jobs j, LATERAL jsonb_each(j.payload) p
        WHERE p.key IN ('entryKey','ackKey') AND (jsonb_typeof(p.value) IS DISTINCT FROM 'string'
            OR p.value #>> '{}' !~ '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$')) THEN
        RAISE EXCEPTION 'Optional notice request evidence must be an actual UUID, not an inferred missing key';
    END IF;
    IF EXISTS (SELECT 1 FROM background_jobs j WHERE j.result_json IS NOT NULL
        AND jsonb_typeof(j.result_json)<>'object') THEN
        RAISE EXCEPTION 'Notice delivery receipts must be explicit binding-keyed objects';
    END IF;
    IF EXISTS (SELECT 1 FROM background_jobs j, LATERAL jsonb_each(coalesce(j.result_json,'{}')) r
        WHERE r.key !~ '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
        OR jsonb_typeof(r.value) IS DISTINCT FROM 'string'
        OR r.value #>> '{}' NOT IN ('ACCEPTED','REJECTED','INVALID_TOKEN')) THEN
        RAISE EXCEPTION 'Unknown installation binding/delivery evidence must not be discarded';
    END IF;
    IF EXISTS (SELECT 1 FROM chat_members p WHERE (p.first_notice_acked_at IS NOT NULL
        AND NOT EXISTS (SELECT 1 FROM background_jobs j WHERE j.job_type='FIRST_ENTRY'
            AND j.payload->>'roomId'=p.chat_room_id::text AND j.payload->>'userId'=p.user_id::text))
        OR (p.end_notice_acked_at IS NOT NULL AND NOT EXISTS (SELECT 1 FROM background_jobs j
            WHERE j.job_type='END_NOTICE' AND j.payload->>'roomId'=p.chat_room_id::text
                AND j.payload->>'userId'=p.user_id::text))) THEN
        RAISE EXCEPTION 'An acknowledged notice has no original notice identity';
    END IF;
    IF to_regclass('public.reports') IS NOT NULL THEN
        IF EXISTS (SELECT 1 FROM reports) THEN
            RAISE EXCEPTION 'Migrate report evidence and retention before converting these chat rows';
        END IF;
    END IF;
END $$;

ALTER TABLE chat_rooms RENAME TO chat;
ALTER TABLE chat RENAME COLUMN chat_room_id TO id;
ALTER TABLE chat RENAME COLUMN source_proposal_id TO matching_id;
ALTER TABLE chat RENAME COLUMN public_id TO legacy_public_id;
ALTER TABLE chat RENAME COLUMN last_sequence TO last_message_seq;
ALTER TABLE chat ALTER COLUMN legacy_public_id DROP NOT NULL;
ALTER TABLE chat ADD COLUMN close_reason chat_close_reason;
ALTER TABLE chat ADD COLUMN retain_until timestamptz;
ALTER TABLE chat ADD COLUMN icebreakers jsonb NOT NULL DEFAULT '{}';
ALTER TABLE chat ADD COLUMN ai_state jsonb NOT NULL DEFAULT '{}';
ALTER TABLE chat RENAME CONSTRAINT pk_chat_rooms TO pk_chat;
ALTER TABLE chat RENAME CONSTRAINT uq_chat_rooms_public_id TO uq_chat_legacy_public_id;
ALTER TABLE chat RENAME CONSTRAINT uq_chat_rooms_source TO uq_chat_matching_id;
-- The original pair_id is already retained and checked in matching.legacy_state.pair.
ALTER TABLE chat DROP COLUMN pair_id;

ALTER TABLE chat_members RENAME TO chat_participant;
ALTER TABLE chat_participant RENAME COLUMN chat_room_id TO chat_id;
ALTER TABLE chat_participant ADD COLUMN id bigint GENERATED ALWAYS AS IDENTITY NOT NULL;
ALTER TABLE chat_participant ADD COLUMN last_read_message_seq bigint NOT NULL DEFAULT 0;
ALTER TABLE chat_participant ADD COLUMN notice_state jsonb NOT NULL DEFAULT '{}';
ALTER TABLE chat_participant ADD COLUMN read_state jsonb NOT NULL DEFAULT '{}';
ALTER TABLE chat_participant DROP CONSTRAINT pk_chat_members;
ALTER TABLE chat_participant ADD CONSTRAINT pk_chat_participant PRIMARY KEY (id);
ALTER TABLE chat_participant ADD CONSTRAINT uq_chat_participant_member UNIQUE (chat_id,user_id);
ALTER TABLE chat_participant ALTER COLUMN user_id DROP NOT NULL;
CREATE INDEX ix_chat_participant_user ON chat_participant (user_id);

UPDATE chat_participant p SET notice_state=coalesce((
    SELECT jsonb_object_agg(j.job_type, jsonb_strip_nulls(jsonb_build_object(
        'noticeId',j.public_id,'jobType',j.job_type,'businessKey',j.business_key,'payload',j.payload,
        'status',j.status,'runAt',j.run_at,'attempts',j.attempts,'leaseUntil',j.lease_until,
        'lastErrorCode',j.last_error_code,'resultJson',j.result_json,'finishedAt',j.finished_at,
        'legacyJobId',j.job_id,'ackedAt',CASE WHEN j.job_type='FIRST_ENTRY'
            THEN p.first_notice_acked_at ELSE p.end_notice_acked_at END)))
    FROM background_jobs j WHERE j.payload->>'roomId'=p.chat_id::text AND j.payload->>'userId'=p.user_id::text
), '{}');

UPDATE chat_participant p SET read_state=coalesce((
    SELECT jsonb_object_agg(a.message_id::text,
        jsonb_build_object('readAt',a.read_at,'legacyReceiptId',a.receipt_id))
    FROM chat_message_reads a JOIN chat_messages m ON m.message_id=a.message_id
    WHERE m.chat_room_id=p.chat_id AND a.user_id=p.user_id
), '{}');

ALTER TABLE chat_participant DROP COLUMN first_notice_acked_at;
ALTER TABLE chat_participant DROP COLUMN end_notice_acked_at;

ALTER TABLE chat_messages RENAME TO chat_message;
ALTER TABLE chat_message RENAME COLUMN message_id TO id;
ALTER TABLE chat_message RENAME COLUMN chat_room_id TO chat_id;
ALTER TABLE chat_message RENAME COLUMN public_id TO legacy_public_id;
ALTER TABLE chat_message RENAME COLUMN sequence TO seq;
ALTER TABLE chat_message ADD COLUMN sender_participant_id bigint;
UPDATE chat_message m SET sender_participant_id=p.id FROM chat_participant p
    WHERE p.chat_id=m.chat_id AND p.user_id=m.sender_id;
ALTER TABLE chat_message ALTER COLUMN sender_participant_id SET NOT NULL;
ALTER TABLE chat_message ALTER COLUMN request_hash TYPE bytea USING decode(request_hash,'hex');
ALTER TABLE chat_message ALTER COLUMN legacy_public_id DROP NOT NULL;
ALTER TABLE chat_message DROP CONSTRAINT uq_chat_messages_request;
ALTER TABLE chat_message DROP CONSTRAINT uq_chat_messages_1;
ALTER TABLE chat_message DROP COLUMN sender_id;
ALTER TABLE chat_message RENAME CONSTRAINT pk_chat_messages TO pk_chat_message;
ALTER TABLE chat_message RENAME CONSTRAINT uq_chat_messages_public_id TO uq_chat_message_legacy_public_id;
ALTER TABLE chat_message RENAME CONSTRAINT uq_chat_messages_2 TO uq_chat_message_seq;
ALTER TABLE chat_message ADD CONSTRAINT uq_chat_message_request UNIQUE (sender_participant_id,request_key);
ALTER TABLE chat_message ADD CONSTRAINT uq_chat_message_client UNIQUE (sender_participant_id,client_message_id);

-- Only proved consecutive displayed messages advance the cursor; gaps never become reads.
UPDATE chat_participant p SET last_read_message_seq=coalesce((
    SELECT coalesce(min(expected) FILTER (WHERE seq<>expected OR NOT displayed)-1,max(seq),0)
    FROM (SELECT m.seq,row_number() OVER (ORDER BY m.seq) AS expected,
        (m.sender_participant_id=p.id OR p.read_state ? m.id::text) AS displayed
        FROM chat_message m WHERE m.chat_id=p.chat_id) messages
),0);

-- Every supported record above has been moved, with its source identifiers retained.
DROP TABLE chat_message_reads;
DROP TABLE background_jobs;
COMMIT;
