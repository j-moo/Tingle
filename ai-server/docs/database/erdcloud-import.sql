-- ERD21 source + approved preservation amendments / 2026-10-28 / 22 tables, 176 columns
-- Documentation only. Not a migration. Not executed against any database.
-- ERDCloud diagram import syntax; mixed display types, not executable PostgreSQL DDL.
-- FOREIGN KEY statements below describe diagram edges, not approved physical FK policy.

-- 성향 분석 결과 — 설문 한 번 분석 = 한 줄. 분석 결과만 담고, 매칭 조건은 matching_preference에
CREATE TABLE `personality` (
    `id` bigint NOT NULL COMMENT '분석 결과 ID — 공개 분석 결과 ID. API resultId',
    `survey_id` bigint NOT NULL COMMENT '설문 회차 ID — 분석한 완료 설문 회차 ID. 회원은 survey에서 확인',
    `analysis_version` varchar(30) NOT NULL COMMENT '분석 규칙 버전 — 채점·유형 계산 규칙의 버전 이름. 규칙 내용은 DB가 아니라 서버 설정 파일(analysis/{버전}.json)에 있음',
    `status` analysis_status NOT NULL COMMENT '분석 상태 — PENDING / SUCCEEDED / INSUFFICIENT / FAILED. PENDING으로 5분 넘게 멈췄거나 FAILED이고 재시도 횟수가 남았으면 주기 작업이 다시 분석',
    `result` jsonb COMMENT '분석 결과 — 내부 점수·성향 데이터·사용 버전 또는 재확인/실패의 최소 정보. 재시도 정보 {attempts, nextRetryAt, lastErrorCode} 포함',
    CONSTRAINT `pk_personality` PRIMARY KEY (`id`)
);
CREATE UNIQUE INDEX `uq_personality_survey_version` ON `personality` (`survey_id`, `analysis_version`);

-- 회원·인증·동의
CREATE TABLE `users` (
    `id` bigint NOT NULL COMMENT '회원 ID — 서비스 회원 번호. DB가 1씩 올려 발급(재사용 안 함). 탈퇴 후 재가입하면 새 번호',
    `provider` auth_provider NOT NULL COMMENT '소셜 로그인 종류 — 소셜 제공자 코드: GOOGLE / KAKAO / NAVER',
    `provider_user_id` varchar(255) NOT NULL COMMENT '소셜 계정 고유값 — 서버가 제공자 인증으로 확인한 소셜 계정 고유값',
    `email` varchar(320) COMMENT '연락용 이메일 — 동의 조건을 충족한 연락용 이메일. 미제공·철회·탈퇴 시 정리하며 계정 식별이나 상대 공개에 사용하지 않음',
    `signup_completed_at` timestamptz COMMENT '가입 완료 시각 — 최초 가입을 모두 완료한 시각',
    `agreed_at` timestamptz COMMENT '일괄 동의 시각 — 첫 화면에서 약관·개인정보·AI 분석 등에 한 번에 동의한 시각. 비어 있거나 새 약관 시행일보다 이르면 재동의 받음',
    `is_operator` boolean NOT NULL DEFAULT false COMMENT '운영자 여부 — 운영 화면 접근 가능 여부. 일반 회원은 false',
    `status` text NOT NULL COMMENT '회원 상태 — 기존 ACTIVE / WITHDRAWAL_PENDING / SUSPENDED / WITHDRAWN 값을 보존. 정지·탈퇴 계정을 ACTIVE로 추정하지 않음',
    `created_at` timestamptz NOT NULL COMMENT '회원 생성 시각 — 최초 소셜 로그인 때 서버가 확정한 기존 시각을 보존. 이관 시 현재 시각으로 바꾸지 않음',
    CONSTRAINT `pk_users` PRIMARY KEY (`id`)
);
CREATE UNIQUE INDEX `uq_users_provider_account` ON `users` (`provider`, `provider_user_id`);

-- 항목별 동의·철회 이력 — 문서 버전·참거짓·기록 시각을 수정 없이 누적하며 users.agreed_at으로 합치거나 과거 일괄 동의를 추정하지 않음
CREATE TABLE `user_consents` (
    `consent_id` bigint NOT NULL COMMENT '동의 이력 ID — 기존 기록 순서와 번호를 보존',
    `user_id` bigint NOT NULL COMMENT '동의한 회원 ID — users.id',
    `consent_type` text NOT NULL COMMENT '동의 항목 — 설정 파일에 정의된 약관·개인정보·AI 등 정확한 항목 코드',
    `document_version` text NOT NULL COMMENT '사용자가 확인한 정확한 문서 버전 — 최신 버전으로 덮어쓰지 않음',
    `agreed` boolean NOT NULL COMMENT '해당 항목 동의 여부 — 동의 true, 거절·철회 false. 선택 동의를 일괄 true로 추정하지 않음',
    `recorded_at` timestamptz NOT NULL COMMENT '동의·거절·철회가 확정된 서버 시각 — 기존 시각 보존',
    CONSTRAINT `pk_user_consents` PRIMARY KEY (`consent_id`)
);
CREATE INDEX `ix_user_consents_user` ON `user_consents` (`user_id`);

-- 로그인 토큰(기기별)
CREATE TABLE `refresh_token` (
    `id` bigint NOT NULL COMMENT '토큰 번호',
    `user_id` bigint NOT NULL COMMENT '회원 ID',
    `family_id` uuid NOT NULL COMMENT '로그인 기기 ID(sid) — 같은 기기에서 재발급된 토큰은 같은 값. 로그인 기기 목록 = 폐기 안 된 family 목록',
    `token_hash` bytea NOT NULL COMMENT '리프레시 토큰 해시 — 원문 저장 안 함',
    `expires_at` timestamptz NOT NULL COMMENT '만료 시각',
    `used_at` timestamptz COMMENT '재발급에 쓴 시각 — 이미 쓴 토큰이 다시 오면 그 family 전체 폐기(탈취 감지)',
    `revoked_at` timestamptz COMMENT '폐기 시각 — 로그아웃·탈취 감지·탈퇴',
    `device_name` varchar(100) COMMENT '기기 이름 — 로그인 기기 목록에 보여 줄 이름(예: Galaxy S24). 재발급 때 그대로 이어받음',
    `last_used_at` timestamptz COMMENT '마지막 사용 시각 — 로그인·재발급한 시각. 로그인 기기 목록에 표시',
    CONSTRAINT `pk_refresh_token` PRIMARY KEY (`id`),
    CONSTRAINT `uq_refresh_token_token_hash` UNIQUE (`token_hash`)
);
CREATE INDEX `ix_refresh_token_family` ON `refresh_token` (`family_id`);

