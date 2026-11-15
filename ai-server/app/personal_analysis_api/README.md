> 2026-10-30 AI MR 준비: 기준 develop은 `54182aaa27d00f71443faea6ace83022b7f34e49`다. 운영 main의 위험 분석·매칭 이유 라우터와 공통 Request ID 미들웨어 1회를 유지하며, 개인 분석 POST는 미등록(404)이다. 표시 제안은 테스트·합성 검토 샘플에서만 명시적으로 사용한다. BE Java smoke test·Gradle 변경·통합 증거는 이 AI 변경 범위에서 제외했다. 아래 수치·경로·차단 사항은 이전 독립 어댑터 작업 당시의 기록이며, 현재 MR 작업본의 재실행 결과나 차단 사항으로 해석하지 않는다.

# S15P31E106-146 BE 확정 계약과 검토 샘플

## 미승인 문구 v2 후속 수정안 — 2026-11-15

MR !357은 2026-11-15 14:29:44(Asia/Seoul)에 `a8d5fc31`로 develop에 머지됐다.
초기 검토는 해당 머지 커밋에서 분리했고, 현재 MR 준비 기준은 !358을 포함한 develop
`19eddf45f310745080160e68f01ee432a7e9122a`다. 기존 MR의 코드 승인을 문구
승인으로 해석하지 않는다. `mr357-wording-review-20261115-v2`는 **UNAPPROVED 검토 버전**이다.
운영 정책 연결과 게이트·계산·DTO·성공 상태 계약은 변경하지 않는다.

선택된 표시 formatter는 SUCCEEDED와 INSUFFICIENT 모두에서 검증된 답변을 재진술한다.
reading formatter의 근거/답변이 맞지 않으면 `FormatterEvidenceMismatch`로 구분하여
기존 `500 INTERNAL_ERROR`로 거부한다. 추론 원문 fallback을 반환하지 않는다.
참조 파일·정책 매핑·차이/추천 근거 등 기존 `PresentationUnavailable`은 503을 유지한다.
이는 성공 문장 text와 별개의 오류 처리 변경이며, 새 외부 오류 코드나 DTO를 추가하지 않는다.
오류 로그에는 검증된 requestId·서버가 선택한 templateId·고정 errorType만 기록한다.
503처럼 templateId가 결정되지 않은 경우 `UNRESOLVED`를 사용한다. 원문·회원정보·JWT·
요청 본문·예외 메시지/traceback은 로그에 담지 않는다.
BE는 `AI_HTTP_500`과 `AI_HTTP_503`을 모두 재시도한다. 따라서 분류 변경으로 재시도가
없어지지는 않는다. 저장소 기본은 최초 포함 최대 3회, 간격 1분이며 운영 설정·DB는 미확인이다.
CONFLICT·SUPPORT의 일반 조언은 사실 재진술 후보와 분리한 보류 대안으로 기록한다.
조사 오류, 음주 선호의 설문상 상한 의미와 irregular 제외 안내도 문구에만 반영한다.

기존 [presentation-review.json](presentation-review.json)은 v1 스냅샷으로 보존한다.
새 완성 문장·경계 사례·v1/v2 비교·기준 커밋·미커밋 diff는
`scripts/export_personal_wording_review.py --output-dir <저장소 밖 경로>`로 생성한다.
상세 범위와 검증 결과는 [WORDING_REVIEW_V2.md](WORDING_REVIEW_V2.md)를 따른다.
여기에 정확한 HTTP 경로·본문, BE 처리 근거, 최초 Luna 실패 traceback과 재실행 결과,
고유 문장 수와 비교·경계 사례 수의 차이도 기록한다. 최초 실패 원인은 미확정이다.
사용자 최종 승인란은 생성 도구가 채우지 않는다.

local/test/dev는 해당 코드가 실제 실행되는 앱을 `create_app()`으로 구성할 때 후보 정책을
연결한다. 배포/재시작 후 새로 요청하는 SUCCEEDED와 INSUFFICIENT 분석에 v2 문구가 적용된다.
develop 머지 자체로 이미 실행 중인 서버나 BE의 저장 결과가 바뀌지는 않는다. AI는 DB를
갱신하거나 과거 분석을 재처리하지 않는다. 명시적 재분석·저장 정책은 BE의 별도 책임이다.

