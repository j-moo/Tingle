# 본인 푸시 단말 등록·교체·해제

## 2026-10-28 경로 이관 단위 · S15P31E106-176

현재 구현 경로는 `PUT /api/push-installations`·`DELETE /api/push-installations/{installationId}`다. 이전 `/api/v1` 경로는 별칭으로 제공하지 않는다. Bearer 인증·현재 로그인 family·가입 단계·설치 UUID·no-store 및 기존 등록/해제 동작을 유지한다. 설치 UUID와 family UUID는 숫자 ID로 바꾸지 않는다.

176·178 사용자 결정으로 `push_installation.binding_id`·`linked_at`을 ERD에 보존했다. bindingId 응답·안내/예약·발송 완료 식별은 같은 연결 세대를 사용한다. 두 값은 함께 저장/해제하며 같은 로그인 family의 토큰 교체에는 유지한다. 최초 연결·해제 후 재연결 때 새 연결 ID와 최초 시각을 확정하고 로그아웃 때 소유자·family와 함께 비운다. 과거 행의 누락 값을 현재 시각·새 UUID로 추정하지 않는다. 현재 JPA의 동명 필드를 재사용하며 중복 컬럼을 추가하지 않는다.

현재 JPA/native SQL은 `push_installation.id`·nullable `user_id`와 canonical `device_platform` enum을 사용한다. 설치 UUID와 기존 암호화 AAD는 유지한다. 같은 owner/family와 완전한 bindingId/linkedAt 쌍만 정상 토큰 교체 때 보존하며, 한쪽이 없는 기존 행은 인증된 재등록 때 두 값을 함께 새로 확정한다. 소유자가 비워진 행은 새 계정으로 등록할 수 있으나 다른 활성 owner의 행은 이전하지 않는다.

일반 로그아웃은 리프레시 증명으로 확인한 회원·family의 모든 설치에서 user_id/login_family_id/binding_id/linked_at을 같은 트랜잭션으로 비우고 해당 family를 폐기한다. 내부 ID·설치 UUID·플랫폼·암호문은 보존한다. 선택 입력 releaseInstallationId는 이전 클라이언트 호환용이며 다른 설치나 세션을 해제할 권한을 주지 않는다. 이미 비워진 연결·다른 회원이나 새 family로 재등록된 연결은 늦은 재시도가 비우지 않는다. 기존 탈취 감지의 회원 전체 세션 폐기 경로도 그 회원의 연결을 함께 비우며 기존 401/noRollbackFor 동작을 유지한다. 명시적 DELETE는 현재 본인 설치 행을 실제 삭제하는 별도 동작이다.

모든 알림 종류는 현재 owner·family·완전한 연결 쌍과 유효한 해당 세션을 요구한다. 로그아웃·폐기·만료·family 미상의 기존 행은 새 제안도 수신하지 않는다. 같은 family의 정상 토큰 재발급은 연결을 유지한다. 후보 해석은 현재 owner·UUID·bindingId·linkedAt·암호문 지문을 다시 확인하며 이전 후보의 무효 토큰 정리도 새 연결을 삭제하지 않는다. 도메인별 현재 수신 권한은 호출 워커가 계속 확인한다.

공유 DB에 SQL을 실행하지 않았고 실제 FCM 발송도 실행하지 않았다. FE 경로 전환은 서버와 함께 적용하며 저장/세션 이관 검증 전 전체 기능 완료로 표시하지 않는다. [176번 검증·남은 작업](../../verification/keyring-push-176.md).

## 이전 구현 계약 · 2026-10-22 (로그아웃·세션 수신 정책은 위 2026-10-28 계약으로 대체)

- 버전: 0.2.0-local, 2026-10-22. 담당 김영빈(BE), S15P31E106-82.
- 기계 판독 계약: [OpenAPI](openapi.json)의 `registerPush`, `removePush`, `PushToken`, `PushRegistration`.
- 영향: FE 설치 UUID·토큰 교체·해제 처리, 가을(BE)의 인증·계정 전환 연동. 로컬 구현 단계이며 팀 승인·병합·배포 또는 실제 푸시 발송 완료를 뜻하지 않는다.

