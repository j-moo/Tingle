# GitLab 컨벤션

v0.2.0, 2026.10.19 (소개팅 앱·IoT 팀 구성, 교차 리뷰와 공통 명세 관리 반영)

키링과 앱을 연결하는 소개팅 서비스 6인 팀의 GitLab 저장소 사용 규칙이다. 각 규칙에는 `[rule-id]` 를 붙여서 MR 리뷰에서 링크로 가리킬 수 있게 한다. 팀 역할과 이슈 관리 규칙은 [Jira 컨벤션](jira-convention.md)을 따른다. Jira 프로젝트 키는 `S15P31E106` 이다.

## 파일 공통 요건

### 파일 인코딩은 UTF-8
_[encoding-utf8]_

모든 소스, 텍스트 문서 파일의 인코딩은 UTF-8로 통일한다. (네이버 Java 컨벤션 `[encoding-utf8]` 과 같다)

### 모델 가중치와 데이터셋은 커밋하지 않는다
_[no-large-artifacts]_

LoRA 어댑터, 모델 가중치, 학습 데이터셋, `.env` 는 `.gitignore` 에 넣고 GPU 서버나 스토리지에 둔다. 저장소에는 위치와 버전만 문서로 남긴다.

### AI 에이전트 지침 파일은 커밋하지 않는다
_[no-agent-files]_

`AGENTS.md`, `CLAUDE.md`, `.claude/` 는 개인이 쓰는 AI 코딩 도구의 지침·설정 파일이므로 `.gitignore` 에 넣고 GitLab 에 올리지 않는다. 하위 디렉토리에 있는 것도 포함되도록 경로 없이 이름만 적는다.

```
AGENTS.md
CLAUDE.md
.claude/
```

이미 커밋된 파일은 `.gitignore` 에 넣어도 계속 추적되므로 추적에서 먼저 뺀다. 로컬 파일은 지워지지 않는다.

```
git rm -r --cached --ignore-unmatch AGENTS.md CLAUDE.md .claude
```

## 브랜치

### `master` ← `develop` ← 작업 브랜치 구조를 쓴다
_[branch-model]_

| 브랜치 | 용도 | 직접 push |
| --- | --- | --- |
| `master` | 배포·시연 버전 | 금지 |
| `develop` | 통합 | 금지 |
| 작업 브랜치 | 이슈 하나의 작업 | 본인만 |

작업 브랜치는 항상 `develop` 에서 따고 `develop` 으로 MR 을 올린다.

### 작업 브랜치 이름은 `{파트}-{타입}/요약-S15P31E106-번호` 로 짓는다
_[branch-name]_

- 파트: `ai` `be` `fe` `iot` `infra` `doc`
- Jira의 `[DOC]` 작업은 `doc-docs/...` 를 사용한다. 기존 `infra-docs/...` 브랜치는 유지할 수 있다.
- 타입: `feature` `fix` `refactor` `docs` `chore`
- 요약: 영어 소문자와 `-` 만 쓴다.
- 이슈 키: 요약 뒤에 `-` 로 이어 붙인다. 키는 Jira 에 표시되는 대문자 그대로 쓴다.

나쁜 예:

```
feature/login
순철-작업
fe-feature/S15P31E106-23-signup-form
fe-feature/signup-form
```

좋은 예:

```
fe-feature/signup-form-S15P31E106-23
be-fix/duplicate-encounter-S15P31E106-41
ai-feature/preference-parser-S15P31E106-30
iot-feature/settings-sync-S15P31E106-42
doc-docs/team-conventions-S15P31E106-3
```

### 작업 브랜치 하나에는 이슈 하나만 담는다
_[one-issue-per-branch]_

머지된 작업 브랜치는 삭제한다.

## 커밋

### 커밋 메시지는 `타입: 요약` 형식으로 쓴다
_[commit-format]_

타입은 `feat` `fix` `refactor` `docs` `test` `chore` 만 쓴다. 요약은 한국어로 쓰고, 마침표를 찍지 않는다. Jira 이슈 키는 커밋에 넣지 않는다. 이슈 연결은 MR 제목으로 한다(`[mr-title]`).

나쁜 예:

