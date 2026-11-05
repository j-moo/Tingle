# S15P31E106-66 구현·검증

기준 develop `fbe0f352d0a013b8aaebf0623c3e9f6a0d5080c8`, 2026-10-22. 구매 안내와 본인 기기 조회를 검증한 단위별로 커밋한다. [API 계약](../contracts/api/keyring-read.md).

## 1. 구매 안내와 본인 등록 필요 여부

- JWT·세션·가입 단계 검사에 구매 안내를 연결했다. 본인의 미해제 binding과 실제 keyrings 행을 함께 확인한다. 설정하지 않은 구매 주소는 null이며 실제 구매처를 임의 지정하지 않는다.
- 구매 안내 응답의 기존 두 필드·no-store, 기기 미등록/등록/해제, 타인·잘못된 기기 참조, 인증 없음·잘못된 토큰·약관 미동의·로그아웃·정지 계정을 검증했다.
- PostgreSQL 17 Testcontainers 일회용 DB에 canonical 설계 SQL을 실행하고 기존 JPA 매핑을 validate했다. 시작 시 Docker 연결 실패로 통합 테스트가 실행되지 않은 첫 시도는 성공으로 계산하지 않았다. Docker 엔진 시작 완료 후 동일 검증을 다시 실행해 통과했다. Docker 데이터나 공용 DB는 변경하지 않았다.

```text
./gradlew test --tests '*Keyring*' --tests '*OnboardingIntegrationTest' --tests '*RuntimeConfigurationTest' checkstyleMain checkstyleTest bootJar --console=plain
```

결과: 29개 테스트, 실패·오류·skip 0. 신규 구매 안내 HTTP/DB 5개·설정 검증 3개, 기존 가입 단계 18개·실행 설정 3개. Checkstyle main/test 경고 0, 컴파일·bootJar 성공. 실제 판매처·결제·실물 키링·기기 등록/동기화·외부 공급자·공유 DB·배포 검증은 아니다.

## 2. 공개 UUID와 본인 기기 목록·소유권

- 사용자 결정대로 공개 deviceId UUID 계약을 유지하고 `keyrings.public_device_id`를 추가했다. JPA 생성 시 UUID v4 발급, 재조회 시 유지, DB NOT NULL·UNIQUE를 확인했다. 기존 숫자 device_id·binding_id와 논리 참조는 바꾸지 않았다.
- `GET /api/v1/keyrings`는 인증된 본인의 현재 활성 연결만 반환한다. 타인 ID/UUID를 쿼리에 넣어도 조회 주체가 바뀌지 않으며 해제 이력·실제 기기가 없는 연결은 제외한다. UUID는 해제 후 다른 계정에 연결해도 유지되지만 이전 소유자의 목록에서는 사라진다.
- 응답 필드 4개·no-store·정렬, 기기 자격 정보 비노출, 미등록·인증 없음·약관 미동의·로그아웃·정지 계정, 현재 설정 ACK·구버전·타인 스냅샷·이전 연결 ACK를 검증했다. 실제 BLE 연결 관측 없이 OFFLINE을 추정하지 않는다.
- 기존 부분 유일 인덱스 `ix_keyring_bindings_1`이 같은/다른 회원의 두 번째 활성 연결을 모두 거부하는 것을 확인했다. 최초 검증의 중복 연결 생성 2개 테스트는 이 기존 제약을 위반하는 fixture였으며, 실제 보장인 제약 거부·기존 소유자 보존을 검사하도록 바로잡았다. 제약을 완화하지 않았다.
- canonical SQL·가져오기 SQL·tables.json의 keyrings 5개 컬럼·타입·nullable·UUID 유일성을 대조했다. 전체는 canonical 24테이블168컬럼, import/metadata 24테이블166컬럼이다. 작업 전부터 있던 email·completed_at 2컬럼 차이는 보존했다. OpenAPI 변경은 66번 두 GET의 구현 문서 링크와 Keyring.deviceId 설명뿐이며 다른 계약은 동일하다.

```text
./gradlew build --console=plain
```

최종 결과: 전체 **167개 테스트, 실패·오류·skip 0**, Checkstyle main/test 경고 0, 컴파일·bootJar·jar·build 성공. 신규 목록·소유권·UUID 통합 테스트 9개를 포함한다. PostgreSQL 17 일회용 DB의 canonical DDL·JPA validate 및 신규 UUID의 JPA persist/재조회를 사용했다. 테스트 fixture의 위 두 실패와 줄 길이 경고 4개를 수정한 최종 소스로 전체 build를 다시 통과했다.

공유 DB에 기존 행이 있으면 UUID 발급·누락/중복 검사·NOT NULL/UNIQUE 적용이 먼저 필요하다. 실제 기기 등록·증명·송수신·공유 DB·웹 ERD/노션·배포 적용 완료를 뜻하지 않는다.
