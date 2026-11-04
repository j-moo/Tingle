-- 운영 DB(tingle) 구조 변경 — 종교 동의 철회 시 과거 설정 입력 사본의 종교 삭제(S15P31E106-232).
-- 이 back 버전을 배포하기 전에 실행한다(운영 back 은 ddl-auto: validate 라 칸이 없으면 뜨지 않는다).
-- 기존 사본의 종교는 배포 후 기동 정리(LegacyOptionalInfoCleanup)가 동의 기록을 보고 지운다.
-- 가린 사본에 의존하는 매칭의 원문·파생 해시 복사본도 앱 코드가 폐기한다(이미 가린 사본 포함).
-- 중복 접수·재발행 방지 식별자와 기존 채팅은 유지하며, 버전·출처가 없는 해당 회원의 구형 근거는 보수적으로 폐기한다.
-- 전체 DB 초기화나 재동의 뒤 새 사본 삭제는 하지 않는다. 이 SQL 자체는 위 데이터 정리를 실행하지 않는다.
-- 실행: docker exec -i tingle-db-1 sh -c 'psql -U "$POSTGRES_USER" -d tingle -v ON_ERROR_STOP=1' < infra/db/prod-changes/2026-11-03-settings-snapshot-redaction.sql
--       개발 DB 는 -d tingle_dev 로 같은 파일을 실행한다.
BEGIN;

ALTER TABLE settings_snapshot ADD COLUMN IF NOT EXISTS redacted_at timestamptz NULL;
ALTER TABLE settings_snapshot ADD COLUMN IF NOT EXISTS redaction_reason varchar(40) NULL;
COMMENT ON COLUMN settings_snapshot.redacted_at IS '동의 철회로 입력 값 일부를 지운 시각; 지운 사본은 해시도 0으로 지워 근거로 쓰지 않음';
COMMENT ON COLUMN settings_snapshot.redaction_reason IS '값을 지운 사유(예: PROFILE_RELIGION_WITHDRAWN); 지운 원문은 남기지 않음';

COMMIT;
