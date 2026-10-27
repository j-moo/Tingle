# Tingle(팅글) 설계 문서

**기능 명세 갱신(2026-10-27):** [명세 안내](specifications/README.md)에서 현재 MVP와 v3 상세 명세를 확인한다. 아래 API/ERD 및 검증 기록의 날짜는 각 작성 당시 기준이다. 개인 계약 검토안·정책·MVP ERD는 저장소 밖으로 분리했으며, 다른 파트가 develop에 반영한 공식 API/IoT 계약과 통합 DB 자료는 유지한다.

수정 2026-10-22. 현재 통합 작업본은 **25개 테이블·185개 컬럼**이다. 기존 25/184에 최신 develop의 키링 공개 UUID를 포함했다. 저장소 문서 정적 검증과 웹 저장·서버 구현·공용 DB 적용은 [현재 검증](verification/api-erd-unified-20261022.md)에서 구분한다. 이전 검증은 [MR26 후속 기록](verification/mr26-alignment-20261022.md)과 [정합화 이력](verification/spec-alignment-20261022.md)을 따른다.

- [정합화 기준·검증 이력·다음 변경 대조 절차](verification/spec-alignment-20261022.md) / [사용자 승인 기준](contracts/api/alignment-20261021.md)
- [API 명세](contracts/api/README.md) / [OpenAPI](contracts/api/openapi.json) / [API 기존안·수정안](contracts/api/changes-20261021.md)
- [ERD·컬럼 설명](database/erd.md) / [SQL](database/schema.sql) / [ERDCloud 가져오기](database/erdcloud-import.sql) / [ERD 변경 비교](database/changes-20261021.md)
- [설문·계산 버전 파일 계약](contracts/survey/versioning.md) / [100문항 catalogue](contracts/survey/questionnaire-SIS-Q-1.1-100.json)
- [앱 인증 확인 사항](contracts/api/auth-recheck.md) / [24기능 대응표](specifications/coverage.md)
- [기능 명세 원문](specifications/functional-spec-v3.md) / [설문·계산 원문](survey-analysis/README.md) / [개인 관리 정책 출처](specifications/sources.md#personal-05)

기능 명세는 최신 develop 원문과 동일하게 동기화했으며 내용 자체를 수정하지 않았다. 설문 원문과 과거 핸드오프도 보존한다. 프로필 정책의 승인된 정합화 설명은 별도로 갱신했다. 기능 명세가 바뀌면 날짜가 붙은 검증 이력을 과거 기준으로 보존하고, 최신 웹 원본과 현재 저장소를 다시 대조한다.

과거 기록(2026-10-21 14:16 KST): 인증 담당 연락으로 약관 동의·가입 분리·새 토큰 응답을 정리했다. 당시 AUTH-OPEN-01~06 조사와 현재 적용 상태의 차이는 [앱 인증 정합화 상태](contracts/api/auth-recheck.md)를 참고한다.

2026-10-22 통합 작업본: [설문 회차 계약](contracts/api/survey-sessions.md) 및 [통합 검증](verification/api-erd-unified-20261022.md)을 확인한다. 기존 25/184에 최신 develop 키링 공개 UUID를 보존해 25/185다. 웹 저장·서버 구현·공용 DB 적용은 검증 기록에서 구분한다.
