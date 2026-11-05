# API·DB 정합화 및 웹 반영 기록

2026-10-22 후속: 최신 develop `ea74cc5`·MR !26 `6217093` 반영과 AI 동의 철회 후 제안 권한 수정은 [MR26 정합화 검증 기록](mr26-alignment-20261022.md)을 따른다. 아래 165컬럼·118 tests 기록은 이전 시점의 증거로 보존한다.

기록일: 2026-10-22. 문서·도메인 정리 기준 Story는 S15P31E106-38이며, 제안43·인증45의 현재 계약을 함께 참고한다. 사용자 승인 기준의 적용·검증 기록이며 팀 전체 승인·MR 병합·배포 완료 기록은 아니다.

## 기준과 출처

| 대상 | 이번에 확인한 기준 |
| --- | --- |
| 백엔드 소스 | develop `1485656dd0b4c7777be704f1023c05caa4abb18c`에 로컬 정합화 수정 적용 |
| 공통 API | [OpenAPI](../contracts/api/openapi.json), 버전1.2.1-alignment-review, 활성68 API |
| 실행 DB 설계 | [schema.sql](../database/schema.sql)·[tables.json](../database/tables.json),24테이블165컬럼 |
| 노션 API | [API 명세서](https://app.notion.com/p/API-4a2c91ccb660820793de81b259578314),78상세 페이지를 내부 브라우저에서 새로 Export |
| 웹 ERD | [TINGLE_ERD](https://www.erdcloud.com/d/6MtwxM7423r8qDiCu), 새로고침 후 Export13번24테이블165컬럼 |
| 기능 명세 | 작성 중 자료로 구분하여 이번 수정 대상에서 제외. 이 기록은 이후 기능 명세 변경을 승인하는 문서가 아님 |

노션78페이지는 활성68 API와 기존안/제외10페이지의 집계다. OpenAPI 제외 항목11개와 집계 대상이 다르므로 페이지 수를 endpoint 수로 사용하지 않는다. 신규 비교는 HTTP method+path를 키로 한다. 다운로드의 `(13)`은 로컬 중복 파일 번호이며 문서 버전이 아니다.

## 사용자 승인으로 정리한 기준

상세 계약은 [정합화 적용 기준](../contracts/api/alignment-20261021.md)과 해당 OpenAPI/정책을 따른다.

| 항목 | 적용 내용 |
| --- | --- |
| 닉네임 | NFC 및 앞뒤 U+0020 정리 후 완성형 한글·영문·숫자·일반 공백2~20자. 독립 자모의 단독·혼합 입력 금지. 형식 오류 중복 확인은200 available=false/INVALID_FORMAT 유지 |
| 이메일 | app_users.email nullable text, 비고유 선택 연락처. PRIVACY 동의 후 로그인부터 갱신하며 제공되지 않으면null. 회원 식별은 provider+subject, 이메일은 응답에 넣지 않음 |
| 프로필 | nullable 응답 키 유지, unknown PATCH는400. 필수 업무 필드 명시null 거부, 종교·소개null은 비우기. completed 이후 성별·생년월일 변경은422, 같은 값은 허용 |
| 생활 정보 | 공통 OpenAPI의 ONLY_WHEN_DRINKING 및 긴 음주 전송 코드 유지 |
| 인증 | 현재 토큰6필드·sid=family·회전/유실 재시도·폐기 범위 유지. 자동 갱신은 ACCESS_TOKEN_EXPIRED만1회 |
| DB | 물리 FK 없음. 문자열 저장은DDL text로 맞추고 업무 길이는 검증 계층에서 제한 |
| 제안 | DB와 동일한 양수 숫자 proposalId 하나. UUID Idempotency-Key와 최초 실제 선택 키2컬럼, 최초 시각·재시도 정책 유지 |

## 확인된 변경과 기존 구현의 구분

- 생활 정보 코드·nullable 응답·unknown 거부·실제 가입 게이트·401 분기는 기준 develop에 이미 존재했다. 이를 이번에 새로 구현했다고 기록하지 않는다.
- 이번 백엔드 변경은 User/Profile/UserConsent의 text 매핑, 프로필 잠금 설명·오류 문구 정렬, 닉네임 자모 회귀와 생성 스키마 검증 보강이다. 닉네임 구현이 이미 거부하던 자모를 OpenAPI와 회귀 테스트에도 맞췄다.
- email은 schema.sql에 이미 있었으며 메타데이터·가져오기 SQL·웹 ERD를 따라 맞췄다. 기존 공유 DB에 스키마를 실행하지 않았다.
- 노션40개 요약과 별도 닉네임 기준을 저장 확인했다. 현재 본문17문단 및 약관 응답·로그아웃 설명을 정리했고,78페이지 속성·변경 이력은 보존했다.
- 웹 ERD에는 이메일·최초 선택 키2컬럼·희망 성별 배열 타입을 반영했다. 주석의 잘못된 선행 따옴표59곳을 정리했다. 초기 빈 화면은 도식 바깥 뷰포트였으며 미니맵/테이블 목록 이동으로 복구했다.
- Upload Snapshot은 파일 선택 뒤에도 무반응이었다. 기존 필드 편집창으로 준비 파일과 동일한 변경을 적용했다. 업로드 기능 자체를 고쳤다고 주장하지 않는다.

## 검증 증거

| 검사 | 결과와 한계 |
| --- | --- |
| 백엔드 전체 build | 2026-10-21 검증한 동일 소스:118 tests, 실패0·오류0·skip0. 일회용 Testcontainers PostgreSQL17·컴파일·Checkstyle main/test·bootJar 포함 |
| 현재 소스 동일성 | 커밋 준비 시 back/ diff가 당시 보존한 검증 patch와 동일함을 확인. 문서 기록만 추가하여 전체 테스트를 반복하지 않음 |
| OpenAPI/SQL | OpenAPI 검증 및 canonical/import PostgreSQL SQL 파싱 통과 |
| 노션 저장 | 새 전체 Export에서78페이지 확인, 수정 요약 중복/누락0, 현재 본문 수정 저장 확인, 속성·이력 변경0 |
| ERD 저장 | 새로고침 후24/165. 준비 파일의 이름·타입·nullable·기본값·설명 차이0. 기존162필드 ID·관계 및24테이블 ID·좌표 변경0 |
| ERD SQL | PostgreSQL·PK포함·FK/Drop/선택한테이블만 옵션 제외로 Export.24 CREATE TABLE·165컬럼·물리FK0, 파싱 성공. canonical SQL 컬럼 타입·nullable 차이0 |

아래 증거는 2026-10-22의 고정된 비교 기준이다. 새 기능의 현행 명세로 별도 복제 관리하지 않는다.

- [이전 검증 요약 원문](spec-alignment-20261022/previous-verification.json): 당시162컬럼·인증/노션 미동기화 상태의 과거 기록이며 현재 상태와 구분
- [기계판독 검증 결과·파일 해시](spec-alignment-20261022/verification.json)
- [노션 API별 출처·본문 해시](spec-alignment-20261022/notion-api-index.json)
- [웹 ERD 정규화 스냅샷](spec-alignment-20261022/erdcloud-snapshot.json): 원본에서 작성자/썸네일 메타데이터를 제외한 설계 증거이며 업로드용 파일이 아님
- [당시 PostgreSQL Export](spec-alignment-20261022/erdcloud-postgresql.sql): 검증 증거이며 실행/마이그레이션 지시가 아님. identity/default/unique/check/index의 실행 기준은 canonical schema.sql

## 남은 범위

- 실제 앱의 새 토큰·401 처리, 실제 소셜 공급자의 이메일 제공/동의, 실기기 송수신·배포·공용 DB 적용은 미검증이다.
- 가입 상세의 설문 진행도·설정 버전 필드는 임시값이다. 채팅방 생성 후속 작업도 미구현이며 현재 chatAvailable=false/chatRoomId=null이다.
- 키링 증명·사진 저장소/전달/삭제·다른 도메인의 ID 매핑 등 기존 미합의 계약은 이 정합화로 확정하지 않는다.
- 원격 최신 커밋/CI/리뷰/승인은 이번 로컬 기록 커밋의 검증 범위가 아니다. 푸시·MR·병합 전 현재 대상 기준으로 다시 확인한다.

## 기능 명세가 바뀌었을 때의 다음 대조 절차

1. 작업 폴더 지침과 변경 상태를 확인하고, 현재 GitLab source/target commit 및 노션 기능/API 페이지 수정 시점·상태를 새로 기록한다. 이 문서의 develop 해시를 최신이라고 가정하지 않는다.
2. 로컬 문서만 보지 않고 최신 노션 API와 ERDCloud Export도 확보한다. 기능 명세는 작성 중/승인된 변경을 구분하고 기존 확정 기준과 충돌하는 정책을 먼저 표시한다.
3. method+path별 요청/응답/필수 헤더/오류/전송 코드, table+column별 타입/nullable/제약, 실제 백엔드 동작을 비교한다. 과거 설계, 현재 구현, 실제 연동 증거를 따로 기록한다.
4. 사용자 승인 기준의 단순 문서 누락과 새 정책 결정을 구분한다. 새 기능 명세만으로 기존 승인 정책을 소급 변경하지 않고 호환 영향·관련 파트·적용 순서를 확정한다.
5. 승인된 변경만 공통 계약·DB 설계·백엔드·웹 원본에 반영하고 영향 테스트/빌드 및 새로 읽은 웹 저장본을 검증한다. 변경일별 새 기록과 이전 기록 링크를 추가하며 이 고정 증거를 덮어쓰지 않는다.

다음 대조는 새 기능 명세와 위 확정 기준의 차이를 확인하는 단계부터 시작한다. 이번 커밋에 기능 명세 원문·과거 핸드오프·실행 환경 비밀값은 포함하지 않는다.
