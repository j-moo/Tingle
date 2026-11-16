# 202 파일 기반 백업 증거 v1 — 검토 후보

이 문서는 구현 MR의 검토 기준이며 운영 범위 승인·활성화 완료를 뜻하지 않는다.
기본 OFF. INFRA의 저장소/로그/사진 범위 확인과 BE·INFRA 검토 후 별도 활성화한다.

## 목적과 범위

기존 `/backup`의 `daily-tingle*.dump` 및 `db-tingle-*.dump`만 메타데이터로 확인한다.
DB 덤프 본문을 읽거나 채팅 삭제 요청으로 덤프 전체를 지우지 않는다.
사진 백업과 Loki는 이 계약이 부재를 보증하지 않는다. 다른 사본 저장소가 확인되면
현재 source를 전체 사본 승인으로 켜지 않고 범위 합의·구현을 먼저 보강한다.

## 공급 파일

```json
{
  "schemaVersion": 1,
  "namespace": "tingle_dev",
  "scopeId": "local-db",
  "restoreGeneration": "restore-20261105-1",
  "inspectedAt": "2026-11-05T03:00:00Z",
  "complete": true,
  "entries": [
    {
      "id": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      "present": true,
      "expiresAt": "2026-11-08T00:00:00+00:00"
    }
  ]
}
```

- `namespace`: `tingle` 또는 `tingle_dev`. 운영 배포 전 덤프는 `tingle`에만 포함한다.
- `scopeId`: 독립적인 BE 설정의 승인 저장소 ID와 정확히 일치해야 한다.
- `restoreGeneration`: 운영자가 복원 전에 갱신하는 별도 파일의 값과 일치해야 한다.
  복원·재삭제 검증이 끝나기 전 사용자 접근을 열지 않는 기존 gate는 그대로 유지한다.
- `id`: 파일명/파일 식별 메타데이터의 SHA-256. 경로·내용·사용자 ID는 공급하지 않는다.
- `present`: 실제 재검사 결과. 기한 경과만으로 false로 만들지 않는다.
- `expiresAt`: 기존 7일 도구의 파일명 시각 기준 기한이다. `expiresAt - 7일`을
  덤프 시작의 보수적 하한으로 사용하며 파일 mtime을 시작 시각으로 추정하지 않는다.

## 공급·실패 동작

`backup-evidence-refresh.sh`는 덤프/보관 작업 후 및 별도 주기 실행(예: 1분)을 위한 진입점이다.
일일 스크립트는 `BACKUP_EVIDENCE_DIR` 설정 시에만 이를 호출한다. Jenkins 배포 전 덤프도
같은 `/backup`에서 다음 주기 검사에 포함된다. 현재 서버 cron/Jenkins 설정은 변경하지 않는다.

Linux `fcntl.flock(LOCK_EX | LOCK_NB)`으로 동일 출력 파일 동시 작성을 차단한다.
잠금 파일 inode는 유지하며 close·강제 종료·재부팅 시 커널이 잠금을 해제한다.
잔존 잠금 파일은 다음 실행을 막지 않으며 실행 중 파일을 삭제하지 않는다.
작성 완료 후 fsync·원자 교체한다.
인식 불가 파일·symlink·진행 중 `.tmp`·검사 중 저장소/세대 변경 시 기존 결과를 갱신하지 않는다.
이전 결과는 5분 후 무효다. 덤프가 5분 넘게 진행되면 `.tmp`가 사라지고 새 검사에 성공할 때까지
오래된 증거로 삭제 완료를 확정하지 않는다. 정확한 BE 상태는 `BLOCKED_UNRESOLVED`
(`BACKUP_EVIDENCE_UNAVAILABLE`)이며 갱신 후 재검사한다. 덤프의 실제 존재를 확인한
경우의 `RETRYABLE_FAILED`와 구분한다.
한 namespace 갱신이 실패해도 다른 namespace는 시도하고 마지막에 실패 코드를 반환한다.
일일 백업의 후속 갱신 실패는 별도 오류 로그만 남기고, 이미 성공한 백업의 완료 로그를 보존한다.
같은 세대의 기존 식별자는 실제 부재 확인 후 false로 보존한다. 세대가 바뀌면 이전 목록은 재사용하지 않는다.