CREATE INDEX `ix_refresh_token_user` ON `refresh_token` (`user_id`);

-- 푸시 알림 기기
CREATE TABLE `push_installation` (
    `id` bigint NOT NULL COMMENT '설치 번호',
    `client_installation_id` uuid NOT NULL COMMENT '앱 설치 ID — 앱이 발급, 재설치 전까지 유지',
    `user_id` bigint COMMENT '연결된 회원 ID — 로그아웃하면 비움',
    `login_family_id` uuid COMMENT '연결된 로그인 기기 — refresh_token.family_id. 그 로그인이 끝나면 비움. 알림 기기 목록 기준',
    `binding_id` uuid COMMENT '현재 수신 연결 세대 — 최초 연결·재연결 때 발급, 같은 family 토큰 교체에는 유지. linked_at과 함께 저장·해제하며 로그아웃 때 비움. 과거 누락 값 추정 금지',
    `linked_at` timestamptz COMMENT '현재 연결 최초 확정 시각 — binding_id와 함께 저장·해제. 토큰 교체·재시도로 연장하지 않음',
    `platform` device_platform NOT NULL COMMENT '기기 종류 — ANDROID / IOS',
    `token_ciphertext` text NOT NULL COMMENT '푸시 토큰 — 암호화한 FCM 토큰',
    `updated_at` timestamptz NOT NULL COMMENT '마지막 갱신 시각',
    CONSTRAINT `pk_push_installation` PRIMARY KEY (`id`),
    CONSTRAINT `uq_push_installation_client_installation_id` UNIQUE (`client_installation_id`)
);

-- 운영자 활동 기록 — 운영자가 회원 정보를 보거나 고치거나 권한을 바꾼 일을 남기는 기록
CREATE TABLE `operator_audit` (
    `id` bigint NOT NULL COMMENT '기록 번호',
    `operator_id` bigint COMMENT '운영자 ID — 행위 운영자 ID. 인증 실패 등 확정할 수 없는 경우 NULL',
    `kind` operator_audit_kind NOT NULL COMMENT '기록 종류 — ACCESS: 회원 정보 조회·처리(2년 보관) / PERMISSION: 운영자 권한 부여·회수(3년 보관)',
    `action` operator_action NOT NULL COMMENT '운영 작업 — ACCESS: MEMBER_VIEW(회원 정보 조회) / REPORT_CHAT_VIEW(신고된 채팅 열람) / REPORT_RESOLVE(신고 처리) / PROFILE_CORRECT(프로필 정정) / KEYRING_UNBIND(분실 기기 등록 해제) / MEMBER_WITHDRAW(강제 탈퇴) / CASE_HANDLE(지원 요청 처리) / LOGIN_FAIL(운영 화면 로그인 실패). PERMISSION: OPERATOR_GRANT(운영자 권한 부여) / OPERATOR_REVOKE(운영자 권한 회수). 작업에 따라 target_* 칸 중 해당하는 것만 채움',
    `target_user_id` bigint COMMENT '대상 회원 ID — 조회·정정·강제 탈퇴·권한 부여/회수한 회원(users.id). 대상이 회원이 아니면 NULL',
    `target_service_case_id` bigint COMMENT '대상 지원 요청 ID — CASE_HANDLE·PROFILE_CORRECT 등에서 처리한 요청(service_case.id)',
    `target_keyring_id` bigint COMMENT '대상 키링 ID — KEYRING_UNBIND에서 등록 해제한 키링(keyring.id)',
    `target_chat_report_id` bigint COMMENT '대상 신고 ID — REPORT_CHAT_VIEW·REPORT_RESOLVE에서 본·처리한 신고(chat_report.id)',
    `result_code` operator_result NOT NULL COMMENT '처리 결과 — SUCCESS / DENIED(권한 없음) / FAILED',
    `reason` text COMMENT '작업 사유 — 운영자가 남긴 이유. 대화 내용·민감 프로필·비밀값은 쓰지 않음',
    `occurred_at` timestamptz NOT NULL COMMENT '운영 작업 시각 — 서버가 기록한 실제 사건 시각. ACCESS는 발생부터 2년, PERMISSION은 변경부터 3년 보관',
    CONSTRAINT `pk_operator_audit` PRIMARY KEY (`id`)
);
CREATE INDEX `ix_operator_audit_time` ON `operator_audit` (`occurred_at`);
CREATE INDEX `ix_operator_audit_operator` ON `operator_audit` (`operator_id`, `occurred_at`);
CREATE INDEX `ix_operator_audit_retention` ON `operator_audit` (`kind`, `occurred_at`);

