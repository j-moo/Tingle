# 설문·계산·분석 버전 파일 계약

1.2.0-survey-review / 수정 2026-10-22. 독립 관리자 게시 DB/API를 전제로 한 기존안을 파일 기반 운영으로 수정했다. 문항100개와 계산 원문은 이번 작업에서 변경하지 않는다.

[최신 문항 catalogue](questionnaire-SIS-Q-1.1-100.json) / [설문 원문](../../survey-analysis/personality-survey-v1.md) / [계산 원문](../../survey-analysis/personality-scoring-v1.md) / [API](../api/README.md) / [ERD](../../database/erd.md)

## 현재 수정안

1. 문항·선택지·표현·채점·유형·분석·참고 추천·매칭·제품 정책·선택 이관 규칙을 함께 실행 가능한 불변 파일 묶음으로 배포한다. SIS-Q-1.1-100 등 기존 원문 버전 코드를 유지한다. 수정은 새 version_code로 구분한다.
2. 서버 배포에 현재 파일과 기존 회차/결과/스냅샷의 재현에 필요한 과거 파일을 함께 포함한다. 기존 파일을 덮어쓰거나 최신 문항을 과거 응답에 적용하지 않는다.
3. 설문 회차는 survey_responses(response_id,public_id,user_id,version_code,response_json,revision,completed_at)로 저장한다. 같은 사용자/버전의 진행 중 회차는 하나이며 완료 후 원응답은 불변이다.
4. 개인 분석은 완료 원응답+version_code로 식별하고 analysis_results에 outcome/result_json을 저장한다. 실제 쌍 매칭은 encounters의 판정/버전과 당시 settings_snapshots로 재현한다.
5. CreateSession은 releaseId 대신 surveyVersion 문자열(예: SIS-Q-1.1-100)을 받는다. version_code로 불변 설문 파일을 고정하고 DB release 행을 요구하지 않는다. 외부 sessionId는 public_id UUID(NOT NULL·UNIQUE·DEFAULT gen_random_uuid())이며 내부 response_id는 유지한다. 설문용 SurveyManifest는 surveyVersion·wordingVersion·contentHash만 제공한다. 분석용 전체 Manifest의 필수값은 유지하며 규칙 파일이 생긴 뒤 후속 계약을 합의한다.
6. 문항별 저장은 미완료+revision 조건부 갱신으로 경합을 막는다. 분석 요청의 sessionRevision은 연결된 불변 완료 회차와 비교하고 결과 행에 중복 저장하지 않는다.
7. 새 버전 이관은 명시적 호환 규칙·사용자 확인→새 응답 회차 생성이다. 원본·결과·키링 사본은 보존한다. legacy80→100 임의 자동 매핑을 하지 않으며 미지원 규칙은 MIGRATION_UNSUPPORTED로 처리한다.

## 파일 검증·변경 검토

문항ID·선택 타입/범위·중복·unknown/any·참조·대표값·결측·동점·점수/coverage 경계·유형/참고 추천 분리·입출력 사례를 검증한다. 코드/SQL eval이나 임의 파일·네트워크 접근은 허용하지 않는다. 파일 운영도 MR 검토와 재현 가능한 회귀 사례가 필요하다.

다음은 서로 구분한다: 개인 점수, 개인16유형, 원응답 기반 해석, 선호와 자기 성향의 차이, 참고 추천 유형, 실제 두 사람의 양방향 매칭. 개인 유형 정의만으로 두 사람 궁합을 확정하지 않는다. 생활조건·Q97 충돌 등 미정 제품 정책은 그대로 확인한다.

## 기존안과 현재 범위에서 제외한 항목

기존안은 rule_artifacts/rule_releases/release_artifacts 및 관리자 초안·검증·승인·게시·current 전환/롤백이었다. 현재는 해당 테이블과 관리자9개 endpoint를 구현 대상으로 요구하지 않는다. 필요가 확인되면 권한·버전 영속성·승인 계약과 함께 별도 설계한다. [API 변경 이력](../api/changes-20261021.md)에 기존 경로와 영향이 남아 있다.

## 확인할 사항

- 불변 파일 묶음 형식·배포 위치·호환 releaseId 매핑은 아직 최종 구현 계약이 아니다. 현재 catalogue 하나만으로 모든 계산/정책 파일이 완성됐다고 보지 않는다.
- 서버 구현·실제 파일 배포·과거 입력 재계산·기기 동기화 시험은 별도다. 이번 작업은 문서·schema 정리와 정적 확인이다.

## 2026-10-22 확인 범위

1~3번은 사용자 전달 요청으로 반영했다. 최초 작성 때는61번 구현이 없었으나, 후속 통합에서 MR !57의 `629ed04a5cd6ef70f62f79b2050e60bf8d5b6a2d`를 읽어 surveyVersion·세 필드 Manifest·공개 UUID 생성/제약 선언을 확인했다. 설문 코드를 이 브랜치에 합치거나 런타임·기존 행 이관을 검증한 것은 아니다. 분석 releaseId·이관 targetReleaseId, 작업 상태 저장 위치와100자 상한 검증 차이는 [설문 회차 계약과 합의 항목](../api/survey-sessions.md)을 따른다.
