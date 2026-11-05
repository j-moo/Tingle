CREATE TABLE "push_installations" (
	"installation_id"	bigint		NOT NULL,
	"client_installation_id"	uuid		NOT NULL,
	"user_id"	bigint		NOT NULL,
	"platform"	text		NOT NULL,
	"token_ciphertext"	text		NOT NULL,
	"updated_at"	timestamptz		NOT NULL
);

COMMENT ON COLUMN "push_installations"."installation_id" IS '서버 단말 등록 번호 — 내부 식별자';

COMMENT ON COLUMN "push_installations"."client_installation_id" IS '앱 설치 구분 번호 — 앱 설치 식별자';

COMMENT ON COLUMN "push_installations"."user_id" IS '회원 번호 — 현재 연결 회원';

COMMENT ON COLUMN "push_installations"."platform" IS '모바일 운영체제 — ANDROID / IOS';

COMMENT ON COLUMN "push_installations"."token_ciphertext" IS '암호화한 푸시 토큰 — 복호화해 전송에 사용하는 암호화 푸시 토큰';

COMMENT ON COLUMN "push_installations"."updated_at" IS '단말 연결 갱신 시각 — 토큰/계정 연결 갱신';

CREATE TABLE "survey_responses" (
	"response_id"	bigint		NOT NULL,
	"user_id"	bigint		NOT NULL,
	"version_code"	text		NOT NULL,
	"client_request_id"	uuid		NOT NULL,
	"response_json"	jsonb		NOT NULL,
	"revision"	bigint		NOT NULL,
	"completed_at"	timestamptz		NULL
);

COMMENT ON COLUMN "survey_responses"."response_id" IS '설문 응답 회차 번호 — 내부 식별자';

COMMENT ON COLUMN "survey_responses"."user_id" IS '회원 번호 — 응답 회원';

COMMENT ON COLUMN "survey_responses"."version_code" IS '설문 정의 버전 코드 — 불변 설문 정의 파일의 버전 코드';

COMMENT ON COLUMN "survey_responses"."client_request_id" IS '앱 요청 중복 방지 번호 — 회차 생성 재시도 키';

COMMENT ON COLUMN "survey_responses"."response_json" IS '설문 답변 묶음 — answers 객체와 필요한 metadata';

COMMENT ON COLUMN "survey_responses"."revision" IS '응답 수정 번호 — 문항별 PATCH 경합·분석 입력 고정을 위한 실제 버전';

COMMENT ON COLUMN "survey_responses"."completed_at" IS '설문 완료 시각 — 완료';

CREATE TABLE "encounters" (
	"encounter_id"	bigint		NOT NULL,
	"pair_id"	bigint		NOT NULL,
	"event_key"	text		NOT NULL,
	"occurred_at"	timestamptz		NOT NULL,
	"opportunity_expires_at"	timestamptz		NOT NULL,
	"counterpart_deadline_at"	timestamptz		NOT NULL,
	"status"	text		NOT NULL,
	"evaluation_version_code"	text		NULL,
	"evaluation_json"	jsonb		NULL
);

COMMENT ON COLUMN "encounters"."encounter_id" IS '만남 기록 번호 — 내부 식별자';

COMMENT ON COLUMN "encounters"."pair_id" IS '두 회원의 쌍 번호 — 당사자 쌍';

COMMENT ON COLUMN "encounters"."event_key" IS '같은 만남 구분값 — 양쪽 증명에서 동일 만남을 연결할 검증된 키';

COMMENT ON COLUMN "encounters"."occurred_at" IS '실제 만남 발생 시각 — 검증된 만남 발생 시각';

COMMENT ON COLUMN "encounters"."opportunity_expires_at" IS '만남 매칭 기회 만료 시각 — 만남 기회 만료';

COMMENT ON COLUMN "encounters"."counterpart_deadline_at" IS '상대 제출 대기 마감 시각 — 상대 제출 대기 기한';

COMMENT ON COLUMN "encounters"."status" IS '처리 상태 — WAITING_FOR_COUNTERPART / EVALUATED / PROPOSED / NOT_MATCHED / EXPIRED_UNPAIRED / REJECTED_INVALID';

COMMENT ON COLUMN "encounters"."evaluation_version_code" IS '매칭 판정 규칙 버전 — 불변 설문 정의 파일의 버전 코드';

COMMENT ON COLUMN "encounters"."evaluation_json" IS '두 사람 매칭 판정 결과 — 최종 판정·각 방향 점수/coverage·제외/미해결 사유';