-- 만남·제안·차단 관계 — 두 사람당 한 줄. 사람별 기록(만남 기록·수락/거절·차단)은 matching_participant에
CREATE TABLE `matching` (
    `id` bigint NOT NULL COMMENT '매칭 ID — 두 사람당 한 줄. 별도 제안 번호 칸은 없음. 앱이 제안을 조회·수락·거절할 때도 이 번호로 가리킴',
    `user_low_id` bigint NOT NULL COMMENT '작은 쪽 회원 ID — 두 회원 번호 중 작은 쪽. 탈퇴하면 이 행 삭제',
    `user_high_id` bigint NOT NULL COMMENT '큰 쪽 회원 ID — 두 회원 번호 중 큰 쪽. 탈퇴하면 이 행 삭제',
    `met_at` timestamptz COMMENT '만남 시각 — 기기 기록을 검증해 확정한 만남 시각',
    `policy_version` varchar(30) COMMENT '매칭 정책 버전 — 이 만남에 적용한 정책(대기 시간·응답 기한 등)의 버전 이름. 숫자 값은 서버 설정 파일(matching-policy/{버전}.json)에 있음',
    `opportunity_expires_at` timestamptz COMMENT '만남 기회 마감 — 만남의 기회 마감. 확정된 만남 시각과 당시 정책으로 고정',
    `counterpart_deadline_at` timestamptz COMMENT '상대 기록 대기 마감 — 상대 기록 대기 마감. 기회 마감과 첫 유효 접수+대기 기간 중 빠른 시각',
    `evaluation` jsonb COMMENT '매칭 판정·이유 — 당시 입력의 최소 근거·판정·규칙 버전과 매칭 이유 설명. 비공개 원응답을 공개 설명에 넣지 않음. AI 설명 상태 {status, attempts, nextRetryAt} 포함(실패 시 주기 작업이 재시도)',
    `status` matching_status NOT NULL COMMENT '매칭 상태 — WAITING(한쪽 기록 대기) / NOT_MATCHED / PENDING(제안 보냄) / ONE_ACCEPTED(한쪽만 수락) / ACCEPTED / REJECTED / EXPIRED / CLOSED. WAITING(한쪽 기록만)·NOT_MATCHED(대기 만료·검증 실패)는 다시 만나면 같은 줄을 WAITING으로 재사용. PENDING(제안) 이후는 영구 재매칭 금지. 같은 두 사람은 uq_matching_pair로 한 줄만',
    `proposed_at` timestamptz COMMENT '제안 시각 — 양쪽 만남 기록 검증 후 서버에서 제안 생성을 확정한 최초 시각. 푸시 발송·수신 시각이 아니다. 응답 기한 24시간의 기산점이며 재전송·알림 실패로 연장하지 않는다.',
    `raw_delete_at` timestamptz COMMENT '만남 원본 삭제 기한 — 제안 생성·대기 만료·최종 검증 불가 기준 시각부터 14일. 행은 남기고 matching_participant.encounter_*만 비운다. 이전 삭제 작업은 같은 matching.id만 보고 새 기록을 삭제하면 안 된다. 선점한 만남 세대·삭제 기한이 현재와 같을 때만 삭제해야 하며 세대 저장 방식은 합의 전이다.',
    `legacy_state` jsonb COMMENT '기존 pair/proposal/encounter·제한·취소 전용 과거 증거. 최신 응답으로 덮어쓰지 않음',
    CONSTRAINT `pk_matching` PRIMARY KEY (`id`)
);
CREATE UNIQUE INDEX `uq_matching_pair` ON `matching` (`user_low_id`, `user_high_id`);
CREATE INDEX `ix_matching_low_status` ON `matching` (`user_low_id`, `status`);
CREATE INDEX `ix_matching_high_status` ON `matching` (`user_high_id`, `status`);
CREATE INDEX `ix_matching_proposal_expiration` ON `matching` (`status`, `proposed_at`);
CREATE INDEX `ix_matching_counterpart_deadline` ON `matching` (`counterpart_deadline_at`);
CREATE INDEX `ix_matching_raw_delete` ON `matching` (`raw_delete_at`);

-- 실물 키링
CREATE TABLE `keyring` (
    `id` bigint NOT NULL COMMENT '기기 ID — 공개 기기 ID. API deviceId',
    `serial_number` varchar(64) NOT NULL COMMENT '기기 고유번호 — 기기 고유번호. 조회 식별값이며 고유번호만으로 소유 인증을 대신하지 않음 (중복 금지)',
    `firmware_version` varchar(30) NOT NULL COMMENT '펌웨어 버전 — 현재 확인된 펌웨어 버전',
    `owner_user_id` bigint COMMENT '소유 회원 ID — 현재 소유 회원 ID. NULL이면 미귀속. 한 회원이 여러 개 등록 가능',
    `state` keyring_state NOT NULL COMMENT '기기 상태 — UNBOUND(미등록) / REGISTERED(등록됨). 기기 초기화·분실 차단은 MVP 기능 제외. 탈퇴·양도는 서버에서 소유자를 비우고 UNBOUND로(이후 그 기기 만남 기록은 무시), 기기 안 설정은 새 소유자가 등록할 때 덮어씀',
    `synced_settings_version` bigint COMMENT '적용된 매칭 성향 설정 버전 — 키링에 실제 저장됐다고 확인한 user_setting.settings_version. ACK 서명이 검증되고 ACK 번호가 그 순간 현재 settings_version과 같을 때만 갱신한다. 낮은 번호의 늦은 ACK는 무시하고 새 등록·해제 때 NULL로 둔다.',
    `synced_at` timestamptz COMMENT '설정 적용 시각 — 그 설정 버전의 실제 적용을 서버가 확인한 시각',
    CONSTRAINT `pk_keyring` PRIMARY KEY (`id`),
    CONSTRAINT `uq_keyring_serial_number` UNIQUE (`serial_number`)
);

-- 채팅 메시지
CREATE TABLE `chat_message` (
    `id` bigint NOT NULL COMMENT '메시지 ID — 공개 메시지 ID. API messageId',
    `chat_id` bigint NOT NULL COMMENT '채팅방 ID — 메시지가 속한 채팅방 ID',
    `seq` bigint NOT NULL COMMENT '방 안의 메시지 순번 — 방 안의 증가 순번. 조회·정렬·재접속 중복 제거에 사용',
    `sender_participant_id` bigint NOT NULL COMMENT '보낸 사람(채팅 참여자 번호) — chat_participant.id. 보낸 사람이 탈퇴해도 신고 보관 중인 방에서는 참여자 번호로 누가 보냈는지 구분',
    `body` text NOT NULL COMMENT '메시지 본문 — 입력 검증 및 웹 주소 치환을 마친 전체 본문. 치환 전 원문은 별도 저장하지 않음',
    `client_message_id` uuid NOT NULL COMMENT '클라이언트 메시지 ID — 클라이언트가 메시지 생성 시 발급하고 재전송에 재사용하는 UUID',
    `request_key` uuid NOT NULL COMMENT '중복 요청 방지 키 — 전송 요청의 Idempotency-Key. 기존 계약의 발신자 범위 중복 판정에 사용',
    `request_hash` bytea NOT NULL COMMENT '요청 내용 해시 — 정규화된 원문·요청 의미의 서버 계산 SHA-256. 원문 없이 다른 내용 재사용을 판정',
    `created_at` timestamptz NOT NULL COMMENT '메시지 저장 시각 — 최초 서버 저장 시각. 정상 재전송에 같은 값 반환',
    `legacy_public_id` uuid COMMENT '과거 공개 메시지 UUID 보존. 현재 공개 ID는 chat_message.id',
    CONSTRAINT `pk_chat_message` PRIMARY KEY (`id`)
);
CREATE UNIQUE INDEX `uq_chat_message_seq` ON `chat_message` (`chat_id`, `seq`);
CREATE UNIQUE INDEX `uq_chat_message_client` ON `chat_message` (`sender_participant_id`, `client_message_id`);
CREATE UNIQUE INDEX `uq_chat_message_request` ON `chat_message` (`sender_participant_id`, `request_key`);

