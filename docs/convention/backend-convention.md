# 백엔드 컨벤션

v0.2.1-review, 2026.10.21 (BE 두 명이 합의하며 채운다)

Spring Boot 서버(`back/`)를 함께 개발하는 **공통 규칙**이다. 인증·토큰·푸시·채팅·키링처럼 기능별 흐름은 여기 적지 않고 기능 명세(`docs/contracts/`)에 두고 링크한다. 각 규칙에는 `[rule-id]` 를 붙여서 MR 리뷰에서 링크로 가리킬 수 있게 한다. 저장소·커밋·MR 규칙은 [GitLab 컨벤션](gitlab-convention.md), 코드 스타일은 [네이버 Java 컨벤션](naver-java-convention.md)을 따른다.

전송 필드·필수값·nullable·HTTP 상태·오류 코드의 기준은 `docs/contracts/api/openapi.json`과 API README다(MR !20). 해당 문서가 아직 병합되지 않았다면 MR !20의 최신 소스 브랜치를 참조한다. 이 컨벤션은 공통 구현 원칙을 설명하며 상세 계약의 미정 항목을 확정하지 않는다.

## 상태 표시

| 표시 | 뜻 |
| --- | --- |
| **[확정]** | BE 두 명이 합의했다. 바꾸려면 이 문서를 고치는 MR로 다시 합의한다 |
| **[제안]** | 초기 구성·로그인 작업에서 이미 이렇게 구현했다. 합의되면 [확정], 바뀌면 코드도 고친다 |
| **[합의 필요]** | 아직 정하지 않았다. 관련 기능 개발 전에 정한다 |

항목을 확정할 때는 표시를 바꾸고 결정 이유를 한 줄 남긴다. 이 문서 자체도 MR 리뷰로 합의한다.

## 1. 개발 환경

### 버전
_[env-versions]_ **[제안]**

| 항목 | 버전 | 비고 |
| --- | --- | --- |
| Java | 21 (LTS) | `build.gradle` 의 toolchain 으로 고정 |
| Spring Boot | 4.1.1 | `build.gradle` 에 패치 버전까지 고정. 3.x 대비 Jackson 3(`tools.jackson`), 테스트 패키지 경로가 다르다 |
| Gradle | Wrapper 로 고정 | 직접 설치하지 않고 `./gradlew` 를 쓴다 |
| PostgreSQL | 17 | `compose.yaml`, Testcontainers 모두 같은 버전 |

- 버전은 "Initializr 기본값"이 아니라 **두 사람 PC에서 `./gradlew build` 가 성공한 것을 확인하고** 확정한다.
- JUnit·Mockito·Jackson 등 Spring Boot가 관리하는 의존성은 특별한 이유 없이 버전을 따로 적지 않는다.

합의할 것: 두 사람 PC 모두 JDK 21로 빌드가 되는지 확인.

### 주요 라이브러리
_[env-libraries]_ **[제안]**

Web MVC, Validation, Data JPA, Security + OAuth2 Resource Server(JWT), Lombok, Docker Compose 지원, Testcontainers.
새 라이브러리를 추가할 때는 MR 본문에 이유를 적는다. **[합의 필요]**

### 로컬 실행과 빌드
_[env-local-run]_ **[제안]**

Java 21과 Docker Desktop이 필요하다. `./gradlew bootRun` 이 `compose.yaml` 의 PostgreSQL을 자동으로 띄운다. `./gradlew build` 는 컴파일, 테스트, Checkstyle을 모두 실행한다.

## 2. 프로젝트 구조

### 기본 패키지와 도메인별 구조
_[pkg-by-domain]_ **[제안]**

기본 패키지는 `com.e106.tingle` 이다. 계층(`controller`, `service`)이 아니라 **도메인별**로 나눈다. 한 기능을 고칠 때 한 폴더 안에서 끝나게 하기 위해서다.

