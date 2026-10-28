# 21개 테이블 기준 설문·분석 API 계약

2026-10-28 / 설문 구현 가을, 통합 계약 정합화 김영빈. 관련 Story S15P31E106-61·62·63·64·65·91·92, 영향 FE·BE·AI. 사용자 제공 최신 21개 테이블 DBML과 기존 노션 명세의 현재 저장본을 대조한 문서 변경이다. 팀 승인, 서버 구현, 실제 데이터 이관 또는 배포 완료를 뜻하지 않는다.

[OpenAPI](openapi.json) / [버전 파일 계약](../survey/versioning.md) / [ERD](../../database/erd.md) / [검증 기록](../../verification/notion-survey-rest-20261028.md) / [통합 검증 결과](../../verification/owned-api-erd-20261028.json)

## 현재 경로와 전송 계약

모든 경로는 `/api`를 사용한다. 본인은 Bearer 인증 주체로 판별하고 경로의 회차·결과 번호와 소유 회원을 별도 검사한다. 남의 회차·결과와 없는 자원은 같은 `404 RESOURCE_NOT_FOUND`로 응답한다. `sessionId = survey.id`, `resultId = personality.id`이며 양의 `integer(int64)` 숫자다. 공개 UUID 매핑 칸은 없다. X-Request-Id 등 본래 UUID인 추적·요청 식별자와 구분한다.

| 메서드·경로 | 요청·정상 응답 | 저장·계산 근거 |
| --- | --- | --- |
| GET `/api/surveys/current` | 200 Questionnaire | 현재 `survey/{버전}.json`. 이 조회는 DB에 쓰지 않음 |
| POST `/api/survey-sessions` | `{surveyVersion}` → 201 SurveySession | `survey(user_id, survey_version, completed_at)` 생성 또는 같은 회원·버전 미완료 회차 재사용 |
| GET `/api/survey-sessions/{sessionId}` | 200 SurveySession | 본인 `survey`·`survey_answer`와 고정 설문 파일 |
| GET `/api/survey-sessions/{sessionId}/questionnaire` | 200 Questionnaire | 본인 회차의 `survey.survey_version`에 고정된 파일 |
| PUT `/api/survey-sessions/{sessionId}/answers/{questionId}` | `{value}` → 200 SurveySession | 미완료 회차의 `survey_answer` 문항별 저장·갱신 |
| DELETE 같은 문항 경로 | 본문 없음 → 200 SurveySession | 해당 답 행 삭제. 이미 없으면 현재 상태를 그대로 반환 |
| POST `/api/survey-sessions/{sessionId}/complete` | 본문 없음 → 200 SurveySession | 전체 문항 검사 후 `survey.completed_at` 최초 기록 |
| GET `/api/survey-sessions/{sessionId}/migration-plan` | 필수 `targetSurveyVersion` 쿼리 → 200 MigrationPlan | 원본 답과 서버 이관 규칙 파일 비교. DB 쓰기 없음 |
| POST `/api/survey-sessions/{sessionId}/migrations` | `{targetSurveyVersion, policyHash}` → 201 SurveySession | 원본을 보존하고 새 회차에 보존 가능한 답만 복사 |
| POST `/api/survey-sessions/{sessionId}/analyses` | 본문 없음 → 202 AnalysisReceipt | 서버 분석 파일 선택 후 `personality` 생성·재사용 |
| GET `/api/analysis-results/{resultId}` | 200 AnalysisResult | `personality.survey_id → survey.user_id` 본인 검사 후 상태·공개 결과 |

현재 노션의 설문 계약은 `Idempotency-Key`, `If-Match`, `ETag`, `revision`, `sessionRevision`, `releaseId`를 요구하지 않는다. 분석 접수는 `jobId` 대신 `resultId`를 돌려준다. 아래 제외·합의 항목과 과거 구현 이력을 현재 활성 계약으로 혼동하지 않는다.

## 설문 회차·답변·완료

