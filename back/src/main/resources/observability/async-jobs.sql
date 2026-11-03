-- Metadata-only, single-statement snapshot. Never select IDs, content, receipts or provider errors.
WITH jobs(stage, doc) AS (
    SELECT 'MATCHING', CASE
        WHEN p.request_state->>'encounter:workflow' IS NULL THEN NULL
        WHEN pg_input_is_valid(p.request_state->>'encounter:workflow', 'jsonb')
            THEN (p.request_state->>'encounter:workflow')::jsonb
        ELSE '{"state":"INVALID"}'::jsonb END
    FROM matching m JOIN matching_participant p ON p.matching_id=m.id AND p.user_id=m.user_low_id
    UNION ALL
    SELECT 'EXPLANATION', v.value->'current' FROM matching m CROSS JOIN LATERAL jsonb_each(
        CASE WHEN jsonb_typeof(m.evaluation#>'{matchExplanations,viewers}')='object'
            THEN m.evaluation#>'{matchExplanations,viewers}' ELSE '{}'::jsonb END) v
    UNION ALL
    SELECT 'ICEBREAKER', icebreakers FROM chat
    UNION ALL
    SELECT 'RISK', ai_state->'riskAnalysis' FROM chat
    UNION ALL
    SELECT 'DB_DELETION', chat_deletion_state FROM matching
    UNION ALL
    SELECT 'EXTERNAL_CLEANUP', chat_external_cleanup_state FROM matching
), fields AS (
    SELECT stage, doc, coalesce(doc->>'state',doc->>'status','INVALID') AS state,
        CASE WHEN pg_input_is_valid(doc->>'attempts','int4') THEN greatest(0,(doc->>'attempts')::integer)
             WHEN pg_input_is_valid(doc->>'attempt','int4') THEN greatest(0,(doc->>'attempt')::integer)
             ELSE 0 END AS attempts,
        coalesce(doc->>'leaseUntil',doc->>'nextRetryAt',doc->>'retryAt',doc->>'nextAttemptAt',
                 doc->>'deadlineAt',doc#>>'{context,dueAt}') AS due_text
    FROM jobs WHERE doc IS NOT NULL AND doc<>'{}'::jsonb
), normalized AS (
    SELECT *, state IN ('READY','PENDING','RUNNING','RETRY','WAITING_RETENTION')
        OR (stage='RISK' AND state='FAILED' AND doc->>'automatic'='true'
            AND attempts<3 AND doc->>'retryAt' IS NOT NULL) AS pending,
        CASE WHEN pg_input_is_valid(due_text,'timestamptz')
             AND lower(due_text) NOT IN ('infinity','-infinity') THEN due_text::timestamptz END AS due
    FROM fields
), observed AS (SELECT cast(? AS timestamptz) AS at)
SELECT stage, count(*) AS total,
    count(*) FILTER (WHERE pending) AS pending,
    count(*) FILTER (WHERE pending AND (state='RETRY' OR attempts>1)) AS retrying,
    count(*) FILTER (WHERE state='FAILED' AND NOT pending) AS failed,
    count(*) FILTER (WHERE state LIKE 'BLOCKED%') AS blocked,
    count(*) FILTER (WHERE doc->>'source' IN ('GENERAL_FALLBACK','RULE_FALLBACK')) AS fallback,
    count(*) FILTER (WHERE pending AND due<=at) AS overdue,
    count(*) FILTER (WHERE pending AND due IS NULL) AS missing_due,
    count(*) FILTER (WHERE state NOT IN ('IDLE','READY','PENDING','RUNNING','RETRY','SUCCEEDED','FAILED',
        'BLOCKED','BLOCKED_REFERENCES','BLOCKED_SOURCE_CHANGED','BLOCKED_GENERATION',
        'WAITING_RETENTION','DB_DELETED','VERIFIED','EXPIRED','STALE')
        OR (due_text IS NOT NULL AND due IS NULL)) AS invalid,
    coalesce(max(greatest(0,extract(epoch FROM (at-due)))) FILTER (WHERE pending),0) AS max_delay_seconds
FROM normalized CROSS JOIN observed GROUP BY stage