결과 JSON은 0640, 메타데이터 폴더는 0750이며 JSON의 그룹은 폴더 그룹을 따른다.
생성기는 디렉터리 모드를 명시하고 원자 교체할 임시 파일도 그룹·0640을 설정한다.
INFRA는 생성기 쓰기 권한과 BE 컨테이너 프로세스의 그룹 읽기·상위 경로 탐색 권한을 설정한다.
uid 1000이 호스트 소유자와 같다고 가정하지 않는다. 세대 파일도 BE가 읽을 수 있어야 한다.
컨테이너에는 메타데이터 폴더만 RO로 붙이며 `/backup`은 마운트하지 않는다.
누락·오래됨·미완성(`complete:false`)·다른 범위/세대·중복 ID·presence 누락은 승인 실패다.

## 삭제·복원 검증

최초 목록이 비어도 scope 참조를 저장하여 DB 삭제 후 새 검사를 반드시 요구한다.
처음 목록 이후 시작된 덤프도 누락하지 않도록 현재 namespace 전체 목록을 재검사한다.
새 epoch 최초 요청 시각 이후의 검사이며, 그 시각 이전에 시작했을 수 있는 덤프가
모두 실제 부재인 경우에만 ABSENT_CONFIRMED다. 읽기 프로세스 재시작도 새 검사를 요구한다.
덤프 존재는 RETRYABLE_FAILED, 미확정 파일은 BLOCKED_UNRESOLVED로 구분한다.
서비스가 검증하는 scopeHash·epoch·dispatchId 및 기존 삭제 저널/복원 gate는 대체하지 않는다.

백업 범위 참조는 `scopeId:namespace`로 발급하며 복원 세대와 분리한다.
기존 저널의 `scopeId:namespace:과거세대`도 정확히 같은 범위일 때 호환한다.
과거 승인·저널·요청 참조·scopeHash는 바꾸지 않으며 증거 JSON은 반드시 현재 세대 파일과 일치해야 한다.
이전 세대의 JSON, 다른 scope/namespace, 실제 존재하는 덤프를 부재로 승인하지 않는다.

검사 기준 시각은 `(epoch, 현재 복원 세대)`별로 보관한다. 최근 사용 순서의 캐시는 최대 10,000개이며
오래된 항목을 축출한다. 새 요청을 영구 차단하지 않는다. 축출된 epoch가 돌아오거나 세대가 바뀌면
그 요청 시각을 새 기준으로 잡고 이후 검사를 요구한다. 캐시 접근·축출은 동기화한다.

복원 재실행은 DB 재삭제·attempt 갱신·이전 외부 검증 무효화와 같은 트랜잭션에서
`dbRestoreGeneration`을 외부 정리 상태에 저장한다. 이는 완료 증명이 아닌 DB 재생 진행 표식이다.
같은 복원 UUID·같은 DB binding·동일 저널 승인이고 채팅이 없는 재시도만 기존 외부 epoch를 이어간다.
그 후에도 기존 외부 검증·gate 확인을 거쳐야 한다. 프로세스 재시작 후에도 진행 표식은 유지한다.
다른 복원 UUID·채팅 재등장·binding 변경·구형 JSON의 표식 누락 시에는 이전 완료를 재사용하지 않는다.
JSON의 추가 nullable 필드는 기존 상태를 읽을 수 있으며, 구형 실행 프로그램은 새 필드를 무시하더라도
이 재시도 개선을 제공하지 않으므로 복원 작업 중 구형 버전으로 되돌리지 않는다.