```
수정
fix bug
feat: 가입 화면
feat: 가입 화면 필수 항목 검증 구현 (S15P31E106-23)
```

좋은 예:

```
feat: 가입 화면 필수 항목 검증 구현
fix: 만남 기록 재전송 시 익명 제안이 중복 생성되는 문제 수정
```

### 본문이 필요하면 한 줄을 비우고 이유를 쓴다
_[commit-body-why]_

무엇을 바꿨는지는 diff 에 있다. 본문에는 왜 바꿨는지를 쓴다.

### 커밋 하나에는 하나의 변경만 담는다
_[atomic-commit]_

포맷 변경과 로직 변경은 다른 커밋으로 나눈다.

## Merge Request

### MR 제목은 `[파트] 요약 S15P31E106-번호` 로 쓴다
_[mr-title]_

파트는 `AI`, `BE`, `FE`, `IOT`, `INFRA`, `DOC` 중 하나를 쓴다. 이슈 키는 브랜치 이름과 같이 요약 뒤에 쓴다. GitLab–Jira 연동이 설정되어 있으면 이 MR 이 Jira 이슈에 연결된다.

```
[FE] 가입 화면 필수 항목 검증 구현 S15P31E106-23
```

### MR 본문은 자동 설명에 템플릿을 덧붙여 쓴다
_[mr-template]_

MR 을 만들면 GitLab 이 커밋 메시지로 설명을 자동으로 채운다. 설명 편집기 위 템플릿 선택 드롭다운에서 `MR` 템플릿을 고르면 기존 설명을 덮어쓸 수 있으므로, 자동 설명을 먼저 복사해 두고 템플릿을 고른 뒤 `## 변경 내용` 아래에 붙여넣는다. 템플릿은 `.gitlab/merge_request_templates/MR.md` 에 둔다. 이름을 `Default.md` 로 하면 자동 설명 대신 템플릿만 채워지므로 쓰지 않는다.

```markdown
## 이슈
S15P31E106-번호
<!-- 이 MR로 이슈의 모든 완료 조건을 충족할 때만 위 줄을 Closes S15P31E106-번호로 바꾼다. -->

## 변경 내용
-

## 확인 방법
1.

## 스크린샷·검증 기록 (해당 시)

## 리뷰 대상
- 기본 리뷰어:
- contract 영향 파트·리뷰어 (해당 시):

## 체크리스트
- [ ] 커밋 메시지 규칙을 지켰다
- [ ] 공통 API·메시지 형식을 처음 작성하거나 변경했다면 명세와 샘플을 갱신하고 관련 파트에 검토를 요청했다
- [ ] 다른 파트의 코드를 건드리지 않았다. 불가피하게 건드렸다면 해당 파트 담당자에게 양해를 구했다
- [ ] 변경에 필요한 검증을 완료하고 확인 방법·결과를 기록했다 (코드는 빌드·테스트, 문서는 내용·링크 확인)
- [ ] `.env` 를 커밋하지 않았다
- [ ] Jira 이슈 체크를 했다
```

### MR 은 작성자를 제외한 기본 리뷰어의 승인을 받고 머지한다
_[mr-approval]_

| 작성 파트 | 기본 리뷰어 |
| --- | --- |
| FE | 다른 FE 담당자: 재준 ↔ 순철 |
| BE | 다른 BE 담당자: 영빈 ↔ 가을 |
| AI | 매칭 계산·키링 데이터는 종무, 서버 AI 연동은 영빈 또는 가을 중 관련 담당자 |
| IOT | 매칭 계산은 성현, 앱 BLE 연동은 재준 또는 순철, 서버 기록 연동은 영빈 또는 가을 중 관련 담당자 |
| INFRA·DOC | 결과물을 사용하는 담당자 또는 팀장·PM 중 작성자가 아닌 1명 |

AI·IOT처럼 1인 파트는 위 교차 리뷰를 같은 파트 승인 대신 사용한다. 여러 범위에 걸치면 각 관련 영역을 검토할 수 있도록 리뷰어를 추가한다. 기본 리뷰어가 부재하거나 작성자와 같으면 팀장 또는 PM이 해당 내용을 검토할 수 있는 다른 팀원을 지정하고 MR에 이유를 남긴다. 본인 승인은 인정하지 않는다.

