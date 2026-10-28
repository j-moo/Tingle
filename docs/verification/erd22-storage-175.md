# 22개 저장 이관 · 2026-10-28

승인한 `worktrees/erd22-storage-175`와 `be-refactor/erd22-storage-S15P31E106-175`에서만 진행한다. 최신 develop 567d340에 A151 d0498a8를 이력 보존 병합한 단위는 b8cb821이다. 충돌 없이 backend와 database 소스가 151과 동일함을 대조했다. 원본 151/176/70과 기획·기능 원문은 수정하지 않는다.

## 단위 1: 순수 JPA의 PostgreSQL 타입·인덱스 생성

- Java enum 이름과 SQL canonical 이름을 일치시켜 타입 생성·컬럼 선언·literal cast를 처리한다. 빈 개인 DB에서 canonical enum과 lower(nickname) unique를 먼저 준비하며 기존 enum 라벨/인덱스 정의가 다르면 임의 수선하지 않는다.
- Hibernate update가 auxiliary 객체의 DROP CASCADE를 호출하지 않도록 자동 enum drop은 빈 명령이다. create-drop은 테스트 표만 정리하고 enum은 보존한다. 일반 DDL 오류는 hbm2ddl.halt_on_error=true로 중단한다.
- 실제 검증에서 Hibernate auxiliary 실행의 quiet=true가 오류를 무시하는 공백을 재현했다. 따라서 순수 Hibernate에도 적용되는 Integrator/SessionFactoryObserver가 schema export 이후 실제 타입 라벨·인덱스 정의를 읽기 전용으로 재검사해 잘못된 update/validate 시작을 거부한다. SQL 로그만 보고 실패 차단을 주장하지 않는다.
- 공통 실제 엔티티 **5표**의 빈 DB 생성·닉네임 경합, update 반복/OID·상태·철회 이력 보존, 잘못된 enum·인덱스의 update/validate 거부 **5개**가 통과했다. Survey 모델을 포함한 부분 인덱스 2개는 해당 이관 단위에서 따로 기록한다. 전체 22표의 성공 증거가 아니다.
- local의 설계 SQL 전체 선실행/continue-on-error는 제거했다. 실제 운영·공유 DB에 SQL을 실행하거나 애플리케이션을 부팅하지 않았다. 현재 남은 B 모델의 전체 정합화와 전체 검사 결과는 후속 단위다.

## 단위 2: 기존 설계의 미매핑 9표

- personality/operator_audit/service_case/service_case_history/chat_report/chat_report_message/chat_safety_notice/user_setting/matching_preference의 모델을 기존 canonical 필드 그대로 매핑했다. 새 표·업무 API·권한·자동 업무 처리를 추가하지 않았다.
- PostgreSQL named enum, 지원·신고 복합 키/논리 참조와 명시 인덱스를 사용하며 물리 FK는 만들지 않는다. 모델 9표/59컬럼의 순수 JPA 생성·update 반복·validate, 분석 버전/신고 메시지/안내 고유성과 실제 필드 자료 보존을 **2개 테스트**로 확인했다.
- 해당 API들이 구현됐다는 뜻은 아니다. 전체 엔티티가 정확히 22표만 생성하는 검증은 B 도메인 매핑을 합친 뒤 수행한다.

## 단위 3: 현재 소유 키링 저장·숫자 ID 조회

- Keyring을 canonical keyring의 숫자 id/serial_number/firmware_version/현재 owner/state/확인 버전·시각으로 매핑했다. 목록·구매 안내는 해당 표와 실제 user_setting만 읽는다. serial·검증 정보·등록 시각을 응답하지 않는다. 기기 상태는 현재 양수 설정의 ACK만 READY이며 BLE/회원 전체 준비와 구분한다.
- 실제 PostgreSQL/JPA canonical 검증 5개와 실제 보안 구성을 적용한 MVC 9개가 통과했다. 숫자 ID 저장·재조회/노출 필드, 타인/UNBOUND/소유자 없는 행 제외, 현재·구/미래/영/음수 ACK와 설정 유실, 이전 소유자 노출 차단 및 serial UNIQUE·상태 enum을 확인했다. 양도는 명시 fixture이며 소유 증명 API 검증을 대신하지 않는다.
- 현재 목록/구매 전체 컨텍스트 테스트의 구 UUID·binding 기대값은 새 저장 계약으로 정합화했으나 해당 전체 실행은 남은 B 매핑 후 확인한다. MVC는 서비스/DB를 대체하며 위 별도 PG 검증과 경계가 다르다.
- 등록/challenge/sync 구현은 이 checkout에 처음부터 없다. 구 해시·검증 키 참조를 사용하던 entity의 읽기 매핑을 바꾼 것이며 기존 DB 열/자료 삭제 SQL은 없다. hash→serial 역변환이나 오래된 ACK를 추정하지 않는다. verified 원문 serial·소유/ACK 자료를 확인한 별도 이관 없이는 기존 DB 자료를 자동 이동할 수 없다.