CREATE TABLE "chat_messages" (
	"message_id"	bigint		NOT NULL,
	"chat_room_id"	bigint		NOT NULL,
	"sender_id"	bigint		NOT NULL,
	"client_message_id"	uuid		NOT NULL,
	"sequence"	bigint		NOT NULL,
	"body"	text		NOT NULL,
	"created_at"	timestamptz		NOT NULL
);

COMMENT ON COLUMN "chat_messages"."message_id" IS '서버 메시지 번호 — 서버가 저장한 메시지의 고유 번호';

COMMENT ON COLUMN "chat_messages"."chat_room_id" IS '채팅방 번호 — 방';

COMMENT ON COLUMN "chat_messages"."sender_id" IS '보낸 회원 번호 — 검증된 보낸 회원';

COMMENT ON COLUMN "chat_messages"."client_message_id" IS '앱 전송 중복 방지 번호 — 앱이 보내기 전에 만드는 전송 구분 번호';

COMMENT ON COLUMN "chat_messages"."sequence" IS '채팅방 안의 메시지 순서 — 해당 채팅방 안에서의 메시지 순서 번호';

COMMENT ON COLUMN "chat_messages"."body" IS '메시지 내용 — 메시지 본문';

COMMENT ON COLUMN "chat_messages"."created_at" IS '생성 시각 — 서버 저장';

CREATE TABLE "match_proposals" (
	"proposal_id"	bigint		NOT NULL,
	"pair_id"	bigint		NOT NULL,
	"encounter_id"	bigint		NOT NULL,
	"low_decision"	text		NULL,
	"high_decision"	text		NULL,
	"low_decided_at"	timestamptz		NULL,
	"high_decided_at"	timestamptz		NULL,
	"status"	text		NOT NULL,
	"created_at"	timestamptz		NOT NULL,
	"expires_at"	timestamptz		NOT NULL,
	"chat_room_id"	bigint		NULL,
	"low_request_key"	uuid		NULL,
	"high_request_key"	uuid		NULL
);

COMMENT ON COLUMN "match_proposals"."proposal_id" IS '매칭 제안 번호 — 내부 식별자';

COMMENT ON COLUMN "match_proposals"."pair_id" IS '두 회원의 쌍 번호 — 당사자 쌍';

COMMENT ON COLUMN "match_proposals"."encounter_id" IS '만남 기록 번호 — 제안이 생긴 만남';

COMMENT ON COLUMN "match_proposals"."low_decision" IS '작은 번호 회원의 선택 — user_low의 ACCEPT / DECLINE';

COMMENT ON COLUMN "match_proposals"."high_decision" IS '큰 번호 회원의 선택 — user_high의 ACCEPT / DECLINE';

COMMENT ON COLUMN "match_proposals"."low_decided_at" IS '작은 번호 회원 선택 시각 — user_low 최초 결정';

COMMENT ON COLUMN "match_proposals"."high_decided_at" IS '큰 번호 회원 선택 시각 — user_high 최초 결정';

COMMENT ON COLUMN "match_proposals"."status" IS '처리 상태 — PENDING / WAITING_FOR_OTHER / ACCEPTED / REJECTED / EXPIRED';

COMMENT ON COLUMN "match_proposals"."created_at" IS '생성 시각 — 제안 생성';

COMMENT ON COLUMN "match_proposals"."expires_at" IS '만료 시각 — 생성+24시간';

COMMENT ON COLUMN "match_proposals"."chat_room_id" IS '채팅방 번호 — 실제로 생성된 방';

COMMENT ON COLUMN "match_proposals"."low_request_key" IS 'user_low 최초 실제 선택의 멱등 키. 선택과 원자 보관';

COMMENT ON COLUMN "match_proposals"."high_request_key" IS 'user_high 최초 실제 선택의 멱등 키. 선택과 원자 보관';

CREATE TABLE "settings_snapshots" (
	"snapshot_id"	bigint		NOT NULL,
	"user_id"	bigint		NOT NULL,
	"settings_version"	bigint		NOT NULL,
	"analysis_result_id"	bigint		NOT NULL,
	"snapshot_json"	jsonb		NOT NULL,
	"created_at"	timestamptz		NOT NULL
);

COMMENT ON COLUMN "settings_snapshots"."snapshot_id" IS '설정 보관본 번호 — 내부 식별자';

