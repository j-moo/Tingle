# AI 문서와 참조 입력 보존 안내

2026-10-29, 저장소 루트 `docs/`의 convention 외 자료가 삭제될 상황에 대비해
AI 개발·검증에 필요한 파일을 `ai-server/` 안에 보존했다.
S15P31E106-185는 루트 원본을 삭제·수정하지 않고 보존 자료와 참조 경로만 제출한다.

## 먼저 읽을 문서

| 목적 | 보존 위치 |
| --- | --- |
| 현재 MVP 범위와 결정 우선순위 | [MVP 명세](docs/specifications/functional-spec-mvp.md), [ERD 기준 범위 결정](docs/specifications/reviews/erd-mvp-scope-20261028.md) |
| AI 시작 질문·위험 대화 감지와 동의 | [AI 기능 명세](docs/specifications/functional-spec-v3/ai.md) |
| 매칭 이유 생성·프롬프트·모델 선택·실패 처리 | [매칭 이유 설명 계약](docs/contracts/api/ai-match-explanation.md) |
| 설문과 개인 성향 계산 | [설문 안내](docs/survey-analysis/README.md), [계산·분석 정의](docs/survey-analysis/personality-scoring-v1.md) |
| 두 사람의 매칭 판정과 검증 사례 | [매칭 규칙](docs/contracts/matching/rules-and-cases.md), [합성 정책 사례](docs/contracts/matching/fixtures/mvp-policy-20261026-cases.json) |
| AI·BE 입출력과 저장 구조 | [API 안내](docs/contracts/api/README.md), [OpenAPI](docs/contracts/api/openapi.json), [ERD](docs/database/erd.md), [설문 버전 계약](docs/contracts/survey/versioning.md) |
| 키링에 전달할 성향·매칭 데이터 | [키링 데이터 구조](docs/contracts/iot/keyring-data-structures-v1.md) |
| MAT-04 테스트 결과와 남은 한계 | [최초 감사](docs/verification/mat04-145/validation-report.md), [최소 보완](docs/verification/mat04-145/minimal-hardening-report.md) |
| 현재 Python 구현·테스트 실행 | [개인 분석 구현](app/personal_analysis/service.py), [공통 테스트 실행기](scripts/run_tests.py), [매칭 설명 구현](app/match_explanation/service.py) |

## 보존 범위와 원본의 의미

- `docs/contracts/`, `docs/survey-analysis/`: AI 계약·설문·계산·매칭 정의와 연결된 공통 계약.
- `docs/specifications/`, `docs/database/`, `docs/verification/`: 연결 문서를 따라 필요한 명세·정책·ERD·검증 근거를 보존했다. API 연동과 과거 판단의 근거를 잃지 않도록 관련 공통 자료도 포함했다.
- `docs/handoffs/survey-20261021/specs/`: 설문 인계 당시 계약 스냅샷. 현재 명세보다 우선하지 않는다.
- `docs/handoffs/survey-20261021/mockup/dist/`: AI 테스트와 데이터 추출에 필요한 JS 원본 7개와 연결 근거인 `app100.mjs`를 보존했다. 이미지·HTML·미리보기 서버는 포함하지 않아 완전한 목업 실행본은 아니다. 함께 보존한 과거 README의 미리보기 명령도 현재 보존본에는 적용하지 않는다. 기존 디렉터리 이름은 출처와 상대 import를 보존하기 위한 것이며 새 빌드 산출물이 아니다.
- `tests/fixtures/mat04-145/synthetic_cases.json`: 기존 MAT-04 합성 입력을 AI 폴더 안에도 보존했다.

자료 155개와 각 SHA-256·원래 경로·기준 커밋을 [보존 목록](docs/PRESERVATION.json)에 기록했다.
원문은 그대로 복사했고, 컨벤션 링크 4곳은 남아 있는 저장소 루트 `docs/convention/`으로 연결했다.
목업 README의 테스트 안내 링크 1곳도 이 보존 안내로 연결했다. 문서 정리 브랜치에서 삭제 예정인 기존 README에 의존하지 않는다.
이 차이는 목록의 `linkAdjustments`와 원본/보존본 해시에 기록했다.
원래 미추적 상태였던 검증 보고서 2개와 합성 JSON도 포함했다.

과거 문서의 초안·미합의·미구현 상태와 실험 결과는 작성 당시 기록이다.
복사 자체가 정책 승인이나 실제 모델의 안전성 검증을 뜻하지 않는다.
외부 Jira·Notion·GitLab 링크의 대상은 이 폴더에 내려받지 않았다.
과거 검증용 Python·SQL 파일은 근거 보존용이며 현재 환경에 자동 실행하지 않는다.
`SOURCE-MANIFEST.json`은 당시 전체 목업의 목록이므로, 이번에 제외한 화면 파일도 나열한다.

## 루트 docs 없이 실행하기