## 등록과 토큰 교체

`PUT /api/push-installations`에 서비스 액세스 토큰을 보낸다. 기존 JWT 서명·만료·현재 세션·회원 상태 검사와 가입 단계 검사를 그대로 적용한다. UUID는 기기 소유 증명이나 회원 인증 자격이 아니다.

```json
{
  "installationId": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
  "platform": "ANDROID",
  "token": "synthetic-push-token"
}
```

- `installationId`: 앱 설치의 UUID. 내부 `push_installations.installation_id` 숫자와 다르며 `client_installation_id`에 대응한다.
- `platform`: `ANDROID` 또는 `IOS`. `token`은 공백만 있는 문자열을 허용하지 않는다. 정의되지 않은 요청 필드는 400이다.
- 현재 회원의 설치는 동일한 내부 행에서 플랫폼·암호문·시각을 갱신한다. 같은 요청 재전송도 행을 추가하지 않는다.
- 다른 회원 소유 UUID에는 404 `NOT_FOUND`를 반환하고 저장값을 보존한다. UUID를 제시하는 것만으로 계정 연결을 이전하지 않는다.
- 서로 다른 회원이 동시에 같은 UUID를 최초 등록해도 DB UNIQUE 제약과 소유권을 포함한 upsert로 한 회원만 소유한다.
- 200 응답은 `{"serverTime":"2026-10-22T01:00:00Z","bindingId":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"}` 형태다. 안내 연동에서 추가한 bindingId는 현재 로그인 연결의 불투명 UUID이며 인증 자격이 아니다. 같은 family의 토큰 교체에는 유지하고 새 로그인·삭제 후 재등록에는 새 값을 발급한다. 토큰·암호문·내부 ID는 반환하지 않으며 `Cache-Control: no-store`를 적용한다.

## 해제와 재등록

`DELETE /api/push-installations/{installationId}`도 인증 및 가입 단계 검사를 적용한다.

- 본인 소유의 현재 행만 실제 삭제하고 204(본문 없음)를 반환한다.
- 다른 회원 소유·미등록·이미 해제한 UUID는 같은 404 `NOT_FOUND` 응답이다. 반복 해제에서도 삭제된 행을 복원하지 않는다.
- 해제 후 등록은 새 행으로 처리한다. 같은 회원의 등록·해제는 회원 행 잠금 아래 직렬화한다.
- 키 누락으로 등록이 불가능해도 기존 본인 설치 해제는 가능하다.

## 저장과 설정

두 번째 단위는 기존 ERD의 6개 컬럼을 사용했다. 세 번째 단위는 설치별 로그인 상태를 구분하도록 nullable UUID `login_family_id` 하나를 추가한다. 값은 요청 필드가 아닌 검증된 JWT `sid`에서 얻는다. 기존 null 행을 로그인 중이라고 가정하지 않으며 새 제안 수신 연결만 허용한다. 클라이언트가 로그인 후 PUT으로 재등록하면 현재 세션을 연결한다. 안내 통합은 nullable binding_id(UUID)·linked_at(timestamptz)을 추가한다. 기존 login_family_id를 재사용하며 session_family_id라는 중복 컬럼을 만들지 않는다. 같은 family 재등록은 최초 linked_at을 유지한다. null인 기존 행의 연결 정보는 추정하지 않고 검증된 재등록 때 채운다. 별도 테이블·물리 FK는 추가하지 않는다.

`PUSH_TOKEN_ENCRYPTION_KEY`에 JWT와 별도로 생성한 32바이트 키를 Base64로 인코딩해 주입한다. 누락 시 등록은 503 `SERVICE_UNAVAILABLE`이며 평문 저장으로 대체하지 않는다. 값이 있으나 잘못된 형식·길이면 시작 단계에서 거부한다. 임의 기본 키나 JWT 키 재사용은 하지 않는다. 실제 키는 환경별로 관리하고 저장소에 기록하지 않는다.

