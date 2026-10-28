# 공통 저장 이관 · 2026-10-28

승인된 `worktrees/api-common-mapping-151`과 기존 `be-feature/api-path-userid-S15P31E106-151`에서 진행한다. 176·70·원본 checkout은 수정하지 않는다. 공유 DB SQL 적용·push·MR·리뷰어 변경·병합·배포는 없다.

## 검증 단위: 의존 통합과 리프레시 토큰

- `7a27482`는 기존 151 f5ef7f3 이력에 176 5516e54를 병합한 의존 단위다. API README/OpenAPI 충돌은 최신 176 계약으로 해결했다. main/test 소스는 176과 동일함을 확인했고 새 작업폴더에서 기존 MVC9/실제 로그아웃 DB8의 17개, Checkstyle 경고0·bootJar를 확인했다. 공통 매핑 전체 성공으로 계산하지 않는다.
- RefreshToken은 canonical `refresh_token.id`로 바꾸고 기존 회원/UUID family/해시/수명주기 시각을 유지한다. nullable device_name과 last_used_at을 매핑한다. 현재 로그인 요청은 기기 이름을 받지 않으므로 임의 이름을 만들지 않는다. ERD의 명시대로 새 로그인/재발급에는 실제 Clock 시각을 기록하고 정상 회전·응답 유실 재시도에서도 기존 기기 이름과 family를 이어받는다. 기존 탈취 판정·유예 시간·401 커밋·로그아웃 원자 정리는 유지한다.
- [수동 토큰 이관 SQL](../database/refresh-token-151-migration.sql)은 알려진 구 7컬럼 테이블을 제자리에서 rename한다. device_name/last_used_at은 NULL로 추가하며 과거 사용 시각을 현재 시각이나 만료 시각에서 추정하지 않는다. 기존 ID·해시·회원/family·수명주기 시각·identity 고수위와 기존 인덱스를 보존한다. 실제 공유 DB의 구조/자료는 조사·변경하지 않았다.
- 실제 PostgreSQL17에서 토큰/푸시 canonical 매핑과 구 User만을 명시한 별도 slice의 **10개(기존 로그아웃8 + 사용시각/기기 이름2), failure/error/skip0**를 확인했다. 이는 User 새 매핑 전의 분리 검증이다. 기존 User용 bridge를 실제 공통 이관의 근거로 쓰지 않는다.
- 별도 실제 PG migration 회귀 **2개, failure/error/skip0**로 해시 바이트·ID/회원/family·expires/used/revoked 시각·새 metadata NULL·identity ALWAYS 및 MAX보다 큰 기존 sequence 고수위100→101, source/target 동시 존재 사전 차단을 확인했다.
- 최초 추가 테스트의 줄 길이 경고1개를 수정한 뒤 migration 회귀 실행의 Checkstyle main/test 경고0·컴파일·bootJar 성공이다. 기존 테스트의 SQL 참조도 singular 이름으로 맞췄으며 아직 전체 인증/안내 런타임 성공으로 표시하지 않는다.

```text
./gradlew test --tests '*PushLogoutPersistenceTest' checkstyleMain checkstyleTest bootJar --console=plain
./gradlew test --tests '*RefreshTokenLegacyMigrationTest' checkstyleMain checkstyleTest bootJar --console=plain
```

## 검증 단위: 회원·프로필·기존 동의 이력 보존

반영 커밋: `812a343`. 토큰 단위는 `1aaa214`, 의존 통합은 `7a27482`다.

