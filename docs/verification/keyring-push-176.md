# S15P31E106-176 키링·푸시 정합화 기록

## 최신 체크포인트: 4API·로그아웃 저장 이관 로컬 검증 완료

2026-10-28 !92 실행 코드 `35166ea`는 구매 안내·본인 키링 조회·푸시 설치 등록/해제 및 기존 로그아웃 연결 해제를 새 22표·176컬럼·36논리관계에 연결했다. 숫자 keyring.id·현재 owner/REGISTERED·현재 설정 ACK 조회, boundAt 비노출, push 연결 ID/최초 시각·토큰 교체/재연결·현재 family 로그아웃 정리를 보존했다. A151 공통 인증/가입·기존 안내와 같은 전체 앱을 실행했으며 테스트 fixture의 실제 기기/외부 공급자 대체를 운영 증명으로 취급하지 않는다.

- [전체 실행 증거](erd22-storage-175.md): clean build 46 suite/422 tests, failure/error/skip 0. 실제 일회용 PostgreSQL의 순수 JPA 22표 생성/176컬럼·타입/nullable/FK0 대조, 컴파일·Checkstyle main/test·bootJar/jar·check/build 통과. 이후 보존 SQL 후속 PG25개·Checkstyle 통과는 422개와 겹쳐 합산하지 않는다.
- OpenAPI의 해당 4개 operation은 erd22-canonical-176으로 갱신하고 REVIEW_REQUIRED를 유지했다. 이번 문서 단위는 아래 정적 검증과 관련 내부 링크 확인만 수행했으며 이미 통과한 실행 테스트를 새로 실행한 것으로 표시하지 않는다.
- 새 70 코드의 결합 전 !92 범위다. 희망 조건/로그인 초기화의 전체 연결은 !93에서 검증한다. 기존 151/176 작업폴더와 과거 기록은 보존한다.

**별도 잔여:** 등록 challenge67/소유 증명100과 동기화68/69는 현재 4API 구현 완료 범위가 아니다. 사용자는 최종 IoT 등록/ACK 규격의 별도 확정본이 없음을 확인했다. OpenAPI RegisterKeyring(challengeId/deviceSerial/proof), DeviceProof(protocolVersion/base64 payload), SyncAck(settingsVersion/payloadHash/proof)는 외곽 필드만 있다. 실제 키 발급·서버 검증키/기기 대응, 승인 프로토콜·서명/MAC 바이트·인코딩, nonce/회원/serial 및 ACK 소유세대/run/version/hash 결합·기기 저장 완료 의미와 nonce 단회 소비/당시 payload 영속 정의가 필요하다. 67 구 브랜치의 registration_challenges 표를 22표에 그대로 추가하거나 mock/serial만으로 성공 처리하지 않는다.

과거 keyring 원문 serial/owner/ACK 대응과 구 분석/설정/동기화 전체 자료 이관은 확인된 입력 없이는 수행할 수 없다. 해시에서 원문을 복원하거나 과거 ACK를 추정하지 않았다. 공유/운영 DB SQL 적용·실제 기기/FCM·배포·Notion 현재 원문 일치·사람 승인/병합도 미완료다. 이 기록의 로컬 성공과 Jira 전체 완료를 구분한다. 아래는 각 과거 시점의 검증/미완료 이력이다.

기준: develop `d3df02a2bd5221bc3876c97a46b725be83b4c7f8`, 2026-10-28. 승인한 별도 worktree와 `be-refactor/keyring-push-erd21-S15P31E106-176`에서 진행한다. 원본 프로젝트와 다른 작업 브랜치는 변경하지 않는다.

## 현재 체크포인트: A151 의존 통합과 전체 컨텍스트 차단 확인

2026-10-28, 기존 176 HEAD `b9fe979`에 원격 A151 `f7f6d9b748d4e58729bb53dd52ead398179cf7ec`(인증·회원 경로/본인 검사)와 `f5ef7f316b00fdfc3eae15dc7d0db58b10a0f830`(설문 경로/숫자 회차 ID) 변경을 통합했다. 151·70·원본 checkout은 변경하지 않았다. 원격 151 HEAD는 여전히 f5ef7f3이며 User/RefreshToken/Profile/동의의 공통 저장 이관이 제공된 것으로 보지 않는다.