COMMENT ON COLUMN "settings_snapshots"."user_id" IS '회원 번호 — 회원';

COMMENT ON COLUMN "settings_snapshots"."settings_version" IS '매칭 설정 변경 번호 — 당시 서버 설정 버전';

COMMENT ON COLUMN "settings_snapshots"."analysis_result_id" IS '성향 분석 결과 번호 — 사용한 개인 계산 결과';

COMMENT ON COLUMN "settings_snapshots"."snapshot_json" IS '당시 매칭 설정 보관본 — 매칭에 필요한 조건·특징값·규칙 버전·나이 기준일';

COMMENT ON COLUMN "settings_snapshots"."created_at" IS '생성 시각 — 스냅샷 생성';

CREATE TABLE "app_users" (
	"user_id"	bigint		NOT NULL,
	"provider"	text		NOT NULL,
	"provider_subject"	text		NOT NULL,
	"status"	text		NOT NULL,
	"created_at"	timestamptz		NOT NULL,
	"signup_completed_at"	timestamptz		NULL,
	"email"	text		NULL
);

COMMENT ON COLUMN "app_users"."user_id" IS '회원 번호 — 내부 식별자';

COMMENT ON COLUMN "app_users"."provider" IS '소셜 로그인 종류 — 소셜 제공자 코드';

COMMENT ON COLUMN "app_users"."provider_subject" IS '소셜 계정 식별값 — 서버가 검증한 공급자 사용자 식별값';

COMMENT ON COLUMN "app_users"."status" IS '처리 상태 — ACTIVE / SUSPENDED / WITHDRAWN';

COMMENT ON COLUMN "app_users"."created_at" IS '생성 시각 — 최초 계정 생성';

COMMENT ON COLUMN "app_users"."signup_completed_at" IS '가입 완료 시각 — 최초 전체 가입 완료';

COMMENT ON COLUMN "app_users"."email" IS '개인정보 동의 후 수집하는 선택 연락처. 회원 식별 및 고유 키로 쓰지 않음';

CREATE TABLE "refresh_tokens" (
	"token_id"	bigint		NOT NULL,
	"user_id"	bigint		NOT NULL,
	"family_id"	uuid		NOT NULL,
	"token_hash"	bytea		NOT NULL,
	"expires_at"	timestamptz		NOT NULL,
	"used_at"	timestamptz		NULL,
	"revoked_at"	timestamptz		NULL
);

COMMENT ON COLUMN "refresh_tokens"."token_id" IS '토큰 번호 — 내부 식별자';

COMMENT ON COLUMN "refresh_tokens"."user_id" IS '회원 번호 — 회원';

COMMENT ON COLUMN "refresh_tokens"."family_id" IS '로그인 토큰 묶음 번호 — 같은 로그인에서 회전된 토큰 계열';

COMMENT ON COLUMN "refresh_tokens"."token_hash" IS '토큰 확인용 해시 — 고엔트로피 난수 토큰의 SHA-256';

COMMENT ON COLUMN "refresh_tokens"."expires_at" IS '만료 시각 — 이 토큰의 만료';

COMMENT ON COLUMN "refresh_tokens"."used_at" IS '토큰 사용 시각 — 정상 회전에서 소비된 시각';

COMMENT ON COLUMN "refresh_tokens"."revoked_at" IS '토큰 폐기 시각 — 서버가 폐기한 시각';

CREATE TABLE "chat_members" (
	"chat_room_id"	bigint		NOT NULL,
	"user_id"	bigint		NOT NULL,
	"left_at"	timestamptz		NULL,
	"first_notice_acked_at"	timestamptz		NULL,
	"end_notice_acked_at"	timestamptz		NULL
);

COMMENT ON COLUMN "chat_members"."chat_room_id" IS '채팅방 번호 — 방';

COMMENT ON COLUMN "chat_members"."user_id" IS '회원 번호 — 참여 회원';

COMMENT ON COLUMN "chat_members"."left_at" IS '채팅 퇴장 시각 — 허용된 퇴장 시각';

COMMENT ON COLUMN "chat_members"."first_notice_acked_at" IS '첫 입장 안내 확인 시각 — 첫 입장 안내 표시 확인';

COMMENT ON COLUMN "chat_members"."end_notice_acked_at" IS '종료 전 안내 확인 시각 — 종료 1시간 전 안내 표시 확인';