BE 의 마운트 · 환경변수는 `infra/docker-compose.yml` 의 `back-env`(운영)와 `back-dev`(개발)에 있다(S15P31E106-250 · 407).
운영 · 개발 모두 늘 켜져 있고 끄는 설정은 없다. 갱신 cron 은 `backup-evidence-refresh.sh` 주석,
복원은 `infra/restore-db.sh` 를 쓴다.
켜기 전에 메타데이터 폴더 · 세대 파일 · 그룹 권한을 준비한다. 백업 자동 삭제 활성화는 없다.
실제 인프라 권한·주기 실행·복원 시 세대 갱신 및 운영 로그 내용은 아직 별도 검증 대상이다.

## 복원 세대와 INFRA 적용 기준

복원 세대는 **환경별 정규 UUID 하나**를 사용한다. 별개 값을 두 번 발급하지 않는다.
공급 파일은 `$BACKUP_EVIDENCE_DIR/tingle_dev.restore-generation`(개발),
`$BACKUP_EVIDENCE_DIR/tingle.restore-generation`(운영)으로 분리한다.
해당 파일 값과 BE의 기존 `tingle.chat.restore.generation` 시작 설정에 같은 UUID를 공급한다.
두 입력은 현재 자동 동기화되지 않으며, 파일만 바꿔서는 실행 중인 BE의 gate 값이 갱신되지 않는다.

복원 순서는 요청·worker 차단 → 해당 환경 새 UUID 발급·파일과 BE 시작 설정 반영·이전 marker 무효화
→ `pg_restore` → 동일 UUID로 BE 재시작 → 새 inventory·삭제 저널 재실행·외부 부재 검증
→ 새 세대의 serving marker 확인 → 접근 재개다. 다른 환경의 세대는 바꾸지 않는다.
파일 교체는 원자적으로 하고 BE가 새 세대 파일을 읽을 수 있는 권한을 유지한다.
이 기준에 따른 복원 스크립트와 기존 gate 설정의 배포 연결은 INFRA가 구현한다.

환경별 공개 식별자는 아래와 같이 지정한다. 서로 재사용하지 않는다. compose 에 값으로 적고 cron 에는 `BACKUP_EVIDENCE_SCOPE_ID`로 공급한다.

- 개발: `SCOPE_ID=tingle-dev-db-dumps-v1`, `APPROVAL_ID=S15P31E106-202-DEV-DB-V1-20261105`
- 운영: `SCOPE_ID=tingle-prod-db-dumps-v1`, `APPROVAL_ID=S15P31E106-407-PROD-DB-V1-20261117` (S15P31E106-407 에서 지정)

ID 지정은 검토 기록을 연결하는 기준이며 활성화 승인 완료를 뜻하지 않는다.
두 환경 모두 활성화한다(S15P31E106-407). 아래 로그 범위 확인은 계속 별도 검증 대상이다.

## 사진·로그 범위 확인

현재 채팅 전송 계약은 `clientMessageId`와 `text`만 받고 DB `ChatMessage.body`에 저장한다.
채팅 첨부 업로드 구현은 없다. `/data/uploads/{prod,dev}` → `/data/photos` 마운트는
프로필 사진 저장소이므로 현 채팅 사본의 범위에는 포함하지 않는다. 첨부 기능 추가 시 재검토한다.

Nginx 접근 로그 형식에는 요청 본문이 없고 현재 채팅 코드도 본문을 로깅하지 않는다.
그러나 Alloy는 컨테이너 stdout/stderr를 수집하며 예외 처리기는 예상하지 못한 예외 stack을 기록한다.
Loki의 336시간 보관 설정만으로 과거·실서버 본문 부재를 증명할 수 없다.
실제 로깅 설정·합성 본문 검사·과거 로그 취급 기준을 BE·INFRA가 확인하기 전에는
Loki 제외나 전체 외부 사본의 부재를 승인하지 않는다. 필요 시 로그 사본 공급 계약을 추가한다.

## 복원 운영 실행기

BE 실행 JAR는 첫 인자가 `chat-restore`일 때만 별도의 운영 명령으로 진입한다.
이 경로는 Spring 애플리케이션·HTTP 서버·JPA/DDL·자동 작업·AI 호출을 시작하지 않는다.
복원에 필요한 JDBC 저장소와 파일 증거 공급자만 명시적으로 구성한다. 일반 앱 실행은 그대로다.