- 사용자 승인으로 users.status(text)/created_at을 보존하고 기존 user_consents 6컬럼을 설계에 유지했다. **22표·161컬럼·36논리관계**이며 물리 FK는 추가하지 않았다. 기존 원본 첨부 hash와 비관련 테이블은 보존하고 승인 보완을 별도 기록했다. 과거 항목별 동의에서 users.agreed_at을 추정하지 않는다. 신규 사용자도 실제 일괄 동의 시각이 확정되기 전에는 NULL이다. 기존 AgreementService의 항목별 버전/필수 동의·AI 철회 예외·PRIVACY 이메일 조건을 권한 근거로 유지한다.
- User는 users.id/provider_user_id와 실제 auth_provider enum, Profile과 공개 프로필 투영은 동일한 profile과 photo_url/active_personality_id 및 실제 프로필 enum으로 이관했다. 기존 상태 ACTIVE/SUSPENDED/WITHDRAWAL_PENDING/WITHDRAWN과 생성/최초 가입·등록 시각을 보존한다. 닉네임 원문을 정규화해서 덮어쓰지 않으며 조회·고유성은 lower(nickname)을 사용한다.
- [회원·프로필 수동 이관 SQL](../database/users-profile-151-migration.sql)은 구 canonical의 기존 행·ID·identity 고수위·상태/시각·동의 이력을 제자리에서 보존한다. 두 표 공존, 길이/enum/닉네임 충돌·사진 정책 위반은 사전 차단하고 나중 enum 변환 실패도 전체 DDL 롤백한다. 실제 사진이 있으면 검증한 저장소 prefix 설정이 필요하며 임의로 주소를 신뢰하지 않는다. 구 탈퇴 예정/탈퇴 시각과 nickname_key 같은 extra 자료는 삭제하지 않는다. 이 SQL은 실제 공유 DB에 적용하지 않았다.
- 전용 실제 PostgreSQL/JPA 회원·프로필 5개와 구 자료 이관 5개가 통과했다. 새 canonical22만 사용하는 실제 TokenService/푸시 10개도 통과했고, 임시 app_users bridge는 제거했다. named enum·lower 별명 동시 충돌·원본 상태/시각·동의 이력·기본 비운영자/NULL 일괄 동의 및 identity 고수위를 확인했다.
- **schema 전제:** JPA create/update만으로 lower(nickname) 표현식 unique 인덱스가 생성된다고 보장하지 않는다. 새 DB는 canonical DDL, 기존 DB는 검토한 수동 이관 SQL의 인덱스를 적용해야 한다. 검증한 canonical/수동 이관 DB에서는 이 인덱스가 실제 경합을 차단했다. 공유 DB 반영 전 이 전제를 확인해야 한다.

## 검증 단위: 실제 단계 이관 DB의 인증·안내 연동

- MemberAccess의 회원 잠금/상태·프로필·준비 조회를 새 공통 테이블로 맞췄다. 준비 증거는 현재 프로필의 active_personality_id → 본인 SUCCEEDED personality → 본인 완료 survey, 명시적인 희망 나이 선택과 user_setting, 본인 소유 REGISTERED keyring의 현재 설정 버전 일치다. 저장 성공이나 설문 제출을 분석 성공·기기 ACK로 추정하지 않는다.
- 단계 이관 검증 DB는 canonical22와 알려진 구 DDL `0255529`의 **기존 B 테이블 23개만** 함께 둔다. app_users/profiles/refresh_tokens 가짜 별칭이나 구 공통 행은 추가하지 않는다. 기존 B 도메인을 삭제하거나 전체 B 저장·정책을 대신 이관하지 않았다. `AuthStagedMigrationIntegrationTest`·`ChatNoticeStagedMigrationIntegrationTest`는 이 상태를 명시한 전체 애플리케이션 컨텍스트다.
- 테스트의 기존 B 준비 seed와 canonical 준비 seed는 숫자 ID가 같다고 가정하지 않고 명시한 fixture 식별값으로 연결한다. 성공 분석/현재 설정과 기기 ACK는 각각 별도 helper에서 명시적으로 기록한다. 이는 합성 분석/기기 증거이며 실제 분석 공급자·기기 검증이 아니다.
- 실제 HTTP/JWT/JPA/트랜잭션의 인증 **27개**와 안내 **18개**가 모두 통과했다. 소셜 공급자·FCM gateway·시계는 합성 대역이다. 합동 실행의 총 **68개(위45 + 회원5 + 보존 이관5 + canonical 로그아웃10 + 설계3)**는 failure/error/skip0이다. 최초 안내 실행의 구 테스트 SQL app_users 참조와 Checkstyle 줄 길이 경고는 수정했으며 실패 이력을 통과로 계산하지 않는다.
- canonical22만 둔 대표 인증 1개는 새 공통 매핑 이후 `Schema validation: missing table [background_jobs]`로 실패했다. 구 공통 app_users 차단은 넘어갔지만 미이관 B 도메인 전체를 canonical22로 전환한 것은 아니다. 전체 `build`를 성공했다고 표시하지 않는다. 선택한 단계 이관 전체 컨텍스트 통과와 이 실패를 구분한다.
- 추가 선택 **40개(준비 권한 부정8 + 가입/기존 접근19 + 명시 DDL 필드2 + MVC9 + 토큰 이관2)**도 failure/error/skip0이다. 최종 Checkstyle main/test 경고0·컴파일·bootJar·diff 검사·22/161/36/API67 정적 대조를 통과했다. 68개와 40개는 서로 다른 선택 실행이고 전체 suite/build 통과와 다르다. 실행별 요약은 [검증 집계](common-storage-151-tests.json)에 보존한다.
- BackendApplicationTests의 순수 JPA create-drop 최초 실행은 named enum 타입을 먼저 만들지 못해 users/profile/push 표가 생성되지 않는 실제 실패를 확인했다. 기존 타입 검사를 조용히 성공 처리하지 않고 명시 canonical 공통 DDL/보존 B 컨텍스트 검사로 이름과 프로필을 바꿨다. 순수 자동 DDL의 named enum/표현식 인덱스 bootstrap 한계는 검증 집계와 back/README에 보존했다.

