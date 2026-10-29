-- Tingle agreed lean24 design / PostgreSQL / 2026-10-21
-- Empty-database design DDL only; not a migration and not executed.
-- Logical ID references only; no physical foreign keys.

-- 회원·단일 소셜 로그인 계정
CREATE TABLE app_users (
    user_id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    provider text NOT NULL,
    provider_subject text NOT NULL,
    status text NOT NULL,
    created_at timestamptz NOT NULL,
    signup_completed_at timestamptz,
    CONSTRAINT pk_app_users PRIMARY KEY (user_id),
    CONSTRAINT uq_app_users_1 UNIQUE (provider, provider_subject),
    CONSTRAINT ck_app_users_1 CHECK (status IN ('ACTIVE','SUSPENDED','WITHDRAWN'))
);

-- 리프레시 토큰·회전 계열; 인증 담당과 조정할 초안
CREATE TABLE refresh_tokens (
    token_id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    user_id bigint NOT NULL,
    family_id uuid NOT NULL,
    token_hash bytea NOT NULL,
    expires_at timestamptz NOT NULL,
    used_at timestamptz,
    revoked_at timestamptz,
    CONSTRAINT pk_refresh_tokens PRIMARY KEY (token_id),
    CONSTRAINT uq_refresh_tokens_1 UNIQUE (token_hash),
    CONSTRAINT ck_refresh_tokens_1 CHECK (octet_length(token_hash)=32)
);

-- 약관·선택적 AI 분석 동의 이벤트
CREATE TABLE user_consents (
    consent_id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    user_id bigint NOT NULL,
    consent_type text NOT NULL,
    document_version text NOT NULL,
    agreed boolean NOT NULL,
    recorded_at timestamptz NOT NULL,
    CONSTRAINT pk_user_consents PRIMARY KEY (consent_id)
);

-- 회원 프로필과 사진 한 장
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
    CONSTRAINT pk_profiles PRIMARY KEY (user_id),
    CONSTRAINT uq_profiles_1 UNIQUE (nickname_key),
    CONSTRAINT ck_profiles_1 CHECK ((nickname IS NULL)=(nickname_key IS NULL))
);

-- 회원별 희망 조건·매칭 설정·키링 반영 버전
CREATE TABLE matching_settings (
    user_id bigint NOT NULL,
    desired_genders text[],
    age_min integer,
    age_max integer,
    matching_enabled boolean NOT NULL DEFAULT false,
    require_partner_photo boolean NOT NULL DEFAULT false,
    settings_version bigint NOT NULL DEFAULT 0,
    CONSTRAINT pk_matching_settings PRIMARY KEY (user_id),
    CONSTRAINT ck_matching_settings_1 CHECK (desired_genders IS NULL OR (cardinality(desired_genders) BETWEEN 1 AND 2 AND desired_genders <@ ARRAY['MALE','FEMALE']::text[])),
    CONSTRAINT ck_matching_settings_2 CHECK ((age_min IS NULL AND age_max IS NULL) OR (age_min IS NOT NULL AND age_min>=19 AND (age_max IS NULL OR age_max>=age_min))),
    CONSTRAINT ck_matching_settings_3 CHECK (settings_version>=0)
);

-- 실물 키링 식별·검증 정보; 37번 계약 확인
CREATE TABLE keyrings (
    device_id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    serial_hash bytea NOT NULL,
    verification_key_ref text NOT NULL,
    firmware_version text NOT NULL,
    CONSTRAINT pk_keyrings PRIMARY KEY (device_id),
    CONSTRAINT uq_keyrings_1 UNIQUE (serial_hash)
);

-- 기기 소유 연결 이력
CREATE TABLE keyring_bindings (
    binding_id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    device_id bigint NOT NULL,
    user_id bigint NOT NULL,
    bound_at timestamptz NOT NULL,
    unbound_at timestamptz,
    CONSTRAINT pk_keyring_bindings PRIMARY KEY (binding_id)
);

-- 일회성 키링 등록 검증; 37번 계약 확인
CREATE TABLE registration_challenges (
    challenge_id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    user_id bigint NOT NULL,
    nonce_hash bytea NOT NULL,
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    CONSTRAINT pk_registration_challenges PRIMARY KEY (challenge_id),
    CONSTRAINT uq_registration_challenges_1 UNIQUE (nonce_hash)
);

-- 설정 동기화 요청과 기기 저장 확인
CREATE TABLE keyring_sync_runs (
    sync_run_id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    binding_id bigint NOT NULL,
    snapshot_id bigint NOT NULL,
    payload_hash bytea NOT NULL,
    status text NOT NULL,
    acknowledged_at timestamptz,
    CONSTRAINT pk_keyring_sync_runs PRIMARY KEY (sync_run_id),
    CONSTRAINT uq_keyring_sync_runs_1 UNIQUE (binding_id, snapshot_id),
    CONSTRAINT ck_keyring_sync_runs_1 CHECK (status IN ('PENDING','ACKNOWLEDGED','FAILED','SUPERSEDED')),
    CONSTRAINT ck_keyring_sync_runs_2 CHECK ((status='ACKNOWLEDGED')=(acknowledged_at IS NOT NULL))
);

