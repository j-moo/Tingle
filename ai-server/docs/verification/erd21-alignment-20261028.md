# ERD21 정합화 검증

2026-10-28 / 김영빈 담당 설명 정합화 / 서버·JPA·공용 DB 적용 아님.

사용자 제공 2026-10-27 DBML의 실제 정의는 21개 표·151개 컬럼·21개 enum·35개 논리 참조다. 원본 첨부는 읽기 전용으로 보존했다. 기존 28개 정의가 남아 있던 tables.json·schema.sql·ERDCloud 도식용 SQL·Mermaid 4개·erd.md 현재 목록을 같은 21개 정의로 맞췄다. 다른 담당의 구조는 사용자 원본 그대로이며 후속 SQL에서 구체화된 설정 버전/ACK 설명과 담당 영역의 제안 시각·원본 삭제 세대·본인 수신 메시지 표시 설명만 보완했다.

`docs/database/generate_erd21.py <원본 DBML 경로>`로 재생성한다. `verify_erd21.py`는 원본 구조·메타데이터·PostgreSQL 구문·Mermaid 컬럼/관계·ERDCloud 도식용 구조·Markdown 표를 대조한다. PostgreSQL 파서는 로컬 pglast를 사용하며 DB에는 접속하거나 SQL을 실행하지 않는다. [기계 판독 검증](erd21-alignment-20261028.json)을 따른다.

물리 FK를 일괄 승인하지 않았다. schema.sql은 논리 참조 주석으로 관계를 기록한다. erdcloud-import.sql의 FOREIGN KEY는 도식 에지 가져오기용이며 실제 PostgreSQL 실행 파일이 아니다. 신고된 채팅을 보관하면서 회원/matching을 삭제하는 순서·잔존 참조는 합의 전이다.

재매칭 제한·즉시 탈퇴·신고 보관 정책의 기존 기능 명세와 차이, 만남 재사용 세대/eventId의 영속 저장, AI 실행 ID·5분 기산점·최대 횟수의 의미, 누락된 동의/작업/입장·종료 안내 저장 대안은 erd.md의 미해결 계약으로 남겼다. 기획·기능·정책·UX 원문은 변경하지 않았다.

웹 반영도 기존 문서에서 저장·재열기 검증을 완료했다. 내부 브라우저만 사용했다. [기존 노션 ERD 설계안](https://app.notion.com/p/ERD-401c91ccb660802ba0e7feb95483fcee)의 담당 11개 현재 테이블(설문 두 표는 한 native 표로 표시), 10개 native 표의 현재71개 필드와 모든 설명 셀을 reload 후 예상값과 정확히 대조했다. personality·chat_message·chat_report·chat_safety_notice의 이전 정의 이력4행은 ‘현재 없음’으로 구분했다. settings_snapshots는 personality의 이력 행/설명으로, 그 밖의 제거된6개 표는 ‘이전 설계 보관(현재 표 없음)’으로 구분했다. 다른 담당의01·02 및 푸시·운영·지원 표는 보존했다. 현재 기준·관계도 링크·JSON/ID/FK 공통 메모도 수정했다.

[기존 ERDCloud Tingle_erd](https://www.erdcloud.com/d/62ajQmkQWgGCL2MTn)는 재열기 후21개 기존 엔티티 이름을 확인했다. 구조 변경 없이 matching.proposed_at·matching.raw_delete_at의 담당 설명을 보완해 SAVE한 뒤 다시 열어 정확한 값을 확인했다. 동일 다이어그램에 담당 정합화·합의 전 항목 메모1개를 추가했고 reload 뒤863자 내용의 정확한 일치를 확인했다. 새 다이어그램·노션 복제본은 만들지 않았다. ERDCloud 전체151개 컬럼을 웹에서 재입력하거나 실DB 구조를 검증했다는 뜻은 아니다.

노션 저장 증거는 프로젝트 tmp/erd21-notion-overview-saved.png와 erd21-notion-matching-saved.png, ERDCloud 저장 증거는 tmp/erd21-erdcloud-entities-saved.png·erd21-erdcloud-proposed-at-saved.png·erd21-erdcloud-raw-delete-saved.png·erd21-erdcloud-memo-saved.png다. 브라우저 표 입력 직후 DOM 값만 맞는 상태는 완료로 취급하지 않았고, reload에서 발견한 입력 누락·중복은 고친 뒤 전체 셀을 다시 확인했다. 웹 저장 증거와 정적 artifact 구조 검증, 서버/JPA/실DB 적용은 별도다.
