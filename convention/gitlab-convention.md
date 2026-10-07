# GitLab 컨벤션

v0.1.0, 2026.10.07

자율 프로젝트 6인 팀의 GitLab 저장소 사용 규칙이다. 각 규칙에는 `[rule-id]` 를 붙여서 MR 리뷰에서 링크로 가리킬 수 있게 한다. 이슈 관리 규칙은 `jira-convention.md` 를 따른다. Jira 프로젝트 키는 `S15P31E106` 이다.

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

- 파트: `ai` `be` `fe` `infra`
- 타입: `feature` `fix` `refactor` `docs` `chore`
- 요약: 영어 소문자와 `-` 만 쓴다.
- 이슈 키: 요약 뒤에 `-` 로 이어 붙인다. 키는 Jira 에 표시되는 대문자 그대로 쓴다.

나쁜 예:

```
feature/login
순철-작업
fe-feature/S15P31E106-23-dialog-streaming
fe-feature/dialog-streaming
```

좋은 예:

```
fe-feature/dialog-streaming-S15P31E106-23
be-fix/secret-leak-S15P31E106-41
ai-feature/multi-lora-serving-S15P31E106-30
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
feat: 대화창
feat: 대화창 문장 단위 스트리밍 표시 (S15P31E106-23)
```

좋은 예:

```
feat: 대화창 문장 단위 스트리밍 표시
fix: 신뢰도 미달 시 비밀 대사가 프롬프트에 들어가는 문제 수정
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

이슈 키는 브랜치 이름과 같이 요약 뒤에 쓴다. GitLab–Jira 연동으로 이 MR 이 Jira 이슈에 연결된다.

```
[FE] 대화창 스트리밍 표시 S15P31E106-23
```

### MR 본문은 자동 설명에 템플릿을 덧붙여 쓴다
_[mr-template]_

MR 을 만들면 GitLab 이 커밋 메시지로 설명을 자동으로 채운다. 설명 편집기 위 템플릿 선택 드롭다운에서 `MR` 템플릿을 고르면 기존 설명을 덮어쓸 수 있으므로, 자동 설명을 먼저 복사해 두고 템플릿을 고른 뒤 `## 변경 내용` 아래에 붙여넣는다. 템플릿은 `.gitlab/merge_request_templates/MR.md` 에 둔다. 이름을 `Default.md` 로 하면 자동 설명 대신 템플릿만 채워지므로 쓰지 않는다.

```markdown
## 이슈
S15P31E106-

## 변경 내용
-

## 확인 방법
1.

## 스크린샷·대화 로그 (해당 시)

## 체크리스트
- [ ] 커밋 메시지 규칙을 지켰다
- [ ] 다른 파트가 쓰는 API·메시지 형식을 바꿨다면 변경된 부분의 명세서를 갱신했다
- [ ] 다른 파트의 코드를 건드리지 않았다. 불가피하게 건드렸다면 해당 파트 담당자에게 양해를 구했다
- [ ] 로컬에서 빌드와 테스트가 통과했다
- [ ] `.env` 를 커밋하지 않았다
- [ ] Jira 이슈 체크를 했다
```

### MR 은 같은 파트 1명 이상의 승인을 받고 머지한다
_[mr-approval]_

Jira `contract` 라벨 이슈는 상대 파트 1명의 승인도 받는다. 본인이 올린 MR 은 본인이 머지하지 않는다.

### 작업 브랜치 → `develop` 은 Squash 머지한다
_[squash-to-develop]_

Squash 커밋 메시지는 `[commit-format]` 을 따른다. GitLab 은 기본으로 MR 제목을 Squash 커밋 메시지로 쓰므로, 머지할 때 이슈 키를 뺀 `타입: 요약` 으로 고친다. `develop` → `master` 는 일반 Merge commit 으로 머지한다.

### MR 을 올리면 Jira 이슈를 리뷰 중으로, 머지되면 완료로 옮긴다
_[mr-jira-sync]_

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

GitLab 프로젝트 Settings > Integrations > Jira 를 설정하면 브랜치 이름과 MR 제목의 이슈 키가 Jira 이슈에 연결된다. 설정은 팀장이 한다.

### Java 프로젝트는 CI 에서 Checkstyle 을 돌린다
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

`.gitlab-ci.yml` 의 MR 파이프라인에서 `./gradlew checkstyleMain` 을 실행하고, 실패하면 머지하지 않는다.
