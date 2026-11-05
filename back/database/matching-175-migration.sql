-- Manual review candidate for the known legacy proposal schema (0255529).
-- Stop old workers and verify backups first. Never automatically executed by the application.
-- Refuse multi-proposal/encounter histories instead of merging IDs or inventing canonical state.
BEGIN;
SET LOCAL search_path TO public, pg_temp;
DO $$
BEGIN
    IF to_regclass('public.user_pairs') IS NULL OR to_regclass('public.match_proposals') IS NULL
        OR to_regclass('public.encounters') IS NULL OR to_regclass('public.encounter_submissions') IS NULL
        OR to_regclass('public.user_blocks') IS NULL OR to_regclass('public.matching') IS NOT NULL
        OR to_regclass('public.matching_participant') IS NOT NULL THEN
        RAISE EXCEPTION 'Expected known legacy sources and absent canonical targets';
    END IF;
    IF EXISTS (SELECT 1 FROM user_pairs p WHERE
        (SELECT count(*) FROM match_proposals m WHERE m.pair_id=p.pair_id)<>1
        OR (SELECT count(*) FROM encounters e WHERE e.pair_id=p.pair_id)<>1)
        OR EXISTS (SELECT 1 FROM match_proposals m LEFT JOIN user_pairs p ON p.pair_id=m.pair_id
            LEFT JOIN encounters e ON e.encounter_id=m.encounter_id
            WHERE p.pair_id IS NULL OR e.encounter_id IS NULL OR e.pair_id<>m.pair_id
            OR e.status IS DISTINCT FROM 'VERIFIED'
            OR m.proposal_id<=0 OR p.user_low<=0 OR p.user_low>=p.user_high
            OR m.expires_at IS DISTINCT FROM m.created_at+interval '24 hours')
        OR EXISTS (SELECT 1 FROM encounters e LEFT JOIN user_pairs p ON p.pair_id=e.pair_id
            WHERE p.pair_id IS NULL)
        OR EXISTS (SELECT 1 FROM encounter_submissions s LEFT JOIN encounters e
            ON e.encounter_id=s.encounter_id WHERE e.encounter_id IS NULL) THEN
        RAISE EXCEPTION 'Ambiguous relationship/proposal history: preserve source and review manually';
    END IF;
    IF EXISTS (SELECT 1 FROM match_proposals m WHERE
        (m.status='PENDING' AND (m.low_decision IS NOT NULL OR m.high_decision IS NOT NULL))
        OR (m.status='WAITING_FOR_OTHER' AND (
            ((m.low_decision IS NOT DISTINCT FROM 'ACCEPT')::integer
                +(m.high_decision IS NOT DISTINCT FROM 'ACCEPT')::integer)<>1
            OR m.low_decision='DECLINE' OR m.high_decision='DECLINE'
            OR m.low_cancelled_at IS NOT NULL OR m.high_cancelled_at IS NOT NULL))
        OR (m.status IN ('ACCEPTED','CLOSED') AND
            (m.low_decision IS DISTINCT FROM 'ACCEPT' OR m.high_decision IS DISTINCT FROM 'ACCEPT'
                OR m.low_cancelled_at IS NOT NULL OR m.high_cancelled_at IS NOT NULL))
        OR (m.status='REJECTED' AND m.low_decision IS DISTINCT FROM 'DECLINE'
            AND m.high_decision IS DISTINCT FROM 'DECLINE'
            AND m.low_cancelled_at IS NULL AND m.high_cancelled_at IS NULL)
        OR m.low_decided_at<m.created_at OR m.low_decided_at>=m.expires_at
        OR m.high_decided_at<m.created_at OR m.high_decided_at>=m.expires_at) THEN
        RAISE EXCEPTION 'Original proposal state or response time lacks consistent evidence';
    END IF;
    IF EXISTS (SELECT 1 FROM user_blocks b WHERE NOT EXISTS (SELECT 1 FROM user_pairs p
        WHERE p.user_low=least(b.blocker_id,b.blocked_id) AND p.user_high=greatest(b.blocker_id,b.blocked_id))) THEN
        RAISE EXCEPTION 'Block-only relationship has no original proposal identity';
    END IF;
    IF EXISTS (SELECT 1 FROM (
        SELECT p.user_low AS user_id,m.low_request_key AS key FROM match_proposals m JOIN user_pairs p USING(pair_id)
        UNION ALL SELECT p.user_high,m.high_request_key FROM match_proposals m JOIN user_pairs p USING(pair_id)
        UNION ALL SELECT p.user_low,m.low_cancel_request_key FROM match_proposals m JOIN user_pairs p USING(pair_id)
        UNION ALL SELECT p.user_high,m.high_cancel_request_key FROM match_proposals m JOIN user_pairs p USING(pair_id)
    ) k WHERE key IS NOT NULL GROUP BY user_id,key HAVING count(*)>1) THEN
        RAISE EXCEPTION 'A member request key is ambiguous across original commands';
    END IF;