## MR !357 미승인 운영 표시 정책 차단 — 2026-11-15

이 절이 아래 과거 운영 연결 설명보다 우선한다. 최신 `develop@2692eff0`과
MR 원본 `dc5289e0`에는 승인된 운영 `PresentationPolicy`나 등록 체계가 없다.
버전 조합·참조 데이터 승인은 표시 문구 승인을 뜻하지 않는다.

| 환경·설정 | 현재 동작 |
| --- | --- |
| prod, `PERSONAL_ANALYSIS_PROD_ENABLED=false` (기본) | 개인분석 import/runtime/라우터 없음, POST 404, 기존 health·다른 라우터 유지 |
| prod, `PERSONAL_ANALYSIS_PROD_ENABLED=true`, 승인 정책 없음 | 앱 생성 단계에서 `RuntimeError`로 기동 거부. 제안 정책·계산 데이터·개인분석 라우터를 로드하지 않음 |
| prod ON, 승인 정책 연결 후 | 기존 실제 adapter·서비스 JWT·버전·참조 해시 경로 사용. 현재 저장소에는 해당 승인 정책이 없으며 테스트에서만 합성 정책으로 연결 경계를 검증 |
| local/test/dev | 기존 `proposed_label_only_policy()` 사용, prod 플래그와 관계없이 기존 결과 유지 |

[production.py](production.py)는 현재 `None`을 반환하는 연결점이다. 환경변수·요청·
임의 SHA 또는 승인 boolean으로 정책을 승인하는 통로는 없다. 정책 담당자의 실제
승인 출처와 표시 버전을 확정한 뒤, 별도 리뷰 변경으로 승인본을 연결해야 한다.
서비스 RS256 JWT, 정확한 `ai:execute`, prod issuer/audience, 버전 및 참조 해시
검증은 그대로다. 개인분석에 GMS를 추가하지 않았다.

[presentation-review.json](presentation-review.json)은 **UNAPPROVED 검토 자료**다.
15개 reading ID·원문 템플릿·priority·근거, 정상 expression-01의 실제 4개 문구,
INSUFFICIENT의 원문 fallback, 차이 ID/key·priority 2/1·문구와 31개 비교쌍,
동적 표현이 사용하는 62개 문항의 실제 보기·스키마를 기록한다. 운영 코드는 이 파일을
읽지 않는다. title은 내부 매핑에만 쓰이며 공개 DTO에 추가하지 않는다.
검증 명령·결과·미확인은 [운영 정책 검증 기록](PRODUCTION_POLICY_VERIFICATION.md)을 따른다.

**문구 승인과 승인본 연결 전에는 운영 활성화가 차단된다.** 이 수정만으로 실제 가입
진행 문제가 해결되었다고 볼 수 없다. 서버 설정 변경·재기동·배포는 수행하지 않는다.

## MR !161 리뷰 보완 — 2026-11-03

이 절은 아래 과거 작업 이력보다 우선한다. `main.py`는 `local/test/dev`에서만
adapter·표시 제안·runtime·router를 import하고 등록한다. `prod`는 개인분석 패키지를
import하지 않는다. 따라서 개인분석 데이터 누락/해시 오류가 있어도 앱 기동·health와
기존 risk/explanation 경로를 유지하고, 개인분석 경로·runtime은 없으며 POST는 404다.
허용 환경에서 발생하는 import 시 데이터 오류는 잡아서 무시하지 않으므로 기동 실패를 유지한다.

### 본문 크기 산정과 방어 상한 후보

현재 승인 버전 조합과 100문항 catalogue, 유효 fixture를 UTF-8 JSON bytes로 계산했다.
compact는 `ensure_ascii=False`, `allow_nan=False`, separators `(',', ':')`이고,
pretty는 indent=2와 마지막 LF 한 개다.

