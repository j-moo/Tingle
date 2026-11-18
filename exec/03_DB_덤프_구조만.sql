--
-- PostgreSQL database dump
--

\restrict vge0v5ZaueTG6TvHGvqElZRIxYzHJHkKhmSsFa2Id5uhPfR5qZ3ABGuuXxRGACu

-- Dumped from database version 17.11 (Debian 17.11-1.pgdg13+2)
-- Dumped by pg_dump version 17.11 (Debian 17.11-1.pgdg13+2)

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET transaction_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Name: public; Type: SCHEMA; Schema: -; Owner: -
--

-- *not* creating schema, since initdb creates it


--
-- Name: SCHEMA public; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON SCHEMA public IS '';


--
-- Name: analysis_status; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.analysis_status AS ENUM (
    'PENDING',
    'SUCCEEDED',
    'INSUFFICIENT',
    'FAILED'
);


--
-- Name: auth_provider; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.auth_provider AS ENUM (
    'GOOGLE',
    'KAKAO',
    'NAVER'
);


--
-- Name: chat_close_reason; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.chat_close_reason AS ENUM (
    'EXPIRED',
    'LEFT',
    'BLOCKED'
);


--
-- Name: chat_report_reason; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.chat_report_reason AS ENUM (
    'ABUSE',
    'SEXUAL',
    'SPAM',
    'ETC'
);


--
-- Name: chat_report_status; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.chat_report_status AS ENUM (
    'RECEIVED',
    'REVIEWING',
    'RESOLVED',
    'DISMISSED'
);


--
-- Name: device_platform; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.device_platform AS ENUM (
    'ANDROID',
    'IOS'
);


--
-- Name: drinking_frequency; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.drinking_frequency AS ENUM (
    'NEVER',
    'SPECIAL_OCCASIONS_ONLY',
    'MONTHLY_AT_MOST_ONCE',
    'MONTHLY_TWO_TO_FOUR',
    'WEEKLY_TWO_TO_THREE',
    'WEEKLY_FOUR_OR_MORE'
);


--
-- Name: gender; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.gender AS ENUM (
    'MALE',
    'FEMALE'
);


--
-- Name: keyring_state; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.keyring_state AS ENUM (
    'UNBOUND',
    'REGISTERED'
);


--
-- Name: matching_status; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.matching_status AS ENUM (
    'WAITING',
    'NOT_MATCHED',
    'PENDING',
    'ONE_ACCEPTED',
    'ACCEPTED',
    'REJECTED',
    'EXPIRED',
    'CLOSED'
);


--
-- Name: operator_action; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.operator_action AS ENUM (
    'MEMBER_VIEW',
    'REPORT_CHAT_VIEW',
    'REPORT_RESOLVE',
    'PROFILE_CORRECT',
    'KEYRING_UNBIND',
    'MEMBER_WITHDRAW',
    'CASE_HANDLE',
    'LOGIN_FAIL',
    'OPERATOR_GRANT',
    'OPERATOR_REVOKE'
);


--
-- Name: operator_audit_kind; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.operator_audit_kind AS ENUM (
    'ACCESS',
    'PERMISSION'
);


--
-- Name: operator_result; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.operator_result AS ENUM (
    'SUCCESS',
    'DENIED',
    'FAILED'
);


--
-- Name: profile_correction_field; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.profile_correction_field AS ENUM (
    'BIRTH_DATE',
    'GENDER',
    'NICKNAME'
);


--
-- Name: proposal_response; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.proposal_response AS ENUM (
    'ACCEPTED',
    'REJECTED'
);


--
-- Name: religion; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.religion AS ENUM (
    'NONE',
    'PROTESTANT',
    'CATHOLIC',
    'BUDDHIST',
    'OTHER',
    'UNDISCLOSED'
);


--
-- Name: safety_notice_type; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.safety_notice_type AS ENUM (
    'MONEY_REQUEST',
    'PERSONAL_INFO',
    'OFF_PLATFORM',
    'HARASSMENT'
);


--
-- Name: service_case_event; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.service_case_event AS ENUM (
    'INFO_REQUESTED',
    'USER_REPLIED',
    'ACTION_DONE',
    'ACTION_FAILED',
    'STATUS_CHANGED'
);


--
-- Name: service_case_kind; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.service_case_kind AS ENUM (
    'PROFILE_CORRECTION',
    'LOST_WITHDRAWAL'
);


--
-- Name: service_case_status; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.service_case_status AS ENUM (
    'RECEIVED',
    'REVIEWING',
    'NEEDS_INFO',
    'COMPLETED',
    'REJECTED',
    'NO_RESPONSE'
);


--
-- Name: smoking_status; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.smoking_status AS ENUM (
    'NON_SMOKER',
    'E_CIGARETTE',
    'ONLY_WHEN_DRINKING',
    'SMOKER'
);


SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: chat; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.chat (
    id bigint NOT NULL,
    matching_id bigint NOT NULL,
    created_at timestamp with time zone NOT NULL,
    closed_at timestamp with time zone,
    close_reason public.chat_close_reason,
    last_message_seq bigint NOT NULL,
    icebreakers jsonb NOT NULL,
    ai_state jsonb NOT NULL,
    retain_until timestamp with time zone,
    expires_at timestamp with time zone NOT NULL,
    delete_at timestamp with time zone,
    legacy_public_id uuid
);


--
-- Name: TABLE chat; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.chat IS '채팅방 — 매칭 한 건당 방 하나. 사람별 상태는 chat_participant에. 참여자 한 명이 탈퇴하면 방과 딸린 표 모두 삭제. 단 신고된 방은 retain_until까지 보관';


--
-- Name: COLUMN chat.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.chat.id IS '채팅방 ID — 공개 채팅방 ID. API chatRoomId';


--
-- Name: COLUMN chat.matching_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.chat.matching_id IS '관계 ID — 방을 생성한 두 사람 관계의 내부 번호 (중복 금지)';


--
-- Name: COLUMN chat.created_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.chat.created_at IS '채팅방 생성 시각 — 최초 방 생성 시각. 이용 만료는 이 시각+168시간';


--
-- Name: COLUMN chat.closed_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.chat.closed_at IS '채팅방 종료 시각 — 실제 종료 효력 시각. 정상 만료는 원래 만료 시각, 중복 처리로 변경하지 않음';


--
-- Name: COLUMN chat.close_reason; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.chat.close_reason IS '종료 사유 — 내부 종료 구분. 설계 코드: EXPIRED / LEFT / BLOCKED. 상대에게 구체 사유 공개 금지';


--
-- Name: COLUMN chat.last_message_seq; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.chat.last_message_seq IS '마지막 메시지 순번 — 방 안의 마지막 메시지 순번. 코드 초깃값 0';


--
-- Name: COLUMN chat.icebreakers; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.chat.icebreakers IS 'AI 시작 질문 — AI 시작 질문 3개·버전과 상태 {status, attempts, nextRetryAt}. 실패 시 주기 작업이 재시도, 최종 실패면 서버 설정 파일의 기본 질문으로 대체. 질문 사용 없이 대화 가능';


--
-- Name: COLUMN chat.ai_state; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.chat.ai_state IS 'AI 위험 분석 상태 — 마지막 분석한 메시지 순번·상태 {status, attempts, nextRetryAt}. 실패 시 주기 작업이 재시도. 미분석/실패를 안전으로 표시하지 않음';


--
-- Name: COLUMN chat.retain_until; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.chat.retain_until IS '신고 보관 기한 — 최초 신고 + 90일. 이 기한 전에는 탈퇴해도 방·메시지를 지우지 않고, 기한이 지나면 삭제';


--
-- Name: COLUMN chat.expires_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.chat.expires_at IS '최초 이용 만료 시각. 생성+168시간 또는 검증한 기존 deadline. 재시도로 연장하지 않음';


--
-- Name: COLUMN chat.delete_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.chat.delete_at IS '일반 삭제 예약 시각 보존. 신고 retain_until보다 먼저 삭제하지 않음';


--
-- Name: COLUMN chat.legacy_public_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.chat.legacy_public_id IS '과거 공개 방 UUID 보존. 현재 공개 ID는 chat.id';


--
-- Name: chat_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.chat ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.chat_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: chat_message; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.chat_message (
    id bigint NOT NULL,
    chat_id bigint NOT NULL,
    seq bigint NOT NULL,
    sender_participant_id bigint NOT NULL,
    body text NOT NULL,
    client_message_id uuid NOT NULL,
    request_key uuid NOT NULL,
    request_hash bytea NOT NULL,
    created_at timestamp with time zone NOT NULL,
    legacy_public_id uuid,
    push_state jsonb DEFAULT '{}'::jsonb NOT NULL
);


--
-- Name: TABLE chat_message; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.chat_message IS '채팅 메시지';


--
-- Name: COLUMN chat_message.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.chat_message.id IS '메시지 ID — 공개 메시지 ID. API messageId';


--
-- Name: COLUMN chat_message.chat_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.chat_message.chat_id IS '채팅방 ID — 메시지가 속한 채팅방 ID';


--
-- Name: COLUMN chat_message.seq; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.chat_message.seq IS '방 안의 메시지 순번 — 방 안의 증가 순번. 조회·정렬·재접속 중복 제거에 사용';


--
-- Name: COLUMN chat_message.sender_participant_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.chat_message.sender_participant_id IS '보낸 사람(채팅 참여자 번호) — chat_participant.id. 보낸 사람이 탈퇴해도 신고 보관 중인 방에서는 참여자 번호로 누가 보냈는지 구분';


--
-- Name: COLUMN chat_message.body; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.chat_message.body IS '메시지 본문 — 입력 검증 및 웹 주소 치환을 마친 전체 본문. 치환 전 원문은 별도 저장하지 않음';


--
-- Name: COLUMN chat_message.client_message_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.chat_message.client_message_id IS '클라이언트 메시지 ID — 클라이언트가 메시지 생성 시 발급하고 재전송에 재사용하는 UUID';


--
-- Name: COLUMN chat_message.request_key; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.chat_message.request_key IS '중복 요청 방지 키 — 전송 요청의 Idempotency-Key. 기존 계약의 발신자 범위 중복 판정에 사용';


--
-- Name: COLUMN chat_message.request_hash; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.chat_message.request_hash IS '요청 내용 해시 — 정규화된 원문·요청 의미의 서버 계산 SHA-256. 원문 없이 다른 내용 재사용을 판정';


--
-- Name: COLUMN chat_message.created_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.chat_message.created_at IS '메시지 저장 시각 — 최초 서버 저장 시각. 정상 재전송에 같은 값 반환';


--
-- Name: COLUMN chat_message.legacy_public_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.chat_message.legacy_public_id IS '과거 공개 메시지 UUID 보존. 현재 공개 ID는 chat_message.id';


--
-- Name: chat_message_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.chat_message ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.chat_message_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: chat_participant; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.chat_participant (
    id bigint NOT NULL,
    chat_id bigint NOT NULL,
    user_id bigint,
    last_read_message_seq bigint DEFAULT 0 NOT NULL,
    left_at timestamp with time zone,
    notice_state jsonb DEFAULT '{}'::jsonb NOT NULL,
    read_state jsonb DEFAULT '{}'::jsonb NOT NULL
);


--
-- Name: TABLE chat_participant; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.chat_participant IS '채팅방 참여자 — 채팅방 하나에 두 사람이니 두 줄. 각자 어디까지 읽었는지, 나갔는지 저장';


--
-- Name: COLUMN chat_participant.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.chat_participant.id IS '채팅 참여자 번호 — 메시지·신고·위험 안내가 이 번호로 사람을 가리킴';


--
-- Name: COLUMN chat_participant.chat_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.chat_participant.chat_id IS '채팅방 ID — 어느 방의 참여자인지(chat.id)';


--
-- Name: COLUMN chat_participant.user_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.chat_participant.user_id IS '회원 ID — 이 참여자 회원. 신고 보관 중인 방에서 이 회원이 탈퇴하면 NULL로 비움(줄은 남겨 발신자 구분 유지)';


--
-- Name: COLUMN chat_participant.last_read_message_seq; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.chat_participant.last_read_message_seq IS '마지막 실제 표시 순번 — 본인 화면에 정상 표시된 마지막 chat_message.seq. 새 수신 메시지는 이보다 큰 순번 중 발신 참여자가 본인이 아닌 메시지다. 상대에게 공개하지 않는다. 건너뛴 메시지를 잘못 확인하지 않도록 연속 표시 범위·ACK 계약은 별도 검증한다.';


--
-- Name: COLUMN chat_participant.left_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.chat_participant.left_at IS '나간 시각 — 이 사람이 방을 나간 시각. NULL이면 아직 방에 있음';