```text
./gradlew test --tests '*AuthStagedMigrationIntegrationTest' --tests '*ChatNoticeStagedMigrationIntegrationTest' --tests '*CommonUserProfilePersistenceTest' --tests '*CommonLegacyMigrationTest' --tests '*PushLogoutPersistenceTest' --tests '*PushBindingSchemaTest' --fail-fast checkstyleMain checkstyleTest bootJar --console=plain
./gradlew test --tests '*AuthIntegrationTest.firstLoginStartsAtAgreementStep' --console=plain
```

## 후속: 가입 단계의 실제 설정·기기 확인 버전

- 사용자 확정: **하나라도 최신이면 준비 완료·가장 높은 확인 버전 표시 — 현재 판정 유지**. auth.md의 복수 기기 미정 항목과 OpenAPI의 해당 두 설명/예시만 좁게 갱신했다. 다른 API·필드 구조는 보존했다.
- requiredSettingsVersion은 실제 user_setting.settings_version이며 행 유실·음수이면 기존 계약의 안전값 0이다. acknowledgedSettingsVersion은 본인 소유 REGISTERED 기기의 확인된 양수 버전 중 최댓값이다. 미확인·영/음수·현재 설정보다 큰 무효 값은 제외하고 없으면 NULL이다. 타인·해제 기기·기기 시각에서 추정하지 않고 저장 자료를 자동 복원하지 않는다.
- 두 표시 값은 한 SELECT 스냅샷에서 읽으며 기존 detail 트랜잭션/잠금 순서를 유지한다. 준비 판정은 기존처럼 하나라도 현재 설정을 확인한 기기가 있어야 하며 나머지 가입 조건도 필요하다. 과거 ACK 표시가 최신 설정과 같다는 뜻은 아니다.
- 실제 단계 이관 HTTP 회귀 3개를 추가했다. 복수 기기 2/4·현재 설정7의 표시4/동기화 대기→한 기기7일 때 준비 완료, 타인/UNBOUND/미확인/미래 값 제외, 해제 후 과거 ACK 표시, 설정 행 유실·음수의 0/NULL 및 무INSERT, 영/음수/NULL ACK를 확인했다. 최초 테스트 fixture의 필수 firmware 누락은 수정한 실패 이력이다.
- 최종 별도 선택 **30개(가입 단계22 + 실제 준비 권한8), failure/error/skip0**, Checkstyle main/test 경고0·컴파일·bootJar·diff 검사·22/161/36/API67 대조를 통과했다. 이전 68/40 실행과 겹치는 선택이므로 138개 고유 검증이나 전체 build 성공으로 합산하지 않는다. OpenAPI JSON 비교로 두 버전 설명과 Onboarding 예시 외 다른 계약이 동일함을 확인했다.
- 이번 후속의 스키마 변경·공유 DB SQL 적용·push/MR/CI/병합/실기기/배포는 없다. canonical-only B 미이관 차단과 자동 DDL bootstrap 한계는 앞의 기록을 유지한다.

```text
./gradlew test --tests '*OnboardingStagedMigrationIntegrationTest' --tests '*MemberAccessReadinessPersistenceTest' checkstyleMain checkstyleTest bootJar --console=plain
```

다음 행동 1개: 미이관 B 도메인의 실제 저장/자료 이관을 담당 범위와 연결하여 canonical-only 전체 컨텍스트 차단을 해소한다. 공유 DB 적용·실제 분석/기기/공급자·CI/승인 MR은 별도 검증이 필요하다.
