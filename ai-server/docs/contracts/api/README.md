# Tingle(팅글) API 명세서

1.4.2-common-storage-preservation-review / 2026-10-28

151 사용자 결정: 회원 상태 `users.status`·생성 시각 `created_at`과 기존 `user_consents`의 항목별 동의·철회·버전 이력을 보존한다. 175 사용자 결정에 따라 업무별 영속 증거를 전용 컬럼에 보완한 현재 설계는 **22개 테이블·176개 컬럼·36개 논리 관계**이며, 과거 이력으로 `users.agreed_at`을 추정하지 않는다. 아래 원본 21개·151컬럼과 푸시 보완 21개·153컬럼 기록은 각 당시 기준이다. 공유 DB 적용은 별도다.

176·178 사용자 결정: 키링 공개 응답의 `boundAt`을 제거하고 `deviceId`·`status`·`firmwareVersion`을 유지했다. 푸시 연결 ID·최초 연결 시각은 ERD에 보존하여 이 푸시 보완 당시 21개 테이블·153개 컬럼·35개 논리 관계였다. 아래 151개는 최초 제공 설계 기준이며 이 2개 추가와 구분한다. 숫자 기기 ID·전체 저장 모델/로그아웃 이관의 완료 여부는 [176 검증](../../verification/keyring-push-176.md)을 따른다.

176 현재 이관 범위: 구매 안내·본인 키링 조회와 푸시 단말 등록·해제 4개 operation 및 기존 로그아웃의 연결 해제를 새 **22표·176컬럼·36논리관계**에 연결했다. !92 실행 코드 `35166ea`에서 숫자 기기 ID·현재 소유/ACK·푸시 연결 세대 보존을 검증했고, 전체 앱 clean build 422개와 Checkstyle/패키징이 통과했다. OpenAPI의 이 4개 operation은 `erd22-canonical-176` 저장 기준이며 사람의 교차 리뷰·승인은 아직 필요하다. 이 기록은 신규 70과 통합하기 전 !92 범위이고, !93 전체 연결 검증을 대신하지 않는다.

등록 challenge67·소유 증명100·동기화68/69는 별도 잔여다. 사용자가 최종 등록/ACK 규격이 별도로 없음을 확인했으며, 실제 기기 키 발급·서명 바이트/검증과 nonce 소비·당시 payload/run/소유세대의 영속 정의가 필요하다. 임의 암호 규칙·신규 API/칼럼을 추가하지 않는다. 과거 serial/owner/ACK 대응 자료의 실제 이관·실기기/FCM·공유 DB·배포 및 Notion 일치 확인은 미완료다. [현재 176 검증과 잔여](../../verification/keyring-push-176.md).

영빈 담당 **40개 operation**의 기존 계약을 사용자 제공 **21개 테이블·151개 컬럼·35개 논리 관계** 설계에 맞춘 검토 작업본이다. 담당 활성 **36개**, 후속·제외 **4개**이며, 다른 담당 31개를 합한 전체 활성 API는 **67개**다. 기존 제외 11개와 이번 4개를 합한 제외 이력은 **15개**다. 문서 반영과 팀 승인·서버 구현·실DB 적용·배포 완료를 구분한다.