| 요청 | compact bytes | pretty bytes |
| --- | ---: | ---: |
| 정상 SUCCEEDED fixture | 3,551 | 6,236 |
| INSUFFICIENT fixture | 4,042 | 6,695 |
| 의미상 가장 큰 정상 canonical 요청 | 4,650 | 7,970 |

최대 요청은 `analysisId=2^63-1`, 승인 버전 문자열, 문항별 가장 긴 JSON 값을 사용한다.
배열은 중복 없이 `maxItems`까지 가장 긴 보기들을 선택하고 `unknown/any`도 비교한다.
DTO·승인 binding·100문항 validation을 실제로 통과시킨다. DTO만 통과하는 무효 answers는
정상 요청으로 세지 않는다. 승인되지 않은 최대 30자 버전도 정상 버전으로 세지 않는다.
기본 `json.dumps` 구분자 사용 시 정상 3,957 bytes, 최대 5,107 bytes다.

**원문 JSON의 절대 최댓값은 유한하지 않다.** 공백, 문자열의 `\uXXXX` escape,
수치적으로 같은 `1.000...` 표기를 늘려도 유효할 수 있다. 위 최대는 canonical 직렬화의
최대이며 모든 가능한 원문 표현의 최대가 아니다. DTO 의미를 이번에 좁히지는 않는다.

방어 상한 **후보 16 KiB = 16,384 bytes**를 개인분석 HTTP 경계에 적용한다.
최대 pretty 요청의 2배(15,940 bytes)보다 크고, compact 최대의 약 3.52배다.
팀의 새로운 일반 계약/승인값으로 확정한 수치가 아니며, 과도한 공백·escape를 포함하는
직렬화까지 무제한 수용하지 않는 개발 runtime 방어 후보임을 명시한다.
팀이 별도 byte 한도를 계약화하려면 이 산정 근거를 검토해야 한다. 환경 설정 우회는 추가하지 않는다.

서비스 JWT 인증을 먼저 완료한 뒤 Content-Length를 사전 확인하고, 새 Request에 감싼
ASGI `receive`의 실제 `http.request` chunk bytes를 누적한다. 상한 초과 chunk는 parser에
전달하지 않으며 나머지 stream도 읽지 않는다. Content-Length 누락·작은 거짓 값에도 적용한다.
상한과 같은 크기는 허용한다. 먼저 `request.body()`로 전체를 읽어 검사하지 않는다.
ASGI 서버가 이미 수신한 단일 chunk의 할당까지 방지하는 제한은 아니며, 앱 parser가
초과 본문 전체를 축적/JSON decode/Pydantic 검증하는 것을 차단한다.

크기 초과는 기존 프레임워크 400 경로로 **400 INVALID_REQUEST**를 반환한다.
code/message, no-store, X-Request-Id를 유지하고 413이나 새 code를 추가하지 않는다.
근거는 `back/src/main/java/com/e106/tingle/personality/HttpPersonalAnalysisClient.java`의
400/409/422 재시도 금지 규칙과 `HttpPersonalAnalysisClientTest`다. 요청 크기를 줄이지 않은
재전송을 유도하지 않는다. 기존 DTO/답변 422와 인증 실패 응답은 보존한다.
risk/explanation에는 이 route class를 적용하지 않는다.

### 재현과 검증

아래 명령은 `ai-server`에서 실행한다. 새 의존성은 없다.

```powershell
python -m scripts.measure_personal_body
python -m pytest -q tests/test_personal_analysis_body_limit.py tests/test_personal_analysis_import_isolation.py tests/test_personal_analysis_api.py tests/test_personal_analysis_api_contract.py tests/test_service_auth_startup.py
python scripts/run_tests.py
docker build -t tingle-ai-personal-runtime-161:review .
python -m scripts.check_personal_runtime --image tingle-ai-personal-runtime-161:review
git diff --check
```