```
com.e106.tingle
├─ auth/          로그인, 토큰, 보안 설정
├─ user/          회원
├─ agreement/     약관
└─ global/        도메인과 무관한 공통 코드
   └─ error/      ErrorCode, BusinessException, 전역 예외 처리
```

합의할 것: 도메인 안에서 다시 `controller/service/repository` 하위 패키지로 나눌지. 지금은 나누지 않고 한 폴더에 둔다.

### 계층 역할
_[layer-roles]_ **[제안]**

| 계층 | 하는 일 | 하지 않는 일 |
| --- | --- | --- |
| Controller | 요청 받기, `@Valid` 검증, 서비스 호출, 응답 반환 | 비즈니스 판단, 트랜잭션 |
| Service | 비즈니스 규칙, `@Transactional` | HTTP 요청·응답 객체 다루기 |
| Repository | DB 조회·저장 | 비즈니스 판단 |

### DTO
_[dto-record]_ **[제안]**

요청·응답 DTO는 `record` 로 만들어 **도메인 폴더 아래 `dto/` 패키지**에 둔다(`user/dto/SignupRequest`). 엔티티를 응답으로 직접 반환하지 않는다. 서비스 안에서만 주고받는 값(`TokenService.IssuedTokens`)은 API DTO가 아니므로 그 클래스 안에 둔다.

### 공통 코드 위치
_[pkg-global]_ **[제안]**

두 개 이상의 도메인이 쓰는 코드만 `global/` 에 둔다. 한 도메인에서만 쓰면 그 도메인 폴더에 둔다. 나중에 쓸 것 같다는 이유로 `BaseService`·`BaseController` 같은 공통 추상화를 미리 만들지 않는다.

## 3. API 공통 규칙

### 앱 전용 API 서버
_[api-app-only]_ **[확정]** (2026.10.20)

서버는 Flutter 앱만을 위한 JSON API다. 웹 화면을 위한 기능은 두지 않는다.

- 응답은 성공·오류 모두 JSON. 요청의 `Accept` 가 `text/html` 이어도 오류는 JSON으로 응답한다.
- 정적 파일(HTML·이미지)을 내보내지 않는다(`spring.web.resources.add-mappings: false`). HTML 오류 화면 대신 `/error` 도 JSON으로 응답한다(`JsonErrorController`).
- 세션·쿠키를 쓰지 않는다(토큰 인증). 그래서 CSRF 방어도 끈다.
- 네이티브 앱 연동에 CORS 허용 설정은 필요하지 않다. CORS는 브라우저 응답 읽기 정책이며 인증·인가를 대체하지 않는다. 웹 클라이언트를 도입하면 허용 출처를 별도 합의한다.

### 경로와 버전
_[api-path]_ **[제안]**

`/api/v1/{복수형 자원}` 형식을 쓴다(`/api/v1/users`, `/api/v1/agreements`). 인증 관련만 `/api/v1/auth/...`. 단어 구분은 `-` (`nickname-availability`). 호환되지 않게 바뀌면 `v2` 를 쓴다.

### 응답 형식
_[api-response]_ **[제안]**

- 성공: 본문을 감싸지 않고 그대로 보낸다. `{"userId": 1024, ...}`
- 오류: 항상 `{"code": "NICKNAME_DUPLICATED", "message": "..."}`. 앱은 `code` 로 분기하고 `message` 는 화면에 그대로 보여 줄 수 있는 문장이다. `message` 는 요청의 `Accept-Language` 언어로, 없으면 한국어로 보낸다.
- JSON 필드는 camelCase. 필수이면서 nullable인 필드는 값이 없어도 null로 포함한다(예: imageUrl, chatRoomId). 선택 필드 생략 여부는 각 DTO의 OpenAPI 정의를 따른다.
- 정의되지 않은 JSON 요청 필드는 400 INVALID_REQUEST로 거부한다. 공통 Jackson3 역직렬화 설정을 적용하며 자유 형식 객체는 상세 스키마로 추가 검증한다.

