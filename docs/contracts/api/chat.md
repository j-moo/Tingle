# 채팅 저장·권한·메시지·본인 표시 확인 API

## 2026-10-28 저장 이관 보완

현재 저장 정의는 승인된 22표 보존 보완본이다. 방·메시지 공개 ID는 양수 int64이며 /api 경로를 사용한다. 기존 clientMessageId·Idempotency-Key·noticeId UUID는 유지한다. 상대 readByPartner는 비공개 계약에 따라 응답에서 제거하고, 본인의 불연속 표시 증거는 참여자의 read_state에 보존한다. 실제 22표 실행·자료 이관 검증 수준은 [175 검증 기록](../../verification/erd22-storage-175.md)을 따른다. 이 변경은 현재 미구현 나가기·신고·AI API의 개발 완료를 뜻하지 않는다.

2026-10-28 / 담당 김영빈(BE), 영향 FE·BE·AI. 최신 21개 테이블 설계 기준의 문서 계약이다. 경로·필드·예시는 [OpenAPI](openapi.json), 저장 정의는 [ERD](../../database/erd.md)를 따른다. 서버 코드·기존 데이터 이관·공용 DB·배포의 완료를 뜻하지 않는다. 기능·매칭 정책 원문은 보존한다.

## 식별자와 저장 위치

| API 항목 | 21개 테이블 기준 |
| --- | --- |
| chatRoomId | `chat.id` 양의 integer(int64). 공개 UUID 칸 없음 |
| messageId | `chat_message.id` 양의 integer(int64) |
| clientMessageId | 앱 발급 UUID인 `chat_message.client_message_id`. 숫자 ID 전환 대상 아님 |
| Idempotency-Key | UUID인 `chat_message.request_key`. 원문을 보관하지 않고 `request_hash`로 요청 의미 충돌 판정 |
| sequence | `chat_message.seq`. 방 안의 증가 순번 |
| sender | `sender_participant_id`를 현재 `chat_participant.id`와 비교해 ME/PARTNER로 반환. 내부 참여자·회원 번호 비공개 |
| text | 입력 검증·웹 주소 치환을 마친 `chat_message.body`. 치환 전 원문 별도 저장·로그 금지 |
| 본인 표시 순번 | `chat_participant.last_read_message_seq`, 초기 0. 상대 비공개 |

인증된 회원이 해당 방 참여자인지 매 요청 검사한다. 번호를 안다는 이유로 접근을 허용하지 않는다. 없는 방과 비참여자에게 같은 404 RESOURCE_NOT_FOUND를 반환한다.

## 방 생성·수명·종료

상호 수락 확정, `chat(matching_id)`, 두 `chat_participant` 생성은 같은 트랜잭션에서 처리한다. `chat.matching_id`의 관계당 한 방 조건을 검사하고 중복 수락은 최초 방·생성 시각을 복구한다. 한쪽 수락·거절·만료는 방을 만들지 않는다. 실패하면 전체를 롤백한다.

`createdAt = chat.created_at`, `expiresAt = created_at + 168시간`이다. 첫 입장·메시지·재접속·재시도·배치 지연으로 기한을 연장하지 않는다. `closedAt = chat.closed_at`은 최초 실제 종료 효력 시각이며 정상 만료는 원래 expiresAt이다. API ACTIVE/CLOSED는 종료·만료로 계산하며 별도 status 열은 없다. close_reason은 EXPIRED/LEFT/BLOCKED 내부 구분이며 상대에게 구체 사유를 공개하지 않는다.

기존 API deleteAt은 별도 DB 칸이 없는 보관 정책 계산값이다. 기존 종료+720시간 정책, 첨부의 탈퇴 즉시 삭제·신고 최초+90일 retain_until은 정책·관계 삭제 순서 합의가 필요하다. retain_until이나 신고 여부를 상대 공개 DTO로 추정·노출하지 않는다. 원문·최소 관계 기록·신고 보관의 목적과 수명을 섞지 않는다.

## 경로와 권한