import 격리 테스트는 임시 앱 복사본의 `reference.json`을 누락시키거나 유효 JSON이지만
잘못된 해시인 `{}`로 바꾸고 별도 Python 프로세스에서 기동한다. prod 두 경우뿐 아니라
local/test/dev 여섯 실패도 확인한다. 실제 참조 파일과 provenance/hash는 수정하지 않는다.
raw ASGI 테스트는 상한 이하·정확히 상한, 초과 Content-Length, 미지정/거짓 길이 chunk,
인증 실패 우선, 파싱/계산 0회, 다른 두 경로 보존을 확인한다. Docker smoke는 이번 소스로
만든 이미지의 Uvicorn에 실제 HTTP 요청을 보내 dev 두 결과 200, prod 정상/누락/손상
개인분석 404·health 200·개인분석 import/runtime 부재를 확인한다.

이번 변경 검증 결과: targeted pytest **390 passed**, `python scripts/run_tests.py`의
전체 Python **1,307 passed**(기존 per-request cookies 폐기 예정 경고 1건), Node 회귀
**4 passed**, JS/Python **367,518 수치 비교 / 최대 절대 오차 0.0**, `git diff --check` 통과.
새 Docker 이미지 `tingle-ai-personal-runtime-161:review`에서 위 네 시나리오 모두 통과했다.
Windows 제한 실행 환경의 asyncio 내부 socketpair 생성 대기는 로컬 소켓 사용 권한으로
재실행해 해소했다. 기존 Jenkins 성공을 이번 검증 결과에 포함하지 않았다.

갱신일: 2026-10-29 (Asia/Seoul). 승인 근거는 이번 사용자 메시지로 전달된 BE 확정 계약이다. **버전·근거 조건·AI 내부 결과 보존·오류 코드와 실제 서비스 JWT 검증 모듈은 반영했으며, 표시 문구·templateId·차이 priority 및 실제 BE 발급기 연동은 별도 확인 대기**다. 운영 라우터는 미등록이며 커밋·push·MR·머지를 하지 않았다.

현재 공통 인증/Request ID의 구조·오류 challenge·키 설정은 [보안 모듈 README](../security/README.md)를 따른다. 이 문서의 실행 수치는 당시 어댑터 작업의 이력이다. 필수 scope는 확정값인 정확한 `ai:execute`다. 환경은 iss/aud 접미사로 비교하며 별도 env claim을 요구하거나 추가 검증하지 않는다. 503에는 WWW-Authenticate를 붙이지 않는다.

## 1. 이전 독립 작업 위치와 당시 기준

- worktree: `C:/Users/SSAFY/.codex/worktrees/personal-analysis-146/S15P31E106`
- 브랜치: `codex/ai-personal-analysis-adapter-S15P31E106-146`, HEAD: `c63e5beda9662fb2f48f68c79ccb4fdc121b404f`.
- 시작 시 API 디렉터리·API 테스트·중첩 계약 fixture는 기존 미추적 파일이었다. 이번에도 stage/commit하지 않았다.
- fetch한 최신 develop: `49d4ee267803af24f20585e4f2445ac9c9c9a74e`. 개인 분석 코어·main.py·requirements는 -146 HEAD와 diff가 없었다.
- 최신 develop에는 -181/-185의 경로 이전이 포함되어 있다. 원본 파일을 수정하지 않고 최신 develop 임시 archive에 -146 API/테스트/fixture만 복사해 조합 검증했다.
- 기존 -144/-145/-181/-184/-185 및 계산 코어·카탈로그·운영 main.py는 수정하지 않았다.

## 2. 확정 계약과 아직 미확정인 표시 정책

