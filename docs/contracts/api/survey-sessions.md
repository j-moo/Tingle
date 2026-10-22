# 설문 회차 변경 계약과 합의 항목

2026-10-22 / 담당: 가을(설문 구현), 김영빈(통합 API·ERD 정합화). 관련 Story: S15P31E106-61, -63, -64, -65, -91, -92. 영향: FE·BE·AI. 사용자 전달 요청 1~3을 반영한 변경안이며 서버 구현·팀 승인·배포 완료가 아니다.

최초 작성 기준은 develop `fbe0f352d0a013b8aaebf0623c3e9f6a0d5080c8`이며 당시에는 설문 구현을 확인하지 못했다. 2026-10-22 후속 통합에서 develop `e59cbfb12bc6ef780b038d44ec42dadc856c7f09`와 [MR !57](https://lab.ssafy.com/s15-final/S15P31E106/-/merge_requests/57)의 `629ed04a5cd6ef70f62f79b2050e60bf8d5b6a2d` 소스를 확인했다. `surveyVersion` 요청, 세 필드 Manifest, `public_id` UUID 생성·NOT NULL·UNIQUE·DB 기본값 선언은 정적으로 대조했다. 해당 MR의 구현은 설문 조회·회차 시작·회차 조회·회차 문항 조회 4개 경로이며 이 브랜치에 설문 코드를 합친 것은 아니다. 나머지 저장·완료·분석·이관 및 실제 설문 런타임·기존 행 이관은 미검증이다.

[OpenAPI](openapi.json) / [설문 버전 계약](../survey/versioning.md) / [ERD](../../database/erd.md) / [SQL](../../database/schema.sql) / [검증·작업 상태](../../verification/survey-session-61.md)

## 반영한 변경

| 항목 | 변경 계약 |
| --- | --- |
| CreateSession | `releaseId` UUID 대신 필수 `surveyVersion` 문자열, 길이 1~100. 예: `SIS-Q-1.1-100`. 생략·null·빈 문자열·이전 releaseId 필드는 스키마에서 거절 |
| 버전 고정 | DB release 행 없이 배포된 불변 설문 파일을 선택하고 `survey_responses.version_code`에 저장. 허용 구버전 범위와 미지원 버전의 정확한 오류 응답은 구현 확인 필요 |
| 설문 manifest | `Questionnaire`·`SurveySession`만 별도 `SurveyManifest` 사용. 필수값은 `surveyVersion`, `wordingVersion`, `contentHash` |
| 이후 분석 manifest | 기존 공유 `Manifest`의 11개 필수값 유지. 규칙 파일이 생긴 뒤 분석에 필요한 버전 조합·해시 범위·releaseId 전환을 합의. 설문용 세 필드로 분석 준비가 끝났다고 보지 않음 |
| 공개 회차 ID | `survey_responses.public_id uuid NOT NULL DEFAULT gen_random_uuid()` 및 UNIQUE. API `sessionId`는 이 값. 내부 `response_id bigint` PK와 분석의 내부 참조는 유지 |
| 답변 저장 | 저장 컬럼은 `response_json`, 내부에 answers·metadata 보존. 문항별 조건부 갱신·revision·완료 후 불변성 유지 |

요청 예시는 `{"surveyVersion":"SIS-Q-1.1-100"}`이다. `Idempotency-Key` 헤더 계약은 유지한다. MR !57은 같은 회원·버전의 미완료 회차를 재사용하고 새 회차는 코드에서 UUID를 생성하며, 재사용을 포함한 시작 응답을 201로 반환한다. 회차 조회·시작 응답의 ETag는 revision이다. 이는 소스 확인이며 이 브랜치의 런타임 검증으로 확대하지 않는다. 명세의 100자 상한에 대응하는 `@Size(max=100)` 선언은 MR !57 DTO에 없으므로 작성자 확인 항목으로 남긴다. 상한을 임의 삭제하거나 다른 담당자의 코드를 수정하지 않았다.

UUID를 알아도 권한이 생기지 않는다. 공개 ID 조회 시 인증 회원의 `user_id`를 검사하고, 답변·완료·이관·분석 경로도 같은 공개 ID 매핑을 사용한다. 새 회차는 새 public_id를 가지며 내부 response_id와 기존 참조를 바꾸지 않는다.

## 분석·이관의 미확정 버전 계약

| ID | 남아 있는 계약 | 합의할 내용 |
| --- | --- | --- |
| SURVEY-OPEN-01 | `AnalysisRequest.releaseId` UUID 필수 | 분석 규칙 파일의 호환 버전 묶음과 `analysis_results.version_code`의 의미. surveyVersion으로 단순 치환하면 채점·유형·분석 버전 재현이 빠질 수 있음 |
| SURVEY-OPEN-02 | migration-plan 쿼리, `MigrateSession` 요청, `MigrationPlan` 응답의 `targetReleaseId` UUID | 대상 surveyVersion 및 이관 정책 파일 선택·policyHash 범위·구버전 지원. 원본 보존과 새 회차 생성은 유지하되 필드 전환/자동 매핑은 합의 전 |
| SURVEY-OPEN-03 | API/DB 작업 종류·상태·공개 ID 불일치 | 아래 권장안 검토 후 -64/-65 구현 전에 확정 |

이 필드들은 이전 계약으로 남기고 OpenAPI에 보류 표시했다. 존재하지 않는 release DB 조회, UUID→surveyVersion 변환, 구체적 실패 응답을 임의로 구현하거나 확정하지 않았다. 기존의 관리자 게시 API 보류 상태도 유지한다.

## 작업 상태 권장안 — 미합의, 이번에 변경하지 않음

진행·실패·재시도는 `background_jobs`, 계산이 끝난 결과는 `analysis_results`에 두는 안을 권장한다. 작업의 재실행과 결과의 의미를 분리해 실패 시 불완전한 결과 행을 만들지 않고, 기존 attempts·lease·business_key를 재사용할 수 있다. `INSUFFICIENT`는 계산 실패가 아니라 정보 부족이라는 정상 결과다.

현재 SQL의 `job_type` 허용 목록에는 ANALYSIS가 없다. `analysis_results.outcome`은 이미 SUCCEEDED/INSUFFICIENT만 허용하지만 OpenAPI `AnalysisResult.status`에는 FAILED가 있다. `Job.status`는 INSUFFICIENT가 있고 CANCELLED가 없으며 DB 작업 상태는 그 반대다. 따라서 ANALYSIS 하나만 enum에 추가해서 끝낼 수 없다.

권장 상태 대응은 PENDING/RUNNING은 작업에만 저장, 최종 실패는 jobs.FAILED와 비민감 last_error_code, 계산 종료는 jobs.SUCCEEDED와 결과 ID, 결과 outcome은 SUCCEEDED 또는 INSUFFICIENT다. 정보 부족 결과의 작업은 실행 자체가 성공했으므로 jobs.SUCCEEDED로 두는 안이다. API Job도 같은 실행 상태를 쓰고 결과 조회로 정보 부족을 확인하는 안을 함께 검토한다. 이는 확정 계약이 아니다.

합의 시 검토할 변경은 ANALYSIS job_type 허용, AnalysisResult의 FAILED 제거, Job의 INSUFFICIENT/CANCELLED 대응, 재시도 가능 여부·최종 실패 경계, result_json의 결과 포인터, 결과와 작업 성공의 원자 저장, 중복 업무 키 구성, 늦은 워커 fencing, 취소·탈퇴·보관 처리다. 현재 jobId/resultId UUID와 DB bigint의 공개 ID 매핑도 미정이며 sessionId 확정으로 함께 해결됐다고 보지 않는다. 개인 계산 ANALYSIS와 후속 AI 생성 작업의 관계도 구분해야 한다. 이번 작업은 해당 enum·DDL·코드를 바꾸지 않았다.

## JPA와 기존 행 확인 사항

MR !57의 SurveyResponse는 response_id의 IDENTITY를 유지하고 생성 시 `UUID.randomUUID()`로 publicId를 채운다. `@Column(nullable=false, unique=true, columnDefinition="uuid default gen_random_uuid()")` 선언도 확인했다. 코드 생성과 DB 기본값은 별도 방어선이다. 이 소스 확인으로 실제 JPA 생성 DDL 실행이나 기존 행 보정이 검증됐다고 보지 않는다. 현재 컨벤션 [db-schema]의 nullable·unique 선언과 코드에서 생성값을 채우는 원칙을 따른다.

`@ColumnDefault("gen_random_uuid()")`는 DDL 기본값 선언이다. 명시적 NULL INSERT를 자동으로 생성값으로 바꾸는 보장은 없으므로 코드 생성과 DB 기본값을 혼동하지 않는다. DB 생성 방식을 고르면 INSERT에서 컬럼 생략과 생성값 readback까지 검증한다. [Hibernate 공식 설명](https://docs.hibernate.org/orm/7.4/javadocs/org/hibernate/annotations/ColumnDefault.html)을 참고하되 프로젝트에서 실제 선택한 Hibernate 버전으로 확인한다.

기존 행이 있는 DB에는 빈 DB용 schema.sql을 실행하지 않는다. 담당자 검토 후 실제 행 수·NULL·중복과 기존 외부 ID 사용 여부를 확인하고, nullable 추가/기존 행별 UUID 부여/NOT NULL·UNIQUE·DEFAULT 설정/검증 순서를 준비한다. 기존에 외부로 발급한 ID가 있다면 호환 매핑부터 결정한다. JPA update만으로 데이터 보정·제약 적용이 끝났다고 보지 않는다. 이번 작업에서 실행 마이그레이션 파일·Flyway를 추가하거나 공용 DB에 접속·적용하지 않았다.

## 호환성과 적용 순서

CreateSession의 releaseId 요청은 호환되지 않는 변경이다. FE가 surveyVersion을 보내고 세 필드 설문 manifest를 읽도록 준비한 뒤 서버 DTO·파일 선택·UUID 생성/소유권을 함께 검증해야 한다. 과거 파일 보존과 기존 행 처리 확인 → JPA 제약·DDL 검증 → FE/BE 연결 검증 순으로 적용한다. 분석·이관은 위 미확정 항목을 별도로 합의한다. 이번에 MR 리뷰어 지정·리뷰 요청·커밋·푸시·병합·메시지 전송은 하지 않았다.

## 전달 초안 — 미전송

가을님, 61번 요청의 surveyVersion·설문용 최소 manifest·sessionId=public_id를 통합 API/ERD에 반영했고 MR !57의 핵심 DTO·UUID 생성/제약 선언도 확인했습니다. 시작 요청의 명세상100자 상한과 DTO 검증 선언 차이는 확인 부탁드립니다. 설문 런타임·기존 행 보정은 이번 검증에 포함하지 않았습니다. -64/-65 전에 ANALYSIS 작업 종류와 작업/결과 상태, 분석 releaseId·이관 targetReleaseId의 파일 버전 대응을 합의하면 좋겠습니다. 진행·실패는 background_jobs, 결과는 SUCCEEDED/INSUFFICIENT만 analysis_results에 두는 안을 제안합니다.