모든 경로는 `/api` 기준이며 Bearer 액세스 토큰을 요구한다.

| 경로 | 요청 | 정상 응답 |
| --- | --- | --- |
| GET /chat-rooms | 선택 cursor, limit 기본 20·최대 100 | 200 ChatPage |
| GET /chat-rooms/{chatRoomId} | 숫자 방 ID | 200 ChatRoom |
| GET /chat-rooms/{chatRoomId}/messages | 선택 cursor, limit 기본 20·최대 100 | 200 MessagePage |
| POST /chat-rooms/{chatRoomId}/messages | UUID Idempotency-Key, `{clientMessageId: UUID, text}` | 최초·정상 재전송 모두 201 Message |
| POST /chat-rooms/{chatRoomId}/reads | 실제 표시한 수신 messageId 숫자 1~100개 | 200 ReadMessagesResult; 본인 read_state 증거와 연속 순번 요약 보존 |
| POST /chat-rooms/{chatRoomId}/leave | 본문 없음 | 현재 POL-09 미합의로 422 POLICY_UNRESOLVED. 합의 후 정상 형식은 200 LeaveResult |

현재 인증·가입 완료·필수 동의·참여/퇴장·양쪽 계정/차단·방 만료를 검사한다. 마지막 키링 해제나 설문/설정 재검사를 기존 방 접근 제한으로 임의 추가하지 않는다. 성공 응답은 no-store다. 메시지·읽음에서 이용 종료는 410 CHAT_CLOSED이며 메타데이터 조회는 참여자의 종료 안내를 위해 200 CLOSED·canRead/canSend=false를 허용하고 대화 내용은 포함하지 않는다.

AI 동의·철회에 따른 접근/외부 분석 차단은 기존 기능 계약을 유지한다. 다만 최신 users.agreed_at 한 칸에는 AI 철회 이력·버전별 선택 상태가 없어 영속 저장·종료 사건 연동 합의가 필요하다. 현재 문서 변경으로 철회 처리나 새 동의 모델 구현 완료를 주장하지 않는다.

커서는 사용자·방 숫자 ID·정렬 위치에 귀속되는 불투명 값이다. 다른 방 커서와 잘못된 값은 400이다. 메시지는 sequence 내림차순이며 FE는 숫자 messageId와 sequence로 재접속 중복을 제거한다. 다운로드·방 목록 조회로 읽음 처리하지 않는다. 실시간 전송·푸시 전달 보장은 별도다.

## 본문 검증과 재전송

1. CRLF/CR을 LF로 통일한다. 공백·줄바꿈만인 입력, NUL 등 제어문자(탭/LF 제외), 잘못된 UTF-16 서로게이트를 거부한다.
2. 치환 전 원문은 Java21 확장 그래핌 기준 1000자와 UTF-8 65536바이트 이하로 검사한다. 위반은 400 INVALID_MESSAGE이며 입력을 자르지 않는다.
3. HTTP/HTTPS·www·프로토콜 없는 도메인/경로·단축 URL·국제화 도메인·IP 웹 주소의 주소 부분을 `[웹 주소 차단]`으로 치환하고 주변 문장은 보존한다. 이메일·일반 숫자·카카오 ID·분할 표기는 합쳐 추정하지 않는다.
4. 치환한 전체 결과를 body에 저장·반환한다. 치환으로 늘어난 결과를 다시 거부하거나 자르지 않는다. 이미지·파일·미정의 필드는 400이다.

동일 재전송은 최초 messageId·sequence·createdAt을 그대로 반환한다. 다른 정규화 원문/업무로 Idempotency-Key 또는 clientMessageId를 재사용하면 409 IDEMPOTENCY_CONFLICT다. 같은 clientMessageId에 새 요청 키를 쓰는 정상 재시도는 기존 메시지를 반환하며 새 키를 소비하지 않는다. 재전송도 현재 권한·기한을 검사한다.