CREATE TABLE "user_blocks" (
	"block_id"	bigint		NOT NULL,
	"blocker_id"	bigint		NOT NULL,
	"blocked_id"	bigint		NOT NULL,
	"created_at"	timestamptz		NOT NULL
);

COMMENT ON COLUMN "user_blocks"."block_id" IS '차단 번호 — 내부 식별자';

COMMENT ON COLUMN "user_blocks"."blocker_id" IS '차단한 회원 번호 — 차단한 회원';

COMMENT ON COLUMN "user_blocks"."blocked_id" IS '차단된 회원 번호 — 차단된 회원';

COMMENT ON COLUMN "user_blocks"."created_at" IS '생성 시각 — 차단';

CREATE TABLE "reports" (
	"report_id"	bigint		NOT NULL,
	"reporter_id"	bigint		NOT NULL,
	"target_type"	text		NOT NULL,
	"target_id"	bigint		NOT NULL,
	"reason"	text		NOT NULL,
	"detail"	text		NULL,
	"status"	text		NOT NULL,
	"created_at"	timestamptz		NOT NULL
);

COMMENT ON COLUMN "reports"."report_id" IS '신고 번호 — 내부 식별자';

COMMENT ON COLUMN "reports"."reporter_id" IS '신고한 회원 번호 — 신고자';

COMMENT ON COLUMN "reports"."target_type" IS '신고 대상 종류 — PROPOSAL / CHAT_ROOM / MESSAGE';

COMMENT ON COLUMN "reports"."target_id" IS '종류에 따른 대상 번호 — target_type에 해당하는 내부 ID';

COMMENT ON COLUMN "reports"."reason" IS '신고 사유 코드 — 신고 사유 코드';

COMMENT ON COLUMN "reports"."detail" IS '신고 상세 설명 — 추가 설명';

COMMENT ON COLUMN "reports"."status" IS '처리 상태 — RECEIVED / REVIEWING / RESOLVED';

COMMENT ON COLUMN "reports"."created_at" IS '생성 시각 — 접수';

CREATE TABLE "keyring_sync_runs" (
	"sync_run_id"	bigint		NOT NULL,
	"binding_id"	bigint		NOT NULL,
	"snapshot_id"	bigint		NOT NULL,
	"payload_hash"	bytea		NOT NULL,
	"status"	text		NOT NULL,
	"acknowledged_at"	timestamptz		NULL
);

COMMENT ON COLUMN "keyring_sync_runs"."sync_run_id" IS '키링 동기화 번호 — 내부 식별자';

COMMENT ON COLUMN "keyring_sync_runs"."binding_id" IS '키링 연결 기록 번호 — 현재 소유 연결';

COMMENT ON COLUMN "keyring_sync_runs"."snapshot_id" IS '설정 보관본 번호 — 반영할 불변 설정';

COMMENT ON COLUMN "keyring_sync_runs"."payload_hash" IS '기기 전송 내용 확인값 — 실제 전송할 payload와 ACK 대조용 해시';

COMMENT ON COLUMN "keyring_sync_runs"."status" IS '처리 상태 — PENDING / ACKNOWLEDGED / FAILED / SUPERSEDED';

COMMENT ON COLUMN "keyring_sync_runs"."acknowledged_at" IS '기기 저장 확인 시각 — 기기 저장 완료 확인';

CREATE TABLE "background_jobs" (
	"job_id"	bigint		NOT NULL,
	"job_type"	text		NOT NULL,
	"business_key"	text		NOT NULL,
	"payload"	jsonb		NOT NULL,
	"status"	text		NOT NULL,
	"run_at"	timestamptz		NOT NULL,
	"attempts"	integer		NOT NULL,
	"lease_until"	timestamptz		NULL,
	"last_error_code"	text		NULL,
	"result_json"	jsonb		NULL,
	"finished_at"	timestamptz		NULL
);

COMMENT ON COLUMN "background_jobs"."job_id" IS '처리 작업 번호 — 내부 식별자';

COMMENT ON COLUMN "background_jobs"."job_type" IS '작업 종류 — 현재 사용하는 CHAT_CREATE / FIRST_ENTRY / END_NOTICE / AI / CHAT_DELETE 등 허용 목록';

COMMENT ON COLUMN "background_jobs"."business_key" IS '같은 작업 중복 방지값 — 같은 업무 재생성 방지 키';

COMMENT ON COLUMN "background_jobs"."payload" IS '작업 처리에 필요한 정보 — 자원 ID·요청 키·필요 버전만';

