-- 운영 적용: 미적용 (S15P31E106-295)
-- 운영 DB 보완 (2026-11-10): 회원별 등록 키링 조회 인덱스. 가입 단계 확인(로그인한 API 요청마다)·설정 버전 조회가
-- keyring.owner_user_id 로 찾는데 인덱스가 없어 키링 표 전체를 읽고 있었다. 인덱스 추가만 하므로 데이터·앱 동작은 그대로.
-- CONCURRENTLY 는 표 쓰기를 막지 않고 만들지만 트랜잭션(BEGIN) 안에서 실행할 수 없어 단독으로 실행한다.
-- 다시 실행해도 안전(IF NOT EXISTS). 중간에 실패해 INVALID 인덱스가 남으면 DROP INDEX CONCURRENTLY ix_keyring_owner; 후 다시 실행.

CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_keyring_owner ON keyring(owner_user_id);

-- 완료 확인: 아래 결과가 t 여야 끝난 것. f 면 중간 실패로 쓸 수 없는 인덱스가 남은 것이니 위 주석대로 지우고 다시 실행한다.
SELECT indisvalid FROM pg_index WHERE indexrelid = 'ix_keyring_owner'::regclass;