요청 키는 발신 회원 범위, clientMessageId는 방+발신자 범위다. 참여자별 물리 고유성 외에 같은 회원의 다른 방 메시지까지 요청 키를 조회한다. 회원→방→참여자 잠금과 참여자 상태 재조회로 명령 충돌 및 안내·읽음 상태의 동시 갱신을 보호한다.

## 본인 수신 메시지 표시 확인

FE는 앱 활성 상태의 현재 방 화면에 실제 표시한 수신 메시지 숫자 ID만 보낸다. 알림 미리보기·다운로드·방 목록·화면 밖 메시지는 ACK 대상이 아니다. 서버는 요청 전체의 방·수신 소유권을 검증하며 본인 발신은 400, 다른 방/없는 메시지는 404다. 일부만 성공시키지 않는다. 늦은 ACK로 종료 방을 복원하지 않는다.

참여자의 read_state는 수신 messageId별 최초 readAt을 영속 보존한다. last_read_message_seq는 빈칸 없는 확인 범위의 요약이며, 상대에게 읽음 상태를 공개하지 않는다. Message.readByPartner는 응답에 포함하지 않는다. 순번 요약보다 앞서 표시한 불연속 메시지의 확인 여부도 개별 증거로 판정한다.

사용자가 기존 숫자 messageIds 배치를 유지하도록 확정했다. 요청 전체의 수신 소유권을 검증하고 개별 최초 표시 시각을 보존한다. 단순 max(seq)로 건너뛴 메시지까지 읽었다고 추정하지 않으며, 재시도는 기존 readAt을 바꾸지 않는다.

## 나가기·AI 시작 질문

나가기는 본인 `chat_participant.left_at`과 최초 `chat.closed_at/close_reason=LEFT`를 원자 처리한다. 재시도는 최초 효력 시각을 유지하고 나가기를 차단과 동일시하지 않는다. 사용자 결정으로 제안 생성 이후 영구 재매칭 금지는 확정됐다. 나가기·삭제의 나머지 POL-09 상세는 별개다. 기존 POL-09 합의 전에는 422 POLICY_UNRESOLVED를 유지하며 문서 수정만으로 이 API를 활성화하지 않는다.

AI 시작 질문의 상태·질문 3개·출처·버전·재시도 정보는 `chat.icebreakers` JSON에 저장한다. POST는 별도 jobId 없이 chatRoomId와 해당 방 GET 상태 URL을 반환한다. GET 상태는 PENDING/SUCCEEDED/FAILED이며 성공한 질문은 정확히 3개다. 대기·실패를 생성 성공으로 표시하지 않는다. 최종 생성 실패는 서버 설정 파일의 기본 질문 대체를 시도하며 대체 성공은 SUCCEEDED/GENERAL_FALLBACK이다. 질문 사용 없이도 대화할 수 있다.

최초 시도 포함 여부·최대 횟수·5분 기산점·실행 선점/세대·늦은 결과 반영 차단은 [ERD 설명](../../database/erd.md)의 합의 항목을 따른다. 채팅 종료·동의 철회 뒤 외부 전송/늦은 결과 반영을 막아야 하며 메모리 큐만으로 재시작 복구를 주장하지 않는다.

## 검증 경계와 구현 이력

현재 숫자 ID·저장 구조는 22개 테이블과 승인된 업무별 증거 보존 컬럼 기준이다. 과거 UUID·개별 읽음 행을 쓰던 구현과는 호환되지 않는 변경이므로 FE/BE 계약·기존 행 이관·현재 소유권 검증을 함께 적용해야 한다. 기존 구현 증거는 [2026-10-22 통합 기록](../../verification/mr29-unified-integration-20261022.md)과 [당시 웹 API 대조](../../verification/notion-api-ten-20261022.json)이며 현재 스키마/서버 동작 증거로 확대하지 않는다. 입장·종료 안내의 참여자 전용 상태 보존은 [안내 계약](chat-notices.md)을 따른다. 나가기의 정책 오류 계약은 현재 없는 컨트롤러의 구현·검증 완료를 의미하지 않는다.