COMMENT ON COLUMN "background_jobs"."status" IS '처리 상태 — PENDING / RUNNING / SUCCEEDED / FAILED / CANCELLED';

COMMENT ON COLUMN "background_jobs"."run_at" IS '작업 실행 예정 시각 — 예약 또는 다음 재시도 가능 시각';

COMMENT ON COLUMN "background_jobs"."attempts" IS '누적 실행 선점 횟수 — 작업을 맡아 실행한 누적 횟수';

COMMENT ON COLUMN "background_jobs"."lease_until" IS '현재 실행자 처리 마감 시각 — 현재 실행자가 작업을 맡아 둘 수 있는 마지막 시각';

COMMENT ON COLUMN "background_jobs"."last_error_code" IS '최근 실패 사유 코드 — 최근 비민감 오류 코드';

COMMENT ON COLUMN "background_jobs"."result_json" IS '처리 결과 묶음 — 삭제 저장소 확인 결과 등 꼭 필요한 작업 결과';

COMMENT ON COLUMN "background_jobs"."finished_at" IS '작업 최종 종료 시각 — 성공·최종 실패·취소 확정';

CREATE TABLE "keyring_bindings" (
	"binding_id"	bigint		NOT NULL,
	"device_id"	bigint		NOT NULL,
	"user_id"	bigint		NOT NULL,
	"bound_at"	timestamptz		NOT NULL,
	"unbound_at"	timestamptz		NULL
);

COMMENT ON COLUMN "keyring_bindings"."binding_id" IS '키링 연결 기록 번호 — 내부 식별자';

COMMENT ON COLUMN "keyring_bindings"."device_id" IS '키링 번호 — 키링';

COMMENT ON COLUMN "keyring_bindings"."user_id" IS '회원 번호 — 연결 회원';

COMMENT ON COLUMN "keyring_bindings"."bound_at" IS '키링 연결 시각 — 소유 연결 시작';

COMMENT ON COLUMN "keyring_bindings"."unbound_at" IS '키링 연결 해제 시각 — 연결 해제';

CREATE TABLE "keyrings" (
	"device_id"	bigint		NOT NULL,
	"serial_hash"	bytea		NOT NULL,
	"verification_key_ref"	text		NOT NULL,
	"firmware_version"	text		NOT NULL
);

COMMENT ON COLUMN "keyrings"."device_id" IS '키링 번호 — 내부 식별자';

COMMENT ON COLUMN "keyrings"."serial_hash" IS '키링 일련번호 확인값 — 기기 시리얼의 검증용 해시';

COMMENT ON COLUMN "keyrings"."verification_key_ref" IS '기기 검증키 보관 위치 — 검증 키 참조';

COMMENT ON COLUMN "keyrings"."firmware_version" IS '펌웨어 버전 — 기기 펌웨어 버전';

CREATE TABLE "user_pairs" (
	"pair_id"	bigint		NOT NULL,
	"user_low"	bigint		NOT NULL,
	"user_high"	bigint		NOT NULL,
	"declined_until"	timestamptz		NULL,
	"declined_proposal_id"	bigint		NULL
);

COMMENT ON COLUMN "user_pairs"."pair_id" IS '두 회원의 쌍 번호 — 내부 식별자';

COMMENT ON COLUMN "user_pairs"."user_low" IS '작은 번호의 회원 — 작은 회원 ID';

COMMENT ON COLUMN "user_pairs"."user_high" IS '큰 번호의 회원 — 큰 회원 ID';

COMMENT ON COLUMN "user_pairs"."declined_until" IS '거절 후 재제안 제한 시각 — 명시적 거절 재제안 제한 종료';

COMMENT ON COLUMN "user_pairs"."declined_proposal_id" IS '거절 제한의 근거 제안 — 현재 거절 제한의 근거 제안';

CREATE TABLE "analysis_results" (
	"result_id"	bigint		NOT NULL,
	"response_id"	bigint		NOT NULL,
	"version_code"	text		NOT NULL,
	"outcome"	text		NOT NULL,
	"result_json"	jsonb		NOT NULL
);

COMMENT ON COLUMN "analysis_results"."result_id" IS '성향 분석 결과 번호 — 내부 식별자';

COMMENT ON COLUMN "analysis_results"."response_id" IS '설문 응답 회차 번호 — 불변 완료 응답';

COMMENT ON COLUMN "analysis_results"."version_code" IS '설문 정의 버전 코드 — 불변 설문 정의 파일의 버전 코드';