- 충돌은 OpenAPI 13구간이다. 176의 현행 계약을 보존했고 최종 OpenAPI paths/components는 b9fe979와 JSON 구조가 같다. 숫자 설문 ID·현재 경로가 이미 문서에 존재하므로 구 문서의 revision/UUID 표현을 다시 가져오지 않았다. 실행 구현의 남은 구 저장/설문 기능 및 auth 문서 전체가 최신 명세와 일치한다는 보장은 아니다.
- 실제 canonical `schema.sql`/`ddl-auto=validate`/PostgreSQL17로 `AuthIntegrationTest`·`ChatNoticeIntegrationTest`를 선택하여 `--fail-fast` 실행했다. **3개 시도, 2 failure, 0 error, 1 skipped**, `Schema validation: missing table [app_users]`로 시작이 차단됐다. 안내 클래스는 fail-fast로 실행 전 중단됐다. 전체 연동 성공이나 안내 런타임 검증으로 계산하지 않는다.
- 151 코드는 `app_users`/`refresh_tokens`/`profiles` 및 구 회원 준비 JOIN을 유지한다. canonical `users`/`refresh_token`/`user_profile`에 임의로 행·컬럼을 추가하거나 공통 매핑을 대신 구현하지 않았다. 제자리 푸시 이관이 이 공통 매핑 차단을 해결한 것은 아니다.
- 기존 ChatNoticeIntegrationTest의 로그아웃 후 소유 연결 존재 기대값은 현 계약대로 소유 조회 없음·설치 행의 연결 네 칸 NULL로 맞췄다. 이 전체 컨텍스트 테스트의 런타임은 위 이유로 미검증이다.
- 별도 영향 검증의 MVC 구성에는 실제 `UserPathInterceptor`를 명시적으로 포함했다. 기존 푸시/키링 경로가 차단되지 않는지, 타인/잘못된 userId가 가입 검사보다 먼저 403인지, 본인 경로 통과와 새 `/api/auth/logout`의 refresh 증명·호환 UUID 전달을 확인한다. 이 MVC 검증은 서비스/DB/실제 JWT 디코딩을 대체하며, 실제 TokenService DB 회귀와 서로 다른 검증 경계다.
- 별도 영향 검증은 **17개(MVC 9 + 실제 로그아웃 DB 8), failure/error/skip 0**이다. 최초 추가 테스트의 import 순서 경고 1개를 수정했고 최종 Checkstyle main/test 경고 0·컴파일·bootJar 성공이다. `git diff --check` 및 21표/153컬럼/35관계/API67개 정적 대조도 통과했다. 실패한 canonical 검증 결과와 구분한다.

```text
# 실패: 알려진 전체 매핑 차단을 대표 확인, 안내 실행 전 중단
./gradlew test --tests '*AuthIntegrationTest' --tests '*ChatNoticeIntegrationTest' --fail-fast --console=plain
# 별도 영향 검증: 전체 컨텍스트가 아닌 MVC + 실제 로그아웃 전용 DB slice
./gradlew test --tests '*PushLogoutPersistenceTest' --tests '*KeyringPushRouteTest' checkstyleMain checkstyleTest bootJar --console=plain
```

공유 DB 적용·push·MR·CI·병합·배포는 없다. 전체 완료/병합 차단은 유지한다.

다음 행동 1개: A151의 실제 공통 저장 매핑이 제공된 뒤 그 변경을 합친 결과에서 인증/안내 전체 컨텍스트 회귀를 다시 실행한다.

## 이전 체크포인트: 푸시 저장·로그아웃 연결 이관

저장 모델 단위 `ae1e11b`에 이어 TokenService의 일반/호환 로그아웃은 증명의 정확한 회원·family 연결만 비우고 같은 Clock 시각으로 세션을 폐기한다. 기존 탈취 판단과 `noRollbackFor=BusinessException`은 유지하며 회원 전체 폐기 시 그 회원의 연결만 함께 비운다. 설치 행·내부 ID·UUID·암호문을 유지하고 명시적 DELETE는 실제 삭제로 구분한다.

