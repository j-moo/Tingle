-- 운영 적용: 미적용 (S15P31E106-331)
-- 키링 별명(label) 칸 추가. 한 계정에 키링이 여러 개일 때 앱에서 번호 대신 이름으로 구분한다.
-- 비어 있으면(NULL) 앱에는 일련번호 앞 10자가 이름으로 보인다. 기존 행은 모두 NULL이라 데이터·동작은 그대로.
-- 운영 서버는 ddl-auto: validate 라서 이 칸이 없으면 새 버전이 뜨지 않는다. 새 버전 배포 전에 실행한다.
-- 다시 실행해도 안전(IF NOT EXISTS).

ALTER TABLE keyring ADD COLUMN IF NOT EXISTS label varchar(10);
COMMENT ON COLUMN keyring.label IS '주인이 붙인 별명(최대 10자). NULL이면 앱에 일련번호 앞 10자를 보여 준다. 본인 앱 화면에서만 쓰고 기기·상대·AI 서버로 보내지 않으며, 주인이 바뀌면(초기화·탈퇴 해제·새 등록) 비운다.';

-- 완료 확인: 아래 결과가 varchar 10 한 줄이면 끝난 것.
SELECT data_type, character_maximum_length FROM information_schema.columns
WHERE table_name = 'keyring' AND column_name = 'label';
