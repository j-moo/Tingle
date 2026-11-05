-- 운영 DB(tingle) 구조 변경 — 프로필 사진 서버 저장(S15P31E106-55). 이 back 버전을 운영에 배포하기 전에 실행 (운영 back 은 ddl-auto: validate)
-- profile.photo_url 은 https 주소가 아니라 서버 사진 폴더 기준 파일 키 profile/{userId}/{uuid}.jpg 만 저장한다 (docs/database/schema.sql 과 같은 CHECK).
-- 새 형식이 아닌 기존 값(옛 https 주소 등)은 가리키는 파일이 서버에 없으므로 NULL(사진 없음)로 바꾼다. 해당 회원은 사진을 다시 올려야 한다.
-- 배포 전 서버 폴더 준비(back 컨테이너는 uid 1000 으로 실행되므로 Docker 가 root 로 만들면 업로드가 실패한다):
--   sudo mkdir -p /data/uploads/prod /data/uploads/dev && sudo chown 1000:1000 /data/uploads/prod /data/uploads/dev
-- 실행: docker exec -i tingle-db-1 sh -c 'psql -U "$POSTGRES_USER" -d tingle -v ON_ERROR_STOP=1' < infra/db/prod-changes/2026-10-29-profile-photo.sql
--       schema.sql 로 만든 개발 DB 면 -d tingle_dev 로 같은 파일을 실행한다.
BEGIN;

UPDATE profile SET photo_url = NULL
WHERE photo_url IS NOT NULL AND photo_url !~ '^profile/[0-9]+/[0-9a-f-]{36}\.jpg$';

ALTER TABLE profile DROP CONSTRAINT IF EXISTS ck_profile_photo_url;
ALTER TABLE profile ADD CONSTRAINT ck_profile_photo_url
    CHECK (photo_url IS NULL OR photo_url ~ '^profile/[0-9]+/[0-9a-f-]{36}\.jpg$');

COMMIT;