## 초기 단위 검사 이력

당시 작업폴더에서 자동 DDL 7개·보완 모델 2개·키링 PostgreSQL 5개·경로 9개의 **23개 runtime은 failure/error/skip0**이었다. 이 중 자동 DDL 5개만 위 첫 단위의 직접 범위다. 초기 generic 컴파일 오류, 작업 중 Survey 시그니처 불일치 및 실제 fail-closed 검사 2개 실패는 수정 전 이력이며 성공으로 계산하지 않는다.

당시 동시 작업 중 다른 새 도메인 파일의 Checkstyle 경고가 있어 이 초기 실행을 전체 build 성공으로 계산하지 않았다. 최종 결과는 아래와 같다. 공유 DB 적용·push·MR·리뷰어·develop 병합·실기기/공급자·배포는 없다.

## 전체 연결 1차 검사

후속 선택 실행 34개 중 26개가 통과했고 8개가 실패했다. MemberAccess 실제 canonical 9개, 자동 DDL 7개, chat 자료 이관 6개, survey 이관 4개는 통과했다. 전체 성공으로 계산하지 않는다.

실패는 세 원인이다. 전체 앱 순수 JPA 생성은 ChatRoom enum의 Java 이름과 canonical enum 이름 불일치로 chat_close_reason 타입 생성이 빠졌다. survey 실제 서비스 6개는 canonical 부분 인덱스가 user_id만 포함한 반면 frozen 모델·서비스·수동 SQL은 user_id/survey_version을 요구해 시작 시 정합성 검사가 차단했다. survey 이관 1개는 구 JSON 컬럼의 NOT NULL이 nullable response_context에 남은 불일치다. 모두 공유 DB를 건드리지 않은 일회용 PostgreSQL 결과이며 각 소유자가 수정 중이다. 컴파일·bootJar는 실행됐고, 테스트 실패로 이 실행의 Checkstyle은 수행되지 않았다.

## 새 22표 runtime 연결

- 실제 전체 앱을 SQL 선행 생성 없는 `test` 프로필로 시작해 22표/176칼럼을 만들었다. tables.json의 모든 표·컬럼 이름, PostgreSQL 타입, varchar 길이, nullable과 물리 FK 0을 대조했다. canonical SQL로 만든 DB에서도 동일 앱이 validate된다. 인증·가입·안내는 두 DB 생성 방식에서 같은 HTTP 회귀를 실행한다. 구 `legacy-domain` 프로필과 보완 SQL fixture는 더 이상 사용하지 않아 제거했으며, 이전 151 검증 이력·JSON과 원본 작업폴더는 보존했다.
- 설문은 survey/survey_answer에 scalar 타입과 기존 response_context, revision/요청 키, 실제 새 정의 snapshot을 분리 보존한다. 제출만으로 성공 분석이나 ACK를 만들지 않는다. 현재 없는 답 저장·완료 HTTP를 새로 구현한 것은 아니다.
- 제안/관계는 matching 한 행과 matching_participant의 최초 선택·요청 영수증으로 통합했다. PENDING 이후 쌍의 영구 재매칭 금지, 최초 응답 불변, 상호 수락과 단일 방·참여자 생성의 원자성을 유지한다. WAITING/NOT_MATCHED는 제안 조회에서 제외한다. 취소 HTTP는 승인된 비노출 정책에 따른다.
- 채팅은 chat/chat_participant/chat_message에 저장한다. 안내 예약·ACK·선점 세대/lease·binding별 전달 결과를 notice_state에, 실제 표시한 개별 수신 메시지와 최초 읽음 시각을 read_state에 보존한다. 실제 연속 표시 범위만 읽음 순번으로 승격하며 누락 메시지를 max(seq)로 읽음 처리하지 않는다. 상대 읽음은 응답하지 않는다. 방/메시지는 숫자 ID, notice/installation/session은 UUID를 유지한다.
- 공통 fixture는 canonical 표만 쓰며 실제 기기/분석 번호를 서로 같다고 가정하지 않는다. 준비 상태를 위한 성공 분석·현재 ACK는 명시적인 테스트 자료다. 실제 외부 분석·기기 증명의 완료 주장이 아니다.

