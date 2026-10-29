# 푸시 단말 등록·교체·해제 구현 기록

- 대상: S15P31E106-82. 2026-10-22 기준 김영빈 담당, 3점 백로그.
- 기준: develop `6267d0d77b588689be8de23af69184a1c9d90110`에서 분기한 `be-feature/push-installations-S15P31E106-82`.
- 범위: `PUT /api/v1/push-installations`, `DELETE /api/v1/push-installations/{installationId}`. 안내 생성·예약·실제 푸시 전달은 별도 Story.
- 변경 단위마다 관련 검증을 수행한 뒤 별도 커밋으로 남긴다. 기능 명세 원문은 수정하지 않는다.

## 1. 토큰 암호화 기반

`push/PushTokenCipher`는 전달 토큰을 AES-256-GCM으로 암호화한다. JWT 서명 키와 구분되는 Base64 형식의 32바이트 키를 생성자에 전달해야 하며, 누락·형식 오류·다른 길이는 거부한다. 이 단계에서는 Spring 설정이나 공개 API에 연결하지 않는다.

- 저장 형식: `v1.{Base64url nonce}.{Base64url ciphertext와 인증 태그}`. nonce는 암호화마다 새로 생성하는 12바이트, 태그는 128비트다.
- 인증 문맥: 형식 버전·설치 UUID·회원 ID. 다른 회원 또는 설치로 복사한 암호문은 복호화되지 않는다.
- 키·토큰·암호문을 예외 메시지나 로그에 출력하지 않는다. 복호화 오류는 원문을 반환하지 않고 동일한 내부 예외로 종료한다.
- 같은 입력의 무작위 암호문과 복원, 소유 문맥 변경, 변조, 잘못된 키, 손상 형식·버전, 빈 토큰을 합성 데이터로 검증했다.

검증 명령(저장소 `back/`에서 실행):

```text
./gradlew test --tests '*PushTokenCipherTest' checkstyleMain checkstyleTest bootJar --console=plain
```

결과: 테스트 6개, 실패·오류·건너뜀 0. Checkstyle main/test 경고 0. 컴파일 및 bootJar 통과. 프로젝트 전체 테스트·DB 통합·실제 단말·실제 푸시 전달·공용 DB 적용·배포 검증 결과는 아니다.

## 2. 등록·교체·해제 API와 소유권 검증

- `PUT /api/v1/push-installations`와 `DELETE /api/v1/push-installations/{installationId}`를 기존 JWT/세션·가입 단계 검사에 연결했다. 공개 UUID와 내부 숫자 ID를 분리하고 `push_installations`의 기존 6개 컬럼에 매핑했다.
- 본인 중복 등록·토큰 교체는 기존 행을 갱신한다. 소유권 조건을 포함한 PostgreSQL upsert와 UNIQUE 제약으로 다른 회원의 동시 최초 등록/덮어쓰기를 중재한다. 같은 회원의 작업은 회원 행 잠금 아래 직렬화한다.
- 본인 해제는 실제 삭제 후 204이며, 타인/미등록/반복 해제는 같은 404 `NOT_FOUND`다. 해제 후 재등록은 새 행이다. 다른 회원의 UUID를 안다는 이유로 소유권을 이전하지 않는다.
- 별도 `PUSH_TOKEN_ENCRYPTION_KEY`가 없으면 등록은 503이다. 해제는 암호화 설정 없이도 가능하며 잘못된 키는 시작 단계에서 거부한다. 요청 DTO의 문자열에는 토큰을 가린다.
- [계약](../contracts/api/push-installations.md)과 OpenAPI의 해당 두 경로에 404 코드 및 로컬 구현 기준을 연결했다. 나머지 경로·스키마는 변경하지 않았다. 기능 명세 원문·ERD·공용 DB·외부 문서는 수정하지 않았다.

검증: 아래 필터의 `test` 작업에서 **68개, 실패·오류·건너뜀 0**. 새 푸시 HTTP/PostgreSQL 통합 테스트 10개, 암호화 6개, 설정·진단 문자열 보호 3개, 기존 인증 27개·가입 단계 18개·오류 문구 1개·실행 설정 3개다. PostgreSQL 17 Testcontainers의 일회용 DB에 설계 SQL을 실행하고 JPA `validate`로 매핑을 확인했다.

```text
./gradlew test --tests '*Push*Test' --tests '*ErrorMessagesTest' --tests '*RuntimeConfigurationTest' --tests '*AuthIntegrationTest' --tests '*OnboardingIntegrationTest' checkstyleMain checkstyleTest bootJar --console=plain
```

테스트는 통과했으며 함께 실행한 스타일 검사에서 발견한 줄 길이 3곳을 줄바꿈만 수정했다. 이후 최종 소스의 아래 검증은 모두 성공했다. Checkstyle main/test 경고 0이며 테스트 로직 변경은 없다.