| 명령 | 동작 | 필수 인자 |
| --- | --- | --- |
| `journal-init` | 운영자가 미리 만든 빈 디렉터리에만 저널과 namespace를 최초 생성 | `--namespace=tingle_dev` 또는 `tingle` |
| `journal-status` | 파일 체인을 검증한 fingerprint·논리 기록 수만 출력, DB 접근 없음 | 동일 namespace |
| `verify` | 현재 DB·복원 세대 확인 후 재삭제·외부 부재 재검증, 성공에만 marker 작성 | namespace, restore-generation, expected-fingerprint, fence-confirmed |

예: `java -jar app.jar chat-restore journal-status --namespace=tingle_dev`.
검증 명령의 인자 형식은 `verify --namespace=tingle_dev --restore-generation=<UUID>`
`--expected-fingerprint=<고정한 SHA-256> --fence-confirmed=true --wait-seconds=180 --poll-seconds=5`다.
인자는 `--이름=값` 형식만 받고 중복·알 수 없는 인자·잘못된 범위를 거부한다.
자격 증명은 명령행이나 출력에 넣지 않고 컨테이너의 기존 환경변수로만 전달한다.

공통 환경변수는 `TINGLE_CHAT_DELETION_JOURNAL_DIRECTORY`다. 절대 경로·symlink 없는 경로를 요구한다.
`journal-init`은 기존 저널을 덮어쓰거나 누락된 파일을 자동 복구하지 않는다.
POSIX에서는 journal 디렉터리0700·파일0600으로 초기화한다. namespace 파일로 환경을 고정한다.
구형 저널에 namespace 표식이 없다면 임의로 초기화·덮어쓰지 않고 별도 조정을 거친다.

`verify`는 추가로 아래 값을 요구한다.

- `TINGLE_CHAT_RESTORE_GENERATION`: 명령행 UUID와 같아야 한다.
- `TINGLE_CHAT_RESTORE_MARKER_FILE`: 저널 디렉터리와 분리한 절대 파일 경로, 부모 폴더 사전 준비.
- `SPRING_DATASOURCE_URL/USERNAME/PASSWORD`: PostgreSQL 연결. 실제 `current_database()`가 namespace와
  일치하는지 먼저 검사하며 다르면 DB 재삭제·marker 변경을 시작하지 않는다.
- 기존 `APP_BACKUP_EVIDENCE_FILE/GENERATION_FILE/NAMESPACE/SCOPE_ID/APPROVAL_ID`.
  증거 세대 파일도 같은 UUID여야 한다. 연결/소켓/쿼리/트랜잭션에는 별도 고정 시간 제한을 둔다.
  JDBC URL의 사용자·비밀번호·timeout 재정의는 받지 않는다.

결과는 한 줄 JSON이다. 초기화·조회 성공은 `status:OK`, 검증 성공은 `status:VERIFIED`와 exit0이다.
조회에만 `fingerprint`·`entries`를 포함한다. 형식/설정 오류는 exit2, 검증 미완료·운영 실패는 exit3이다.
stderr/JSON에 자격 증명·경로·저널 내용·예외 원문을 출력하지 않는다.
시간 초과·실패를 성공으로 바꾸거나 marker를 수동 조작하지 않는다.

검증은 동일 프로세스·공급자·epoch 기준으로 새 cron 검사를 기다릴 수 있다.
`wait-seconds`는 재시도 대기 예산(기본180,0~900), `poll-seconds`는 검사 간격(기본5,1~30)이며
진행 중 JDBC 작업까지 즉시 중단하는 전체 wall-clock 제한은 아니다. 예산 소진 시 pending을 유지한다.
매번 새 프로세스를 즉시 재실행하는 방식으로 새 검사 시각 조건을 우회하지 않는다.
별도 `restore.lock`을 unlink하지 않고 잠가 동시 검증을 막으며, 세대 변경 시 검증을 중단한다.

### 권한과 INFRA 연결 기준

