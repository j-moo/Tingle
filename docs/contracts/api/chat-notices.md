# 첫 입장·종료 안내와 설치별 알림

2026-10-28 / 승인된 22표 보존 설계. [채팅](chat.md)·[OpenAPI](openapi.json)·[ERD](../../database/erd.md)를 따른다. 현재 코드·DB 검증은 [175 기록](../../verification/erd22-storage-175.md)에 구분한다.

## 현재 22개 테이블 기준

| API | 식별자·역할 | 영속 상태 |
| --- | --- | --- |
| POST /api/chat-rooms/{chatRoomId}/entries | 방은 양수 int64. 본인 최초 입장 안내 확보 | chat_participant.notice_state의 최초 entry 키·안내 UUID |
| GET /api/chat-rooms/{chatRoomId}/notices | 본인 미확인 입장·종료 안내 | 같은 참여자 상태의 예약·미확인·최초 ACK·복구 증거 |
| PUT /api/chat-rooms/{chatRoomId}/notices/{noticeId}/ack | noticeId UUID·본인 실제 표시 확인 | 해당 업무의 ack 키·최초 ACK 시각. 다른 방/사용자 안내는404 |

일반 안내는 참여자의 명시적인 notice_state에 저장한다. FIRST_ENTRY와 종료 안내별 UUID·businessKey·payload·상태·예약시각·attempt/lease·최초 ACK·설치 연결별 결과를 보존한다. background_jobs 표나 메모리 큐를 추가하지 않고, chat_safety_notice 또는 무관 AI 상태로 대신하지 않는다. 안내 UUID 중복·요청 키 충돌, 늦은 worker 완료는 실제 현재 소유·선점 세대와 비교한다. 기존 자료의 증거가 부족하면 추정 이관하지 않는다.

안내 상태와 read_state는 같은 참여자 행에서 갱신되므로 잠금 후 현재 자료를 다시 읽어 다른 상태를 덮어쓰지 않는다. 미확인 첫 안내는 메시지 조회/전송/읽음을 제한하며 최초 ACK 뒤 현재 권한을 다시 계산한다. 이른 종료 안내 ACK는409, 종료 후 ACK가 방 권한을 되살리지 않는 계약을 유지한다.

방 기한은 최초 생성·상호 수락 시각의 원래168시간이며 늦은 방 복구/worker/재시도로 연장하지 않는다. 신고 retain_until이 일반 삭제 예약보다 우선한다. 외부 공급자 접수·단말 수신·화면 표시 ACK는 서로 다른 증거이며 합성 회귀로 실기기 성공을 주장하지 않는다.

설치는 push_installation의 현재 user/family/설치UUID·binding_id·linked_at으로 재검사한다. 사용자 결정으로 보존한 연결 세대·최초 시각을 사용하며 정상 토큰 교체와 해제 후 재연결을 구분한다. 설치/session UUID는 숫자 방·메시지 ID와 별개다. 첫 안내 ACK 후 재입장은 빈 notices일 수 있으며 최초 미확인 예시와 구분한다.

## 2026-10-22 구현 이력 — 구스키마 기록

아래는 당시 구현·통합 근거를 보존한 기록이다. background_jobs·public_id·binding_id·linked_at 및 과거 UUID 방 식별자를 최신 21개 테이블에 존재하는 칸으로 인용하지 않는다. 현재 저장 구조 이관·팀 승인·서버/공용 DB/배포 완료를 뜻하지 않는다.
v0.1.0-review / 2026-10-22. 담당 김영빈(BE), 연동 황순철(FE)·조가을(인증). 관련 S15P31E106-49·50 및 API/ERD 계약. 기존 기능 명세와 매칭 정책을 기준으로 구현하며 원문을 수정하지 않는다. 필드/예시는 [OpenAPI](openapi.json), 채팅 기본 권한은 [chat.md](chat.md), 저장은 [ERD](../../database/erd.md)와 schema.sql을 따른다.

## 입장과 표시 확인

| API | 동작 |
| --- | --- |
| POST /chat-rooms/{chatRoomId}/entries | Idempotency-Key UUID 필수, 본문 없음. 본인 FIRST_ENTRY 안내와 방 메타데이터 반환 |
| GET /chat-rooms/{chatRoomId}/notices | 현재 필요한 본인 미확인 안내 목록 |
| PUT /chat-rooms/{chatRoomId}/notices/{noticeId}/ack | Idempotency-Key UUID 필수, 본문 없음. 실제 표시/확인한 안내의 최초 ACK 기록 |

위 채팅 안내 경로는 `/api` 기준이며 Bearer 인증과 현재 방 권한을 검사한다. 아래 설치 등록·해제의 기존 `/api/v1` 경로는 이번 변경 범위 밖이다. 같은 방이어도 참여자별로 안내 ID와 ACK를 분리한다. FIRST_ENTRY는 최초 실제 입장 때 한 번 생성하고 미확인 재입장은 동일 UUID를 반환한다. ACK 후에는 다시 표시하지 않는다. 최초 ACK 시각은 중복/동시 요청으로 바꾸지 않는다.