```text
./gradlew compileTestJava checkstyleMain checkstyleTest bootJar --console=plain
```

정상 등록, 같은 회원의 두 설치 격리·토큰 교체·중복 등록, 본인 해제/재등록, 타인 교체/해제 거부 및 저장값 보존, 잘못된/누락된 인증·가입 미완료·폐기 세션·정지 회원, 잘못된 입력과 응답의 토큰 비노출, 같은/다른 회원의 동시 등록을 검증했다. 키 누락은 실제 암호화 구성 단위 테스트로, 암호화 불가 시 HTTP 503·기존 값 보존·해제 허용은 암호화 경계의 실패를 주입한 통합 테스트로 확인했다. 소셜 검증과 시계는 기존 통합 테스트 방식대로 대체했다.

## 3. 계정 전환·로그아웃과 전달 조회 경계

- 설치별 검증된 JWT sid를 nullable `login_family_id`에 연결한다. 일반 로그아웃은 새 제안 연결을 남기고, 메시지·방 생성·종료 안내는 해당 설치의 현재 세션으로 판단한다. 다른 설치의 로그인이 대신 통과시키지 않는다.
- 기존 로그아웃 요청에 선택 UUID `releaseInstallationId`를 추가했다. 기존 리프레시 증명에서 확인한 회원·family·설치가 모두 일치할 때만 연결을 해제하고 세션을 폐기한다. 다른 회원/세션 및 해제 후 재등록된 행에는 과거 요청이 영향을 주지 않는다. 기존 요청·재시도·기기별 폐기 범위는 유지한다.
- JWT 검사 후 저장하기 전 로그아웃이 발생해도 회원 잠금 안에서 세션을 재검사한다. 리프레시 회전은 family를 유지한다.
- 내부 `PushDeliveryLookup`은 토큰 없는 후보와 발송 직전 재해석을 분리한다. 현재 소유자·암호문 지문·계정·설치 세션을 확인한 뒤 복호화하고, 예전 계정/토큰/행의 대기 참조는 거절한다. 실제 공급자 호출·자원별 정책·지연 도착 차단은 이 조회 경계의 보장이 아니다.
- canonical/import SQL·ERD·tables.json에 push 컬럼 1개를 반영했다. 기존 canonical/import의 `app_users.email`·`profiles.completed_at` 차이는 기준 커밋에도 있었음을 확인하고 보존했다. canonical 총167/import·metadata 총165이며 이번 push의 7개 컬럼만 세 형식에서 일치한다.

```text
./gradlew build --console=plain
```

결과: **전체 150개 테스트, 실패·오류·건너뜀 0**, Checkstyle main/test 경고0, 컴파일·bootJar·jar·build 성공. 푸시 테스트는 31개이며 이번 세션·계정 전환 PostgreSQL/HTTP 테스트 12개를 추가했다. 기존 인증·가입·프로필·제안 테스트도 포함한다.

이번 추가 검증: 일반 로그아웃 뒤 새 제안 유지/3종 채팅 알림 차단, 설치별 로그인 분리, 이미 로그아웃한 증명의 명시적 해제→새 계정 등록, 지연 과거 해제와 다른 회원/세션 증명 차단, 잘못된 증명/UUID, 저장 직전 폐기 재검사·실제 등록/로그아웃 경합, 토큰 교체/삭제/재등록 후 예전 후보 무효화, 리프레시 회전·만료, 재로그인 뒤 과거 로그아웃의 안전성, null 세션 행·정지/탈퇴 계정, 변조 암호문 거부. Docker의 일회용 PostgreSQL17과 가상 소셜 검증을 사용했다.

## 적용 전 남은 확인

- FE·인증 담당의 선택적 해제/재등록·로그아웃 수신 계약 검토와 실제 앱/기기 검증. 증명 폐기 후의 UUID만으로 이전 연결을 탈취하는 우회는 제공하지 않는다.
- 인프라 담당의 공유 DB nullable 컬럼 적용, 운영 키 관리/교체 및 실제 전달 adapter·공급자 연동 확인. 실제 운영 키 교체나 공용 DB 변경은 실행하지 않았다.
- 기존 OpenAPI 정책 gate의 현재성·다른 공통 계약 차이는 별도 검토한다. 이번 구현을 미정 정책의 자동 승인이나 기존 전체 명세 정합화 완료로 간주하지 않는다.

82번의 세 단위 로컬 구현·검증 기록이며, 완료 조건 전체 충족이나 MR 승인/병합을 의미하지 않는다. 실제 단말·외부 소셜/푸시·공용 DB·배포 검증은 수행하지 않았다.
