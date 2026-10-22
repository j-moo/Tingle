## 파일 구조

```
S15P31E106/
├─ .gitlab/
│  └─ merge_request_templates/
│     └─ MR.md
├─ back/                     # Spring Boot 서버
├─ docs/
│  ├─ contracts/
│  │  └─ api/
│  │     └─ auth.md
│  └─ convention/
│     ├─ backend-convention.md   # 백엔드 규칙 (BE 합의 중)
│     ├─ gitlab-convention.md
│     ├─ jira-convention.md
│     └─ naver-java-convention.md
├─ .gitattributes
├─ .gitignore
└─ README.md
```

## 백엔드 로컬 실행

Java 21과 Docker Desktop이 필요하다. 실행하면 Spring Boot가 `back/compose.yaml`의 PostgreSQL을 자동으로 띄우고 연결한다.

```
cd back
./gradlew bootRun     # 서버 실행
./gradlew build       # 빌드, 테스트, Checkstyle(네이버 컨벤션) 검사. 테스트도 Docker로 DB를 띄운다
```

환경변수 이름은 `back/.env.example`을 따른다. 실제 값은 `back/.env`에 넣고 커밋하지 않는다. 선택 변수는 빈 값 대신 주석으로 둔다.

공통 설정은 JPA `validate`·Compose 비활성, local은 `update`·Compose 활성, test는 `create-drop`이다. 기본 실행 프로필은 local이며 dev/prod는 인프라 준비 후 정한다.

Docker 없이 공통 설정·예외 처리만 확인하려면 다음을 실행한다. 전체 DB 통합 테스트에는 Docker가 필요하다.

```
./gradlew test --tests com.e106.tingle.global.error.GlobalExceptionHandlerTest --tests com.e106.tingle.RuntimeConfigurationTest
```

## MR 체크리스트

MR 을 올리기 전에 확인한다. MR 템플릿(`.gitlab/merge_request_templates/MR.md`)에도 같은 목록이 있다.

- [ ] 커밋 메시지 규칙을 지켰다
- [ ] 다른 파트가 쓰는 API·메시지 형식을 바꿨다면 변경된 부분의 명세서를 갱신했다
- [ ] 다른 파트의 코드를 건드리지 않았다. 불가피하게 건드렸다면 해당 파트 담당자에게 양해를 구했다
- [ ] 로컬에서 빌드와 테스트가 통과했다
- [ ] `.env` 를 커밋하지 않았다
- [ ] Jira 이슈 체크를 했다