- `surveyVersion`은 필수 문자열 1~30자이며 `survey.survey_version`에 고정한다. 시작 뒤 현재 게시 버전이 바뀌어도 기존 회차의 문항·보기는 바뀌지 않는다.
- `SurveyManifest`는 `surveyVersion`, `wordingVersion`, `contentHash` 세 필드다. DB에는 첫 필드만 저장하고 나머지는 버전 파일에서 읽거나 계산한다. `wordingVersion`은 1~100자, `contentHash`는 소문자 16진수 64자리다. 예시의 0 해시는 실제 파일 해시가 아니다.
- `questionId`는 1~50자다. 답은 해당 버전 파일의 보기 ID 또는 점수 하나(Number 또는 String)로 검증하고 `answer_value varchar(100)`에 문자열로 저장한다. 배열·객체·null은 받지 않는다. `unknown`과 `any`의 허용 여부는 문항 파일을 따른다. 삭제는 미응답이며 `unknown` 응답과 다르다.
- `(survey_id, question_id)`의 같은 문항을 다시 저장하면 `answer_value`와 `answered_at`을 갱신한다. 현재 노션은 같은 문항에서 나중에 처리한 요청이 남는 설계를 사용한다. 실제 경합 처리와 완료/저장 동시성은 구현 검증 대상이다.
- `status`, `answeredCount`, `questionCount`, `nextQuestionId`는 별도 DB 칸이 아니다. 각각 `completed_at`, 답 행 수, 고정 파일의 문항 수와 순서로 계산한다.
- 완료에는 모든 문항의 유효한 답이 필요하다. `unknown`도 답으로 센다. 완료 요청을 반복해도 최초 `completed_at`을 바꾸지 않는다. 완료 뒤 답 저장·삭제는 `409 SESSION_COMPLETED`이며 재응답은 새 회차다. 제출 완료와 분석 성공은 별개다.
- 현재/회차 문항 정의의 노션 예시는 전체 100문항 중 Q001만, 완료 응답은 100개 답 중 Q001·Q002·Q100만 발췌한 것이다. 기계 판독용 완료 예시는 100개 전체를 담아 답변 수와 배열 길이를 일치시킨다.

## 원본 보존 이관

미리보기는 `sourceSessionId`(숫자), `targetSurveyVersion`(1~30자), 보존·재확인·비호환 문항 ID 목록과 `policyHash`를 반환한다. 규칙은 서버 파일에 있고 `policyHash`도 파일에서 계산하므로 DB에 release나 정책 해시 행을 꾸며 넣지 않는다. 지원되지 않는 두 버전 또는 같은 버전은 `409 MIGRATION_UNSUPPORTED`다. 이전 80문항을 임의로 100문항에 자동 매핑하지 않는다.

실제 이관은 현재 규칙 해시와 요청 `policyHash`를 다시 비교한다. 다르면 `409 MIGRATION_POLICY_CHANGED`이며 미리보기를 다시 받아야 한다. 새 `survey`와 보존 가능한 `survey_answer`만 생성하고 원본 회차·답·`personality`는 바꾸지 않는다. 재확인·비호환 문항은 미응답으로 둔다.

**합의 필요:** 같은 회원·대상 버전의 미완료 회차를 재사용할 때 원본 답을 복사할지, 기존 답을 그대로 돌려줄지는 현재 노션에서도 미정이다. 이 정책을 임의 확정하거나 기존 답을 덮어쓰는 구현 보장으로 쓰지 않는다.

## 개인 분석과 유효 결과 적용

요청 본문은 없으며 서버가 해당 `survey_version`을 지원하는 `analysis/{버전}.json`을 선택한다. 미완료 회차는 `409 SESSION_NOT_COMPLETED`, 지원 규칙이 없으면 `409 ANALYSIS_VERSION_UNSUPPORTED`다. `(survey_id, analysis_version)` 조합의 `personality`를 생성 또는 재사용하고 둘 다 202로 현재 상태를 반환한다.

