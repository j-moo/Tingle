# 설문 회차 API·ERD 검증 및 작업 상태

2026-10-22. 목적: S15P31E106-61의 요청 변경 1~3을 통합 명세에 반영하고 분석 전 합의 사항을 분리한다.

종료 전 원격 재확인에서도 develop 해시가 같고 61번 브랜치는 없었다.

기준 develop: `fbe0f352d0a013b8aaebf0623c3e9f6a0d5080c8`. 작업 폴더는 `worktrees/survey-session-contract-20261022`, 로컬 브랜치는 `be-docs/survey-session-contract-S15P31E106-91`이다. 다른 채팅의 `spec-alignment-20261021`에는 작업하지 않았다. 기능 명세·매칭·백엔드 소스·기존 인계 스냅샷은 변경하지 않았다. 커밋·푸시·병합·MR 리뷰어 변경·리뷰 요청·메시지 전송을 하지 않았다.

## 확인·변경

- 원격 fetch 및 전체 heads 조회에서 설문 61번 구현 브랜치가 없었다. develop에도 설문 도메인 구현 파일이 없어 요청/응답 DTO·JPA·실행 결과는 미확인이다.
- CreateSession의 surveyVersion, 별도 SurveyManifest, sessionId=public_id를 OpenAPI·설명·ERD·SQL·metadata에 반영했다. 공유 Manifest의 필수값은 유지했다.
- 변경 전 canonical SQL은 24개·167개 컬럼, import/metadata는 24개·165개였다. canonical과 develop User/Profile에 이미 존재하는 email/completed_at을 import/metadata에 보완하고 public_id 하나를 추가해 세 자료를 24개·168개로 맞췄다.
- 분석/이관 UUID와 분석 작업 종류·상태 불일치는 [합의 항목과 권장안](../contracts/api/survey-sessions.md)에 기록했다. analysis_results/background_jobs DDL과 API 상태 enum은 변경하지 않았다.
- 기존 Questionnaire 예시의 questionCount=1·questions=[] 불일치를 가상 1문항 예시로 바로잡고 실제 100문항 버전과 명시적으로 구분했다. DRAFT의 completedAt 예시도 null로 정리했다. 실제 catalogue는 수정하지 않았다.

## 검증 경계

OpenAPI 표준 validator PASS, 예시 841건·내부 참조 780곳·잘못된 요청 7종 PASS, catalogue 100문항 검사 PASS. PostgreSQL SQL parser, SQL/import/metadata의 24개 테이블·168개 컬럼/타입/nullable/UUID 제약, Mermaid 컬럼 대조, 논리 참조와 로컬 링크 46곳 모두 PASS다. 검증 도구와 일시 출력은 저장소 밖의 현재 채팅 output에 둔다.

공용 DB에 접속하거나 SQL을 실행하지 않았다. 일회용 PostgreSQL 실행·JPA validate·서버 빌드/실행·FE 연결·배포를 이번 검증으로 주장하지 않는다. 설문 JPA 구현이 없으므로 생성·제약·기존 행 backfill 검증은 진행할 수 없다.

## 웹 상태

노션 connector는 대상 페이지 404를 반환해 내부 브라우저로 대조했다. 노션 회차 생성 페이지에서 요청 필드·예시와 sessionId·manifest 설명을 수정하고 저장된 본문을 읽어 확인했다. 설문·분석 관련 총 13개 페이지의 현재 설명을 대조해 공개 회차 ID/설문 최소 manifest를 반영하고, 분석·이관 UUID 대응 및 작업/결과 상태 불일치는 미합의 표시했다. 이관/분석 요청 필드와 enum은 유지했다. 수정 직후 본문 readback을 확인했고 회차 생성 페이지는 재로드 후 변경 이력과 요청 예시의 저장도 확인했다. 개발 현황·담당자·Jira·Sprint는 변경하지 않았다.

ERDCloud `TINGLE_ERD`는 재시도에서 엔티티 목록을 통해 접근했다. 변경 전 PostgreSQL 내보내기는 24개 테이블·166개 컬럼이었다. public_id를 추가한 뒤 재로드하고 다시 내보낸 SQL에서 UUID·NOT NULL·UNIQUE·DEFAULT gen_random_uuid()를 PostgreSQL parser로 확인했다. 현재 웹 ERD는 24개·167개이며, 기존 컬럼·다른 테이블·ALTER 제약은 이전 내보내기와 동일하다. ERDCloud에 별도 UNIQUE 속성이 없어 Type에 `uuid UNIQUE`를 명시했고, 내보낸 SQL이 실제 uuid 타입과 UNIQUE 제약으로 파싱됨을 확인했다. 공용 DB에는 실행하지 않았다.

사용자가 제공한 [프로젝트 노션 ERD](https://app.notion.com/p/ERD-401c91ccb660802ba0e7feb95483fcee)도 내부 브라우저에서 대조했다. survey_responses를 7→8개 컬럼으로 수정하고 public_id 제약·sessionId 대응·surveyVersion/version_code 고정 설명을 반영했다. 전체 Markdown 내보내기의 실제 컬럼 행을 재집계해 25개 테이블·184개 컬럼을 확인하고 총계를 183→184로 정정했다. 다시 내보낸 문서에서 총계·8개 설문 컬럼·public_id 계약의 저장을 확인했다. 다른 파트·채팅 작업의 추가 설계를 삭제하거나 24개 테이블 기준으로 덮어쓰지 않았다.

## 남은 작업과 다음 행동

이번 설문 회차 범위의 웹 반영·저장 검증은 완료했다. 저장소의 설문 8개 컬럼과 노션·ERDCloud 설문 8개 컬럼이 일치한다. 전체 모델은 서로 다른 작업 기준이므로 전체 일치로 표시하지 않는다.

| 자료 | 테이블 | 컬럼 | 확인 범위 |
| --- | ---: | ---: | --- |
| 이 작업 폴더 SQL/import/tables.json | 24 | 168 | 앞선 정적 검증 유지 |
| ERDCloud 재로드 후 PostgreSQL 내보내기 | 24 | 167 | public_id 제약 저장 확인. push_installations.login_family_id는 웹에 없으며 이번 범위에서 보존 |
| 프로젝트 노션 ERD 최종 Markdown 내보내기 | 25 | 184 | 실제 컬럼 행 재집계. 제안·채팅·읽음·푸시·작업의 별도 추가 설계 포함 |

미확인 사항은 원격 설문 구현의 DTO/JPA 생성·제약·기존 행 처리다. 분석 releaseId·이관 targetReleaseId 및 ANALYSIS/작업·결과 상태 합의도 그대로 남는다. 기존 작업 폴더와 다른 영역의 코드는 건드리지 않았고 커밋·푸시·병합·리뷰 요청·메시지 전송을 하지 않았다.

다음 행동 하나: 원격 설문 61번 구현이 공유되면 DTO·JPA·기존 행 처리를 변경 계약과 대조한다.

검증 숫자는 [기계판독 기록](../verification.json)의 survey_session_61_review에도 저장했다. 다른 영역의 과거 검증 필드는 이번 작업의 실행 증거가 아니다.