--
-- Name: COLUMN chat_participant.notice_state; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.chat_participant.notice_state IS '일반안내·예약·최초ACK·선점·lease·재시도·설치별 전달 결과. AI 상태와 분리';


--
-- Name: COLUMN chat_participant.read_state; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.chat_participant.read_state IS '본인 messageId별 최초 표시 시각·기존 receipt. 불연속 배치를 max(seq) 읽음으로 추정하지 않음';


--
-- Name: chat_participant_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.chat_participant ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.chat_participant_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: chat_report; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.chat_report (
    id bigint NOT NULL,
    chat_id bigint NOT NULL,
    reporter_participant_id bigint NOT NULL,
    reason public.chat_report_reason NOT NULL,
    detail text,
    status public.chat_report_status NOT NULL,
    created_at timestamp with time zone NOT NULL,
    resolved_at timestamp with time zone
);


--
-- Name: TABLE chat_report; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.chat_report IS '채팅 신고 — 신고 한 건당 한 줄. 첫 신고가 들어오면 chat.retain_until(+90일)이 정해짐';


--
-- Name: COLUMN chat_report.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.chat_report.id IS '신고 번호';


--
-- Name: COLUMN chat_report.chat_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.chat_report.chat_id IS '신고된 채팅방 ID — chat.id';


--
-- Name: COLUMN chat_report.reporter_participant_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.chat_report.reporter_participant_id IS '신고한 사람(채팅 참여자 번호) — chat_participant.id';


--
-- Name: COLUMN chat_report.reason; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.chat_report.reason IS '신고 사유 코드 — 예: ABUSE(욕설) / SEXUAL(성적 발언) / SPAM(광고) / ETC(기타)';


--
-- Name: COLUMN chat_report.detail; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.chat_report.detail IS '신고 상세 내용 — 신고한 사람이 직접 쓴 설명. 선택';


--
-- Name: COLUMN chat_report.status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.chat_report.status IS '처리 상태 — RECEIVED(접수) / REVIEWING(검토 중) / RESOLVED(처리 완료) / DISMISSED(문제 없음)';


--
-- Name: COLUMN chat_report.created_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.chat_report.created_at IS '신고 접수 시각';


--
-- Name: COLUMN chat_report.resolved_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.chat_report.resolved_at IS '처리 완료 시각 — RESOLVED·DISMISSED가 된 시각';


--
-- Name: chat_report_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.chat_report ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.chat_report_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: chat_report_message; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.chat_report_message (
    report_id bigint NOT NULL,
    message_id bigint NOT NULL
);


--
-- Name: TABLE chat_report_message; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.chat_report_message IS '신고에 첨부한 메시지 — 신고 한 건에 메시지 여러 개를 고를 수 있어 따로 둠. 신고 하나 × 메시지 하나 = 한 줄';


--
-- Name: COLUMN chat_report_message.report_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.chat_report_message.report_id IS '신고 번호 — chat_report.id';


--
-- Name: COLUMN chat_report_message.message_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.chat_report_message.message_id IS '신고된 메시지 ID — chat_message.id';


--
-- Name: chat_safety_notice; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.chat_safety_notice (
    id bigint NOT NULL,
    chat_participant_id bigint NOT NULL,
    notice_type public.safety_notice_type NOT NULL,
    created_at timestamp with time zone NOT NULL,
    displayed_at timestamp with time zone,
    audience character varying(16),
    CONSTRAINT ck_chat_safety_notice_audience CHECK (((audience)::text = ANY ((ARRAY['SENDER'::character varying, 'RECIPIENT'::character varying])::text[])))
);


--
-- Name: TABLE chat_safety_notice; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.chat_safety_notice IS 'AI 위험 안내 — AI가 위험한 대화를 감지했을 때 한 사람에게 보여 준 안내. 같은 사람·같은 유형·같은 대상(보낸 쪽/받은 쪽)은 방마다 한 번만';


--
-- Name: COLUMN chat_safety_notice.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.chat_safety_notice.id IS '위험 안내 번호';


--
-- Name: COLUMN chat_safety_notice.chat_participant_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.chat_safety_notice.chat_participant_id IS '안내를 받은 사람(채팅 참여자 번호) — chat_participant.id';


--
-- Name: COLUMN chat_safety_notice.notice_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.chat_safety_notice.notice_type IS '위험 유형 — 예: MONEY_REQUEST(금전 요구) / PERSONAL_INFO(개인정보 요구) / OFF_PLATFORM(외부 연락 유도) / HARASSMENT(괴롭힘)';


--
-- Name: COLUMN chat_safety_notice.created_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.chat_safety_notice.created_at IS '안내 생성 시각 — AI가 감지해 안내를 만든 시각';


--
-- Name: COLUMN chat_safety_notice.displayed_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.chat_safety_notice.displayed_at IS '안내 확인 시각 — 이 사람이 앱에서 안내를 확인(닫음)한 시각. NULL이면 아직 확인 안 함(안내가 계속 보임)';


--
-- Name: COLUMN chat_safety_notice.audience; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.chat_safety_notice.audience IS '안내 대상 — SENDER(위험한 말을 보낸 사람) / RECIPIENT(받은 사람). NULL이면 이전 기록(구분 없음)';


--
-- Name: chat_safety_notice_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.chat_safety_notice ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.chat_safety_notice_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: keyring; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.keyring (
    id bigint NOT NULL,
    serial_number character varying(64) NOT NULL,
    firmware_version character varying(30) NOT NULL,
    owner_user_id bigint,
    state public.keyring_state NOT NULL,
    synced_settings_version bigint,
    synced_at timestamp with time zone,
    credential_key_id character varying(64),
    credential_public_key character varying(128),
    credential_revoked_at timestamp with time zone,
    owner_generation uuid,
    ownership_epoch bigint
);


--
-- Name: TABLE keyring; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.keyring IS '실물 키링';


--
-- Name: COLUMN keyring.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.keyring.id IS '기기 ID — 공개 기기 ID. API deviceId';


--
-- Name: COLUMN keyring.serial_number; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.keyring.serial_number IS '기기 고유번호 — 기기 고유번호. 조회 식별값이며 고유번호만으로 소유 인증을 대신하지 않음 (중복 금지)';


--
-- Name: COLUMN keyring.firmware_version; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.keyring.firmware_version IS '펌웨어 버전 — 현재 확인된 펌웨어 버전';


--
-- Name: COLUMN keyring.owner_user_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.keyring.owner_user_id IS '소유 회원 ID — 현재 소유 회원 ID. NULL이면 미귀속. 한 회원이 여러 개 등록 가능';


--
-- Name: COLUMN keyring.state; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.keyring.state IS '기기 상태 — UNBOUND(미등록) / REGISTERED(등록됨). 기기 초기화·분실 차단은 MVP 기능 제외. 탈퇴·양도는 서버에서 소유자를 비우고 UNBOUND로(이후 그 기기 만남 기록은 무시), 기기 안 설정은 새 소유자가 등록할 때 덮어씀';


--
-- Name: COLUMN keyring.synced_settings_version; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.keyring.synced_settings_version IS '적용된 매칭 성향 설정 버전 — 키링에 실제 저장됐다고 확인한 user_setting.settings_version. ACK 서명이 검증되고 ACK 번호가 그 순간 현재 settings_version과 같을 때만 갱신한다. 낮은 번호의 늦은 ACK는 무시하고 새 등록·해제 때 NULL로 둔다.';


--
-- Name: COLUMN keyring.synced_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.keyring.synced_at IS '설정 적용 시각 — 그 설정 버전의 실제 적용을 서버가 확인한 시각';


--
-- Name: COLUMN keyring.credential_key_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.keyring.credential_key_id IS '등록 시 서버가 serial에 대응시킨 기기 검증키 식별자. 원문 개인키는 서버에 저장하지 않으며 기존 미상 행은 NULL.';


--
-- Name: COLUMN keyring.credential_public_key; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.keyring.credential_public_key IS '사전 등록한 Ed25519 공개키의 X.509 SPKI 인코딩. 요청에서 받은 공개키를 신뢰하지 않으며 기존 미상 행은 NULL.';


--
-- Name: COLUMN keyring.credential_revoked_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.keyring.credential_revoked_at IS '이 검증 자격이 폐기된 실제 시각. NULL은 폐기 기록 없음이며 누락된 키의 유효성을 추정하지 않는다.';


--
-- Name: COLUMN keyring.owner_generation; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.keyring.owner_generation IS '현재 소유 등록 세대. 새로 검증한 등록에서만 발급하며 기존 소유자의 미상 세대를 임의 UUID로 채우지 않는다.';


--
-- Name: COLUMN keyring.ownership_epoch; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.keyring.ownership_epoch IS '기기 서명으로 확인한 소유 카운터. 기존 미확인 값은 NULL로 유지하며 임의 backfill하지 않고 재등록 시 보존한다.';


--
-- Name: keyring_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.keyring ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.keyring_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: keyring_peer_encounter; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.keyring_peer_encounter (
    receipt_id uuid NOT NULL,
    owner_user_id bigint NOT NULL,
    peer_user_id bigint,
    device_id bigint NOT NULL,
    owner_generation uuid NOT NULL,
    ownership_epoch bigint NOT NULL,
    event_id uuid NOT NULL,
    submission_id uuid NOT NULL,
    received_at timestamp with time zone NOT NULL,
    occurred_at timestamp with time zone NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    encounter_status character varying(32) NOT NULL,
    raw_payload bytea,
    envelope_hash character varying(64),
    peer_credential bytea,
    receipt_json text,
    retired boolean DEFAULT false NOT NULL,
    CONSTRAINT ck_keyring_peer_encounter_epoch CHECK ((ownership_epoch >= 0))
);


--
-- Name: keyring_peer_owner; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.keyring_peer_owner (
    user_id bigint NOT NULL,
    owner_ref uuid NOT NULL,
    created_at timestamp with time zone NOT NULL
);


--
-- Name: keyring_peer_owner_grant; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.keyring_peer_owner_grant (
    user_id bigint NOT NULL,
    keyring_id bigint NOT NULL,
    request_key uuid NOT NULL,
    family_id uuid NOT NULL,
    owner_generation uuid NOT NULL,
    ownership_epoch bigint NOT NULL,
    credential_public_key character varying(128) NOT NULL,
    request_hash character varying(64) NOT NULL,
    response_json text NOT NULL,
    created_at timestamp with time zone NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    CONSTRAINT keyring_peer_owner_grant_check CHECK (((expires_at > created_at) AND (expires_at <= (created_at + '00:05:00'::interval)))),
    CONSTRAINT keyring_peer_owner_grant_ownership_epoch_check CHECK ((ownership_epoch >= 0))
);


--
-- Name: keyring_peer_ownership; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.keyring_peer_ownership (
    keyring_id bigint NOT NULL,
    owner_generation uuid NOT NULL,
    ownership_epoch bigint NOT NULL,
    owner_user_id bigint NOT NULL,
    owner_ref uuid NOT NULL,
    device_serial character varying(64) NOT NULL,
    credential_key_id character varying(64) NOT NULL,
    credential_public_key character varying(128) NOT NULL,
    valid_from timestamp with time zone NOT NULL,
    valid_until timestamp with time zone,
    CONSTRAINT ck_keyring_peer_epoch CHECK ((ownership_epoch >= 0)),
    CONSTRAINT ck_keyring_peer_interval CHECK (((valid_until IS NULL) OR (valid_until >= valid_from)))
);


--
-- Name: keyring_peer_record_command; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.keyring_peer_record_command (
    user_id bigint NOT NULL,
    keyring_id bigint NOT NULL,
    request_key uuid NOT NULL,
    command_kind character varying(32) NOT NULL,
    receipt_id uuid NOT NULL,
    grant_id uuid NOT NULL,
    owner_generation uuid NOT NULL,
    ownership_epoch bigint NOT NULL,
    signer_key_id character varying(64) NOT NULL,
    issued_at timestamp with time zone NOT NULL
);


--
-- Name: keyring_peer_review_resolution; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.keyring_peer_review_resolution (
    receipt_id uuid NOT NULL,
    reviewed_at timestamp with time zone NOT NULL,
    disposition character varying(32) NOT NULL
);


--
-- Name: keyring_reset_run; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.keyring_reset_run (
    run_id uuid NOT NULL,
    keyring_id bigint NOT NULL,
    owner_user_id bigint NOT NULL,
    owner_generation uuid NOT NULL,
    request_key uuid NOT NULL,
    discard_unsent boolean NOT NULL,
    nonce character varying(43) NOT NULL,
    command text NOT NULL,
    created_at timestamp with time zone NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    from_ownership_epoch bigint NOT NULL,
    challenge_id uuid,
    device_nonce character varying(43),
    request_hash character varying(64) NOT NULL,
    challenge_hash character varying(64) NOT NULL,
    proof_hash character varying(64),
    completed_at timestamp with time zone,
    CONSTRAINT ck_keyring_reset_complete CHECK (((completed_at IS NULL) = (proof_hash IS NULL)))
);


