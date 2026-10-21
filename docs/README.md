# Tingle(팅글) 설계 문서

수정 2026-10-21 11:47 KST. 최신 설계는24개 테이블·162개 컬럼이다. 노션과 Jira 최신화 내용을 저장소 문서에 맞췄으며, 커밋·서버/DB 적용·팀 승인·실제 연동/배포 완료는 별도다.

- [API 명세](contracts/api/README.md) / [OpenAPI](contracts/api/openapi.json) / [API 기존안·수정안](contracts/api/changes-20261021.md)
- [ERD·컬럼 설명](database/erd.md) / [SQL](database/schema.sql) / [ERDCloud 가져오기](database/erdcloud-import.sql) / [ERD 변경 비교](database/changes-20261021.md)
- [설문·계산 버전 파일 계약](contracts/survey/versioning.md) / [100문항 catalogue](contracts/survey/questionnaire-SIS-Q-1.1-100.json)
- [앱 인증 확인 사항](contracts/api/auth-recheck.md) / [24기능 대응표](specifications/coverage.md)
- [기능 명세 원문](specifications/functional-spec-v1.md) / [설문·계산 원문](survey-analysis/README.md) / [가입·매칭 정책 원문](contracts/matching/profile-policy-v1.md)

마지막 세 원문은 다른 작업의 변경을 보존했으며 이번 ERD 동기화로 덮어쓰지 않았다. 현재 변경 차이는 API/ERD 변경 비교와 확인 사항을 먼저 참고한다.
