# API·ERD 통합 작업 상태

2026-10-22. 사용자 승인: 별도 작업 폴더에서 API·ERD·노션·ERDCloud 정합화 및 검증. 기존 작업 폴더 변경, 기능 명세/매칭/백엔드 수정, 커밋·푸시·병합·리뷰 요청·메시지 전송·공용 DB 적용은 제외한다.

## 기준과 확인된 결정

- 최신 develop 16e5ede84c1cef75642c594843d6761ec5f59cd6에서 새 worktrees/api-erd-unified-20261022를 만들었다. 기존 두 작업 폴더에는 쓰지 않았다.
- 다른 채팅의 문서 소스는 4f7dc2e5952c83a25f12d10dcabc9a107edda6a0 커밋을 고정해 읽었다. 미푸시 상태를 완료/병합 상태로 해석하지 않는다.
- 기존 25/184는 채팅/안내 설계 25/183 + 설문 public_id 1개다. 새 develop에 keyrings.public_device_id가 추가됐으므로 이를 보존한 현재 통합 기준은 25/185다. 새 키링 계약을 빼서 숫자를 맞추지 않는다.
- 설문 CreateSession은 surveyVersion, 설문 전용 최소 SurveyManifest, sessionId=survey_responses.public_id이며 내부 response_id를 유지한다. 공유 Manifest의 전체 필수값은 보존한다.
- 분석 releaseId/이관 targetReleaseId, ANALYSIS job_type, AnalysisResult.FAILED와 작업·결과 상태의 불일치는 합의 전 유지한다. 권장안은 survey-sessions.md에 있다.

## 완료와 검증 경계

SQL/import/tables.json/ERD/OpenAPI/API 설명에 두 문서 변경과 최신 키링 계약을 합쳤다. Accept-Language 공통 매개변수와 설문 UUID 설명도 함께 보존했다. 정확한 실행 결과는 unified-verification.json을 따른다. 다른 채팅의 과거 실행 기록은 그 커밋 당시의 증거이며 현재 브랜치 서버 검증으로 재사용하지 않는다.

설문 61번 원격 구현 브랜치는 이번 원격 전체 heads에도 없었다. 요청/응답 DTO·설문 JPA UUID 생성/제약·기존 행 처리는 확인하지 못했다. 공용 DB·배포·실제 FCM/단말은 검증하지 않았다.

## 웹 저장 검증과 현재 체크포인트

완료: ERDCloud에 없던 제안 취소 4개와 keyrings.public_device_id 1개만 추가했다. 다른 채팅이 반영한 채팅·읽음·예약 작업·설치 컬럼을 보존했다. 행 옆 버튼의 관계 추가 동작을 한 번 되돌린 뒤, 기존 모든 컬럼과 내보낸 ALTER 제약이 작업 전 AST와 동일함을 확인했다. 기존 엔티티를 다시 가져오거나 전체 설계를 교체하지 않았다.

ERDCloud 재접속 PostgreSQL Export는 25테이블·185컬럼이다. canonical/import/metadata/메인 Mermaid와 전체 이름·타입·nullable이 일치한다. 설문 public_id의 UUID/NOT NULL/UNIQUE/DEFAULT gen_random_uuid(), 키링 public_device_id의 UUID/NOT NULL/UNIQUE 및 DB 기본값 없음도 확인했다. 실행용 identity/복합 UNIQUE/CHECK/index 전체를 ERDCloud 그림에 재현했다고 주장하지 않는다. 실행 제약의 기준은 schema.sql이다.

노션 ERD에는 keyrings.public_device_id 행, 5컬럼 표시, 0.2.6-unified-review/25테이블185컬럼 총계를 저장했다. 공식 Markdown Export의 실제 185개 행에서 저장소와 테이블·컬럼 이름/타입/필수 여부가 모두 일치했다. 기존 다른 표는 수정하지 않았다. 노션 API 공통 기준에는 설문 최소 Manifest/분석 공유 Manifest 분리, UUID/분석 미합의 사항, 71 API/25·185 총계를 반영했고 재조회했다. 다른 담당자의 개발 현황·담당자·Jira 속성은 수정하지 않았다.