| 항목 | 현재 상태 |
| --- | --- |
| 내부 경로 | POST `/internal/ai/personal-analyses`; 운영 app에는 미등록 |
| 공식 버전 | `SIS-Q-1.1-100 + SIS-A-1.0 → local-personal-js100-port-1` 한 조합만 허용 |
| 참조 파일 대응 | [adapter.py](adapter.py)의 고정 해시와 provenance·실제 파일을 코어 호출 전에 검사. mismatch/파일 누락은 503 |
| 추천 근거 | 실제 score 참여 rows + 명시적으로 소비한 Q096/Q098 조건. 아래 및 [정책 문서](DISPLAY_POLICY_DRAFT.md) 참조 |
| 정보 부족 | AI가 계산한 scores/readings/차이/추천/missingQuestionIds를 상태와 무관하게 보존. BE 공개 필터를 AI에서 추가하지 않음 |
| 표시 문구·ID·priority | 미승인. [proposals.py](proposals.py)의 명시적 제안 설정에서만 사용 |
| 기본 표시 설정 | readings 매핑 비어 있음, 차이 renderer=None. 필요한 표시가 있으면 503. 공식 근거 함수만 기본값으로 연결 |
| 실제 인증 | ServiceJWTVerifier 구현. 기존 authorize hook에 명시적으로 주입 가능. 기본 None/운영 라우터 미등록 유지 |
| X-Request-Id | 단일 UUID 유지, 그 외 UUIDv4 생성; 개인 분석 성공·실패 응답에 반송/no-store. ASGI 공통 미들웨어 추가 |

`APPROVED_VERSION_BINDINGS`에는 공식 한 쌍만 있다. 사용자 지정 binding을 넣어도 승인 목록 밖의 alias/latest/내부 계산 버전 문자열을 외부 버전으로 사용할 수 없다. 버전 승인이 표시 정책 승인까지 뜻하지 않는다.

- reference.json SHA-256: `9f4d0c762a713c8c8bb8f69eb45c9e6206a33857ff482a4988d74db383585658`
- questionnaire SHA-256: `d1199ef59596e78e2c2d3df8884a72a2b97c63099e443da7b9103f3d1e7bcb6e`
- 코어 계산 및 참고 유형 버전은 기존 `local-personal-js100-port-1` / `SIS-TYPE-REF-1`을 보존한다. 버전별 표시 정책·저장 스냅샷 관리가 최종 승인 전에 추가로 필요하다.

### 실제 근거 조건

- readings: 코어 evidence의 ordinal을 카탈로그로 역매핑. Q037→Q032, Q045→Q044 등 기존 순서를 보존한다.
- 차이: 실제 자기/선호 문항 쌍. 수치 비교이고 Q098이 close/some/wide이면 Q098 추가. Q096은 사용하지 않는다.
- PREFERENCE_FIT: 선택 유형의 실제 rows[].id만 사용. Q096 배열이 실제 참여 domains와 겹치면 Q096 추가. 실제 rows에 수치/척도 비교가 있고 Q098이 close/some/wide이면 Q098 추가.
- unknown/any 기본 동작을 측정 근거로 바꾸지 않는다. Q098 때문에 fit=None으로 빠진 행은 score 근거에서 제외한다.
- SELF_SIMILARITY: 실제 rows[].feature만 qN/전체 척도로 확장. E/A/C/I만 모델 척도 가중치에 포함된다. 일부 척도 응답이 unknown이면 그 척도 전체를 제외한다. Q096/Q098을 넣지 않는다.
- 두 추천 모드에서 Q097/Q099/Q100은 score 근거가 아니다. reasons에 표시된 일부 문장으로 전체 근거를 대신하지 않는다.

## 3. 요청·응답의 구조와 자료형

요청은 JSON object이며 추가 필드를 허용하지 않는다. [dto.py](dto.py)와 기존 카탈로그 검증을 사용한다.

| 요청 필드 | 자료형 / 제약 | 역할 |
| --- | --- | --- |
| analysisId | integer, 1~2^63-1; bool/문자열/float 불가 | BE 실행 추적용. 계산 입력·응답에 포함하지 않음 |
| surveyVersion | string, 1~30자 | 공식 설문 버전 |
| analysisVersion | string, 1~30자 | 공식 외부 분석 버전 |
| answers | array<object>, 정확한 Q001~Q100 전체 | 문항 중복·누락 금지 |
| answers[].questionId | string | 실제 카탈로그 ID |
| answers[].value | number/string/array | 문항별 허용 값만. unknown/any 허용 여부도 카탈로그로 검증 |

BE는 나이·성별 metadata를 보내지 않는다. adapter가 기존 코어 metadata의 나이 세 값을 null로 보완한다.