등록은 AES-256-GCM 암호문만 저장하며 설치 UUID·회원 ID를 인증 문맥에 포함한다. 요청 DTO의 `toString()`도 토큰을 가린다. 키를 바꾸기만 하면 이전 암호문을 복호화할 수 없다. 실제 키 교체는 등록·발송을 멈춘 운영 절차에서 기존 키로 복원하고 새 키로 재암호화한 뒤 검증하는 작업이며, 이번에 실제 운영 키 교체를 실행한 것은 아니다. 키 미설정·복호화 오류는 전달 조회에서도 서비스 오류로 구분해 재시도/설정 복구로 연결하고, 평문이나 손상된 값을 토큰처럼 반환하지 않는다.

## 로그아웃과 계정 전환

일반 `POST /api/auth/logout`은 기존 `{refreshToken}` 요청과 204 응답을 유지한다. 연결은 남기고 기존 세션 family를 폐기한다. 다른 설치의 로그인은 이 설치의 로그인 상태를 대신하지 않는다.

계정 전환 시에는 같은 로그아웃 경로에 선택 필드를 보낸다.

```json
{
  "refreshToken": "synthetic-previous-account-refresh-proof",
  "releaseInstallationId": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
}
```

- 서버가 확인한 리프레시 증명의 회원·family와 설치 UUID 세 값이 현재 연결에 모두 일치할 때만 그 연결을 해제하고 같은 트랜잭션에서 세션을 폐기한다. 새 계정의 JWT나 UUID만으로 이전 연결을 해제하지 않는다.
- 증명이 없는/잘못된 요청은 401, 잘못된 UUID는 400이다. 기존 로그아웃과 같이 서버가 알고 있는 만료·이미 로그아웃한 증명의 재시도는 허용한다. 확인할 수 있는 과거 증명이 없다면 이전 계정 재인증 후 해제해야 한다.
- 이미 해제됐거나 다른 회원/세션으로 다시 연결된 설치는 건드리지 않고 204다. 지연 재시도가 새 소유자의 연결을 지우지 않는다. 204는 지정한 UUID의 임의 소유권 이전을 허용한다는 뜻이 아니다.
- FE 순서: 기존 증명을 폐기하기 전에 해제 요청 확인 → 새 계정 로그인 → 새 JWT로 PUT 등록. 해제 실패·새 등록 404를 연결 완료로 표시하지 않는다. 로그아웃 후 기존 증명을 이미 지웠다면 UUID만 보내는 우회 해제는 허용하지 않는다. 실제 FE 흐름·OS 보호 저장소·기기 검증은 별도다.
- JWT 검증과 저장 사이에 로그아웃이 들어와도 회원 행 잠금 안에서 세션을 다시 검사해 폐기된 세션의 등록·해제를 막는다.

## 내부 전달 adapter 조회 경계

`PushDeliveryLookup`은 신뢰된 내부 워커 전용이며 공개 HTTP endpoint가 아니다.

| 종류 | 현재 수신 조건 |
| --- | --- |
| `NEW_PROPOSAL` | 현재 ACTIVE 계정의 등록 연결. 로그아웃 후에도 유지 |
| `CHAT_MESSAGE`, `CHAT_ROOM_CREATED`, `CHAT_END_NOTICE` | ACTIVE 계정이며 해당 설치 family에 폐기·만료·소비되지 않은 리프레시 토큰이 존재 |

1. `candidates(userId, kind)`는 현재 수신 가능한 설치의 토큰 없는 참조를 반환한다. 참조에는 내부 행 ID·회원·설치 UUID·알림 종류·현재 암호문의 지문만 포함한다. 작업 payload에 원문이나 암호문을 넣지 않는다.
2. 발송 adapter는 실제 외부 호출 직전에 `resolve(candidate)`를 다시 호출한다. 현재 소유자·행 ID·암호문 지문·계정·설치 세션을 재확인한 뒤에만 복호화된 `PushDeliveryTarget`을 반환한다. 해제·계정 전환·재로그인 등록·토큰 교체 후 이전 참조는 거부한다.
3. 해석한 토큰은 저장·재사용하지 않는다. 실패 재시도도 후보를 다시 조회·해석한다. `toString()`은 토큰을 가리지만 `token()`은 외부 공급자 호출에 필요한 내부 비밀값이다.

