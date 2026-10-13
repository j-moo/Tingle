## 파일 구조

```
S15P31E106/
├─ .gitlab/
│  └─ merge_request_templates/
│     └─ MR.md
├─ docs/
│  └─ convention/
│     ├─ gitlab-convention.md
│     ├─ jira-convention.md
│     └─ naver-java-convention.md
├─ .gitattributes
├─ .gitignore
└─ README.md
```

## MR 체크리스트

MR 을 올리기 전에 확인한다. MR 템플릿(`.gitlab/merge_request_templates/MR.md`)에도 같은 목록이 있다.

- [ ] 커밋 메시지 규칙을 지켰다
- [ ] 다른 파트가 쓰는 API·메시지 형식을 바꿨다면 변경된 부분의 명세서를 갱신했다
- [ ] 다른 파트의 코드를 건드리지 않았다. 불가피하게 건드렸다면 해당 파트 담당자에게 양해를 구했다
- [ ] 로컬에서 빌드와 테스트가 통과했다
- [ ] `.env` 를 커밋하지 않았다
- [ ] Jira 이슈 체크를 했다