AI 응답은 아래 **11개 필드**다. BE 공개 envelope의 resultId/sessionId/manifest/createdAt, PENDING/FAILED 상태는 BE 책임이다.

| AI 응답 필드 | 자료형 / 제약 | 보존 규칙 |
| --- | --- | --- |
| status | SUCCEEDED / INSUFFICIENT | 기존 코어 상태 그대로 |
| surveyVersion | string | SIS-Q-1.1-100 |
| analysisVersion | string | SIS-A-1.0 |
| scores | object, E/A/C/N/I/avoidance/anxiety 각 number 또는 null | 원래 7척도 값. 정보 부족일 때 유효한 일부 점수도 보존 |
| typeStatus | ASSIGNED / BORDERLINE / TIED / INSUFFICIENT | 코어 값 그대로 |
| primaryType | string 또는 null | TIED/INSUFFICIENT는 null |
| tiedTypes | array<string> | 코어의 순서·빈 배열 그대로 |
| readings | array<Reading>, 최대 3개 | priority·근거 순서·선정 순서 보존. text는 아래 Reading 필드 규칙을 따름 |
| selfPreferenceDifferences | array<Reading>, 최대 2개 | 코어 fit 오름차순·동점 문항 번호순. 표시만 제안 renderer 사용 |
| typeRecommendations | array<TypeReference>, 최대 3개 | type/score/모드/배열 순서 보존 |
| missingQuestionIds | array<string> | 자기 Q001~Q060 중 unknown. SUCCEEDED에서도 보존 |

| Reading 필드 | 자료형 | 의미 |
| --- | --- | --- |
| key | nonempty string | 기존 reading key / 차이는 실제 자기·선호 ID 쌍의 제안 key |
| templateId | nonempty string | 분기 고정 ID 후보. title 해시로 만들지 않음 |
| text | nonempty string | v2 미승인 제안은 등록한 formatter를 정상/정보 부족 양쪽에 적용한다. 검증 실패는 기존 503이며 추론 원문 fallback은 없다. 음주 차이는 선택한 설문상 상한을 명시하고 irregular가 포함된 차이는 계산에서 제외한 보기를 알린다. 점수·ID·priority·근거·선정 순서는 보존한다. |
| evidenceQuestionIds | array<string> | 실제 카탈로그 ID, 의미 있는 원래 순서 유지 |
| priority | strict integer | readings 원래 값 / 차이 첫 행 2·둘째 행 1은 미승인 표시 순위 |

| TypeReference 필드 | 자료형 | 의미 |
| --- | --- | --- |
| typeCode | nonempty string | 코어가 선택한 실제 유형 코드 |
| score | finite number, 0~100 | 기존 점수 그대로. 반올림·확률 해석 없음 |
| mode | PREFERENCE_FIT / SELF_SIMILARITY | 실제 계산 모드 |
| evidenceQuestionIds | array<string> | 해당 점수의 실제 참여 입력과 승인된 조건 근거 |

현재 response에는 classification/ranking/features/prototypes/reasons/typeReferenceVersion/원응답을 추가하지 않는다. 이 구조 보존과 BE의 최종 공개 필터는 별개다.

## 4. 실제 계산 기반 합성 샘플

| 샘플 | 요청 (100문항) | AI 응답 (제안 표시 정책) | 실제 결과 |
| --- | --- | --- | --- |
| 정상 | [succeeded.request.json](../../tests/fixtures/personal-analysis-api/succeeded.request.json) | [succeeded.response.proposal.json](../../tests/fixtures/personal-analysis-api/succeeded.response.proposal.json) | SUCCEEDED / ASSIGNED / EMBER, 누락 자기 문항 0개 |
| 정보 부족 | [insufficient.request.json](../../tests/fixtures/personal-analysis-api/insufficient.request.json) | [insufficient.response.proposal.json](../../tests/fixtures/personal-analysis-api/insufficient.response.proposal.json) | INSUFFICIENT, E=50·나머지 척도 null, 누락 자기 문항 52개 |

