# 개인 분석 Python 최소 구현

2026-10-28 최종 검토. Jira [S15P31E106-142](https://ssafy.atlassian.net/browse/S15P31E106-142).
계산 원본과 최초 분기 기준은 `origin/develop@f5224c9aad434e4e1b54df8f3f17d6f84eb48897`이다.
제출 브랜치는 `ai-feature/personal-analysis-core-S15P31E106-142`, MR 대상은 `develop`이다.
최신 fetch로 확인한 `origin/develop@6474d34224d53cc2dbd6ec2d198ab1040407d95b`는 41개 커밋 앞서 있으나
`ai-server/`와 이 구현이 참조하는 100문항 catalogue·JS 원본에는 변경이 없었다.
이 구현은 계산 코어이며 BE 내부 HTTP 계약·서비스 인증·DB 저장·운영 배포 완료를 의미하지 않는다.

## 실행

Python 3.12와 기존 `requirements.txt`를 사용한다. 새 의존성은 없다.
저장소의 `ai-server` 폴더에서 가상환경을 활성화한 뒤 실행한다.

```powershell
python -m app.personal_analysis examples/personal-normal.json
python -m app.personal_analysis examples/personal-insufficient.json
python -m pytest -q
python -m scripts.check_personal_parity
```

처음 두 명령은 합성 입력을 계산하고 결과 JSON을 UTF-8 표준 출력으로 반환한다.
입력 파일 오류·검증 오류는 종료 코드 2, 계산 장애는 3이며 표준 오류에는 코드만 출력한다.
실사용자 원응답 파일을 예제 폴더나 Git에 추가하지 않는다.

Python에서 호출하는 진입점:

```python
import json
from pathlib import Path
from app.personal_analysis import analyze_personal

payload = json.loads(Path("examples/personal-normal.json").read_text(encoding="utf-8"))
result = analyze_personal(payload)
print(result.status, result.typeStatus, result.primaryType)
# SUCCEEDED ASSIGNED STEADYSPARK
```

`analyze_personal`은 검증 후 실제 계산을 실행한다. FastAPI·네트워크·DB·GMS/LLM을 호출하지 않는다.
`app.main`은 기존 `/health`만 노출한다. `tests/test_personal_analysis_fastapi.py` 안에서만
`/_test/personal-analysis`를 연결한다. 테스트의 200/422/500 응답은 미합의 운영 HTTP 코드가 아니다.

## 파일과 계산 근거

| 경로 | 역할 |
| --- | --- |
| `app/personal_analysis/core.py` | 7척도, 유형 판정, readings, 자기·선호 차이, 참고 유형의 Python 계산 |
| `dto.py`, `validation.py`, `service.py` | 로컬 DTO, 전체 스냅샷 검증, 결과·오류 분리 |
| `reference.py`, `data/` | 원본에서 추출한 유형·문구·영역, 원문 catalogue 사본, SHA-256 출처·무결성 검사 |
| `__main__.py`, `examples/` | 파일 기반 실행과 기존 합성 자료에서 얻은 정상·정보 부족 입력 |
| `tests/js_personal_reference.mjs` | 수정하지 않은 기존 JS를 직접 실행하는 독립 기준 |
| `tests/test_personal_analysis*.py`, `tests/conftest.py` | 값·문구·순서·상태, 검증·장애·로컬 HTTP·앱 단독 패키지 검사 |
| `scripts/check_personal_parity.py` | JS 대조 건수와 실제 최대 수치 오차 집계 |
| `scripts/export_personal_reference.mjs` | 원본을 읽어 AI 패키지 데이터로 추출. 원본 해시 변경 시 재발행 검토 요구 |

계산 원본은 `docs/handoffs/survey-20261021/mockup/dist/engine100.mjs`,
`initial-profiles.mjs`, `type-recommendations100.mjs`, `data100.mjs`, `wording100.mjs`다.
기존 `wording-baseline.json`의 16개 합성 응답과 `demoAnswers`를 재사용한다.
프로토타입·한글명·꽃·가중치·문구를 새로 창작하지 않았다.
runtime은 JS나 저장소 `docs/`에 의존하지 않으며, 대조 테스트만 Node.js를 요구한다.

척도는 구성 문항 하나라도 unknown이면 해당 척도만 null이다. 역채점 `6-x`,
`(평균-1)*25`, 유형의 고정 가중치·8영역·coverage+1e-10 검사·동점 거리 1e-10·경계 3점을 유지한다.
유형별 순위는 원시 거리와 코드, 참고 유형은 원시 점수와 코드 순이다.
Python 3.12 `sum()`의 보상 합산 대신 JS reduce와 같은 순차 합산을 사용한다.
자기 유사성 근거의 표시 문구만 JS `Math.round`와 동일하게 처리한다. 판정 전 반올림은 없다.

readings와 차이 설명은 JS 구조·문구·안정 정렬 순서 그대로다. 원본에 없는 templateId·차이 priority·
추가 해석 문장은 만들지 않는다. 참고 유형은 `typeRecommendations`의 상위 최대 3개다.
과거 가상 양방향 `recommendations`, `pair`, 실제 상대 조건·나이·Q97 제외 조건은 사용하지 않는다.

## 로컬 입출력과 버전의 범위

입력은 `surveyVersion`, `calculationVersion`, `answers`, `metadata` 네 필드다.
기존 협의 초안의 requestId/sessionId/revision/completedAt/manifest를 포함하는 내부 HTTP DTO와 다르다.
현재 지원 설문은 `SIS-Q-1.1-100` 하나다.
제안 중인 103문항 개편과 Q101~Q103은 포함하지 않는다.
`calculationVersion=local-personal-js100-port-1`은 이 로컬 Python 구현을 구분하는 식별자이며,
승인된 analysisVersionCode·releaseId·matchingVersion이 아니다.
협의 초안의 `DRAFT-PERSONAL-REF-1`이나 `latest`를 자동 대체하지 않는다.

100개 questionId를 catalogue의 ordinal로 매핑한다. 배열 순서는 계산에 영향을 주지 않는다.
누락·중복·잘못된 ID·null·bool·숫자 문자열·범위 밖 값·빈 배열·중복 선택·선택 개수 초과를 거절한다.
JS와 JSON의 숫자 의미에 따라 유효한 1.0은 1과 동일하다. NaN·Infinity는 허용 답변이 아니다.
unknown은 유효한 미측정 응답, any는 Q061~Q097에서만 허용하는 무제약 응답이다.
항목 자체가 없는 미응답은 완료 스냅샷 오류다. 잘못된 값을 삭제하거나 중간값으로 보충하지 않는다.

metadata는 ownAge/desiredAgeMin/desiredAgeMax 세 키를 요구하며 각 값은 null 또는 19~100 정수다.
세 값 모두 null인 입력은 유효하다. 전체 metadata 생략/null과 구별한다.
Min > Max는 기존 스키마가 금지하지 않아 이번 개인 계산에서도 새 의미 검증을 추가하지 않는다.
나이는 개인 계산에 사용하지 않는다. 추가 metadata 키는 로컬 DTO에서 거절한다.
이는 additionalProperties:true인 공개 SurveyMetadata보다 엄격한 로컬 선택이므로 내부 계약 채택 시 합의해야 한다.

현재 catalogue의 Q097은 smoking/children/religion/tattoo/**age** 다섯 선택지를 가진다.
새 네 선택지 catalogue는 발행하지 않았으며 원문·버전·기존 age 의미를 보존했다.

결과는 7개 scores, 원본 구조 classification/readings/selfPreferenceDifferences/typeReferences,
원본 reference version, missingQuestionIds를 포함한다. typeStatus는 원본 상태를 대문자로 표시한다.
partner-preferences → PREFERENCE_FIT, self-similarity → SELF_SIMILARITY는 로컬 표시 대응이다.
유형 정보가 충분하면 ASSIGNED/BORDERLINE/TIED 모두 SUCCEEDED로 반환하고,
부족하면 INSUFFICIENT로 반환한다. 이 전체 상태 집계는 BE가 채택해야 할 로컬 서비스 규칙이다.
자기 유형 부족과 상대 선호 참고 유형 3개는 공존할 수 있다.
원본 ranking·evidence·대표값은 검토용 진단 구조이며 공개 AnalysisResult DTO에 그대로 넣을 수 없다.

| 사례 | 실제 결과 |
| --- | --- |
| 정상 STEADYSPARK 합성 응답 | SUCCEEDED / ASSIGNED / STEADYSPARK / 참고 3개 |
| Q001=unknown | E=null, 나머지 유지 / SUCCEEDED |
| 100개 모두 unknown | INSUFFICIENT / 모든 척도 null / 참고 없음 |
| 자기 응답 unknown, 상대 선호 유지 | INSUFFICIENT / PREFERENCE_FIT 참고 3개 |
| 모든 유형 관련 선호 any | 자기 정보 충분 시 SELF_SIMILARITY 참고 3개 |
| 기존 중간값 합성 응답 | SUCCEEDED / TIED / primaryType=null / 공동 후보 16개 |
| 경계 합성 응답 | SUCCEEDED / BORDERLINE / 단독 primary 유지 |
| Q101 / 중복 / 누락 | INVALID_QUESTION_ID / DUPLICATE_QUESTION_ID / MISSING_ANSWERS 예외 |
| 미지원 설문 / 계산판 | UNSUPPORTED_SURVEY_VERSION / UNSUPPORTED_CALCULATION_VERSION 예외 |
| 잘못된 답변 / DTO | INVALID_ANSWER / INVALID_REQUEST 예외 |
| 실제 계산 예외 | CALCULATION_FAILED 예외, 정보 부족으로 변환하지 않음 |

오류에 제출한 값·토큰·metadata를 넣지 않으며 계산 모듈은 로그를 기록하지 않는다.
서비스 인증이나 저장 성공을 대신하는 mock은 없다.

DTO 인스턴스도 `model_validate()`로 직접 검증한다. 공통 DTO에 `revalidate_instances="always"`를
설정해 중첩 Answer·SurveyMetadata도 다시 검사한다. 검증 전 `model_dump()`는 호출하지 않는다.
잘못 구성된 객체를 먼저 직렬화하면 Pydantic 경고의 input_value에 입력값이 포함될 수 있기 때문이다.
검증 오류는 입력값을 제외한 `INVALID_REQUEST`로 변환하고 원인 예외의 출력도 억제한다.

## 검증 기록

Windows PowerShell 7.6.5, Python 3.12.12, Node.js 24.18.0.
기존 가상환경의 FastAPI 0.150.0 / Pydantic 2.14.1 / pytest 8.4.2를 사용했다.
새 설치나 requirements 변경은 없었다.

JS↔Python은 정상·모든 unknown·일부 척도 unknown·단독·경계·공동 1위·두 참고 모드·
Q97 age·허용폭·고정 seed 합성 사례 147개와 프로필 경계 8개, wishFit 5,832개를 비교했다.
숫자 367,518개를 대조했으며 실제 최대 절대 오차는 **0.0**이다.
허용 절대 오차는 **1e-12**, 상대 허용 오차는 **0**이다.
모든 객체 키·배열 순서·상태·문구는 정확히 비교한다. 차이를 반올림해 숨기지 않는다.
coverage 0.8의 양쪽, gap 3점의 양쪽, 동점 허용 오차의 양쪽도 원본 JS와 비교했다.

기존 JS의 experience100/type-reference/wording-age/religion-tattoo 테스트 4개도 직접 실행해 모두 통과했다.
기존 협의 초안 examples의 정상·정보 부족·일부 척도 누락·선호만 존재·자기 유사성 5개는
로컬 DTO로 변환한 뒤 scores/status/typeStatus/primaryType이 초안 예시와 같음을 별도로 확인했다.
초안의 추가 문구·templateId·HTTP/DB/멱등성 기대값을 구현 완료로 간주하지 않는다.

2026-10-28 재실행한 전체 `python -m pytest -q` 결과는 **115 passed**다. 기존 `/health` 테스트도 포함한다.
입력값 비노출 수정의 회귀 검증은 39개다. 정상 DTO 3개와 잘못된 DTO 36개를 검사한다.
`model_construct()`·`model_copy()`·생성 후 값 변경, 최상위·중첩 DTO, dict 안의 DTO를 포함하며
가상 민감 문자열이 경고·stdout·stderr·출력되는 예외·DEBUG 수준 로그에 포함되지 않는지 확인한다.
147개 정상·경계·정보 부족 합성 입력에서 dict와 DTO 진입점의 전체 결과 JSON도 같음을 확인한다.
Windows 샌드박스에서는 기존 TestClient도 asyncio socketpair 생성 중 멈췄고 임시 폴더 접근도 제한됐다.
로컬 테스트 권한으로 같은 pytest 명령을 다시 실행해 확인했다.
표준 명령은 `python -m pytest -q`이며 코드에 샌드박스 우회용 설정을 추가하지 않았다.

## Docker와 남은 합의

기존 Dockerfile은 변경하지 않았다. `.dockerignore`에 고정 JSON 3개와 부모 폴더만 허용했다.
`app/`만 임시 폴더에 복사하고 PATH에서 Node.js를 제거한 subprocess에서 정상 계산이 통과했다.
2026-10-28 로컬 권한으로도 dockerDesktopLinuxEngine 파이프가 없어 Docker 엔진에 연결하지 못했다.
이번 재검증에서 이미지 빌드·기동은 실행하지 못했으며 성공 여부는 미확인이다.
Docker Desktop Linux 엔진을 실행한 뒤 아래 명령으로 확인할 수 있다.

```powershell
docker build --tag tingle-ai-personal-local .
docker run --rm --entrypoint python tingle-ai-personal-local -c "from app.personal_analysis.reference import CATALOGUE; print(CATALOGUE['questionCount'])"
```

BE와 다음을 합의한 뒤 HTTP 어댑터를 연결한다.

1. 내부 경로·메서드·서비스 인증·인증 키 운영·타임아웃·재시도·오류의 HTTP 대응.
2. 완료 원응답의 소유권·revision·완료 검증 책임, requestId/sessionId/completedAt의 필요성.
3. survey/wording/contentHash 검증과 불변 계산 묶음의 식별자, 공개 releaseId/전체 Manifest/DB version_code 대응.
4. 공개 Reading/TypeReference와 원본 결과의 변환: templateId·evidence ID·추가 차이 설명·coverage/ranking 공개 여부.
5. 전체 결과 상태 집계, metadata 허용 목록, 멱등성·저장·작업 실패 및 재시도 책임.

후속 연동은 다음 이슈에서 진행한다.

- [BE -127](https://ssafy.atlassian.net/browse/S15P31E106-127), 가을: 내부 필드·경로·인증·timeout·오류 계약과 모의 서버.
- [AI -146](https://ssafy.atlassian.net/browse/S15P31E106-146), 성현: -142 리뷰/병합과 -127 합의 후 HTTP DTO 어댑터·FastAPI 라우터·계약 테스트.
- [BE -115](https://ssafy.atlassian.net/browse/S15P31E106-115), 가을: 실제 AI 호출·재시도·분석 결과 저장과 BE 연동 검증.

팀 컨벤션에서 AI는 1인 파트이므로 계산 코어 기본 리뷰어는 종무(IOT)다.
서버 연결 검토는 관련 BE 담당자에게 요청하고, 사람의 리뷰·승인 후 작성자가 아닌 팀원이 병합한다.
이 MR은 Q101~Q103, 양방향 pair 계산, 운영 API를 포함하지 않으며 자동 승인·병합·배포를 수행하지 않는다.