COMMENT ON COLUMN "analysis_results"."outcome" IS '분석 성공 여부 — SUCCEEDED / INSUFFICIENT';

COMMENT ON COLUMN "analysis_results"."result_json" IS '처리 결과 묶음 — 점수·유형·문구·참고 유형 결과';

CREATE TABLE "chat_rooms" (
	"chat_room_id"	bigint		NOT NULL,
	"pair_id"	bigint		NOT NULL,
	"created_at"	timestamptz		NOT NULL,
	"expires_at"	timestamptz		NOT NULL,
	"closed_at"	timestamptz		NULL,
	"delete_at"	timestamptz		NULL,
	"last_sequence"	bigint		NOT NULL
);

COMMENT ON COLUMN "chat_rooms"."chat_room_id" IS '채팅방 번호 — 내부 식별자';

COMMENT ON COLUMN "chat_rooms"."pair_id" IS '두 회원의 쌍 번호 — 당사자 쌍';

COMMENT ON COLUMN "chat_rooms"."created_at" IS '생성 시각 — 최초 실제 방 생성';

COMMENT ON COLUMN "chat_rooms"."expires_at" IS '만료 시각 — created_at+168시간';

COMMENT ON COLUMN "chat_rooms"."closed_at" IS '채팅 종료 효력 시각 — 실제 종료 효력';

COMMENT ON COLUMN "chat_rooms"."delete_at" IS '채팅 기록 삭제 예정 시각 — closed_at+720시간';

COMMENT ON COLUMN "chat_rooms"."last_sequence" IS '방의 마지막 메시지 순서 — 방별 메시지 순번 발급';

CREATE TABLE "registration_challenges" (
	"challenge_id"	bigint		NOT NULL,
	"user_id"	bigint		NOT NULL,
	"nonce_hash"	bytea		NOT NULL,
	"expires_at"	timestamptz		NOT NULL,
	"consumed_at"	timestamptz		NULL
);

COMMENT ON COLUMN "registration_challenges"."challenge_id" IS '등록 인증 요청 번호 — 내부 식별자';

COMMENT ON COLUMN "registration_challenges"."user_id" IS '회원 번호 — 등록을 요청한 회원';

COMMENT ON COLUMN "registration_challenges"."nonce_hash" IS '일회용 인증값 확인값 — 검증용 일회성 값의 해시';

COMMENT ON COLUMN "registration_challenges"."expires_at" IS '만료 시각 — 검증 만료';

COMMENT ON COLUMN "registration_challenges"."consumed_at" IS '인증값 사용 완료 시각 — 소비 완료';

CREATE TABLE "user_consents" (
	"consent_id"	bigint		NOT NULL,
	"user_id"	bigint		NOT NULL,
	"consent_type"	text		NOT NULL,
	"document_version"	text		NOT NULL,
	"agreed"	boolean		NOT NULL,
	"recorded_at"	timestamptz		NOT NULL
);

COMMENT ON COLUMN "user_consents"."consent_id" IS '동의 기록 번호 — 내부 식별자';

COMMENT ON COLUMN "user_consents"."user_id" IS '회원 번호 — 동의 주체';

COMMENT ON COLUMN "user_consents"."consent_type" IS '동의 항목 종류 — 동의 종류';

COMMENT ON COLUMN "user_consents"."document_version" IS '동의 문서 버전 — 사용자가 확인한 정확한 문서 버전';

COMMENT ON COLUMN "user_consents"."agreed" IS '동의 여부 — 동의 true, 거절/철회 false';

COMMENT ON COLUMN "user_consents"."recorded_at" IS '동의 기록 시각 — 서버 기록 시각';

CREATE TABLE "matching_settings" (
	"user_id"	bigint		NOT NULL,
	"desired_genders"	text[]		NULL,
	"age_min"	integer		NULL,
	"age_max"	integer		NULL,
	"matching_enabled"	boolean		NOT NULL,
	"require_partner_photo"	boolean		NOT NULL,
	"settings_version"	bigint		NOT NULL
);

COMMENT ON COLUMN "matching_settings"."user_id" IS '회원 번호 — 회원';

COMMENT ON COLUMN "matching_settings"."desired_genders" IS '희망 성별 — 희망 상대 성별';

COMMENT ON COLUMN "matching_settings"."age_min" IS '희망 최소 나이 — 희망 최소 만 나이';

COMMENT ON COLUMN "matching_settings"."age_max" IS '희망 최대 나이 — 희망 최대 만 나이';

