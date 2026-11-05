# S15P31E106-185 제출 검증

검증일: 2026-10-29 (Asia/Seoul). Windows PowerShell 7.6.5, Python 3.12.12, Node.js 24.18.0. 기존 의존성만 사용했다.

## 기준과 제출 범위

- 전용 브랜치: `ai-refactor/docs-preservation-S15P31E106-185`.
- fetch한 대상: `origin/develop@97056b849ea7edb1aab6b5767105976650340d8e`.
- 진행 중인 문서 정리: `doc-docs/functional-spec-complete-S15P31E106-16@48f4df0`.
- 이 develop에는 -181 이관 커밋 `dc0481e`가 병합돼 있다. 광범위한 문서 정리는 아직 develop에 반영되지 않았다. !97의 IoT 계약 3개 삭제는 이미 반영됐으며 이 삭제를 유지한다.
- 루트 `docs/`, 기존 README, `Jenkinsfile`, AI 앱 코드·의존성은 수정·삭제하지 않는다.
- 원래 작업 폴더의 미추적 `audit-experiments/`, 루트 `tests/`, -145 테스트는 가져오지 않았다. 별도 !101 (-184) 안전 보완 코드도 포함하지 않는다.

## 보존과 민감정보

- PRESERVATION 목록의 155개(보존 docs 자료 154개와 MAT-04 합성 JSON 1개) SHA-256이 통과했다. 원래 작업 폴더의 원본 해시도 확인했다.
- 내부 문서 링크 443개가 유효하다. 컨벤션 링크 4개는 삭제 대상이 아닌 루트 `docs/convention/`을 참조한다.
- 원문 의미는 보존했다. 컨벤션·테스트 안내 링크 5곳만 새 위치에 맞췄다. `docs/.gitattributes`는 Git checkout 시 해시가 달라지지 않도록 원본 바이트를 유지한다.
- 보존 자료·실행형 참조·스키마 fixture·신규 안내/검증 도구에서 개인키, 공급자 API 키, JWT, URL 내 자격증명, 실제 Bearer 값, 전화번호, 주민번호 및 이메일 패턴을 검사했다.
- 발견된 이메일 1건은 채팅 명세의 `example.com` 예시다. 실제 자격증명·연락처 패턴과 `.env`, `local.properties`, 개인키 파일은 발견하지 못했다. 패턴 검사와 확인 범위의 결과이며 모든 형태의 민감정보 부재를 수학적으로 증명하지는 않는다.
- 과거 API README/OpenAPI는 스냅샷이다. 최신 develop의 매칭 설정 후속 변경이 두 파일에 있으므로 최신 계약으로 자동 승격하지 않는다.

## -181 및 문서 정리와의 겹침

- -181의 Node 테스트 4개는 새 파일로 중복 추가하지 않고 기존 파일의 참조 경로만 바꿨다. 경로 문자열을 정규화하면 원본과 동일하다.
- `wording-baseline.json`, `v14-source-manifest.json`은 develop 원본과 바이트가 같고 변경 목록에도 없다.
- `Explanation` 고정 fixture는 최신 develop OpenAPI의 해당 스키마와 같다. 기존 assertion은 유지했다.
- 문서 정리 브랜치와 겹치는 실행 파일 8개는 그 브랜치와 동일한 바이트다. JS 원본 7개·설문 JSON·고정 스키마도 같은 경로와 바이트로 준비했다.
- 삭제 예정인 기존 AI README와 Jenkinsfile은 -185에서 수정하지 않아 수정/삭제 충돌을 줄였다.
- 최종 제출 커밋을 대상으로 한 `git merge-tree` 결과와 GitLab 충돌 상태는 MR 설명에서 확인한다.

## 루트 docs 없는 실제 실행

`ai-server/`만 임시 폴더에 복사했다. 임시 루트에는 `docs/`와 `tests/`가 없었다. `.env`, 가상환경, 캐시는 복사하지 않고 기존 Python 의존성과 Node.js를 사용했다.

| 검사 | 결과 |
| --- | --- |
| 보존 해시·링크 | PASS: 155개, 내부 링크 443개 |
| pytest | 249 passed, skip 없음 |
| Node 내장 테스트 | 4 passed, skip/todo/cancelled 없음 |
| parity 전체 응답 / 프로필 경계 / wishFit | 147 / 8 / 5,832 |
| 수치 비교 / 최대 절대 오차 | 367,518 / 0.0 |
| JS 참조 데이터 재추출 | PASS, 기존 배포 JSON 3개의 해시 유지 |
| -181 assertion/fixture 보존 | PASS |
| git diff --check | PASS |

이 249개가 최신 develop 기반 -185의 검증 수다. AI_DOCS에 보존된 342개 결과는 -184/-145 변경이 있던 이전 작업 폴더의 이력이며 이번 제출 결과로 합산하지 않는다. 샌드박스의 subprocess/TestClient 제약 때문에 로컬 실행 권한으로 실행했다. GMS 실호출·운영 배포는 없다.

## 적용 순서와 CI

이 MR을 먼저 검토·병합하고 다른 팀의 docs 정리를 병합하는 순서를 권장한다. 반대 순서라면 최신 develop을 반영해 같은 검사와 충돌 검사를 다시 해야 한다. 담당자 간 머지 순서 조정이 필요하다. 다른 팀의 삭제 커밋을 되돌리거나 자동 머지하지 않는다.

MR 제출 후 Jenkins/GitLab CI 결과는 MR에서 확인하며, 로컬 통과를 CI 통과로 간주하지 않는다.