-- 설문 회차·문항별 응답·재개 상태
CREATE TABLE survey_responses (
    response_id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    user_id bigint NOT NULL,
    version_code text NOT NULL,
    client_request_id uuid NOT NULL,
    response_json jsonb NOT NULL,
    revision bigint NOT NULL DEFAULT 0,
    completed_at timestamptz,
    CONSTRAINT pk_survey_responses PRIMARY KEY (response_id),
    CONSTRAINT uq_survey_responses_1 UNIQUE (user_id, client_request_id),
    CONSTRAINT ck_survey_responses_1 CHECK (revision>=0),
    CONSTRAINT ck_survey_responses_2 CHECK (jsonb_typeof(response_json)='object')
);

-- 개인 성향 계산 결과·재계산 이력
CREATE TABLE analysis_results (
    result_id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    response_id bigint NOT NULL,
    version_code text NOT NULL,
    outcome text NOT NULL,
    result_json jsonb NOT NULL,
    CONSTRAINT pk_analysis_results PRIMARY KEY (result_id),
    CONSTRAINT uq_analysis_results_1 UNIQUE (response_id, version_code),
    CONSTRAINT ck_analysis_results_1 CHECK (outcome IN ('SUCCEEDED','INSUFFICIENT')),
    CONSTRAINT ck_analysis_results_2 CHECK (jsonb_typeof(result_json)='object')
);

-- 키링·오프라인 만남 당시의 설정 보존
CREATE TABLE settings_snapshots (
    snapshot_id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    user_id bigint NOT NULL,
    settings_version bigint NOT NULL,
    analysis_result_id bigint NOT NULL,
    snapshot_json jsonb NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT pk_settings_snapshots PRIMARY KEY (snapshot_id),
    CONSTRAINT uq_settings_snapshots_1 UNIQUE (user_id, settings_version),
    CONSTRAINT ck_settings_snapshots_1 CHECK (settings_version>=0),
    CONSTRAINT ck_settings_snapshots_2 CHECK (jsonb_typeof(snapshot_json)='object')
);

-- 동일 회원 쌍·동시 처리 잠금·거절 제한
CREATE TABLE user_pairs (
    pair_id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    user_low bigint NOT NULL,
    user_high bigint NOT NULL,
    declined_until timestamptz,
    declined_proposal_id bigint,
    CONSTRAINT pk_user_pairs PRIMARY KEY (pair_id),
    CONSTRAINT uq_user_pairs_1 UNIQUE (user_low, user_high),
    CONSTRAINT ck_user_pairs_1 CHECK (user_low<user_high),
    CONSTRAINT ck_user_pairs_2 CHECK ((declined_until IS NULL)=(declined_proposal_id IS NULL))
);

-- 양쪽 제출을 묶는 만남·최종 판정
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

-- 각 기기의 제출·재전송·증명
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

-- 제안·고정 두 사람의 선택·방 연결
CREATE TABLE match_proposals (
    proposal_id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    pair_id bigint NOT NULL,
    encounter_id bigint NOT NULL,
    low_decision text,
    high_decision text,
    low_decided_at timestamptz,
    high_decided_at timestamptz,
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
    CONSTRAINT ck_match_proposals_5 CHECK (status IN ('PENDING','WAITING_FOR_OTHER','ACCEPTED','REJECTED','EXPIRED')),
    CONSTRAINT ck_match_proposals_6 CHECK (expires_at=created_at+interval '24 hours')
);

-- 7일 대화와 종료 후 30일 보관
CREATE TABLE chat_rooms (
    chat_room_id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    pair_id bigint NOT NULL,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    closed_at timestamptz,
    delete_at timestamptz,
    last_sequence bigint NOT NULL DEFAULT 0,
    CONSTRAINT pk_chat_rooms PRIMARY KEY (chat_room_id),
    CONSTRAINT ck_chat_rooms_1 CHECK (expires_at=created_at+interval '168 hours'),
    CONSTRAINT ck_chat_rooms_2 CHECK (last_sequence>=0),
    CONSTRAINT ck_chat_rooms_3 CHECK ((closed_at IS NULL AND delete_at IS NULL) OR (closed_at IS NOT NULL AND delete_at=closed_at+interval '720 hours'))
);

-- 참여자별 입장·두 안내의 표시 확인
CREATE TABLE chat_members (
    chat_room_id bigint NOT NULL,
    user_id bigint NOT NULL,
    left_at timestamptz,
    first_notice_acked_at timestamptz,
    end_notice_acked_at timestamptz,
    CONSTRAINT pk_chat_members PRIMARY KEY (chat_room_id, user_id)
);

-- 메시지와 순서·재전송 키
CREATE TABLE chat_messages (
    message_id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    chat_room_id bigint NOT NULL,
    sender_id bigint NOT NULL,
    client_message_id uuid NOT NULL,
    sequence bigint NOT NULL,
    body text NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT pk_chat_messages PRIMARY KEY (message_id),
    CONSTRAINT uq_chat_messages_1 UNIQUE (chat_room_id, sender_id, client_message_id),
    CONSTRAINT uq_chat_messages_2 UNIQUE (chat_room_id, sequence),
    CONSTRAINT ck_chat_messages_1 CHECK (sequence>0)
);