COMMENT ON COLUMN "matching_settings"."matching_enabled" IS '매칭 사용 여부 — 매칭 ON/OFF';

COMMENT ON COLUMN "matching_settings"."require_partner_photo" IS '상대 사진 필수 여부 — 상대 사진 필수';

COMMENT ON COLUMN "matching_settings"."settings_version" IS '매칭 설정 변경 번호 — 프로필·성향·조건 변경과 키링 반영 비교용 실제 버전';

CREATE TABLE "encounter_submissions" (
	"receipt_id"	bigint		NOT NULL,
	"encounter_id"	bigint		NOT NULL,
	"binding_id"	bigint		NOT NULL,
	"snapshot_id"	bigint		NOT NULL,
	"submission_id"	uuid		NOT NULL,
	"proof_json"	jsonb		NOT NULL,
	"received_at"	timestamptz		NOT NULL
);

COMMENT ON COLUMN "encounter_submissions"."receipt_id" IS '만남 제출 접수 번호 — 내부 식별자';

COMMENT ON COLUMN "encounter_submissions"."encounter_id" IS '만남 기록 번호 — 만남';

COMMENT ON COLUMN "encounter_submissions"."binding_id" IS '키링 연결 기록 번호 — 당시 소유 연결';

COMMENT ON COLUMN "encounter_submissions"."snapshot_id" IS '설정 보관본 번호 — 당시 기기 반영 설정';

COMMENT ON COLUMN "encounter_submissions"."submission_id" IS '기기 제출 중복 방지 번호 — 기기 측 제출 재시도 키';

COMMENT ON COLUMN "encounter_submissions"."proof_json" IS '기기 증명 자료 — 검증에 필요한 서명·증명 데이터만';

COMMENT ON COLUMN "encounter_submissions"."received_at" IS '서버 접수 시각 — 서버 접수 시각';

CREATE TABLE "profiles" (
	"user_id"	bigint		NOT NULL,
	"nickname"	text		NULL,
	"nickname_key"	text		NULL,
	"birth_date"	date		NULL,
	"gender"	text		NULL,
	"smoking_status"	text		NULL,
	"drinking_frequency"	text		NULL,
	"religion"	text		NULL,
	"introduction"	text		NULL,
	"image_url"	text		NULL,
	"completed_at"	timestamptz		NULL
);

COMMENT ON COLUMN "profiles"."user_id" IS '회원 번호 — 회원';

COMMENT ON COLUMN "profiles"."nickname" IS '닉네임 — 표시 닉네임';

COMMENT ON COLUMN "profiles"."nickname_key" IS '닉네임 중복 확인값 — 정규화한 중복 검사 키';

COMMENT ON COLUMN "profiles"."birth_date" IS '생년월일 — 실제 생년월일';

COMMENT ON COLUMN "profiles"."gender" IS '성별 — 성별 코드';

COMMENT ON COLUMN "profiles"."smoking_status" IS '흡연 여부 — 흡연 코드';

COMMENT ON COLUMN "profiles"."drinking_frequency" IS '음주 빈도 — 음주 코드';

COMMENT ON COLUMN "profiles"."religion" IS '종교 — 종교 코드';

COMMENT ON COLUMN "profiles"."introduction" IS '자기소개 — 자기소개';

COMMENT ON COLUMN "profiles"."image_url" IS '현재 프로필 사진 주소 — 현재 사진의 저장소 참조 URL';

CREATE TABLE "ai_results" (
	"job_id"	bigint		NOT NULL,
	"kind"	text		NOT NULL,
	"user_id"	bigint		NOT NULL,
	"target_id"	bigint		NOT NULL,
	"result_json"	jsonb		NOT NULL,
	"model_version"	text		NOT NULL,
	"prompt_version"	text		NOT NULL,
	"created_at"	timestamptz		NOT NULL
);

COMMENT ON COLUMN "ai_results"."job_id" IS '처리 작업 번호 — 생성 작업 ID이자 결과 PK';

COMMENT ON COLUMN "ai_results"."kind" IS 'AI 결과 종류 — PERSONAL_ANALYSIS / MATCH_EXPLANATION / ICEBREAKERS / RISK_DETECTION';

COMMENT ON COLUMN "ai_results"."user_id" IS '회원 번호 — 요청 또는 분석 적용 회원';

