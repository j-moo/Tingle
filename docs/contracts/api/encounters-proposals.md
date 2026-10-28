# Tingle(팅글) 만남 기록과 매칭 제안 API 명세

v0.2.0-erd21-review / 2026-10-28 / 담당 김영빈 / S15P31E106-91·92

사용자 제공 21개 테이블·151개 컬럼과 기존 노션 API의 경로·표·예시를 맞춘 검토 계약이다. 현재 전송 기준은 [OpenAPI](openapi.json), 전체 범위는 [API 안내](README.md), 데이터·미합의는 [ERD 설명](../../database/erd.md)을 따른다. 서버·기기·실DB·배포 실행 결과와 구분한다. 기획·기능·정책 원문은 수정하지 않는다.

## 1 경로·식별자·권한

기존 담당 경로는 `/api` 기준이며 `/v1`이 없다. proposalId와 내부 encounterId는 같은 `matching.id` integer(int64)다. 만남 대기 WAITING/NOT_MATCHED 행을 공개 제안으로 노출하지 않는다. chatRoomId는 `chat.id` nullable int64, deviceId는 `keyring.id` int64다. submissionId/eventId와 Idempotency-Key는 각 역할의 UUID를 유지한다.

서버가 Bearer 주체·가입 단계·본인 소유 기기·제안 당사자·현재 접근권한을 검사한다. 타인의 제안·프로필은404 RESOURCE_NOT_FOUND로 숨기고, 본인의 접근 종료·마감은 해당 operation의410을 유지한다. 민감 응답은 no-store, 상대 내부 회원번호·생년월일·원응답·기기 비밀은 공개하지 않는다.

## 2 만남 기록 접수

`POST /api/offline-encounters/sync`는 EncounterBatch의 숫자 deviceId와1~100 submissions 형식을 사용한다. 제출 기기는 현재 REGISTERED이며 토큰 회원 소유여야 한다. 기록 당시 소유 구간·설정/이벤트 세대·서명·시각을 별도로 검증한다. 현재 소유 확인만으로 이전 소유자의 기록을 승인하지 않는다.

| 입력 | 형식 | 역할과 남은 합의 |
| --- | --- | --- |
| deviceId | integer(int64) 필수 | keyring.id, 현재 소유·등록 검증 |
| submissionId / eventId | UUID 필수 | 클라이언트 제출/이벤트 상관값. 공개 DB PK가 아님 |
| occurredAt | UTC date-time 필수 | 증명과 함께 검증할 만남 시각 |
| policyVersion / matchingRuleVersion | 기존 버전 string 필수 | 기록 당시 정책·계산 파일 대응, 소급/시각 오차는 IoT·제품 합의 |
| settingsSnapshotId | UUID 필수 legacy 계약 | ERD21 저장 정보 없음. 당시 입력·소유 세대의 지속 증거 대안 미합의 |
| peerCredential / evidence | 기존 불투명 값·객체 | 임의 payload를 유효 증명으로 인정하지 않음. 원문·기기 비밀 외부 전송 금지 |

검증 기록은 matching 및 matching_participant.encounter_keyring_id/encounter_signature/encounter_reported_at/encounter_verified_at에 연결한다. 한 앱이 상대의 독립 서명을 대신 만들 수 없다. 서명 실패·대기 만료·정책 부족을 성공 제안으로 표시하지 않는다.

ERD에는 독립 receiptId 저장 칸과 원본 삭제 후 재전송 판정 증거가 없다. 지속 저장·내용 충돌 판정·기존 결과 재현을 검증하기 전 `canDeleteLocalRecord=true`를 보장하지 않는다. 다음은 안전한 **형식 초안**이며 모든 운영 요청의 고정 결과가 아니다.

```json
{
  "results": [{
    "submissionId": "20000000-0000-4000-8000-000000000001",
    "eventId": "30000000-0000-4000-8000-000000000001",
    "receiptStatus": "NOT_STORED",
    "receiptId": null,
    "canDeleteLocalRecord": false,
    "encounterStatus": null,
    "counterpartDeadlineAt": null,
    "retryable": false,
    "errorCode": "POLICY_UNRESOLVED"
  }]
}
```

같은 이벤트 재전송은 기한·검증·제안 생성을 새 만남으로 초기화하지 않는다. WAITING/NOT_MATCHED 행 재사용에는 실제 새 이벤트·소유/설정 세대의 증명이 필요하다. 초기화 대상과 세대별 삭제 조건은 ERD 설명을 따른다. blocked_at은 지우지 않는다. 과거 삭제 예약·늦은 검증/AI 응답이 새 행 세대에 적용되지 않아야 하며, 마감시각 하나를 세대 증거로 대신하지 않는다.