최초 입장 안내를 확인하기 전에는 메시지 조회·전송·읽음 ACK를403 CHAT_NOTICE_REQUIRED로 막고 ChatRoom.canRead/canSend=false를 반환한다. 방 입장 자체와 안내 조회는 가능하며 확인 후 메시지 권한을 다시 계산한다. 다른 사용자/방 안내는404, 이른 END_NOTICE ACK는409 NOTICE_STATE_CONFLICT다. 최초 entry/ack 요청 키는 각각 업무에 귀속하며 다른 업무 재사용은409 IDEMPOTENCY_CONFLICT다. 같은 업무의 새로운 재시도 키는 결과만 재사용하며 최초 키를 덮어쓰지 않는다.

7일 이용·실제 종료 시각을 한국 시각으로 안내한다. 종료1시간 이내 첫 입장은 FIRST_ENTRY와 ONE_HOUR_BEFORE_END 두 안내를 별도로 제공하고 각각 ACK한다. 이 분리는 기능 정책이 허용한 화면/채팅 계약의 구현 선택이며 FE 적용 검토가 필요하다. 문구는 메시지 리소스로 관리하고 Accept-Language 지원 문구가 없으면 한국어를 쓴다. 종료 안내는 지연 시 실제 남은 분을 표시하며 고정된 “1시간 뒤”로 안내하지 않는다.

만료/조기 권한 종료 뒤 새 입장/안내 조회는410 CHAT_CLOSED다. 이미 받은 본인 안내의 ACK만 종료 뒤에도 허용하며 내용·방·기한을 복구하지 않는다. 단말 간 정확히 한 번 표시를 보장하지 않으므로 FE도 noticeId로 중복 표시를 억제한다. 표시 확인과 메시지 읽음은 서로 다른 상태다.

## 영속 예약과 알림

최초 방 생성 트랜잭션 안에서 두 참여자의 END_NOTICE를 각각 expiresAt-1시간에 예약한다. 별도 안내 테이블 없이 background_jobs의 FIRST_ENTRY/END_NOTICE와 고유 business_key `chat-notice:{internalRoomId}:{userId}:{type}`를 사용한다. public_id는 외부 noticeId이며 내부 bigint를 노출하지 않는다. payload에는 방/회원 식별자·최초 entry/ack 키만 저장하고 메시지·토큰 원문을 넣지 않는다.

예약 run_at은 재시도 가능 시각이며, API scheduledAt은 원래 expiresAt-1시간을 유지한다. 상태 PENDING/RUNNING/SUCCEEDED/FAILED/CANCELLED는 작업 처리 상태다. SUCCEEDED만 보고 단말 수신/표시 성공으로 판단하지 않는다. result_json의 설치 bindingId별 ACCEPTED는 FCM의 접수 응답일 뿐이다. REJECTED·INVALID_TOKEN도 별도 기록하고 ACK는 자동으로 채우지 않는다.

작업기는 짧은 DB 트랜잭션에서 SKIP LOCKED로 작업을 가져와30초 lease를 기록하고, 실제 공급자 호출은 트랜잭션 밖에서 수행한다. 만료 lease는 재확보하되 시도 번호가 달라진 이전 작업자가 새 상태를 덮어쓰지 못하게 한다. 일시 실패는30초부터 지수 지연해 재시도하며 공급자 실패5회면 FAILED로 남긴다. 설치별 이미 완료한 결과는 재전송하지 않는다. 발송 직전에 현재 방·ACK·설치 연결·해당 로그인 family의 유효성·기한을 다시 검사한다. 종료/차단/철회/탈퇴·조기 종료·이미 표시 확인한 대상은 발송하지 않는다.

외부 접수 직후 작업자가 중단되면 재전달 가능성이 있으므로 논리 noticeId와 FCM collapse ID, FE 중복 처리가 함께 필요하다. provider 접수·단말 수신·사용자 표시/확인을 하나의 성공으로 합치지 않는다. 실패나 알림 누락으로 방 기한을 늘리지 않으며 앱의 안내 조회로 기한 내 현재 미확인 안내를 복구한다.

## 설치 등록·로그아웃·계정 전환

| API | 요청/응답 |
| --- | --- |
| PUT /push-installations | `{installationId:UUID,platform:ANDROID 또는 IOS,token:최대4096자}` →200 `{serverTime,bindingId}` |
| DELETE /push-installations/{installationId} | 현재 계정 소유 연결만204, 다른 계정/없는 설치는404 |

