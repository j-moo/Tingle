# 브랜치 선택 및 게시 안내 — 2026-10-21

사용자가 기존 로컬 브랜치 doc-docs/survey-handoff-S15P31E106-41 사용을 승인했다. 이 브랜치에서 docs/handoffs/survey-20261021 폴더만 커밋하여 develop 대상 MR로 전달한다. 기존 서비스 코드와 다른 docs 파일의 미커밋 편집은 포함하지 않는다.

- [브랜치의 인계 폴더](https://lab.ssafy.com/s15-final/S15P31E106/-/tree/doc-docs/survey-handoff-S15P31E106-41/docs/handoffs/survey-20261021)
- [프로젝트 MR 목록](https://lab.ssafy.com/s15-final/S15P31E106/-/merge_requests): 제목의 S15P31E106-41로 확인한다.
- 대상 develop 확인 커밋: 0b1ddde6964aad5156c51e7bf50169f03e62d1c1. 게시 및 병합 상태는 GitLab 화면이 기준이다.

기존 원격 문서 브랜치 API·ERD 38번과 기능 명세 16번은 다른 이슈 범위다. 이슈별 브랜치 분리 규칙에 따라 재사용하지 않았다. 실제 원본 저장소의 열려 있는 브랜치는 전환하지 않는다.

## 병합 조건

기본 리뷰와 contract 영향 파트 리뷰가 필요하다. [GitLab 규칙](../../../../docs/convention/gitlab-convention.md)의 mr-approval에 따라 작성자는 본인을 승인하거나 자신의 MR을 병합하지 않는다. CI가 없거나 필요한 검사가 누락된 상태를 성공으로 취급하지 않는다. 충돌 없음·로컬 검증 성공·CI 성공·승인·병합 완료는 각각 별도로 확인한다.
