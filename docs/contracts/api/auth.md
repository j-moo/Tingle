# 인증 API 명세

| 항목 | 내용 |
| --- | --- |
| 버전 | v0.1.2 |
| 변경일 | 2026-10-22 |
| 담당자 | 가을 (BE) |
| 관련 Story | S15P31E106-35 [BE] 소셜 로그인 인증 흐름과 API 명세 작성 |
| 연관 Story | S15P31E106-34 [DOC] 가입 항목과 매칭 정책 v1 작성 (`docs/contracts/matching/profile-policy-v1.md`. 가입 입력 항목이 바뀌면 이 명세도 함께 수정) |
| 영향 파트 | FE, IOT(키링 등록 단계) |

소셜 로그인, 가입 단계, 약관 조회·동의, 프로필 저장, 닉네임 중복 확인, 토큰 재발급의 앱–서버 계약이다. 키링 등록·성향 설문·희망 조건 API는 각 기능 명세에서 정하고, 이 명세는 그 단계의 **순서와 접근 규칙**만 정한다.

## 목차

- [공통 규칙](#공통-규칙)
- [인증 흐름](#인증-흐름)
- [가입 단계](#가입-단계)
- [소셜 로그인](#소셜-로그인)
- [가입 단계 조회](#가입-단계-조회)
- [약관 조회](#약관-조회)
- [약관 동의](#약관-동의)
- [프로필 저장](#프로필-저장)
- [프로필 등록 완료](#프로필-등록-완료)
- [프로필 조회](#프로필-조회)
- [닉네임 중복 확인](#닉네임-중복-확인)
- [토큰 재발급](#토큰-재발급)
- [로그아웃](#로그아웃)
- [입력값 규칙](#입력값-규칙)
- [변경 이력](#변경-이력)

## 공통 규칙

### 인증 헤더

인증이 필요한 API는 서비스 액세스 토큰을 헤더에 넣는다.

```
Authorization: Bearer {accessToken}
```

### 응답 형식

- 성공 응답은 본문을 감싸지 않고 그대로 보낸다. 생성·저장도 `200 OK`다.
- 시각은 ISO-8601 UTC 문자열이다(`2026-10-20T01:30:00Z`). 날짜만 있는 값은 `YYYY-MM-DD`다.
- 필수(O) 필드는 값이 없어도 빼지 않고 `null`로 보낸다(예: 사진이 없으면 `"imageUrl": null`). 필수가 아닌(X) 필드만 값이 없을 때 빠진다.
- 요청 본문에 정의되지 않은 필드가 있으면 무시하지 않고 `400 INVALID_REQUEST`다.

### 오류 응답 형식

모든 오류는 같은 형식으로 응답한다. 앱은 `code`로 분기하고, `message`는 화면에 그대로 보여 줘도 되는 문장이다.
`message`는 요청의 `Accept-Language` 언어로 오고, 그 언어 문구가 없으면 기본 언어(한국어)로 온다(기능 명세 §1.1 I18N01·07). 앱은 `message` 문장을 비교해 동작을 나누지 않는다.

```json
{
  "code": "NICKNAME_DUPLICATED",
  "message": "이미 사용 중인 닉네임입니다."
}
```

### 모든 인증 API에 공통인 오류

| 상태 | code | 상황 | 앱 처리 |
| --- | --- | --- | --- |
| 401 | AUTH_REQUIRED | 액세스 토큰 없음 | 로그인 화면 (토큰을 갖고 있다면 앱 버그) |
| 401 | ACCESS_TOKEN_EXPIRED | 액세스 토큰 만료 | 토큰 재발급을 **한 번** 시도하고 원래 요청을 재시도. 재발급도 실패하면 로그인 화면. 동시에 만료된 요청들은 재발급 한 번을 함께 기다린다 |
| 401 | ACCESS_TOKEN_INVALID | 위조·손상된 토큰 | 재발급하지 않고 로그인 화면 |
| 401 | SESSION_REVOKED | 로그아웃이나 탈취 감지로 끝난 세션의 토큰 | 재발급하지 않고 로그인 화면 |
| 403 | ONBOARDING_REQUIRED | 아직 도달하지 않은 가입 단계의 API 호출 ([가입 단계](#가입-단계)의 접근 규칙) | [가입 단계 조회](#가입-단계-조회)로 현재 단계를 받아 그 화면으로 이동 |

### 토큰

| 토큰 | 형식 | 유효 기간 |
| --- | --- | --- |
| 액세스 토큰 | JWT. `sub`는 서비스 회원번호(숫자 문자열), `sid`는 로그인 세션 ID(UUID 문자열) | 30분 |
| 리프레시 토큰 | 서버가 발급하는 임의 문자열 | 14일 |

유효 기간은 서버 설정값이며, 바뀌면 이 명세의 버전을 올린다.

**로그인 세션:** 소셜 로그인 한 번(기기 하나)마다 세션이 하나 생긴다. 재발급해도 세션은 그대로다. 서버는 요청마다 토큰의 서명·만료와 함께 **세션이 끝나지 않았는지** 확인한다. 세션은 [로그아웃](#로그아웃)하거나, 리프레시 토큰 탈취가 감지되면(그 회원의 모든 세션) 끝난다.

## 인증 흐름

```
[앱] 구글·카카오·네이버 SDK로 로그인
  │  POST /api/v1/auth/{provider}  { token }
  ▼
[서버] 소셜 토큰 검증 → 소셜 고유 ID로 회원 조회 → 없으면 새 회원 생성 → 서비스 토큰 발급
  │  { accessToken, refreshToken, tokenType, accessTokenExpiresAt, refreshTokenExpiresAt, onboardingStatus }
  ▼
[앱] onboardingStatus 에 맞는 화면으로 이동 (가입 단계 표 참고)
```

- 서버가 소셜에서 받는 정보는 **소셜 고유 ID와 이메일**이다. 이름·사진은 받지 않는다.
  - 이메일은 사용자가 소셜 동의 화면에서 제공에 동의한 경우에만 받는다. 동의하지 않아도 로그인은 된다.
  - 구글은 `email_verified`가 true일 때, 카카오는 유효하고 인증된 이메일일 때만 저장한다. 네이버는 프로필 이메일을 저장한다.
  - **개인정보 수집·이용(`PRIVACY`)에 동의한 회원만** 저장한다. 동의 전 첫 로그인에서는 저장하지 않고, 동의 뒤 로그인부터 저장한다.
  - 저장한 뒤에는 로그인할 때마다 최신 값으로 바꾸고, 제공하지 않으면 지운다.
  - 카카오는 이메일 항목만 요청한다. 네이버는 요청으로 항목을 고를 수 없으므로 개발자센터의 제공 정보를 고유 ID와 이메일만 켠다.
  - 이메일은 연락용이다. 회원 식별에 쓰지 않고(다른 소셜 계정이 같은 이메일을 가질 수 있다) 이 명세의 어떤 응답에도 넣지 않는다.
  - 앱은 소셜 로그인을 요청할 때 이메일 권한을 함께 요청한다(구글 `email` scope, 카카오 `account_email`, 네이버 이메일 제공 항목).
- 회원은 `(소셜 종류, 소셜 고유 ID)`로 식별한다. 같은 사람이 다른 소셜로 로그인하면 **별개 계정**이다. 계정 연동은 MVP 범위가 아니다.
- 서비스 안에서는 소셜 ID가 아니라 **서비스 회원번호**로 사람을 가리킨다. 다른 사용자의 회원번호는 응답에 넣지 않는다.

## 가입 단계

> 단계 이름과 접근 규칙은 34번 정책 MR(!6) 검토로 FE·IOT와 맞추는 중인 **초안**이다.

소셜 로그인 뒤 아래 순서로 진행한다. 서버는 회원의 현재 단계를 계산해 `onboardingStatus`로 알려 준다.

| 순서 | onboardingStatus | 뜻 | 앱 화면 | 이 단계를 마치는 API |
| --- | --- | --- | --- | --- |
| 1 | `AGREEMENT_REQUIRED` | 필수 약관에 동의하지 않음 | 약관 동의 | [약관 동의](#약관-동의) |
| 2 | `KEYRING_REQUIRED` | 등록한 키링이 없음 | 키링 등록·구매 안내 | 키링 등록 (키링 명세) |
| 3 | `PROFILE_REQUIRED` | 필수 프로필 항목이 비어 있음 | 프로필 입력 | [프로필 저장](#프로필-저장) |
| 4 | `SURVEY_REQUIRED` | 성향 설문을 마치지 않았거나 계산할 수 있는 결과가 없음 | 성향 설문 | 설문 제출 (설문 명세) |
| 5 | `PREFERENCES_REQUIRED` | 희망 성별·나이를 정하지 않음 | 희망 조건 | 희망 조건 저장 (희망 조건 명세) |
| 6 | `SYNC_PENDING` | 입력은 끝났지만 키링에 아직 반영되지 않음 | 키링 동기화 안내 | 키링 동기화 완료 보고 (키링 명세) |
| 7 | `READY` | 모든 단계 완료 | 메인 | - |

**접근 규칙 (서버가 확인)**

- 각 단계에서는 **그 단계와 이전 단계의 API**만 호출할 수 있다. 예: `KEYRING_REQUIRED`에서는 약관·키링 API는 되고, 프로필 저장은 `403 ONBOARDING_REQUIRED`다.
- 각 API의 "`X` 단계부터"는 `X`와 그 뒤의 모든 단계(`READY` 포함)에서 호출할 수 있다는 뜻이다. 가입을 마친 뒤에도 프로필 조회·닉네임 확인 등은 그대로 쓴다.
- 단계와 상관없이 항상 호출할 수 있는 API: 소셜 로그인, 토큰 재발급, 로그아웃, 약관 조회, [가입 단계 조회](#가입-단계-조회).
- 서버는 미구현 단계를 통과시키지 않는다. 각 단계는 실제 데이터(키링 연결, 설문 분석 성공, 희망 조건, 키링 반영 확인)로 판단하며, `READY`는 매칭 제안과 같은 기준이다. 그래서 키링·설문·희망 조건 기능이 생기기 전에는 누구도 `READY`가 되지 않는다.
- **임시 예외:** 키링 등록 API가 생기기 전까지 [프로필 저장](#프로필-저장)·[프로필 등록 완료](#프로필-등록-완료)·[프로필 조회](#프로필-조회)·[닉네임 중복 확인](#닉네임-중복-확인)은 `KEYRING_REQUIRED` 단계부터 허용한다. 키링 API가 생기면 `PROFILE_REQUIRED` 단계부터로 되돌린다.
- **가입을 한 번 마친 회원(`signupCompleted: true`)** 은 마지막 키링 초기화·재검사 등으로 단계가 다시 앞으로 가도 기존 기능(홈·기존 제안·채팅·탈퇴 등)을 계속 쓴다. 새 매칭만 `matchingReady: false`로 보류된다(기능 명세 AUTH-02). AI 동의 철회는 기존 회원의 제안·매칭 권한을 막지 않는다. 그 외 바뀐 필수 약관 동의(`AGREEMENT_REQUIRED`)는 먼저 받는다. 앱은 이 회원을 가입 화면으로 보내지 않고 홈에서 키링 등록·동기화 안내를 보여 준다.
- 최초 가입 미완료 회원은 해당 단계 이후 API에 `403 ONBOARDING_REQUIRED`다. 가입 완료 회원의 기존 기능과 AI 철회 예외는 위 규칙을 따른다. `SYNC_PENDING`에서 허용할 서비스 기능은 키링 명세에서 정한다.

**앱 처리**

- 로그인·재발급 응답, 각 단계 API 응답의 `onboardingStatus`를 보고 다음 화면으로 간다. 앱을 다시 열었을 때는 [가입 단계 조회](#가입-단계-조회)를 호출한다.
- **앱은 모르는 값이 오면** 앱 업데이트 안내 등 안전한 화면으로 보낸다. 단계는 추가될 수 있다.
- 단계가 바뀌어도 토큰을 다시 받지 않는다. 단계는 토큰이 아니라 서버에 저장되므로 기존 토큰을 그대로 쓴다.
- 이미 끝난 단계로 되돌아가지는 않는다. 다만 약관 버전이 바뀌어 다시 동의가 필요해지는 경우 등 되돌아가는 조건은 해당 기능에서 정한다.

## 소셜 로그인

```
POST /api/v1/auth/{provider}
```

인증: 불필요

### 요청

**Path 변수**

| 이름 | 자료형 | 필수 | 설명 |
| --- | --- | --- | --- |
| provider | String | O | `google`, `kakao`, `naver` 중 하나. 소문자 |

**Body**

| 필드 | 자료형 | 필수 | 설명 |
| --- | --- | --- | --- |
| token | String | O | 구글은 **ID 토큰**, 카카오·네이버는 **액세스 토큰** |

```json
{
  "token": "eyJhbGciOiJSUzI1NiIs..."
}
```

### 응답

**200 OK**

| 필드 | 자료형 | 필수 | 설명 |
| --- | --- | --- | --- |
| accessToken | String | O | 서비스 액세스 토큰 |
| refreshToken | String | O | 서비스 리프레시 토큰 |
| tokenType | String | O | 항상 `Bearer` |
| accessTokenExpiresAt | String | O | 액세스 토큰 만료 시각 (ISO-8601, UTC) |
| refreshTokenExpiresAt | String | O | 리프레시 토큰 만료 시각 (ISO-8601, UTC) |
| onboardingStatus | String | O | 현재 [가입 단계](#가입-단계) |

```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
  "refreshToken": "c2b7e1f0-...",
  "tokenType": "Bearer",
  "accessTokenExpiresAt": "2026-10-20T02:00:00Z",
  "refreshTokenExpiresAt": "2026-11-03T01:30:00Z",
  "onboardingStatus": "AGREEMENT_REQUIRED"
}
```

### 오류

| 상태 | code | 상황 |
| --- | --- | --- |
| 400 | INVALID_PROVIDER | provider가 `google`, `kakao`, `naver`가 아님 |
| 400 | INVALID_REQUEST | token 누락 |
| 401 | INVALID_SOCIAL_TOKEN | 만료·위조된 토큰, 우리 앱에서 발급되지 않은 토큰 |
| 502 | SOCIAL_PROVIDER_ERROR | 소셜 서버 오류나 응답 없음. 잠시 후 재시도 |

### 주의사항

- 처음 로그인한 소셜 계정은 `AGREEMENT_REQUIRED`부터 시작한다. 가입 도중 앱을 끄고 다시 로그인하면 마치지 않은 단계가 온다.
- 만료 시각은 미리 재발급할 시점을 정하는 데만 쓴다. 기기 시계가 틀릴 수 있으므로 만료 판단은 서버의 `401 ACCESS_TOKEN_EXPIRED`를 기준으로 한다.
- **구글은 ID 토큰을 보낸다.** 서버는 토큰의 `aud`가 서버용 웹 클라이언트 ID인지 확인하므로, 앱의 구글 로그인 설정에서 서버용 웹 클라이언트 ID를 지정해야 한다. 실제 값은 서버·앱 모두 환경변수로 주입하며 저장소에 올리지 않는다. 변수 이름은 `.env.example`을 따른다.
- 서버의 소셜별 검증 방식
  - 구글: 구글 공개키로 ID 토큰 서명을 검증하고 발급자·대상·만료를 확인한다.
  - 카카오: 토큰 정보 조회 API로 **토큰을 발급한 앱이 우리 앱인지** 확인한 뒤 회원번호를 얻는다.
  - 네이버: 회원 프로필 조회 API로 고유 ID를 얻는다.

## 가입 단계 조회

```
GET /api/v1/users/me/onboarding
```

인증: 필요 (모든 단계에서 호출 가능)

앱을 다시 열었을 때나 `403 ONBOARDING_REQUIRED`를 받았을 때 현재 단계를 확인한다.

### 응답

**200 OK**

| 필드 | 자료형 | 필수 | 설명 |
| --- | --- | --- | --- |
| nextStep | String | O | 다음에 마칠 [가입 단계](#가입-단계). 로그인 응답의 `onboardingStatus`와 같은 값 |
| signupCompleted | Boolean | O | 한 번이라도 모든 가입 단계를 마친 적이 있는지 |
| onboardingCompleted | Boolean | O | 지금 모든 단계를 마친 상태(`READY`)인지. 약관 버전이 오르면 다시 `false`가 될 수 있다 |
| matchingReady | Boolean | O | 새 매칭을 시작할 수 있는지. 키링 반영 기능 전까지는 `onboardingCompleted`와 같다 |
| surveySessionId | String \| null | O | 진행 중인 설문 회차 ID (UUID). 없으면 `null` |
| answeredCount | Number | O | 응답을 저장한 문항 수 |
| questionCount | Number | O | 설문 전체 문항 수 |
| nextQuestionId | String \| null | O | 다음 미응답 문항 ID. 없으면 `null` |
| requiredSettingsVersion | Number | O | 서버가 요구하는 최신 설정 변경 번호 |
| acknowledgedSettingsVersion | Number \| null | O | 기기에 반영됐다고 확인한 설정 변경 번호. 없으면 `null` |
| serverTime | String | O | 서버의 현재 시각 (ISO-8601, UTC). 기기 시계 보정용 |

```json
{
  "nextStep": "PROFILE_REQUIRED",
  "signupCompleted": false,
  "onboardingCompleted": false,
  "matchingReady": false,
  "surveySessionId": null,
  "answeredCount": 0,
  "questionCount": 0,
  "nextQuestionId": null,
  "requiredSettingsVersion": 0,
  "acknowledgedSettingsVersion": null,
  "serverTime": "2026-10-21T03:00:00Z"
}
```

- 설문 진행도(`surveySessionId`~`nextQuestionId`)와 설정 반영 번호는 설문·키링 기능 전까지 **임시값**(`null`, `0`)이다. 키는 항상 있다. `questionCount`는 설문 문항이 정해지면 실제 문항 수가 된다.
- 처음 로그인했을 때는 이 API를 따로 부를 필요가 없다. 로그인 응답의 `onboardingStatus`가 서버가 그 순간 DB를 확인한 같은 결과다.

### 오류

| 상태 | code | 상황 |
| --- | --- | --- |
| 401 | AUTH_REQUIRED 등 | [공통 오류](#모든-인증-api에-공통인-오류) 참고 |

## 약관 조회

```
GET /api/v1/agreements
```

인증: 불필요

약관 동의 화면에 표시할 동의 항목 목록을 받는다. 앱은 이 목록을 **그대로 화면에 그리고**, 사용자의 선택을 [약관 동의](#약관-동의) 요청에 담아 보낸다.

### 응답

**200 OK**

| 필드 | 자료형 | 필수 | 설명 |
| --- | --- | --- | --- |
| agreements | Array | O | 현재 적용 중인 동의 항목 목록. 화면에 표시할 순서대로 온다 |
| agreements[].type | String | O | 항목 식별자. 예: `TERMS`, `PRIVACY`, `AI_RISK_DETECTION`, `ADULT` |
| agreements[].version | String | O | 항목 버전 |
| agreements[].title | String | O | 화면에 표시할 문구. 요청 `Accept-Language` 언어로 오고, 없으면 한국어 |
| agreements[].required | Boolean | O | `true`면 동의해야 다음 단계로 갈 수 있다 |
| agreements[].contentUrl | String | X | 전문 URL. 전문이 없는 항목(만 19세 확인 등)은 응답에서 빠진다 |

```json
{
  "agreements": [
    {
      "type": "TERMS",
      "version": "2026-10-19",
      "title": "서비스 이용약관",
      "required": true,
      "contentUrl": "https://example.com/terms"
    },
    {
      "type": "PRIVACY",
      "version": "2026-10-19",
      "title": "개인정보 수집·이용 동의",
      "required": true,
      "contentUrl": "https://example.com/privacy"
    },
    {
      "type": "AI_RISK_DETECTION",
      "version": "2026-10-22",
      "title": "AI 대화 위험 감지를 위한 개인정보 처리 동의",
      "required": true,
      "contentUrl": "https://example.com/ai-risk"
    },
    {
      "type": "ADULT",
      "version": "2026-10-19",
      "title": "만 19세 이상입니다",
      "required": true
    }
  ]
}
```

### 주의사항

- **앱은 항목 종류를 코드에 고정하지 않는다.** 서버 설정으로 항목이 추가·삭제될 수 있으므로(예: 선택 항목인 마케팅 수신 동의), 응답 목록을 그대로 체크박스로 그린다. `required`로 필수 표시와 다음 버튼 활성화를 정한다.
- 앱은 동의 화면을 열 때마다 이 API를 호출한다. 버전을 앱에 저장해 두지 않는다.

## 약관 동의

```
POST /api/v1/users/me/agreements
```

인증: 필요 (`AGREEMENT_REQUIRED` 단계부터)

로그인 직후 첫 단계. 약관 조회로 표시한 항목에 대한 사용자의 선택을 보낸다. 개인정보 수집·이용 동의 전에는 키링·프로필 등 어떤 개인정보도 받지 않기 위해 가장 먼저 한다.

### 요청

| 필드 | 자료형 | 필수 | 설명 |
| --- | --- | --- | --- |
| agreements | Array | O | 약관 조회로 **표시한 모든 항목**에 대한 사용자의 선택 |
| agreements[].type | String | O | 약관 조회 응답의 `type` |
| agreements[].version | String | O | 사용자에게 **표시한** 버전. 약관 조회 응답의 `version` |
| agreements[].agreed | Boolean | O | 동의 여부. `required: true` 항목은 `true`여야 함 |

```json
{
  "agreements": [
    { "type": "TERMS", "version": "2026-10-19", "agreed": true },
    { "type": "PRIVACY", "version": "2026-10-19", "agreed": true },
    { "type": "AI_RISK_DETECTION", "version": "2026-10-22", "agreed": true },
    { "type": "ADULT", "version": "2026-10-19", "agreed": true }
  ]
}
```

### 응답

**200 OK**

| 필드 | 자료형 | 필수 | 설명 |
| --- | --- | --- | --- |
| onboardingStatus | String | O | 동의 후 [가입 단계](#가입-단계). 보통 `KEYRING_REQUIRED` |

```json
{
  "onboardingStatus": "KEYRING_REQUIRED"
}
```

### 오류

| 상태 | code | 상황 |
| --- | --- | --- |
| 400 | INVALID_REQUEST | 필드 누락, 형식 오류 |
| 400 | REQUIRED_AGREEMENT_MISSING | 필수 항목 중 하나라도 `agreed: false` |
| 401 | AUTH_REQUIRED 등 | [공통 오류](#모든-인증-api에-공통인-오류) 참고 |
| 409 | AGREEMENT_VERSION_OUTDATED | 보낸 항목의 버전이 현재와 다름, 현재 없는 항목을 보냄, 현재 항목이 빠짐(빈 목록 포함). 화면을 띄운 사이 항목이 바뀐 경우다. 약관 조회를 다시 호출해 바뀐 약관을 보여 주고 다시 동의받은 뒤 재요청 |

### 주의사항

- **약관 버전은 앱이 보낸 값을 서버가 검증한다.** 서버는 앱이 표시한 버전이 현재 버전과 같을 때만 저장하고, 동의한 항목의 버전과 동의 시각을 남긴다. 사용자가 보지 않은 버전에 동의한 것으로 기록되지 않게 하기 위해서다. 선택 항목에 동의하지 않은 경우는 저장하지 않는다.
- **다시 보내면 보낸 내용이 현재 동의 상태가 된다.** 같은 내용을 다시 보내면 결과도 같으므로, 응답을 받지 못했으면 그대로 재시도한다. 선택 항목을 `false`로 바꿔 보내면 그 항목의 동의는 철회된다. 필수 항목은 이 API로 철회할 수 없다(`400 REQUIRED_AGREEMENT_MISSING`).
- 동의·철회 시각은 이력으로 남는다.
- `ADULT`는 사용자의 자기 확인이다. 실제 나이는 [프로필 저장](#프로필-저장)의 생년월일로 다시 확인한다.
- 가입 전 필수 항목은 기능 명세 §2.7을 따른다: 이용약관, 계정 생성·관리용 개인정보, AI 대화 위험 감지용 개인정보 처리, 만 19세 이상 확인. 프로필·설문 저장, 공개 프로필 제공, 사진·자기소개·종교 선택 동의처럼 단계별로 받는 동의는 별도 작업에서 정한다.

## 프로필 저장

```
PATCH /api/v1/users/me/profile
```

인증: 필요 (`PROFILE_REQUIRED` 단계부터. 키링 등록 API 전까지는 임시로 `KEYRING_REQUIRED` 단계부터)

본인 프로필을 저장한다. 가입 중 자동 임시 저장과 가입 후 수정을 같은 API로 한다. 임시 저장만으로는 단계를 마치거나 값이 고정되지 않는다. 필수 항목을 다 채운 뒤 [프로필 등록 완료](#프로필-등록-완료)를 호출해야 `completed: true`가 되고 `PROFILE_REQUIRED` 단계를 마친다. 사진은 별도 사진 API에서 다룬다.

### 요청

**보낸 필드만 바꾼다.** 필드를 생략하면 그대로 두고, 선택 항목에 `null`을 보내면 비운다.

| 필드 | 자료형 | 구분 | 설명 |
| --- | --- | --- | --- |
| nickname | String | 필수 항목 | [닉네임 규칙](#닉네임) |
| gender | String | 필수 항목 | `MALE`, `FEMALE` 중 하나. 프로필 등록 완료 뒤에는 바꿀 수 없다 |
| birthDate | String | 필수 항목 | 생년월일 `YYYY-MM-DD`. [생년월일 규칙](#생년월일과-나이). 프로필 등록 완료 뒤에는 바꿀 수 없다 |
| smokingStatus | String | 필수 항목 | [생활 정보 코드](#생활-정보-코드) |
| drinkingFrequency | String | 필수 항목 | [생활 정보 코드](#생활-정보-코드) |
| religion | String | 선택 항목 | `null`이면 미입력. [생활 정보 코드](#생활-정보-코드) |
| introduction | String | 선택 항목 | `null`이거나 공백·줄바꿈뿐이면 미입력. [자기소개 규칙](#자기소개) |

"필수 항목"은 프로필 등록 완료 전에 채워야 한다는 뜻이다. 임시 저장에서는 생략할 수 있지만 `null`로 비울 수는 없다(`400 INVALID_REQUEST`).

```json
{
  "nickname": "가을 하늘",
  "gender": "FEMALE",
  "birthDate": "2001-03-15"
}
```

### 응답

**200 OK** — 저장된 전체 프로필. [프로필 조회](#프로필-조회) 응답과 같다.

```json
{
  "userId": 1024,
  "nickname": "가을 하늘",
  "gender": "FEMALE",
  "birthDate": "2001-03-15",
  "smokingStatus": "NON_SMOKER",
  "drinkingFrequency": "MONTHLY_TWO_TO_FOUR",
  "religion": "UNDISCLOSED",
  "introduction": "산책이랑 전시 보는 걸 좋아해요.",
  "imageUrl": null,
  "hasProfilePhoto": false,
  "completed": false
}
```

### 오류

| 상태 | code | 상황 |
| --- | --- | --- |
| 400 | INVALID_NICKNAME | 닉네임 길이·허용 문자 규칙 위반 |
| 400 | INVALID_BIRTH_DATE | 형식 오류, 존재하지 않는 날짜, 미래 날짜 |
| 400 | INVALID_INTRODUCTION | 자기소개 200자 초과, 줄바꿈 문자 3개 초과 |
| 400 | INVALID_REQUEST | 본문이 JSON 객체가 아님, 필수 항목을 `null`로 보냄, 정해지지 않은 코드 값 |
| 401 | AUTH_REQUIRED 등 | [공통 오류](#모든-인증-api에-공통인-오류) 참고 |
| 403 | UNDER_AGE | 만 19세 미만. 저장하지 않는다 |
| 403 | ONBOARDING_REQUIRED | 아직 `PROFILE_REQUIRED` 단계에 오지 않음 |
| 409 | NICKNAME_DUPLICATED | 다른 회원이 사용 중인 닉네임. 닉네임 입력 칸에 오류 표시 |
| 422 | PROFILE_FIELD_LOCKED | 프로필 등록 완료 뒤 `gender`·`birthDate`를 다른 값으로 바꾸려 함. 같은 값은 통과. 오입력은 서비스팀 정정으로 고친다(기능 명세 PRO-03) |

### 주의사항

- 한 요청에 오류가 하나라도 있으면 아무것도 저장하지 않는다.
- 같은 내용을 다시 보내도 결과가 같으므로, 응답을 받지 못했으면 그대로 재시도한다.
- 필수 항목을 다 채웠는지는 앱이 입력값으로 판단해 "프로필 등록 완료" 버튼을 켠다. 서버도 등록 완료 때 다시 확인한다.
- **`403 UNDER_AGE`를 받으면** 앱은 "만 19세 이상만 이용할 수 있어요" 안내를 띄우고 로그아웃한다. 생년월일은 자기신고라 다른 날짜로 다시 입력하는 것을 막지 못한다. 실제 연령 인증 수단은 출시 준비 단계에서 정한다(34번 정책).
- 닉네임은 중복 확인에서 사용 가능이었어도 저장 시점에 다른 사람이 먼저 가져가면 `NICKNAME_DUPLICATED`가 올 수 있다.
- 사진 API가 생기기 전까지 `hasProfilePhoto`는 항상 `false`이고 `imageUrl`은 `null`이다.
- 프로필이 바뀌면 키링 반영용 설정 버전(B 담당 `matching_settings.settings_version`)을 올린다(키링 기능 연동 후 적용). 프로필 자체에는 버전 필드가 없다.
- **공개 범위:** 닉네임·성별·나이·생활 정보·자기소개는 **유효한 매칭 제안을 조회할 때부터** 상대에게 공개한다. 생년월일 원본은 상대용 응답과 AI 입력에 넣지 않고 계산한 나이만 쓴다. 종교가 미입력이거나 `UNDISCLOSED`면 상대에게 "종교 정보 비공개"로 보인다.

## 프로필 등록 완료

```
POST /api/v1/users/me/profile/complete
```

인증: 필요 (`PROFILE_REQUIRED` 단계부터. 키링 등록 API 전까지는 임시로 `KEYRING_REQUIRED` 단계부터)

"프로필 등록 완료" 버튼. 임시 저장한 프로필을 최종 등록한다(기능 명세 PRO-01). 성공하면 `PROFILE_REQUIRED` 단계를 마치고, 이때부터 생년월일·성별을 바꿀 수 없다. 앱은 이 요청 전에 두 값을 다시 확인하는 화면을 보여 준다.

### 요청

본문 없음.

### 응답

**200 OK**

| 필드 | 자료형 | 필수 | 설명 |
| --- | --- | --- | --- |
| onboardingStatus | String | O | 등록 후 [가입 단계](#가입-단계). 보통 `SURVEY_REQUIRED` |

```json
{
  "onboardingStatus": "SURVEY_REQUIRED"
}
```

### 오류

| 상태 | code | 상황 |
| --- | --- | --- |
| 400 | PROFILE_INCOMPLETE | 필수 항목(닉네임·성별·생년월일·흡연·음주) 중 빈 것이 있음. 프로필 저장으로 채운 뒤 다시 요청 |
| 401 | AUTH_REQUIRED 등 | [공통 오류](#모든-인증-api에-공통인-오류) 참고 |
| 403 | ONBOARDING_REQUIRED | 아직 이 단계에 오지 않음 |

### 주의사항

- 이미 등록한 프로필에 다시 요청해도 아무것도 바꾸지 않고 200이다. 응답을 받지 못했으면 그대로 재시도한다.
- 등록 시각을 서버에 남긴다(ERD `profiles.completed_at`).

## 프로필 조회

```
GET /api/v1/users/me/profile
```

인증: 필요 (`PROFILE_REQUIRED` 단계부터. 키링 등록 API 전까지는 임시로 `KEYRING_REQUIRED` 단계부터)

본인 프로필을 받는다. 가입 중 저장한 초안을 다시 채워 넣을 때 쓴다.

### 응답

**200 OK**

| 필드 | 자료형 | 필수 | 설명 |
| --- | --- | --- | --- |
| userId | Number | O | 서비스 회원번호 |
| nickname | String \| null | O | 저장 전이면 `null` |
| gender | String \| null | O | |
| birthDate | String \| null | O | `YYYY-MM-DD`. 본인 조회에서만 준다 |
| smokingStatus | String \| null | O | |
| drinkingFrequency | String \| null | O | |
| religion | String \| null | O | |
| introduction | String \| null | O | |
| imageUrl | String \| null | O | 현재 프로필 사진 주소. 사진이 없으면 `null` |
| hasProfilePhoto | Boolean | O | 사진 등록 여부 |
| completed | Boolean | O | [프로필 등록 완료](#프로필-등록-완료)를 마쳤는지. 필수 항목이 다 찼는지가 아니다 |

### 오류

| 상태 | code | 상황 |
| --- | --- | --- |
| 401 | AUTH_REQUIRED 등 | [공통 오류](#모든-인증-api에-공통인-오류) 참고 |
| 403 | ONBOARDING_REQUIRED | 아직 `PROFILE_REQUIRED` 단계에 오지 않음 |

## 닉네임 중복 확인

```
GET /api/v1/users/nickname-availability?nickname={nickname}
```

인증: 필요 (`PROFILE_REQUIRED` 단계부터. 키링 등록 API 전까지는 임시로 `KEYRING_REQUIRED` 단계부터)

프로필 화면에서 입력 중인 닉네임을 쓸 수 있는지 미리 확인한다.

### 요청

**Query**

| 이름 | 자료형 | 필수 | 설명 |
| --- | --- | --- | --- |
| nickname | String | O | 확인할 닉네임. URL 인코딩 |

```
GET /api/v1/users/nickname-availability?nickname=%EA%B0%80%EC%9D%84%20%ED%95%98%EB%8A%98
```

### 응답

**200 OK**

| 필드 | 자료형 | 필수 | 설명 |
| --- | --- | --- | --- |
| nickname | String | O | 확인한 닉네임(앞뒤 공백 제거·정규화 후) |
| available | Boolean | O | 사용 가능 여부 |
| reason | String | X | 사용할 수 없을 때만. `DUPLICATED`, `INVALID_FORMAT` 중 하나 |

```json
{
  "nickname": "가을 하늘",
  "available": false,
  "reason": "DUPLICATED"
}
```

### 오류

| 상태 | code | 상황 |
| --- | --- | --- |
| 400 | INVALID_REQUEST | nickname 누락 |
| 401 | AUTH_REQUIRED 등 | [공통 오류](#모든-인증-api에-공통인-오류) 참고 |
| 403 | ONBOARDING_REQUIRED | 아직 `PROFILE_REQUIRED` 단계에 오지 않음 |

### 주의사항

- 형식 오류는 400이 아니라 `200` + `available: false` + `reason: INVALID_FORMAT`으로 응답한다. 입력 중 실시간 확인에 쓰기 때문이다.
- 이 결과는 확인 시점 기준이다. 최종 판단은 프로필 저장 API에서 한다.
- 본인이 이미 저장한 닉네임은 `available: true`다.
- 앱은 입력이 멈춘 뒤 요청한다(예: 마지막 입력 후 0.3초). 글자마다 요청하지 않는다.

## 토큰 재발급

```
POST /api/v1/auth/token/refresh
```

인증: 불필요 (액세스 토큰이 만료된 상태에서 호출)

### 요청

| 필드 | 자료형 | 필수 | 설명 |
| --- | --- | --- | --- |
| refreshToken | String | O | 가장 최근에 받은 리프레시 토큰 |

```json
{
  "refreshToken": "c2b7e1f0-..."
}
```

### 응답

**200 OK** — 소셜 로그인 응답과 같은 형식

| 필드 | 자료형 | 필수 | 설명 |
| --- | --- | --- | --- |
| accessToken | String | O | 새 액세스 토큰 |
| refreshToken | String | O | **새 리프레시 토큰** |
| tokenType | String | O | 항상 `Bearer` |
| accessTokenExpiresAt | String | O | 액세스 토큰 만료 시각 (ISO-8601, UTC) |
| refreshTokenExpiresAt | String | O | 리프레시 토큰 만료 시각 (ISO-8601, UTC) |
| onboardingStatus | String | O | 현재 [가입 단계](#가입-단계) |

```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
  "refreshToken": "9a41d3c8-...",
  "tokenType": "Bearer",
  "accessTokenExpiresAt": "2026-10-20T02:00:00Z",
  "refreshTokenExpiresAt": "2026-11-03T01:30:00Z",
  "onboardingStatus": "READY"
}
```

### 오류

| 상태 | code | 상황 | 앱 처리 |
| --- | --- | --- | --- |
| 400 | INVALID_REQUEST | refreshToken 누락 | - |
| 401 | INVALID_REFRESH_TOKEN | 만료·폐기·존재하지 않는 리프레시 토큰, 탈취로 판단된 재사용(아래 표) | 저장된 토큰을 지우고 로그인 화면으로 |

### 주의사항

- **재발급할 때마다 리프레시 토큰도 새로 바뀐다.** 앱은 응답의 새 리프레시 토큰으로 반드시 교체 저장한다.

**이전 리프레시 토큰이 다시 들어왔을 때 (서버)**

| 상황 | 서버 처리 |
| --- | --- |
| 교체된 지 **30초 이내**이고, 그때 발급한 새 토큰이 **아직 쓰이지 않음** | 응답이 유실돼 재시도한 것으로 본다. 새 토큰 쌍을 다시 발급하고, 앞서 발급했지만 앱이 받지 못한 토큰은 폐기한다. 이전 토큰이 그사이 만료됐어도 받아 준다 |
| 교체된 지 **30초가 지남** | 탈취로 본다. 그 회원의 **모든 세션**과 리프레시 토큰을 폐기하고 `401 INVALID_REFRESH_TOKEN`. 모든 기기에서 다시 로그인해야 한다. 이전 토큰이 만료됐어도 같다 |
| 30초 이내지만 새 토큰이 **이미 쓰임** | 응답이 유실된 게 아니라 이전 토큰을 다른 누군가가 가진 것이므로 탈취로 본다. 전체 폐기 |
| 응답 유실 재시도로 **폐기된 토큰**이 나중에 쓰임 | 앱은 그 토큰을 받지 못했으므로 탈취로 본다. 전체 폐기 |

같은 회원의 재발급 요청은 서버가 한 번에 하나씩 처리한다. 로그아웃한 세션의 리프레시 토큰이 다시 오면 탈취로 보지 않고 `401 INVALID_REFRESH_TOKEN`만 준다(다른 기기 세션은 유지).

30초는 서버 설정값이며, 바뀌면 이 명세의 버전을 올린다.

**재시도 규칙 (앱)**

- 응답을 받지 못한 경우(네트워크 오류, 타임아웃)에만 **같은 리프레시 토큰으로 최대 2번** 재시도한다. 간격은 1초, 3초다.
- `401 INVALID_REFRESH_TOKEN`을 받으면 재시도하지 않고 저장된 토큰을 지운 뒤 로그인 화면으로 간다.
- 새 토큰을 저장한 **뒤에** 원래 요청을 재시도한다.
- 재발급 요청을 **동시에 여러 번 보내지 않는다.** 여러 요청이 동시에 401을 받으면 재발급은 한 번만 하고, 나머지 요청은 그 결과를 기다렸다가 재시도한다.

## 로그아웃

```
POST /api/v1/auth/logout
```

인증: 불필요 (액세스 토큰이 만료돼도 호출할 수 있도록 리프레시 토큰으로 본인을 확인한다)

현재 기기의 로그인 세션을 끝낸다. 그 세션의 리프레시 토큰과 액세스 토큰은 남은 시간과 상관없이 바로 쓸 수 없게 된다. 같은 회원의 다른 기기 로그인은 유지된다.

### 요청

| 필드 | 자료형 | 필수 | 설명 |
| --- | --- | --- | --- |
| refreshToken | String | O | 이 기기에 저장된 리프레시 토큰 |

### 응답

**204 No Content** — 본문 없음

### 오류

| 상태 | code | 상황 | 앱 처리 |
| --- | --- | --- | --- |
| 400 | INVALID_REQUEST | refreshToken 누락 | - |
| 401 | INVALID_REFRESH_TOKEN | 서버가 모르는 리프레시 토큰 | 저장된 토큰을 지우고 로그인 화면으로 |

### 주의사항

- 같은 토큰으로 다시 보내도 `204`다(이미 끝난 세션을 다시 끝내는 것뿐이다). 응답을 받지 못했으면 그대로 재시도한다.
- 만료된 리프레시 토큰으로도 로그아웃할 수 있다.
- 앱은 응답과 상관없이 저장된 토큰을 지우고 로그인 화면으로 간다.

## 입력값 규칙

FE와 BE가 **같은 규칙**으로 검사한다. 정책 근거는 34번 정책 문서다.

### 닉네임

| 규칙 | 내용 |
| --- | --- |
| 정규화 | 앞뒤 공백을 지우고 Unicode NFC로 정규화한 값을 검사·저장한다 |
| 길이 | 정규화 후 **2~20자**. 허용 문자가 한글·영문·숫자·공백뿐이라 문자 하나가 화면의 한 글자다 |
| 허용 문자 | 한글 완성형(가~힣), 영문, 숫자, 일반 공백(U+0020). 독립 자음·모음(단독·혼합)·특수문자·이모지·탭·줄바꿈 불가 |
| 공백 | 내부 공백 허용, 연속 공백도 그대로 보존. 공백만으로는 불가 |
| 중복 | 영문 대소문자를 구분하지 않는다. `John Smith`와 `john smith`는 같은 닉네임이다 |

### 생년월일과 나이

| 규칙 | 내용 |
| --- | --- |
| 형식 | `YYYY-MM-DD`. 실제 존재하는 날짜, 미래 날짜 불가 |
| 나이 계산 | **한국 날짜(Asia/Seoul) 기준 만 나이.** `오늘 >= 올해 생일`이면 `올해 - 출생연도`, 아니면 거기서 1을 뺀다. 생일 당일부터 나이가 오른다 |
| 2월 29일생 | 평년에는 올해 생일을 **3월 1일**로 본다. 윤년에는 2월 29일이다 |
| 가입 기준 | 만 19세 이상, 상한 없음. 미만이면 `403 UNDER_AGE` |
| 공개 | 상대에게는 계산한 나이만 준다. 생년월일 원본은 본인 조회에서만 준다 |

생년월일은 자기신고이며 본인·연령 인증을 뜻하지 않는다. 별도 인증 수단은 출시 준비 단계에서 정한다.

### 자기소개

| 규칙 | 내용 |
| --- | --- |
| 길이 | 최대 **200자**. 화면에 보이는 글자 단위(Unicode 확장 문자소)로 센다. 이모지 하나는 1자 |
| 줄바꿈 | 줄바꿈 문자(`\n`) 최대 3개, 즉 최대 4줄. `\r\n`은 `\n` 하나로 바꿔서 센다 |
| 허용 문자 | 특수문자·이모지 허용 |
| 빈 값 | 공백·줄바꿈뿐이면 미입력으로 저장한다 |

글자 수 세는 법: BE는 Java `BreakIterator.getCharacterInstance()`, FE는 Dart `characters` 패키지(`.characters.length`)를 쓴다.

### 생활 정보 코드

화면 표시명은 34번 정책에서 확정됐고, 아래 전송 코드는 공통 OpenAPI와 동일하게 사용한다(2026-10-21 사용자 승인 정합화 기준). 코드 순서는 점수가 아니다(생활 정보는 매칭 점수·제외 조건에 쓰지 않는다).

**smokingStatus (단계를 마치려면 필수)**

| 코드 | 화면 표시 |
| --- | --- |
| `NON_SMOKER` | 비흡연 |
| `E_CIGARETTE` | 전자담배 |
| `ONLY_WHEN_DRINKING` | 술마실때만 가끔흡연 |
| `SMOKER` | 흡연 |

**drinkingFrequency (단계를 마치려면 필수)**

| 코드 | 화면 표시 |
| --- | --- |
| `NEVER` | 전혀 안마심 |
| `SPECIAL_OCCASIONS_ONLY` | 중요한 자리일때만 |
| `MONTHLY_AT_MOST_ONCE` | 월 1회이하 |
| `MONTHLY_TWO_TO_FOUR` | 월 2~4회 |
| `WEEKLY_TWO_TO_THREE` | 주 2~3회 |
| `WEEKLY_FOUR_OR_MORE` | 주 4회 이상 |

**religion (선택)**

| 코드 | 화면 표시 |
| --- | --- |
| `NONE` | 무교 |
| `PROTESTANT` | 개신교 |
| `CATHOLIC` | 천주교 |
| `BUDDHIST` | 불교 |
| `OTHER` | 기타 |
| `UNDISCLOSED` | 공개하지 않음 |

종교를 건너뛰면 필드를 보내지 않는다(미입력). 이미 고른 종교를 지우려면 `null`을 보낸다(PATCH에서 생략은 기존 값 유지). 미입력과 `UNDISCLOSED`는 서버에 구분해 저장하지만 상대에게는 둘 다 "종교 정보 비공개"로 보인다. 선택지에 기본 선택값을 두지 않는다.

## 변경 이력

| 버전 | 날짜 | 내용 | 호환성 |
| --- | --- | --- | --- |
| v0.1.0 | 2026-10-21 | 최초 작성. 리뷰 중 변경: 토큰 응답을 만료 시각(`...ExpiresAt`)과 `onboardingStatus` 형식으로 변경, 소셜 이메일 수집 추가, 34번 정책 반영(가입 단계 7개와 단계별 접근 규칙, 회원가입을 약관 동의와 프로필 저장으로 분리, 생년월일·만 나이·2월 29일 규칙, 닉네임 2~20자·공백 허용, 생활 정보·자기소개, 닉네임 공개 시점), 로그인 세션(`sid`)과 로그아웃, 프로필 저장을 PATCH(초안·수정 겸용)로 변경, 가입 단계 조회 응답을 `nextStep`과 완료 여부 필드로 변경 | - |
| v0.1.1 | 2026-10-21 | MR 리뷰 반영(통합 OpenAPI와 통일): 토큰 응답은 통합 OpenAPI `TokenPair` 6개 필드 유지, 미구현 가입 단계를 통과시키지 않음(프로필은 키링 API 전까지 `KEYRING_REQUIRED`부터 임시 허용), 401 오류 코드를 `AUTH_REQUIRED`·`ACCESS_TOKEN_EXPIRED`·`ACCESS_TOKEN_INVALID`·`SESSION_REVOKED`로 분리(만료만 재발급), 가입 단계 조회에 설문·설정 필드 추가(임시값), 값이 없는 필드는 `null`로 보내고 정의되지 않은 요청 필드는 400, 흡연·음주 코드를 통합 OpenAPI 값으로 변경 | FE 미착수라 영향 없음 |
| v0.1.2 | 2026-10-22 | 기능 명세 v0.1.81 반영: 가입 필수 동의에 `AI_RISK_DETECTION` 추가(§2.7), 프로필 등록 완료 API 추가와 생년월일·성별 고정 시점을 등록 완료로 변경(PRO-01, `completed` 뜻 변경, `PROFILE_INCOMPLETE`), 가입을 마친 회원은 키링이 없어도 기존 기능 사용(AUTH-02), 오류 `message`를 요청 언어로·없으면 한국어(§1.1) | 미착수 FE 영향 없음 |

## 설치 연결을 해제하는 선택적 로그아웃

최신 설치 모듈 계약에 따라 기존 refreshToken 요청에 선택 UUID releaseInstallationId를 보낼 수 있다. 검증된 리프레시 증명의 회원·family와 현재 설치가 일치할 때만 같은 트랜잭션에서 해제한다. 생략하면 일반 로그아웃처럼 연결을 보존한다. 이미 다른 로그인/회원에 다시 연결된 설치는 지연 요청으로 해제하지 않는다. 성공은 기존204이며 상세는 [설치 계약](push-installations.md)을 따른다.
