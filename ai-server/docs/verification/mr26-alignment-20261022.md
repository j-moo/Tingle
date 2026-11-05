# MR26 후속 명세·제안 권한 정합화

2026-10-22. 이전 [정합화 기록](spec-alignment-20261022.md)을 보존하고 최신 develop·MR26 기준의 후속 변경을 기록한다. 아래 검증은 커밋 전 작업본에서 수행한 기록이다. 검증 당시 푸시·MR 승인·병합·배포는 하지 않았다.

## 기준과 수정 범위

| 구분 | 기준 |
| --- | --- |
| develop | `3787f98630256da08d8271291d49159195a93803` |
| MR !26 | `64414355e558cf982e00d03dbc0995a993f61bb8` ([GitLab](https://lab.ssafy.com/s15-final/S15P31E106/-/merge_requests/26)) |
| 기존 로컬 HEAD | `906747d71a8910f7e09e386ac5f0d55e8359a0cc`; 앞선 두 정합화 커밋 보존 |
| 통합 방법 | MR26 변경과 develop 문서를 파일별로 반영. Git 브랜치 merge/rebase/commit 없음 |
| 사용자 확정 | 가입 후 AI 동의 철회로 제안·매칭을 막지 않음. 기능 명세 내용 수정·커밋·푸시·병합 금지 |

기능 명세는 최신 develop 원문과 동일하게 동기화했다. 기능 명세 문구를 임의로 수정하지 않았다. MR26의 프로필 등록 완료·가입 후 기능 접근·오류 메시지 리소스 변경을 기존 이메일·닉네임·숫자 제안 ID·멱등 키 정합화와 함께 반영했다.

작업 중 원격 갱신을 다시 확인했다. develop 추가분은 웹 웰컴 화면이며 백엔드·기능/정책 명세 변경은 없다. MR26 추가분은 약관 제목의 메시지 리소스 분리와 언어별 반환이다. 이11개 파일을 추가 반영하고 백엔드 검증을 다시 수행했다. 최초6217093 기준129 tests 결과는 [이전 검증 JSON](mr26-alignment-20261022/initial-verification-6217093.json)으로 보존한다.

## 계약과 구현

- `POST /api/v1/users/me/profile/complete`: 본문 없이 200 `{ onboardingStatus }`. 필수 항목이 비면400 `PROFILE_INCOMPLETE`. 반복 요청은 성공하며 최초 완료 시각을 보존한다.
- `completed`는 명시적 등록 완료 여부다. PATCH 임시 저장 중에는 성별·생년월일을 바꿀 수 있으며, 등록 완료 후 다른 값으로 변경하면422 `PROFILE_FIELD_LOCKED`다.
- `profiles.completed_at timestamptz NULL`을 canonical SQL·컬럼 명세·가져오기 SQL·그림·ERDCloud에 반영했다. 현재24테이블166컬럼이며 물리 FK는 없다.
- 가입 동의는 현재 `TERMS`·`PRIVACY`·`ADULT`(2026-10-19), `AI_RISK_DETECTION`(2026-10-22) 네 필수 항목이다. 목록은 설정에서 읽으며 앱에 종류·버전을 고정하지 않는다. 예시 URL은 실제 안내문이 아니다.
- AI 동의는 최초 가입에는 필요하다. 가입 완료 후에는 제안·매칭 권한에 필요한 비AI 필수 동의와 구분한다. 서비스·개인정보 등 현재 필수 약관 동의가 없으면 제안 접근은 계속403이다.
- 기존 제안 응답은 가입 완료·활성 계정·현재 비AI 필수 동의를 검사한다. 키링 초기화/설문 재검사/동기화 상태를 기존 제안 응답 조건으로 사용하지 않는다. 신규 매칭 준비 검사는 별도로 유지한다.
- 제안 당사자·만료·차단·관계 상태·동시 잠금·멱등 키 검사를 유지했다. AI 동의를 철회했다고 새 채팅 권한이 생기는 것은 아니다.
- 약관 `title`과 오류 `message`는 `Accept-Language`의 지원 문구를 쓰고 미지정·미지원 시 한국어를 쓴다. 앱 분기는 `code` 기준이다. 액세스 토큰401 네 종류와 소셜/리프레시 오류를 구분한다.
- Onboarding의 `questionCount`는 설문 연결 전 임시값0을 허용한다. 실제 설문 회차의 최소 문항 수를0으로 바꾸지 않았다.

## 검증

| 범위 | 결과 |
| --- | --- |
| 백엔드 `check bootJar` | 129 tests, 실패0·오류0·skip0. Testcontainers 일회용 PostgreSQL17 포함 |
| Checkstyle | main/test 오류0, 실행 JAR 생성 성공 |
| 제안 회귀 | 키링 초기화 후 accept/decline, AI 철회 후 조회·상대 프로필·accept/decline, 두 상태 동시 발생, TERMS/PRIVACY 거부 시403, 최초 가입 AI 필수 검사 |
| 기존 회귀 | 제안 만료·소유권·차단·동시성·멱등 키, 인증·프로필 등록 완료·잠금·닉네임·오류 리소스 검사 포함 |
| OpenAPI | 활성69 API, 전체 문서 검증 및 요청/응답 예시649개 스키마 검사 통과 |
| DB 문서 | canonical/import/웹 Export PostgreSQL 파싱 성공, 각24/166·물리FK0. 테이블·컬럼 이름/타입/nullable 차이0 |
| ERD 저장 | 웹 새로고침 후 Export 재수집. 기존165개 필드와24개 테이블 ID·관계·좌표·속성 변경0. 추가는 `profiles.completed_at` 하나 |
| 기능 명세 | 최신 develop 원문과 동일 |

기계 판독 결과·소스 해시는 [검증 JSON](mr26-alignment-20261022/verification.json)에 기록한다. [ERD 스냅샷](mr26-alignment-20261022/erdcloud-snapshot.json)은 개인 메타데이터를 제외한 구조만 보존한다. [웹 PostgreSQL Export](mr26-alignment-20261022/erdcloud-postgresql.sql)는 검증 증거이며 운영 DB 적용 명령이 아니다. 실행 DDL의 identity/default/unique/check/index 기준은 [schema.sql](../database/schema.sql)이다.

## 웹 반영

새 [프로필 등록 완료 API](https://app.notion.com/p/401c91ccb660803ba58ce36a65a4e915)와 기존 프로필·가입 동의·온보딩·제안 API 페이지를 이번 변경 범위로 동기화했다. API14개와 [API 명세서 공통 규칙](https://app.notion.com/p/API-4a2c91ccb660820793de81b259578314)까지15페이지를 저장 후 재조회했다. 저장 후 재조회 결과와 본문 해시는 [노션 검증 인덱스](mr26-alignment-20261022/notion-readback.json)에 기록한다. 기능 명세 노션 페이지는 수정하지 않는다. 과거 “기존안·원문 보존” 영역은 당시 이력으로 남긴다.

## 남은 연동과 적용 전 확인

- AI 동의 철회/재동의 API·활성 방 종료·새 방 채팅 접근 차단·AI 분석 중단은 별도 채팅/AI 후속 구현이다. 이번 테스트는 최신 철회 이력이 저장된 상태의 제안 권한을 검증했다. 철회 API 구현 완료를 뜻하지 않는다.
- 제안 양쪽 수락 후 실제 방 생성은 기존 후속 작업이다. 현재 구현의 `chatAvailable=false`·`chatRoomId=null`을 유지한다.
- 실제 소셜 공급자·앱·실물 키링·공용 DB·배포는 검증하지 않았다. 가입 상세 설문/설정 임시값도 실제 연결 완료로 표시하지 않는다.
- 원격 CI/승인·리뷰·병합 가능 여부는 이번 로컬 검증 결과와 별도다. 푸시/MR 진행 시 최신 source/target과 필수 검사를 다시 확인한다.
- 기존 공용 DB에 적용하려면 nullable 컬럼 추가와 기존 회원 등록 완료 시각의 이행 기준을 배포 담당과 정한다. 임의 시각을 채우거나 테스트로 운영 데이터를 이행하지 않았다.

## 커밋·MR 준비 재확인

2026-10-22 사용자 요청으로 기존 작업 브랜치의 커밋·푸시·develop 대상 MR 생성을 준비했다. 병합은 승인 범위에서 제외한다. 최신 develop `c25bb4f`의 추가 변경은 인프라/웹 이미지이며, 직전 검증 기준 `3787f98` 대비 `back/`·`docs/`·CI 규칙 변경은 없다. 백엔드 전체 소스 해시는129 tests·빌드·Checkstyle 통과 당시와 일치한다. MR26은 `6441435` 그대로다. OpenAPI·문서의 미커밋 표기는 검증 시점 기록과 MR 검토용 상태로 구분했다. 이후 원격 source/target 및 MR 상태는 MR 본문과 작업 체크포인트를 따른다.