-- 설문 회차 — 설문 한 번 = 한 줄. 문항별 답은 survey_answer에
CREATE TABLE `survey` (
    `id` bigint NOT NULL COMMENT '설문 회차 ID — 공개 설문 회차 ID. API sessionId',
    `user_id` bigint NOT NULL COMMENT '회원 ID — 응답 소유 회원 ID',
    `survey_version` varchar(30) NOT NULL COMMENT '설문 버전 — 회차 시작 때 고정한 설문 버전 이름. 문항·보기 내용은 DB가 아니라 서버 설정 파일(survey/{버전}.json)에 있음',
    `completed_at` timestamptz COMMENT '설문 제출 시각 — 제출 완료 시각. NULL이면 진행 중이며 계산 성공과는 별개',
    `client_request_id` uuid COMMENT '최초 생성 요청 키. 회원별 재시도는 같은 회차. 과거 미상은 NULL',
    `revision` bigint NOT NULL DEFAULT 0 COMMENT '응답 동시성 증거. 기존 revision을 보존하고 임의 초기화하지 않음',
    `legacy_public_id` uuid COMMENT '과거 공개 회차 UUID. 기존 값만 보존하고 신규 숫자 ID와 구분',
    `response_context` jsonb COMMENT 'answers 외 원본 업무 문맥. 문항별 답변의 중복 저장이 아님',
    `definition_snapshot` jsonb COMMENT '시작 시 검증한 불변 문항·manifest 증거. 과거 미상은 NULL',
    CONSTRAINT `pk_survey` PRIMARY KEY (`id`)
);
CREATE INDEX `ix_survey_user_version` ON `survey` (`user_id`, `survey_version`);

-- 채팅방 — 매칭 한 건당 방 하나. 사람별 상태는 chat_participant에. 참여자 한 명이 탈퇴하면 방과 딸린 표 모두 삭제. 단 신고된 방은 retain_until까지 보관
CREATE TABLE `chat` (
    `id` bigint NOT NULL COMMENT '채팅방 ID — 공개 채팅방 ID. API chatRoomId',
    `matching_id` bigint NOT NULL COMMENT '관계 ID — 방을 생성한 두 사람 관계의 내부 번호 (중복 금지)',
    `created_at` timestamptz NOT NULL COMMENT '채팅방 생성 시각 — 최초 방 생성 시각. 이용 만료는 이 시각+168시간',
    `closed_at` timestamptz COMMENT '채팅방 종료 시각 — 실제 종료 효력 시각. 정상 만료는 원래 만료 시각, 중복 처리로 변경하지 않음',
    `close_reason` chat_close_reason COMMENT '종료 사유 — 내부 종료 구분. 설계 코드: EXPIRED / LEFT / BLOCKED. 상대에게 구체 사유 공개 금지',
    `last_message_seq` bigint NOT NULL COMMENT '마지막 메시지 순번 — 방 안의 마지막 메시지 순번. 코드 초깃값 0',
    `icebreakers` jsonb NOT NULL COMMENT 'AI 시작 질문 — AI 시작 질문 3개·버전과 상태 {status, attempts, nextRetryAt}. 실패 시 주기 작업이 재시도, 최종 실패면 서버 설정 파일의 기본 질문으로 대체. 질문 사용 없이 대화 가능',
    `ai_state` jsonb NOT NULL COMMENT 'AI 위험 분석 상태 — 마지막 분석한 메시지 순번·상태 {status, attempts, nextRetryAt}. 실패 시 주기 작업이 재시도. 미분석/실패를 안전으로 표시하지 않음',
    `retain_until` timestamptz COMMENT '신고 보관 기한 — 최초 신고 + 90일. 이 기한 전에는 탈퇴해도 방·메시지를 지우지 않고, 기한이 지나면 삭제',
    `expires_at` timestamptz NOT NULL COMMENT '최초 이용 만료 시각. 생성+168시간 또는 검증한 기존 deadline. 재시도로 연장하지 않음',
    `delete_at` timestamptz COMMENT '일반 삭제 예약 시각 보존. 신고 retain_until보다 먼저 삭제하지 않음',
    `legacy_public_id` uuid COMMENT '과거 공개 방 UUID 보존. 현재 공개 ID는 chat.id',
    CONSTRAINT `pk_chat` PRIMARY KEY (`id`),
    CONSTRAINT `uq_chat_matching_id` UNIQUE (`matching_id`)
);
CREATE INDEX `ix_chat_closed` ON `chat` (`closed_at`);
CREATE INDEX `ix_chat_retain_until` ON `chat` (`retain_until`);

