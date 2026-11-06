-- 운영 적용: 2026-11-05 (S15P31E106-259 에서 기록)
-- 운영 DB 보완 (2026-11-05): master 코드가 기대하지만 운영에 없던 칸·표 추가. 추가만 하므로 운영 중인 앱에 영향 없음.
-- 다시 실행해도 안전(IF NOT EXISTS).
BEGIN;

-- 채팅 새 메시지 알림 상태 (기존 메시지는 빈 객체)
ALTER TABLE chat_message ADD COLUMN IF NOT EXISTS push_state jsonb NOT NULL DEFAULT '{}'::jsonb;

-- 회원 차단 관계 (현재 차단 목록)
CREATE TABLE IF NOT EXISTS user_block (
    id uuid PRIMARY KEY,
    owner_id bigint NOT NULL,
    target_id bigint NOT NULL,
    matching_id bigint NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT uq_user_block_owner_target UNIQUE (owner_id, target_id),
    CONSTRAINT ck_user_block_distinct CHECK (owner_id <> target_id)
);
CREATE INDEX IF NOT EXISTS ix_user_block_owner_created ON user_block(owner_id, created_at, id);

COMMIT;
