# Suddenly I See 설문 담당자 인계

2026-10-21 / 관련 Story S15P31E106-41. 수신: 설문 화면·응답 저장·성향 분석을 이어받는 FE·BE·AI 담당자.

현재 프로젝트에는 명세와100문항 JSON이 있고 서비스 설문 기능은 아직 구현되지 않았다. 별도 공개v15 목업은 실행 가능한 참고 구현이다. 이 묶음은 최신 명세, 목업 원본, 미결정 정책과 추천안, 실행 방법을 한 위치에서 전달한다. 실제 앱·서버·DB·키링 코드는 수정하지 않는다.

## 처음 보는 순서

1. 이 문서에서 구현 경계와 인계 상태를 확인한다.
2. [미결정 정책과 추천안](POLICY-DECISIONS.md)을 읽는다. 추천안은 아직 승인되지 않았다.
3. [100문항 정의서](specs/docs/survey-analysis/personality-survey-v1.md)와 [문항 JSON](specs/docs/contracts/survey/questionnaire-SIS-Q-1.1-100.json)을 확인한다.
4. [계산·16유형·개인 분석 정의서](specs/docs/survey-analysis/personality-scoring-v1.md)를 읽는다. 개인 유형, 참고 유형, 실제 두 사람 매칭을 구분한다.
5. [목업 실행 방법](mockup/README.md)을 따라 실행하고 예시 결과·파트 전환·파일 비교를 살펴본다.
6. [API](specs/docs/contracts/api/README.md), [OpenAPI](specs/docs/contracts/api/openapi.json), [버전 계약](specs/docs/contracts/survey/versioning.md), [ERD](specs/docs/database/erd.md), [SQL](specs/docs/database/schema.sql) 순서로 서비스 구현 계약을 확인한다.

## 담당별 시작점

| 담당 범위 | 먼저 볼 자료 | 이어서 할 연결 |
| --- | --- | --- |
| FE | 문항 JSON, app100 화면 흐름, API 예시 |100문항 표시·검증, 임시 저장·복원, 완료·정보 부족 상태 |
| BE | 설문12개 API, 버전 계약, survey_responses·analysis_results SQL | 회차·revision·완료 불변성·이관, 가입 단계·분석 요청 연결 |
| AI·계산 | 계산 정의, engine100·initial-profiles·type-recommendations100, 네 검증 스크립트 | 규칙 버전별 재현, 개인 분석과 실제 쌍 판정 분리 |
| PM·영향 파트 | 미결정 정책 D01~D10 | 나이·생활조건·중복 입력·필드 호환·적용 범위 합의 |
| IOT | 합의된 매칭·버전 계약, 기존 IoT 계약 | 실제 기기 입력·동기화·구버전 처리. 목업 통과는 실기기 증거가 아님 |

## 실제 저장소와 목업의 경계

기준 develop은 0b1ddde6964aad5156c51e7bf50169f03e62d1c1이다. 원격 확인 시점에 문항·계산 명세, 설문·분석12개 API 계약, 응답·분석 SQL은 develop에 존재한다. Survey Controller·Service·Entity·Repository, 계산 실행기, 앱 설문 화면, 키링 연동은 확인되지 않았다. AI 서버는 health 확인용 초기 구성이다.

로그인45번 커밋4f691085에는 SURVEY_REQUIRED가 있지만 OnboardingService는 약관·프로필만 확인하고 READY를 반환한다. 설문 완료·동기화 검사는 후속 연결이다. 제안43번 구현은 제안 조회·수락·거절이며 매칭 점수를 계산하거나 후보를 고르는 엔진이 아니다.

목업은 이 폴더의 mockup/dist에서 브라우저 내부 계산·저장으로 동작한다. 코드 위치를 프로젝트에 전달하는 것이며 서비스 back/front/ai-server/iot에 통합하거나 배포한 것이 아니다. 새 정책을 계산에 반영하지 않았다.

## 위치와 게시 상태

- 실제 저장소의 로컬 폴더: docs/handoffs/survey-20261021. 시작 파일은 이 README.md다.
- 게시 브랜치: doc-docs/survey-handoff-S15P31E106-41. 사용자가 기존 로컬 브랜치 사용과 인계 폴더만의 커밋·푸시·MR 생성을 승인했다. 병합은 검증·리뷰 조건 충족이 전제다.
- [GitLab 인계 폴더](https://lab.ssafy.com/s15-final/S15P31E106/-/tree/doc-docs/survey-handoff-S15P31E106-41/docs/handoffs/survey-20261021)에서 받는다. develop 반영 여부는 MR의 실제 병합 상태로 확인한다.
- specs/docs는 2026-10-21 인계용 명세 스냅샷이다. 별도로 계속 관리하는 공식 명세가 아니며, 이후 정책 확정은 저장소의 공식 docs에 반영해야 한다.
- 스냅샷은 develop 0b1ddde 기준 문서에 이번 대화에서 정리한 설문 관련 문서 5개의 미커밋 편집본을 포함한다. 파일별 해시는 SOURCE-MANIFEST.json에 있다.
- 기존 실제 저장소의 다른 docs 파일은 덮어쓰지 않았다. 이 폴더만 복사해도 명세와 목업을 함께 읽을 수 있다.
- [공개 체험](https://suddenly-see-heart-e106.rusidpia.chatgpt.site)은 기존 별도 목업이다. 이번 폴더를 게시했다는 뜻이 아니다.
- [브랜치 선택 및 게시 안내](BRANCH-STATUS.md)를 참고한다. 다음 단계는 MR에서 필요한 리뷰와 CI 근거를 확인하는 것이다.

## 확인 상태와 주의점

- [검증 기록](VALIDATION.md)과 [원본 파일 해시](SOURCE-MANIFEST.json)를 함께 전달한다.
- 현재 문서의 정책 추천은 확정안이 아니다. 설문 버전·계산·응답은 유지했다.
- 문서의 answers_json과 SQL의 response_json 이름 차이는 구현 전에 합의한다.
- 연구 척도 참고와 자체100문항·16유형의 신뢰도·타당도 검증은 별개다.
- Jira41은 진행 중이며 contract 라벨이다. 기본 검토자 이재준, 영향 FE·BE·AI 승인과 작성자가 아닌 팀원의 병합이 필요하다. 새 담당 배정·이슈 완료 전환은 하지 않았다.
- 기존 v14 PDF와 과거72/80문항 원문은 역사 자료다. 현행 기준은 문항 SIS-Q-1.1-100, 공개v15, 참고 유형 SIS-TYPE-REF-1이다.