OpenAPI validator PASS, 예시870건·내부 참조872곳·잘못된 CreateSession 요청7종·100문항 catalogue·로컬 링크63곳 검사 PASS. SQL parser/25·185/타입·nullable/공개 UUID 제약/논리 참조/메인 Mermaid 일치 PASS. 기능 명세·매칭·백엔드·컨벤션에 대한 변경은 없다. 정확한 결과와 증거 해시는 unified-verification.json을 따른다. 서버 빌드/테스트·공용 DB는 이 브랜치에서 실행하지 않았다.

작업 중 develop은 e59cbfb까지 갱신됐지만 시작16e5ede 이후 API/ERD 파일 변경은 없었다. 추가 docs 변경은 IoT 계약4개로 이 작업에서 재작성하지 않았다. 원격 설문 구현은 여전히 미확인이며 분석/이관/작업 상태 합의는 남아 있다. 새 작업 폴더의 HEAD는 시작 develop16e5ede 그대로이고 커밋·푸시·병합·리뷰 요청·메시지 전송을 하지 않았다. 기존 두 작업 폴더에는 쓰지 않았다.

## 남은 노션 10개 API 대조 완료

2026-10-22 후속 요청 범위를 완료했다. 다른 채팅이 저장한 방 목록·방 단건·메시지 조회/전송·개별 읽음·설치 등록/해제·로그아웃8개는 현재 노션 저장본을 직접 읽고 기존 반영을 보존했다. 새 읽음 항목을 중복 생성하거나 다른 페이지의 담당자·개발 현황·Jira 속성을 수정하지 않았다.

키링 조회·구매 안내2개에서는 현재 구역의 인증/응답/처리 규칙, 총6개 단락을 보완했다. KEYRING_REQUIRED·401/403·no-store, 네 필수 기기 필드와 공개 UUID, 상태 계산, 필수이면서 nullable인 purchaseUrl과 등록 필요 여부를 명시했다. 새로고침 후6개 단락의 저장 내용이 모두 일치했다. 기존 메타데이터와 변경 이력은 전후 해시가 동일하다.

OpenAPI의 Keyring.status에는 OFFLINE이 허용되지만 현재 서버는 REGISTERED/SYNC_PENDING/READY만 계산한다. enum을 임의로 줄이거나 새 동작을 확정하지 않고 현재 반환 경로와 미확정 관측/반환 정책을 노션·OpenAPI 설명에 구분했다. 키링 GET2개의403 설명·예시도 기존 구현의 ONBOARDING_REQUIRED로 맞췄다. 다른 채팅의 설치403 수정·읽음 링크·API 목록 행 보완도 이 별도 작업본에 반영했다. 그 기존 작업 폴더에는 쓰지 않았다.

10개 현재 계약에서 요청/응답/필수·nullable/권한/식별자/저장 규칙112개 항목, HTTP·경로와 필수 응답 키를 대조했다. 공통 문서의 미완료 표시를 이번10개 범위 완료로 바꾸고 재접속했다. 공식 내보내기 중 전송 API 파일이 조회(GET) 내용으로 반환된 건은 검증에서 제외하고 실제 POST 페이지의 현재 화면 DOM으로 대체 확인했다. 잘못된 내보내기를 성공 증거로 사용하지 않았다.

최종 OpenAPI 예시870개·참조872곳·잘못된 요청7종·catalogue100문항·SQL/metadata/ERD25·185·타입/nullable·UUID 제약·로컬 링크64곳이 통과했다. 상세 URL·증거 해시·검사 결과는 [10개 API 검증 JSON](notion-api-ten-20261022.json), 전체 경계는 [통합 검증 JSON](unified-verification.json)에 기록했다. 다른 도메인의 노션 개별 API 전체를 다시 검증한 것으로 확대하지 않는다.

요청한 노션 후속10개 범위의 남은 수정/저장 검증은 없다. 서버 빌드/실제 공급자·단말/공용 DB 실행/배포는 이번 문서 검증으로 주장하지 않는다. 분석/이관/작업 상태 합의와 원격 설문 구현·JPA·기존 행 처리는 종전 미확인 상태로 남긴다. 커밋·푸시·병합·MR/리뷰어 변경·메시지 전송은 하지 않았다. 기능 명세·매칭·백엔드 코드도 변경하지 않았다.

다음 행동 하나: 설문 구현이 공유되면 DTO·JPA·기존 행 처리 근거를 변경 계약과 대조한다.