## 3 서버 시각과 관계 상태

| 값 | 기준 |
| --- | --- |
| met_at·policy_version·기회/상대 대기 마감 | 검증된 만남과 당시 서버 정책 파일로 고정. 기간·시각 오차·정책 소급은 IoT·제품 합의 항목 |
| Proposal.createdAt | matching.proposed_at: 양쪽 검증 후 서버가 제안 생성을 최초 확정한 시각. 푸시 발송·수신·첫 조회 시각 아님 |
| Proposal.expiresAt | proposed_at+24시간. 정각부터 새 수락·거절 불가. 재시도·재시작·알림 실패로 연장하지 않음 |
| chat.created_at·만료 | 상호 수락과 방·참여자 원자 생성의 최초 시각, 이용기한은 그+168시간 |
| raw_delete_at | 제안 생성·대기 만료·최종 검증 불가 기준+14일. matching 행이 아닌 encounter_* 원본 칸 정리. 탈퇴 전 관계 제한은 유지 |

API status는 기존 PENDING/WAITING_FOR_OTHER/ACCEPTED/REJECTED/EXPIRED를 본인 기준으로 투영한다. myDecision NONE/ACCEPT/DECLINE은 본인 response NULL/ACCEPTED/REJECTED에 대응한다. 상대 선행 응답·차단·ONE_ACCEPTED 내부값을 미응답자에게 그대로 공개하지 않는다. responded_at은 최초 서버 응답 확정 시각이다.

## 4 제안 조회·공개 프로필

| API | 내용 |
| --- | --- |
| GET /api/match-proposals | 본인 목록, createdAt 내림차순·proposalId 보조키, 불투명 cursor |
| GET /api/match-proposals/{proposalId} | 본인 상태·선택·고정 기한·방 준비 여부 |
| GET /api/match-proposals/{proposalId}/partner-profile | 승인된 현재 공개 프로필, 종료된 본인 접근은410 |

```json
{
  "proposalId": 1001,
  "status": "PENDING",
  "myDecision": "NONE",
  "createdAt": "2026-10-20T05:00:00Z",
  "expiresAt": "2026-10-21T05:00:00Z",
  "chatAvailable": false,
  "chatRoomId": null
}
```

PublicProfile DTO 모양은 유지하고 profile의 허용 필드와 photo_url을 imageUrl로 투영한다. profileVersion은 공개 표시 필드의 서버 계산 비교 해시이며 별도 저장 컬럼을 가정하지 않는다. 상대 비공개 선호·설문 원응답·생년월일·소셜ID·내부회원번호·정밀 만남/기기 정보를 공개하지 않는다. 종교 비공개·미입력을 무교로 추정하지 않는다. 현재 나이는 한국 날짜 기준이다.

사진 삭제 후 imageUrl=null·hasProfilePhoto=false를 반환하고 앱은 기존 사진 캐시를 정리한다. 사진·현재 나이·공개 프로필 변경이나 사진 옵션 변경으로 기존 제안을 자동 종료하지 않는다. 미수락자는 변경된 공개 정보 재확인 후 다시 수락하며 이미 확정한 수락은 유지한다. 타 담당 상대 사진 경로·본인 프로필/사진 API는 이번 수정에서 덮어쓰지 않는다.

## 5 수락·거절과 원자 방 생성

`POST /api/match-proposals/{proposalId}/accept`는 기존 profileVersion 요청을 유지한다. `POST .../decline`은 본문이 없다. 기존 UUID Idempotency-Key 헤더 계약을 보존하지만 선택 키 전용 저장 칸이 없는 ERD21에서 영속 중복 판정·다른 명령 재사용 검출·응답 재현을 보장한 것으로 기술하지 않는다. 해당 저장 대안은 합의 전이다.

matching 단위 잠금 안에서 당사자·현재 상태·기한·차단·공개 필드 해시를 재검사하고 최초 response·responded_at을 확정한다. 응답은 한 번 확정한 뒤 되돌리지 않는다. 동시 두 번째 수락·거절·만료는 일관된 한 결과로 직렬화한다. 상호 수락, chat 생성, 두 chat_participant 생성은 같은 트랜잭션에서 완료한다. 중복 수락은 같은 방·최초 시각을 사용하며 방이 실제 준비되기 전 chatAvailable=true를 보내지 않는다.

거절 응답의 형식 예시:

```json
{
  "proposalId": 1001,
  "status": "REJECTED",
  "myDecision": "DECLINE",
  "createdAt": "2026-10-20T05:00:00Z",
  "expiresAt": "2026-10-21T05:00:00Z",
  "chatAvailable": false,
  "chatRoomId": null
}
```