--
-- Name: keyring_sync_run; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.keyring_sync_run (
    id bigint NOT NULL,
    sync_run_id uuid NOT NULL,
    keyring_id bigint NOT NULL,
    owner_user_id bigint NOT NULL,
    owner_generation uuid NOT NULL,
    credential_key_id character varying(64) NOT NULL,
    protocol_version character varying(32) NOT NULL,
    settings_version bigint NOT NULL,
    payload_format character varying(64),
    payload bytea NOT NULL,
    payload_hash character varying(64) NOT NULL,
    nonce character varying(43) NOT NULL,
    created_at timestamp with time zone NOT NULL,
    state character varying(16) NOT NULL,
    acked_at timestamp with time zone,
    ack_proof_hash character varying(64),
    request_key uuid NOT NULL,
    request_key_aliases jsonb,
    ack_request_key uuid,
    revision bigint NOT NULL,
    server_proofs jsonb,
    ownership_epoch bigint
);


--
-- Name: TABLE keyring_sync_run; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.keyring_sync_run IS '기기·소유 세대·설정 버전에 고정한 동기화 실행과 불변 payload 및 저장 ACK 증거. 복수 실행의 이력을 덮어쓰지 않는다.';


--
-- Name: COLUMN keyring_sync_run.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.keyring_sync_run.id IS '동기화 실행 내부 ID';


--
-- Name: COLUMN keyring_sync_run.sync_run_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.keyring_sync_run.sync_run_id IS '서버가 발급한 불변 공개 실행 ID';


--
-- Name: COLUMN keyring_sync_run.keyring_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.keyring_sync_run.keyring_id IS '동기화 대상 숫자 keyring.id';


--
-- Name: COLUMN keyring_sync_run.owner_user_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.keyring_sync_run.owner_user_id IS '실행 생성 당시 소유 회원 ID';


--
-- Name: COLUMN keyring_sync_run.owner_generation; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.keyring_sync_run.owner_generation IS '실행 생성 당시 소유 세대. 재등록 이전 ACK의 새 소유 반영을 금지한다.';


--
-- Name: COLUMN keyring_sync_run.credential_key_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.keyring_sync_run.credential_key_id IS '실행에 고정한 서버 대응 기기 검증키 식별자';


--
-- Name: COLUMN keyring_sync_run.protocol_version; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.keyring_sync_run.protocol_version IS '실행 당시 증명 규격. tingle-device-proof-v1';


--
-- Name: COLUMN keyring_sync_run.settings_version; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.keyring_sync_run.settings_version IS '실행에 고정한 양수 설정 버전. ACK 시 현재 설정과 함께 재검증한다.';


--
-- Name: COLUMN keyring_sync_run.payload_format; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.keyring_sync_run.payload_format IS '실행 생성 시 실제 전송 바이트와 함께 고정한 payload 형식 식별자. 이후 형식으로 교체하지 않으며 기존 미상 출처는 NULL로 보존한다. 서버 입력 증거의 형식으로 추정 대체하지 않는다.';


--
-- Name: COLUMN keyring_sync_run.payload; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.keyring_sync_run.payload IS '실제로 전달하는 당시 payload의 불변 원본 바이트. 현재 설정이나 임의 JSON 재직렬화로 대체하지 않는다.';


--
-- Name: COLUMN keyring_sync_run.payload_hash; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.keyring_sync_run.payload_hash IS '불변 payload 원본의 SHA-256 소문자 hex';


--
-- Name: COLUMN keyring_sync_run.nonce; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.keyring_sync_run.nonce IS '이 실행에 고정한 32바이트 canonical unpadded base64url nonce';


--
-- Name: COLUMN keyring_sync_run.created_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.keyring_sync_run.created_at IS '실제 서버 Clock의 최초 실행 생성 시각';


--
-- Name: COLUMN keyring_sync_run.state; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.keyring_sync_run.state IS '동기화 영속 상태 PENDING / ACKED / FAILED. HTTP 응답 투영은 별도 연동';


--
-- Name: COLUMN keyring_sync_run.acked_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.keyring_sync_run.acked_at IS '저장 증명·소유 세대·실행·현재 버전 검사 후 최초 확정한 ACK 시각';


--
-- Name: COLUMN keyring_sync_run.ack_proof_hash; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.keyring_sync_run.ack_proof_hash IS '최초 수용한 저장 증명 바이트 SHA-256 소문자 hex. 중복·상이한 ACK 판정용';


--
-- Name: COLUMN keyring_sync_run.request_key; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.keyring_sync_run.request_key IS '기기·소유 세대 범위 실행 생성 Idempotency-Key';


--
-- Name: COLUMN keyring_sync_run.request_key_aliases; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.keyring_sync_run.request_key_aliases IS '같은 기기·소유 세대·설정 버전의 불변 실행에 추가로 대응시킨 Idempotency-Key의 UUID 문자열 배열(List<String>). 최초 request_key는 유지하고 기기 잠금 아래 검증한 키만 추가한다. 기존 미상 값은 NULL이며 빈 배열과 구분한다.';


--
-- Name: COLUMN keyring_sync_run.ack_request_key; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.keyring_sync_run.ack_request_key IS '최초 수용한 저장 ACK Idempotency-Key';


--
-- Name: COLUMN keyring_sync_run.revision; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.keyring_sync_run.revision IS 'JPA 낙관 잠금 버전. 생성자가 0부터 설정하며 DB default로 생성하지 않는다.';


--
-- Name: COLUMN keyring_sync_run.ownership_epoch; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.keyring_sync_run.ownership_epoch IS '이 실행에 고정한 서명 v2 기기 카운터. 기존 v1 실행은 NULL이며 소급 변환하지 않는다.';


--
-- Name: keyring_sync_run_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.keyring_sync_run ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.keyring_sync_run_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: matching; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.matching (
    id bigint NOT NULL,
    user_low_id bigint,
    user_high_id bigint,
    met_at timestamp with time zone,
    policy_version character varying(30),
    opportunity_expires_at timestamp with time zone,
    counterpart_deadline_at timestamp with time zone,
    evaluation jsonb,
    status public.matching_status NOT NULL,
    proposed_at timestamp with time zone,
    raw_delete_at timestamp with time zone,
    legacy_state jsonb,
    chat_deletion_state jsonb,
    chat_external_cleanup_state jsonb,
    CONSTRAINT ck_matching_withdrawn_pair CHECK ((((user_low_id IS NOT NULL) AND (user_high_id IS NOT NULL)) OR ((user_low_id IS NULL) AND (user_high_id IS NULL) AND (status = 'CLOSED'::public.matching_status))))
);


--
-- Name: TABLE matching; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.matching IS '만남·제안·차단 관계 — 두 사람당 한 줄. 사람별 기록(만남 기록·수락/거절·차단)은 matching_participant에';


--
-- Name: COLUMN matching.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.matching.id IS '매칭 ID — 두 사람당 한 줄. 별도 제안 번호 칸은 없음. 앱이 제안을 조회·수락·거절할 때도 이 번호로 가리킴';


--
-- Name: COLUMN matching.user_low_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.matching.user_low_id IS '작은 쪽 회원 ID — 두 회원 번호 중 작은 쪽. 탈퇴하면 이 행 삭제';


--
-- Name: COLUMN matching.user_high_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.matching.user_high_id IS '큰 쪽 회원 ID — 두 회원 번호 중 큰 쪽. 탈퇴하면 이 행 삭제';


--
-- Name: COLUMN matching.met_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.matching.met_at IS '만남 시각 — 기기 기록을 검증해 확정한 만남 시각';


--
-- Name: COLUMN matching.policy_version; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.matching.policy_version IS '매칭 정책 버전 — 이 만남에 적용한 정책(대기 시간·응답 기한 등)의 버전 이름. 숫자 값은 서버 설정 파일(matching-policy/{버전}.json)에 있음';


--
-- Name: COLUMN matching.opportunity_expires_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.matching.opportunity_expires_at IS '만남 기회 마감 — 만남의 기회 마감. 확정된 만남 시각과 당시 정책으로 고정';


--
-- Name: COLUMN matching.counterpart_deadline_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.matching.counterpart_deadline_at IS '상대 기록 대기 마감 — 상대 기록 대기 마감. 기회 마감과 첫 유효 접수+대기 기간 중 빠른 시각';


--
-- Name: COLUMN matching.evaluation; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.matching.evaluation IS '매칭 판정·이유 — 당시 입력의 최소 근거·판정·규칙 버전과 매칭 이유 설명. 비공개 원응답을 공개 설명에 넣지 않음. AI 설명 상태 {status, attempts, nextRetryAt} 포함(실패 시 주기 작업이 재시도)';


--
-- Name: COLUMN matching.status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.matching.status IS '매칭 상태 — WAITING(한쪽 기록 대기) / NOT_MATCHED / PENDING(제안 보냄) / ONE_ACCEPTED(한쪽만 수락) / ACCEPTED / REJECTED / EXPIRED / CLOSED. WAITING(한쪽 기록만)·NOT_MATCHED(대기 만료·검증 실패)는 다시 만나면 같은 줄을 WAITING으로 재사용. PENDING(제안) 이후는 영구 재매칭 금지. 같은 두 사람은 uq_matching_pair로 한 줄만';


--
-- Name: COLUMN matching.proposed_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.matching.proposed_at IS '제안 시각 — 양쪽 만남 기록 검증 후 서버에서 제안 생성을 확정한 최초 시각. 푸시 발송·수신 시각이 아니다. 응답 기한 24시간의 기산점이며 재전송·알림 실패로 연장하지 않는다.';


--
-- Name: COLUMN matching.raw_delete_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.matching.raw_delete_at IS '만남 원본 삭제 기한 — 제안 생성·대기 만료·최종 검증 불가 기준 시각부터 14일. 행은 남기고 matching_participant.encounter_*만 비운다. 이전 삭제 작업은 같은 matching.id만 보고 새 기록을 삭제하면 안 된다. 선점한 만남 세대·삭제 기한이 현재와 같을 때만 삭제해야 하며 세대 저장 방식은 합의 전이다.';


--
-- Name: COLUMN matching.legacy_state; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.matching.legacy_state IS '기존 pair/proposal/encounter·제한·취소 전용 과거 증거. 최신 응답으로 덮어쓰지 않음';


--
-- Name: COLUMN matching.chat_deletion_state; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.matching.chat_deletion_state IS '채팅 DB 삭제 전용 실행 세대·보관 기한·재시도 및 승인 외부 대상 참조. 채팅 삭제 뒤에도 보존하며 원문·토큰은 저장하지 않음';


--
-- Name: COLUMN matching.chat_external_cleanup_state; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.matching.chat_external_cleanup_state IS '승인된 외부 대상의 삭제·잔존 확인·재시도 상태. DB 삭제와 독립된 검증 세대로 복원 후 재확인하며 원문·토큰은 저장하지 않음';


--
-- Name: matching_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.matching ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.matching_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: matching_participant; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.matching_participant (
    matching_id bigint NOT NULL,
    user_id bigint NOT NULL,
    encounter_keyring_id bigint,
    encounter_signature bytea,
    encounter_reported_at timestamp with time zone,
    encounter_verified_at timestamp with time zone,
    response public.proposal_response,
    responded_at timestamp with time zone,
    blocked_at timestamp with time zone,
    decision_request_key uuid,
    request_state jsonb
);


--
-- Name: TABLE matching_participant; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.matching_participant IS '매칭 참여자별 기록 — 매칭 한 건에 두 사람이니 두 줄. 각자 보낸 만남 기록, 수락/거절, 차단을 사람별로 저장';


--
-- Name: COLUMN matching_participant.matching_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.matching_participant.matching_id IS '매칭 ID — 어느 매칭의 참여자인지(matching.id)';


--
-- Name: COLUMN matching_participant.user_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.matching_participant.user_id IS '회원 ID — 이 줄의 주인. 탈퇴하면 매칭째로 삭제';


--
-- Name: COLUMN matching_participant.encounter_keyring_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.matching_participant.encounter_keyring_id IS '만남을 기록한 키링 ID — 이 사람이 만남 기록을 보낼 때 쓴 키링(keyring.id). 기록 전에는 NULL';


--
-- Name: COLUMN matching_participant.encounter_signature; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.matching_participant.encounter_signature IS '만남 기록 증명 서명 — 키링이 기기 키(마스터 키 + serial_number로 계산, DB 저장 안 함)로 만든 서명. 원본 삭제 기한이 지나면 비움';