리프레시 회전은 같은 family를 유지해 정상 수신에 영향이 없다. 이전에 로그인한 다른 설치의 상태를 보고 이 설치의 채팅 알림을 허용하지 않는다. 늦은 작업을 재로그인만으로 복원하는 방식도 사용하지 않는다.

자원별 현재 권한·방 종료·제안 만료·AI 동의·발송 중복·본문 제한은 호출 워커가 함께 검사한다. 특히 로그아웃 중 새 제안은 상대 사진·이름·채팅 원문이 없는 일반 알림만 허용한다. 조회와 외부 공급자 접수는 하나의 트랜잭션이 아니며, 이미 공급자가 접수한 푸시를 취소하거나 지연 도착을 막는 보장은 없다. 실제 전달 adapter·FE는 수신 당시 계정/자원 재확인을 적용해야 한다. 이번 단위는 실제 공급자 호출을 구현하거나 검증하지 않았다.

## 호환성과 후속 연동

- 기존 공개 경로·UUID·요청 필드를 유지하며 등록은200 PushRegistration(serverTime, bindingId), 해제는204 본문 없음이다. 이 단위의 404는 기존 OpenAPI 템플릿의 `RESOURCE_NOT_FOUND` 대신 서버의 일반 자원 오류 `NOT_FOUND`로 맞춘다. FE는 새 분기를 적용한 서버와 함께 반영한다.
- 가입 미완료 403 `ONBOARDING_REQUIRED`, 인증 오류 401 분기, 키 미설정 503을 기존 서버 형식으로 응답한다. 실제 발송·채널 선택·예약·ACK 정책은 구현하지 않는다.
- 적용 순서: FE·인증 담당 계약 검토 → 인프라 담당이 공유 DB nullable 컬럼 적용·키 관리 확인 → 승인 MR 반영 → 서버 키 설정·클라이언트 적용 → 실제 기기/공급자 검증. 공유 DB에 설계 SQL을 직접 실행하지 않는다. 기존 클라이언트는 일반 로그아웃을 유지하며, 설치 재등록·명시적 해제 연동은 FE 반영이 필요하다.
- 로그아웃 시 연결을 자동 삭제하지 않는다. 설치·세션을 확인한 선택적 해제만 계정 전환에 사용한다. 계정 전환을 이전 연결 해제와 새 인증 절차 없이 자동 이전하지 않는다.

정상/오류·재등록·소유권·동시 등록 테스트의 실행 결과는 [구현 기록](../../verification/push-installations-82.md)에 남긴다.

2026-10-22 후속 웹 대조: 요청받은 채팅·설치·로그아웃8개와 키링2개 API의 노션 현재 계약·저장 검증을 완료했다. 이 기록은 다른 API 전체나 서버/공용 DB/단말 검증으로 확대하지 않는다. [현재 통합 검증](../../verification/mr29-unified-integration-20261022.md)과 [10개 API 증거](../../verification/notion-api-ten-20261022.json)를 따른다.

## ERD 21개 테이블 대조 (2026-10-27, 가을)

2026-10-27 ERD 수정본(21개 테이블)의 `push_installation`은 `id`, `client_installation_id`(unique), `user_id`, `login_family_id`, `platform`, `token_ciphertext`, `updated_at` 7칸이다. 위 본문은 당시 구현 기록이다. 경로의 `/v1`은 뺐다.

- `installationId`는 앱이 만드는 `client_installation_id`(UUID)라 그대로다.
- 본문 "저장과 설정"의 `binding_id`·`linked_at` 칸이 ERD에 없다. 등록 응답의 `bindingId`를 무엇으로 만들지, 지연 알림 차단을 `login_family_id`만으로 할지 합의가 필요하다. 지금 코드는 두 칸을 쓰고 있어 ERD를 적용하면 코드와 맞지 않는다.
- 로그아웃은 `refresh_token`의 family를 폐기하고, 선택 해제(`releaseInstallationId`) 규칙은 그대로다. 탈퇴하면 그 회원의 설치 연결을 해제한다.
