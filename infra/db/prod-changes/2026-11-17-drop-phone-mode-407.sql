-- 개발·운영 DB 정리 (2026-11-17, S15P31E106-407): 휴대폰 만남 모드를 코드째 지웠다(결정 D1).
-- 회원 FK 가 없어 탈퇴 정리에서도 더는 지우지 않으므로 남은 행이 쌓이지 않게 표를 지운다.
-- 실행 대상: 운영 DB tingle 과 개발 DB tingle_dev 둘 다(운영에는 보통 없다). 다시 실행해도 안전(IF EXISTS).
-- 엔티티가 아니므로 앱 기동·검증에 영향 없다. 참조 순서대로 지운다.
BEGIN;

DROP TABLE IF EXISTS phone_mode_evaluation_input;
DROP TABLE IF EXISTS phone_mode_evaluation;
DROP TABLE IF EXISTS phone_mode_submission;
DROP TABLE IF EXISTS phone_mode_token;
DROP TABLE IF EXISTS phone_mode_request;
DROP TABLE IF EXISTS phone_mode_encounter;
DROP TABLE IF EXISTS phone_mode_device;

COMMIT;