두 응답 모두 차이 2개와 추천 3개를 유지한다. 정상 readings는 3개, 정보 부족 readings는 2개다. 추천 점수는 실제 코어의 SOFTTIDE=83.33333333333333, WARMORBIT=83.33333333333333, DAYDREAM=50.0이다. 이를 매칭 확률이나 실제 상대의 자격 통과로 해석하지 않는다.

[샘플 manifest](../../tests/fixtures/personal-analysis-api/sample-manifest.json)는 승인된 계약과 **UNAPPROVED 표시 정책**을 분리하고 입력·응답 해시와 참조 데이터 해시를 기록한다. 검토용 manifest이며 AI 응답 필드에 주입하지 않는다. 샘플에는 개인정보·인증 헤더·토큰·키가 없다.

[review_samples.py](review_samples.py)는 카탈로그의 첫 유효 자기 응답을 기본으로 사용하고 Q030=4/Q031=5/Q032=2/Q033=3, Q065=1/Q079=[apologize]/Q096=[approach]/Q098=close를 합성 설정한다. 나머지 상대 선호는 any다. 정보 부족 사례는 자기 E 척도와 설명/비교에 필요한 일부 문항만 측정한다. 실제 서비스 검증·계산 후 응답을 직렬화하며 유형·점수를 직접 작성하지 않는다.

```powershell
Set-Location 'C:\Users\SSAFY\.codex\worktrees\personal-analysis-146\S15P31E106\ai-server'
& 'C:\Project\S15P31E106\ai-server\.venv\Scripts\python.exe' -B -X utf8 -m app.personal_analysis_api.review_samples
```

생성기는 명시적으로 제안 정책을 선택한 직접 adapter 호출이다. HTTP 인증을 우회하는 운영 기능이나 공식 표시 정책 등록이 아니다.

## 5. 오류 코드·X-Request-Id 경계

오류 body는 code/message만 반환하고 Cache-Control: no-store를 적용한다. 요청 본문·토큰·원래 예외 문구는 응답/로그에 넣지 않는다.

| HTTP | code | 경계 |
| --- | --- | --- |
| 400 | INVALID_REQUEST | application/json 파싱 오류 또는 프레임워크의 400 |
| 422 | INVALID_REQUEST | DTO·누락/중복/카탈로그 값 오류. JSON 자료형/요청 본문 형식 오류 포함 |
| 409 | ANALYSIS_VERSION_UNSUPPORTED | 승인된 조합 밖의 버전. 코어 실행 전 |
| 500 | INTERNAL_ERROR | 계산 실패·예상 못 한 변환/프레임워크 예외. INSUFFICIENT로 바꾸지 않음 |
| 503 | SERVICE_UNAVAILABLE | 인증기 미설정/장애, 표시 정책 미설정, 참조 해시 불일치/파일 누락, 프레임워크 503 |
| 401/403 | AUTH_REQUIRED / ACCESS_TOKEN_EXPIRED / ACCESS_TOKEN_INVALID / FORBIDDEN | 실제 JWT 검증 및 고정 WWW-Authenticate, scope 부족 구분. 보안 모듈 문서 참고 |

인증 검사는 JSON 파싱 전에 실행한다. 실제 인증기 없이 테스트용 True 함수나 X-Test-Service-Token을 운영 설정에 연결하지 않는다. PyJWT[crypto] 기반 실제 검증기는 독립 테스트 앱에 주입하여 확인했다. 중복 Authorization/복수 토큰의 400도 별도 분류한다.

X-Request-Id는 단일 36자 UUID를 그대로 유지하고 누락·잘못된 값·중복 헤더는 새 UUIDv4로 교체한다. request.state.request_id와 응답 헤더에 같은 값을 넣으며 no-store를 적용한다. 인증이나 body의 analysisId와 별개다. 본문/토큰/키/예외 원문 로그는 추가하지 않았다. 이 문장의 원래 독립 스냅샷과 달리, 이번 develop 통합에서는 기존 main의 공통 미들웨어·위험 분석·매칭 이유 라우터를 보존한다. 개인 분석 운영 경로는 404다.

## 6. 변경 파일과 검증

