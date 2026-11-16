-- 운영 DB 보완 (2026-11-17, S15P31E106-407): 개발·운영 스위치 제거로 운영에서도 키링 초기화(app.keyring-reset)가 늘 켜진다.
-- 키링 초기화 표는 엔티티가 아니라(JDBC 전용) validate 로 잡히지 않고, 설정 동기화·소유 세션·기록 명령이 초기화 대기 여부를 이 표에서 읽는다.
-- 표가 없으면 그 요청들이 실패하므로 master 머지 전에 먼저 실행한다. 원본: back/src/main/resources/database/keyring-reset-249.sql
-- 이 파일보다 먼저 적용돼 있어야 하는 것: 2026-11-06-peer-flow-270.sql(TSV3 표), 2026-11-06-member-withdrawal.sql,
--   2026-11-06-keyring-ownership-epoch.sql, 247-keyring-server-proofs.sql. 나머지 새로 켜지는 기능(탈퇴·푸시·채팅 삭제·만남 평가)은
--   엔티티 표만 쓰므로 추가 변경이 없다.
-- 표 추가만 하므로 운영 중인 앱에 영향 없음. 다시 실행해도 안전(IF NOT EXISTS).
BEGIN;

CREATE TABLE IF NOT EXISTS keyring_reset_run (
    run_id uuid primary key,
    keyring_id bigint not null,
    owner_user_id bigint not null,
    owner_generation uuid not null,
    request_key uuid not null,
    discard_unsent boolean not null,
    nonce varchar(43) not null,
    command text not null,
    created_at timestamptz not null,
    expires_at timestamptz not null,
    from_ownership_epoch bigint not null,
    challenge_id uuid,
    device_nonce varchar(43),
    request_hash varchar(64) not null,
    challenge_hash varchar(64) not null,
    proof_hash varchar(64),
    completed_at timestamptz,
    constraint uq_keyring_reset_generation unique(keyring_id, owner_generation),
    constraint uq_keyring_reset_request unique(owner_user_id, keyring_id, request_key),
    constraint ck_keyring_reset_complete check ((completed_at is null) = (proof_hash is null))
);

COMMIT;
