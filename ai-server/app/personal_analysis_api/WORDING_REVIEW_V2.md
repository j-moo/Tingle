# 개인분석 문구 v2 미승인 수정안

작성일: 2026-11-15 (Asia/Seoul). 검토 자료 버전 `mr357-wording-review-20261115-v2`.
**정책 상태는 UNAPPROVED이며 실제 승인·운영 활성화·BE 재처리를 하지 않는다.**

현재 오류 분류는 아래 **BE 리뷰 반영** 절을 따른다. formatter 근거 불일치는 기존
`500 INTERNAL_ERROR`로 분리하고 다른 서비스 불가 조건은 503을 유지한다.
앞선 formatter 503 경로·테스트·CI 수치는 bcd2c020까지의 검증 이력으로 보존한다.

## 기준과 범위

- MR !357은 GitLab에서 Merged 상태이며 2026-11-15 14:29:44에 머지됐다.
- 초기 검토 기준은 !357 머지 커밋 `a8d5fc31af035be3e0794ab48024a47e2af96bb2`다.
  v1 source `3e981cca1b3e13b0ce3bb219ae4bd31af4b04769`는 이 커밋의 조상이고
  두 커밋 사이 개인분석 구현 차이는 없다. 코드 리뷰 승인은 문구 정책 승인이 아니다.
- 현재 MR 준비 기준은 !358 머지까지 포함한 develop
  `19eddf45f310745080160e68f01ee432a7e9122a`다. !358의 source/test/fixture 12개를 그대로 보존한다.
- 전용 작업트리 `.personal-wording-v2`에서 초기 `codex/personal-wording-v2` 브랜치를
  팀 규칙에 맞는 `ai-fix/personal-analysis-wording-v2-S15P31E106-146`으로 변경했다.
  !357·!358·다른 작업트리·Match 작업 공간에 변경을 추가하지 않는다. 최신 사용자 지시에 따라
  로컬 재검증 후 별도 commit/push/MR 생성과 가을님 리뷰 요청까지만 진행한다. 머지·배포·활성화·승인은 하지 않는다.
- 설문·core·점수/fit·유형·선정 순서/priority·DTO·인증·운영 게이트는 그대로다.
  초기 문구 v2는 12개 파일이다. BE 후속 리뷰는 라우터의 오류 분류·안전 로그를 추가로
  허용하므로 전체 MR은 기존 12개와 router.py·관련 시작 테스트의 14개 범위다. 최신 추가 변경은 아래 절에 기록한다.
  성공 결과에서 `text` 외 필드는 보존한다. 검증 실패의 fallback 제거는 별도의 오류 처리
  동작 변경이며, 기존 오류 응답 형식·코드를 재사용하더라도 실패 발생 조건은 달라진다.

## 문제별 재현과 수정 후보

| 문제 | v1 완성 문장 수 | v2 후보 |
| --- | ---: | --- |
| EXPRESSION-01 정보 부족 원문의 미측정 행동 추론 | 1 | 정상과 동일한 실제 Q030/Q032 답변 4개 재진술을 정보 부족에도 적용 |
| CONFLICT-03 보기 뒤 중복·부자연스러운 편함 표현 | 17 | 대화 시작 시점/감정이 격해질 때 필요한 방식을 각각 선택 답변으로 재진술 |
| SUPPORT의 이유 설명/사과 표현 뒤 고정 조사 오류 | 10 | 보기 뒤 목적격 조사를 붙이지 않는 답변 양식. Q045 가까운 사람과 Q044 연인을 구분 |
| 음주 선호의 허용 상한 의미 누락 | 10 | 설문에서 상대 음주 빈도의 상한으로 선택한 답변임을 명시. Q098 확장 계산과 구분 |
| EXPERIENCE의 ‘익숙한 곳’라고 / ‘새로운 곳’라고 | 8 | 준비 방식과 익숙한/새로운 장소 축을 설명한 뒤 선택 답변을 인용 |
| TIME-02의 ‘주 4회 이상’를 | 5 | 연락/만남 각각 원하는 빈도에 대한 답변을 인용 |

기존 수정 필요는 38개이며 추가 조사 오류 13개를 합쳐 51개다. 동일 고정 원문이 여러
답변에서 발생하는 경우 v1 원문+ID로 중복을 제거해 센다. v2는 그 답변별 완성 문장을
빠짐없이 기록하므로 v1과 행 수가 같아야 한다고 가정하지 않는다.

## EXPRESSION-01 안전 경로

이 절과 다음 formatter 503 경로는 bcd2c020 당시 기록이며, 최신 분류는 BE 리뷰 반영 절을 따른다.

- SUCCEEDED: 기존 네 개의 답변 재진술을 보존한다.
- INSUFFICIENT: 해당 두 문항이 실제 측정됐으면 같은 재진술을 사용한다. 정보 부족
  상태·점수 null·유형 산정·누락 문항은 바꾸지 않는다. 전체 분석이 충분하다고 주장하지 않는다.
- 검증 실패: 보기값, evidence의 개수·정수 ID·순서·실제 answer label을 확인한다.
  누락/변조/순서 오류 등에 기존 `PresentationUnavailable`을 발생시킨다. 라우터의
  기존 `503 SERVICE_UNAVAILABLE`를 사용하므로 새 DTO·오류 코드·상태 계약은 필요 없다.
  예외에 답변 원문을 담지 않으며 미승인 추론 원문 fallback을 사용하지 않는다.
- 다른 등록 formatter도 같은 evidence 검증을 사용한다. 등록하지 않은 분기의 기존
  문구는 보존한다. 등록된 formatter는 정상/정보 부족 양쪽에서 text만 바꾼다.

### 검증 실패에서 HTTP 503까지

소스 경로는 `app/personal_analysis_api/` 기준이다. 아래 파일·행은 bcd2c020 기준 기록이다.

1. `router.py:170`의 POST endpoint가 `PersonalAnalysisAdapter.analyze(payload)`를 호출한다.
2. `adapter.py:258`의 `analyze()`가 기존 요청/버전/참조 검증과 계산을 실행하고,
   `adapter.py:272`에서 실제 답변 context와 함께 `to_be_response()`를 호출한다.