기존 제품의 거절30일 제한·자동 만료 재매칭과 ERD의 PENDING 이후 영구 재매칭 금지는 충돌한다. 정책 원문을 바꾸거나 영구 제한으로 임의 확정하지 않고 제품·BE 합의 항목으로 남긴다. 조기 퇴장·보관·외부 사본 삭제 범위 POL-09도 그대로 미합의다. 일반 종료 후30일 보관/삭제와 신고용90일 후속 설계를 혼동하지 않는다.

## 6 설명 생성·조회와 내부 계산

`POST .../{proposalId}/explanations`는202 ExplanationTask7필드(proposalId/kind/status/statusUrl/errorCode/retryable/pollAfterSeconds)를 반환한다. 별도 Job/jobId·공용 jobs 조회는 없다. `GET .../{proposalId}/explanation`은200 ExplanationStatus5필드(status/source/text/evidenceKeys/promptVersion)다. PENDING·FAILED면 source/text/promptVersion=null·evidenceKeys=[]이며 성공한 실제 근거 설명만 텍스트로 반환한다.

상태·결과는 matching.evaluation JSON에 연결한다. 내부 reasonKeys·원응답을 그대로 공개하지 않고 공개 승인된 표시용 evidenceKeys만 반환한다. 공개 매핑 미합의면 빈 배열이 가능하다. 실제 근거가 없으면 가짜 고정 문구를 만들지 않으며 같은 유효 근거의 검증된 RULE_FALLBACK만 사용한다. 설명 실패는 제안·기한·수락 상태를 바꾸지 않는다. [AI 설명 계약](ai-match-explanation.md)의 정보 공개·모델 전송 경계를 유지한다.

내부 `/internal/matching/evaluations`의 encounterId는 matching.id 숫자다. leftSnapshotId/rightSnapshotId/releaseId UUID는 legacy 입력 계약이며 ERD21에 대응 테이블이 없어 당시 입력 증거·서버 파일 매핑 합의 전 REVIEW_REQUIRED/구현 차단이다. 현재 설정으로 과거 입력을 추정하지 않는다. 판정 수식·모델 정책은 바꾸지 않는다.

내부 AI의 attemptId는 실행 상관 UUID, contextId는 설명 matching.id·채팅 AI chat.id다. Manifest11필드는 유지한다. 5분 기산점·최초시도 포함·최대횟수·영속 선점·실행/입력 세대·중복/늦은 결과는 합의가 필요하며 임의 컬럼을 추가하지 않는다.

## 7 현재 MVP 제외와 검증 경계

CM12(2026-10-26)에 따라 수락 취소는 현재 MVP 제외·후속이다. 활성 cancel 경로를 제공하지 않고 x-excluded-operations에 이력을 보존한다. response 불변·취소 저장 칸 부재를 숨기거나 미래 성공을 현재 성공으로 표시하지 않는다. 수락 이후 상대 응답·원래24시간 만료 대기, 차단·탈퇴, 방 생성 뒤 나가기는 유지한다.

CM09로 신고 전체도 후속이다. 기존 신고 자료가 있으면 별도 전환 검토 없이 삭제·기한 단축하지 않는다. 일반 방 이용·나가기·기록 정리는 [채팅](chat.md) 계약을 함께 검토한다.

문서 정적 검증은 [검증 기록](../../verification/owned-api-erd-20261028.md)을 따른다. 소유·기한·동시성·재전송·실제 기기 서명·서버·배포·외부 AI 실행 검증은 이번 문서 반영으로 완료되지 않는다.

## 이전 계약·구현 이력

아래는 작성 당시의 계약·로컬 구현 기록이다. 과거 테이블·컬럼·UUID·취소·재매칭·보관 규칙을 ERD21 현재 규범으로 적용하지 않는다.

### 현재 구현과의 차이

43번 로컬 구현은 제안 ACCEPTED까지이며 실제 채팅방이 없어 chatAvailable=false·chatRoomId=null을 반환한다. §8의 채팅 수명·안내·삭제 계약은 후속 구현 요구이며 실행 검증 결과가 아니다. MR16 형식의 sid/refresh_tokens 가족 세션을 검사한다. 실제 소셜 로그인·MR16 전체 병합, 37번 기기 증명 수신·배포는 검증 범위 밖이다.


## 12 변경 이력