성공 응답은 감싸지 않고 목록은 items 객체를 사용한다. 응답 모양 변경은 FE와 계약을 함께 갱신한다.

### HTTP 상태 코드
_[api-status]_ **[제안]**

| 상태 | 쓰는 경우 |
| --- | --- |
| 200 / 201 / 202 / 204 | 성공·생성·비동기 접수·본문 없음. 경로별 OpenAPI 기준 |
| 400 | 요청 형식·검증 실패 |
| 401 | 인증 없음·만료 |
| 403 | 인증은 됐지만 허용되지 않음 (`ONBOARDING_REQUIRED`, `UNDER_AGE`) |
| 404 | 대상 없음 |
| 409 | 현재 상태와 충돌 (중복, 이미 처리됨) |
| 410 | 제안 만료·종료된 본인 제안의 프로필 접근 종료 |
| 422 | 합의 전 정책 적용 거부(POLICY_UNRESOLVED) |
| 413 / 415 | 본문·파일 크기 초과 / 지원하지 않는 형식 |
| 429 | 요청 횟수 제한 |
| 500 | 예상하지 못한 내부 오류 |
| 502 | 외부 서비스(소셜, GMS) 오류 |
| 504 | 외부 서비스 응답 시간 초과 |

오류를 200 + 오류 `code` 로 보내지 않는다.

### PATCH 의미
_[api-patch]_ **[제안]**

| 요청 본문 | 의미 |
| --- | --- |
| 필드 생략 | 기존 값 유지 |
| 명시적 `null` | 값 삭제 (삭제할 수 있는 필드만. 필수 필드의 `null` 은 400) |
| 값 | 그 값으로 변경 |

일반 `record` 필드는 "생략"과 "`null`"을 구분하지 못한다. 수정 API를 만들 때 두 경우를 구분하는 방법(예: `JsonNullable`, `Map` 으로 받기)을 함께 정하고, 빈 문자열·빈 배열의 의미도 필드별로 명세에 적는다.

### 요청값 검증과 예외 처리
_[api-validation]_ **[제안]**

- 형식 검증은 DTO의 Bean Validation(`@NotBlank` 등) + Controller의 `@Valid`.
- 업무 규칙 위반은 서비스에서 `BusinessException(ErrorCode.XXX)` 를 던진다.
- 사용자에게 보이는 문구(오류 문구, 약관 제목 등)는 코드·설정에 직접 쓰지 않고 `resources/messages.properties` 에 키로 둔다(오류 `error.{오류 코드}`, 약관 제목 `agreement.{type}.title`, 읽기는 `Messages.get`)(기능 명세 §1.1 I18N01·07). 다른 언어는 `messages_{언어}.properties` 로 추가하고, 없으면 기본 파일(한국어)을 쓴다. `ErrorCode` 를 추가하면 문구도 함께 추가한다(`ErrorMessagesTest` 가 확인).
- 오류 응답은 처리되는 곳이 달라도 **같은 JSON 형식**(`ErrorResponse`)으로 보낸다.

  | 오류가 난 곳 | 변환하는 곳 |
  | --- | --- |
  | Controller·Service (MVC) | `GlobalExceptionHandler` |
  | Security 필터 (인증·인가 실패) | `SecurityConfig` 의 `AuthenticationEntryPoint`·`AccessDeniedHandler` |
  | 그 밖의 서블릿 오류 (`/error`) | `JsonErrorController` |

- 예상하지 못한 예외는 500 `INTERNAL_ERROR` 로 응답하고 예외 이름·SQL·토큰 같은 내부 정보는 숨긴다.
- `@Valid` 는 형식만 본다. 본인 자원인지, 기한이 지났는지 같은 업무·권한 검사는 서비스에서 한다.

### 오류 코드 관리
_[api-error-code]_ **[합의 필요]**

`global/error/ErrorCode` 한 파일에 도메인별로 모은다. 실제 충돌이 잦아지면 분리를 검토한다. 오류 코드 이름을 바꾸면 `docs/contracts` 명세도 함께 고친다.