--
-- Name: COLUMN matching_participant.encounter_reported_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.matching_participant.encounter_reported_at IS '만남 기록 접수 시각 — 이 사람의 만남 기록을 서버가 받은 시각. NULL이면 아직 기록 안 보냄';


--
-- Name: COLUMN matching_participant.encounter_verified_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.matching_participant.encounter_verified_at IS '만남 기록 검증 시각 — 서명 검증에 성공한 시각. 검증 실패면 NULL로 남고 매칭 상태가 NOT_MATCHED';


--
-- Name: COLUMN matching_participant.response; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.matching_participant.response IS '제안 응답 — ACCEPTED(수락) / REJECTED(거절). NULL이면 아직 응답 안 함. 한 번 정하면 못 바꿈';


--
-- Name: COLUMN matching_participant.responded_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.matching_participant.responded_at IS '응답 시각 — 수락·거절을 누른 시각';


--
-- Name: COLUMN matching_participant.blocked_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.matching_participant.blocked_at IS '차단 시각 — 이 사람이 상대를 차단한 시각. NULL이면 차단 안 함. 차단은 나가기와 구분';


--
-- Name: COLUMN matching_participant.decision_request_key; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.matching_participant.decision_request_key IS '최초 응답 확정 키. 회원 범위 중복 사용 차단과 최초 선택·시각 보존';


--
-- Name: COLUMN matching_participant.request_state; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.matching_participant.request_state IS '명령·재전송 receipt 및 구 취소 키/시각. 새 수락 취소 성공을 뜻하지 않음';


--
-- Name: matching_preference; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.matching_preference (
    user_id bigint NOT NULL,
    matching_enabled boolean NOT NULL,
    age_preference_mode boolean,
    min_age integer,
    max_age integer,
    photo_required boolean NOT NULL
);


--
-- Name: TABLE matching_preference; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.matching_preference IS '매칭 조건 — 회원당 한 줄. 어떤 상대를 매칭할지 회원이 정한 조건. 바꾸면 user_setting.settings_version이 1 올라 키링에 다시 보냄';


--
-- Name: COLUMN matching_preference.user_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.matching_preference.user_id IS '회원 ID — 조건 주인(users.id)';


--
-- Name: COLUMN matching_preference.matching_enabled; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.matching_preference.matching_enabled IS '매칭 사용 여부 — 새 매칭 사용 여부. 가입 시 초깃값 true';


--
-- Name: COLUMN matching_preference.age_preference_mode; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.matching_preference.age_preference_mode IS '희망 나이 지정 여부 — NULL: 아직 선택 안 함 / false: 상관없음 / true: min_age·max_age 범위 지정. 희망 조건 단계 완료에는 직접 선택 필요(NULL 불가)';


--
-- Name: COLUMN matching_preference.min_age; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.matching_preference.min_age IS '희망 최소 나이 — 만 나이, 최소 19. age_preference_mode가 false면 NULL';


--
-- Name: COLUMN matching_preference.max_age; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.matching_preference.max_age IS '희망 최대 나이 — 만 나이. NULL이면 상한 없음. age_preference_mode가 false면 NULL';


--
-- Name: COLUMN matching_preference.photo_required; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.matching_preference.photo_required IS '사진 필수 여부 — 사진 있는 상대만 새 매칭할지. 가입 시 초깃값 false';


--
-- Name: operator_audit; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.operator_audit (
    id bigint NOT NULL,
    operator_id bigint,
    kind public.operator_audit_kind NOT NULL,
    action public.operator_action NOT NULL,
    target_user_id bigint,
    target_service_case_id bigint,
    target_keyring_id bigint,
    target_chat_report_id bigint,
    result_code public.operator_result NOT NULL,
    reason text,
    occurred_at timestamp with time zone NOT NULL
);


--
-- Name: TABLE operator_audit; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.operator_audit IS '운영자 활동 기록 — 운영자가 회원 정보를 보거나 고치거나 권한을 바꾼 일을 남기는 기록';


--
-- Name: COLUMN operator_audit.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.operator_audit.id IS '기록 번호';


--
-- Name: COLUMN operator_audit.operator_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.operator_audit.operator_id IS '운영자 ID — 행위 운영자 ID. 인증 실패 등 확정할 수 없는 경우 NULL';


--
-- Name: COLUMN operator_audit.kind; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.operator_audit.kind IS '기록 종류 — ACCESS: 회원 정보 조회·처리(2년 보관) / PERMISSION: 운영자 권한 부여·회수(3년 보관)';


--
-- Name: COLUMN operator_audit.action; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.operator_audit.action IS '운영 작업 — ACCESS: MEMBER_VIEW(회원 정보 조회) / REPORT_CHAT_VIEW(신고된 채팅 열람) / REPORT_RESOLVE(신고 처리) / PROFILE_CORRECT(프로필 정정) / KEYRING_UNBIND(분실 기기 등록 해제) / MEMBER_WITHDRAW(강제 탈퇴) / CASE_HANDLE(지원 요청 처리) / LOGIN_FAIL(운영 화면 로그인 실패). PERMISSION: OPERATOR_GRANT(운영자 권한 부여) / OPERATOR_REVOKE(운영자 권한 회수). 작업에 따라 target_* 칸 중 해당하는 것만 채움';


--
-- Name: COLUMN operator_audit.target_user_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.operator_audit.target_user_id IS '대상 회원 ID — 조회·정정·강제 탈퇴·권한 부여/회수한 회원(users.id). 대상이 회원이 아니면 NULL';


--
-- Name: COLUMN operator_audit.target_service_case_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.operator_audit.target_service_case_id IS '대상 지원 요청 ID — CASE_HANDLE·PROFILE_CORRECT 등에서 처리한 요청(service_case.id)';


--
-- Name: COLUMN operator_audit.target_keyring_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.operator_audit.target_keyring_id IS '대상 키링 ID — KEYRING_UNBIND에서 등록 해제한 키링(keyring.id)';


--
-- Name: COLUMN operator_audit.target_chat_report_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.operator_audit.target_chat_report_id IS '대상 신고 ID — REPORT_CHAT_VIEW·REPORT_RESOLVE에서 본·처리한 신고(chat_report.id)';


--
-- Name: COLUMN operator_audit.result_code; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.operator_audit.result_code IS '처리 결과 — SUCCESS / DENIED(권한 없음) / FAILED';


--
-- Name: COLUMN operator_audit.reason; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.operator_audit.reason IS '작업 사유 — 운영자가 남긴 이유. 대화 내용·민감 프로필·비밀값은 쓰지 않음';


--
-- Name: COLUMN operator_audit.occurred_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.operator_audit.occurred_at IS '운영 작업 시각 — 서버가 기록한 실제 사건 시각. ACCESS는 발생부터 2년, PERMISSION은 변경부터 3년 보관';


--
-- Name: operator_audit_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.operator_audit ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.operator_audit_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: personality; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.personality (
    id bigint NOT NULL,
    survey_id bigint NOT NULL,
    analysis_version character varying(30) NOT NULL,
    status public.analysis_status NOT NULL,
    result jsonb
);


--
-- Name: TABLE personality; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.personality IS '성향 분석 결과 — 설문 한 번 분석 = 한 줄. 분석 결과만 담고, 매칭 조건은 matching_preference에';


--
-- Name: COLUMN personality.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.personality.id IS '분석 결과 ID — 공개 분석 결과 ID. API resultId';


--
-- Name: COLUMN personality.survey_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.personality.survey_id IS '설문 회차 ID — 분석한 완료 설문 회차 ID. 회원은 survey에서 확인';


--
-- Name: COLUMN personality.analysis_version; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.personality.analysis_version IS '분석 규칙 버전 — 채점·유형 계산 규칙의 버전 이름. 규칙 내용은 DB가 아니라 서버 설정 파일(analysis/{버전}.json)에 있음';


--
-- Name: COLUMN personality.status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.personality.status IS '분석 상태 — PENDING / SUCCEEDED / INSUFFICIENT / FAILED. PENDING으로 5분 넘게 멈췄거나 FAILED이고 재시도 횟수가 남았으면 주기 작업이 다시 분석';


--
-- Name: COLUMN personality.result; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.personality.result IS '분석 결과 — 내부 점수·성향 데이터·사용 버전 또는 재확인/실패의 최소 정보. 재시도 정보 {attempts, nextRetryAt, lastErrorCode} 포함';


--
-- Name: personality_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.personality ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.personality_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: profile; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.profile (
    user_id bigint NOT NULL,
    nickname character varying(20),
    birth_date date,
    gender public.gender,
    smoking_status public.smoking_status,
    drinking_frequency public.drinking_frequency,
    religion public.religion,
    introduction text,
    photo_url character varying(2048),
    completed_at timestamp with time zone,
    active_personality_id bigint,
    CONSTRAINT ck_profile_photo_url CHECK (((photo_url IS NULL) OR ((photo_url)::text ~ '^profile/[0-9]+/[0-9a-f-]{36}\.jpg$'::text)))
);


--
-- Name: TABLE profile; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.profile IS '프로필';


--
-- Name: COLUMN profile.user_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.profile.user_id IS '회원 ID — 프로필 소유 회원 ID';


--
-- Name: COLUMN profile.nickname; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.profile.nickname IS '닉네임 — NFC·앞뒤 일반 공백 정리 후 2~20자. 중복 금지는 lower(nickname) unique 인덱스(대소문자 무시)';


--
-- Name: COLUMN profile.birth_date; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.profile.birth_date IS '생년월일 — 생년월일. 완료 전 정정 가능, 완료 후 사용자가 직접 변경할 수 없음';


--
-- Name: COLUMN profile.gender; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.profile.gender IS '성별 — 본인 성별 MALE / FEMALE. 상대 성별은 반대로 계산';


--
-- Name: COLUMN profile.smoking_status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.profile.smoking_status IS '흡연 여부 — 필수 흡연 선택 코드. 프로필 코드와 설문 답변을 임의 변환하지 않음';


--
-- Name: COLUMN profile.drinking_frequency; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.profile.drinking_frequency IS '음주 빈도 — 필수 음주 빈도 선택 코드';


--
-- Name: COLUMN profile.religion; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.profile.religion IS '종교 — 별도 동의를 받은 선택 종교 정보. 비공개·미입력을 무교로 추정하지 않음';


--
-- Name: COLUMN profile.introduction; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.profile.introduction IS '자기소개 — 선택 자기소개. 최대 200자·줄바꿈 최대 3개 등 명세 기준 적용';


--
-- Name: COLUMN profile.photo_url; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.profile.photo_url IS '프로필 사진 파일 키. NULL이면 사진 없음. 서버 사진 폴더(tingle.photo.dir) 기준 profile/{userId}/{uuid}.jpg만 저장하며 절대 경로·URL·쿼리 문자열은 저장하지 않는다. 응답은 권한 검증을 거친 사진 API로 제공한다.';


--
-- Name: COLUMN profile.completed_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.profile.completed_at IS '프로필 완료 시각 — 프로필 최종 등록 성공 시각. 초안 저장 시에는 설정하지 않음';


--
-- Name: COLUMN profile.active_personality_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.profile.active_personality_id IS '적용 중인 분석 ID — 현재 새 매칭에 적용하는 유효 분석 결과 ID. 재검사 대기·실패 중에는 기존 값 유지';


--
-- Name: push_installation; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.push_installation (
    id bigint NOT NULL,
    client_installation_id uuid NOT NULL,
    user_id bigint,
    login_family_id uuid,
    binding_id uuid,
    linked_at timestamp with time zone,
    platform public.device_platform NOT NULL,
    token_ciphertext text NOT NULL,
    updated_at timestamp with time zone NOT NULL
);


--
-- Name: TABLE push_installation; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.push_installation IS '푸시 알림 기기';


--
-- Name: COLUMN push_installation.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.push_installation.id IS '설치 번호';


--
-- Name: COLUMN push_installation.client_installation_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.push_installation.client_installation_id IS '앱 설치 ID — 앱이 발급, 재설치 전까지 유지';


--
-- Name: COLUMN push_installation.user_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.push_installation.user_id IS '연결된 회원 ID — 로그아웃하면 비움';


--
-- Name: COLUMN push_installation.login_family_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.push_installation.login_family_id IS '연결된 로그인 기기 — refresh_token.family_id. 그 로그인이 끝나면 비움. 알림 기기 목록 기준';


--
-- Name: COLUMN push_installation.binding_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.push_installation.binding_id IS '현재 수신 연결 세대 UUID — 최초 연결·재연결 시 발급, 같은 family 토큰 교체에는 유지. linked_at과 함께 저장·해제하며 로그아웃 때 둘 다 비움. 과거 행 누락 값 추정 금지';


