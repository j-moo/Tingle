# Tingle(팅글) 매칭 제안 개발·검증

MR9(6d0f1d8)의 제안 기능을 develop(0b1ddde)의 초기 구성과 통합한 로컬 검토본이다. Java21·Spring Boot4.1.1·Gradle9.8.1·PostgreSQL17을 사용한다. 시작 클래스는 BackendApplication 하나이며 Gradle 프로젝트 이름은 tingle-back을 유지한다.

## 실행 설정

back에서 실행한다. .env.example을 참고해 로컬 전용 JWT_SECRET(32바이트 이상)을 설정한다. back/.env는 Spring이 직접 읽고 OS 환경변수가 우선한다. 기본 local 프로필은 Compose PostgreSQL 및 ddl-auto=update를 사용한다. 명시적 dev/prod 설정은 후속이다. 공통 기본값은 validate이며 Compose는 local에서만 켜진다.

```text
./gradlew bootRun
```

## 테스트

전체 build는 Testcontainers의 일회용 PostgreSQL17 DB가 필요하다. 기존 DB 접속정보를 지정하지 않는다. 제안 테스트는 자체 가상 사용자·세션을 생성하고 실제 JWT 필터·JPA·잠금 경합을 검사한다. 실제 소셜 로그인·MR16 통합 검증은 아니다.

```text
./gradlew build
```

DB가 필요 없는 응답·오류·설정 검증과 컴파일·스타일·실행 파일 검증:

```text
./gradlew test --tests '*ProposalResponseTest' --tests '*GlobalExceptionHandlerTest' --tests '*RuntimeConfigurationTest' checkstyleMain checkstyleTest bootJar
```

오류 응답 테스트의 MVC 슬라이스에서는 인증 컴포넌트를 제외한다. 실제 인증 필터·가입 제한 검증은 ProposalIntegrationTest에서 수행한다.

## 유지한 제안 기능

제안 목록·단건·공개 프로필·수락·거절·24시간 만료, 정각 판정, 최초 선택 고정과 동일 선택 재시도, 거절30일 제한, 쌍 잠금을 유지한다. 사진은 imageUrl 및 hasProfilePhoto, 사진이 없으면 imageUrl:null이다. 공개 응답에 proposalId/profileVersion/profileImageId를 넣지 않는다. 목록은 items/nextCursor/serverTime, 마지막 cursor는 null이다. 종료 프로필·마감 선택은410 PROPOSAL_EXPIRED, 없음/타인은 동일404 RESOURCE_NOT_FOUND, 선택 변경은409 PROPOSAL_STATE_CONFLICT다. 오류 구현은 develop의 BusinessException(ErrorCode)와 ErrorResponse를 사용한다.

## 남은 통합 범위

임시 login_sessions를 제거하고 refresh_tokens.family_id·폐기·사용·만료 및 ACTIVE 회원을 검사한다. MR16 토큰(sub/sid/iat/exp)에 맞춰 iss/aud 생략을 허용하고, 제공된 경우에는 설정과 일치해야 한다. 필수 최신 동의·성인 필수 프로필·매칭 조건·최근 설문/성공 분석·현 설정과 일치하는 소유 기기 ACK를 DB에서 검사한다. 실제 채팅방 연결 전이므로 상호 수락은 ACCEPTED까지, chatAvailable=false/chatRoomId=null이다. 실제 소셜·키링 송수신·푸시·WS·실기기·배포 완료를 의미하지 않는다. 스키마를 기존 공용 DB에 자동 적용하지 않는다.

## 숫자 ID·실제 ERD 검증

제안 proposalId는 1 이상의 integer(int64) 숫자이며 DB proposal_id와 동일하다. 별도 공개 UUID나 매핑 컬럼을 두지 않는다. UUID 형식인 Idempotency-Key·세션 family ID는 그대로 유지한다.

수락·거절의 Idempotency-Key는 UUID 필수다. 사용자별 최초 실제 선택의 키를 low_request_key/high_request_key에 선택·시각과 원자 저장하고 제안 보관 동안 유지한다. 같은 키를 다른 제안·다른 선택에 재사용하면 409 IDEMPOTENCY_CONFLICT다. 같은 선택의 재시도는 현재 상태를 반환하며 최초 시각·30일 제한을 연장하지 않는다. 이미 선택한 뒤 새 키로 같은 선택을 보내면 상태만 반환하고 새 키는 저장·소비하지 않는다. 사용자 행→쌍→제안 순서로 잠가 다른 제안 간 키 경합도 직렬화한다. 키는 사용자별이므로 다른 사용자와 같아도 된다. 과거 응답 바이트를 재생하는 방식은 아니다.

ProposalIntegrationTest는 test,erd 프로필로 docs/database/schema.sql 전체를 새 Testcontainers PostgreSQL17에 실행하고 ddl-auto=validate로 검사한다. user_pairs·match_proposals·profiles는 최신 모델을 사용한다. 새 선택 키2컬럼은 공용 DB에 아직 적용하지 않았다. 앱 최신 develop front 소스에는 제안 UUID 연결이 없었다.
