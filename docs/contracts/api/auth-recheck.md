# 앱 인증·토큰 연동 확인 사항

수정 2026-10-21 14:16 KST. 로그인 담당 연락과 !14 80310e6·!16 4f69108를 대조해 문서만 수정했다. 아래 미정 항목의 합의·코드 수정·실제 공급자/DB/앱 통합 검증은 하지 않았다.

## MR9 후속 로컬 통합 검증

매칭 제안은 sid=refresh family를 채택하고 세션 폐기와 최신 필수 동의·프로필·기기·설문·분석·조건·ACK를 DB에서 검사하도록 수정했다. MR16 형식 토큰으로 PostgreSQL 통합 테스트를 통과했다. 아래 MR16 자체 미완료·협의 사항을 해결한 것으로 간주하지 않는다. 실제 공급자 로그인·앱 통합·공용 DB 적용은 수행하지 않았다.

## 이번에 명세에 반영한 내용

1. 가입 순서는 AGREEMENT_REQUIRED → KEYRING_REQUIRED → PROFILE_REQUIRED → SURVEY_REQUIRED → PREFERENCES_REQUIRED → SYNC_PENDING → READY다. 연락의 AGREEMENT/SYNC는 설명용 약칭이며 enum을 바꾸지 않는다.
2. POST /api/v1/users/me/agreements를 추가했다. 요청은 {agreements:[{type,version,agreed}]},200 응답은 {onboardingStatus}다. 현재 목록·버전·필수 항목 동의는 서버가 검사한다.
3. POST /api/v1/auth/signup은 활성 계약에서 제거하고 OpenAPI x-excluded-operations에 과거 요청·응답으로 보존했다. 약관 동의와 프로필 PATCH로 나눠 호출한다.
4. 로그인·재발급 응답은 accessToken,refreshToken,tokenType,accessTokenExpiresAt,refreshTokenExpiresAt,onboardingStatus로 맞췄다. 기존 남은 초·가입 완료 필드의 호환 여부는 합의 전이다.
5. 프로필 PATCH와 refresh 본문/204 로그아웃 계약을 유지한다. PROFILE_FIELD_LOCKED는 임시 코드로 기록한다.

## 합의 필요

| ID | 합의할 내용 | 현재 처리 |
| --- | --- | --- |
| AUTH-OPEN-01 | 새 토큰6개 필드 채택·기존 accessTokenExpiresIn/signupCompleted 병행 유지·전환 시점 | 구현된 새 응답을 명세 초안에 반영. 구형 필드 미제공은 호환 승인 완료가 아님 |
| AUTH-OPEN-02 | 422 PROFILE_FIELD_LOCKED 이름·잠금 시작 시점·POL-02 변경 허용 정책 | 임시 코드로 표시. 기존 POLICY_UNRESOLVED와 구분 |
| AUTH-OPEN-03 | sid=refresh family 채택·로그아웃/탈취의 기기별·전체 폐기 범위·30초 유실 재시도 | 구현을 기록하되 기존 폐기 검사를 임의 제거하지 않음 |
| AUTH-OPEN-04 | 401 만료·위조·폐기 구분과 앱 자동 갱신 조건 | 현재 코드 UNAUTHORIZED와 통합 명세의 갱신 분기 차이를 미정으로 남김 |
| AUTH-OPEN-05 | app_users.email·물리 FK 및 text/길이 차이, 인증/생활정보 전송 코드 | ERD/DDL 변경 없음. 현재4개 테이블명은 일치하지만 email/FK1개는 별도 조정 |
| AUTH-OPEN-06 | 미구현 가입 게이트·단계 조회 추가 필드·프로필 필수 nullable/미정의 필드 검증 | 코드의 부분 구현과 목표 계약을 구분. 프로필 완료만으로 READY 처리하지 않는 정책 유지 |

## 구현과 문서의 경계

- 현재 단계 계산은 약관·프로필만 검사하고 다른 게이트는 통과시키므로, 실제 전체 가입 READY와 동일하지 않다. 단계 응답의 설문 진행도·설정 버전 필수값도 전부 구현된 것은 아니다.
- ProfileResponse의 NON_NULL은 필수 nullable 키를 생략하고 JsonNode PATCH는 미정의 키를 거부하지 않는 차이가 있다. 이 문서 수정으로 코드를 고친 것으로 표시하지 않는다.
- 생활정보는 소스의 SOCIAL_SMOKER/축약 음주 코드와 통합 API의 ONLY_WHEN_DRINKING/긴 음주 코드가 다르다. 이번 연락이 전송 코드 변경까지 승인한 것으로 간주하지 않는다.
- auth.md의 상세 가입 분리 경로와 새 응답은 구현에 존재한다. 아직 남은 합의 항목을 이유로 새 경로를 예전 통합 signup으로 되돌리라고 요구하지 않는다.

## 테이블 연락의 현재성

연락의 users/login_sessions/user_agreements 및 과거 ERD social_accounts는 이전 상태다. 확인한 최신 소스는 app_users,refresh_tokens,profiles,user_consents를 사용하며 별도 login_sessions/social_accounts가 없다. 현재 ERD와 테이블명은 맞는다. 단 app_users.email 추가와 profiles의 물리 FK1개는 ERD에 반영되지 않았다. 현재24개·162개 ERD/SQL을 임의 확장하지 않는다.

## 유지하는 앱 인증 원칙

- Google ID token·Kakao/Naver access token은 서버에서 검증하고 provider+subject로 식별한다. 이메일·이름 기준 자동 계정 통합은 없다.
- 토큰은 OS 보호 저장소에 보관한다. 토큰·소셜 증명·개인정보·메시지는 로그/이슈에 넣지 않는다.
- access30분/refresh14일과30초 유실 재시도는 현재 구현/계약 값이다. 401 실패 분기와 재사용 탐지 범위는 위 합의가 필요하다.
- 가입 상태는 서버 원본에서 계산하며 요청의 userId/sessionId를 소유권 근거로 믿지 않는다.

## 인계 순서

변경 문서 검토·게시 → 인증 담당에게 추천안과 AUTH-OPEN 인계 → 담당자·FE 합의 → 합의 결과를 인증 명세와 구현에 반영한다. 이전 doc-docs/api-erd-complete MR은 이미 develop에 병합됐으므로 최신 develop 기반 후속 문서 브랜치를 사용한다. 이 문서의 게시·병합은 미정 정책 확정이나 로그인 코드 수정 완료를 뜻하지 않는다.

[현재 API](README.md) / [변경 이력](changes-20261021.md) / [ERD](../../database/erd.md)