3. `adapter.py:226`에서 선택된 templateId의 formatter를 찾고 `:229`에서 호출한다.
   등록된 formatter에서 예외가 나면 `row["text"]`로 재시도하는 경로가 없다.
   이 조건식의 `else row["text"]`는 **formatter가 없는 다른 템플릿**에만 적용된다.
4. EXPRESSION-01은 `adapter.py:158` → `verified_reading_labels(..., (30, 32))`에서
   실제 응답 보기값, evidence 개수, 정수 ID와 순서, answer label의 완전 일치를 확인한다.
   실패 시 `PresentationUnavailable`을 던진다. 다른 등록 formatter 7개도 동일 검증기를 쓴다.
5. `router.py:146–147`의 기존 APIRoute catch가 HTTP 503으로 변환한다.
   `error_response()`는 `:84`, 메시지 상수는 `:27`이며, 외부 handler `:103–108`이
   `X-Request-Id`와 `Cache-Control: no-store`를 붙인다. 응답에는 분석 결과/답변 원문이 없다.

```json
{"code":"SERVICE_UNAVAILABLE","message":"개인 분석 서비스의 계약 또는 인증 설정을 확인해야 합니다."}
```

유효한 Q030=4/Q032=2 입력은 HTTP 200이며 본문의 상태는 각각 SUCCEEDED와
INSUFFICIENT다. 양쪽 모두 다음 실제 답변 재진술을 반환한다.

> 호감이 생기면 먼저 마음을 표현하는 편이라고 답했어요. 연인에게 좋아한다는 말은 가끔 한다고 답했어요.

정상 응답은 전체 adapter 결과와 HTTP 본문을 비교한다. 강제 검증 실패는 두 상태 각각에
answer 누락/불일치, evidence 역순/추가, ID의 float 변조를 주입한 10개 HTTP 사례다.
모두 위 JSON 전체·503·헤더·추론 및 변조 문자열 미노출을 확인한다. 추가로 등록 formatter
8개를 직접 호출해 잘못된 근거를 거부하는지도 검사한다. 테스트 앱의 authorize 대역은
formatter 경계 격리용이며 실제 인증 구현이나 운영 게이트를 완화하지 않는다.

**변경 분류:** 유효 응답은 text 수정이다. 잘못된 formatter evidence에서 v1의 원문
fallback(200 결과에 미측정 추론 포함 가능)을 v2의 503 거부로 바꾼 것은 오류 처리 변화다.
새 DTO·오류 코드·HTTP 상태 종류를 추가하지 않았지만 호출자의 재시도/최종 실패에 영향을
준다. 이 검증은 허용된 최소 수정의 일부로 별도 리뷰해야 한다. 기존 다른 503 발생 조건
(인증/계약/런타임 설정 등)은 그대로이며 이 공통 본문만으로 원인을 구분할 수 없다.

### BE의 기존 503 처리 — 읽기 전용 조사

다음은 기준 커밋의 `back/` 소스 근거다. BE 코드·설정·DB를 변경하거나 재처리하지 않았다.

| 근거 | 확인한 동작 |
| --- | --- |
| `HttpPersonalAnalysisClient.java:38–43` | 503을 `Failure("AI_HTTP_503", true)`로 분류. 5xx·408·429는 재시도 대상 |
| `global/ai/AiInternalClient.java:113–122, :177–187` | 503은 즉시 예외. 같은 HTTP 호출 안의 추가 send는 첫 401의 `ACCESS_TOKEN_EXPIRED`에만 적용. 503 본문의 formatter 원인은 구분하지 않음 |
| `PersonalAnalysisWorker.java:65–68, :109–122` | client가 준비되지 않으면 claim하지 않음. claim 시 attempts 증가 및 `now + stuckAfter` 선점 기한. 이미 최대 시도면 FAILED, 재시도 시각 없음 |
| `PersonalAnalysisWorker.java:173–175` | `retryable && attempt < maxAttempts`일 때 FAILED 내부 상태에 `nextRetryAt=now+retryDelay` 저장. 마지막 시도는 FAILED, nextRetryAt 없음 |
| `PersonalityRepository.java:25–30` | 기한이 된 PENDING 또는 nextRetryAt이 도래한 FAILED만 자동 선점. 최종 FAILED에 nextRetryAt이 없으면 자동 재선점되지 않음 |
| `PersonalAnalysisService.java:53–56, :95–99` | 명시적 분석 재요청 시 같은 버전의 최종 FAILED를 attempts=0/PENDING으로 되돌리는 경로 존재. jobs 조회는 재시도 예정 내부 FAILED를 PENDING으로 표현. 이번에 호출하지 않음 |
| `PersonalAnalysisProperties.java:18`, `resources/application.yml:177–187` | `tingle.ai.personal-analysis`: max-attempts=3(최초 포함), retry-delay=1m, stuck-after=5m, scheduling-enabled=true, poll-ms=2000 |
| `PersonalAnalysisScheduler.java:17–24` | 스케줄러 설정이 켜져야 주기 실행. 실제 재시도는 기한 이후 poll/작업 준비 상태 등에 영향받으므로 정확히 60초 후를 보장하지 않음 |
| `HttpPersonalAnalysisClientTest.java:49–56`, `PersonalAnalysisIntegrationTest.java:349–375` | 503 retryable 분류와 지연 후 세 번째 성공 사례가 기존 테스트에 있음. 이번 조사에서는 읽기만 했으며 실행하지 않음 |

저장소 기본 설정으로 같은 formatter 오류가 지속되면 첫째/둘째 시도 뒤 재시도를 예약하고,
셋째 시도 실패 뒤에는 최종 FAILED로 남는다. 5분 stuck 기한은 중단된 claim 복구용이고
일반 503의 1분 retry-delay와 다르다. 결정적인 문구 오류가 단순 재시도로 해결된다고
보장하지 않는다. 실제 운영 profile/환경 override·스케줄러 상태·DB 내 attempts·가입 화면의
표시/흐름은 미확인이다. 기존 운영 게이트 때문에 승인 정책 없는 prod ON은 여전히 기동을
거부하므로 이 조사 결과를 운영 호출 성공의 증거로 사용하지 않는다.