등록은 현재 JWT의 userId와 sid(refresh family)에 연결하며 최신 설치 모듈의 login_family_id에 저장한다. 설치 UUID나 bindingId만으로 인증하지 않는다. 토큰 갱신은 현재 계정/family의 bindingId·linkedAt을 유지하고 같은 계정의 새 로그인은 새 연결 버전을 만든다. 다른 계정 소유 UUID 등록은404 NOT_FOUND이며 기존 값을 보존한다. 계정 전환은 이전 계정의 인증으로 연결 해제 성공 후 새 계정 JWT로 등록하거나, 기존 refresh 증명과 선택 releaseInstallationId로 기존 연결을 해제한다. 새 계정 JWT와 UUID만으로 이전 연결을 덮어쓰지 않는다. 등록/해제는 회원 행 잠금 안에서 현재 ACTIVE·소비/폐기/만료되지 않은 family를 다시 검사한다. 토큰은 별도32바이트 키의 AES-256-GCM 암호문만 저장하고 설치 UUID·회원 ID를 인증 문맥(AAD)으로 묶는다. 키 누락은503 SERVICE_UNAVAILABLE이며 잘못된 키 설정은 시작 단계에서 거부한다. 평문 대체 저장은 하지 않는다.

로그아웃은 설치 수신 주소를 삭제하지 않지만 해당 family가 폐기되면 채팅 종료 알림 대상에서 제외한다. 다른 기기의 살아 있는 세션으로 이 설치를 로그인 상태라고 간주하지 않는다. 이 처리는 새 제안용 연결을 지우지 않으며 제안 알림 구현은 이번 범위 밖이다. 원래 안내 시각 뒤 새 로그인/등록한 설치에는 과거 종료 알림을 일괄 재발송하지 않는다. 앱은 현재 미확인 안내를 조회한다.

FCM에는 noticeId·chatRoomId·installationId·bindingId·type·expiresAt의 데이터만 전달한다. 상대 정보/메시지와 고정 종료 예고문을 넣지 않는다. Android TTL과 APNs 만료 시각은 방 종료까지로 제한하고 같은 noticeId를 collapse ID로 사용한다. 앱은 현재 로컬 설치/계정의 bindingId가 다르거나 로그아웃/만료 상태인 지연 데이터를 버리고, 표시 전에 인증된 API로 현재 안내와 권한을 확인해야 한다. 전송 뒤 계정 전환이나 로그아웃은 서버의 이미 발송한 패킷 회수로 해결할 수 없으므로 이 FE 검증이 필수다.

현재 어댑터는 데이터 메시지이며 OS가 자동 표시하는 알림 UI가 아니다. 앱 종료·백그라운드·절전·iOS 데이터 메시지 처리 조건 및 실제 알림 표시/권한은 FE와 실기기에서 검증해야 한다. 서버 접수 검증만으로 앱 종료 상태의 사용자 수신을 완료 처리하지 않는다.

## 실행 설정과 검증 경계

- `PUSH_ENABLED=false`가 기본이다. 이때 공급자 호출 및 예약 작업의 소모를 하지 않으며 첫 입장/앱 내 안내 API는 독립적으로 동작한다.
- 실제 FCM 실행에는 FIREBASE_PROJECT_ID, 런타임 ADC 자격, PUSH_TOKEN_ENCRYPTION_KEY(Base64 인코딩32바이트)가 필요하다. 자격 파일과 실제 값은 저장소에 넣지 않는다. 앱 설치 등록에도 토큰 암호화 키가 필요하다.
- 작업기는 기본15초 주기로 최대20개씩 처리한다. 테스트는 자동 작업기를 끄고 고정 시계/가짜 전송기로 시각·실패·경합을 검증한다. 실제 전송은 별도 설정/기기 검증 대상이다.
- Firebase Admin SDK9.11.0을 추가했다. 직접 OAuth/FCM 인증·전송을 재구현하지 않고 공식 ADC/FCM 클라이언트를 사용하기 위한 의존성이다. [공식 릴리스](https://firebase.google.com/support/releases), [메시지 수명/만료](https://firebase.google.com/docs/cloud-messaging/customize-messages/setting-message-lifespan)를 기준으로 확인했다.
- 노션·ERDCloud 웹 저장·공용 DB 적용·실제 공급자/기기·배포·CI 검증은 이번 로컬 검사와 구분한다. 신고 보관/삭제와 철회 이벤트의 종료 저장·AI 작업 중단, 일반 채팅 메시지/방 생성 푸시는 이번 종료 안내 범위 밖이다.

## 최신 설치 모듈 통합

develop fbe0f35의 설치·AAD 암호화·선택적 로그아웃 해제 계약을 사용한다. 발송기는 PushDeliveryLookup.candidates/resolve로 토큰 없는 후보를 얻고 발송 직전에 현재 소유권·암호문 지문·세션을 재확인한다. 후보/작업 payload에는 토큰 원문·암호문을 넣지 않는다. 교체 전 토큰의 늦은 INVALID_TOKEN 응답은 현재 토큰을 삭제하거나 해당 연결을 전달 완료로 표시하지 않고 새 후보로 재시도한다.

공개 설치 응답에는 안내 연동용 bindingId가 추가된다. 기존 serverTime은 유지한다. 구형 설치 행의 nullable binding_id·linked_at을 기존 updated_at이나 현재 세션으로 추정하지 않는다. 인증된 재등록 때만 생성하며 그전에는 종료 안내 대상에서 제외한다. FE는 등록 응답의 bindingId를 보관해 지연 데이터의 연결을 비교해야 한다.