전체 `clean build` 첫 실행은 46 suite/418 tests 중 374 통과·44 실패(error/skip 0)였다. 제안 42개는 공통 fixture의 필수 firmware_version 누락으로 준비 단계에서 실패했고, 안내 두 클래스 각 1개는 숫자 ID를 UUID로 바꾼 테스트의 참조 비교가 남은 문제였다. 스타일·컴파일·패키징은 통과했다. 두 fixture를 수정한 뒤 제안 42+canonical 안내18+순수 JPA 안내18의 78개와 main/test Checkstyle은 전부 통과했다. 이를 전체 build 성공으로 합산하지 않으며 최종 전체 결과는 후속 기록으로 확정한다.

## 최종 runtime 검사

`./gradlew.bat clean build --console=plain`이 **성공**했다. XML 집계는 **46 suite / 422 tests / failure 0 / error 0 / skip 0**이다. main/test 컴파일·Checkstyle, bootJar/jar, check/build를 모두 실행했다. [검증 JSON](erd22-storage-175/verification.json)에 suite별 결과와 이전 실패 원인을 남겼다. 앞선 선택 실행의 31개·78개를 이 422개에 더하지 않는다.

이 검사는 새 22표만 사용하는 canonical SQL DB와 순수 JPA 생성 DB의 실제 전체 앱 컨텍스트·HTTP·JPA/native SQL·세션/권한·잠금 회귀다. 실제 외부 소셜 공급자·FCM·기기·운영 접속·배포 검증이 아니다. 설문/제안/채팅의 알려진 구 자료 이관도 일회용 DB로 확인했으며 실제 운영 자료를 조사하거나 변환하지 않았다. 사용자/동의 이력과 원본 151/176/70 작업폴더·기획/기능 원문을 보존했다.

## 자료 보존 SQL 후속 검사

전체 성공 이후 Java main 변경 없이 수동 이관 SQL만 `public, pg_temp`로 검색 경로를 고정했다. 존재 검사·실제 변경의 schema를 일치시키며, 같은 연결의 동명 임시 표와 다른 schema의 동명 원본은 그대로 보존한다. 알려진 원본 표를 먼저 잠가 사전 검사와 변경 사이의 쓰기를 차단한다.

[설문 SQL](../database/survey-175-migration.sql)·[제안 SQL](../database/matching-175-migration.sql)·[채팅 SQL](../database/chat-175-migration.sql)은 알려진 0255529 구조의 수동 검토 후보다. 원래 ID·공개 UUID·시퀀스 고수위, 최초 상태/응답 시각·요청/취소 영수증·원문 관계 증거, JSON scalar와 부가 문맥·개별 읽음·안내 예약/전달/ACK 증거를 보존한다. 과거 답변 시각·정의 snapshot처럼 없는 증거는 NULL로 남긴다. 여러 과거 제안, 미검증 만남, 지원하지 않는 작업/관계/값은 가짜 병합·절단 대신 변경 전 거부/전체 rollback한다.

후속 실제 PostgreSQL 회귀는 **chat 9 + matching 9 + survey 7 = 25개** 모두 failure/error/skip 0이다. main/test Checkstyle도 통과했고 실행 파일은 전체 성공 시점 그대로다. 이 25개는 전체 422개와 겹치므로 합산하지 않는다. 세 SQL 모두 **공유/운영 DB 적용 없음**이다.

이 SQL들이 과거 28표의 모든 자료를 자동 전환한다는 뜻은 아니다. keyring 원문 serial/소유·ACK 대응 자료와 구 분석/설정·동기화 자료의 실제 이관은 조사·검토되지 않았다. 원본 DB 자료를 삭제하거나 추정 변환하지 않았다. 실제 자료/외부 서비스·기기·배포 검증은 별도이며, 현재 범위는 새 22표의 전체 앱 생성·실행과 알려진 자료 보존 후보의 로컬 검증까지다.