COMMENT ON COLUMN "ai_results"."target_id" IS '종류에 따른 대상 번호 — kind별 분석 결과/제안/방/메시지 ID';

COMMENT ON COLUMN "ai_results"."result_json" IS '처리 결과 묶음 — 생성 문구 또는 위험 분류·신뢰도';

COMMENT ON COLUMN "ai_results"."model_version" IS '사용한 AI 모델 버전 — 실제 사용 모델 식별/버전';

COMMENT ON COLUMN "ai_results"."prompt_version" IS '사용한 프롬프트 버전 — 실제 프롬프트 버전';

COMMENT ON COLUMN "ai_results"."created_at" IS '생성 시각 — 결과 생성';

ALTER TABLE "push_installations" ADD CONSTRAINT "PK_PUSH_INSTALLATIONS" PRIMARY KEY (
	"installation_id"
);

ALTER TABLE "survey_responses" ADD CONSTRAINT "PK_SURVEY_RESPONSES" PRIMARY KEY (
	"response_id"
);

ALTER TABLE "encounters" ADD CONSTRAINT "PK_ENCOUNTERS" PRIMARY KEY (
	"encounter_id"
);

ALTER TABLE "chat_messages" ADD CONSTRAINT "PK_CHAT_MESSAGES" PRIMARY KEY (
	"message_id"
);

ALTER TABLE "match_proposals" ADD CONSTRAINT "PK_MATCH_PROPOSALS" PRIMARY KEY (
	"proposal_id"
);

ALTER TABLE "settings_snapshots" ADD CONSTRAINT "PK_SETTINGS_SNAPSHOTS" PRIMARY KEY (
	"snapshot_id"
);

ALTER TABLE "app_users" ADD CONSTRAINT "PK_APP_USERS" PRIMARY KEY (
	"user_id"
);

ALTER TABLE "refresh_tokens" ADD CONSTRAINT "PK_REFRESH_TOKENS" PRIMARY KEY (
	"token_id"
);

ALTER TABLE "chat_members" ADD CONSTRAINT "PK_CHAT_MEMBERS" PRIMARY KEY (
	"chat_room_id",
	"user_id"
);

ALTER TABLE "user_blocks" ADD CONSTRAINT "PK_USER_BLOCKS" PRIMARY KEY (
	"block_id"
);

ALTER TABLE "reports" ADD CONSTRAINT "PK_REPORTS" PRIMARY KEY (
	"report_id"
);

ALTER TABLE "keyring_sync_runs" ADD CONSTRAINT "PK_KEYRING_SYNC_RUNS" PRIMARY KEY (
	"sync_run_id"
);

ALTER TABLE "background_jobs" ADD CONSTRAINT "PK_BACKGROUND_JOBS" PRIMARY KEY (
	"job_id"
);

ALTER TABLE "keyring_bindings" ADD CONSTRAINT "PK_KEYRING_BINDINGS" PRIMARY KEY (
	"binding_id"
);

ALTER TABLE "keyrings" ADD CONSTRAINT "PK_KEYRINGS" PRIMARY KEY (
	"device_id"
);

ALTER TABLE "user_pairs" ADD CONSTRAINT "PK_USER_PAIRS" PRIMARY KEY (
	"pair_id"
);

ALTER TABLE "analysis_results" ADD CONSTRAINT "PK_ANALYSIS_RESULTS" PRIMARY KEY (
	"result_id"
);

ALTER TABLE "chat_rooms" ADD CONSTRAINT "PK_CHAT_ROOMS" PRIMARY KEY (
	"chat_room_id"
);

ALTER TABLE "registration_challenges" ADD CONSTRAINT "PK_REGISTRATION_CHALLENGES" PRIMARY KEY (
	"challenge_id"
);

ALTER TABLE "user_consents" ADD CONSTRAINT "PK_USER_CONSENTS" PRIMARY KEY (
	"consent_id"
);

ALTER TABLE "matching_settings" ADD CONSTRAINT "PK_MATCHING_SETTINGS" PRIMARY KEY (
	"user_id"
);

ALTER TABLE "encounter_submissions" ADD CONSTRAINT "PK_ENCOUNTER_SUBMISSIONS" PRIMARY KEY (
	"receipt_id"
);

ALTER TABLE "profiles" ADD CONSTRAINT "PK_PROFILES" PRIMARY KEY (
	"user_id"
);

ALTER TABLE "ai_results" ADD CONSTRAINT "PK_AI_RESULTS" PRIMARY KEY (
	"job_id"
);