-- 방향성 차단과 해제
CREATE TABLE user_blocks (
    block_id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    blocker_id bigint NOT NULL,
    blocked_id bigint NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT pk_user_blocks PRIMARY KEY (block_id),
    CONSTRAINT uq_user_blocks_1 UNIQUE (blocker_id, blocked_id),
    CONSTRAINT ck_user_blocks_1 CHECK (blocker_id<>blocked_id)
);

-- 신고 접수와 처리 상태
CREATE TABLE reports (
    report_id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    reporter_id bigint NOT NULL,
    target_type text NOT NULL,
    target_id bigint NOT NULL,
    reason text NOT NULL,
    detail text,
    status text NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT pk_reports PRIMARY KEY (report_id),
    CONSTRAINT ck_reports_1 CHECK (target_type IN ('PROPOSAL','CHAT_ROOM','MESSAGE')),
    CONSTRAINT ck_reports_2 CHECK (status IN ('RECEIVED','REVIEWING','RESOLVED'))
);

-- AI 생성 결과와 위험 분석 결과 한 곳
CREATE TABLE ai_results (
    job_id bigint NOT NULL,
    kind text NOT NULL,
    user_id bigint NOT NULL,
    target_id bigint NOT NULL,
    result_json jsonb NOT NULL,
    model_version text NOT NULL,
    prompt_version text NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT pk_ai_results PRIMARY KEY (job_id),
    CONSTRAINT ck_ai_results_1 CHECK (kind IN ('PERSONAL_ANALYSIS','MATCH_EXPLANATION','ICEBREAKERS','RISK_DETECTION')),
    CONSTRAINT ck_ai_results_2 CHECK (jsonb_typeof(result_json)='object')
);

-- 앱 설치별 푸시 전달 주소
CREATE TABLE push_installations (
    installation_id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    client_installation_id uuid NOT NULL,
    user_id bigint NOT NULL,
    platform text NOT NULL,
    token_ciphertext text NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT pk_push_installations PRIMARY KEY (installation_id),
    CONSTRAINT uq_push_installations_1 UNIQUE (client_installation_id),
    CONSTRAINT ck_push_installations_1 CHECK (platform IN ('ANDROID','IOS'))
);

-- 안내·AI·삭제의 실제 예약·실패 재시도
CREATE TABLE background_jobs (
    job_id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
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
    CONSTRAINT ck_background_jobs_1 CHECK (job_type IN ('CHAT_CREATE','FIRST_ENTRY','END_NOTICE','AI','CHAT_DELETE')),
    CONSTRAINT ck_background_jobs_2 CHECK (status IN ('PENDING','RUNNING','SUCCEEDED','FAILED','CANCELLED')),
    CONSTRAINT ck_background_jobs_3 CHECK (attempts>=0),
    CONSTRAINT ck_background_jobs_4 CHECK ((status='RUNNING')=(lease_until IS NOT NULL)),
    CONSTRAINT ck_background_jobs_5 CHECK (jsonb_typeof(payload)='object'),
    CONSTRAINT ck_background_jobs_6 CHECK (result_json IS NULL OR (jsonb_typeof(result_json)='object'))
);

CREATE INDEX ix_refresh_tokens_1 ON refresh_tokens (family_id);
CREATE INDEX ix_refresh_tokens_2 ON refresh_tokens (user_id);
CREATE INDEX ix_user_consents_1 ON user_consents (user_id, consent_type, recorded_at DESC, consent_id DESC);
CREATE UNIQUE INDEX ix_keyring_bindings_1 ON keyring_bindings (device_id) WHERE unbound_at IS NULL;
CREATE INDEX ix_keyring_bindings_2 ON keyring_bindings (user_id);
CREATE UNIQUE INDEX ix_survey_responses_1 ON survey_responses (user_id, version_code) WHERE completed_at IS NULL;
CREATE UNIQUE INDEX ix_match_proposals_1 ON match_proposals (pair_id) WHERE status IN ('PENDING','WAITING_FOR_OTHER');
CREATE INDEX ix_match_proposals_2 ON match_proposals (pair_id, created_at DESC, proposal_id DESC);
CREATE UNIQUE INDEX ix_chat_rooms_1 ON chat_rooms (pair_id) WHERE closed_at IS NULL;
CREATE INDEX ix_chat_rooms_2 ON chat_rooms (expires_at) WHERE closed_at IS NULL;
CREATE INDEX ix_chat_rooms_3 ON chat_rooms (delete_at) WHERE closed_at IS NOT NULL;
CREATE INDEX ix_chat_members_1 ON chat_members (user_id, chat_room_id);
CREATE INDEX ix_ai_results_1 ON ai_results (kind, target_id);
CREATE INDEX ix_push_installations_1 ON push_installations (user_id);
CREATE INDEX ix_background_jobs_1 ON background_jobs (run_at, job_id) WHERE status='PENDING';
CREATE INDEX ix_background_jobs_2 ON background_jobs (lease_until) WHERE status='RUNNING';