- [OpenAPI: 타입·필수값·예시](openapi.json), [현재 정합화 검증](../../verification/owned-api-erd-20261028.md)
- [ERD·컬럼 설명](../../database/erd.md), [로컬 설계 SQL](../../database/schema.sql), [21개 구조 검증](../../verification/erd21-alignment-20261028.md)
- [설문](survey-sessions.md), [설문 파일 버전](../survey/versioning.md), [만남·제안](encounters-proposals.md), [채팅](chat.md), [안내](chat-notices.md), [AI 설명](ai-match-explanation.md)
- [기존 노션 API 명세서](https://app.notion.com/p/API-4a2c91ccb660820793de81b259578314), [인증 확인 사항](auth-recheck.md)

## 2026-10-28 ERD21 담당 정합화

관련 S15P31E106-91·92. 기존 승인 작업 위치와 브랜치를 유지한다. 영빈 담당 경로에서 `/v1`을 제거하고 `/api` 또는 `/internal`을 유지했다. 본인 위험 분석 동의는 `/api/users/{userId}/risk-analysis-consent`다. 가을 전달 기준에 맞춰 희망 조건·설정 4개 경로도 `/api/users/{userId}/preferences`·`/api/users/{userId}/settings`로 바로잡고 필수 int64 userId와 토큰 주체 불일치 `403 FORBIDDEN`을 명시했다. 경로 ID를 인증 근거로 신뢰하지 않는다.

다른 담당 31개 operation은 이 4개의 경로·userId·403 보완 외에 기존 동작과 스키마를 보존한다. 따라서 인증·프로필·키링·사진·푸시·차단 등의 기존 `/v1`·`/me`와 이전 저장 설명이 남을 수 있다. 상대 사진 등 타 담당 경로도 임의 변경하지 않았다. 아래 API 목록과 각 operation의 담당 범위를 확인한다.

현재 계약의 숫자 ID는 `sessionId=survey.id`, `resultId=personality.id`, `proposalId/encounterId=matching.id`, `chatRoomId=chat.id`, `messageId=chat_message.id`, `deviceId=keyring.id`다. 모두 integer(int64)다. 앱 발급 installationId·sid/refresh family·Idempotency-Key·clientMessageId·만남 submissionId/eventId·AI attemptId는 역할이 다른 UUID다. 내부 AI의 legacy releaseId와 snapshot UUID는 저장/파일 매핑 미합의 상태로 보존한다.

## 현재 저장·시간·권한 기준

| 범위 | ERD21 계약과 제한 |
| --- | --- |
| 설문·답변 | `survey`·`survey_answer` 문항별 저장. 설문 버전 최대30자·문항ID 최대50자·저장 답값 최대100자. scalar 답만 현재 형식에 포함. 선택 나이 정보는 새 칸 없이 `survey.response_context.metadata`, 그 저장만 `survey.revision`·If-Match/ETag로 검사(S15P31E106-112). 완료와 분석 성공은 별개 |
| 분석 | `personality` 상태·결과와 `profile.active_personality_id`. 유효한 최신 SUCCEEDED만 결과·포인터·설정 버전을 원자 전환. 대기·부족·실패 중 기존 적용 값 유지. AnalysisManifest는2필드, 설문 SurveyManifest는3필드이며 공유 Manifest11필드는 보존 |
| 만남·제안 | `matching`·`matching_participant`. 제안 createdAt은 서버 생성 최초 확정 `proposed_at`, expiresAt은 그+24시간. 푸시 시각으로 바꾸거나 재전송으로 연장하지 않음. 상대 선행 응답·차단 이유를 본인 DTO에 누설하지 않음 |
| 수락·거절·방 생성 | matching 잠금·기한/권한 재검사. 응답 최초 확정 뒤 변경 불가. 상호 수락과 `chat`·두 참여자 생성은 원자 처리. 키 저장 대안·기존30일 대 ERD영구 재매칭 제한 충돌은 미합의 |
| 채팅·메시지 | `chat`·`chat_participant`·`chat_message`. numeric ID·seq·발신자 ME/PARTNER. request_key/client_message_id·request_hash 재전송 구분. 상대 읽음 공개 제거, 본인 실제 표시 순번 기준. reads 배치의 연속 범위·안내 UUID 저장 대안은 상세 명세의 미합의 유지 |
| AI 설명 | `matching.evaluation` 상태/결과. POST는7필드 ExplanationTask, GET은5필드 ExplanationStatus(PENDING/SUCCEEDED/FAILED). 공용 jobId 없음. 승인된 실제 근거만 생성·공개하고 유효 근거 대체 문장만 RULE_FALLBACK으로 구분 |
| AI 시작 질문·위험 분석 | `chat.icebreakers`·`chat.ai_state`와 `chat_safety_notice`. 내부 실행은 attemptId 상관 UUID+숫자 contextId. 실행 복구·늦은 결과·입력 세대 대안 미합의. 질문 실패 시 기본 질문·질문 없이 대화 가능, 미분석/실패를 안전으로 표시하지 않음 |
| 위험 분석 동의 | `users.agreed_at` 하나로 선택 동의·철회·버전·이력을 보존할 수 없음. enabled/noticeVersion/grantedAt DTO는 합의 전 초안이며 독립 저장·검증 및 POL-07 승인 전 REVIEW_REQUIRED/구현 차단. 일괄 동의에서 enabled=true 추정 금지 |
| 신고·수락 취소 | CM09·CM12로 현재 MVP 제외. 신고는 후속 숫자 chatRoomId/messageIds/reportId 형식과 ERD코드만 보존. 기존 신고 자료의 삭제·기한 단축 금지. 수락 취소 성공을 표시하거나 응답을 되돌리지 않음 |

Spring Boot·JPA·PostgreSQL 운영 기준과 인증·프로필·키링 담당 계약은 해당 문서를 따른다. 이 문서 변경에서 서버/JPA/실SQL을 실행하지 않는다. 공유 Manifest11필드와 다른 담당 operation의 재귀 참조 스키마는 보존했으며, 필요한 분리는 별도 ERD21 스키마로 한정했다.

## 공통 전송과 미합의 경계

- 성공은 감싸지 않는 DTO, 목록은 items/nextCursor이며, 오류 필수값은 code/message다. 오류의 fields는 현재 문맥에 맞는 배열이고 정책·권한 오류를 무관한 revision 충돌로 표시하지 않는다.
- UTC ISO8601, 서버 Clock, 기한 정각부터 만료를 유지한다. 만남 시각·정밀 위치·내부 회원번호·비공개 원응답·기기 비밀은 공개 DTO/로그/모델 전송에 자동 포함하지 않는다.
- 인증·가입 단계·본인 소유/현재 참여 권한을 먼저 검사하고 타인 자원은404로 숨긴다. 본인 자원의 기한·상태 오류는 경로별 OpenAPI를 따른다. 민감 응답 no-store, 기존 인증401/토큰 갱신 및 공개 API 예외 범위를 보존한다.
- Idempotency-Key는 해당 operation에 있는 기존 요청 계약을 보존한다. 전용 저장 정보가 없는 곳의 영속 판정·응답 재현을 문서만으로 보장하지 않는다. 설문에서 제거한 헤더를 다른 도메인에 일괄 적용하지 않는다.
- 만남 WAITING/NOT_MATCHED 재사용은 새 이벤트·소유/설정 세대 증거가 필요하다. blocked_at은 지우지 않으며, 이전 삭제 예약·늦은 검증/AI 결과가 새 기록을 덮거나 삭제하지 않도록 세대를 확인해야 한다.
- AI 재시도의 5분 기산점·최초 실행 포함 여부·최대 횟수·영속 선점·실행 세대/입력 식별·늦은 결과 차단은 미합의다. 없는 컬럼·작업 테이블·릴리스 테이블을 구현된 저장소로 기술하지 않는다.
- 기획·기능·정책·UX 원문과 타 담당 구현은 보존한다. 계약 검토 → FE/BE/AI/IoT 합의 → 구현·통합 → 실제 기기/외부 서비스/배포 검증 순으로 진행한다.

## 현재 API 목록

담당의 REVIEW_REQUIRED는 현재 검토 계약이며 구현 제공·개발 완료 표시가 아니다. 다른 담당은 기존 계약 보존 범위다.

| Method | Path | operationId | 범위 |
| --- | --- | --- | --- |
| POST | `/api/v1/auth/{provider}` | socialLogin | 다른 담당 기존 계약 |
| POST | `/api/v1/auth/token/refresh` | refreshTokens | 다른 담당 기존 계약 |
| POST | `/api/v1/auth/logout` | logout | 다른 담당 기존 계약 |
| GET | `/api/v1/agreements` | listAgreements | 다른 담당 기존 계약 |
| POST | `/api/v1/users/me/agreements` | submitAgreements | 다른 담당 기존 계약 |
| GET | `/api/v1/users/me/onboarding` | getOnboarding | 다른 담당 기존 계약 |
| GET | `/api/v1/users/nickname-availability` | checkNickname | 다른 담당 기존 계약 |
| GET | `/api/keyrings/purchase-info` | purchaseInfo | 176 새22표 로컬 검증·교차 리뷰 필요 |
| POST | `/api/v1/keyrings/registration-challenges` | createRegistrationChallenge | 다른 담당 기존 계약 |
| POST | `/api/v1/keyrings` | registerKeyring | 다른 담당 기존 계약 |
| GET | `/api/keyrings` | listKeyrings | 176 새22표 로컬 검증·교차 리뷰 필요 |
| GET | `/api/v1/users/me/profile` | getMyProfile | 다른 담당 기존 계약 |
| PATCH | `/api/v1/users/me/profile` | saveProfileDraft | 다른 담당 기존 계약 |
| POST | `/api/v1/users/me/profile/photos` | uploadPhoto | 다른 담당 기존 계약 |
| DELETE | `/api/v1/users/me/profile/photo` | deletePhoto | 다른 담당 기존 계약 |
| POST | `/api/v1/keyrings/{deviceId}/sync-runs` | createSync | 다른 담당 기존 계약 |
| GET | `/api/v1/keyrings/{deviceId}/sync-runs/{syncRunId}` | getSync | 다른 담당 기존 계약 |
| POST | `/api/v1/keyrings/{deviceId}/sync-runs/{syncRunId}/ack` | ackSync | 다른 담당 기존 계약 |
| PUT | `/api/survey-sessions/{sessionId}/answers/{questionId}` | saveSurveyAnswer | 영빈 ERD21 검토 |
| DELETE | `/api/survey-sessions/{sessionId}/answers/{questionId}` | deleteSurveyAnswer | 영빈 ERD21 검토 |
| PUT | `/api/survey-sessions/{sessionId}/metadata` | setSurveyMetadata | 가을 2026-11-04 구현(S15P31E106-112) |
| GET | `/api/v1/chat-rooms/{chatRoomId}/partner-profile` | getChatProfile | 다른 담당 기존 계약 |
| GET | `/api/v1/chat-rooms/{chatRoomId}/partner-photo` | getPartnerPhoto | 다른 담당 기존 계약 |
| GET | `/api/v1/match-proposals/{proposalId}/partner-photo` | getProposalPhoto | 다른 담당 기존 계약 |
| GET | `/api/chat-rooms/{chatRoomId}/messages` | listMessages | 영빈 ERD21 검토 |
| POST | `/api/chat-rooms/{chatRoomId}/messages` | sendMessage | 영빈 ERD21 검토 |
| POST | `/api/chat-rooms/{chatRoomId}/icebreakers` | requestIcebreakers | 영빈 ERD21 검토 |
| GET | `/api/chat-rooms/{chatRoomId}/icebreakers` | getIcebreakers | 영빈 ERD21 검토 |
| GET | `/api/v1/blocks` | listBlocks | 다른 담당 기존 계약 |
| POST | `/api/v1/blocks` | createBlock | 다른 담당 기존 계약 |
| DELETE | `/api/v1/blocks/{blockId}` | removeBlock | 다른 담당 기존 계약 |
| PUT | `/api/push-installations` | registerPush | 176 새22표 로컬 검증·교차 리뷰 필요 |
| DELETE | `/api/push-installations/{installationId}` | removePush | 176 새22표 로컬 검증·교차 리뷰 필요 |
| POST | `/api/v1/users/me/profile/complete` | completeProfile | 다른 담당 기존 계약 |
| GET | `/api/surveys/current` | getQuestionnaire | 영빈 ERD21 검토 |
| POST | `/api/survey-sessions` | createSurveySession | 영빈 ERD21 검토 |
| GET | `/api/survey-sessions/{sessionId}` | getSurveySession | 영빈 ERD21 검토 |
| GET | `/api/survey-sessions/{sessionId}/questionnaire` | getSessionQuestionnaire | 영빈 ERD21 검토 |
| POST | `/api/survey-sessions/{sessionId}/complete` | completeSurvey | 영빈 ERD21 검토 |
| GET | `/api/survey-sessions/{sessionId}/migration-plan` | getMigrationPlan | 영빈 ERD21 검토 |
| POST | `/api/survey-sessions/{sessionId}/migrations` | migrateSurvey | 영빈 ERD21 검토 |
| POST | `/api/survey-sessions/{sessionId}/analyses` | requestAnalysis | 영빈 ERD21 검토 |
| GET | `/api/analysis-results/{resultId}` | getAnalysis | 영빈 ERD21 검토 |
| GET | `/api/jobs/{jobId}` | getJob | 가을 2026-11-04 활성화(작업 테이블 없음) |
| GET | `/api/chat-rooms` | listChats | 영빈 ERD21 검토 |
| GET | `/api/chat-rooms/{chatRoomId}` | getChat | 영빈 ERD21 검토 |
| POST | `/api/chat-rooms/{chatRoomId}/entries` | enterChat | 영빈 ERD21 검토 |
| GET | `/api/chat-rooms/{chatRoomId}/notices` | listNotices | 영빈 ERD21 검토 |
| PUT | `/api/chat-rooms/{chatRoomId}/notices/{noticeId}/ack` | ackNotice | 영빈 ERD21 검토 |
| POST | `/api/chat-rooms/{chatRoomId}/leave` | leaveChat | 영빈 ERD21 검토 |
| POST | `/api/chat-rooms/{chatRoomId}/reads` | acknowledgeDisplayedMessages | 영빈 ERD21 검토 |
| GET | `/api/users/{userId}/risk-analysis-consent` | getRiskConsent | 영빈 ERD21 검토 |
| PUT | `/api/users/{userId}/risk-analysis-consent` | setRiskConsent | 영빈 ERD21 검토 |
| POST | `/api/offline-encounters/sync` | syncEncounters | 영빈 ERD21 검토 |
| GET | `/api/match-proposals` | listProposals | 영빈 ERD21 검토 |
| GET | `/api/match-proposals/{proposalId}` | getProposal | 영빈 ERD21 검토 |
| GET | `/api/match-proposals/{proposalId}/partner-profile` | getProposalProfile | 영빈 ERD21 검토 |
| POST | `/api/match-proposals/{proposalId}/accept` | acceptProposal | 영빈 ERD21 검토 |
| POST | `/api/match-proposals/{proposalId}/decline` | declineProposal | 영빈 ERD21 검토 |
| POST | `/api/match-proposals/{proposalId}/explanations` | requestExplanation | 영빈 ERD21 검토 |
| GET | `/api/match-proposals/{proposalId}/explanation` | getExplanation | 영빈 ERD21 검토 |
| POST | `/internal/matching/evaluations` | evaluatePair | 영빈 ERD21 검토 |
| POST | `/internal/ai/explanations` | generateExplanation | 영빈 ERD21 검토 |
| POST | `/internal/ai/icebreakers` | generateIcebreakers | 영빈 ERD21 검토 |
| POST | `/internal/ai/risk-detections` | detectRisk | 영빈 ERD21 검토 |
| PUT | `/api/users/{userId}/preferences` | setPreferences | 다른 담당 기존 계약 |
| GET | `/api/users/{userId}/preferences` | getPreferences | 다른 담당 기존 계약 |
| PUT | `/api/users/{userId}/settings` | setSettings | 다른 담당 기존 계약 |
| GET | `/api/users/{userId}/settings` | getSettings | 다른 담당 기존 계약 |
| POST | `/api/admin/v1/rule-artifacts` | createArtifact | 가을 운영자 규칙 관리(57~60) |
| GET | `/api/admin/v1/rule-artifacts/{artifactId}` | getArtifact | 가을 운영자 규칙 관리(57~60) |
| PUT | `/api/admin/v1/rule-artifacts/{artifactId}` | updateArtifact | 가을 운영자 규칙 관리(57~60) |
| POST | `/api/admin/v1/releases` | createRelease | 가을 운영자 규칙 관리(57~60) |
| GET | `/api/admin/v1/releases/{releaseId}` | getRelease | 가을 운영자 규칙 관리(57~60) |
| POST | `/api/admin/v1/releases/{releaseId}/validate` | validateRelease | 가을 운영자 규칙 관리(57~60) |
| POST | `/api/admin/v1/releases/{releaseId}/approvals` | approveRelease | 가을 운영자 규칙 관리(57~60) |
| POST | `/api/admin/v1/releases/{releaseId}/publish` | publishRelease | 가을 운영자 규칙 관리(57~60) |
| GET | `/api/admin/v1/release-channels/current` | getReleaseChannel | 가을 운영자 규칙 관리(57~60) |
| PUT | `/api/admin/v1/release-channels/current` | activateRelease | 가을 운영자 규칙 관리(57~60) |

## 현재 MVP 제외·과거 계약 보존

제외 operation은 활성 paths에서 제거하고 x-excluded-operations에 원문 이력과 후속 형식을 보존한다. 제외 경로는 호출 가능한 API 목록이 아니다.

| Method | 보존 경로 | operationId | 구분 |
| --- | --- | --- | --- |
| GET | `/api/v1/users/me/profile/photos/{photoId}` | getPhotoStatus | 기존 제외 이력 유지 |
| POST | `/api/v1/auth/signup` | completeProfile | 기존 제외 이력 유지 |
| POST | `/api/reports` | createReport | 이번 담당 제외·후속 |
| POST | `/api/match-proposals/{proposalId}/cancel` | cancelProposalAcceptance | 이번 담당 제외·후속 |

## 이전 구현·정합화 이력

아래는 해당 날짜의 구현·검증 기록이다. 당시 테이블 수·DTO·경로·수치를 ERD21 현재 계약이나 실행 검증으로 인용하지 않는다.

## 2026-10-22 MR26 정합화

- POST `/api/v1/users/me/profile/complete`: 본문 없음 → 200 `{ onboardingStatus }`, 필수값 누락400 PROFILE_INCOMPLETE. 재요청200, 최초 완료시각 유지.
- `completed`는 명시적 등록 완료 여부이며, PATCH 임시 저장만으로 완료·성별/생년월일 잠금이 되지 않는다.
- AI_RISK_DETECTION은 최초 가입 필수. 가입 후 철회로 제안·매칭을 막지 않으며 채팅/AI 권한은 별도다. 키링 초기화 후 기존 제안 권한과 신규 매칭 준비를 분리한다.
- 오류 message는 Accept-Language별 문구, 미지원/미지정 시 한국어. Onboarding.questionCount만 미연결 임시값0을 허용한다.

## 2026-10-22 제안 정책 작업본

수락 취소, 공개 프로필 비교와 사용자별 공개 상태 변경은 [제안 계약](encounters-proposals.md#2026-10-22-제안-정책-정합화-작업본)과 [검증 이력](../../verification/proposal-policy-20261022.md)을 따른다. 수락 본문에 profileVersion이 필수로 추가되어 앱 적용 준비가 필요하다. 노션·ERDCloud 웹 동기화와 채팅 후속 구현은 아직 완료하지 않았다.

## 2026-10-22 채팅 기본 구현

채팅 저장·접근 권한·메시지·개별 읽음과 상호 수락 방 연결은 [채팅 계약](chat.md)을 따른다. HTTP 조회/전송 구현이며 실시간/푸시·신고 보관/삭제·웹 명세 반영·배포는 별도다. 기능 명세와 매칭 정책은 수정하지 않는다.

## 2026-10-22 안내·알림 구현

첫 입장/종료 안내·표시 ACK와 설치 세션별 종료 알림은 [안내·알림 계약](chat-notices.md)을 따른다. 첫 입장 확인 전에는 메시지 이용이 제한된다. 실제 공급자/앱 종료 상태 수신 및 웹 문서 반영은 별도 검증 대상이다.

2026-10-22 설문 회차는 [survey-sessions.md](survey-sessions.md)의 surveyVersion·SurveyManifest·public_id를 따른다. 공유 Manifest와 분석/이관의 미합의 UUID 계약은 유지한다. 기존 25/184에 최신 develop 키링 UUID를 포함해 현재 총계는 25테이블·185컬럼이며 [통합 검증](../../verification/api-erd-unified-20261022.md)에서 확인한다.