## 사실·계산 안내·일반 조언의 분리

1. 사실 후보: 설문에서 실제 선택한 보기만 재진술한다. 실제 행동을 관찰한 결과나
   상대방의 실제 선호·성격·관계 성공 가능성으로 확대하지 않는다.
2. 계산 안내 후보: irregular가 실제 포함된 시간대 차이는 원래 응답을 보존하면서
   해당 비교에서 ‘일정이 불규칙함’을 제외했다는 안내를 추가한다. 비교할 값이 없으면
   기존처럼 차이를 만들지 않는다. 배열 순서를 중요도나 순위로 해석하지 않는다.
3. 일반 조언 보류안: CONFLICT-01의 재대화 시간 제안, SUPPORT의 원하는 도움 방식
   확인 제안은 계산 결과가 아니다. 현재 v2 formatter 후보에는 넣지 않고 재검토표의
   별도 ‘일반 조언 대안’ 행에 둔다. 제공 여부·별도 표시 영역·효과 단정의 완화는 사용자 결정이다.

음주 문장은 **설문에서 고른 상한**을 설명한다. Q098 close/some/wide/unknown에 따라
계산에서 적용되는 허용 구간의 상한까지 같은 것처럼 표현하지 않는다. fit 숫자를
텍스트에 새로 노출하지 않는다. unknown 기본 폭과 근거에서 Q098을 제외하는 규칙도 보존한다.

## 문장 수·비교 사례·경계의 집계 기준

‘고유 문장’의 키는 **(templateId, 실제 완성 문장)**이다. 응답 상태가 달라도 같은 ID와
문장이면 하나로 센다. 비교 사례는 v1/v2 한 쌍과 재현 상태를 보존하므로 같은 단위가 아니다.

| 수치 | 구성과 중복 제거 기준 |
| --- | --- |
| v1 고유 문장 18,601 | reading 115 + 정상 EXPRESSION 4 + 차이 18,482. 기존 v1 검토 요약과 일치 |
| v2 고유 문장 18,603 | reading 121 + 차이 18,482. 정상 EXPRESSION 4개는 정보 부족의 같은 문장과 겹쳐 별도 가산하지 않음 |
| 비교 사례 18,607 | 부분 답변 reading 121 + 정상 EXPRESSION 4 + 차이 18,482. reading은 (ID, v1 문장, v2 문장)으로 중복 제거하고 정상 상태 4개를 별도 재현 |
| 유효 경계 응답 12 | all-unknown / self-unknown / wish-any / wish-unknown / tolerance-unknown 5 + 음주 허용폭 4 + irregular 3. text 외 응답 불변 대조이며 문장 수에 미포함 |
| 강제 formatter 실패 3행 | answer 누락·불일치·evidence 누락의 v1 fallback/v2 거부 대조. 위 경계 12와 별도. HTTP 테스트의 10가지 오류 주입 수와도 다른 집계 |
| 일반 조언 대안 2행 | 현재 formatter가 반환하지 않는 보류 제안. 고유 문장·불변 비교 사례에 미포함 |
| 검토표 전체 18,624행 | 18,607 + 12 + 3 + 2. 헤더/설명 행 제외 |

고유 문장 +2의 세부 원인은 EXPRESSION-01의 **5 → 4 (-1)**, CONFLICT-01의
**1 → 4 (+3)**다. 전자는 v1의 정보 부족 추론 원문 1개와 정상 4개를 실제 답변 4개로
통일한 결과다. 후자는 고정 조언 문장 1개를 Q042/Q043 답변 조합 4개로 재진술한 결과다.
나머지 ID의 고유 문장 수는 같다. 18,607과 18,603의 차이 4는 정상 EXPRESSION을
동일 문장이어도 별도 상태 사례로 비교하기 때문이다. 추가/누락된 설문이나 새 선정 규칙이 아니다.

배열은 표시 순서에 따라 문장이 달라져 유효한 순열을 보존한다. 숫자 1/1.0처럼 같은 보기
label인 표현은 하나로 센다. Q098 허용폭 4종의 fit 값은 같은 차이 문장 한 행에 묶고
차이 생성 조건을 별도 확인한다. 따라서 이 수치는 문구별 치환 범위의 전수이며 전체 설문
동시 입력 조합 수나 실제 앱 화면 노출 횟수를 뜻하지 않는다. 세부 구조와 ID별 증감은
생성되는 `review-summary.json`의 `countDefinitions`에 기록한다.

## 재현 명령과 기록

기존 AI requirements를 갖춘 Python 환경과 Node.js를 사용한다. ai-server에서 실행한다.
새 의존성은 추가하지 않았다. 이 작업은 기존 격리 환경을 실행에만 재사용한다.

```powershell
python -B -m app.personal_analysis_api.review_samples
python -B -m pytest -q tests/test_personal_analysis_api_contract.py tests/test_personal_analysis_production.py tests/test_personal_analysis_wording_v2.py
python -B scripts/run_tests.py
python -B -m scripts.export_personal_wording_review --output-dir C:/Users/SSAFY/.codex/visualizations/2026/11/15/01a1dd59-1938-7320-8c3c-4d0b78b74607/policy-review-357-v2
git diff --check
```

생성 도구는 Git 객체에서 기준 presentation/DTO를 임시 namespace로 읽는다. 보호한 계산·
계약 파일이 기준과 같은지 확인하고, 동일 계산 결과를 v1/v2 adapter로 변환해 `text`
이외 모든 필드가 같은지 대조한다. 완성 문장 전체의 알려진 조사 오류·추론 원문 잔존과
templateId 연결도 검사한다. source SHA와 미커밋 patch를 함께 기록하며 승인 근거로
사용하는 새 SHA 체계는 만들지 않는다.

검증 결과:

- 주요 문구·계약·운영 경계 targeted: **440 passed**. 초기 실행의 테스트 보조 코드가
  배열 전수를 반복 탐색해 45초 faulthandler 진단이 나왔지만 실제 실패는 없었다.
  보조 코드를 문항별 직접 보기 선택으로 줄인 뒤 아래 전체 runner에서 재검증했다.
- 첫 전체 runner: **3,261 passed / 1 failed**. 수정하지 않은
  `test_luna_shadow_asgi.py::test_shadow_skips_a_stage_without_enough_budget[rule]`에서
  기대 200 대신 503이 나왔다. 이 단계에서 runner가 종료돼 Node/parity는 미실행이었다.
- 해당 Luna 사례를 코드 변경 없이 독립 실행: **1 passed (1.34초)**. 실제 20ms 잔여
  예산 경계에서 실패 원인은 재현되지 않았으므로 문구 변경 때문이거나 단순 부하 때문이라고 단정하지 않는다.
- 테스트 제외·Luna/Match 수정 없이 전체 runner 재실행: **Python 3,262 passed**,
  **Node 4 passed**, JS/Python **367,518회 수치 비교 / 최대 절대 오차 0.0**.
  기존 per-request cookies 폐기 예정 경고 1건. 실패·오류·skip 0.
- `git diff --check` 통과. 보호 파일(계산·설문·계약·인증·운영 게이트·Match·BE)의 diff 없음.

### 2026-11-15 오류 경계 후속 확인

이 후속 확인에서 추가로 수정한 것은 기존 허용 12개 중 다음 **4개**다.
`tests/test_personal_analysis_wording_v2.py`(HTTP 200 두 사례 추가·503 본문 전체 검증),
`scripts/export_personal_wording_review.py`(집계 검증·실패 동작 분류), 이 문서 및 README다.
추가 runtime 구현 변경은 없다. 후속 확인 전후 adapter/proposals·계산·라우터·게이트·Luna
소스가 동일한지는 작업 증거의 파일 hash 대조로 확인한다. hash는 증거 무결성 용도이며
정책 승인이나 새 승인 체계로 사용하지 않는다.

우선 표적 HTTP/formatter 검증: **13 passed / 109 deselected (0.61초)**.
2개 HTTP 200 + 10개 HTTP 503 + 등록 formatter 전부의 직접 거부 검사 1개다.
후속 개인분석 계약·운영 경계·문구 회귀: **442 passed (22.70초)**.
실행한 명령은 다음과 같다(실제 실행은 기존 `.mr357-policy/ai-server/.venv` Python 3.12.12,
`PYTHONPATH`는 각 테스트 대상 ai-server로 설정).

```powershell
python -B -m pytest -q tests/test_personal_analysis_wording_v2.py -k 'valid_expression_evidence_returns_http_200 or invalid_formatter_evidence_uses_existing_http_503 or each_registered_formatter_refuses'
python -B -m pytest -q tests/test_personal_analysis_api_contract.py tests/test_personal_analysis_production.py tests/test_personal_analysis_wording_v2.py
```

기존 전체 runner 3,262/Node 4/parity 367,518 통과는 **후속 테스트/집계 도구·문서 보완 전**
실행 결과다. runtime 소스에는 그대로 적용되지만, 새 HTTP 테스트 2개와 이번 도구 보완을
포함한 전체 runner를 다시 실행했다고 주장하지 않는다. 생성 도구 전수 비교도 별도로
실행한다. BE Gradle/DB 통합·실제 앱·운영은 이번에도 미실행이다.

### 최초 Luna 실패 보존과 원인 범위

실패 테스트:
`tests/test_luna_shadow_asgi.py::test_shadow_skips_a_stage_without_enough_budget[rule]`.
첫 전체 runner 로그의 **1 failed / 3,261 passed (384.27초)** 이력을 삭제하지 않았다.
실패는 verdict 값 비교에 도달하기 전 HTTP 상태 assertion에서 발생했다.

```text
tests/test_luna_shadow_asgi.py:551
    assert post(host)["verdict"] == model_verdict("NORMAL")
tests/test_luna_shadow_asgi.py:177 in post
    body = expect(host.post(payload), status, code)
tests/safety_asgi_contract_support.py:202 in expect
    assert response.status_code == status
AssertionError: {'actualStatus': 503, 'actualCode': 'SERVICE_UNAVAILABLE', 'expectedStatus': 200}
```

원본 전체 traceback 포함 로그를 `boundary-audit-20261115/initial-ai-regression.log`로
바이트 동일하게 보존했다. 원본 `ai-regression.log`와 SHA-256이 같다:
`ac9fa10cc2965b34bca8366492354ef2dfd1792898e2e4f2e1753e3bcdbb94fa`.

이번에는 소스 수정 전에 해당 단일 테스트를 우선 실행했다: **1 passed (0.79초)**.
이후 동일 Python/설치 환경에서 현재 v2와 기준 a8d5fc31의 Luna 전체 모듈을 순서대로
실행했다: **현재 67 passed (7.07초) / 변경 전 67 passed (7.65초)**.
기준 소스는 `git archive`를 저장소 밖 별도 디렉터리에 풀어 읽었으며 기존 작업트리와
Match 폴더를 사용하거나 변경하지 않았다. 비교 명령은 다음과 같다.

```powershell
python -B -m pytest -q 'tests/test_luna_shadow_asgi.py::test_shadow_skips_a_stage_without_enough_budget[rule]'
# 현재 v2 ai-server 및 기준 archive의 ai-server에서 각각, PYTHONPATH도 각 대상에 맞춤
python -B -m pytest -q -p no:cacheprovider tests/test_luna_shadow_asgi.py
```

