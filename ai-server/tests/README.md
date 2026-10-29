# AI 테스트 이관 및 실행 안내

관련 이슈: S15P31E106-181. 기준: `origin/develop`의 `c63e5beda9662fb2f48f68c79ccb4fdc121b404f`.

## 이관 전 조사와 경로 계획

`docs/`에서 AI 실행형 테스트는 아래 Node 스크립트 4개로 확인했다. Python AI 테스트는 이미 `ai-server/tests/`에 있다. `docs/database/`와 `docs/verification/`의 Python 도구는 ERD/API 검증용이므로 이번 AI 이관 대상이 아니다.

| 기존 경로 (`docs/handoffs/survey-20261021/mockup/` 기준) | 신규 경로 (저장소 루트 기준) | 용도 |
| --- | --- | --- |
| `experience100.test.mjs` | `ai-server/tests/node/experience100.test.mjs` | 100문항, 16유형, 대칭성, 결측, 개인 해석 회귀 |
| `religion-tattoo.test.mjs` | `ai-server/tests/node/religion-tattoo.test.mjs` | 종교·문신 조건 및 응답 버전 이관 회귀 |
| `wording-age.test.mjs` | `ai-server/tests/node/wording-age.test.mjs` | 문구 변경 전후 계산 동일성, 나이 경계 회귀 |
| `type-reference.test.mjs` | `ai-server/tests/node/type-reference.test.mjs` | 참고 유형과 실제 쌍 제외 조건 분리, 원본 해시 검증 |
| `fixtures/wording-baseline.json` | `ai-server/tests/fixtures/wording-baseline.json` | 합성 응답 16개와 기존 기대 결과 |
| `fixtures/v14-source-manifest.json` | `ai-server/tests/fixtures/v14-source-manifest.json` | 원본 핵심 모듈 6개의 SHA-256 기대값 |

위 6개는 `git mv`로 이동했다. 테스트 본문의 assertion·기대값과 JSON 내용은 유지하고 import·읽기 경로만 변경했다.

## 유지하는 원본과 다른 작업의 경계

| 경로 | 유지 이유 |
| --- | --- |
| `docs/handoffs/survey-20261021/mockup/dist/` | JS 참조 엔진·문항·브라우저 목업의 단일 원본. 계산식·정책·해시 불변 |
| `docs/handoffs/survey-20261021/mockup/preview.mjs` | 목업 미리보기 서버이며 테스트 실행기가 아님 |
| `docs/contracts/survey/questionnaire-SIS-Q-1.1-100.json` | 공식 설문 카탈로그. 테스트 전용 데이터가 아님 |
| `docs/contracts/matching/fixtures/mvp-policy-20261026-cases.json` | 공유 정책 사례 계약. 이번 Node 테스트 4개의 전용 fixture가 아님 |
| `docs/handoffs/survey-20261021/SOURCE-MANIFEST.json`, `VALIDATION.md`, `specs/` | 과거 인계 시점의 출처·검증·문서 스냅샷. 과거 기록을 현재 결과로 덮어쓰지 않음 |
| `ai-server/app/personal_analysis/` | -142 기능과 고정 참조 데이터. 변경하지 않음 |