-- 프로필
CREATE TABLE `profile` (
    `user_id` bigint NOT NULL COMMENT '회원 ID — 프로필 소유 회원 ID',
    `nickname` varchar(20) COMMENT '닉네임 — NFC·앞뒤 일반 공백 정리 후 2~20자. 중복 금지는 lower(nickname) unique 인덱스(대소문자 무시)',
    `birth_date` date COMMENT '생년월일 — 생년월일. 완료 전 정정 가능, 완료 후 사용자가 직접 변경할 수 없음',
    `gender` gender COMMENT '성별 — 본인 성별 MALE / FEMALE. 상대 성별은 반대로 계산',
    `smoking_status` smoking_status COMMENT '흡연 여부 — 필수 흡연 선택 코드. 프로필 코드와 설문 답변을 임의 변환하지 않음',
    `drinking_frequency` drinking_frequency COMMENT '음주 빈도 — 필수 음주 빈도 선택 코드',
    `religion` religion COMMENT '종교 — 별도 동의를 받은 선택 종교 정보. 비공개·미입력을 무교로 추정하지 않음',
    `introduction` text COMMENT '자기소개 — 선택 자기소개. 최대 200자·줄바꿈 최대 3개 등 명세 기준 적용',
    `photo_url` varchar(2048) COMMENT '프로필 사진 주소 — NULL이면 사진 없음(hasProfilePhoto = 이 칸이 비어 있지 않음). 저장 전 서버 검증: (1) https:// 로 시작 (2) 우리 사진 저장소 주소(서버 설정 파일의 허용 주소)로 시작 (3) 그 안에서도 이 회원 폴더(profile/{userId}/)만 허용해 남의 사진 주소 도용 차단 (4) ? 가 붙은 주소 거부 = 금방 만료되는 서명 URL 저장 금지 (5) 2048자 이하, 빈 문자열·공백 거부(삭제는 사진 삭제 API로). 위반이면 400 PHOTO_URL_INVALID, 기존 사진 유지. DB에도 같은 안전장치(CHECK 제약): NULL이거나, https:// 로 시작하고 ? 가 없어야 저장됨. 삭제하면 NULL로 바꾸고(반복 삭제도 204) 저장소 파일은 정리 작업이 따로 지움. 탈퇴 시 파일도 삭제. 상대 사진 조회에서 NULL이면 410. 이미지 용량: 앱이 올리기 전에 1080×1080 이하로 줄이고 WebP(품질 80)로 바꿔 보통 300KB 안팎, 업로드 주소 발급 때 최대 1MB로 제한(넘으면 저장소가 업로드 거부)',
    `completed_at` timestamptz COMMENT '프로필 완료 시각 — 프로필 최종 등록 성공 시각. 초안 저장 시에는 설정하지 않음',
    `active_personality_id` bigint COMMENT '적용 중인 분석 ID — 현재 새 매칭에 적용하는 유효 분석 결과 ID. 재검사 대기·실패 중에는 기존 값 유지',
    CONSTRAINT `pk_profile` PRIMARY KEY (`user_id`)
);
CREATE UNIQUE INDEX `uq_profile_nickname_lower` ON `profile` (lower(nickname));

-- 매칭 조건 — 회원당 한 줄. 어떤 상대를 매칭할지 회원이 정한 조건. 바꾸면 user_setting.settings_version이 1 올라 키링에 다시 보냄
CREATE TABLE `matching_preference` (
    `user_id` bigint NOT NULL COMMENT '회원 ID — 조건 주인(users.id)',
    `matching_enabled` boolean NOT NULL COMMENT '매칭 사용 여부 — 새 매칭 사용 여부. 가입 시 초깃값 true',
    `age_preference_mode` boolean COMMENT '희망 나이 지정 여부 — NULL: 아직 선택 안 함 / false: 상관없음 / true: min_age·max_age 범위 지정. 희망 조건 단계 완료에는 직접 선택 필요(NULL 불가)',
    `min_age` integer COMMENT '희망 최소 나이 — 만 나이, 최소 19. age_preference_mode가 false면 NULL',
    `max_age` integer COMMENT '희망 최대 나이 — 만 나이. NULL이면 상한 없음. age_preference_mode가 false면 NULL',
    `photo_required` boolean NOT NULL COMMENT '사진 필수 여부 — 사진 있는 상대만 새 매칭할지. 가입 시 초깃값 false',
    CONSTRAINT `pk_matching_preference` PRIMARY KEY (`user_id`)
);

-- 회원 설정 — 회원당 한 줄. 키링 진동·LED와 키링에 보낼 설정 버전
CREATE TABLE `user_setting` (
    `user_id` bigint NOT NULL COMMENT '회원 ID — 설정 소유 회원 ID',
    `vibration_enabled` boolean NOT NULL COMMENT '진동 사용 여부 — 키링 진동 사용 여부. 가입 시 초깃값 true',
    `led_enabled` boolean NOT NULL COMMENT 'LED 사용 여부 — 키링 LED 사용 여부. 가입 시 초깃값 true',
    `settings_version` bigint NOT NULL COMMENT '매칭 성향 설정 버전 — 키링에 보낼 매칭 조건·진동/LED·적용 성향·제외 상대가 바뀌는 같은 트랜잭션에서 1 증가한다. 저장 성공일 뿐 기기 적용은 keyring.synced_settings_version으로 따로 확인한다.',
    CONSTRAINT `pk_user_setting` PRIMARY KEY (`user_id`)
);

