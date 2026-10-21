# Tingle(팅글) API 명세서

1.2.0-auth-review / 수정 2026-10-21 14:16 KST

노션에서 검토한 **24개 테이블·162개 컬럼** 설계를 반영한 계약 초안이다. 현재 구현 대상으로 정리한 API는 **68개**, 범위에서 제외·폐기한 기존안은 **11개**다. 문서 정리와 팀 합의·실제 구현·DB 적용·배포 완료를 구분한다.

- [OpenAPI: 요청/응답 타입·필수값·예시](openapi.json)
- [기존안·수정안과 API별 변경 이력](changes-20261021.md)
- [ERD·컬럼 설명](../../database/erd.md) / [SQL](../../database/schema.sql)
- [설문 버전 파일 계약](../survey/versioning.md) / [인증 확인 사항](auth-recheck.md) / [기능 대응표](../../specifications/coverage.md)
- [노션 API 명세서](https://app.notion.com/p/API-4a2c91ccb660820793de81b259578314)

## 공통 계약

- Spring Boot·JPA·PostgreSQL 기준이다. Flyway·H2는 사용하지 않는다. local은 update, test는 create-drop이며 dev/prod 스키마 운영과 Docker·인프라는 준비 후 정한다.
- 앱은 제공자 증명을 서버에 전달해 자체 access/refresh를 발급받고 OS 보호 저장소에 보관한다. 서버는 JWT 주체와 현재 권한을 검증한다. 앱이 보낸 회원번호를 인증 근거로 사용하지 않는다.
- 소셜·refresh 요청은 유지하고 새 토큰 응답6개(accessToken/refreshToken/tokenType/accessTokenExpiresAt/refreshTokenExpiresAt/onboardingStatus)를 구현 기준 초안으로 반영했다. 기존 accessTokenExpiresIn/signupCompleted의 병행 유지·전환은 AUTH-OPEN-01 합의 필요다. sid/로그아웃/회전 범위는 AUTH-OPEN-03·04를 확인하며 보안 검사를 임의 제거하지 않는다.
- 공개 UUID 계약은 내부 bigint 변경으로 자동 교체하지 않는다. 자원별 매핑·형식은 FE/AI/IoT와 확인할 사항이다. 상대의 내부 회원번호·생년월일·소셜ID·원응답·기기 자격은 공개 DTO에서 제외한다.
- 가입 단계는 AGREEMENT_REQUIRED → KEYRING_REQUIRED → PROFILE_REQUIRED → SURVEY_REQUIRED → PREFERENCES_REQUIRED → SYNC_PENDING → READY다. nextStep과 onboardingStatus는 같은 서비스에서 계산한다. POST /users/me/agreements + PATCH /users/me/profile로 분리하며 기존 POST /auth/signup은 폐기했다. 현재 코드의 미구현 가입 게이트는 AUTH-OPEN-06으로 구분한다.
- 서버가 단계별 호출·소유권을 검사한다. 키링 등록/조회 등 가입 진행 API는 해당 단계에 허용하되 모든 후속 API를 일괄 허용하지 않는다.
- 성공 응답은 감싸지 않는다. 목록은 items 객체를 사용한다. 오류의 필수값은 code,message이며 추적 정보는 선택 확장이다. 상태 코드는 endpoint별 OpenAPI 계약을 따른다.
- UTC ISO8601 시각을 주고받으며 나이는 Asia/Seoul 날짜로 계산한다. 만19세 이상, 윤일생은 평년3월1일에 나이가 바뀐다. 현재시각이 기한과 같아지면 만료다.
- 닉네임은 NFC·앞뒤 ASCII공백 제거 후2~20자, 한글·영문·숫자·공백이다. 소개는 보이는 글자200개·LF3개 이하이며 text로 저장한다. FE/BE 분할 규칙을 동일하게 확인한다.
- 조건 헤더는 설문 revision 등 실제 조건부 갱신을 하는 경로에 적용한다. 프로필 profileVersion/ETag/If-Match 필수 구조는 제외한다. JSON 미정의 필드는 거절하고 문항 응답은 해당 버전 파일 schema로 추가 검사한다.
- 기존 Idempotency-Key 요청 계약은 유지하지만 별도 idempotency_records 테이블을 요구하지 않는다. 자원별 업무 키·유일 제약과 원자 저장으로 같은 입력 재시도를 판정한다. 안전한 응답 재현을 보장하지 못하는 경로의 헤더 제거/대체는 호출자와 합의한다.
- 민감 응답은 Cache-Control:no-store. 토큰·생년월일·원응답·메시지·기기 자격을 로그에 기록하지 않는다. 401 만료 시 공유 갱신과 명시적 위조/폐기/403의 처리 구분은 필요하다. 현재 인증 소스의 UNAUTHORIZED 단일 코드와 앱 갱신 조건의 차이는 AUTH-OPEN-04 합의 전이며 양쪽 방식이 이미 일치한다고 표현하지 않는다.

## 기능별 저장·시간 기준

| 기능 | 현재 기준 |
| --- | --- |
| 사진 | 검증된 URL 한 장을 profiles.image_url에 저장. JSON imageUrl POST→200. 실패 시 기존 URL 유지, 삭제는 NULL. 별도 사진 행·crop·PROCESSING/READY 조회 제외. 업로드·소유권·전달 URL·실제 파일 삭제는 별도 계약 확인 |
| 희망 조건/설정 | matching_settings 한 행. 변경 필드만 갱신하고 settings_version을 원자 증가시켜 snapshot 생성. 나이 ANY는 양 경계NULL, RANGE는 하한19 이상·상한NULL 또는 하한 이상 |
| 설문 | survey_responses의 answers_json·revision·completed_at. 진행 중 회차 하나, 조건부 저장, 완료 후 불변. sessionId 외부 이름은 유지하고 내부 response_id와 대응 |
| 개인 분석 | analysis_results에 완료 원응답+version_code별 결과. inputHash/결과 생성시각 필수 응답 제외. 완료시각을 생성시각으로 대신 쓰지 않음 |
| 동기화 | 같은 binding+snapshot의 실행을 재사용, 새 생성201/재사용200. 전송만으로 READY가 아니며 ACK·버전·증명을 검증. payloadHash/status 일부는 원본에서 계산, 정확한 IoT37 계약 필요 |
| 만남/평가 | encounters+encounter_submissions. 판정과 버전은 evaluation_json/evaluation_version_code, 별도 evaluationId 대신 encounterId. 기회7일·상대 대기·제안24시간은 각각 검사 |
| 제안 | match_proposals의 두 선택·최초 결정시각. 거절/수락 시각으로 resolvedAt 계산. 최초 거절+30일 제한을 재시도로 연장하지 않음. 사진 옵션 변경 전 제안은 유지하고 현재 프로필을 표시 |
| 채팅 | 최초 방 생성+168시간에 종료. 요청마다 시간/참여 검사. 정상 closed_at=expires_at, delete_at=closed_at+720시간. 배치 지연·재접속으로 기한 연장 없음 |
| 안내 | chat_members의 두 표시 ACK와 background_jobs의 사용자별 최초 입장/종료1시간 전 업무 키. noticeId는 작업을 가리키는 호환 식별자이며 소유·종류·방을 재검사 |
| 메시지 | 서버 message_id, 앱 재전송 client_message_id, 방 안 sequence 모두 유지. 같은 키·다른 본문 충돌. 원문 큐/로그 복제 금지 |
| 후속 처리 | background_jobs로 예약·실패 재시도 통합. business_key 유일, 선점마다 attempts 증가, 완료 시 자신의 attempt·RUNNING·lease 유효성 검사. 별도 outbox/삭제/AI 작업 테이블 없음 |
| AI | ai_results.job_id가 결과PK. kind+target을 함께 확인. 개인 분석과 두 사람 비교·참고 추천은 별개. 동의·허용 문맥·최소 입력·종료 후 접근 차단 유지 |

## 미정 계약과 적용 순서

기존 POL-01~10 중 생활조건/계산 충돌, IoT 증명·시간·오프라인 처리, 사진 전달/삭제, 종료 안내 채널, 위험분석 동의/외부전송, 메시지 부가기능, 조기 종료·차단/해제·재매칭, 보관 목적·백업/외부 사본 삭제는 해당 상세 명세의 확인 사항으로 유지한다. 윤일 계산은 평년3월1일로 확정됐으며 이를 미정으로 되돌리지 않는다.

확정 계약 검토 → 저장소 명세 교차 검토 → 관련 구현과 로컬 검증 → 가상 키링 통합 → 실제 외부/실물/배포 검증 순으로 진행한다. 현재 단계는 문서 반영이다. DB DDL 실행·원격 게시·MR 병합은 하지 않았다. 실제 개발 이력은 다른 BE 팀원의 docs 위치·양식을 확인한 후 맞춘다.

## 인증 담당 연락 반영 및 합의 필요

약관 동의 API 추가·통합 signup 폐기·새 토큰 응답을 반영했다. 현재 구현과 기존 앱의 호환을 구분한다.

| ID | 합의할 내용 | 현재 처리 |
| --- | --- | --- |
| AUTH-OPEN-01 | 새 토큰6개 필드 채택·기존 accessTokenExpiresIn/signupCompleted 병행 유지·전환 시점 | 구현된 새 응답을 명세 초안에 반영. 구형 필드 미제공은 호환 승인 완료가 아님 |
| AUTH-OPEN-02 | 422 PROFILE_FIELD_LOCKED 이름·잠금 시작 시점·POL-02 변경 허용 정책 | 임시 코드로 표시. 기존 POLICY_UNRESOLVED와 구분 |
| AUTH-OPEN-03 | sid=refresh family 채택·로그아웃/탈취의 기기별·전체 폐기 범위·30초 유실 재시도 | 구현을 기록하되 기존 폐기 검사를 임의 제거하지 않음 |
| AUTH-OPEN-04 | 401 만료·위조·폐기 구분과 앱 자동 갱신 조건 | 현재 코드 UNAUTHORIZED와 통합 명세의 갱신 분기 차이를 미정으로 남김 |
| AUTH-OPEN-05 | app_users.email·물리 FK 및 text/길이 차이, 인증/생활정보 전송 코드 | ERD/DDL 변경 없음. 현재4개 테이블명은 일치하지만 email/FK1개는 별도 조정 |
| AUTH-OPEN-06 | 미구현 가입 게이트·단계 조회 추가 필드·프로필 필수 nullable/미정의 필드 검증 | 코드의 부분 구현과 목표 계약을 구분. 프로필 완료만으로 READY 처리하지 않는 정책 유지 |

상세 요청·응답/구현 차이와 인계 순서는 [인증 확인 사항](auth-recheck.md)을 따른다. 노션은 이번에 수정하지 않았으며 저장소의 신규/변경 계약은 노션과 후속 동기화가 필요하다.

## 기존 38번 계약과의 적용 기준

전송 필드·필수값·HTTP 상태·오류 코드는 이 디렉터리의 OpenAPI를 기준으로 한다. 기존 38번 `encounters-proposals.md`는 상태·시간·동시성 흐름을 보완한다. 두 문서를 함께 검토한 뒤 서버와 앱의 오류 분기를 같은 배포 단위로 맞춘다. 문서 변경만으로 기존 구현의 호환성이 보장되지는 않는다.

| 기존안 | 수정안 | 적용 대상 |
| --- | --- | --- |
| 404 PROPOSAL_NOT_FOUND | 404 RESOURCE_NOT_FOUND | 제안 없음·타인 접근 |
| 409 PROPOSAL_EXPIRED | 410 PROPOSAL_EXPIRED | 수락·거절 마감 |
| 409 PROFILE_ACCESS_ENDED | 410 PROPOSAL_EXPIRED | 종료된 본인 제안의 상대 프로필 접근 |
| 503 TEMPORARILY_UNAVAILABLE | 503 SERVICE_UNAVAILABLE | 일시 장애 |
| profileImageId·profileVersion·proposalId가 있는 공개 프로필 | imageUrl·hasProfilePhoto, 그 외 PublicProfile 허용 필드 | 사진 없으면 imageUrl=null |
| 자발적 퇴장 후 영구 재매칭 제한 | 제한 유무·기간 미정(POL-09), 합의 전 422 | 자동 만료와 구분 |

404는 존재 여부 노출을 막기 위해 소유권부터 확인하고, 본인 자원인 경우에만 기한·상태를 판정한다. 409는 상태·멱등 충돌에 사용한다. 공통 오류 전체와 경로별 지원 여부는 OpenAPI를 따른다. 공개 프로필 버전/ETag를 새 필수값으로 추가하지 않는다.

## 현재 API 목록

| Method | Path | 기능 | 요청 → 성공 응답 | operationId |
| --- | --- | --- | --- | --- |
| POST | `/api/v1/auth/{provider}` | 소셜 로그인 (수정 2026-10-21 14:16) | TokenRequest → 200 TokenPair | `socialLogin` |
| POST | `/api/v1/auth/token/refresh` | 토큰 재발급 (수정 2026-10-21 14:16) | RefreshRequest → 200 TokenPair | `refreshTokens` |
| POST | `/api/v1/auth/logout` | 현재 로그인 로그아웃 (수정 2026-10-21 14:16) | RefreshRequest → 204 본문 없음 | `logout` |
| GET | `/api/v1/agreements` | 약관 조회 | 없음 → 200 Agreements | `listAgreements` |
| POST | `/api/v1/users/me/agreements` | 약관 동의 저장 (수정 2026-10-21 14:16) | AgreementConsentInput → 200 AgreementConsentResult | `submitAgreements` |
| GET | `/api/v1/users/me/onboarding` | 다음 미완료 단계 조회 (수정 2026-10-21 14:16) | 없음 → 200 Onboarding | `getOnboarding` |
| GET | `/api/v1/users/nickname-availability` | 닉네임 중복 확인 | 없음 → 200 NicknameAvailability | `checkNickname` |
| GET | `/api/v1/keyrings/purchase-info` | 구매 안내 | 없음 → 200 PurchaseInfo | `purchaseInfo` |
| POST | `/api/v1/keyrings/registration-challenges` | 등록 증명 challenge 발급 | 없음 → 201 Challenge | `createRegistrationChallenge` |
| POST | `/api/v1/keyrings` | 키링 소유 증명 후 등록 | RegisterKeyring → 201 Keyring | `registerKeyring` |
| GET | `/api/v1/keyrings` | 본인 키링 조회 | 없음 → 200 Keyrings | `listKeyrings` |
| GET | `/api/v1/users/me/profile` | 본인 프로필 조회 (수정 2026-10-21 14:16) | 없음 → 200 Profile | `getMyProfile` |
| PATCH | `/api/v1/users/me/profile` | 프로필 초안·수정 저장 (수정 2026-10-21 14:16) | ProfileDraft → 200 Profile | `saveProfileDraft` |
| POST | `/api/v1/users/me/profile/photos` | 사진 업로드·처리 시작 (수정 2026-10-21 11:12) | ImageUrlInput → 200 CurrentPhoto | `uploadPhoto` |
| DELETE | `/api/v1/users/me/profile/photo` | 현재 사진 삭제 (수정 2026-10-21 11:24) | 없음 → 204 본문 없음 | `deletePhoto` |
| PUT | `/api/v1/users/me/preferences` | 양방향 기본 희망 조건 저장 (수정 2026-10-21 11:12) | Preferences → 200 Preferences | `setPreferences` |
| GET | `/api/v1/users/me/preferences` | 희망 조건 조회 (수정 2026-10-21 11:12) | 없음 → 200 Preferences | `getPreferences` |
| PUT | `/api/v1/users/me/settings` | 사진 옵션·매칭 설정 저장 (수정 2026-10-21 11:13) | SettingsInput → 200 Settings | `setSettings` |
| GET | `/api/v1/users/me/settings` | 설정 조회 (수정 2026-10-21 11:13) | 없음 → 200 Settings | `getSettings` |
| POST | `/api/v1/keyrings/{deviceId}/sync-runs` | 최신 설정 동기화 생성 (수정 2026-10-21 11:24) | 없음 → 201 SyncRun / 200 SyncRun | `createSync` |
| GET | `/api/v1/keyrings/{deviceId}/sync-runs/{syncRunId}` | 동기화 상태 조회 (수정 2026-10-21 11:24) | 없음 → 200 SyncRun | `getSync` |
| POST | `/api/v1/keyrings/{deviceId}/sync-runs/{syncRunId}/ack` | 기기 저장 ACK 검증 (수정 2026-10-21 11:24) | SyncAck → 200 SyncRun | `ackSync` |
| GET | `/api/v1/surveys/current` | 현재 게시 설문 정의 (수정 2026-10-21 11:21) | 없음 → 200 Questionnaire | `getQuestionnaire` |
| POST | `/api/v1/survey-sessions` | 버전 고정 설문 시작 (수정 2026-10-21 11:21) | CreateSession → 201 SurveySession | `createSurveySession` |
| GET | `/api/v1/survey-sessions/{sessionId}` | 응답 복원·진행 조회 (수정 2026-10-21 11:22) | 없음 → 200 SurveySession | `getSurveySession` |
| GET | `/api/v1/survey-sessions/{sessionId}/questionnaire` | 세션에 고정된 문항 정의 (수정 2026-10-21 11:22) | 없음 → 200 Questionnaire | `getSessionQuestionnaire` |
| PUT | `/api/v1/survey-sessions/{sessionId}/answers/{questionId}` | 문항별 임시 저장 (수정 2026-10-21 11:23) | AnswerInput → 200 SurveySession | `saveSurveyAnswer` |
| DELETE | `/api/v1/survey-sessions/{sessionId}/answers/{questionId}` | 초안 응답 제거 (수정 2026-10-21 11:23) | 없음 → 200 SurveySession | `deleteSurveyAnswer` |
| PUT | `/api/v1/survey-sessions/{sessionId}/metadata` | 선택 나이 메타데이터 저장 (수정 2026-10-21 11:23) | SurveyMetadata → 200 SurveySession | `setSurveyMetadata` |
| POST | `/api/v1/survey-sessions/{sessionId}/complete` | 응답 완료 확정 (수정 2026-10-21 11:23) | 없음 → 200 SurveySession | `completeSurvey` |
| GET | `/api/v1/survey-sessions/{sessionId}/migration-plan` | 새 버전 이관 미리보기 (수정 2026-10-21 11:23) | 없음 → 200 MigrationPlan | `getMigrationPlan` |
| POST | `/api/v1/survey-sessions/{sessionId}/migrations` | 원본 보존 후 새 버전 세션 생성 (수정 2026-10-21 11:23) | MigrateSession → 201 SurveySession | `migrateSurvey` |
| POST | `/api/v1/survey-sessions/{sessionId}/analyses` | 개인 성향·유형·분석 요청 (수정 2026-10-21 11:23) | AnalysisRequest → 202 Job | `requestAnalysis` |
| GET | `/api/v1/analysis-results/{resultId}` | 개인 결과 조회 (수정 2026-10-21 11:23) | 없음 → 200 AnalysisResult | `getAnalysis` |
| GET | `/api/v1/jobs/{jobId}` | 본인 비동기 작업 조회 (수정 2026-10-21 11:23) | 없음 → 200 Job | `getJob` |
| POST | `/api/v1/offline-encounters/sync` | 만남 기록 내구성 접수 (수정 2026-10-21 11:13) | EncounterBatch → 200 Receipts | `syncEncounters` |
| GET | `/api/v1/match-proposals` | 본인 제안 목록 (수정 2026-10-21 11:20) | 없음 → 200 ProposalPage | `listProposals` |
| GET | `/api/v1/match-proposals/{proposalId}` | 제안 상태 조회 (수정 2026-10-21 11:21) | 없음 → 200 Proposal | `getProposal` |
| GET | `/api/v1/match-proposals/{proposalId}/partner-profile` | 수락 전 최신 공개 프로필 (수정 2026-10-21 11:21) | 없음 → 200 PublicProfile | `getProposalProfile` |
| POST | `/api/v1/match-proposals/{proposalId}/accept` | 제안 수락 (수정 2026-10-21 11:22) | 없음 → 200 Proposal | `acceptProposal` |
| POST | `/api/v1/match-proposals/{proposalId}/decline` | 제안 거절 (수정 2026-10-21 11:22) | 없음 → 200 Proposal | `declineProposal` |
| POST | `/api/v1/match-proposals/{proposalId}/explanations` | 실제 매칭 근거 설명 (수정 2026-10-21 11:13) | 없음 → 202 Job | `requestExplanation` |
| GET | `/api/v1/match-proposals/{proposalId}/explanation` | 생성·대체 설명 조회 (수정 2026-10-21 11:16) | 없음 → 200 Explanation | `getExplanation` |
| GET | `/api/v1/chat-rooms` | 참여 채팅 목록 (수정 2026-10-21 11:21) | 없음 → 200 ChatPage | `listChats` |
| GET | `/api/v1/chat-rooms/{chatRoomId}` | 채팅 수명·권한 조회 (수정 2026-10-21 11:21) | 없음 → 200 ChatRoom | `getChat` |
| GET | `/api/v1/chat-rooms/{chatRoomId}/partner-profile` | 활성 채팅 상대 프로필 (수정 2026-10-21 11:24) | 없음 → 200 PublicProfile | `getChatProfile` |
| GET | `/api/v1/chat-rooms/{chatRoomId}/partner-photo` | 권한 검사 후 현재 사진 바이트 (수정 2026-10-21 11:24) | 없음 → 200 string | `getPartnerPhoto` |
| GET | `/api/v1/match-proposals/{proposalId}/partner-photo` | 유효 제안의 사진 바이트 (수정 2026-10-21 11:24) | 없음 → 200 string | `getProposalPhoto` |
| POST | `/api/v1/chat-rooms/{chatRoomId}/entries` | 사용자별 첫 입장 안내 확보 (수정 2026-10-21 11:24) | 없음 → 200 Entry | `enterChat` |
| GET | `/api/v1/chat-rooms/{chatRoomId}/notices` | 본인 종료 안내 조회 (수정 2026-10-21 11:24) | 없음 → 200 Notices | `listNotices` |
| PUT | `/api/v1/chat-rooms/{chatRoomId}/notices/{noticeId}/ack` | 안내 표시 확인 (수정 2026-10-21 11:24) | 없음 → 200 Ack | `ackNotice` |
| GET | `/api/v1/chat-rooms/{chatRoomId}/messages` | 활성방 메시지 조회 | 없음 → 200 MessagePage | `listMessages` |
| POST | `/api/v1/chat-rooms/{chatRoomId}/messages` | 텍스트 메시지 저장 | SendMessage → 201 Message | `sendMessage` |
| POST | `/api/v1/chat-rooms/{chatRoomId}/icebreakers` | 질문 3개 생성 (수정 2026-10-21 11:12) | 없음 → 202 Job | `requestIcebreakers` |
| GET | `/api/v1/chat-rooms/{chatRoomId}/icebreakers` | 생성·대체 질문 조회 (수정 2026-10-21 11:12) | 없음 → 200 Icebreakers | `getIcebreakers` |
| GET | `/api/v1/users/me/risk-analysis-consent` | 분석 안내·동의 조회 (수정 2026-10-21 11:12) | 없음 → 200 RiskConsent | `getRiskConsent` |
| PUT | `/api/v1/users/me/risk-analysis-consent` | 위험 대화 분석 설정 (수정 2026-10-21 11:12) | RiskConsentInput → 200 RiskConsent | `setRiskConsent` |
| POST | `/api/v1/reports` | 신고 접수 | ReportInput → 201 Report | `createReport` |
| GET | `/api/v1/blocks` | 본인 차단 목록 (수정 2026-10-21 11:21) | 없음 → 200 Blocks | `listBlocks` |
| POST | `/api/v1/blocks` | 문맥 상대 차단 (수정 2026-10-21 11:24) | BlockInput → 201 Block | `createBlock` |
| DELETE | `/api/v1/blocks/{blockId}` | 본인 차단 해제 (수정 2026-10-21 11:24) | 없음 → 204 본문 없음 | `removeBlock` |
| POST | `/api/v1/chat-rooms/{chatRoomId}/leave` | 자발적 채팅 퇴장 | 없음 → 200 LeaveResult | `leaveChat` |
| PUT | `/api/v1/push-installations` | 종료 안내 단말 등록 | PushToken → 200 Ack | `registerPush` |
| DELETE | `/api/v1/push-installations/{installationId}` | 본인 단말 푸시 해제 | 없음 → 204 본문 없음 | `removePush` |
| POST | `/internal/v1/matching/evaluations` | 스냅샷 기반 실제 쌍 비교 (수정 2026-10-21 11:20) | EvaluationInput → 200 Evaluation | `evaluatePair` |
| POST | `/internal/v1/ai/explanations` | AI 근거 설명 (수정 2026-10-21 11:20) | AiInput → 200 Explanation | `generateExplanation` |
| POST | `/internal/v1/ai/icebreakers` | AI 시작 질문 (수정 2026-10-21 11:12) | AiInput → 200 Icebreakers | `generateIcebreakers` |
| POST | `/internal/v1/ai/risk-detections` | AI 위험 판정 (수정 2026-10-21 11:12) | AiInput → 200 RiskResult | `detectRisk` |

## 현재 구현 범위에서 제외한 기존안

새 서버의 활성 endpoint로 취급하지 않는다. 원래 경로와 DTO는 OpenAPI의 x-excluded-operations에 보존한다.

| Method | Path | 기능 | 사유 |
| --- | --- | --- | --- |
| GET | `/api/v1/users/me/profile/photos/{photoId}` | 사진 처리 상태 (수정 2026-10-21 11:24) | 사진별 비동기 처리 모델 제외 |
| POST | `/admin/v1/rule-artifacts` | 규칙 초안 생성 (수정 2026-10-21 11:23) | 불변 버전 파일 운영으로 단순화. 관리자 실시간 게시/롤백 보류 |
| GET | `/admin/v1/rule-artifacts/{artifactId}` | 규칙·스키마 조회 (수정 2026-10-21 11:23) | 불변 버전 파일 운영으로 단순화. 관리자 실시간 게시/롤백 보류 |
| PUT | `/admin/v1/rule-artifacts/{artifactId}` | 초안 수정 (수정 (수정 2026-10-21 11:23) | 불변 버전 파일 운영으로 단순화. 관리자 실시간 게시/롤백 보류 |
| POST | `/admin/v1/releases` | 호환 버전 조합 생성 (수정 2026-10-21 11:23) | 불변 버전 파일 운영으로 단순화. 관리자 실시간 게시/롤백 보류 |
| GET | `/admin/v1/releases/{releaseId}` | 배포 manifest 조회 (수정 2026-10-21 11:23) | 불변 버전 파일 운영으로 단순화. 관리자 실시간 게시/롤백 보류 |
| POST | `/admin/v1/releases/{releaseId}/validate` | 스키마·참조·회귀 검증 (수정 2026-10-21 11:23) | 불변 버전 파일 운영으로 단순화. 관리자 실시간 게시/롤백 보류 |
| POST | `/admin/v1/releases/{releaseId}/publish` | 검증된 버전 불변 게시 (수정 2026-10-21 11:23) | 불변 버전 파일 운영으로 단순화. 관리자 실시간 게시/롤백 보류 |
| POST | `/admin/v1/releases/{releaseId}/approvals` | 작성자 외 검토 승인 (수정 2026-10-21 11:23) | 불변 버전 파일 운영으로 단순화. 관리자 실시간 게시/롤백 보류 |
| PUT | `/admin/v1/release-channels/current` | 신규 세션 기본 버전 전환·롤백 (수정 2026-10-21 11:23) | 불변 버전 파일 운영으로 단순화. 관리자 실시간 게시/롤백 보류 |
| POST | `/api/v1/auth/signup` | 통합 가입 (폐기 2026-10-21 14:16) | 약관 동의와 프로필 PATCH로 대체 |