Jira [-147](https://ssafy.atlassian.net/browse/S15P31E106-147)은 버전·출처를 고정한 양방향 매칭 Golden Vector와 BE/IoT 독립 검산 도구를 정리한다. -181은 기존 파일과 실행 진입점 이관만 수행한다. 새 벡터·정책 사례·허용오차를 만들거나 -147의 27개 사례·추가 감사 횟수를 이번 통과 개수에 합치지 않는다. -144·-146의 미병합 코드는 포함하지 않는다.

fixture는 새 경로에서만 관리한다. `js_personal_reference.mjs`도 같은 `wording-baseline.json`을 읽으며 docs에 사본을 남기지 않는다. `v14-source-manifest.json`은 테스트 전용 해시 기대값이어서 이동하지만 내용은 그대로 유지한다. `dist/` 및 개인 분석 배포용 JSON은 각각 기존 위치를 유지하며, 기존 provenance 검증으로 변경 여부를 확인한다.

## 실행 명령

Python 3.12 가상환경에 기존 `requirements.txt`를 설치하고 Node.js를 PATH에 둔다. 새 Python/npm 의존성은 추가하지 않았다. 아래 명령은 `ai-server/`에서 실행한다.

```text
python scripts/run_tests.py
```

저장소 루트에서는 다음과 같다. 다른 디렉터리에서는 스크립트의 절대 경로를 지정한다.

```text
python ai-server/scripts/run_tests.py
```

공통 실행기는 pytest 전체 → Node 테스트 파일 전체 → 수치 parity 집계 순서로 실행한다. Node 실행 파일이나 Node 테스트 파일이 없으면 실패한다. 각 하위 명령의 실패 코드도 즉시 반환하므로 CI가 실패를 성공으로 처리하지 않는다.

개별 확인은 `ai-server/`에서 다음 명령을 사용한다. Node 파일 경로를 명시하여 OS별 셸 wildcard 확장 차이를 피한다.

```text
python -m pytest --collect-only -q
python -m pytest -q
node --test tests/node/experience100.test.mjs tests/node/religion-tattoo.test.mjs tests/node/wording-age.test.mjs tests/node/type-reference.test.mjs
python -m scripts.check_personal_parity
```

## 실제 코드로 배우는 실행 구조

### pytest가 테스트를 발견하는 방법

기본 pytest 수집 규칙은 지정한 경로에서 `test_*.py` 또는 `*_test.py` 파일을 찾고, 그 안의 `test_` 함수 등을 수집하는 것이다. 여기서는 `test_health.py`, `test_personal_analysis.py`, `test_personal_analysis_fastapi.py` 3개 파일에서 총 115개를 수집한다. `@pytest.mark.parametrize`는 인수 조합별 테스트 ID를 만든다. 예를 들어 `test_required_outcomes`의 7개 입력은 7개의 수집 항목이다.

`test_all_js_fixtures_and_service_outputs_match` 내부의 147개 반복과 `test_5832_wish_fit_cases_and_thresholds_match_js` 내부의 5,832개 반복은 각각 하나의 pytest 항목 안에서 수행된다. 따라서 115개와 5,832개를 더해 테스트 총수를 세면 안 된다. Node의 `.test.mjs` 파일은 pytest의 Python 파일 수집 대상이 아니므로 별도로 실행해야 한다. 수집만 확인하는 명령은 `--collect-only`이며 실제 통과를 증명하지는 않는다.

`tests/conftest.py`는 pytest가 자동으로 불러오는 공통 fixture 파일이다. `js_reference`는 `scope="session"`이므로 테스트 세션에서 필요할 때 한 번 원본 JS oracle을 실행하고 결과를 재사용한다. Node가 없으면 `pytest.fail`로 실패하며 skip하지 않는다. `normal_request`는 그 결과 중 정상 합성 응답으로 각 테스트의 입력을 준비한다.

### Python import와 작업 디렉터리는 별개다

작업 디렉터리(CWD)는 일반 상대 파일 경로를 해석하는 기준이고, Python import는 `sys.path`에 있는 디렉터리에서 모듈을 찾는다. pytest의 `rootdir`은 설정·수집 기준이며 이것만으로 `app`을 import할 경로가 자동 추가되거나 CWD가 변경되는 것은 아니다.

현재 `tests/conftest.py`와 Python 테스트들은 `from app.personal_analysis ...`를 사용한다. `ai-server/`에서 `python -m pytest`를 실행하면 현재 디렉터리가 모듈 검색 경로에 포함되어 `app/`을 찾는다. 이번 조사 중 저장소 루트에서 같은 명령을 실행하면 `ModuleNotFoundError: No module named 'app'`로 수집에 실패하는 것도 확인했다.

`scripts/run_tests.py`는 다음처럼 스크립트 자체 위치로 AI 루트를 구한 뒤, 하위 프로세스의 CWD를 그곳으로 고정한다. `sys.executable`은 실행기를 호출한 Python을 그대로 사용하므로 활성 가상환경도 유지된다. 앱 코드나 전역 `PYTHONPATH`는 바꾸지 않는다.

```python
root = Path(__file__).resolve().parents[1]
subprocess.run([sys.executable, "-m", "pytest", "-q"], cwd=root, check=False)
```

기존 parity 도구는 `python -m scripts.check_personal_parity`로 실행한다. `python scripts/check_personal_parity.py`는 직접 실행한 스크립트 폴더가 import 기준이 되어 `app` 검색이 달라질 수 있다. 공통 실행기는 기존 모듈 실행 방식을 사용한다. `Path(__file__)`는 파일 위치, `Path.cwd()`는 실행 위치를 나타낸다는 차이를 기억하면 된다.

### Node.js 테스트는 파일 단위로 실행된다

이관한 4개 `.mjs`는 ES module이며 최상위에서 `node:assert/strict` assertion을 수행한다. `node:test`의 `test()`로 개별 사례를 등록한 구조는 아니다. `node --test`에 파일을 넘기면 각 파일을 테스트로 실행하고, assertion이 예외를 던지면 해당 파일이 실패한다. 따라서 출력의 `tests 4`는 파일 4개를 의미하고, 내부에서 비교하는 16유형·256쌍 등을 별도 테스트 개수로 세지 않는다.

`run_tests.py`는 `tests/node/*.test.mjs`를 Python `Path.glob()`으로 찾고 정렬한 뒤 Node 인수 목록에 넘긴다. 셸에서 문자열을 다시 해석하지 않아 실행 파일 경로에 공백이 있어도 처리할 수 있다. `.mjs`와 Node 내장 모듈만 사용하므로 `package.json`이나 npm 설치가 필요 없다.

ES module의 상대 `import`는 모듈 파일 위치를 기준으로 한다. 반면 기존 `fs.readFileSync('fixtures/...')`는 CWD 기준이어서 다른 폴더에서 실행하면 실패했다. `wording-age.test.mjs`는 다음처럼 바뀌었다.

```javascript
const old = JSON.parse(fs.readFileSync(
  new URL('../fixtures/wording-baseline.json', import.meta.url)
));
```

`import.meta.url`은 현재 모듈의 URL이다. 이를 기준으로 fixture URL을 만들면 실행 위치에 관계없이 같은 JSON을 읽는다. `type-reference.test.mjs`의 fixture·원본 해시 읽기도 같은 원리를 사용한다. Python/JS oracle인 `js_personal_reference.mjs`는 새 `./fixtures/wording-baseline.json` 하나를 공유한다.

### 테스트 fixture와 참조 데이터의 역할

`wording-baseline.json`은 과거 결과와 비교할 합성 응답·기대 결과를 담은 테스트 fixture다. 입력을 바꾸거나 기대 결과를 새 계산 결과로 덮어쓰면 회귀를 놓칠 수 있어 내용 그대로 이동했다. `v14-source-manifest.json`도 기존 원본의 SHA-256을 기대값으로 쓰는 전용 fixture다.

`docs/.../dist/engine100.mjs`는 실제 계산을 수행하는 참조 엔진이고, 공식 설문 JSON은 문항 계약이다. 둘 다 테스트 전용 데이터가 아니어서 이동하지 않았다. `app/personal_analysis/data/`의 JSON은 Python 실행에 필요한 고정 참조 데이터다. 기존 `test_reference_data_and_sources_are_unchanged`가 provenance의 원본 해시·패키지 데이터·카탈로그 일치를 검증한다.

pytest의 `@pytest.fixture`도 테스트 준비물을 제공하지만 JSON 파일과 같은 개념은 아니다. 여기서는 Python fixture 함수가 Node를 실행해 얻은 참조 결과를 준비하고 테스트에 주입한다.

### Jenkins에서 실행 위치가 달라질 때

Jenkins `inDocker(..., 'ai-server', ...)`는 컨테이너의 작업 디렉터리를 `$WORKSPACE/ai-server`로 정한다. 저장소 전체가 마운트되므로 `../docs/` 참조도 가능하다. `ai-server/`만 따로 복사하면 Python 단독 런타임은 동작해도 원본 JS를 읽는 대조 테스트는 실패한다.

AI 단계는 기존 `python:3.12-slim`, `apt-get`의 Node 설치, `requirements.txt` 설치를 유지하고 마지막 명령만 `python scripts/run_tests.py`로 바꿨다. Python 이미지에는 Node가 기본으로 없으므로 Node 설치 줄을 제거하면 공통 실행기가 실패한다. `-w`나 디렉터리를 잘못 지정하면 실행기 파일 자체를 찾을 수 없으므로 Jenkins의 `'ai-server'`도 유지했다.

`AI_TEST_INPUTS`는 테스트가 직접 읽는 `docs/.../dist/`와 공식 설문 JSON이다. 이 경로만 바뀌어도 `changed`에 `ai`를 추가한다. 기존 다른 파트의 변경 감지는 유지하고, `Jenkinsfile` 변경 시 전체 파트 검사도 유지한다. 앞으로 원본 참조 위치를 바꾸면 import뿐 아니라 이 감지 목록도 함께 검토한다.

## 실제 검증 결과 (2026-10-28)

Windows PowerShell 7.6.5, Python 3.12.12, Node.js 24.18.0, pytest 8.4.2. 기존 로컬 가상환경을 재사용했으며 의존성을 추가 설치하지 않았다. Windows 샌드박스에서는 기존 FastAPI TestClient 실행이 멈춰 subprocess·소켓 실행이 가능한 로컬 권한으로 같은 pytest를 수행했다.

| 검사 | 이관 전 | 이관 후 |
| --- | --- | --- |
| pytest 수집 | 115개 | 동일 ID·순서의 115개 |
| pytest 실제 실행 | 115 passed (6.06s) | 115 passed (5.86s) |
| Node 실제 실행 | 4 passed, skip/todo/cancelled 0 | 4 passed, skip/todo/cancelled 0 |
| parity 전체 응답 / 프로필 경계 / wishFit | 147 / 8 / 5,832 | 동일 |
| 숫자 비교 / 최대 절대 오차 | 367,518 / 0.0 | 동일 |
| 절대 / 상대 허용오차 | 1e-12 / 0 | 동일 |

pytest 115개와 Node 파일 4개를 합친 실행 항목은 이관 전후 **119개**다. 이번 단계에서 새 기능 테스트는 추가하지 않았다. 이관 후 저장소 루트에서 공통 실행기를 실행했고, 저장소 밖 CWD에서 Node 파일의 절대 경로를 넘겨도 4개 모두 통과했다. 이전 Node 출력의 성공 메시지도 유지됐다. 현재 테스트 코드에 skip 표식은 없다.

다음 추가 검사도 통과했다.

- JSON 2개를 `git show HEAD:<기존 경로>`의 바이트와 직접 비교하여 완전 동일함을 확인했다.
- Node 4개는 경로 변경을 되돌려 정규화하면 기존 소스와 완전 동일했다. assertion·기대값은 바뀌지 않았다.
- `ai-server/app/`, BE·FE·IoT, 원본 `dist/`, 설문 카탈로그, 공유 매칭 fixture, 인계 manifest·검증 기록·문서 스냅샷의 diff는 없다.
- 기존 Gradle에 포함된 Groovy 4.0.29로 Jenkinsfile의 AST 변환까지 문법을 검사했다. Jenkins DSL·플러그인 실행 검증은 아니다.
- Jenkinsfile의 실제 변경 감지 블록을 추출해 AI 코드·fixture·docs 원본·설문 JSON·BE·무관 README·Jenkinsfile·AI 중복 감지 8개 입력에서 예상 파트와 일치함을 확인했다.
- 실제 AI 셸 블록을 추출해 작업 위치·Node 설치·공통 실행기 연결을 확인하고 `bash -n`을 통과했다.
- `git diff HEAD --check`를 통과했다.

fixture SHA-256은 다음과 같다. 파일을 이동해도 해시는 같다.

```text
wording-baseline.json     8c662b2e1da7e62486c62ce3388cbe0f816d81973c28a354bd45e3cfac07d439
v14-source-manifest.json  e85a2e4224859146178568a6dcd3e99100f1352293f23dbdfa1717becda37d99
```

### 미확인 사항과 Linux 재현

Docker Desktop 시작을 시도했지만 `dockerDesktopLinuxEngine` 파이프가 없어 엔진에 연결하지 못했다. 따라서 이번 단계에서 Linux 컨테이너의 apt/pip 설치와 전체 테스트, 실제 Jenkins 잡 실행은 확인하지 못했다. 이를 Linux CI 통과로 표기하지 않는다. Jenkins/GitLab 외부 변경·실행 요청도 하지 않았다.

Docker Linux 엔진이 준비된 뒤 저장소 루트에서 다음 Bash 명령으로 같은 AI 단계의 의존성·공통 실행기를 확인할 수 있다. mount를 읽기 전용으로 두고 Python bytecode·pytest 캐시 생성만 막는다.

```bash
docker run --rm -v "$PWD:/workspace:ro" -w /workspace/ai-server \
  -e PYTHONDONTWRITEBYTECODE=1 -e 'PYTEST_ADDOPTS=-p no:cacheprovider' \
  python:3.12-slim sh -euc '
apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq --no-install-recommends nodejs >/dev/null
pip install -q --root-user-action=ignore -r requirements.txt
python scripts/run_tests.py
'
```

과거 계약 문서·인계 `VALIDATION.md`에 남아 있는 옛 명령은 고정 커밋 당시의 검증 기록이다. 과거 기록과 해시를 수정하지 않았으며 현재 실행은 이 문서와 mockup README를 따른다. -147 작업에서는 새 이관 경로를 사용하되 원본·공유 사례의 출처 SHA를 보존해야 한다.

## MR에 포함할 파일과 제출 범위

전용 worktree에서 `ai-refactor/test-migration-S15P31E106-181`을 사용한다. 아래 12개 논리 파일을 MR 범위로 검토한다. 이동 전 경로의 삭제도 해당 rename에 포함된다. 이 문서는 로컬 이관·검증 기록이며 실제 제출 커밋과 MR·Jenkins 결과는 해당 MR에서 확인한다.

| 경로 | 변경 이유 |
| --- | --- |
| `ai-server/tests/node/experience100.test.mjs` | 기존 테스트 이동·원본 import 경로 수정 |
| `ai-server/tests/node/religion-tattoo.test.mjs` | 기존 테스트 이동·원본 import 경로 수정 |
| `ai-server/tests/node/wording-age.test.mjs` | 기존 테스트 이동·원본 import 및 fixture 읽기 경로 수정 |
| `ai-server/tests/node/type-reference.test.mjs` | 기존 테스트 이동·원본 import 및 fixture·해시 읽기 경로 수정 |
| `ai-server/tests/fixtures/wording-baseline.json` | 내용 그대로 이동 |
| `ai-server/tests/fixtures/v14-source-manifest.json` | 내용 그대로 이동 |
| `ai-server/tests/js_personal_reference.mjs` | 공유 baseline의 새 경로 사용 |
| `ai-server/scripts/run_tests.py` | 실행 위치가 일정한 Python·Node·parity 공통 실행기 추가 |
| `Jenkinsfile` | 공통 실행기 호출·docs 원본의 AI 변경 감지 |
| `ai-server/README.md` | 현재 전체 실행 명령·테스트 안내 링크 |
| `docs/handoffs/survey-20261021/mockup/README.md` | 기존 명령을 새 실행 위치로 연결 |
| `ai-server/tests/README.md` | 조사·이관표·실제 검증·학습 자료·잔여 위험 |

제출 대상은 `develop`이며 Linux/Jenkins 확인과 AI 교차 리뷰가 필요하다. 자동 승인·머지·배포는 수행하지 않는다. 기존 -144/-146 worktree와 원래 작업 폴더의 `audit-experiments/`는 그대로 남겨 두었다.