Jira `contract` 라벨 이슈는 기본 승인에 더해 **영향받는 다른 파트마다 1명 이상**의 승인이 필요하다. 교차 리뷰어가 해당 파트 담당자라면 하나의 승인으로 기본 승인과 그 파트 승인을 함께 충족할 수 있다. 예를 들어 IOT → FE 계약은 FE 리뷰어 1명으로 가능하고, IOT → FE·BE 계약은 FE·BE 각 1명의 승인이 필요하다. 작성자는 자기 파트의 설명·확인을 맡으며 승인 인원에 포함하지 않는다.

작성자는 MR 본문에 기본 리뷰어와 영향 파트를 적는다. 필요한 승인을 모두 받은 뒤 **작성자가 아닌 팀원이** 병합한다. 본인이 올린 MR 은 본인이 머지하지 않는다. GitLab에서 승인 기능을 사용할 수 없으면 리뷰어가 MR에 승인 의사를 명시한 댓글을 남긴다.

### 공통 명세는 저장소에서 버전과 변경 영향을 함께 관리한다
_[shared-contracts]_

**2026-10-27 개인 작업 자료 관리 예외:** 현재 기능 명세 브랜치에 있던 계약·정책·ERD 작업 자료는 사용자 결정으로 저장소 밖에서 이재준이 관리한다. [출처 목록](../specifications/sources.md)을 사용하며 해당 개인 자료를 아래 경로에 다시 생성하지 않는다. 아래 표는 파트 간 공식 계약의 기존 관리 기준이며 다른 브랜치·과거 커밋의 계약 위치를 변경한 것은 아니다.

파트 간 공통 명세의 최초 작성과 변경 모두 Jira `contract` 라벨을 사용한다. 공식 명세는 `docs/contracts/` 아래에 두며, 실제 명세를 작성할 때 파일을 생성한다.

| 경로 | 내용 |
| --- | --- |
| `docs/contracts/api/` | 앱–서버·서버–AI 요청·응답·인증·오류·상태값 |
| `docs/contracts/keyring/` | 앱–키링·키링 간 메시지, 저장 완료 응답, 재전송·중복 처리 |
| `docs/contracts/matching/` | 매칭 입력 스키마, 자연어 구조화 결과, 경량 계산 규칙·테스트 사례 |

각 명세에는 담당자, 관련 Story, 버전·변경일, 필드명·자료형·필수 여부, 정상·오류 샘플, 영향 파트를 적는다. 변경할 때는 이전 버전과의 호환 여부, 함께 수정할 앱·서버·펌웨어와 적용 순서를 명시한다. API는 OpenAPI 등 기계 판독 가능한 형식을 사용할 수 있으며, 같은 내용의 문서를 중복 관리하지 않고 링크로 연결한다.

작성자는 Jira Watcher를 확인하고 MR 설명에 변경 요약·명세 경로·영향 파트·적용 순서를 남겨 리뷰를 요청한다. MR 병합 후 관련 Story에 병합 링크와 적용 시점을 공유한다. 호환되지 않는 변경은 관련 파트의 적용 준비를 확인한 뒤 합의한 순서로 반영한다. 가상 키링과 실물 키링은 동일한 계약을 사용하고, 실물에서만 확인할 항목은 별도로 표시한다.

### 작업 브랜치 → `develop` 은 Squash 머지한다
_[squash-to-develop]_

Squash 커밋 메시지는 `[commit-format]` 을 따른다. GitLab 은 기본으로 MR 제목을 Squash 커밋 메시지로 쓰므로, 머지할 때 이슈 키를 뺀 `타입: 요약` 으로 고친다. `develop` → `master` 는 일반 Merge commit 으로 머지한다.

### 자동 완료 연동이 검증된 경우에만 `Closes` 로 완료를 전환한다
_[mr-jira-sync]_