코드상 테스트는 가짜 clock을 7.98초로 이동시켜 기본 8초 예산 중 약 20ms를 남긴다
(`test_luna_shadow_asgi.py:531–557`, `execution.py:15–18`). 남은 예산으로
`asyncio.wait`의 실제 대기를 제한한다(`execution.py:122, :209`). 정상 응답 직전에도
release 검증 executor를 거치며 timeout/capacity 실패는 공통 503으로 변환된다
(`http_api.py:298–311`). 개인분석 formatter는 이 위험 분석 요청의 호출 경로에 없다.
이 구조는 타이밍 민감성을 설명하지만 **최초 실패를 timeout, CPU 부하 또는 특정 내부
단계 때문이라고 입증하지는 않는다**. 원본에는 내부 단계/시간 계측과 상세 실행 순서·호스트
부하 기록이 없어 순서·환경 영향도 확정할 수 없다. 두 모듈 통과는 해당 실행에서 재현되지
않았다는 증거이며 전체 스위트 순서/부하 조건이 같았다는 증거가 아니다.

판정은 **최종 재실행 PASS / 최초 실패 원인 미확정**이다. Luna/Match 코드·시간 예산·
assertion을 바꾸거나 테스트를 제외하지 않았다. 원인을 좁히는 별도 계측이 필요하면 현재
12개 범위 밖 변경이므로 따로 제안한다.

후속 증거는 저장소 밖
`C:/Users/SSAFY/.codex/visualizations/2026/11/15/01a1dd59-1938-7320-8c3c-4d0b78b74607/policy-review-357-v2/boundary-audit-20261115/`
에 있다: `luna-first-repro.log`, `luna-current-module.log`, `luna-baseline-module.log`,
`http-formatter-targeted.log`, `personal-regression.log`, `audit-input-state.json`.
후속 확인 전 patch/요약/검토표도 이 디렉터리의 `pre-audit/`에 보존했다.

기존 CI 성공은 위 로컬 결과에 포함하지 않는다. Docker·실제 BE 저장·앱 표시·
운영 활성화·배포는 이번 문구 작업에서 미실행/미검증이다. 타이밍 경계 테스트의
첫 실패와 재실행 결과는 로그에 모두 보존한다.

## v2 패치 재검토: core 상태와 any 스키마 — 2026-11-15

이번 확인도 기준 a8d5fc31의 미커밋 작업이다. 기존 허용 12개 중
`tests/test_personal_analysis_wording_v2.py`와 이 문서 **2개만 추가 보완**했다.
adapter·core·설문·입력 validator·DTO·점수/fit·유형·선정·운영 게이트 구현은 변경하지 않았다.
위 503의 BE 재시도 영향과 최초 Luna 실패 이력·판정은 그대로 보존한다.

### formatter의 실제 적용 상태

- 실제 core 결과 `app/personal_analysis/dto.py:35–38`의 status는
  `Literal["SUCCEEDED", "INSUFFICIENT"]`다. BE용 응답 DTO `personal_analysis_api/dto.py:48`도 같다.
- 서비스 `personal_analysis/service.py:35–49`는 분류가 insufficient일 때 INSUFFICIENT,
  나머지 유효 분류일 때 SUCCEEDED 결과를 만든다. 계산 결함은 FAILED 결과를 반환하지 않고
  `CalculationFailure`를 던진다. typeStatus의 ASSIGNED/BORDERLINE/TIED는 별도의 분류 상태이며
  formatter 적용을 위한 추가 result status가 아니다. BE의 PENDING/FAILED도 core 결과가 아니다.
- `adapter.py:221–229`에 별도 status 조건문은 없다. 현재 실제 서비스 경로에서는 위 두
  상태의 검증된 결과만 `to_be_response()`에 전달되므로 formatter 범위가 다른 상태로 확대되지
  않는다. 이를 확인하는 계약 테스트를 추가했으며 runtime guard나 DTO 변경은 추가하지 않았다.
- 테스트는 두 실제 core 결과 상태와 두 DTO의 허용 상태 집합을 대조하고,
  PENDING/RUNNING/FAILED 결과를 core DTO가 거부함을 확인한다. 기존 정상/정보 부족의
  모든 reading 변형 검증도 유지했다. 실제 core 계산에 예외를 주입한 경우는 formatter에
  도달하면 테스트가 실패하도록 구성했으며 `CalculationFailure → HTTP 500 INTERNAL_ERROR`
  본문 전체를 확인했다. formatter 근거 검증 실패의 503과 계산 실패의 500을 구분한다.

### Q065~Q095의 any 허용 범위

대조 원본은 고정된 `app/personal_analysis/data/questionnaire-SIS-Q-1.1-100.json`과
`reference.CATALOGUE`다. **Q065~Q095의 31개 모두** `allowAny=true`이며
`responseSchema.oneOf`에 `{"const":"any"}`가 있다. validator의 결과도 전부 일치한다.
숫자 보기 문항 20개, 복수 선택 문항 11개 모두 문자열 `"any"`를 허용한다.
복수 선택 문항에서도 `"any"`는 문자열 sentinel이며 선택지 배열의 원소가 아니다.

| 입력 범위 | 스키마·실제 처리 | 검증 수 |
| --- | --- | ---: |
| Q065~Q095의 문자열 `any` | 31개 모두 허용. HTTP 200/INSUFFICIENT, 해당 자기/선호 차이 없음 | 31 |
| 자기 값 unknown / 상대 선호 unknown | 문항별 const/allowUnknown 대조 후 HTTP 200, 해당 차이 없음 | 31 × 2 |
| Q065~Q095의 `["any"]` | 31개 모두 미허용. INVALID_ANSWER, HTTP 422 INVALID_REQUEST | 31 |
| 대응 자기 문항 Q030~Q060의 문자열 `any` | allowAny=false, const 없음. INVALID_ANSWER, HTTP 422 INVALID_REQUEST | 31 |

기존 31쌍 테스트에서 정상 취급한 문자열 any는 모두 허용 값이었다. 이를 93개의 독립
사례로 분리하고 실제 문항 스키마·validator·adapter·HTTP를 함께 대조하도록 강화했다.
미허용 형태/문항 62개는 오류의 question_ids까지 확인하고, presentation에 도달하면
실패하도록 검사했다. 입력 validator `validation.py:23–39, :45–63`와 라우터의 기존
입력 오류 변환은 수정하지 않았다. unknown과 any의 의미·fit 계산을 바꾸지 않았다.