### 날짜·시간
_[api-time]_ **[제안]**

- 코드: `Instant` 를 쓰고, 현재 시각은 주입받은 `Clock` 에서 얻는다(테스트에서 시간을 움직이기 위해). `LocalDateTime.now()` 를 직접 부르지 않는다.
- 기준 시간대: `Asia/Seoul`.
- API 형식: ISO-8601 문자열  — UTC(`2026-10-20T01:30:00Z`)

### 목록 조회와 페이지네이션
_[api-pagination]_ **[합의 필요]**

offset 방식과 cursor 방식 중 고르고 응답 모양을 정한다. 채팅 메시지처럼 계속 쌓이는 목록은 cursor가 유리하다. 제안 목록·채팅 기능 전에 정한다.

### 외부에 노출하는 ID
_[api-public-id]_ **[합의 필요]**

사용자 결정(2026-10-21)에 따라 매칭 제안은 proposalId 숫자(int64, 양수) 하나를 DB·API에서 사용한다. 상대 회원번호는 이 예외에 포함하지 않는다. 그 외 내부 bigint를 외부 응답으로 자동 노출하지 않는다. 공개 UUID 계약과 내부 ID의 매핑·형식은 호출자와 확인한다. 상대의 내부 회원번호는 공개 프로필에서 제외한다.

### API 문서화
_[api-docs]_ **[합의 필요]**

파트 간 계약의 공식 문서는 `docs/contracts/api/` 이다(GitLab 컨벤션 `[shared-contracts]`). Swagger(springdoc)를 추가로 쓸지, 쓴다면 계약 문서와 어떻게 맞출지 정한다.

## 4. 인증 연동

### 토큰 전달
_[auth-header]_ **[제안]**

`Authorization: Bearer {accessToken}`. 회원 식별은 검증된 JWT sub를 사용한다. sid·세션 모델과 로그아웃 즉시 access 폐기 방식·범위는 인증 계약 확인 사항이다. 최신 통합 계약의 `auth-recheck.md`를 기준으로 합의하고, 미정이라는 이유로 현재 폐기 검사를 임의로 제거하지 않는다. 로그인 구현의 refresh family 기반 sid는 별도 인증 검토 대상으로 구분한다. (현재 구현: `sid` = `refresh_tokens.family_id`, 요청마다 세션·회원 상태 확인. 확정 여부는 AUTH-OPEN-03)

### 로그인한 사용자 ID 꺼내기
_[auth-current-user]_ **[제안]**

지금은 Controller에서 `@AuthenticationPrincipal Jwt jwt` 를 받아 `Long.parseLong(jwt.getSubject())` 로 꺼낸다. 타입은 `long`.

합의할 것: `@LoginUserId long userId` 같은 공통 파라미터를 만들어 반복을 줄일지.

### 가입 미완료 사용자의 접근 범위
_[auth-onboarding-step]_ **[제안]**