-- 매칭 참여자별 기록 — 매칭 한 건에 두 사람이니 두 줄. 각자 보낸 만남 기록, 수락/거절, 차단을 사람별로 저장
CREATE TABLE `matching_participant` (
    `matching_id` bigint NOT NULL COMMENT '매칭 ID — 어느 매칭의 참여자인지(matching.id)',
    `user_id` bigint NOT NULL COMMENT '회원 ID — 이 줄의 주인. 탈퇴하면 매칭째로 삭제',
    `encounter_keyring_id` bigint COMMENT '만남을 기록한 키링 ID — 이 사람이 만남 기록을 보낼 때 쓴 키링(keyring.id). 기록 전에는 NULL',
    `encounter_signature` bytea COMMENT '만남 기록 증명 서명 — 키링이 기기 키(마스터 키 + serial_number로 계산, DB 저장 안 함)로 만든 서명. 원본 삭제 기한이 지나면 비움',
    `encounter_reported_at` timestamptz COMMENT '만남 기록 접수 시각 — 이 사람의 만남 기록을 서버가 받은 시각. NULL이면 아직 기록 안 보냄',
    `encounter_verified_at` timestamptz COMMENT '만남 기록 검증 시각 — 서명 검증에 성공한 시각. 검증 실패면 NULL로 남고 매칭 상태가 NOT_MATCHED',
    `response` proposal_response COMMENT '제안 응답 — ACCEPTED(수락) / REJECTED(거절). NULL이면 아직 응답 안 함. 한 번 정하면 못 바꿈',
    `responded_at` timestamptz COMMENT '응답 시각 — 수락·거절을 누른 시각',
    `blocked_at` timestamptz COMMENT '차단 시각 — 이 사람이 상대를 차단한 시각. NULL이면 차단 안 함. 차단은 나가기와 구분',
    `decision_request_key` uuid COMMENT '최초 응답 확정 키. 회원 범위 중복 사용 차단과 최초 선택·시각 보존',
    `request_state` jsonb COMMENT '명령·재전송 receipt 및 구 취소 키/시각. 새 수락 취소 성공을 뜻하지 않음',
    CONSTRAINT `pk_matching_participant` PRIMARY KEY (`matching_id`, `user_id`)
);
CREATE INDEX `ix_matching_participant_user` ON `matching_participant` (`user_id`);

-- 채팅방 참여자 — 채팅방 하나에 두 사람이니 두 줄. 각자 어디까지 읽었는지, 나갔는지 저장
CREATE TABLE `chat_participant` (
    `id` bigint NOT NULL COMMENT '채팅 참여자 번호 — 메시지·신고·위험 안내가 이 번호로 사람을 가리킴',
    `chat_id` bigint NOT NULL COMMENT '채팅방 ID — 어느 방의 참여자인지(chat.id)',
    `user_id` bigint COMMENT '회원 ID — 이 참여자 회원. 신고 보관 중인 방에서 이 회원이 탈퇴하면 NULL로 비움(줄은 남겨 발신자 구분 유지)',
    `last_read_message_seq` bigint NOT NULL DEFAULT 0 COMMENT '마지막 실제 표시 순번 — 본인 화면에 정상 표시된 마지막 chat_message.seq. 새 수신 메시지는 이보다 큰 순번 중 발신 참여자가 본인이 아닌 메시지다. 상대에게 공개하지 않는다. 건너뛴 메시지를 잘못 확인하지 않도록 연속 표시 범위·ACK 계약은 별도 검증한다.',
    `left_at` timestamptz COMMENT '나간 시각 — 이 사람이 방을 나간 시각. NULL이면 아직 방에 있음',
    `notice_state` jsonb NOT NULL DEFAULT '{}' COMMENT '일반안내·예약·최초ACK·선점·lease·재시도·설치별 전달 결과. AI 상태와 분리',
    `read_state` jsonb NOT NULL DEFAULT '{}' COMMENT '본인 messageId별 최초 표시 시각·기존 receipt. 불연속 배치를 max(seq) 읽음으로 추정하지 않음',
    CONSTRAINT `pk_chat_participant` PRIMARY KEY (`id`)
);
CREATE UNIQUE INDEX `uq_chat_participant_member` ON `chat_participant` (`chat_id`, `user_id`);
CREATE INDEX `ix_chat_participant_user` ON `chat_participant` (`user_id`);

-- 채팅 신고 — 신고 한 건당 한 줄. 첫 신고가 들어오면 chat.retain_until(+90일)이 정해짐
CREATE TABLE `chat_report` (
    `id` bigint NOT NULL COMMENT '신고 번호',
    `chat_id` bigint NOT NULL COMMENT '신고된 채팅방 ID — chat.id',
    `reporter_participant_id` bigint NOT NULL COMMENT '신고한 사람(채팅 참여자 번호) — chat_participant.id',
    `reason` chat_report_reason NOT NULL COMMENT '신고 사유 코드 — 예: ABUSE(욕설) / SEXUAL(성적 발언) / SPAM(광고) / ETC(기타)',
    `detail` text COMMENT '신고 상세 내용 — 신고한 사람이 직접 쓴 설명. 선택',
    `status` chat_report_status NOT NULL COMMENT '처리 상태 — RECEIVED(접수) / REVIEWING(검토 중) / RESOLVED(처리 완료) / DISMISSED(문제 없음)',
    `created_at` timestamptz NOT NULL COMMENT '신고 접수 시각',
    `resolved_at` timestamptz COMMENT '처리 완료 시각 — RESOLVED·DISMISSED가 된 시각',
    CONSTRAINT `pk_chat_report` PRIMARY KEY (`id`)
);
CREATE INDEX `ix_chat_report_worklist` ON `chat_report` (`status`, `created_at`);
CREATE INDEX `ix_chat_report_chat` ON `chat_report` (`chat_id`);

-- 신고에 첨부한 메시지 — 신고 한 건에 메시지 여러 개를 고를 수 있어 따로 둠. 신고 하나 × 메시지 하나 = 한 줄
CREATE TABLE `chat_report_message` (
    `report_id` bigint NOT NULL COMMENT '신고 번호 — chat_report.id',
    `message_id` bigint NOT NULL COMMENT '신고된 메시지 ID — chat_message.id',
    CONSTRAINT `pk_chat_report_message` PRIMARY KEY (`report_id`, `message_id`)
);

-- AI 위험 안내 — AI가 위험한 대화를 감지했을 때 한 사람에게 보여 준 안내. 같은 사람·같은 유형은 방마다 한 번만
CREATE TABLE `chat_safety_notice` (
    `id` bigint NOT NULL COMMENT '위험 안내 번호',
    `chat_participant_id` bigint NOT NULL COMMENT '안내를 받은 사람(채팅 참여자 번호) — chat_participant.id',
    `notice_type` safety_notice_type NOT NULL COMMENT '위험 유형 — 예: MONEY_REQUEST(금전 요구) / PERSONAL_INFO(개인정보 요구) / OFF_PLATFORM(외부 연락 유도) / HARASSMENT(괴롭힘)',
    `created_at` timestamptz NOT NULL COMMENT '안내 생성 시각 — AI가 감지해 안내를 만든 시각',
    `displayed_at` timestamptz COMMENT '안내 표시 시각 — 이 사람 화면에 실제로 보여 준 시각. NULL이면 아직 안 봄',
    CONSTRAINT `pk_chat_safety_notice` PRIMARY KEY (`id`)
);
CREATE UNIQUE INDEX `uq_chat_safety_notice_once` ON `chat_safety_notice` (`chat_participant_id`, `notice_type`);