`AnalysisReceipt`는 `resultId`, `sessionId`, `analysisVersion`(1~30자), `status`다. `AnalysisResult`는 같은 숫자 ID와 별도 `AnalysisManifest {surveyVersion, analysisVersion}`, 상태 및 공개 결과를 제공한다. 이 두 필드 DTO는 설문용 세 필드 SurveyManifest 및 다른 호출자가 쓰는 공유 Manifest 전체 정의와 구분한다.

| 상태 | 공개 결과·기존 유효 분석 |
| --- | --- |
| PENDING | 결과를 꾸며 내지 않으며 nullable 결과는 null, 목록은 빈 배열. 기존 유효 분석 유지 |
| SUCCEEDED | `personality.result`에서 공개 점수·유형·해석·참고 추천을 투영 |
| INSUFFICIENT | 부족 문항을 `missingQuestionIds`로 안내하고 새 회차 재응답으로 연결. 기존 유효 분석 유지 |
| FAILED | 실패를 유효 결과로 대체하지 않으며 재시도 가능 상태는 서버가 관리. 기존 유효 분석 유지 |

본인 회차·분석 버전·현재 유효한 최신 실행의 SUCCEEDED 결과임을 확인한 경우에만 결과 저장, `profile.active_personality_id` 전환, `user_setting.settings_version` 증가를 같은 트랜잭션에서 처리한다. 늦게 도착한 이전 실행이 새 적용 값을 되돌리지 않아야 한다. 내부 `attempts`, `nextRetryAt`, `lastErrorCode`와 원점수는 공개 응답에 내보내지 않는다.

**합의 필요:** PENDING 5분 정체 복구의 기산점, attempts의 최초 실행 포함 여부와 최대 횟수, 중복 선점·실행 세대·입력 식별, 늦은 결과 차단 기준. 기존 JSON 안에 필요한 정보를 둘지부터 정의하고 재시작·중복 실행 복구를 검증해야 한다. 이 문서가 AI 계산·모델 정책을 정한 것은 아니다.

## 제외·보류 계약

- PUT `/api/survey-sessions/{sessionId}/metadata`는 현재 노션에서 이미 제외 상태다. `survey`·`survey_answer`에 나이 metadata 칸을 만들지 않는다. 본인 나이는 `profile.birth_date`, 희망 조건은 별도 `matching_preference` 계약을 따른다.
- GET `/api/jobs/{jobId}`도 현재 노션에서 이미 제외 상태다. `background_job` 표가 없으므로 설문 분석의 진행 상태는 반환된 `resultId`로 개인 결과 조회를 호출한다. 이를 다른 도메인에 존재하지 않는 공용 작업 저장소를 보장하는 근거로 쓰지 않는다.
- 관리자 규칙/릴리스 게시 9개 페이지는 담당 가을·제외 상태의 기존 설계 이력으로 보존한다. 21개 테이블에는 해당 관리자 게시 저장소가 없다. 사용자 담당으로 확인되지 않은 페이지의 경로·속성·원문을 임의 변경하지 않는다.

## 과거 구현 증거와 현재 적용 순서

2026-10-22에는 develop `e59cbfb12bc6ef780b038d44ec42dadc856c7f09`와 MR !57 `629ed04a5cd6ef70f62f79b2050e60bf8d5b6a2d`에서 당시의 공개 UUID 생성·제약 선언, 문자열 버전과 세 필드 manifest를 정적으로 대조했다. 당시 `survey_responses.public_id`·100자 버전·revision/ETag는 과거 계약의 구현 기록이며 현재 21개 테이블의 숫자 ID 전환 또는 서버 실행 증거가 아니다.

호환되지 않는 변경은 앱 호출·DTO·서버 경로·소유권 검사·버전 파일·기존 행 이관을 함께 검토한 뒤 적용한다. 실제 DB에는 이 문서를 근거로 SQL을 실행하지 않는다. 기존 데이터가 있으면 이미 발급한 외부 ID와 변경 이력 보존을 확인하고 별도 검토된 이관 절차가 필요하다. 문서/노션 저장 검증과 코드 빌드·통합 테스트·공용 DB 적용·배포는 각각 별도 상태로 보고한다.