- 새 `PushLogoutPersistenceTest`는 실제 TokenService/JPA/서명/암호화와 일회용 PostgreSQL 17을 사용한다. 테스트 바깥 트랜잭션으로 결과를 감추지 않는다. canonical 푸시 enum/테이블은 실제 설계를 쓰며 아직 미이관된 User/RefreshToken만 명시적인 테스트 전용 legacy bridge DDL로 구동한다. 전체 A151 이관을 대체하지 않는다.
- **8개 모두 failure/error/skip 0**: 같은 family 여러 설치 정리·타 family/회원 보존·동일 정리/폐기 시각, 계정 전환과 같은 회원 새 family의 늦은 일반/호환 재시도, foreign release UUID, 등록/로그아웃 잠금 경합, 탈취 401의 실제 커밋, 세션 저장 실패 시 연결 정리 롤백, 정상 토큰 회전 유지·만료 시 새 제안 차단이다.
- Checkstyle main/test 경고 0·컴파일·bootJar 성공. 저장 단위의 27개와 이번 로그아웃 8개는 서로 다른 실행/선택이며 합쳐 전체 앱 suite 통과로 표현하지 않는다. 변경한 기존 전체 컨텍스트 PushInstallationIntegrationTest/PushSessionIntegrationTest의 테이블/PK·종료 후 기대값은 컴파일/정적 검사했고, 알려진 A151 `app_users` 차단으로 런타임을 다시 실행하지 않았다.
- 현재 계약의 이전 일반 로그아웃 연결 유지·세션 미상 새 제안 허용 설명은 2026-10-28 계약으로 대체했다. 웹 Notion/공유 DB/운영 DB/FCM/기기/CI/배포에는 반영하지 않았다. 이관 SQL 적용 상태는 **없음**이다.

```text
./gradlew test --tests '*PushLogoutPersistenceTest' checkstyleMain checkstyleTest bootJar --console=plain
```

다음 행동 1개: A151 공통 매핑과 합친 결과에서 기존 인증/안내 전체 컨텍스트 회귀를 검증한다. keyring 숫자 ID·소유/ACK 이관, 과거 실제 DB 조사와 승인 후 수동 이관, 공유/기기 검증은 여전히 남아 있다.

## 이전 체크포인트: 사용자 선택 반영 단위

사용자는 178번 선택에 **키링 응답에서 등록 시각을 제거하고 ERD 유지**, **푸시 연결 ID·최초 연결 시각을 ERD에 보존**으로 답했다. 이 두 선택은 더 이상 합의 대기 항목이 아니다.

- KeyringView·native SELECT 투영·OpenAPI required/property/example에서 `boundAt`을 제거했다. `deviceId`·`status`·`firmwareVersion`은 유지하고 `syncedAt`·현재 시각을 대신 반환하지 않는다. 현 구 binding 테이블의 bound_at 정렬은 내부에서만 유지하며 keyring ERD에 시각 칸을 추가하지 않았다. 숫자 기기 ID·전체 소유/ACK 저장 이관은 별도 남은 작업이다.
- 현재 singular `push_installation`의 canonical 설계 SQL·ERDCloud 표시 SQL·tables.json·ERD 표/관계도에 nullable `binding_id` UUID와 `linked_at` timestamptz를 보존했다. **21표·153컬럼·35논리관계**이며 관계나 물리 FK는 늘리지 않았다. 원본 151컬럼 첨부의 source hash와 과거 검증 기록은 보존했다.
- 두 값은 같은 트랜잭션에서 함께 확정/해제하고 같은 family의 정상 토큰 교체에는 유지한다. 최초 연결/재연결에는 새 세대와 최초 시각을 확정하며 로그아웃 때 소유자·family와 함께 비운다. 기존 행의 모르는 값은 둘 다 NULL로 유지하고 임의 UUID·현재 시각으로 backfill하지 않는다. 기존 JPA 동명 필드를 재사용하므로 중복 칸을 만들지 않는다.
- 실제 PostgreSQL17 일회용 DB에서 전체 canonical 설계 DDL을 실행해 **21표·153컬럼**, 두 타입/nullable/default 없음, 기존 증거 없는 행의 NULL 유지 및 확정된 UUID/최초 시각 저장을 신규 3개 테스트로 확인했다. 이는 전체 앱 JPA/로그아웃 구현 검증을 대신하지 않는다.
- 최종 영향 검증 **28개(실제 PostgreSQL 설계 검증 3 + DB 비의존 25), failure/error/skip 0**. Checkstyle main/test 경고 0, 컴파일·bootJar 및 `git diff --check` 통과. 전체 build의 알려진 A 공통 매핑 차단은 다시 실행하지 않았고 해소로 보지 않는다.
- `python docs/verification/verify_keyring_push_176.py`는 두 DDL의 모든 21표/153컬럼과 metadata 일치, 35관계/원본 provenance/타 테이블 보존, 키링 3필드·활성 API67개를 확인했다.