-- 설문 문항별 답 — 설문 한 번에 문항 수만큼 줄. 문항·보기 내용은 설문 파일(survey/{버전}.json)에 있고 여기엔 고른 값만
CREATE TABLE `survey_answer` (
    `survey_id` bigint NOT NULL COMMENT '설문 회차 ID — survey.id',
    `question_id` varchar(50) NOT NULL COMMENT '문항 ID — 설문 파일에 있는 문항 번호(예: Q07)',
    `answer_value` varchar(100) NOT NULL COMMENT '고른 답 — 설문 파일의 보기 ID나 점수(예: 4). 설문 버전의 보기 목록으로 검증',
    `answered_at` timestamptz COMMENT '답한 시각 — 같은 문항을 다시 고르면 값과 시각을 덮어씀',
    `answer_type` text COMMENT 'scalar 원래 형식 STRING/NUMBER/BOOLEAN/NULL. 증거 미상은 추정하지 않음',
    CONSTRAINT `pk_survey_answer` PRIMARY KEY (`survey_id`, `question_id`)
);

-- 지원 요청 처리 기록 — 추가 확인 요청·회원 답변·운영 작업 결과를 일어난 순서대로 한 줄씩
CREATE TABLE `service_case_history` (
    `id` bigint NOT NULL COMMENT '처리 기록 번호',
    `service_case_id` bigint NOT NULL COMMENT '지원 요청 ID — service_case.id',
    `event` service_case_event NOT NULL COMMENT '무슨 일 — INFO_REQUESTED / USER_REPLIED / ACTION_DONE / ACTION_FAILED / STATUS_CHANGED',
    `actor_user_id` bigint COMMENT '한 사람 — 운영자나 요청 회원(users.id). 시스템 자동 처리면 NULL',
    `message` text COMMENT '내용 — 추가 확인 질문, 회원 답변, 실패 사유 등',
    `created_at` timestamptz NOT NULL COMMENT '기록 시각',
    CONSTRAINT `pk_service_case_history` PRIMARY KEY (`id`)
);
CREATE INDEX `ix_service_case_history_case` ON `service_case_history` (`service_case_id`, `created_at`);

-- 정정·분실 지원 요청 — 요청 한 건 = 한 줄. 처리하며 주고받은 기록은 service_case_history에
CREATE TABLE `service_case` (
    `id` bigint NOT NULL COMMENT '지원 요청 ID — 요청 공개 ID. 접수 결과·추가 확인·처리 상태 조회에 사용',
    `requester_user_id` bigint NOT NULL COMMENT '요청 회원 ID — 서버 인증으로 확인한 요청 회원 ID',
    `kind` service_case_kind NOT NULL COMMENT '지원 요청 종류 — 요청 종류. 설계 코드: PROFILE_CORRECTION / LOST_WITHDRAWAL',
    `reason` text NOT NULL COMMENT '요청 사유 — 회원이 직접 쓴 설명. 증빙 사진·파일 사본은 저장 안 함',
    `correction_field` profile_correction_field COMMENT '정정할 항목 — PROFILE_CORRECTION일 때만. 분실 탈퇴 요청이면 NULL',
    `correction_value` varchar(100) COMMENT '바꿔 달라는 값 — PROFILE_CORRECTION일 때만. 예: 생년월일 1999-03-01',
    `lost_keyring_id` bigint COMMENT '분실한 키링 ID — LOST_WITHDRAWAL일 때만(keyring.id). 정정 요청이면 NULL',
    `status` service_case_status NOT NULL COMMENT '요청 처리 상태 — 설계 코드: RECEIVED / REVIEWING / NEEDS_INFO / COMPLETED / REJECTED / NO_RESPONSE',
    `assigned_operator_id` bigint COMMENT '담당 운영자 ID — 현재 담당 운영자 ID. 배정 전 NULL',
    `created_at` timestamptz NOT NULL COMMENT '요청 접수 시각 — 최초 접수 성공 시각',
    `reply_due_at` timestamptz COMMENT '추가 확인 응답 기한 — 유효한 추가 확인 전달 후 사용자 응답 마감. 14일 무응답 처리용이며 전달 실패로 시작하지 않음',
    `closed_at` timestamptz COMMENT '요청 종료 시각 — 완료·반려·응답 없음의 최초 종료 시각. 원칙적으로 이 시각+720시간에 업무 기록 삭제',
    CONSTRAINT `pk_service_case` PRIMARY KEY (`id`)
);
CREATE INDEX `ix_service_case_requester` ON `service_case` (`requester_user_id`, `created_at`);
CREATE INDEX `ix_service_case_worklist` ON `service_case` (`status`, `assigned_operator_id`);
CREATE INDEX `ix_service_case_reply_due` ON `service_case` (`reply_due_at`);
CREATE INDEX `ix_service_case_closed` ON `service_case` (`closed_at`);