--
-- Name: COLUMN push_installation.linked_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.push_installation.linked_at IS '현재 수신 연결 최초 확정 시각 — binding_id와 함께 저장·해제. 토큰 교체·재시도로 연장하지 않으며 updated_at·현재 시각으로 과거 값을 생성하지 않음';


--
-- Name: COLUMN push_installation.platform; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.push_installation.platform IS '기기 종류 — ANDROID / IOS';


--
-- Name: COLUMN push_installation.token_ciphertext; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.push_installation.token_ciphertext IS '푸시 토큰 — 암호화한 FCM 토큰';


--
-- Name: COLUMN push_installation.updated_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.push_installation.updated_at IS '마지막 갱신 시각';


--
-- Name: push_installation_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.push_installation ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.push_installation_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: refresh_token; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.refresh_token (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    family_id uuid NOT NULL,
    token_hash bytea NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    used_at timestamp with time zone,
    revoked_at timestamp with time zone
);


--
-- Name: TABLE refresh_token; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.refresh_token IS '로그인 토큰(기기별)';


--
-- Name: COLUMN refresh_token.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.refresh_token.id IS '토큰 번호';


--
-- Name: COLUMN refresh_token.user_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.refresh_token.user_id IS '회원 ID';


--
-- Name: COLUMN refresh_token.family_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.refresh_token.family_id IS '로그인 세션 ID(sid) — 재발급된 토큰은 같은 값. 로그인 기기는 1개라 새로 로그인하면 이전 family는 모두 폐기';


--
-- Name: COLUMN refresh_token.token_hash; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.refresh_token.token_hash IS '리프레시 토큰 해시 — 원문 저장 안 함';


--
-- Name: COLUMN refresh_token.expires_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.refresh_token.expires_at IS '만료 시각';


--
-- Name: COLUMN refresh_token.used_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.refresh_token.used_at IS '재발급에 쓴 시각 — 이미 쓴 토큰이 다시 오면 그 family 전체 폐기(탈취 감지)';


--
-- Name: COLUMN refresh_token.revoked_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.refresh_token.revoked_at IS '폐기 시각 — 로그아웃·탈취 감지·탈퇴';


--
-- Name: refresh_token_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.refresh_token ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.refresh_token_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: registration_challenge; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.registration_challenge (
    id bigint NOT NULL,
    challenge_id uuid NOT NULL,
    user_id bigint NOT NULL,
    request_key uuid NOT NULL,
    nonce character varying(43) NOT NULL,
    protocol_version character varying(32) NOT NULL,
    issued_at timestamp with time zone NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    consumed_at timestamp with time zone,
    registered_keyring_id bigint,
    registered_owner_generation uuid,
    registered_key_id character varying(64),
    registration_proof_hash character varying(64),
    registration_request_key uuid,
    registration_request_hash character varying(64),
    registered_firmware_version character varying(30),
    registered_status text,
    revision bigint NOT NULL
);


--
-- Name: TABLE registration_challenge; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.registration_challenge IS '회원별 등록 증명 challenge와 단회 소비·최초 등록 결과. 기기 미지정 발급과 병렬 요청을 보존한다.';


--
-- Name: COLUMN registration_challenge.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.registration_challenge.id IS '등록 challenge 내부 ID';


--
-- Name: COLUMN registration_challenge.challenge_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.registration_challenge.challenge_id IS '서버가 발급한 공개 challenge ID. 같은 등록 요청의 재시도는 같은 ID를 사용한다.';


--
-- Name: COLUMN registration_challenge.user_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.registration_challenge.user_id IS 'challenge를 발급받은 본인 회원 ID';


--
-- Name: COLUMN registration_challenge.request_key; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.registration_challenge.request_key IS '회원 범위 등록 challenge 발급 Idempotency-Key';


--
-- Name: COLUMN registration_challenge.nonce; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.registration_challenge.nonce IS '기기 등록 서명에 포함하는 32바이트 canonical unpadded base64url nonce. 회원 귀속·단회 소비와 함께 검증한다.';


--
-- Name: COLUMN registration_challenge.protocol_version; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.registration_challenge.protocol_version IS '발급 시 고정한 등록 증명 규격. tingle-device-proof-v1';


--
-- Name: COLUMN registration_challenge.issued_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.registration_challenge.issued_at IS '실제 서버 Clock의 최초 발급 시각';


--
-- Name: COLUMN registration_challenge.expires_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.registration_challenge.expires_at IS '최초 발급 시각부터 5분인 만료 시각. 재시도로 연장하지 않는다.';


--
-- Name: COLUMN registration_challenge.consumed_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.registration_challenge.consumed_at IS '검증된 등록과 같은 원자 처리에서 단회 소비한 실제 서버 시각';


--
-- Name: COLUMN registration_challenge.registered_keyring_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.registration_challenge.registered_keyring_id IS '최초 성공한 등록 기기. 소비 전에는 NULL';


--
-- Name: COLUMN registration_challenge.registered_owner_generation; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.registration_challenge.registered_owner_generation IS '최초 성공한 소유 세대. 같은 등록 재시도의 결과 검증에 사용하며 새로운 세대로 바꾸지 않는다.';


--
-- Name: COLUMN registration_challenge.registered_key_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.registration_challenge.registered_key_id IS '등록 성공 시 검증한 서버 대응 검증키 식별자';


--
-- Name: COLUMN registration_challenge.registration_proof_hash; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.registration_challenge.registration_proof_hash IS '등록에 성공한 증명 바이트의 SHA-256 소문자 hex. 상이한 재시도 판정용';


--
-- Name: COLUMN registration_challenge.registration_request_key; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.registration_challenge.registration_request_key IS '회원별 등록 API 전체 범위에서 최초 성공 요청에 고정한 Idempotency-Key. challenge 발급 키와 구분하며 기존 미상 값은 NULL.';


--
-- Name: COLUMN registration_challenge.registration_request_hash; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.registration_challenge.registration_request_hash IS '최초 등록 요청 본문 비교용 SHA-256 소문자 hex. 같은 키의 다른 본문을 거부하며 기존 요청을 추정해 채우지 않는다.';


--
-- Name: COLUMN registration_challenge.registered_firmware_version; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.registration_challenge.registered_firmware_version IS '최초 등록 성공 응답의 firmwareVersion. 재시도 시 현재 기기 값 대신 원래 응답을 사용하며 기존 미상 값은 NULL.';


--
-- Name: COLUMN registration_challenge.registered_status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.registration_challenge.registered_status IS '최초 등록 성공 응답의 REGISTERED 또는 SYNC_PENDING 상태. 이후 동기화 상태로 원래 응답을 덮어쓰지 않으며 기존 미상 값은 NULL.';


--
-- Name: COLUMN registration_challenge.revision; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.registration_challenge.revision IS 'JPA 낙관 잠금 버전. 생성자가 0부터 설정하며 DB default로 생성하지 않는다.';


--
-- Name: registration_challenge_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.registration_challenge ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.registration_challenge_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: rule_artifact; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.rule_artifact (
    id uuid NOT NULL,
    kind character varying(30) NOT NULL,
    version character varying(100) NOT NULL,
    schema_version character varying(100) NOT NULL,
    revision integer NOT NULL,
    document jsonb NOT NULL,
    content_hash character varying(64) NOT NULL,
    created_by bigint NOT NULL,
    created_at timestamp with time zone NOT NULL,
    published_at timestamp with time zone
);


--
-- Name: TABLE rule_artifact; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.rule_artifact IS '규칙 정의 — 설문 문항·이관 규칙 등 한 버전. 게시 전 초안만 수정, 게시 후 불변';


--
-- Name: COLUMN rule_artifact.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.rule_artifact.id IS '정의 ID — API artifactId';


--
-- Name: COLUMN rule_artifact.kind; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.rule_artifact.kind IS '종류 — QUESTIONNAIRE / MIGRATION 등(RuleKind). 형식은 kind+schema_version 으로 검사';


--
-- Name: COLUMN rule_artifact.version; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.rule_artifact.version IS '버전 이름 — 종류 안에서 유일. 설문은 survey.survey_version 으로 쓰여 30자 이하';


--
-- Name: COLUMN rule_artifact.schema_version; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.rule_artifact.schema_version IS '정의 형식 버전 — 서버에 등록된 형식만 허용';


--
-- Name: COLUMN rule_artifact.revision; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.rule_artifact.revision IS '초안 수정 번호 — 0부터, 수정마다 1 증가. If-Match 조건부 수정에 사용';


--
-- Name: COLUMN rule_artifact.document; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.rule_artifact.document IS '정의 본문 — 키를 정렬한 JSON. 실행 코드가 아닌 데이터';


--
-- Name: COLUMN rule_artifact.content_hash; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.rule_artifact.content_hash IS '내용 해시 — kind·version·schema_version·document 의 SHA-256';


--
-- Name: COLUMN rule_artifact.created_by; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.rule_artifact.created_by IS '만든 운영자 ID(users.id)';


--
-- Name: COLUMN rule_artifact.created_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.rule_artifact.created_at IS '만든 시각';


--
-- Name: COLUMN rule_artifact.published_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.rule_artifact.published_at IS '게시 시각 — NULL 이면 초안. 값이 있으면 수정 불가';


--
-- Name: rule_audit; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.rule_audit (
    id bigint NOT NULL,
    action character varying(30) NOT NULL,
    target_id uuid,
    revision integer,
    content_hash character varying(64),
    detail jsonb,
    reason text,
    actor_id bigint,
    occurred_at timestamp with time zone NOT NULL
);


--
-- Name: TABLE rule_audit; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.rule_audit IS '규칙 관리 작업 기록 — 생성·수정·검증·승인·게시·전환. 수정·삭제하지 않음';


--
-- Name: COLUMN rule_audit.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.rule_audit.id IS '기록 번호';


--
-- Name: COLUMN rule_audit.action; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.rule_audit.action IS '작업 — ARTIFACT_CREATE / ARTIFACT_UPDATE / RELEASE_CREATE / RELEASE_VALIDATE / RELEASE_APPROVE / RELEASE_PUBLISH / CHANNEL_SWITCH';


--
-- Name: COLUMN rule_audit.target_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.rule_audit.target_id IS '대상 정의·조합 ID. 채널을 파일 버전으로 되돌리면 NULL';


--
-- Name: COLUMN rule_audit.revision; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.rule_audit.revision IS '정의 수정 번호 — 정의 작업만';


--
-- Name: COLUMN rule_audit.content_hash; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.rule_audit.content_hash IS '그때의 정의 내용 해시 또는 조합 후보 해시';


--
-- Name: COLUMN rule_audit.detail; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.rule_audit.detail IS '부가 정보 — 검증 결과(검사별 기대값·실제값), 채널 전환 전 조합';


--
-- Name: COLUMN rule_audit.reason; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.rule_audit.reason IS '변경 이유 — 운영자가 남긴 이유';


--
-- Name: COLUMN rule_audit.actor_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.rule_audit.actor_id IS '작업한 운영자 ID(users.id)';


--
-- Name: COLUMN rule_audit.occurred_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.rule_audit.occurred_at IS '작업 시각';


--
-- Name: rule_audit_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.rule_audit ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.rule_audit_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: rule_channel; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.rule_channel (
    name character varying(30) NOT NULL,
    release_id uuid,
    survey_version character varying(30),
    updated_at timestamp with time zone NOT NULL
);


--
-- Name: TABLE rule_channel; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.rule_channel IS '새 설문 회차 기본 조합 포인터 — 행은 current 하나. 기존 회차·결과에는 영향 없음';


--
-- Name: COLUMN rule_channel.name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.rule_channel.name IS '채널 이름 — current';


--
-- Name: COLUMN rule_channel.release_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.rule_channel.release_id IS '가리키는 게시 조합(rule_release.id). NULL 이면 서버 설정 파일 버전';


--
-- Name: COLUMN rule_channel.survey_version; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.rule_channel.survey_version IS '가리키는 조합의 설문 버전 사본 — 새 회차 버전 조회용';


--
-- Name: COLUMN rule_channel.updated_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.rule_channel.updated_at IS '마지막 전환 시각';


--
-- Name: rule_release; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.rule_release (
    id uuid NOT NULL,
    name character varying(100) NOT NULL,
    engine_version character varying(100) NOT NULL,
    artifact_ids jsonb NOT NULL,
    created_by bigint NOT NULL,
    created_at timestamp with time zone NOT NULL,
    validation_id uuid,
    validation_hash character varying(64),
    validation_passed boolean,
    approval_id uuid,
    approval_hash character varying(64),
    approved_by bigint,
    approved_at timestamp with time zone,
    published_by bigint,
    published_at timestamp with time zone
);