### 이번 재검토의 실행 결과

실행 위치는 전용 작업트리의 ai-server이며 기존 Python 3.12.12 격리 환경을 재사용했다.

```powershell
python -B -m pytest -q tests/test_personal_analysis_wording_v2.py -k 'result_states or lifecycle_states or core_failure or schema_permitted or outside_the_question_schema'
python -B -m pytest -q tests/test_personal_analysis.py tests/test_personal_analysis_api_contract.py tests/test_personal_analysis_production.py tests/test_personal_analysis_wording_v2.py
```

- 상태·스키마 표적: **162 passed / 119 deselected (3.43초)**. 기존 두 상태의 reading
  전수 검사 2개 + 상태 계약 1개 + BE 상태 거부 3개 + 계산 실패 1개 + 허용 입력 93개 + 거부 입력 62개.
- core 입력 검증·API 계약·운영 경계·문구 관련 회귀: **705 passed (25.15초)**.
  기존 HTTP 200/503, unknown/any, 음주 허용폭, irregular, 인증·버전·GMS 금지 대역 검사도 포함한다.
- 전체 AI runner, Node/parity, Luna 모듈, BE/DB·실제 앱·운영은 이번 재검토에서 재실행하지
  않았다. 앞 절의 전체 runner·Luna 결과는 당시 실행 이력으로 보존하며 새 테스트를 포함한
  전체 실행 결과로 바꾸어 보고하지 않는다.

이번 증거는 검토 자료 폴더의 `state-schema-audit-20261115/targeted.log`, `regression.log`,
`source-before.json`에 보존한다. 기존 검토표의 문구·집계·사용자 승인란은 변경하지 않는다.
정책은 계속 UNAPPROVED이며 commit/push/MR 생성·정책 승인·운영 활성화를 수행하지 않는다.

## !358 머지 후 최신 develop 재검증 및 MR 준비 — 2026-11-15

- 최신 fetch 기준 `origin/develop`은 `19eddf45f310745080160e68f01ee432a7e9122a`다.
  GitLab !358의 Merged 표시와 git merge 메시지·조상 관계를 대조했다. !357과 !358을 모두 포함한다.
- v2 변경 12개를 저장소 밖 ZIP·파일 해시 목록과 scoped stash에 백업했다. !358의 변경 12개와
  경로가 겹치지 않아 전용 작업트리만 fast-forward한 뒤 원래 v2 파일 바이트를 그대로 복원했다.
  충돌은 없었다. 다른 작업트리·Match 작업 폴더는 변경하지 않았다.
- 최신 기준 반영 후 변경한 것은 검토 생성 도구의 기준/출처 metadata와 README·이 문서다.
  adapter/proposals·테스트·합성 응답의 기능 내용은 직전 검증본과 같다. 사용자 지정 12개만
  커밋 대상으로 유지하며, 검토 문서도 사용자가 명시한 기존 경로 안에서만 갱신한다.
- !358의 source/test/fixture 12개와 BE 전체, 개인분석 core/설문·DTO·인증·운영 게이트,
  위험 분석·Match 런타임은 최신 develop과 동일하다. 보호한 파일을 이 MR에서 수정하지 않는다.

Python 3.12.12의 기존 AI 격리 환경과 Node v24.18.0에서 다음을 실행했다.

```powershell
python -B -m pytest -q tests/test_personal_analysis.py tests/test_personal_analysis_api_contract.py tests/test_personal_analysis_production.py tests/test_personal_analysis_wording_v2.py tests/test_match_be_release_metadata.py
python -B scripts/run_tests.py
git diff --check
```

- 관련 회귀 **718 passed (17.29초)**. !358 metadata 13개를 포함한다.
- 전체 runner **Python 3,436 passed (142.28초), Node 4 passed, 수치 비교 367,518회 PASS**.
  최대 절대 오차 0.0, 실패/오류/skip 없음. 기존 per-request cookies 폐기 예정 경고 1건.
- 이 전체 실행은 앞선 표적 보완까지 포함한 최신 코드에 대한 결과다. 최초 Luna 실패
  traceback과 원인 미확정 판정은 위 절에 그대로 보존한다. 이번 통과로 최초 원인을 확정하지 않는다.
- SUCCEEDED/INSUFFICIENT, 검증 실패의 추론 fallback 차단/503, 상태 계약, 문항별 any,
  음주 허용폭/irregular, 인증/버전, GMS 호출 금지, prod OFF 라우터 미등록 및 승인 정책 없는
  prod ON 기동 거부를 포함한다. 승인 정책 있음 분기는 합성 정책 대역이며 실제 승인 근거가 아니다.
- 최신 source SHA는 MR의 commit 탭 및 저장소 밖 `review-summary.json`의 sourceCommit에
  기록한다. 재현 기준과 문구 비교용 patch는 생성 도구가 함께 출력하며 승인 체계로 사용하지 않는다.
- 검증 로그·백업은 기존 검토 자료 폴더의 `develop-revalidation-20261115/`에 보존한다.
  생성 도구 출력도 이 폴더로 분리해 기존 미승인 검토표와 과거 이력을 덮어쓰지 않는다.
- BE/공유 DB 테스트·실제 앱 표시·실제 GMS·운영 활성화·배포는 수행하지 않았다.
  prod 기본 OFF와 승인 정책 없는 prod ON 차단은 그대로다. 정책은 UNAPPROVED,
  사용자 최종 결정·실제 정책 승인 이력은 공란으로 유지한다.

이 후속 MR은 develop 대상으로 생성하고, 사용자 지정에 따라 **가을님(@chogaeull)**에게
코드 리뷰를 요청한다. formatter 검증 실패의 503이 BE 재시도·최종 실패로 이어지는 영향을
별도로 확인해야 한다. 코드 리뷰 요청/승인은 문구 정책 승인이 아니며, 이 작업에서
MR 병합·자동 병합·배포·운영 활성화·정책 승인을 수행하지 않는다.

## BE 리뷰 반영: formatter 500 분리와 안전 진단 — 2026-11-15

