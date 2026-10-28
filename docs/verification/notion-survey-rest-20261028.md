# 나머지 설문·분석 API 정합화 검증

2026-10-28 / 내부 브라우저 전용. 기존 페이지를 읽고 부분 수정했으며 새 노션 페이지를 만들지 않았다. 코드·JPA·실제 DB·기획/기능/정책/UX 원문·다른 담당 API와 속성은 변경하지 않았다.

## 기존 노션 7개 저장본

| 페이지 | 실제 확인 |
| --- | --- |
| [선택 나이 metadata](https://app.notion.com/p/3ffc91ccb6608036a4acd5fb6579f674) | 이미 제외. 설문 저장 칸을 만들지 않고 프로필·희망 조건으로 대체한 기존 문구 보존 |
| [응답 완료](https://app.notion.com/p/3ffc91ccb66080958025f66cb1090369) | 숫자 sessionId, 완료/분석 구분, 요청 본문·If-Match 없음, 200 SurveySession. 100개 답 중 3개 발췌 및 0 해시 설명을 보완하고 reload 후 저장 확인 |
| [이관 미리보기](https://app.notion.com/p/3ffc91ccb66080b9bc54d2bef655201f) | 숫자 sourceSessionId, targetSurveyVersion, 파일 policyHash, 세 문항 묶음. 읽기 전용 기존 표·예시 확인 |
| [이관 실행](https://app.notion.com/p/3ffc91ccb66080eda03fed57ae561afd) | targetSurveyVersion+policyHash 요청, 201 새 SurveySession, 원본 불변. 기존 draft 재사용 시 답 복사 여부의 합의 필요 표시 보존 |
| [분석 요청](https://app.notion.com/p/3ffc91ccb66080c49e86f1e2c43be061) | 본문 없음, 서버 파일 버전 선택, 202 AnalysisReceipt 4필드. 본인·버전·최신 실행 확인과 원자 포인터/설정 버전 전환, 재시도 미합의 경계를 보완하고 reload 후 두 문장 저장 확인 |
| [개인 결과](https://app.notion.com/p/3ffc91ccb6608029a62fd73c71663636) | 숫자 resultId/sessionId, 별도 AnalysisManifest 두 필드, 네 분석 상태와 nullable 결과/빈 배열. 내부 재시도 정보를 공개하지 않는 기존 표·예시 확인 |
| [공용 작업 조회](https://app.notion.com/p/3ffc91ccb660805aa405f6e09587a2b3) | 이미 제외. background_job 없이 반환된 resultId의 개인 결과 조회로 대체한 기존 문구 보존 |

이미 부모가 검증한 설문 6개는 재편집하지 않았다. 그 계약을 포함한 11개 활성 설문 operation과 제외 2개를 [통합 OpenAPI](../contracts/api/openapi.json)에 반영했다. 공유 Manifest 전체 정의는 유지하며 별도 AnalysisManifest를 사용한다. 검사 수치와 보존한 관리자 목록은 [기계 검증 기록](notion-survey-rest-20261028.json)을 따른다.

## 관리자 9개 보존

규칙 생성/조회/수정, 릴리스 생성/조회/검증/게시/승인, current 전환·롤백 9개 모두 기존 페이지의 담당자 `가을`, 개발 현황 `제외`를 확인했다. 타담당·제외 조건에 따라 `/admin/v1` 경로와 설계 이력을 그대로 보존했으며 활성 API로 재도입하지 않았다. 과거 24개 설계 문구는 현재 21개 계약의 근거로 사용하지 않는다.

## 미확정·검증 범위

- 기존 동일 버전 미완료 회차 재사용 시 원본 답 복사 여부는 합의 전이다.
- AI 정체 5분 기산점, 최초 실행 포함과 최대 실행 횟수, 실행/입력 식별·선점·늦은 결과 차단 기준은 합의 전이다.
- 결과 저장·유효 프로필 적용·설정 버전 증가의 원자성은 구현해야 할 계약이며 서버 실행으로 검증한 상태가 아니다.
- 문서와 노션 저장본의 정합성·스키마/예시/참조만 검사했다. 서버 빌드·실제 파일 배포·기존 행 이관·공용 DB·배포는 검증하지 않았다.

## 증빙

연결 복구 후 수정한 두 페이지를 reload해 본문을 다시 읽었다. 스크린샷은 작업 폴더 `tmp/notion-survey-complete-rest-final.png`, `tmp/notion-survey-analysis-rest-final.png`에 저장한다. 저장소 파일은 지정된 설문 계약 문서 두 개와 이 검증/변경안에 한정한다. staging·commit·push는 부모가 통합 범위 확인 후 수행한다.