| 파일 | 변경 이유 |
| --- | --- |
| [adapter.py](adapter.py) | 공식 버전/데이터 해시 대응, 실제 rows 기반 승인 근거, 요청·차이 index 문맥 전달 |
| [router.py](router.py) | 선택적 UUID 수신, 예상 밖 HTTPException의 무조건 400 변환 보완 |
| [proposals.py](proposals.py) | 15개 고정 ID 후보와 fit 비노출 차이 문장·priority 2/1을 기본 정책과 분리 |
| [review_samples.py](review_samples.py) | 검증된 100문항 요청과 실제 계산 JSON의 재생성 |
| [기존 API 테스트](../../tests/test_personal_analysis_api.py) | 73개 기존 회귀를 새 승인 상태/문맥 시그니처에 맞춤 |
| [추가 계약 테스트](../../tests/test_personal_analysis_api_contract.py) | 15분기, 차이 경계 200조합, 조건 근거, alias/해시 차단, 147개 JS 사례 보존, 헤더·샘플 검증 |
| [샘플 fixture 폴더](../../tests/fixtures/personal-analysis-api) | 합성 요청 2개·응답 2개·검토 manifest 1개 |
| README / [표시 정책](DISPLAY_POLICY_DRAFT.md) / [이전 합성 기록](SYNTHETIC_DISPLAY_REVIEW.md) | 승인 범위·미승인 문구·자료형·재현 및 검증 이력 정리 |

검증 결과:

- 최종 -146 worktree 전체: **464 passed in 14.20s** (기존 188 + 새 계약 검증 276).
- 최신 develop@49d4ee2 임시 archive + -146 API/테스트/fixture: **634 passed in 14.99s** (develop 기존 285 + API 349).
- 기존 운영 FastAPI의 정상·빈·잘못된 JSON 분석 POST는 모두 **404**, health 200, 운영 OpenAPI에는 분석 경로 없음.
- 샘플은 재생성 결과와 파일 내용/해시가 일치한다. SUCCEEDED/INSUFFICIENT 모두 코어 값·배열 순서 보존.
- 이번 허용 범위 밖 기존 Python/JS/JSON/requirements 보호 파일 **22개의 작성 전후 SHA-256 동일**을 확인했다.
- 이전 이력의 188 PASS / 최신 develop@19acd98 조합 358 PASS / 문서 내 1,840개 시나리오는 앞선 시점 기록이다. 이번 결과와 혼합해 집계하지 않는다.

```powershell
Set-Location 'C:\Users\SSAFY\.codex\worktrees\personal-analysis-146\S15P31E106\ai-server'
& 'C:\Project\S15P31E106\ai-server\.venv\Scripts\python.exe' -B -X utf8 -m pytest -q -p no:cacheprovider
```

Python 3.12.12 / FastAPI 0.150.0 / Pydantic 2.14.1 / pytest 8.4.2 / Node.js 24.18.0. CI·Docker 이미지·실제 BE 호출/저장·JWT·timeout/retry는 이번 로컬 검증으로 입증하지 않는다.

## 7. 이전 독립 작업 당시 차단 사항 (이력)

1. 구현한 공통 JWT 검증기와 실제 BE 발급기의 상호운용·키 배포 검증. ai:execute 확정 계약을 적용했으며 운영 조립은 나머지 승인·인증 연결 조건 충족 후 별도로 진행.
2. 15개 templateId와 차이 공통 ID·key namespace, label-only 문구 및 priority 2/1 승인. fit 노출 여부는 계산과 별도의 표시 선택이다.
3. SIS-A-1.0 아래 최종 표시 정책의 불변 식별·보관과 기존 저장 결과 원문 보존 방식 확정. 버전 매핑 승인만으로 미승인 문구를 확정한 것으로 보지 않음.
4. 실제 BE DTO 모의/실호출·오류·공개 필터·저장 round-trip 확인. X-Request-Id 실제 전파와 민감정보 없는 로그 상관관계 확인.
5. 제출 시점의 최신 develop 재확인과 팀의 branch 정리·사람 리뷰. 이번에는 merge/rebase·커밋·push·MR·운영 라우터 등록을 하지 않음.