개발 호스트의 `/srv/tingle/state/chat-retention/dev/journal`을 `/run/tingle/chat-journal`에 연결한다.
저널은 신뢰된 BE 삭제 프로세스(uid1000)와 운영 복원 도구만 RW로 사용한다. 일반 사용자·다른 서비스의
쓰기를 허용하지 않는다. 현재 snapshot도 lock을 WRITE로 열기 때문에 journal 전체 RO는 동작하지 않는다.
여기서 privileged는 허용된 신뢰 작성 프로세스라는 뜻이며 OS root 실행을 필수로 요구하지 않는다.

별도 `/srv/tingle/state/chat-retention/dev/restore`를 `/run/tingle/chat-restore`에 연결하고
`TINGLE_CHAT_RESTORE_MARKER_FILE=/run/tingle/chat-restore/serving.marker`로 둔다.
일반 BE에는 이 폴더를 RO, 제한된 일회성 복원 도구에만 RW로 제공한다. 운영은 prod 경로로 분리한다.
CLI를 실행할 때 HTTP service port를 공개하거나 Docker socket을 마운트할 필요가 없다.
기본 Compose·서버 ACL·환경변수 전달·DB 복원 orchestration은 INFRA 적용 범위이며 이 BE 구현이
자동으로 생성·활성화하지 않는다.

INFRA는 요청·자동 작업·배포를 먼저 차단한 뒤 `journal-status`의 검증된 fingerprint를 DB 밖에 고정한다.
`--fence-confirmed=true`는 그 절차를 완료했다는 운영자 확인이며 도구가 외부 ingress를 차단했다는 증명이 아니다.
새 환경 UUID를 세대 파일·BE 시작 설정·복원 도구에 동일하게 반영하고 pg_restore를 수행한다.
원샷 `verify`가 exit0이고 같은 UUID·fingerprint의 gate를 통과한 뒤에만 사용자 접근을 재개한다.
그 전에는 일반 BE를 시작하거나 generation을 비워 우회하지 않는다. 검증 중 실패하면 차단을 유지한다.
현재 저널이 추가되면 기존 marker는 자동으로 승인 범위를 넓히지 않으며 재검증이 필요하다.
본 변경은 실서버 DB 복원·기능 활성화·운영 적용·자동 정리 활성화를 수행한 증거가 아니다.

### 재개 전 읽기 전용 gate 판정

`gate-status`는 `verify`와 같은 필수 인자(namespace·restore-generation·expected-fingerprint·fence-confirmed)를
받고 저널·marker·증거 세대 파일만 검사한다. DB 연결이나 marker 쓰기는 하지 않는다.
일반 BE와 같은 RO marker 마운트에서 현재 세대 파일·고정 fingerprint·실제 `RestoreServingGateFilter`가
모두 일치할 때만 exit0/`status:ALLOWED`를 반환한다. 불일치는 exit3/`status:BLOCKED`이며
잘못된 설정은 exit2다. 이 명령의 성공은 실제 BE 컨테이너 설정과 외부 요청·worker 차단을 증명하지 않는다.

복원 작업의 UUID·복원 전 고정 fingerprint·선택 dump·단계는 DB 밖의 제한된 작업 기록에 보관한다.
도구 실패/중단 후에는 같은 작업의 UUID와 fingerprint로 재시도하고, DB 복원이 이미 끝났으면
불필요하게 다시 복원하지 않는다. DB 복원이 중단됐으면 fence를 유지한 채 같은 dump부터 다시 수행한다.
다른 dump로 새 작업을 시작할 때는 새 UUID를 사용한다. 도구 lock은 전체 복원 작업 직렬화를 대신하지 않는다.
요청·worker·자동 배포 차단 → 최신 저널 fingerprint 고정 → 동일 UUID 설정·DB 복원 → 재삭제·외부 확인 →
marker → RO gate 판정 → fence 안에서 BE 제한 기동·실제 gate/readiness 확인 → 승인된 서비스 재개 순서를 따른다.
모든 worker와 자동 배포를 확실히 차단할 수단이 확인되지 않으면 실제 서비스 재개를 진행하지 않는다.