```text
./gradlew test --tests '*PushBindingSchemaTest' --tests '*PushDeliveryLookupTest' --tests '*KeyringPushRouteTest' --tests '*KeyringPropertiesTest' --tests '*PushTokenCipherTest' --tests '*PushTokenEncryptionTest' --tests '*FirebasePushGatewayTest' checkstyleMain checkstyleTest bootJar --console=plain
```

공유/기존 DB 반영용 변경 SQL의 의미는 다음과 같다. 대상이 이미 singular 구조인지와 기존 칼럼 타입·NULL 허용을 먼저 확인하고 누락분만 적용해야 한다. 이 SQL을 공유 DB나 운영 DB에 **실행하지 않았다**. 현재 커밋의 canonical SQL은 새 일회용 DB용 설계 DDL이고 자동 migration이 아니다.

```sql
ALTER TABLE push_installation
    ADD COLUMN IF NOT EXISTS binding_id uuid,
    ADD COLUMN IF NOT EXISTS linked_at timestamptz;
```

다음 행동 1개: 확정된 두 연결 필드를 재사용해 push_installation의 JPA/nativeSQL·로그아웃 연결 비움 이관을 A151과 맞춰 검증한다. 현 브랜치의 구 plural JPA/로그아웃 동작과 keyring 숫자ID 이관은 미완료다. push·MR·병합·공유 DB 적용·실기기/FCM·배포는 미실행이다.

## 2026-10-28 후속: 푸시 저장 모델 이관

- `PushInstallation`과 native SQL을 `push_installation.id`로 맞추고 `user_id`를 nullable로 전환했다. 플랫폼은 canonical PostgreSQL `device_platform` enum으로 읽고 쓴다. 설치 UUID·family UUID·기존 암호화 AAD 문자열은 유지한다.
- 명시적으로 소유자가 비워진 행만 다른 회원이 재등록할 수 있다. 같은 회원·family이며 binding_id/linked_at이 모두 존재할 때만 기존 연결 쌍을 보존한다. 한쪽이라도 없는 과거 행은 인증된 재등록 시 두 값을 함께 새로 확정하며 과거 자료를 추정해서 채우지 않는다.
- 모든 알림 종류는 현재 소유자·family·완전한 연결 쌍과 유효 세션이 필요하다. 기존 새 제안의 로그아웃/세션 미상 허용은 확정된 연결 비움 계약에 따라 종료했다. 도메인별 알림 권한은 변경하지 않는다.
- [수동 이관 SQL](../database/push-installation-176-migration.sql)은 알려진 구 canonical DDL(`0255529`)에 대한 검토안이다. 기존 테이블·PK를 제자리에서 rename하고 owner NULL 허용·기존 플랫폼 CHECK 제거·enum 변환만 수행한다. 데이터 DML·identity 재설정·암호문 재생성은 없다. source/target 동시 존재와 불완전한 연결 쌍은 사전 차단한다. 실제 공유 DB의 스키마/자료를 조사하거나 이 SQL을 적용하지 않았다.
- 일회용 PostgreSQL 17에서 canonical JPA/enum·소유 경쟁·불완전 쌍·토큰 교체·조건부 삭제 8개와 구 테이블 이관/충돌 사전 차단 3개를 검증했다. 이관 회귀는 기존 ID/UUID/암호문 복호화/NULL/시각/UNIQUE 및 다음 identity를 보존한다. 과거 암호문 fixture는 변경하지 않은 현재 Cipher로 생성했으며 실제 운영 암호문·모든 과거 변형 스키마에 대한 검증은 아니다.
- 영향 검증은 저장 8·이관 3·설계 3·조회 7·경로 6의 **27개**, failure/error/skip 0이다. Checkstyle main/test 경고 0·Java 컴파일·bootJar 성공, `git diff --check` 통과. 전체 앱의 기존 A151 매핑 차단은 여전히 별개다.

```text
./gradlew test --tests '*PushStoragePersistenceTest' --tests '*PushLegacyMigrationTest' --tests '*PushBindingSchemaTest' --tests '*PushDeliveryLookupTest' --tests '*KeyringPushRouteTest' checkstyleMain checkstyleTest bootJar --console=plain
```

다음 행동 1개: 이 저장 모델을 사용한 실제 TokenService 로그아웃/탈취 폐기의 원자 연결 비움과 계정 전환 회귀를 검증한다. 키링 숫자 ID·A151 공통 매핑·전체 앱/안내 통합·공유 DB 적용은 남아 있다.

## 첫 단위: 네 API 경로·인증 회귀