| 버전 | 날짜 | 내용 |
| --- | --- | --- |
| v0.1.0-draft | 2026-10-20 | 최초 상태·기한·접수·공개 프로필·선택 계약. 34번 v0.1.9 사진 옵션 유지·최신 사진 삭제 표시 반영 |
| v0.1.1-draft | 2026-10-20 | 34번 v0.1.12·16번 기능 명세 반영. 최초 방 생성·중복 재시도·168시간 이용·종료 후 720시간 보관·권한 경계·49/50번 사용자별 안내 연결·검증 사례 추가. 미정 전송 형식과 43번 현재 구현을 구분 |
| v0.1.2-draft | 2026-10-20 | 서비스명 Tingle(팅글)을 제목·기본 정보에 반영. 요청·응답·기한 정책 변경 없음 |
| v0.1.3-draft | 2026-10-21 | 최신 통합 OpenAPI를 전송 계약 기준으로 명시. 자발적 퇴장 영구 제한을 미정으로 정정하고 오류 매핑·410 만료·imageUrl 공개 프로필·접수 errorCode 예시를 통일. 서버·앱 적용과 교차 검토는 후속 |

## 2026-10-22 기존 제안 권한 정정

회원 행 잠금 뒤 활성 계정·최초 가입 완료 기록·최신 서비스 필수 동의를 확인한다. 기기/현재 설문/설정/ACK는 신규 매칭 준비 조건으로 분리하며 기존 유효 제안의 조회·수락·거절을 막지 않는다. 최초 가입의 AI 동의는 필수지만 가입 후 철회는 제안·매칭을 막지 않는다. 소유권·기한·차단/계정 제한·기존 결정/멱등 검사는 유지한다. 채팅 권한과 위험 분석 전송 여부는 별도 검사다.

## 2026-10-22 제안 정책 정합화 작업본

- 담당: 김영빈(BE), 관련 S15P31E106-38·43, 영향 FE 순철·공개 프로필 가을. 기능 명세·정책 문서는 수정하지 않는다.
- 미응답자는 상대의 선행 수락/거절/취소에 관계없이 원래 기한 전 PENDING·기존 프로필/선택 경로를 유지한다. 상대 응답값/시각/사유를 추가하지 않는다. 후속 수락은 정상200 REJECTED 비성사이며 채팅을 생성하지 않는다.
- 내 거절/취소/비성사/만료는 목록 필터를 페이지 처리 전에 적용한다. 별도 종료 이력 목록을 만들지 않는다. 단건 조회는 이미 성공한 요청의 처리 복구용으로 남기며 공개 상태만 반환한다. 차단·비활성 상대는 목록 및 선택 경로에서 제외한다.
- profileVersion은 nickname·현재 한국 날짜 기준 age·gender·introduction·smokingStatus·drinkingFrequency·religionDisplay·imageUrl의 비교값이다. 내부 회원번호·생년월일·비공개 설문/설정은 포함하지 않는다. FE는 계산하지 않고 실제 표시한 응답의 값을 보낸다.
- 최초 수락은 회원→쌍→제안→상대 프로필 행 잠금 후 비교한다. 공개 프로필 쓰기와 직렬화하고 프로필 잠금 획득 뒤 기한도 다시 검사한다. 변경이면409 PROFILE_CHANGED 후 최신 프로필 GET과 명시적 재수락이 필요하며, 실패 시 결정/키는 저장하지 않는다. 기존 성공 수락의 응답 유실 재시도는 이 비교를 다시 요구하지 않는다.
- 취소와 상대 수락은 쌍 잠금으로 직렬화한다. 취소 선확정이면 후속 수락은 비성사, 상호 수락 선확정이면 취소409다. 실제 방 생성이 지연돼도 ACCEPTED를 취소로 되돌리지 않는다.
- 호환성: accept의 본문 필수가 추가되고 PublicProfile에 profileVersion이 추가되는 변경이다. FE는 GET 프로필 표시→비교값 보관→수락 본문 전달→409 재확인·취소 흐름 순으로 적용 준비가 필요하다. 미지원 앱과 동시 배포하면 기존 수락 요청은400이므로 적용 순서 협의 전 배포하지 않는다.
- 로컬 API·ERD·백엔드 변경이며 노션·ERDCloud 저장, 앱·공용 DB·배포 증거와 구분한다. 채팅 구현 및 매칭 계산은 이번 제안 검증 완료를 뜻하지 않는다.

## 2026-10-22 채팅 기본 구현 연결

상호 수락은 쌍/제안 잠금 트랜잭션 안에서 실제 방과 두 참여자·제안 연결을 원자 저장한다. 중복 수락은 같은 방과 최초 생성 시각을 재사용한다. 성공 응답은 방 UUID와 현재 chatAvailable을 반환하며, 현재 이용 권한이 없으면 방 생성/성사 자체는 유지하고 chatAvailable=false다. 성사 제안은 제안 목록에서 제외하고 활성 채팅 목록으로 관리한다. 기존 §8의 생성 후속 검토/준비 전 false 설명 중 이 연결 범위는 현재 [채팅 계약](chat.md)으로 대체한다. 알림/푸시·신고 보관/삭제·AI 종료 이벤트 후속은 별도다.
