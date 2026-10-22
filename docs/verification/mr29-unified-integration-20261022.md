# MR !29 최신 develop 및 통합 API·ERD 검증

2026-10-22 시작, 2026-10-23 확인 완료. !29의 충돌 해결과 다른 작업 폴더의 미커밋 통합 명세를 함께 반영한 로컬 검증이다. 커밋·푸시·MR 병합·승인·리뷰어 변경·공용 DB 적용·배포·실제 알림 발송은 수행하지 않았다.

## 기준과 범위

- 소스: be-fix/contract-alignment-S15P31E106-38, 17e6b4467af786b1319ea5629b5213aefb0a1732.
- 받은 develop: e59cbfb12bc6ef780b038d44ec42dadc856c7f09. 커밋 없이 작업본에 통합했다.
- 명세 원본: 별도 api-erd-unified-20261022의 미커밋 문서. 읽기 전후48개 파일 해시를 대조하며 원본 폴더는 수정하지 않았다.
- DB 대조: MR !56 ea32ef8064996672dd7191d7430f6179830dfc71.
- 설문 소스 대조: MR !57 629ed04a5cd6ef70f62f79b2050e60bf8d5b6a2d. 해당 MR의 백엔드 코드를 이 브랜치에 통합한 것은 아니다.
- 기능 명세·프로필/매칭 정책·매칭 계산·설문 원문·다른 작업 폴더는 수정하지 않았다. develop의 기존 변경은 보존했다.

## 충돌과 문서 정리

1. back/.env.example: 채팅 알림·암호화 설정과 키링 구매 안내 환경변수 예시를 모두 보존했다.
2. back/src/main/resources/application.yml: 키링 구매 설정과 health 설정을 보존하고 app.push 중복을 제거했다. PUSH_ENABLED 기본false, FCM 프로젝트 및 토큰 암호화 키 설정을 한 블록으로 유지했다.
3. docs/database/erd.md·tables.json: 별도 통합 문서를 기준으로25테이블185컬럼을 보존했다. 내부 bigint와 외부 UUID를 섞지 않았다.
4. OpenAPI·API 설명·SQL/import/Mermaid·문서 링크를 통합했다. 채팅·메시지·개별 읽음·안내·설치·로그아웃 계약을 보존하고, 설문용 최소 SurveyManifest와 분석용 전체 Manifest를 분리했다.

!29의183컬럼에 survey_responses.public_id와 keyrings.public_device_id 두 컬럼을 더하면185다. !56 및 통합 원본과 테이블/컬럼 집합·타입·NULL 허용 여부를 대조했다. MR !56의 테이블 제약 중 누락된 것은 없으며 기존 제안 취소4개 CHECK와 읽음(message_id,user_id) UNIQUE를 추가로 보존했다. 실행용 제약의 기준은 schema.sql이며 ERDCloud 그림에 모든 제약이 있다는 뜻은 아니다.

## 실행한 검증

- JDK21.0.11에서 back/의 `gradlew.bat build --no-daemon --console=plain`: BUILD SUCCESSFUL, 1분23초.
- 23개 suite, 테스트238개, 실패/오류/건너뜀0. Checkstyle main/test 오류·경고0. compile·bootJar·jar·check·build 통과.
- PostgreSQL17 Testcontainers를 사용했다. erd 프로필 테스트는185컬럼 canonical schema.sql을 실행하고 JPA validate를 수행했다. 기존 Docker 데이터와 공용 DB를 사용하거나 초기화하지 않았다.
- OpenAPI 형식·예시870건·참조872곳·잘못된 CreateSession 요청7종·설문100문항 및 문서 상대링크를 검사했다.
- canonical SQL/import/tables.json의25테이블185컬럼 이름·타입·nullable, 공개 UUID 제약, 논리 참조와 Mermaid 컬럼을 대조했다. 분석 결과 DDL과 공유 Manifest 필수값은 유지했다.
- 빌드 후 backend source/설정/DDL 해시를 고정하고, 최종 문서 단계에서도 해당 빌드 입력이 유지되는지 확인했다. 결과는 [검증 JSON](mr29-unified-integration-20261022/verification.json)을 따른다.

## MR !57 확인과 남은 범위

현재 소스에서 확인한 것은 설문 조회·회차 시작·회차 조회·회차 문항 조회4개 경로와 핵심 DTO/엔티티다. surveyVersion, 세 필드 Manifest, sessionId=public_id, 코드의 UUID 생성 및 JPA NOT NULL·UNIQUE·DB 기본값 선언을 대조했다. 시작 재사용도201이며 ETag는revision이다.

명세 CreateSession.surveyVersion의100자 상한에 대응하는 Size 선언은 MR !57의 NotBlank DTO에 없다. 상한을 임의로 없애거나 다른 담당자 코드를 고치지 않고 작성자 확인 사항으로 남겼다. 이 차이는 원격 소스 정적 검토 결과이며 해당 MR의 HTTP 실행 결과를 주장하지 않는다.

설문 저장·완료·분석·이관의 전체 런타임, 기존 행 보정, 분석 releaseId/이관 targetReleaseId/작업 종류·상태 합의는 별도다. 분석 계약을 설문 세 필드로 축소하거나 미합의 매칭 계산을 활성화하지 않았다.

기존 [통합 문서 검증](api-erd-unified-20261022.md)과 [웹10 API 검증](notion-api-ten-20261022.json)은 당시 별도 문서 작업의 근거다. 이전 문서의 원격 구현 미확인·서버 테스트 미실행 표시는 당시 범위로 읽으며 현재 소스/빌드 상태는 이 기록을 따른다. 노션·ERDCloud는 이번 로컬 통합에서 편집하지 않았다.

## 현재 상태

로컬 작업본의 통합·검증까지 완료했다. HEAD는 기존 커밋이며 커밋 전 통합 상태를 남겼다. 이 변경은 원격 MR !29에 아직 반영되지 않았으므로 기존 CI/충돌 표시와 구분해야 한다. 새로운 원격 CI·팀 승인·최종 병합 검증은 수행하지 않았다.
