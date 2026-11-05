# 설문·계산·분석 버전 파일 계약

2026-10-28 / 21개 테이블 기준 문서 정합화. 문항 100개와 계산·기획·정책 원문은 변경하지 않는다. 이 문서는 파일 선택과 저장 위치를 설명하며 모든 규칙 파일의 구현·배포 완료를 뜻하지 않는다.

[문항 catalogue](questionnaire-SIS-Q-1.1-100.json) / [설문 원문](../../survey-analysis/personality-survey-v1.md) / [계산 원문](../../survey-analysis/personality-scoring-v1.md) / [설문 API 계약](../api/survey-sessions.md) / [OpenAPI](../api/openapi.json) / [ERD](../../database/erd.md)

## 불변 파일과 저장 위치

1. 같은 버전의 문항·보기·표현·계산 규칙·이관 규칙을 덮어쓰지 않는다. 변경은 새 파일 버전으로 구분한다. 현재 파일과 기존 회차/결과 재현에 필요한 과거 파일을 배포·보존해야 한다.
2. 설문 정의는 `survey/{버전}.json`, 분석 규칙은 `analysis/{버전}.json`에 있다. 현재 문항 catalogue 하나만으로 모든 계산·분석·정책 파일이 완성됐다고 보지 않는다. 파일의 실제 패키징·레지스트리·배포 위치는 구현 검증 대상이다.
3. 회차는 `survey(id, user_id, survey_version, completed_at)`에 저장하고 답은 `survey_answer(survey_id, question_id, answer_value, answered_at)`로 문항별 분리한다. `sessionId`는 `survey.id` 숫자다. 문항·보기 정의와 metadata/revision을 DB의 JSON 사본으로 저장하는 계약을 사용하지 않는다.
4. `surveyVersion`은 1~30자로 회차 시작 때 고정한다. 같은 회원·버전의 미완료 회차는 재사용하는 현재 문서 설계를 따르며 완료 후 원응답은 불변이다. 다른 게시 버전을 과거 회차에 자동 적용하지 않는다.
5. 개인 분석은 완료 회차와 `analysis_version`(1~30자)으로 식별하고 `personality(survey_id, analysis_version, status, result)`에 저장한다. `resultId`는 `personality.id` 숫자다. 점수·유형·원응답 해석·자기성향/선호 차이·참고 추천을 결과 안에서도 구분한다.
6. 실제 두 사람 매칭은 개인16유형의 참고 추천과 별개이며 `matching`의 판정·정책 버전과 당시 최소 근거를 따른다. 과거 `settings_snapshots` 테이블이 현재 존재한다고 가정하지 않는다. 만남 세대와 당시 입력 근거 보존은 담당 매칭 계약의 합의 항목이다.

## 공개 버전 DTO

| DTO | 필드 | 저장·선택 |
| --- | --- | --- |
| SurveyManifest | `surveyVersion`, `wordingVersion`, `contentHash` | 첫 필드는 `survey.survey_version`, 나머지는 설문 파일에서 읽거나 계산 |
| AnalysisManifest | `surveyVersion`, `analysisVersion` | `survey.survey_version`, `personality.analysis_version` |
| 공유 Manifest | 기존 전체 정의 유지 | 다른 내부/도메인 호출자의 별도 계약. 위 설문 DTO로 축소하지 않음 |

`wordingVersion`은 1~100자, `contentHash`는 파일 내용의 SHA-256 소문자 16진수 64자리다. 문서에서 0으로 채운 해시는 형식 예시이므로 실제 파일 검증값으로 복사하지 않는다. 문항 ID는 1~50자이고 저장할 답을 문자열로 표현했을 때 최대 100자다. 허용 값·척도·unknown/any는 고정된 문항 파일로 검사한다.

## 이관과 분석 상태

새 버전 이관은 명시적 파일 규칙과 사용자 확인을 거쳐 보존 가능한 문항만 새 `survey`·`survey_answer`로 복사한다. 원본 회차·분석은 유지한다. `targetSurveyVersion`과 파일 내용으로 계산한 `policyHash`를 미리보기·실제 이관에서 함께 검사하며 임의 자동 문항 매핑을 하지 않는다. 같은 버전 또는 미지원 규칙은 `MIGRATION_UNSUPPORTED`, 해시 변경은 `MIGRATION_POLICY_CHANGED`다. 기존 동일 버전 미완료 회차 재사용 시 답 복사 여부는 아직 합의 전이다.

분석 접수는 공용 `jobId` 대신 `resultId`와 현재 상태를 반환한다. 상태는 `personality.status`의 PENDING/SUCCEEDED/INSUFFICIENT/FAILED이며 내부 재시도 정보는 `personality.result`에 둔다. 이전 유효 분석은 재검사 대기·실패·정보 부족 중 유지한다. 최신 유효 SUCCEEDED 결과 확인 후 결과 저장·프로필 적용 포인터·설정 버전 갱신을 원자 처리해야 한다.

5분 정체의 기산점, 최초 실행을 포함한 attempts 의미·최대 횟수, 입력/실행 세대·중복 선점·늦은 결과 차단은 AI·BE 합의가 필요하다. 존재하지 않는 background_job나 새 DB 칸을 이미 있는 저장소로 쓰지 않는다.

## 파일 검증과 검토

문항 ID·선택 타입/범위·중복·unknown/any·참조·대표값·결측·동점·점수/coverage 경계·유형/참고 추천 분리·입출력 사례를 버전별로 검증한다. 파일 내용으로 임의 코드·SQL을 실행하거나 임의 파일·네트워크 접근을 허용하지 않는다. 파일 운영도 코드 검토와 재현 가능한 회귀 사례가 필요하다.

개인 점수, 개인16유형, 원응답 해석, 선호/자기성향 차이, 참고 추천 유형, 실제 양방향 매칭을 구분한다. 생활조건·Q97 등 제품 정책의 미정 항목을 이 문서에서 임의 확정하지 않는다.

## 이전 설계 이력과 제외 범위

이전 공개 UUID·revision/ETag 설계와 MR !57의 정적 확인은 당시 구현 이력이다. 현재 숫자 ID·문항별 답 저장·상태 조회로 코드나 기존 데이터 이관이 완료됐다는 증거가 아니다. 자세한 과거 확인 커밋은 [설문 API 계약](../api/survey-sessions.md)의 구현 증거 절을 따른다.

2026-11-04(S15P31E106-57~60): 관리자 초안·검증·승인·게시·current 전환/롤백 API를 `/api/admin/v1/...` 경로로 다시 구현했다. 저장은 rule_artifact·rule_release·rule_channel·rule_audit 4표다. 서버가 검사·사용하는 형식은 설문(QUESTIONNAIRE/questionnaire-1)과 이관 규칙(MIGRATION/migration-1)이며 나머지 종류는 형식 합의 전 422다. 파일 버전은 그대로 쓰고, 게시한 설문·이관 규칙은 파일처럼 버전 이름으로 찾는다(파일 우선). current 채널이 비어 있으면 새 회차는 app.survey.current-version 파일 버전을 쓴다. 이미 시작한 회차는 시작 때 버전을 유지한다. 계산·분석 규칙(analysis_version)은 여전히 서버 설정·AI 서버 파일 기준이다.
