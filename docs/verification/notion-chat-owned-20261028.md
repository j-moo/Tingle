# 채팅 담당 API 11개 노션 저장 검증 — 2026-10-28

기존 노션 페이지를 내부 브라우저로만 수정했다. 로컬 문서·OpenAPI는 설계 계약이며 서버·실DB·배포의 완료를 뜻하지 않는다. 원본 DBML은 최신 21개 테이블 기준이다.

## 저장 결과

| 기존 페이지 | 검증 | JSON 예시 수 |
| --- | --- | --- |
| [leave](https://app.notion.com/p/3ffc91ccb6608074b7b4d4964c5a6656) | 저장 재조회 확인 | 1 |
| [icePost](https://app.notion.com/p/3ffc91ccb66080bcb2ebc3c1a03f72b8) | 저장 재조회 확인 | 1 |
| [send](https://app.notion.com/p/3ffc91ccb6608042a53fc12019b109e5) | 저장 재조회 확인 | 2 |
| [iceGet](https://app.notion.com/p/3ffc91ccb660803fbc5bf872fe8e805c) | 저장 재조회 확인 | 1 |
| [rooms](https://app.notion.com/p/3ffc91ccb660805ea5c4d17106bfe471) | 저장 재조회 확인 | 1 |
| [room](https://app.notion.com/p/3ffc91ccb660805ca7cbe858730577b8) | 저장 재조회 확인 | 1 |
| [messages](https://app.notion.com/p/3ffc91ccb660806e8880d5fd202bb99d) | 저장 재조회 확인 | 1 |
| [entries](https://app.notion.com/p/3ffc91ccb660803c9176de479f0abbfc) | 저장 재조회 확인 | 1 |
| [noticeAck](https://app.notion.com/p/3ffc91ccb6608016ae78f266ed2c93eb) | 저장 재조회 확인 | 1 |
| [reads](https://app.notion.com/p/401c91ccb66080f6944af1b785df25c3) | 저장 재조회 확인 | 2 |
| [notices](https://app.notion.com/p/3ffc91ccb660805e9b89d734fec0fb78) | 저장 재조회 확인 | 1 |

11개 페이지의 새로고침 또는 URL 새 탐색 후 상단 설명부터 오류 공통 문단까지 읽었다. 총 13개 JSON 예시가 파싱됐고 통합 OpenAPI의 해당 요청·응답 스키마 대조도 13/13 통과했다. 공개 chatRoomId/messageId의 UUID 형식 잔존 0개, 예시 readByPartner 0개, 본문의 /api/v1 경로 잔존 0개를 확인했다. 요청 clientMessageId·Idempotency-Key UUID는 유지한다. 숫자 ID·수명·닫힘 시각의 표를 직접 확인했으며 entries/noticeAck의 안내 UUID는 저장 대안 미확정으로 표시했다. icebreakers POST는 chatRoomId와 현재 상태 URL, GET은 status와 결과를 반환하며 GET 표의 status 행까지 추가 후 재조회했다.

일부 셀은 즉시 편집기 표시와 실제 저장본이 달랐다. dblclick으로 실제 셀 편집에 진입하고 전체 선택·입력 후 헤딩 클릭으로 확정한 뒤 다시 불러와 대조했다. 저장 재조회에서 확인되지 않은 초기 입력은 완료 집계에서 제외했다.

원본 UI 본문과 표 셀별 저장 재조회는 [JSON 증거](notion-chat-owned-20261028.json)에 기록했다. 화면 증거는 프로젝트 루트 tmp/notion-chat-send-saved-20261028.png 및 tmp/notion-chat-response-saved-20261028.png다.

## 보존한 미확정 사항

- FIRST_ENTRY/종료 안내, entry 업무 키, 예약·표시 ACK의 영속 저장 부재. chat_safety_notice는 위험 안내 전용이며 대체 저장소로 확정하지 않았다.
- 숫자 messageIds 배치를 본인의 last_read_message_seq로 반영할 때 미표시 구간을 건너뛰지 않는 표시 범위·재시도 규칙. 상대 읽음 상태는 공개하지 않는다.
- 회원 범위 요청 멱등 키와 참여자 범위 ERD 제약의 차이, AI 동의 철회 이력 저장, 종료·탈퇴·신고 보관 정책 충돌.
- AI 재시도 기산점·최대 횟수·중복 실행·늦은 결과 차단 세부 규칙. 상태·질문·재시도 정보는 chat.icebreakers JSON에 대응하며 별도 jobId는 사용하지 않는다.
- leave의 POL-09 정책 합의 전 422 POLICY_UNRESOLVED 유지. 정상 응답 예시는 미래 합의 후 형식 참고다.

## 로컬 정합화

chat.md와 chat-notices.md의 현재 21개 기준을 갱신했고 구스키마 구현 이력은 명시적으로 분리했다. 11 operation·13 전용 schema를 [통합 OpenAPI](../contracts/api/openapi.json)에 반영했다. 공유 schema를 덮어쓰지 않도록 대체 정의를 사용했다. reads 예시 숫자 8192 교정도 통합 검증했다. 최종 커밋 상태는 전체 작업 기록을 따른다.