END $$;
CREATE TABLE "matching" (
    "id" bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    "user_low_id" bigint NOT NULL,
    "user_high_id" bigint NOT NULL,
    "met_at" timestamptz,
    "policy_version" varchar(30),
    "opportunity_expires_at" timestamptz,
    "counterpart_deadline_at" timestamptz,
    "evaluation" jsonb,
    "status" "matching_status" NOT NULL,
    "proposed_at" timestamptz,
    "raw_delete_at" timestamptz,
    "legacy_state" jsonb,
    CONSTRAINT "pk_matching" PRIMARY KEY ("id")
);
CREATE UNIQUE INDEX "uq_matching_pair" ON "matching" ("user_low_id", "user_high_id");
CREATE INDEX "ix_matching_low_status" ON "matching" ("user_low_id", "status");
CREATE INDEX "ix_matching_high_status" ON "matching" ("user_high_id", "status");
CREATE INDEX "ix_matching_proposal_expiration" ON "matching" ("status", "proposed_at");
CREATE INDEX "ix_matching_counterpart_deadline" ON "matching" ("counterpart_deadline_at");
CREATE INDEX "ix_matching_raw_delete" ON "matching" ("raw_delete_at");
CREATE TABLE "matching_participant" (
    "matching_id" bigint NOT NULL,
    "user_id" bigint NOT NULL,
    "encounter_keyring_id" bigint,
    "encounter_signature" bytea,
    "encounter_reported_at" timestamptz,
    "encounter_verified_at" timestamptz,
    "response" "proposal_response",
    "responded_at" timestamptz,
    "blocked_at" timestamptz,
    "decision_request_key" uuid,
    "request_state" jsonb,
    CONSTRAINT "pk_matching_participant" PRIMARY KEY ("matching_id", "user_id")
);
CREATE INDEX "ix_matching_participant_user" ON "matching_participant" ("user_id");
CREATE UNIQUE INDEX "uq_matching_participant_user_request" ON "matching_participant" ("user_id", "decision_request_key");
INSERT INTO matching(id,user_low_id,user_high_id,met_at,opportunity_expires_at,counterpart_deadline_at,
    status,proposed_at,legacy_state) OVERRIDING SYSTEM VALUE
SELECT m.proposal_id,p.user_low,p.user_high,e.occurred_at,
    e.opportunity_expires_at,e.counterpart_deadline_at,
    (CASE WHEN m.status='WAITING_FOR_OTHER' THEN 'ONE_ACCEPTED' ELSE m.status END)::matching_status,
    m.created_at,jsonb_build_object('pair',to_jsonb(p),'proposal',to_jsonb(m),'encounter',to_jsonb(e),
        'submissions',coalesce((SELECT jsonb_agg(to_jsonb(s) ORDER BY s.receipt_id)
            FROM encounter_submissions s WHERE s.encounter_id=e.encounter_id),'[]'::jsonb),
        'blocks',coalesce((SELECT jsonb_agg(to_jsonb(b) ORDER BY b.blocker_id) FROM user_blocks b
            WHERE least(b.blocker_id,b.blocked_id)=p.user_low
            AND greatest(b.blocker_id,b.blocked_id)=p.user_high),'[]'::jsonb))
FROM match_proposals m JOIN user_pairs p USING(pair_id) JOIN encounters e USING(encounter_id);
INSERT INTO matching_participant(matching_id,user_id,response,responded_at,blocked_at,decision_request_key,request_state)
SELECT m.proposal_id,v.user_id,
    (CASE v.decision WHEN 'ACCEPT' THEN 'ACCEPTED' WHEN 'DECLINE' THEN 'REJECTED' END)::proposal_response,
    v.decided_at,b.created_at,v.request_key,
    jsonb_strip_nulls(jsonb_build_object('receiptVersion','legacy','command',v.decision,
        'legacyCancelledAt',v.cancelled_at,'legacyCancelRequestKey',v.cancel_key))
FROM match_proposals m JOIN user_pairs p USING(pair_id)
CROSS JOIN LATERAL (VALUES (p.user_low,m.low_decision,m.low_decided_at,m.low_request_key,
    m.low_cancelled_at,m.low_cancel_request_key),
    (p.user_high,m.high_decision,m.high_decided_at,m.high_request_key,
    m.high_cancelled_at,m.high_cancel_request_key)) v(user_id,decision,decided_at,request_key,cancelled_at,cancel_key)
LEFT JOIN user_blocks b ON b.blocker_id=v.user_id AND b.blocked_id=
    CASE WHEN v.user_id=p.user_low THEN p.user_high ELSE p.user_low END;
-- Keep identity high-water mark as well as existing external numeric proposal IDs.
SELECT setval(pg_get_serial_sequence('matching','id'),greatest(
    coalesce((SELECT max(id) FROM matching),1),
    (SELECT last_value FROM match_proposals_proposal_id_seq)),
    (SELECT is_called FROM match_proposals_proposal_id_seq) OR EXISTS(SELECT 1 FROM matching));
-- Every original source row is retained in its owning matching.legacy_state; no separate legacy table.
DROP TABLE encounter_submissions;
DROP TABLE encounters;
DROP TABLE match_proposals;
DROP TABLE user_pairs;
DROP TABLE user_blocks;
COMMIT;