가입 단계는 AGREEMENT_REQUIRED → KEYRING_REQUIRED → PROFILE_REQUIRED → SURVEY_REQUIRED → PREFERENCES_REQUIRED → SYNC_PENDING → READY다. 서버가 단계별 API 허용과 소유권을 확인하고 단계 계산은 서비스 한 곳(`OnboardingService`)에 둔다. /api/v1/auth/** 전체를 가입 제한의 예외로 두지 않는다. 약관 동의·프로필 저장 분리 경로/DTO 및 허용 단계는 인증 상세 계약에서 확인한다. (현재 구현: 가입 중에도 쓸 API는 `@RequiresOnboarding(가장 이른 단계)`, 붙이지 않은 `/api/**` 는 `READY` 필요, 어기면 `403 ONBOARDING_REQUIRED`)

### 인증 없이 여는 API
_[auth-public-api]_ **[제안]**

공개 API는 인증 상세 계약의 경로·메서드 허용 목록을 따른다. 로그아웃의 토큰 전달·즉시 폐기 범위 등 미정 항목을 이 공통 문서에서 확정하지 않는다. 나머지 API는 인증과 현재 권한을 검사한다. (현재 구현: 허용 목록은 `SecurityConfig.isPublic` 한 곳)

### 로컬·테스트에서 인증 사용자 준비
_[auth-test-user]_ **[제안]**

- 통합 테스트: `SocialVerifier` 를 `@MockitoBean` 으로 대체하고 실제 로그인 API를 호출해 토큰을 받는다(`AuthIntegrationTest` 참고).
- 로컬 수동 테스트: 실제 소셜 토큰으로 로그인한다.

합의할 것: 로컬 개발용으로 소셜 없이 토큰을 받는 방법(예: `local` 프로필에서만 켜지는 개발용 로그인)이 필요한지. 만든다면 배포 환경에서 절대 켜지지 않게 한다.

### 키링·채팅의 인증
_[auth-device-ws]_ **[합의 필요]**

키링(기기)이 보내는 요청의 인증 방식과 WebSocket(채팅) 연결 시 토큰 전달 방식은 이 문서가 아니라 각 기능 명세에서 정한다(채팅: 채팅 API 명세, 키링: IoT 담당과 `docs/contracts/keyring/`). 푸시 토큰도 해당 기능을 만들 때 기능 명세에서 정한다. 로그인 세션·로그아웃의 미확정 범위는 인증 명세와 `auth-recheck.md`에서 확인한다.

## 5. DB와 실행 설정

### DB와 로컬 실행
_[db-local]_ **[제안]**

PostgreSQL 17. 로컬은 `compose.yaml` 이 자동으로 띄우고(local 프로필), 테스트는 Testcontainers가 띄운다(`[test-db-lifecycle]`). 로컬에서도 H2 같은 다른 DB를 쓰지 않는다(쿼리·제약 동작이 달라 서버에서만 나는 오류를 막기 위해). Docker Desktop이 꺼져 있으면 테스트가 실패한다.

### 명명
_[db-naming]_ **[제안]**

- 테이블: 복수형 snake_case (`app_users`, `refresh_tokens`). **테이블·컬럼 이름은 팀 ERD를 따른다.**
- 컬럼: snake_case (`signup_completed_at`). JPA 엔티티 필드는 camelCase로 쓰고 자동 변환에 맡긴다.
- 제약·인덱스: `uq_테이블_내용`, `ix_테이블_내용`
- 시각 컬럼: `~_at`

### 타입
_[db-types]_ **[제안]**

| 용도 | DB | Java |
| --- | --- | --- |
| PK | `BIGSERIAL` | `Long` (`GenerationType.IDENTITY`) |
| 시각 | `TIMESTAMPTZ` | `Instant` |
| enum | `VARCHAR` | `@Enumerated(EnumType.STRING)`. 순서값(ORDINAL) 금지 |

### 공통 생성·수정 시각
_[db-audit]_ **[합의 필요]**

지금은 공통 처리 없이 필요한 테이블에만 `created_at` 을 둔다. 모든 엔티티에 `created_at`·`updated_at` 을 자동으로 넣는 `BaseTimeEntity`(JPA Auditing)를 쓸지 정한다. 쓴다면 시각을 `Clock` 에서 받게 해서 테스트와 맞춘다.

### 엔티티 설계
_[db-entity]_ **[제안]**

- 기본 생성자는 `protected`, 값 변경은 의미 있는 메서드로(`completeSignup`). `@Setter`·`@Data` 는 쓰지 않는다.
- 다른 엔티티는 객체 연관관계(`@ManyToOne`) 대신 **ID 값으로** 참조한다(`userId`). 지연 로딩·N+1 문제를 피하고 도메인 간 의존을 줄이기 위해서다.
- 현재 ERD는 물리 외래 키를 두지 않는다. 그래서 연관관계 없이 ID 필드만 두고, 참조 대상의 존재·소유 확인과 삭제 정리는 서비스 코드가 한다.

### 스키마 관리
_[db-schema]_ **[확정]** (2026.10.20, Flyway 없이 JPA 자동 생성으로 관리. 설계 SQL·계약 이력과 실행 마이그레이션은 구분)

테이블은 JPA가 엔티티를 보고 만든다. local은 `ddl-auto: update`, test는 `create-drop`, 공통 기본값은 `validate`이며 dev/prod는 인프라 준비 후 확정(`[env-profiles]`). Flyway 실행용 SQL 마이그레이션은 두지 않는다. ERD 설명·가져오기 SQL·계약 변경 기록은 설계 문서로 보존하며 자동 실행하지 않는다.

- **제약·인덱스는 엔티티에 선언한다.** 유니크는 `@Table(uniqueConstraints = ...)` 나 `@Column(unique = true)`, 인덱스는 `@Table(indexes = ...)`, 길이·필수는 `@Column(length, nullable)`.
- **대소문자를 무시한 유니크**처럼 어노테이션으로 표현할 수 없는 제약은 비교용 컬럼을 따로 둔다(예: `nickname` 과 소문자로 바꾼 `nickname_key` 에 유니크).
- **DB 기본값(`DEFAULT now()`)에 기대지 않는다.** 생성 시각 같은 값은 코드에서 넣는다.
- **enum 컬럼에 CHECK 제약을 만들지 않는다.** `update` 는 CHECK를 고치지 않아 선택지를 추가하면 기존 DB에 새 값을 저장할 수 없기 때문이다. 방언 `NoColumnCheckPostgreSqlDialect` 로 끄고, 값 검증은 서버 코드가 한다.
- 외래 키는 ERD에 정의된 관계에만 만든다(`[db-entity]`). 그 밖의 참조 무결성은 서비스 코드가 지킨다.

알고 쓸 한계:
- `update` 는 테이블·컬럼을 **추가만** 한다. 컬럼 이름 변경·삭제, 타입 변경, 기존 데이터 이관은 하지 않는다. 엔티티에서 필드를 지워도 DB 컬럼은 남는다.
- 그래서 공유 DB(개발·시연 서버)에서 컬럼 이름을 바꾸거나 지울 때는 **직접 확인하고 팀원에게 공유한 뒤** DB에 반영한다. MR 본문에도 무엇을 바꿨는지 적는다.
- 로컬 DB가 엔티티와 어긋나면 초기화한다(`docker compose down -v`, 로컬 데이터 삭제).

### 트랜잭션과 동시성
_[db-transaction]_ **[제안]**

- `@Transactional` 은 서비스 메서드에 둔다. 조회만 하면 `readOnly = true`.
- 소셜 로그인·GMS 같은 **외부 API 호출은 트랜잭션 밖에서** 한다(외부 응답을 기다리는 동안 DB 연결을 잡지 않기 위해).
- 동시 요청은 상황별로 아래처럼 막는다.

  | 상황 | 방법 |
  | --- | --- |
  | 닉네임·기기 소유처럼 겹치면 안 되는 값 | DB UNIQUE 제약 + 충돌 시 오류 변환 |
  | 프로필·설정을 오래된 값으로 덮어쓰기 | `@Version`(낙관적 락) 또는 버전 비교 |
  | 제안 수락·거절처럼 같은 관계를 동시에 바꾸기 | 행 잠금(`PESSIMISTIC_WRITE`). 두 사용자를 잠글 때는 ID 작은 쪽부터 |
  | 같은 요청·이벤트 재전송 | 업무상 고유키로 중복 처리 |

  지금은 가입 완료와 토큰 재발급에서 회원 행을 잠근다.
- "조회해서 없으면 insert"만으로 중복을 막았다고 보지 않는다. UNIQUE 제약이 최종 방어선이다.
- 외부 호출을 기다리는 동안 잠금을 잡고 있지 않는다.

### 개발용 데이터
_[db-seed]_ **[합의 필요]**

시연용 가상 프로필(약 10개)을 어떻게 넣을지 정한다. 배포 DB에는 들어가지 않도록 `local` 프로필 전용 초기화 코드나 스크립트로 분리한다.

### 환경변수와 .env
_[env-vars]_ **[제안]**

- 실제 값은 `back/.env` 에만 두고 커밋하지 않는다. 변수 이름은 `back/.env.example` 에 추가한다.
- 값이 없는 변수는 주석으로 둔다. 빈 값(`KEY=`)은 기본값을 덮어쓴다.
- `JWT_SECRET` 은 환경(로컬·개발 서버·시연 서버)마다 다른 값을 쓴다.
- `back/.env` 는 `spring.config.import: optional:file:.env[.properties]` 로 **Spring이 직접 읽는다**(2026.10.20 이 방식 유지로 확정). 같은 이름의 OS 환경변수가 있으면 환경변수가 우선한다. `.env` 는 `back/` 에서 실행할 때 기준이다.

### 실행 프로필
_[env-profiles]_ **[확정]** (2026.10.20, 공통·local·test만 먼저 나눔. dev/prod는 인프라 준비 후 정함)

| 파일 | 프로필 | 내용 |
| --- | --- | --- |
| `application.yml` | 공통 | 모든 환경에 같은 설정. `ddl-auto: validate`(테이블을 바꾸지 않고 검사만), Docker Compose 꺼짐 |
| `application-local.yml` | local | `ddl-auto: update`, Docker Compose로 PostgreSQL 자동 실행 |
| `src/test/resources/application-test.yml` | test | Testcontainers 테스트 전용 DB에 `create-drop` |

- 프로필을 지정하지 않으면 local 로 뜬다(`spring.profiles.default: local`). 그래서 `./gradlew bootRun` 과 IDE 실행은 따로 설정할 필요가 없다.
- 테스트는 `src/test/resources/config/application.properties` 에서 test 프로필을 켠다.
- dev/prod 프로필을 만들 때는 서버 실행 시 프로필을 반드시 지정한다(지정하지 않으면 local 로 떠서 `update` 가 켜진다).

## 6. 검증과 합류 기준

### 테스트
_[test-tools]_ **[제안]**

- 도구: JUnit 5, Spring Boot Test, MockMvc, Testcontainers(PostgreSQL 17), Mockito.
- 외부 서비스(소셜, GMS)는 테스트에서 대체한다. 실제 호출은 수동 테스트로 확인한다.
- 테스트 메서드 이름은 영어 camelCase(언더스코어 허용), 한글 설명은 `@DisplayName` 에 쓴다(네이버 컨벤션 `[method-lower-camelcase]`).

### 테스트 DB와 데이터 격리
_[test-db-lifecycle]_ **[제안]**

- DB 컨테이너는 **테스트마다 새로 뜨지 않는다.** `TestcontainersConfiguration` 의 컨테이너는 Spring 테스트 컨텍스트와 함께 살아서, 설정이 같아 컨텍스트가 재사용되는 테스트 클래스들이 한 DB를 함께 쓴다.
- 그래서 데이터 격리는 따로 챙긴다. 실제 HTTP 호출·별도 스레드·동시성 테스트는 트랜잭션 롤백이 적용되지 않으므로 테스트마다 다른 데이터(다른 소셜 ID·닉네임)를 쓴다.
- DB를 같이 쓰는 테스트는 병렬로 돌리지 않는다.
- 결과를 보고할 때 단위 테스트, DB 통합 테스트, 실제 소셜 연동 확인을 구분한다.

합의할 것: 기능마다 필수로 쓸 테스트 범위(예: API마다 정상 1개 + 오류 코드마다 1개).

### Checkstyle
_[test-checkstyle]_ **[제안]**

네이버 규칙 파일(`back/config/checkstyle/`)을 수정 없이 쓰고, **경고가 1건이라도 있으면 빌드가 실패한다.** 규칙에서 빼야 할 파일이 생기면 `naver-checkstyle-suppressions.xml` 에 이유와 함께 추가한다.

### 공통 파일 수정
_[merge-shared-files]_ **[합의 필요]**

`build.gradle`, `SecurityConfig`, `ErrorCode`, `application.yml`, 엔티티(테이블 구조)처럼 두 사람이 함께 쓰는 파일을 고칠 때 미리 알릴지, 별도 MR로 먼저 올릴지 정한다.

### 초기 구성의 합류
_[merge-initial-setup]_ **[제안]**

Spring Boot 프로젝트 초기 구성 MR(이 문서 포함)을 먼저 develop에 병합하고, 그 위에서 각자 기능 브랜치를 딴다. 이 문서의 [합의 필요] 항목 중 초기 구성에 영향을 주는 것(외부 ID, 마이그레이션 버전, 오류 코드 관리)은 이 MR 리뷰 중에 정한다.

## 7. 결정 기록

### 결정 기록(ADR) 폴더
_[doc-adr]_ **[합의 필요]**

이 문서는 **지금 지켜야 할 규칙**만 짧게 두고, 결정마다의 **배경·대안·이유**는 ADR(Architecture Decision Record) 파일로 따로 남기는 것을 제안한다.

| | 이 문서 | ADR |
| --- | --- | --- |
| 담는 것 | 현재 규칙 (무엇을) | 결정 하나의 맥락·대안·이유 (왜) |
| 형태 | 한 파일, 최신 상태로 계속 고친다 | 결정마다 파일 하나. 확정 후 고치지 않고, 바뀌면 새 ADR로 대체한다 |

```
docs/
├─ adr/
│  ├─ README.md                               ADR 목록·상태, 작성 방법
│  ├─ template.md                             맥락 / 결정 / 대안 / 결과
│  ├─ 2026-10-20-public-user-id.md
│  └─ 2026-10-20-schema-management.md
└─ convention/
   └─ backend-convention.md                   결론 한 줄 + ADR 링크
```

- 파일명은 `yyyy-MM-dd-주제.md` 로 한다. 순번을 쓰지 않아 두 사람이 동시에 만들어도 충돌하지 않는다. ADR은 실행 순서가 없으므로 순서 문제도 없다.
- 상태는 `제안 → 승인 → 대체됨(새 ADR 링크)` 으로 바꿔 가며 남긴다.
- 이 문서의 [합의 필요] 항목은 ADR 초안으로 옮겨 논의하고, 승인되면 이 문서에는 결론과 링크만 남긴다.
- 이유가 중요한 [제안] 항목(도메인별 패키지, ID 참조, 비관적 락, Java 21·Spring Boot 4)도 "이미 내린 결정"으로 ADR에 남긴다.
- `docs/adr/` 는 GitLab 컨벤션에 없는 새 폴더이므로 팀장에게 공유하고, 다른 파트도 쓸지 함께 정한다.

## 8. 그 밖에 정할 것

| 항목 | 정할 시점 |
| --- | --- |
| 만료 처리 스케줄러 위치, 서버 여러 대일 때 중복 실행 방지 `[ops-scheduler]` | 제안 만료 기능 전 |
| 외부 API 호출의 타임아웃·재시도 `[ops-external-call]` (소셜은 3초 타임아웃, 재시도 없음) | GMS 연동 전 |
| 로그 규칙 `[ops-logging]`: 토큰·소셜 ID·개인정보·원응답·메시지 내용을 로그에 남기지 않기, 요청 추적 ID | 기능 개발 시 적용 |
| 헬스체크·배포 `[ops-deploy]`: Actuator 사용 여부, Dockerfile, Jenkins 실행 명령 | 배포 전 |
| 회원 탈퇴와 데이터 보관 `[ops-withdrawal]`: 삭제 범위, 소셜 연결 끊기, 보관 기간 | 실사용자 배포 전 |