GitLab–Jira 연동의 Jira 전환 기능이 설정되고 `develop` 대상 동작이 검증된 경우, MR 병합 시 설명에 적은 이슈가 완료로 옮겨진다. 닫기 키워드는 `Closes` `Fixes` `Resolves` 를 쓸 수 있다. MR 제목의 이슈 키는 연결용이며 완료 전환을 대신하지 않는다. 실제 연동 확인 전에는 자동 완료를 가정하지 않고 담당자가 완료 조건과 병합 결과를 확인해 Jira 상태를 옮긴다.

```
Closes S15P31E106-23
```

한 이슈를 여러 MR 로 나눠 작업하면 모든 완료 조건을 충족하는 마지막 MR 에만 `Closes` 를 쓰고, 나머지 MR 에는 이슈 키만 적는다. 설문 배포·실기기 검증 등 병합 이후 작업이 남았거나 이미 완료된 이슈를 참고하는 경우에도 이슈 키만 쓴다. MR 을 올려도 이슈는 완료 기준을 충족할 때까지 진행 중에 둔다.

## 코드 리뷰

### 리뷰 코멘트에는 말머리를 붙인다
_[review-prefix]_

| 말머리 | 의미 |
| --- | --- |
| `[필수]` | 고치지 않으면 승인하지 않는다 |
| `[제안]` | 고치면 좋지만 작성자가 판단한다 |
| `[질문]` | 이해를 위한 질문. 답만 하면 된다 |

### 컨벤션 위반은 규칙 ID 로 가리킨다
_[review-rule-id]_

네이버 Java 컨벤션과 이 문서의 규칙 ID 를 쓴다.

```
[필수] [need-braces] 한 줄짜리 if 에도 중괄호가 필요합니다.
[필수] [commit-format] 커밋 메시지에 타입이 빠졌습니다.
```

### 리뷰 요청은 하루 안에 처리한다
_[review-within-a-day]_

하루 안에 못 보면 MR 에 그렇게 남기고 다른 리뷰어를 지정한다.

## 코드 스타일

### Java 코드는 네이버 캠퍼스 핵데이 Java 컨벤션을 따른다
_[java-naver]_

`naver-java-convention.md` 를 따르고, 검사는 Checkstyle 로 자동화한다. Python, TypeScript 등 다른 언어의 스타일 규칙은 파트별로 정해서 이 섹션에 추가한다.

## 도구 설정

### `master` 와 `develop` 은 보호 브랜치로 설정한다
_[protected-branches]_

GitLab 프로젝트 Settings > Repository > Protected branches 에서 두 브랜치 모두 "Allowed to push: No one", "Allowed to merge: Developers + Maintainers" 로 둔다.

### GitLab 과 Jira 를 연동한다
_[gitlab-jira-integration]_

GitLab의 Jira issues 연동에서 이슈 링크와 완료 전환을 설정한다. 설정은 팀장이 맡고, 실제 메뉴·지원 범위에 맞춰 적용한다. 테스트용 Story와 MR로 `develop` 병합 시 링크·상태 전환을 확인하고 설정 확인일·확인자·결과 링크를 관련 Jira 이슈에 남긴다. 보호 브랜치·승인 설정도 같은 방식으로 확인한다. 이 문서의 규칙만으로 실제 설정 완료를 의미하지 않는다.

### Java 프로젝트는 Jenkins CI 에서 Checkstyle 을 돌린다
_[ci-checkstyle]_

규칙 파일은 네이버 저장소의 `naver-checkstyle-rules.xml` 과 `naver-checkstyle-suppressions.xml` 을 `config/checkstyle/` 에 둔다. Checkstyle 버전은 8.24 이상으로 지정한다.

```groovy
plugins {
    id 'checkstyle'
}

checkstyle {
    toolVersion = '8.24'
    configFile = file('config/checkstyle/naver-checkstyle-rules.xml')
    configProperties = [suppressionFile: 'config/checkstyle/naver-checkstyle-suppressions.xml']
}
```

저장소 최상위 `Jenkinsfile` 의 MR 파이프라인에서 `./gradlew checkstyleMain` 을 실행하고, 실패하면 머지하지 않는다. Jenkins 는 GitLab Webhook 으로 MR 이벤트를 받고 결과를 MR 에 표시한다. GitLab 프로젝트 설정에서 "파이프라인이 성공해야 머지 가능" 을 켠다.
