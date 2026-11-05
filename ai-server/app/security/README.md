# 공통 서비스 인증·Request ID

2026-10-30 AI MR 기준 develop: `54182aaa27d00f71443faea6ace83022b7f34e49`.
기존 BE 인증 구현은 develop에 내용이 반영되어 있으며 Java 코드를 다시 추가하지 않는다.

JWT 서명·claims 검증은 `service_auth.py`의 `ServiceJWTVerifier.verify_claims()` 한 구현을 사용한다.

| 소비 코드 | 입력/반환 및 예외 |
| --- | --- |
| 개인 분석 테스트 앱 | `ServiceJWTVerifier(request)`는 성공 시 `True`, state에는 `ServiceIdentity`; 실패는 `ServiceAuthError` |
| 직접 JWT 검증 | `verify_token(token)`은 `ServiceIdentity`, `verify_claims(token)`은 검증된 claims |
| 기존 main/위험 분석/매칭 이유 | `ServiceAuthenticator(request)`는 검증된 claims; 실패는 `HTTPException`으로 변환 |

개인 분석 `authorize`는 정확한 `True` 반환을 요구하므로 `ServiceAuthenticator`를 그대로 주입하지 않는다.
테스트 앱에는 `ServiceJWTVerifier`를 사용하며 운영 main에 개인 분석을 등록하지 않는다.

`ServiceAuthSettings`는 `app_env`/`environment`,
`service_auth_public_key_files`/`public_keys`/`ai_service_public_keys` 이름을 호환한다.
기존 main/config의 `APP_ENV`, `AI_SERVICE_PUBLIC_KEYS` 소비 코드는 변경하지 않는다.
키 값은 허용된 로컬 공개키 파일 경로다. PEM·개인키·토큰을 설정 예제나 보고서에 저장하지 않는다.
기존 `settings.public_keys` 딕셔너리의 항목 변경과 `reload_keys()`를 통한 키 교체는 유지한다.
기존 frozen dataclass와 새 BaseSettings의 생성·설정 의미는 완전히 같지는 않다.

검증 규칙은 RS256, typ=`tingle-service+jwt`, 등록 kid, 환경별 iss/aud,
sub=`tingle-be`, UUIDv4 jti, 정수 iat/nbf/exp, nbf=iat, exp-iat=300초,
시계 오차 30초, 정확한 `ai:execute` scope다.
헤더 allowlist를 유지하여 jku/jwk/x5u/x5c 등 추가 헤더를 거절하고 네트워크 키 조회를 하지 않는다.
중복 JSON member와 NaN/Infinity를 거절한다.

develop의 기존 검증은 0초 초과 300초 이하 토큰을 허용했지만 이번 검증은 정확한 300초를 요구한다.
현재 BE 발급기는 300초를 사용하며 Java 코드는 수정하지 않는다.
Bearer 파싱은 `split()`을 사용하므로 기존 ASCII 공백 전용 정규식보다 공백 허용 범위가 넓다.
공개키는 RSA 2048비트 이상인 단일 공개 PEM만 허용한다.
이러한 공통 인증 입력 차이는 기존 소비 경로에도 적용되므로 MR 리뷰에서 확인한다.

`is_ready`/`reload_keys()`는 전체 키 검증 후 verifier를 교체한다.
교체 실패 시 이전 키로 계속 인증하지 않고 준비 상태를 해제하여 503으로 차단한다.
운영 main은 기존처럼 키가 비어 있으면 health를 유지하고 보호 경로를 503으로 차단하며,
비어 있지 않은 잘못된 키 설정에서는 기동을 거절한다.
별도 opt-in `service_auth_lifespan`은 키 누락도 기동 실패로 처리하며 운영 main에는 연결하지 않는다.

오류는 고정 code/message와 `Cache-Control: no-store`만 사용한다.
401은 인증 누락·토큰 무효·만료, 403은 권한 부족, 400은 중복/복수 인증 헤더,
503은 설정/인증기 장애다. 503 외 인증 오류에는 고정 `WWW-Authenticate`를 제공한다.
기존 공통 HTTPException handler는 code를 보존하고 메시지를 공통 문구로 정규화한다.

`RequestIdMiddleware` 하나를 사용하고 `RequestIDMiddleware`는 동일 클래스의 이름 별칭이다.
운영 앱에 한 번만 등록한다. 단일 UUID를 그대로 유지하고 누락·잘못된 값·중복은 새 UUIDv4로 바꾼다.
request.state와 응답 `X-Request-Id`가 일치하며 모든 응답에 `no-store`를 적용한다.
이미 시작한 응답에는 두 번째 오류 응답을 보내지 않는다.

회귀 검증은 `tests/security/`, 기존 `tests/test_service_auth.py`,
`tests/test_service_auth_startup.py`, 위험 분석·매칭 이유 HTTP 테스트를 함께 실행한다.
과거 Java HTTP 10건·위험 HTTP 4건·BE 86 PASS/1 SKIP은 별도 통합 검증 이력이며
이 AI MR 작업본에서 Java 검증을 재실행했다는 의미가 아니다.