기준은 `bcd2c0201dae213c238d765c15f9152c3f90bad4`이며 가을님의
[3개 BE 리뷰](https://lab.ssafy.com/s15-final/S15P31E106/-/merge_requests/359#note_3075849)를 반영한다.
문구·계산·DTO·정책을 바꾸지 않고 formatter 근거 불일치의 오류 분류와 진단만 보완한다.

### PresentationUnavailable 전체 발생 경로 분류

| 기존 검사 위치 | 이번 처리 |
| --- | --- |
| `verify_reference_binding`: catalogue/계산 버전·packaged hash·실제 파일 hash·파일 I/O | 기존 PresentationUnavailable / HTTP 503 유지 |
| `question_id`: 정수 ordinal·매핑 범위 | 503 유지 |
| `difference_evidence`: 비교 문항 짝·측정 여부 | 503 유지 |
| `reference_evidence`: 참여 feature/문항/domain·원래 wish/self·부족한 근거·mode | 503 유지 |
| `to_be_response`: 결과 버전·없는 reading ID 매핑·없는 difference/reference renderer | 503 유지 |
| `proposed_label_only_difference`: index/fit·label/self/wish 일치 | 503 유지. proposals.py를 변경하지 않음 |
| `verified_reading_labels`: 근거 개수·정수 ID·순서·유효한 실제 답변/label | FormatterEvidenceMismatch / HTTP 500으로 분리 |
| `format_expression_reading`: 위 검사 및 선택된 분기와 답변 코드의 일치 | FormatterEvidenceMismatch / HTTP 500으로 분리 |

등록 reading formatter 8개가 공통 검증기를 사용한다. `to_be_response()`가 formatter를
호출하는 지점에서 별도 예외에 **서버가 선택한 templateId만** 붙이고, 라우터가 고정
`500 INTERNAL_ERROR` 응답으로 바꾼다. formatter가 없을 때의 기존 row.text 선택은 유지하며,
등록 formatter의 검증 실패를 추론 원문으로 바꾸는 fallback은 없다. 예외 객체에 답변이나
row를 담지 않는다. 기존 CalculationFailure/예상 밖 내부 오류의 500도 바꾸지 않는다.

새 외부 오류 코드는 추가하지 않는다. DTO `ErrorResponse`는 기존 code/message이고,
500은 `{"code":"INTERNAL_ERROR","message":"개인 분석 처리 중 오류가 발생했습니다."}`다.
503은 기존 `SERVICE_UNAVAILABLE`이며 개인분석 계약 메시지 또는 기존 서비스 인증
오류의 메시지를 그대로 유지한다. 두 오류 모두 기존 X-Request-Id와 no-store를 유지하고
WWW-Authenticate를 새로 추가하지 않는다. 인증 401/403·버전·본문 검증은 바꾸지 않는다.

### 진단 로그와 개인정보 경계

로그는 `app.personal_analysis_api.router`의 ERROR 기록이다. 고정 메시지와 구조화 extra에
requestId/templateId/errorType 세 값만 전달한다. 기존 미들웨어가 검증한 UUID를 재사용하며
잘못되거나 중복된 X-Request-Id는 기존 규칙으로 새 UUID가 된다.

```text
personal_analysis_error requestId=11111111-2222-4333-8444-555555555555 templateId=PA-R-EXPRESSION-01 errorType=FORMATTER_EVIDENCE_MISMATCH
personal_analysis_error requestId=11111111-2222-4333-8444-555555555555 templateId=UNRESOLVED errorType=PRESENTATION_UNAVAILABLE
```

templateId가 아직 결정되지 않은 503은 UNRESOLVED다. 고정 내부 유형은
FORMATTER_EVIDENCE_MISMATCH, PRESENTATION_UNAVAILABLE, AUTH_NOT_CONFIGURED,
AUTH_VERIFIER_UNAVAILABLE, HTTP_SERVICE_UNAVAILABLE다. 이는 로그 분류이며 새 응답 코드가 아니다.
답변·원응답·회원/analysisId·JWT·요청 본문·헤더 전체·예외 문자열/traceback은 기록하지 않는다.
기존 ServiceAuthError의 503도 응답을 보존하면서 같은 안전 진단만 남긴다.

### BE 재시도 영향과 local/test/dev 적용 조건

BE `HttpPersonalAnalysisClient.analyze()`는 HTTP 상태가 500 이상이면 retryable=true로
분류한다. 따라서 **500과 503 모두 재시도**하며, 500 분리는 반복 재시도를 제거하지 않는다.
기존 Worker는 retryable이고 attempts < maxAttempts일 때만 다음 시각을 예약한다.
저장소 기본은 최초 포함 3회·1분 간격이며 계속 실패하면 최종 FAILED다. 실제 운영 override,
DB 내 시도 횟수, 재처리는 확인/실행하지 않았다. 불필요한 반복 재시도를 막으려면 별도
BE 오류 분류 협의가 필요하며 이번에 BE 코드나 외부 오류 계약을 임의로 바꾸지 않는다.

local/test/dev는 새 코드로 `create_app()`을 구성할 때 제안 정책을 연결한다. 유효한 기존
서비스 인증·버전·설문 검증을 통과하여 **실제로 새 AI 분석 호출이 발생한 경우** 정상과
정보 부족 모두 v2 문구를 반환한다. Git의 develop 머지 자체는 실행 중인 서버를 갱신하지
않는다. 배포·재시작은 이번 작업에서 하지 않는다. 운영 기본 OFF 및 승인 정책 없는 prod ON
차단은 그대로다.

BE 결과 조회 `PersonalAnalysisService.get()`는 DB에 저장된 JSON을 읽고, Worker는
PENDING/재시도 예정 FAILED만 선점한다. 기존 SUCCEEDED/INSUFFICIENT 저장 결과를
새 formatter로 다시 렌더링하거나 자동 갱신하지 않는다. 기존 완료 결과가 재사용되면 새 AI
호출도 없을 수 있다. 명시적인 재분석/재저장은 별도 정책이며 이번에 실행하지 않는다.

### 검증 및 CI 이력

bcd2c020의 [기존 CI #299135](https://lab.ssafy.com/s15-final/S15P31E106/-/pipelines/299135)는
Passed였다. 당시 리뷰봇은 Jenkins ai/jenkins success를 표시했다. 이 성공은 새 후속 커밋의
CI 성공으로 재사용하지 않는다. 최초 Luna 실패 traceback과 원인 미확정 판정은 위 원문 및
저장소 밖 원본 로그에 보존한다. 새 검증 결과는 아래에 별도 기록한다.

로그·재현 자료는 기존 검토 자료 폴더의 `be-review-20261115/`에 분리한다. 정상 문장과
text 외 계산 결과·선정·근거·priority 및 UNAPPROVED/실제 승인 이력 공란은 유지한다.

이번 후속 변경은 adapter/router, 관련 문구·계약·시작 테스트 3개, README/이 문서,
검토 생성 도구·샘플 생성기·샘플 manifest의 **10개 파일**이다. 샘플 정상/정보 부족 응답
본문과 proposals.py는 그대로다. 시작 테스트는 실제 인증 구현을 바꾸지 않고 새 로그와
기존 인증 경계가 함께 성립하는지 검증한다.

```powershell
# ai-server, 기존 Python 3.12.12 환경
python -B -m pytest -q tests/test_service_auth_startup.py tests/test_personal_analysis.py tests/test_personal_analysis_api_contract.py tests/test_personal_analysis_production.py tests/test_personal_analysis_wording_v2.py
python -B scripts/run_tests.py
# back, 기존 JDK 21 실행 경로 사용. 실제 HTTP·DB 없는 기존 클라이언트 단위테스트만 선택
./gradlew.bat test --tests 'com.e106.tingle.personality.HttpPersonalAnalysisClientTest' --offline --no-daemon --console=plain
```

- 최종 관련/시작 회귀: **804 passed (104.61초)**. 정상 200, 8개 formatter × 2개 결과 상태 ×
  5개 변조의 500/안전 로그, 기존 503, 잘못된 requestId 정규화, 인증/버전/운영 게이트를 포함한다.
- 기존 BE 클라이언트 단위테스트: **4 passed**, 실패/오류/skip 0. 500과 503이 모두 retryable임을
  실제 클라이언트 코드로 확인했다. Worker/DB 통합 재시도·실제 운영 설정은 미실행/미확인이다.
- 이번 첫 전체 실행: **3,514 passed / 8 failed (517.71초)**. Python 실패로 Node/parity는
  이 첫 runner에서 미실행이었다. 전체 로그를 `full-runner.log`에 보존했다.
- 그중 시작 테스트 6건은 추가된 안전 로그 때문에 기존 `stderr == ""` 기대가 실패한 것이다.
  로그를 숨기거나 모든 stderr를 허용하지 않았다. 실제 개인분석 503 응답의 UUID 목록과
  고정 AUTH_VERIFIER_UNAVAILABLE 로그를 순서·개수·전체 문자열까지 정확히 대조하고,
  JWT 2개 및 본문 sentinel이 stdout/stderr에 없음을 검사한다. 기존 인증·환경·실패 폐쇄
  assertion은 그대로이며, 보완 후 위 804개 회귀에 모두 포함해 통과했다.
- 나머지는 `test_actual_asgi_disconnect_cancels_provider_and_sends_no_response`의
  runtime.remaining 기대 0/실제 2, 기존 Luna `[rule]`의 기대 HTTP 200/실제 503이다.
  수정본의 두 테스트 독립 실행은 **1 PASS / Luna 1 FAIL**이었다. 변경 전 bcd2c020을
  저장소 밖 archive로 추출해 동일 Python 환경에서 비교해도 **1 PASS / Luna 1 FAIL**이었다.
  GMS 연결 해제의 첫 실패는 독립 실행에서 재현되지 않았고 원인은 미확정이다. Luna는
  기준본에서도 동일 실패를 재현했으나 내부 단계·환경의 정확한 원인은 확정하지 않는다.
  이 두 모듈의 코드·시간 예산·assertion을 바꾸거나 테스트를 제외하지 않았다.
- 최초 문구 v2 작업 당시의 Luna 실패 로그도 별도로 원본 SHA와 함께 계속 보존한다.
  위 과거 PASS 이력을 현재 전체 실행 PASS로 덮어 쓰지 않는다. 최종 전체 재실행은 별도 로그로 남긴다.
- 보완 후 최종 전체 runner는 **Python 3,522 passed (454.88초), Node 4 passed,
  수치 비교 367,518회 PASS / 최대 절대 오차 0.0**이다. 실패/오류/skip 0, 기존 cookies
  폐기 예정 경고 1건. 로그는 `full-runner-rerun.log`다. 위험 분석·Luna의 코드·시간 예산·
  assertion을 바꾸지 않고 전체를 재실행했다. 판정은 **최종 재실행 PASS / 최초 실패 원인 미확정**이다.

## 아직 필요한 정책 결정

- v2 문구·ID·priority 범위와 정상/정보 부족 formatter 적용에 대한 사용자 승인.
- irregular 제외 안내의 표현과 배치, 일반 조언 제공 여부와 별도 표시 영역.
  조언을 별도 응답 필드로 요구한다면 DTO·BE 계약 협의가 추가로 필요하며 이번에는 구현하지 않는다.
- 승인 시 최종 표시 버전, 검토자·날짜·승인한 문구/ID/priority 목록 버전·변경 사항 기록.
- 기존 BE 저장 문구를 재처리할지 여부는 별도 협의 대상이며 이번에는 실행하지 않는다.
- 운영 정책은 여전히 없고 prod ON은 기존처럼 전체 앱 기동을 거부한다. 이 게이트의
  후속 개선이나 운영 활성화는 이번 범위에서 변경하지 않는다.

v1 검토표와 원본 `presentation-review.json`은 과거 미승인 스냅샷으로 보존한다.
v2 검토표의 사용자 최종 결정·검토자·결정일·변경 사항 입력란은 전부 비워 둔다.