-185 제출본의 실행형 테스트·데이터 추출은 `ai-server/reference/survey-20261021/`의 원본 7개와 설문 JSON을 읽는다. 매칭 설명 테스트는 `tests/fixtures/explanation-contract.json`의 고정 스키마를 읽는다. `docs/`는 근거 보존용 스냅샷이다. 문서 정리 브랜치 `48f4df0`와 같은 경로·바이트를 적용해 충돌을 줄였다.
실행 코드는 기존 계산·프롬프트·판정·패키지 데이터와 provenance 해시를 유지한다.
`provenance.json`의 `docs/...` 키는 기존 출처를 유지하며, 실행 스크립트가 위 `reference/` 경로로 매핑한다. 원본 해시·계산식·판정은 바꾸지 않는다.
Jenkinsfile은 이 MR에서 수정하지 않는다. 기존 `ai-server/` 변경 감지가 새 자료도 포함하며, 문서 정리 브랜치의 Jenkins 변경과 겹치지 않는다.
운영 Docker 이미지는 기존처럼 앱과 패키지 JSON만 포함한다.

`ai-server/`에서 기존 가상환경과 Node.js를 사용한다. 새 의존성은 없다.

```powershell
python scripts/verify_docs.py
python scripts/run_tests.py
```

원본 삭제 전에는 다음 명령으로 원본과 보존본을 각각 기록된 해시와 비교한다.
링크 조정 외 내용 변경이 없고 원본이 유지됐는지 확인할 수 있다. 제출 작업 공간의 최신 develop과 보존 당시 스냅샷은 다를 수 있다. 당시 원본을 확인하려면 `--source-root`로 그 원본 폴더를 지정한다.

```powershell
python scripts/verify_docs.py --check-originals
```

보존본을 나중에 수정하면 목록의 해시와 달라져 검증이 실패한다.
내용·버전·영향을 검토하고 보존 목록도 함께 갱신한다.
보존본은 `docs/.gitattributes`로 줄바꿈 정규화를 막아 커밋·checkout 뒤에도 기록된 해시를 유지한다.

## 보존 준비 당시 검증 이력 (-185 전용 브랜치 결과와 구분)

기존 Python 3.12.12 가상환경과 Node.js 24.18.0을 사용했다.
샌드박스에서는 기존 subprocess·TestClient 테스트가 진행되지 않아 중단했고,
로컬 실행 권한으로 변경 전 기준 검증과 변경 후 격리 검증을 수행했다.

- 보존 자료 155개 해시·원본 보존 검사와 내부 문서 링크 443개 검사가 통과했다. 컨벤션 링크 4개는 남아 있는 루트 문서를 참조한다.
- `ai-server/`만 임시 폴더에 복사했다. 임시 저장소 루트에는 `docs/`와 `tests/`를 만들지 않았고 `.env`·가상환경·캐시는 복사하지 않았다.
- 이 복사본에서 Python 테스트 342개와 Node 테스트 4개가 모두 통과했다.
- JS 대조는 전체 응답 147건·프로필 경계 8건·선호 적합도 5,832건, 수치 비교 367,518회에서 최대 절대 오차 0이었다.
- 격리 복사본의 참조 데이터 추출 스크립트도 성공했고, 실행 전후 배포용 JSON 3개의 SHA-256이 모두 같았다.

원본 `docs/`와 루트 합성 fixture는 수정하지 않았다. 실제 원격 문서 삭제·Jenkins 실행·외부 GMS 호출·서비스 배포는 수행하지 않았다.

## -185 제출 범위와 머지 순서

- 최신 기준은 `origin/develop@97056b849ea7edb1aab6b5767105976650340d8e`이며, 이 기준에는 -181이 병합됐고 광범위한 루트 docs 정리는 아직 반영되지 않았다. 다만 !97의 IoT 계약 3개 삭제는 이미 반영됐고, -185는 이를 되돌리지 않는다.
- 삭제 작업 브랜치 `doc-docs/functional-spec-complete-S15P31E106-16@48f4df0`와 실행형 참조 파일의 바이트·경로를 맞췄다. 루트 원본과 기존 README·Jenkinsfile은 수정하지 않는다.
- -181에서 이관한 Node 테스트 4개와 공유 fixture 2개를 중복 추가하지 않는다. 기존 테스트의 import/원본 읽기 경로만 수정한다.
- 별도 MR !101 (-184)의 생성 서비스 안전 보완과 미추적 -145 테스트는 포함하지 않는다. 위 342개 결과는 이들이 있던 원래 작업 폴더의 과거 기록이다. -185 전용 검증 수는 제출 검증 보고서를 따른다.
- 보존 당시 API README/OpenAPI와 최신 develop의 두 파일에는 매칭 설정 후속 변경 차이가 있다. 보존 자료는 당시 스냅샷이며 최신 계약으로 자동 승격하지 않는다.
- 권장 순서는 이 보존 MR을 먼저 검토·병합한 뒤 다른 팀의 docs 정리 작업을 병합하는 것이다. 반대 순서라면 최신 develop을 다시 반영하고 동일 검증·충돌 검사를 수행해야 한다. 담당자 간 머지 순서 조정이 필요하다.
- 다른 팀의 삭제 커밋을 되돌리거나 자동 머지하지 않는다.
