-- 운영 적용: 미적용 (S15P31E106-366)
-- AI 위험 안내를 받은 사람이 보낸 쪽(SENDER)인지 받은 쪽(RECIPIENT)인지 남긴다. 앱이 안내 문구와 차단 버튼을 고른다.
-- 기존 행은 NULL(구분 없음)로 남고 데이터·동작은 그대로. 다시 실행해도 안전(IF NOT EXISTS · 제약은 없을 때만 추가).
--
-- 순서: [1] 칸 추가 → 새 버전 배포 → [2] 인덱스 교체
--   운영: [1] 을 배포 전에(ddl-auto: validate 라 칸이 없으면 서버가 안 뜬다), [2] 를 배포 후에 실행한다.
--   개발: [1] 은 배포 때 자동으로 생긴다(ddl-auto: update). [2] 는 자동으로 안 바뀌니 배포 후 직접 실행한다.
--   새 버전은 [2] 전에도 오류 없이 돈다(그동안은 예전처럼 같은 사람·같은 유형 한 번만 저장).
--   [2] 를 배포 전에 실행하면 옛 버전의 저장 SQL 이 42P10 으로 실패하니 순서를 지킨다.

-- [1] 배포 전: 칸 추가
ALTER TABLE chat_safety_notice ADD COLUMN IF NOT EXISTS audience varchar(16);
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_chat_safety_notice_audience') THEN
    ALTER TABLE chat_safety_notice ADD CONSTRAINT ck_chat_safety_notice_audience CHECK (audience IN ('SENDER', 'RECIPIENT'));
  END IF;
END $$;
COMMENT ON COLUMN chat_safety_notice.displayed_at IS '안내 확인 시각 — 이 사람이 앱에서 안내를 확인(닫음)한 시각. NULL이면 아직 확인 안 함(안내가 계속 보임)';
COMMENT ON COLUMN chat_safety_notice.audience IS '안내 대상 — SENDER(위험한 말을 보낸 사람) / RECIPIENT(받은 사람). NULL이면 이전 기록(구분 없음)';

-- [2] 배포 후: 한 번만 저장하는 기준에 audience 를 더한다(개발 DB 도 직접 실행).
-- 서로 욕설을 주고받으면 한 사람이 보낸 쪽 안내와 받은 쪽 안내를 각각 한 번씩 받아야 한다. 기존 안내가 0건이라 충돌 없음.
-- 서버가 표를 직접 만든 DB(ddl-auto: update)에서는 인덱스가 아니라 제약으로 있어서 DROP INDEX 만으로는 지워지지 않는다. 둘 다 실행한다.
ALTER TABLE chat_safety_notice DROP CONSTRAINT IF EXISTS uq_chat_safety_notice_once;
DROP INDEX IF EXISTS uq_chat_safety_notice_once;
CREATE UNIQUE INDEX uq_chat_safety_notice_once ON chat_safety_notice (chat_participant_id, notice_type, audience);
COMMENT ON TABLE chat_safety_notice IS 'AI 위험 안내 — AI가 위험한 대화를 감지했을 때 한 사람에게 보여 준 안내. 같은 사람·같은 유형·같은 대상(보낸 쪽/받은 쪽)은 방마다 한 번만';

-- 완료 확인: [1] 뒤에는 varchar 16 한 줄, [2] 뒤에는 인덱스 정의 끝이 (chat_participant_id, notice_type, audience) 이면 끝난 것.
SELECT data_type, character_maximum_length FROM information_schema.columns
WHERE table_name = 'chat_safety_notice' AND column_name = 'audience';
SELECT indexdef FROM pg_indexes WHERE indexname = 'uq_chat_safety_notice_once';