--
-- Name: TABLE rule_release; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.rule_release IS '규칙 조합 — 종류별 정의 하나씩 묶어 검증·승인·게시하는 단위. 조합은 생성 후 불변';


--
-- Name: COLUMN rule_release.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.rule_release.id IS '조합 ID — API releaseId';


--
-- Name: COLUMN rule_release.name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.rule_release.name IS '조합 이름';


--
-- Name: COLUMN rule_release.engine_version; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.rule_release.engine_version IS '실행할 규칙 엔진 버전 — 서버 엔진과 같아야 검증 통과';


--
-- Name: COLUMN rule_release.artifact_ids; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.rule_release.artifact_ids IS '조합에 든 정의 ID 배열(rule_artifact.id 문자열)';


--
-- Name: COLUMN rule_release.created_by; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.rule_release.created_by IS '만든 운영자 ID(users.id)';


--
-- Name: COLUMN rule_release.created_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.rule_release.created_at IS '만든 시각';


--
-- Name: COLUMN rule_release.validation_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.rule_release.validation_id IS '마지막 검증 번호 — API validationRunId. 검증 결과 상세는 rule_audit.detail';


--
-- Name: COLUMN rule_release.validation_hash; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.rule_release.validation_hash IS '마지막 검증 때 후보 해시 — 지금 후보 해시와 다르면 지난 검증';


--
-- Name: COLUMN rule_release.validation_passed; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.rule_release.validation_passed IS '마지막 검증 통과 여부';


--
-- Name: COLUMN rule_release.approval_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.rule_release.approval_id IS '마지막 승인 번호';


--
-- Name: COLUMN rule_release.approval_hash; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.rule_release.approval_hash IS '승인한 후보 해시 — 지금 후보 해시와 다르면 승인 무효';


--
-- Name: COLUMN rule_release.approved_by; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.rule_release.approved_by IS '승인한 운영자 ID(users.id) — 조합·정의 작성자와 달라야 함';


--
-- Name: COLUMN rule_release.approved_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.rule_release.approved_at IS '승인 시각';


--
-- Name: COLUMN rule_release.published_by; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.rule_release.published_by IS '게시한 운영자 ID(users.id)';


--
-- Name: COLUMN rule_release.published_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.rule_release.published_at IS '게시 시각 — NULL 이면 미게시. 값이 있으면 불변';


--
-- Name: service_case; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.service_case (
    id bigint NOT NULL,
    requester_user_id bigint NOT NULL,
    kind public.service_case_kind NOT NULL,
    reason text NOT NULL,
    correction_field public.profile_correction_field,
    correction_value character varying(100),
    lost_keyring_id bigint,
    status public.service_case_status NOT NULL,
    assigned_operator_id bigint,
    created_at timestamp with time zone NOT NULL,
    reply_due_at timestamp with time zone,
    closed_at timestamp with time zone
);


--
-- Name: TABLE service_case; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.service_case IS '정정·분실 지원 요청 — 요청 한 건 = 한 줄. 처리하며 주고받은 기록은 service_case_history에';


--
-- Name: COLUMN service_case.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.service_case.id IS '지원 요청 ID — 요청 공개 ID. 접수 결과·추가 확인·처리 상태 조회에 사용';


--
-- Name: COLUMN service_case.requester_user_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.service_case.requester_user_id IS '요청 회원 ID — 서버 인증으로 확인한 요청 회원 ID';


--
-- Name: COLUMN service_case.kind; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.service_case.kind IS '지원 요청 종류 — 요청 종류. 설계 코드: PROFILE_CORRECTION / LOST_WITHDRAWAL';


--
-- Name: COLUMN service_case.reason; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.service_case.reason IS '요청 사유 — 회원이 직접 쓴 설명. 증빙 사진·파일 사본은 저장 안 함';


--
-- Name: COLUMN service_case.correction_field; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.service_case.correction_field IS '정정할 항목 — PROFILE_CORRECTION일 때만. 분실 탈퇴 요청이면 NULL';


--
-- Name: COLUMN service_case.correction_value; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.service_case.correction_value IS '바꿔 달라는 값 — PROFILE_CORRECTION일 때만. 예: 생년월일 1999-03-01';


--
-- Name: COLUMN service_case.lost_keyring_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.service_case.lost_keyring_id IS '분실한 키링 ID — LOST_WITHDRAWAL일 때만(keyring.id). 정정 요청이면 NULL';


--
-- Name: COLUMN service_case.status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.service_case.status IS '요청 처리 상태 — 설계 코드: RECEIVED / REVIEWING / NEEDS_INFO / COMPLETED / REJECTED / NO_RESPONSE';


--
-- Name: COLUMN service_case.assigned_operator_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.service_case.assigned_operator_id IS '담당 운영자 ID — 현재 담당 운영자 ID. 배정 전 NULL';


--
-- Name: COLUMN service_case.created_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.service_case.created_at IS '요청 접수 시각 — 최초 접수 성공 시각';


--
-- Name: COLUMN service_case.reply_due_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.service_case.reply_due_at IS '추가 확인 응답 기한 — 유효한 추가 확인 전달 후 사용자 응답 마감. 14일 무응답 처리용이며 전달 실패로 시작하지 않음';


--
-- Name: COLUMN service_case.closed_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.service_case.closed_at IS '요청 종료 시각 — 완료·반려·응답 없음의 최초 종료 시각. 원칙적으로 이 시각+720시간에 업무 기록 삭제';


--
-- Name: service_case_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.service_case_history (
    id bigint NOT NULL,
    service_case_id bigint NOT NULL,
    event public.service_case_event NOT NULL,
    actor_user_id bigint,
    message text,
    created_at timestamp with time zone NOT NULL
);


--
-- Name: TABLE service_case_history; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.service_case_history IS '지원 요청 처리 기록 — 추가 확인 요청·회원 답변·운영 작업 결과를 일어난 순서대로 한 줄씩';


--
-- Name: COLUMN service_case_history.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.service_case_history.id IS '처리 기록 번호';


--
-- Name: COLUMN service_case_history.service_case_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.service_case_history.service_case_id IS '지원 요청 ID — service_case.id';


--
-- Name: COLUMN service_case_history.event; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.service_case_history.event IS '무슨 일 — INFO_REQUESTED / USER_REPLIED / ACTION_DONE / ACTION_FAILED / STATUS_CHANGED';


--
-- Name: COLUMN service_case_history.actor_user_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.service_case_history.actor_user_id IS '한 사람 — 운영자나 요청 회원(users.id). 시스템 자동 처리면 NULL';


--
-- Name: COLUMN service_case_history.message; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.service_case_history.message IS '내용 — 추가 확인 질문, 회원 답변, 실패 사유 등';


--
-- Name: COLUMN service_case_history.created_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.service_case_history.created_at IS '기록 시각';


--
-- Name: service_case_history_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.service_case_history ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.service_case_history_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: service_case_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.service_case ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.service_case_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: settings_snapshot; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.settings_snapshot (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    settings_version bigint NOT NULL,
    input_format character varying(64) NOT NULL,
    input_json text NOT NULL,
    input_hash character varying(64) NOT NULL,
    captured_at timestamp with time zone NOT NULL,
    redacted_at timestamp with time zone,
    redaction_reason character varying(40)
);


--
-- Name: TABLE settings_snapshot; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.settings_snapshot IS '사용자·설정 버전별 불변 입력 보존 기반; 소비 형식/준비 검증 별도';


--
-- Name: COLUMN settings_snapshot.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.settings_snapshot.id IS '내부 저장 ID';


--
-- Name: COLUMN settings_snapshot.user_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.settings_snapshot.user_id IS '입력 소유 사용자';


--
-- Name: COLUMN settings_snapshot.settings_version; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.settings_snapshot.settings_version IS '당시 서버 설정 세대';


--
-- Name: COLUMN settings_snapshot.input_format; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.settings_snapshot.input_format IS '호출자가 명시한 입력 형식 버전';


--
-- Name: COLUMN settings_snapshot.input_json; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.settings_snapshot.input_json IS '정규화 불변 JSON 입력 증거; READY 승인 아님';


--
-- Name: COLUMN settings_snapshot.input_hash; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.settings_snapshot.input_hash IS '정규화 UTF8 입력 SHA256';


--
-- Name: COLUMN settings_snapshot.captured_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.settings_snapshot.captured_at IS '실제 보존 시각';


--
-- Name: COLUMN settings_snapshot.redacted_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.settings_snapshot.redacted_at IS '동의 철회로 입력 값 일부를 지운 시각; 지운 사본은 해시도 0으로 지워 근거로 쓰지 않음';


--
-- Name: COLUMN settings_snapshot.redaction_reason; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.settings_snapshot.redaction_reason IS '값을 지운 사유(예: PROFILE_RELIGION_WITHDRAWN); 지운 원문은 남기지 않음';


--
-- Name: settings_snapshot_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.settings_snapshot ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.settings_snapshot_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: survey; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.survey (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    survey_version character varying(30) NOT NULL,
    completed_at timestamp with time zone,
    client_request_id uuid,
    revision bigint DEFAULT 0 NOT NULL,
    legacy_public_id uuid,
    response_context jsonb,
    definition_snapshot jsonb
);


--
-- Name: TABLE survey; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.survey IS '설문 회차 — 설문 한 번 = 한 줄. 문항별 답은 survey_answer에';


--
-- Name: COLUMN survey.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.survey.id IS '설문 회차 ID — 공개 설문 회차 ID. API sessionId';


--
-- Name: COLUMN survey.user_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.survey.user_id IS '회원 ID — 응답 소유 회원 ID';


--
-- Name: COLUMN survey.survey_version; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.survey.survey_version IS '설문 버전 — 회차 시작 때 고정한 설문 버전 이름. 문항·보기 내용은 DB가 아니라 서버 설정 파일(survey/{버전}.json)에 있음';


--
-- Name: COLUMN survey.completed_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.survey.completed_at IS '설문 제출 시각 — 제출 완료 시각. NULL이면 진행 중이며 계산 성공과는 별개';


--
-- Name: COLUMN survey.client_request_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.survey.client_request_id IS '과거 최초 생성 요청 키 보존용. 현재 회차 시작 API는 이 값을 받거나 새로 채우지 않으며 회원·버전별 미완료 회차를 재사용한다. 기존 미상은 NULL.';


--
-- Name: COLUMN survey.revision; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.survey.revision IS '과거 응답 변경 번호 보존용. 현재 API는 이 값을 ETag로 사용하거나 JPA @Version으로 증가시키지 않는다. 기존 값은 유지하며 새 회차 초기값은 0이다.';


--
-- Name: COLUMN survey.legacy_public_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.survey.legacy_public_id IS '과거 공개 회차 UUID. 기존 값만 보존하고 신규 숫자 ID와 구분';


--
-- Name: COLUMN survey.response_context; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.survey.response_context IS '과거 answers 외 원본 업무 문맥 보존용. 현재 설문 API는 새 값을 채우거나 읽지 않으며 문항별 답은 survey_answer에 저장한다.';


--
-- Name: COLUMN survey.definition_snapshot; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.survey.definition_snapshot IS '과거 시작 시 검증한 문항·manifest 사본 보존용. 현재 설문 API는 새 사본을 채우거나 읽지 않고 회차의 survey_version 파일을 사용한다. 기존 미상은 NULL이며 추정 복원하지 않는다.';


--
-- Name: survey_answer; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.survey_answer (
    survey_id bigint NOT NULL,
    question_id character varying(50) NOT NULL,
    answer_value character varying(100) NOT NULL,
    answered_at timestamp with time zone,
    answer_type text
);


--
-- Name: TABLE survey_answer; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.survey_answer IS '설문 문항별 답 — 설문 한 번에 문항 수만큼 줄. 문항·보기 내용은 설문 파일(survey/{버전}.json)에 있고 여기엔 고른 값만';


--
-- Name: COLUMN survey_answer.survey_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.survey_answer.survey_id IS '설문 회차 ID — survey.id';


--
-- Name: COLUMN survey_answer.question_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.survey_answer.question_id IS '문항 ID — 설문 파일에 있는 문항 번호(예: Q07)';


--
-- Name: COLUMN survey_answer.answer_value; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.survey_answer.answer_value IS '설문 버전 규칙으로 검증한 답의 저장 문자열(최대 100자). STRING은 원문, NUMBER는 후행 0 없는 수치 표기, BOOLEAN/NULL은 JSON 표기, ARRAY는 문자열·숫자 항목 배열의 JSON 표기다. 문자열 "4"를 숫자 4로 추정하지 않는다.';


