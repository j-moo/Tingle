# 개인분석 API — 현재 동작과 검토 이력

기준: 2026-11-16, [MR !369 머지 `dc7fc401`](https://lab.ssafy.com/s15-final/S15P31E106/-/commit/dc7fc4016319844373961f9d8b0a61b10b4a66f2).
개인분석은 **local/test/dev/prod 모두 같은 v2 문구 정책**으로 동작한다.
이 문서는 저장소 동작을 설명하며 실제 서버 배포·운영 가입 완료를 증명하지 않는다.

## 현재 앱 조립과 실행 조건

[main.py](../main.py)의 `create_app()`이 환경과 관계없이
`PersonalAnalysisAdapter(presentation=proposed_label_only_policy())`와 라우터를 등록한다.
내부 경로는 `POST /internal/ai/personal-analyses`다.

| 항목 | 현재 동작 |
| --- | --- |
| 문구 정책 | [proposals.py](proposals.py)의 동일 v2 정책. 검토 식별자는 `mr357-wording-review-20261115-v2` |
| 환경 차이 | JWT issuer/audience 등 인증 환경과 주소·비밀키 설정. 개인분석 기능 선택에는 환경 분기가 없음 |
| 정상 분석 | 인증·입력 검증 후 HTTP 200, `SUCCEEDED` 또는 `INSUFFICIENT` |
| 승인 파일·pin·표시 정책 digest | 요구하지 않음. `production.py`와 별도 승인 등록 구조는 삭제됨 |
| 개인분석 기능 스위치 | `PERSONAL_ANALYSIS_PROD_ENABLED`, `PERSONAL_ANALYSIS_POLICY_APPROVAL_FILE`, `PERSONAL_ANALYSIS_POLICY_APPROVAL_SHA256`는 삭제된 설정이며 현재 요구사항이 아님 |
| 참조 데이터 누락·변조 | 모든 환경에서 앱 기동 실패. 요청 시 버전·참조 해시·근거 검증도 유지 |
| 인증 설정 | 기존 서비스 JWT 검증 유지. 잘못 명시한 키는 기동 실패, 인증 미설정·검증 장애는 기존 503 등으로 거절 |

별도 승인 상태 때문에 정상 prod 요청을 404/503으로 막는 경로는 없다.
문구 검토는 팀 리뷰로 관리하며 이 JSON이나 환경변수가 운영 승인을 발급하지 않는다.
독립 `PersonalAnalysisAdapter()`의 빈 기본 표시 설정과 앱의 명시적 정책 주입은 구분한다.
개인분석에 GMS 호출이나 tokenizer를 추가하지 않았다.

## 요청·응답과 검증 경계

[dto.py](dto.py), [adapter.py](adapter.py), [router.py](router.py)가 현재 계약의 구현이다.

- 요청은 `analysisId`, `surveyVersion`, `analysisVersion`, `answers`이며 추가 필드는 허용하지 않는다.
- `analysisId`는 양의 정수이며 BE 실행 추적용이다. 계산 결과에 주입하지 않는다.
- Q001~Q100 전체를 중복·누락 없이 보내고 문항별 catalogue 값으로 검증한다.
- 버전 조합은 `SIS-Q-1.1-100` / `SIS-A-1.0`, 계산 버전은 `local-personal-js100-port-1`이다.
- 참조 파일·provenance·고정 데이터 해시는 그대로 검증한다. 이를 삭제한 표시 정책 승인 pin과 혼동하지 않는다.
- AI 응답은 status, 버전, scores, typeStatus, primaryType, tiedTypes, readings,
  selfPreferenceDifferences, typeRecommendations, missingQuestionIds를 보존한다.
  resultId/sessionId/저장·조회와 PENDING/FAILED 관리는 BE 책임이다.
- INSUFFICIENT도 AI 내부 점수·설명·차이·추천·누락 문항을 보존한다. BE 공개 필터를 AI에 추가하지 않는다.

근거는 실제 계산 참여 문항과 소비한 Q096/Q098 조건을 사용한다.
unknown/any를 측정 근거로 만들지 않으며 기존 순서·추천 모드·선정 값은 바꾸지 않는다.
문구·근거의 상세 범위는 [v2 문구 설명](WORDING_REVIEW_V2.md)을 따른다.

| HTTP | code / 조건 |
| --- | --- |
| 200 | SUCCEEDED / INSUFFICIENT |
| 400 | INVALID_REQUEST: 본문 16 KiB 초과 등 기존 요청 경계 |
| 422 | INVALID_REQUEST: DTO·100문항·catalogue 검증 오류 |
| 409 | ANALYSIS_VERSION_UNSUPPORTED: 허용하지 않는 버전 |
| 401/403 | 기존 서비스 JWT·scope 검증 실패 |
| 500 | INTERNAL_ERROR: 계산 실패·formatter 근거 불일치 등. 정보 부족이나 원문 fallback으로 바꾸지 않음 |
| 503 | SERVICE_UNAVAILABLE: 인증 미설정/장애, 요청 시 참조·표시 변환 계약 오류 등 기존 서비스 불가 조건. 문구 승인 게이트가 아님 |

인증을 본문 파싱·계산보다 먼저 수행한다. X-Request-Id와 Cache-Control: no-store를 유지한다.
오류 진단은 requestId·templateId·고정 errorType만 남기고 답변·JWT·키·예외 원문은 기록하지 않는다.
공통 인증 규칙은 [보안 모듈 설명](../security/README.md)을 참조한다.

## 합성 자료·검증

[합성 fixture](../../tests/fixtures/personal-analysis-api)는 정상/정보부족 요청·응답과 과거 검토 manifest다.
fixture/생성기의 `UNAPPROVED` 표기는 생성 당시 검토 자료의 상태이며 현재 공통 정책을 막는 설정이 아니다.
이 문서 정리는 fixture·생성기·API 응답을 변경하지 않는다.

[presentation-review.json](presentation-review.json)은 현재 동작 설명과 과거 v1 검토 inventory를 구분한다.
`currentRuntime`은 !369 이후 설명, `historicalReview`는 당시 원문 snapshot이다.
과거 UNAPPROVED·prod OFF/ON·INSUFFICIENT 원문 fallback 설명은 역사적 자료에만 속한다.
파일은 이미지에 포함되지만 런타임은 이를 읽어 정책을 선택하거나 승인을 검증하지 않는다.

기존 테스트 의존성 환경의 `ai-server` 디렉터리에서 확인한다.

```powershell
python -B -m pytest -q tests/test_personal_analysis_environment_parity.py tests/test_personal_analysis_production.py tests/test_service_auth_startup.py tests/test_personal_analysis_import_isolation.py tests/test_personal_analysis_wording_v2.py tests/test_personal_analysis_api_contract.py tests/test_personal_analysis_documentation.py
docker build -t personal-analysis-docs:review .
python -B -m scripts.check_personal_runtime --image personal-analysis-docs:review
git diff --check
```

Docker 검증은 기존 CMD `python -m app.main`을 dev/prod에서 사용해 합성 응답 동등성을 확인한다.
인증·본문 크기·버전 거절과 JSON 패키징 검사도 유지한다. 테스트 명령은 승인이나 배포 명령이 아니다.
이번 후속 작업의 실제 실행 결과·미실행 항목은 별도 결과 보고서로 관리한다.

## 과거 기록 — 현재 요구사항이 아님

| 시기 | 당시 상태와 현재의 차이 |
| --- | --- |
| 2026-10~11 초 독립 adapter 작업 | prod 라우터 미등록/404, 일부 환경만 연결하던 시기의 기록 |
| 2026-11-15 !357·v1/v2 검토 | 문구 UNAPPROVED와 prod OFF/ON 차단을 설명하던 시기. 당시 실행 수치는 현재 재검증 결과가 아님 |
| 2026-11-16 !369 | 승인 구조·환경 분기 제거, 모든 환경에 같은 v2 정책 적용. [가을님 확인](https://lab.ssafy.com/s15-final/S15P31E106/-/merge_requests/369#note_3081464) |

세부 과거 기록은 [정리 전 README 고정본](https://lab.ssafy.com/s15-final/S15P31E106/-/blob/dc7fc4016319844373961f9d8b0a61b10b4a66f2/ai-server/app/personal_analysis_api/README.md)과
[현재/과거 검증 구분](PRODUCTION_POLICY_VERIFICATION.md)에서 확인한다.
머지·새 코드 배포는 이미 저장된 BE 분석 text를 자동 교체하거나 과거 FAILED를 재처리하지 않는다.
