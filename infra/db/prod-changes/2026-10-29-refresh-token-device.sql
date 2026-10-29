-- 운영 DB(tingle) 구조 변경 — 2026-10-29 로그인 기기 1개 변경(be-fix/auth-session-underage)
-- 로그인 기기가 1개라 기기 이름 · 마지막 사용 시각을 더 쓰지 않는다. 설계 DDL(docs/database/schema.sql)에서도 뺐다.
-- 순서: 이 back 버전을 운영에 배포한 "뒤에" 실행한다. 이전 버전 back 은 이 칸에 값을 넣으므로 먼저 지우면 로그인이 실패한다.
--       (새 버전은 ddl-auto validate 라 칸이 남아 있어도 뜬다.)
-- 실행: docker exec -i tingle-db-1 sh -c 'psql -U "$POSTGRES_USER" -d tingle -v ON_ERROR_STOP=1' < infra/db/prod-changes/2026-10-29-refresh-token-device.sql
--       개발 DB 는 -d tingle_dev 로 같은 파일을 실행한다.
BEGIN;

ALTER TABLE refresh_token
    DROP COLUMN IF EXISTS device_name,
    DROP COLUMN IF EXISTS last_used_at;

COMMIT;
