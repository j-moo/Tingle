-- Apply before deploying the block-management API. Does not change historical blocked_at.
-- Existing relations receive an immutable UUID on their first owner list/command under transaction locks.
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