--
-- Name: COLUMN survey_answer.answered_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.survey_answer.answered_at IS '답한 시각 — 같은 문항을 다시 고르면 값과 시각을 덮어씀；구 JSON에 문항별 시각이 없으면 NULL. 현재·완료 시각 추정 금지';


--
-- Name: COLUMN survey_answer.answer_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.survey_answer.answer_type IS '답 원래 형식 STRING/NUMBER/BOOLEAN/NULL/ARRAY. ARRAY는 문자열·숫자 항목 배열이며 새 enum 타입을 만들지 않는다. 과거 형식 미상은 NULL로 보존하고 추정하지 않는다.';


--
-- Name: survey_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.survey ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.survey_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: user_block; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_block (
    id uuid NOT NULL,
    owner_id bigint NOT NULL,
    target_id bigint NOT NULL,
    matching_id bigint NOT NULL,
    created_at timestamp with time zone NOT NULL,
    CONSTRAINT ck_user_block_distinct CHECK ((owner_id <> target_id))
);


--
-- Name: user_consents; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_consents (
    consent_id bigint NOT NULL,
    user_id bigint NOT NULL,
    consent_type text NOT NULL,
    document_version text NOT NULL,
    agreed boolean NOT NULL,
    recorded_at timestamp with time zone NOT NULL
);


--
-- Name: TABLE user_consents; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.user_consents IS '항목별 동의·철회 이력 — 문서 버전·참거짓·기록 시각을 수정 없이 누적하며 users.agreed_at으로 합치거나 과거 일괄 동의를 추정하지 않음';


--
-- Name: COLUMN user_consents.consent_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.user_consents.consent_id IS '동의 이력 ID — 기존 기록 순서와 번호를 보존';


--
-- Name: COLUMN user_consents.user_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.user_consents.user_id IS '동의한 회원 ID — users.id';


--
-- Name: COLUMN user_consents.consent_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.user_consents.consent_type IS '동의 항목 — 설정 파일에 정의된 약관·개인정보·AI 등 정확한 항목 코드';


--
-- Name: COLUMN user_consents.document_version; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.user_consents.document_version IS '사용자가 확인한 정확한 문서 버전 — 최신 버전으로 덮어쓰지 않음';


--
-- Name: COLUMN user_consents.agreed; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.user_consents.agreed IS '해당 항목 동의 여부 — 동의 true, 거절·철회 false. 선택 동의를 일괄 true로 추정하지 않음';


--
-- Name: COLUMN user_consents.recorded_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.user_consents.recorded_at IS '동의·거절·철회가 확정된 서버 시각 — 기존 시각 보존';


--
-- Name: user_consents_consent_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.user_consents ALTER COLUMN consent_id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.user_consents_consent_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: user_setting; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_setting (
    user_id bigint NOT NULL,
    vibration_enabled boolean NOT NULL,
    led_enabled boolean NOT NULL,
    settings_version bigint NOT NULL
);


--
-- Name: TABLE user_setting; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.user_setting IS '회원 설정 — 회원당 한 줄. 키링 진동·LED와 키링에 보낼 설정 버전';


--
-- Name: COLUMN user_setting.user_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.user_setting.user_id IS '회원 ID — 설정 소유 회원 ID';


--
-- Name: COLUMN user_setting.vibration_enabled; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.user_setting.vibration_enabled IS '진동 사용 여부 — 키링 진동 사용 여부. 가입 시 초깃값 true';


--
-- Name: COLUMN user_setting.led_enabled; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.user_setting.led_enabled IS 'LED 사용 여부 — 키링 LED 사용 여부. 가입 시 초깃값 true';


--
-- Name: COLUMN user_setting.settings_version; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.user_setting.settings_version IS '매칭 성향 설정 버전 — 키링에 보낼 매칭 조건·진동/LED·적용 성향·제외 상대가 바뀌는 같은 트랜잭션에서 1 증가한다. 저장 성공일 뿐 기기 적용은 keyring.synced_settings_version으로 따로 확인한다.';


--
-- Name: users; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.users (
    id bigint NOT NULL,
    provider public.auth_provider NOT NULL,
    provider_user_id character varying(255) NOT NULL,
    email character varying(320),
    signup_completed_at timestamp with time zone,
    agreed_at timestamp with time zone,
    is_operator boolean DEFAULT false NOT NULL,
    status text NOT NULL,
    created_at timestamp with time zone NOT NULL
);


--
-- Name: TABLE users; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.users IS '회원·인증·동의';


--
-- Name: COLUMN users.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.users.id IS '회원 ID — 서비스 회원 번호. DB가 1씩 올려 발급(재사용 안 함). 탈퇴 후 재가입하면 새 번호';


--
-- Name: COLUMN users.provider; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.users.provider IS '소셜 로그인 종류 — 소셜 제공자 코드: GOOGLE / KAKAO / NAVER';


--
-- Name: COLUMN users.provider_user_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.users.provider_user_id IS '소셜 계정 고유값 — 서버가 제공자 인증으로 확인한 소셜 계정 고유값';


--
-- Name: COLUMN users.email; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.users.email IS '연락용 이메일 — 동의 조건을 충족한 연락용 이메일. 미제공·철회·탈퇴 시 정리하며 계정 식별이나 상대 공개에 사용하지 않음';


--
-- Name: COLUMN users.signup_completed_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.users.signup_completed_at IS '가입 완료 시각 — 최초 가입을 모두 완료한 시각';


--
-- Name: COLUMN users.agreed_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.users.agreed_at IS '일괄 동의 시각 — 첫 화면에서 약관·개인정보·AI 분석 등에 한 번에 동의한 시각. 비어 있거나 새 약관 시행일보다 이르면 재동의 받음';


--
-- Name: COLUMN users.is_operator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.users.is_operator IS '운영자 여부 — 운영 화면 접근 가능 여부. 일반 회원은 false';


--
-- Name: COLUMN users.status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.users.status IS '회원 상태 — 기존 ACTIVE / WITHDRAWAL_PENDING / SUSPENDED / WITHDRAWN 값을 보존. 정지·탈퇴 계정을 ACTIVE로 추정하지 않음';


--
-- Name: COLUMN users.created_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.users.created_at IS '회원 생성 시각 — 최초 소셜 로그인 때 서버가 확정한 기존 시각을 보존. 이관 시 현재 시각으로 바꾸지 않음';


--
-- Name: users_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.users ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.users_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: withdrawal_cooldown; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.withdrawal_cooldown (
    provider character varying(16) NOT NULL,
    account_hash character varying(64) NOT NULL,
    rejoin_available_at timestamp with time zone NOT NULL
);


--
-- Name: withdrawal_identity_key; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.withdrawal_identity_key (
    id integer NOT NULL,
    key_hash character varying(64) NOT NULL
);


--
-- Name: withdrawal_operation; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.withdrawal_operation (
    id uuid NOT NULL,
    user_id bigint,
    session_id uuid,
    request_key uuid,
    kind character varying(10) NOT NULL,
    prepared_at timestamp with time zone NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    completed_at timestamp with time zone,
    rejoin_available_at timestamp with time zone,
    device_permanently_blocked boolean NOT NULL
);


--
-- Name: withdrawal_photo_task; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.withdrawal_photo_task (
    id uuid NOT NULL,
    withdrawal_id uuid NOT NULL,
    file_key character varying(255) NOT NULL,
    attempts integer NOT NULL,
    next_attempt_at timestamp with time zone NOT NULL
);


--
-- Name: withdrawal_relation; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.withdrawal_relation (
    first_hash character varying(64) NOT NULL,
    second_hash character varying(64) NOT NULL,
    expires_at timestamp with time zone NOT NULL
);


--
-- Name: keyring_peer_encounter keyring_peer_encounter_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.keyring_peer_encounter
    ADD CONSTRAINT keyring_peer_encounter_pkey PRIMARY KEY (receipt_id);


--
-- Name: keyring_peer_owner_grant keyring_peer_owner_grant_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.keyring_peer_owner_grant
    ADD CONSTRAINT keyring_peer_owner_grant_pkey PRIMARY KEY (user_id, keyring_id, request_key);


--
-- Name: keyring_peer_owner keyring_peer_owner_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.keyring_peer_owner
    ADD CONSTRAINT keyring_peer_owner_pkey PRIMARY KEY (user_id);


--
-- Name: keyring_peer_ownership keyring_peer_ownership_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.keyring_peer_ownership
    ADD CONSTRAINT keyring_peer_ownership_pkey PRIMARY KEY (keyring_id, owner_generation);


--
-- Name: keyring_peer_record_command keyring_peer_record_command_grant_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.keyring_peer_record_command
    ADD CONSTRAINT keyring_peer_record_command_grant_id_key UNIQUE (grant_id);


--
-- Name: keyring_peer_record_command keyring_peer_record_command_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.keyring_peer_record_command
    ADD CONSTRAINT keyring_peer_record_command_pkey PRIMARY KEY (user_id, keyring_id, request_key);


--
-- Name: keyring_peer_review_resolution keyring_peer_review_resolution_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.keyring_peer_review_resolution
    ADD CONSTRAINT keyring_peer_review_resolution_pkey PRIMARY KEY (receipt_id);


--
-- Name: keyring_reset_run keyring_reset_run_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.keyring_reset_run
    ADD CONSTRAINT keyring_reset_run_pkey PRIMARY KEY (run_id);


--
-- Name: chat pk_chat; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chat
    ADD CONSTRAINT pk_chat PRIMARY KEY (id);


--
-- Name: chat_message pk_chat_message; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chat_message
    ADD CONSTRAINT pk_chat_message PRIMARY KEY (id);


--
-- Name: chat_participant pk_chat_participant; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chat_participant
    ADD CONSTRAINT pk_chat_participant PRIMARY KEY (id);


--
-- Name: chat_report pk_chat_report; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chat_report
    ADD CONSTRAINT pk_chat_report PRIMARY KEY (id);


--
-- Name: chat_report_message pk_chat_report_message; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chat_report_message
    ADD CONSTRAINT pk_chat_report_message PRIMARY KEY (report_id, message_id);


--
-- Name: chat_safety_notice pk_chat_safety_notice; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chat_safety_notice
    ADD CONSTRAINT pk_chat_safety_notice PRIMARY KEY (id);


--
-- Name: keyring pk_keyring; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.keyring
    ADD CONSTRAINT pk_keyring PRIMARY KEY (id);


--
-- Name: keyring_sync_run pk_keyring_sync_run; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.keyring_sync_run
    ADD CONSTRAINT pk_keyring_sync_run PRIMARY KEY (id);


--
-- Name: matching pk_matching; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.matching
    ADD CONSTRAINT pk_matching PRIMARY KEY (id);


--
-- Name: matching_participant pk_matching_participant; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.matching_participant
    ADD CONSTRAINT pk_matching_participant PRIMARY KEY (matching_id, user_id);


--
-- Name: matching_preference pk_matching_preference; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.matching_preference
    ADD CONSTRAINT pk_matching_preference PRIMARY KEY (user_id);


--
-- Name: operator_audit pk_operator_audit; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.operator_audit
    ADD CONSTRAINT pk_operator_audit PRIMARY KEY (id);


--
-- Name: personality pk_personality; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.personality
    ADD CONSTRAINT pk_personality PRIMARY KEY (id);


--
-- Name: profile pk_profile; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.profile
    ADD CONSTRAINT pk_profile PRIMARY KEY (user_id);


--
-- Name: push_installation pk_push_installation; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.push_installation
    ADD CONSTRAINT pk_push_installation PRIMARY KEY (id);


--
-- Name: refresh_token pk_refresh_token; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.refresh_token
    ADD CONSTRAINT pk_refresh_token PRIMARY KEY (id);


--
-- Name: registration_challenge pk_registration_challenge; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.registration_challenge
    ADD CONSTRAINT pk_registration_challenge PRIMARY KEY (id);


--
-- Name: rule_artifact pk_rule_artifact; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.rule_artifact
    ADD CONSTRAINT pk_rule_artifact PRIMARY KEY (id);


--
-- Name: rule_audit pk_rule_audit; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.rule_audit
    ADD CONSTRAINT pk_rule_audit PRIMARY KEY (id);


--
-- Name: rule_channel pk_rule_channel; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.rule_channel
    ADD CONSTRAINT pk_rule_channel PRIMARY KEY (name);


--
-- Name: rule_release pk_rule_release; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.rule_release
    ADD CONSTRAINT pk_rule_release PRIMARY KEY (id);


--
-- Name: service_case pk_service_case; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.service_case
    ADD CONSTRAINT pk_service_case PRIMARY KEY (id);