- 구매 안내·본인 키링 GET 2개 및 푸시 단말 PUT/DELETE 2개에서 `/v1`을 제거했다. 기존 경로를 쓰는 관련 통합 테스트의 호출만 바꿨다.
- 실제 SecurityConfig와 가입 인터셉터를 적용한 MVC 테스트는 DB/외부 공급자를 대체한다. 새 경로의 JWT 주체·설치/session UUID 전달, no-store, 미인증·손상 토큰·가입 미완료 거부 및 구경로 비노출을 검증한다. DB 영속 상태·실제 JWT 서명·로그아웃 경합 검증을 대신하지 않는다.
- OpenAPI는 이 4개 operation만 이동하고 `PATH_ALIGNED_STORAGE_REVIEW_REQUIRED`를 표시했다. 같은 구 키링 경로의 POST 등록과 challenge/sync operation은 변경하지 않았다. 다른 API·공유 스키마는 유지했다.
- Java21은 `C:/Program Files/Java/jdk-21.0.11`을 사용한다. 초기 Gradle 실행의 캐시 권한 오류, 테스트 mock의 generic JwtException 사용 오류 및 새 테스트 import/줄바꿈 경고는 검증 실패 이력이며 성공으로 계산하지 않는다.
- 최종 경로 단위 검증: DB 비의존 20개 테스트(실패·오류·skip 0), Checkstyle main/test 경고 0, Java 컴파일·bootJar 성공. 새 MVC 회귀 6개를 포함한다. OpenAPI 활성 67개를 유지하고 경로 4개 및 검토 표시 외에는 전체 스키마·다른 operation이 동일함을 JSON 구조 대조했다. `git diff --check` 통과.

```text
./gradlew test --tests '*KeyringPushRouteTest' --tests '*KeyringPropertiesTest' --tests '*PushTokenCipherTest' --tests '*PushTokenEncryptionTest' --tests '*FirebasePushGatewayTest' checkstyleMain checkstyleTest bootJar --console=plain
```

반영 커밋: `5979c59` (`refactor: 키링 조회와 푸시 단말 API 경로 정합화`). 위 20개 검증을 마친 코드 단위다.

## 전체 build 결과와 실DB 검증 한계

같은 경로 단위 소스로 `./gradlew build --console=plain`을 실행했다. Java 컴파일·jar/bootJar·Checkstyle은 성공했지만 **253개 중 159개 테스트 실패(오류 0, skip 0)**로 전체 build는 실패했다. PostgreSQL 17 Testcontainers는 실행됐으나 `test,erd` 프로필에서 설계 DDL의 `users`와 기존 JPA의 `app_users`가 맞지 않아 `Schema validation: missing table [app_users]`로 ApplicationContext 시작이 차단되고 같은 컨텍스트 테스트들이 연쇄 실패했다. 이 두 파일/매핑은 이번 경로 단위에서 변경하지 않았다. 기준 커밋의 전체 build를 별도로 재실행해 비교한 것은 아니므로 159개 각각의 과거 실행 결과까지 확인했다는 의미는 아니다.

따라서 이 단위의 DB 비의존 20개 통과와 전체 build 실패를 구분한다. 이관 전용 PostgreSQL 회귀·기존 데이터 이관·실제 로그아웃/푸시 경합 통합 검증은 완료되지 않았으며, 전체 build 실패는 최종 병합을 막는다. A151의 공통 엔티티 이관과 175/176 저장 이관을 맞춘 뒤 영향받는 통합 검증을 다시 실행해야 한다. 공유 DB나 운영 데이터를 초기화해 우회하지 않는다.

## 두 번째 단위: 지연 전달 후보의 현재 연결 재검사

2026-10-28 이어서 진행했다. `PushDeliveryLookup.resolve`는 후보의 현재 소유자·설치 UUID·암호문 지문을 확인했지만 후보에 보존한 `bindingId`·`linkedAt`을 비교하지 않았다. 암호문이 같아도 연결이 바뀌면 이전 후보를 새 수신 연결로 해석할 수 있는 검사 공백을 보완했다. 소유 연결이 비워진 행은 resolve/invalidate에서 Long 자동 언박싱 예외 대신 빈 결과/false로 차단한다. 새 컬럼·연결 정책을 추가하지 않았고, 기존 계약의 발송 직전 재검사만 강화했다.

