# 기능 → API → ERD 대응표

수정 2026-10-22. 기존24개 기능의 API 대응을 최신화했으며 현재25테이블185컬럼이다. 최신 기능 명세 전체에 대한 설계 완료를 뜻하지 않는다. 구현·통합·출시 완료 표시가 아니다. 관리자9개·사진 처리 조회1개 및 통합 signup은 제외/폐기. 인증 연락 동기화 2026-10-21 14:16 KST.

[API](https://lab.ssafy.com/s15-final/S15P31E106/-/blob/934732229f83885faf0f0379d0e092c8a7605dbb/docs/contracts/api/README.md) / [ERD](https://lab.ssafy.com/s15-final/S15P31E106/-/blob/934732229f83885faf0f0379d0e092c8a7605dbb/docs/database/erd.md)

| 기능 ID | 현재 operationId | 저장 테이블 |
| --- | --- | --- |
| AUTH-01 | `socialLogin`, `refreshTokens`, `logout`, `listAgreements` | `app_users`, `refresh_tokens`, `user_consents` |
| AUTH-02 | `getOnboarding`, `submitAgreements`, `getSurveySession` | `app_users`, `user_consents`, `profiles`, `survey_responses`, `matching_settings` |
| KEY-01 | `purchaseInfo`, `createRegistrationChallenge`, `registerKeyring`, `listKeyrings` | `keyrings`, `keyring_bindings`, `registration_challenges` |
| KEY-02 | `createSync`, `getSync`, `ackSync` | `keyring_sync_runs`, `keyring_bindings`, `settings_snapshots`, `matching_settings` |
| PRO-01 | `checkNickname`, `getMyProfile`, `saveProfileDraft`, `completeProfile` | `profiles`, `app_users` |
| PRO-02 | `uploadPhoto`, `deletePhoto`, `getPartnerPhoto`, `getProposalPhoto` | `profiles`, `background_jobs` |
| PRO-03 | `saveProfileDraft`, `getChatProfile` | `profiles`, `matching_settings`, `settings_snapshots` |
| SUR-01 | `getQuestionnaire`, `createSurveySession`, `getSurveySession`, `getSessionQuestionnaire`, `saveSurveyAnswer`, `deleteSurveyAnswer`, `setSurveyMetadata`, `completeSurvey`, `getMigrationPlan`, `migrateSurvey` | `survey_responses` |
| SUR-02 | `requestAnalysis`, `getAnalysis`, `getJob` | `survey_responses`, `analysis_results`, `ai_results`, `background_jobs` |
| MAT-01 | `setPreferences`, `getPreferences` | `matching_settings`, `settings_snapshots` |
| MAT-02 | `setSettings`, `getSettings` | `matching_settings`, `settings_snapshots` |
| MAT-03 | `syncEncounters`, `evaluatePair` | `settings_snapshots`, `encounters`, `encounter_submissions`, `user_pairs` |
| MAT-04 | `getJob`, `requestExplanation`, `getExplanation`, `generateExplanation` | `encounters`, `match_proposals`, `ai_results`, `background_jobs` |
| PROP-01 | `listProposals`, `getProposal`, `getProposalProfile` | `match_proposals`, `profiles`, `user_pairs` |
| PROP-02 | `acceptProposal`, `declineProposal` | `match_proposals`, `user_pairs`, `background_jobs` |
| CHAT-01 | `acceptProposal`, `listChats`, `getChat` | `chat_rooms`, `chat_members`, `match_proposals`, `background_jobs` |
| CHAT-02 | `listMessages`, `sendMessage` | `chat_messages`, `chat_rooms`, `chat_members` |
| CHAT-03 | `enterChat`, `ackNotice` | `chat_members`, `background_jobs` |
| CHAT-04 | `listNotices`, `registerPush`, `removePush` | `push_installations`, `chat_members`, `background_jobs` |
| CHAT-05 | `getChat` | `chat_rooms`, `chat_members`, `background_jobs` |
| CHAT-06 | CHAT_DELETE 서버 작업(공개 명령 없음) | `chat_rooms`, `chat_members`, `chat_messages`, `ai_results`, `background_jobs` |
| AI-01 | `getJob`, `requestIcebreakers`, `getIcebreakers`, `generateIcebreakers` | `ai_results`, `background_jobs`, `chat_rooms` |
| AI-02 | `getRiskConsent`, `setRiskConsent`, `detectRisk` | `user_consents`, `ai_results`, `background_jobs` |
| SAFE-01 | `createReport`, `listBlocks`, `createBlock`, `removeBlock`, `leaveChat` | `reports`, `user_blocks`, `user_pairs`, `chat_members` |

## 서버 작업

| 작업 | 저장·업무 키/조건 |
| --- | --- |
| CHAT_CREATE | background_jobs, 제안 고유 업무 키·쌍 잠금·실제 방 연결 |
| FIRST_ENTRY | FIRST_ENTRY:room:user, 방 수명 동안 유지 |
| END_NOTICE | room:user:type, expires_at-1시간·지연 억제 |
| CHAT_EXPIRE | 실제 expires_at 효력·요청마다 접근 검사 |
| CHAT_DELETE | closed_at+720시간, 저장소별 결과·재시도 |
| AI | job_id·kind·target·model/prompt 버전, 선점 attempt/lease 검증 |
별도 outbox·전용 삭제/알림/AI 작업 테이블을 요구하지 않는다. 각 업무 등록은 해당 상태 저장과 원자 처리해야 한다. 실제 개발 이력은 다른 BE 팀원의 docs 폴더·양식 확인 후 맞춘다.