--
-- Name: service_case_history pk_service_case_history; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.service_case_history
    ADD CONSTRAINT pk_service_case_history PRIMARY KEY (id);


--
-- Name: settings_snapshot pk_settings_snapshot; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.settings_snapshot
    ADD CONSTRAINT pk_settings_snapshot PRIMARY KEY (id);


--
-- Name: survey pk_survey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.survey
    ADD CONSTRAINT pk_survey PRIMARY KEY (id);


--
-- Name: survey_answer pk_survey_answer; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.survey_answer
    ADD CONSTRAINT pk_survey_answer PRIMARY KEY (survey_id, question_id);


--
-- Name: user_consents pk_user_consents; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_consents
    ADD CONSTRAINT pk_user_consents PRIMARY KEY (consent_id);


--
-- Name: user_setting pk_user_setting; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_setting
    ADD CONSTRAINT pk_user_setting PRIMARY KEY (user_id);


--
-- Name: users pk_users; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT pk_users PRIMARY KEY (id);


--
-- Name: chat uq_chat_matching_id; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chat
    ADD CONSTRAINT uq_chat_matching_id UNIQUE (matching_id);


--
-- Name: keyring_peer_ownership uq_keyring_peer_epoch; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.keyring_peer_ownership
    ADD CONSTRAINT uq_keyring_peer_epoch UNIQUE (keyring_id, ownership_epoch);


--
-- Name: keyring_peer_encounter uq_keyring_peer_event; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.keyring_peer_encounter
    ADD CONSTRAINT uq_keyring_peer_event UNIQUE (device_id, owner_generation, event_id);


--
-- Name: keyring_peer_owner uq_keyring_peer_owner_ref; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.keyring_peer_owner
    ADD CONSTRAINT uq_keyring_peer_owner_ref UNIQUE (owner_ref);


--
-- Name: keyring_peer_encounter uq_keyring_peer_submission; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.keyring_peer_encounter
    ADD CONSTRAINT uq_keyring_peer_submission UNIQUE (device_id, submission_id);


--
-- Name: keyring_reset_run uq_keyring_reset_generation; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.keyring_reset_run
    ADD CONSTRAINT uq_keyring_reset_generation UNIQUE (keyring_id, owner_generation);


--
-- Name: keyring_reset_run uq_keyring_reset_request; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.keyring_reset_run
    ADD CONSTRAINT uq_keyring_reset_request UNIQUE (owner_user_id, keyring_id, request_key);


--
-- Name: keyring uq_keyring_serial_number; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.keyring
    ADD CONSTRAINT uq_keyring_serial_number UNIQUE (serial_number);


--
-- Name: push_installation uq_push_installation_client_installation_id; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.push_installation
    ADD CONSTRAINT uq_push_installation_client_installation_id UNIQUE (client_installation_id);


--
-- Name: refresh_token uq_refresh_token_token_hash; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.refresh_token
    ADD CONSTRAINT uq_refresh_token_token_hash UNIQUE (token_hash);


--
-- Name: rule_artifact uq_rule_artifact_kind_version; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.rule_artifact
    ADD CONSTRAINT uq_rule_artifact_kind_version UNIQUE (kind, version);


--
-- Name: user_block uq_user_block_owner_target; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_block
    ADD CONSTRAINT uq_user_block_owner_target UNIQUE (owner_id, target_id);


--
-- Name: withdrawal_operation uq_withdrawal_prepared_member; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.withdrawal_operation
    ADD CONSTRAINT uq_withdrawal_prepared_member UNIQUE (user_id);


--
-- Name: user_block user_block_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_block
    ADD CONSTRAINT user_block_pkey PRIMARY KEY (id);


--
-- Name: withdrawal_cooldown withdrawal_cooldown_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.withdrawal_cooldown
    ADD CONSTRAINT withdrawal_cooldown_pkey PRIMARY KEY (provider, account_hash);


--
-- Name: withdrawal_identity_key withdrawal_identity_key_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.withdrawal_identity_key
    ADD CONSTRAINT withdrawal_identity_key_pkey PRIMARY KEY (id);


--
-- Name: withdrawal_operation withdrawal_operation_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.withdrawal_operation
    ADD CONSTRAINT withdrawal_operation_pkey PRIMARY KEY (id);


--
-- Name: withdrawal_photo_task withdrawal_photo_task_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.withdrawal_photo_task
    ADD CONSTRAINT withdrawal_photo_task_pkey PRIMARY KEY (id);


--
-- Name: withdrawal_relation withdrawal_relation_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.withdrawal_relation
    ADD CONSTRAINT withdrawal_relation_pkey PRIMARY KEY (first_hash, second_hash);


--
-- Name: ix_chat_closed; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_chat_closed ON public.chat USING btree (closed_at);


--
-- Name: ix_chat_participant_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_chat_participant_user ON public.chat_participant USING btree (user_id);


--
-- Name: ix_chat_report_chat; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_chat_report_chat ON public.chat_report USING btree (chat_id);


--
-- Name: ix_chat_report_worklist; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_chat_report_worklist ON public.chat_report USING btree (status, created_at);


--
-- Name: ix_chat_retain_until; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_chat_retain_until ON public.chat USING btree (retain_until);


--
-- Name: ix_keyring_owner; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_keyring_owner ON public.keyring USING btree (owner_user_id);


--
-- Name: ix_keyring_peer_encounter_owner; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_keyring_peer_encounter_owner ON public.keyring_peer_encounter USING btree (owner_user_id, peer_user_id);


--
-- Name: ix_keyring_peer_encounter_pair; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_keyring_peer_encounter_pair ON public.keyring_peer_encounter USING btree (event_id, owner_user_id, peer_user_id);


--
-- Name: ix_keyring_peer_serial_generation; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_keyring_peer_serial_generation ON public.keyring_peer_ownership USING btree (device_serial, owner_generation);


--
-- Name: ix_keyring_peer_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_keyring_peer_user ON public.keyring_peer_ownership USING btree (owner_user_id);


--
-- Name: ix_keyring_sync_run_owner; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_keyring_sync_run_owner ON public.keyring_sync_run USING btree (owner_user_id, keyring_id);


--
-- Name: ix_matching_counterpart_deadline; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_matching_counterpart_deadline ON public.matching USING btree (counterpart_deadline_at);


--
-- Name: ix_matching_high_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_matching_high_status ON public.matching USING btree (user_high_id, status);


--
-- Name: ix_matching_low_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_matching_low_status ON public.matching USING btree (user_low_id, status);


--
-- Name: ix_matching_participant_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_matching_participant_user ON public.matching_participant USING btree (user_id);


--
-- Name: ix_matching_proposal_expiration; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_matching_proposal_expiration ON public.matching USING btree (status, proposed_at);


--
-- Name: ix_matching_raw_delete; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_matching_raw_delete ON public.matching USING btree (raw_delete_at);


--
-- Name: ix_operator_audit_operator; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_operator_audit_operator ON public.operator_audit USING btree (operator_id, occurred_at);


--
-- Name: ix_operator_audit_retention; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_operator_audit_retention ON public.operator_audit USING btree (kind, occurred_at);


--
-- Name: ix_operator_audit_time; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_operator_audit_time ON public.operator_audit USING btree (occurred_at);


--
-- Name: ix_refresh_token_family; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_refresh_token_family ON public.refresh_token USING btree (family_id);


--
-- Name: ix_refresh_token_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_refresh_token_user ON public.refresh_token USING btree (user_id);


--
-- Name: ix_registration_challenge_expiry; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_registration_challenge_expiry ON public.registration_challenge USING btree (expires_at);


--
-- Name: ix_rule_audit_target; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_rule_audit_target ON public.rule_audit USING btree (target_id, occurred_at);


--
-- Name: ix_service_case_closed; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_service_case_closed ON public.service_case USING btree (closed_at);


--
-- Name: ix_service_case_history_case; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_service_case_history_case ON public.service_case_history USING btree (service_case_id, created_at);


--
-- Name: ix_service_case_reply_due; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_service_case_reply_due ON public.service_case USING btree (reply_due_at);


--
-- Name: ix_service_case_requester; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_service_case_requester ON public.service_case USING btree (requester_user_id, created_at);


--
-- Name: ix_service_case_worklist; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_service_case_worklist ON public.service_case USING btree (status, assigned_operator_id);


--
-- Name: ix_survey_user_version; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_survey_user_version ON public.survey USING btree (user_id, survey_version);


--
-- Name: ix_user_block_owner_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_user_block_owner_created ON public.user_block USING btree (owner_id, created_at, id);


--
-- Name: ix_user_consents_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_user_consents_user ON public.user_consents USING btree (user_id);


--
-- Name: ix_withdrawal_cooldown_expiry; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_withdrawal_cooldown_expiry ON public.withdrawal_cooldown USING btree (rejoin_available_at);


--
-- Name: ix_withdrawal_operation_expiry; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_withdrawal_operation_expiry ON public.withdrawal_operation USING btree (expires_at);


--
-- Name: ix_withdrawal_photo_due; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_withdrawal_photo_due ON public.withdrawal_photo_task USING btree (next_attempt_at);


--
-- Name: ix_withdrawal_photo_operation; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_withdrawal_photo_operation ON public.withdrawal_photo_task USING btree (withdrawal_id);


--
-- Name: ix_withdrawal_relation_expiry; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_withdrawal_relation_expiry ON public.withdrawal_relation USING btree (expires_at);


--
-- Name: uq_chat_legacy_public_id; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_chat_legacy_public_id ON public.chat USING btree (legacy_public_id);


--
-- Name: uq_chat_message_client; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_chat_message_client ON public.chat_message USING btree (sender_participant_id, client_message_id);


--
-- Name: uq_chat_message_legacy_public_id; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_chat_message_legacy_public_id ON public.chat_message USING btree (legacy_public_id);


--
-- Name: uq_chat_message_request; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_chat_message_request ON public.chat_message USING btree (sender_participant_id, request_key);


--
-- Name: uq_chat_message_seq; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_chat_message_seq ON public.chat_message USING btree (chat_id, seq);


--
-- Name: uq_chat_participant_member; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_chat_participant_member ON public.chat_participant USING btree (chat_id, user_id);


--
-- Name: uq_chat_safety_notice_once; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_chat_safety_notice_once ON public.chat_safety_notice USING btree (chat_participant_id, notice_type, audience);


--
-- Name: uq_keyring_peer_current; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_keyring_peer_current ON public.keyring_peer_ownership USING btree (keyring_id) WHERE (valid_until IS NULL);


--
-- Name: uq_keyring_sync_run_public; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_keyring_sync_run_public ON public.keyring_sync_run USING btree (sync_run_id);


--
-- Name: uq_keyring_sync_run_request; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_keyring_sync_run_request ON public.keyring_sync_run USING btree (keyring_id, owner_generation, request_key);


--
-- Name: uq_keyring_sync_run_version; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_keyring_sync_run_version ON public.keyring_sync_run USING btree (keyring_id, owner_generation, settings_version);


--
-- Name: uq_matching_pair; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_matching_pair ON public.matching USING btree (user_low_id, user_high_id);


--
-- Name: uq_matching_participant_user_request; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_matching_participant_user_request ON public.matching_participant USING btree (user_id, decision_request_key);


--
-- Name: uq_personality_survey_version; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_personality_survey_version ON public.personality USING btree (survey_id, analysis_version);


--
-- Name: uq_profile_nickname_lower; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_profile_nickname_lower ON public.profile USING btree (lower((nickname)::text));


--
-- Name: uq_registration_challenge_public; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_registration_challenge_public ON public.registration_challenge USING btree (challenge_id);


--
-- Name: uq_registration_challenge_registration_request; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_registration_challenge_registration_request ON public.registration_challenge USING btree (user_id, registration_request_key);


--
-- Name: uq_registration_challenge_request; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_registration_challenge_request ON public.registration_challenge USING btree (user_id, request_key);


--
-- Name: uq_settings_snapshot_user_version; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_settings_snapshot_user_version ON public.settings_snapshot USING btree (user_id, settings_version);


--
-- Name: uq_survey_draft; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_survey_draft ON public.survey USING btree (user_id, survey_version) WHERE (completed_at IS NULL);


--
-- Name: uq_survey_legacy_public_id; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_survey_legacy_public_id ON public.survey USING btree (legacy_public_id);


--
-- Name: uq_survey_user_request; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_survey_user_request ON public.survey USING btree (user_id, client_request_id);


--
-- Name: uq_users_provider_account; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_users_provider_account ON public.users USING btree (provider, provider_user_id);


--
-- PostgreSQL database dump complete
--

\unrestrict vge0v5ZaueTG6TvHGvqElZRIxYzHJHkKhmSsFa2Id5uhPfR5qZ3ABGuuXxRGACu