-- Source design relationships (logical):
ALTER TABLE `personality` ADD CONSTRAINT `rel_personality_survey_id` FOREIGN KEY (`survey_id`) REFERENCES `survey` (`id`);
ALTER TABLE `refresh_token` ADD CONSTRAINT `rel_refresh_token_user_id` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`);
ALTER TABLE `push_installation` ADD CONSTRAINT `rel_push_installation_user_id` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`);
ALTER TABLE `operator_audit` ADD CONSTRAINT `rel_operator_audit_operator_id` FOREIGN KEY (`operator_id`) REFERENCES `users` (`id`);
ALTER TABLE `matching` ADD CONSTRAINT `rel_matching_user_low_id` FOREIGN KEY (`user_low_id`) REFERENCES `users` (`id`);
ALTER TABLE `matching` ADD CONSTRAINT `rel_matching_user_high_id` FOREIGN KEY (`user_high_id`) REFERENCES `users` (`id`);
ALTER TABLE `keyring` ADD CONSTRAINT `rel_keyring_owner_user_id` FOREIGN KEY (`owner_user_id`) REFERENCES `users` (`id`);
ALTER TABLE `chat_message` ADD CONSTRAINT `rel_chat_message_chat_id` FOREIGN KEY (`chat_id`) REFERENCES `chat` (`id`);
ALTER TABLE `chat_message` ADD CONSTRAINT `rel_chat_message_sender_participant_id` FOREIGN KEY (`sender_participant_id`) REFERENCES `chat_participant` (`id`);
ALTER TABLE `survey` ADD CONSTRAINT `rel_survey_user_id` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`);
ALTER TABLE `chat` ADD CONSTRAINT `rel_chat_matching_id` FOREIGN KEY (`matching_id`) REFERENCES `matching` (`id`);
ALTER TABLE `profile` ADD CONSTRAINT `rel_profile_user_id` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`);
ALTER TABLE `user_setting` ADD CONSTRAINT `rel_user_setting_user_id` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`);
ALTER TABLE `matching_preference` ADD CONSTRAINT `rel_matching_preference_user_id` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`);
ALTER TABLE `profile` ADD CONSTRAINT `rel_profile_active_personality_id` FOREIGN KEY (`active_personality_id`) REFERENCES `personality` (`id`);
ALTER TABLE `service_case` ADD CONSTRAINT `rel_service_case_requester_user_id` FOREIGN KEY (`requester_user_id`) REFERENCES `users` (`id`);
ALTER TABLE `service_case` ADD CONSTRAINT `rel_service_case_assigned_operator_id` FOREIGN KEY (`assigned_operator_id`) REFERENCES `users` (`id`);
ALTER TABLE `matching_participant` ADD CONSTRAINT `rel_matching_participant_matching_id` FOREIGN KEY (`matching_id`) REFERENCES `matching` (`id`);
ALTER TABLE `matching_participant` ADD CONSTRAINT `rel_matching_participant_user_id` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`);
ALTER TABLE `matching_participant` ADD CONSTRAINT `rel_matching_participant_encounter_keyring_id` FOREIGN KEY (`encounter_keyring_id`) REFERENCES `keyring` (`id`);
ALTER TABLE `chat_participant` ADD CONSTRAINT `rel_chat_participant_chat_id` FOREIGN KEY (`chat_id`) REFERENCES `chat` (`id`);
ALTER TABLE `chat_participant` ADD CONSTRAINT `rel_chat_participant_user_id` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`);
ALTER TABLE `chat_report` ADD CONSTRAINT `rel_chat_report_chat_id` FOREIGN KEY (`chat_id`) REFERENCES `chat` (`id`);
ALTER TABLE `chat_report` ADD CONSTRAINT `rel_chat_report_reporter_participant_id` FOREIGN KEY (`reporter_participant_id`) REFERENCES `chat_participant` (`id`);
ALTER TABLE `chat_report_message` ADD CONSTRAINT `rel_chat_report_message_report_id` FOREIGN KEY (`report_id`) REFERENCES `chat_report` (`id`);
ALTER TABLE `chat_report_message` ADD CONSTRAINT `rel_chat_report_message_message_id` FOREIGN KEY (`message_id`) REFERENCES `chat_message` (`id`);
ALTER TABLE `chat_safety_notice` ADD CONSTRAINT `rel_chat_safety_notice_chat_participant_id` FOREIGN KEY (`chat_participant_id`) REFERENCES `chat_participant` (`id`);
ALTER TABLE `survey_answer` ADD CONSTRAINT `rel_survey_answer_survey_id` FOREIGN KEY (`survey_id`) REFERENCES `survey` (`id`);
ALTER TABLE `service_case` ADD CONSTRAINT `rel_service_case_lost_keyring_id` FOREIGN KEY (`lost_keyring_id`) REFERENCES `keyring` (`id`);
ALTER TABLE `service_case_history` ADD CONSTRAINT `rel_service_case_history_service_case_id` FOREIGN KEY (`service_case_id`) REFERENCES `service_case` (`id`);
ALTER TABLE `service_case_history` ADD CONSTRAINT `rel_service_case_history_actor_user_id` FOREIGN KEY (`actor_user_id`) REFERENCES `users` (`id`);
ALTER TABLE `operator_audit` ADD CONSTRAINT `rel_operator_audit_target_user_id` FOREIGN KEY (`target_user_id`) REFERENCES `users` (`id`);
ALTER TABLE `operator_audit` ADD CONSTRAINT `rel_operator_audit_target_service_case_id` FOREIGN KEY (`target_service_case_id`) REFERENCES `service_case` (`id`);
ALTER TABLE `operator_audit` ADD CONSTRAINT `rel_operator_audit_target_keyring_id` FOREIGN KEY (`target_keyring_id`) REFERENCES `keyring` (`id`);
ALTER TABLE `operator_audit` ADD CONSTRAINT `rel_operator_audit_target_chat_report_id` FOREIGN KEY (`target_chat_report_id`) REFERENCES `chat_report` (`id`);
ALTER TABLE `user_consents` ADD CONSTRAINT `rel_user_consents_user_id` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`);

-- Approved domain preservation indexes; no shared DB execution.
CREATE UNIQUE INDEX `uq_survey_user_request` ON `survey` (`user_id`, `client_request_id`);
CREATE UNIQUE INDEX `uq_survey_legacy_public_id` ON `survey` (`legacy_public_id`);
CREATE UNIQUE INDEX `uq_survey_draft` ON `survey` (`user_id`, `survey_version`) WHERE `completed_at` IS NULL;
CREATE UNIQUE INDEX `uq_chat_legacy_public_id` ON `chat` (`legacy_public_id`);
CREATE UNIQUE INDEX `uq_chat_message_legacy_public_id` ON `chat_message` (`legacy_public_id`);
CREATE UNIQUE INDEX `uq_matching_participant_user_request` ON `matching_participant` (`user_id`, `decision_request_key`);