- 신규 회귀 5개로 정상 현재 후보, 동일 암호문의 다른 연결 UUID, 다른 최초 연결 시각, 소유자 null의 해석/무효화를 검증했다. 수정 전 5개 중 4개가 실패해 검사 공백과 null 예외를 재현했다.
- 수정 후 신규 5개와 이전 경로/설정/암호화/게이트웨이 회귀를 합한 **25개 테스트, 실패·오류·skip 0**, Checkstyle main/test 경고 0, Java 컴파일·bootJar 성공. 저장소·회원·세션·복호화 포트는 가상 구현이며 DB/실제 FCM 검증이 아니다.
- 전체 build를 다시 실행해 알려진 공통 JPA `app_users`/설계 DDL `users` 불일치를 중복 확인하지 않았다. 위 전체 build 실패와 최종 병합 차단은 유지한다.

```text
./gradlew test --tests '*PushDeliveryLookupTest' --tests '*KeyringPushRouteTest' --tests '*KeyringPropertiesTest' --tests '*PushTokenCipherTest' --tests '*PushTokenEncryptionTest' --tests '*FirebasePushGatewayTest' checkstyleMain checkstyleTest bootJar --console=plain
```

## 178번 선택 내역과 구현 사항 — 아래 두 선택은 이후 사용자 답변으로 확정

| 구분 | 구체 내용 |
| --- | --- |
| 확정 선택 1 | keyring 조회 `boundAt` 제거·ERD 유지. 현재 시각 또는 synced_at으로 대신 만들지 않는다. |
| 확정 선택 2 | push 응답·안내 수신/발송 완료 키의 `bindingId`와 최초 연결 시각 `linkedAt`을 ERD에 보존한다. 같은 family 토큰 교체에는 유지, 재연결은 새 세대이며 로그아웃 연결 해제 때 함께 비운다. |
| 별도 제품 결정이 아닌 구현 사항 | 숫자 keyring.id, keyrings→keyring·push_installations→push_installation 이름/PK 변경, push user_id nullable·로그아웃 연결 비움은 최신 ERD의 명시 항목이다. 공통 A151/인증의 실제 이관과 소유·세션·참조 회귀가 필요하다. |

현재 같은 로그인 family의 정상 토큰 교체는 bindingId/linkedAt을 유지한다. 반면 명시적 해제 후 같은 family로 재등록하는 경우에는 새 연결이다. 따라서 family+설치 UUID만으로는 두 경우를 구분하지 못한다. `updated_at`/암호문 지문은 정상 토큰 교체에도 바뀌므로 이를 그대로 발송 완료 키로 쓰면 중복 발송하거나, 최초 linkedAt 대신 쓰면 오래 연결된 단말을 늦게 등록한 단말로 잘못 분류할 수 있다. 지문은 순간 후보 무효화에 유효하지만 기존 안정적인 안내 수신 연결 의미 전체를 대체하지 않는다. 이 판단은 코드의 register SQL과 NoticeDeliveryStore의 최초 연결 시각·delivered(bindingId) 사용을 대조한 결과다.

## 엔티티·SQL 적용 상태와 남은 작업

첫 경로 단위와 지연 후보 검사 단위는 JPA 엔티티나 설계/적용 SQL을 변경하지 않았다. 후속 사용자 선택 단위는 위 두 설계 컬럼을 보존했으며 공유 DB·운영 DB·배포에 적용한 SQL은 없다. ERD21 전체 저장 이관이 완료된 것으로 보지 않는다.

| 남은 이관 | 현재 구코드와 ERD21 차이 | 선행 확인 |
| --- | --- | --- |
| 키링 조회/ID | keyrings.public_device_id UUID와 binding·sync/snapshot JOIN → keyring.id 숫자, owner_user_id/state/synced_settings_version | boundAt 제거는 완료. 소유 증명/등록·ACK와 설정 세대 연계 및 숫자 ID 저장 이관은 남음 |
| 푸시 저장 | push_installations.installation_id → push_installation.id, user_id nullable | JPA/nativeSQL/소유 조회 및 암호화 AAD·계정 전환 영향 |
| 로그아웃·지연 발송 | 현재 family 연결 유지/선택적 삭제 → 로그아웃 연결 비움, binding_id·linked_at은 보존 확정 | 보존 필드를 사용한 오래된 후보·늦은 재시도·재로그인·다른 계정 연결·안내 연동의 실제 이관/회귀 |

이번 두 선택을 적용한 현재 체크포인트와 다음 행동은 위 최신 단위를 따른다. A151·키링67/100·동기화68/69·안내95/97의 경계를 맞춰 실제 이관을 이어 간다. 현재 작업의 실제 반영은 로컬 브랜치이며 push·MR·승인·develop 병합·실DB·기기/FCM·배포는 별도다.
