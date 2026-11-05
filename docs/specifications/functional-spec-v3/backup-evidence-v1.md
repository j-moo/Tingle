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

`backup-evidence.compose.yml`은 **개발 수동 확인용** opt-in overlay다.
Jenkins의 기본 Compose 배포에는 적용되지 않는다. 지속 배포의 마운트·환경변수 반영은 INFRA 담당이다.
승인 ID·scope·실존 폴더가 필요하며 자동 디렉터리 생성이나 기본 Compose 변경·자동 삭제 활성화는 없다.
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

개발 계약의 공개 식별자는 아래와 같이 지정한다. 운영에 재사용하지 않는다.

- `SCOPE_ID=tingle-dev-db-dumps-v1` (`BACKUP_EVIDENCE_SCOPE_ID`로 공급)
- `APPROVAL_ID=S15P31E106-202-DEV-DB-V1-20261105` (`BACKUP_EVIDENCE_APPROVAL_ID`로 공급)

ID 지정은 검토 기록을 연결하는 기준이며 활성화 승인 완료를 뜻하지 않는다.
BE·INFRA 검토와 아래 로그 범위 확인 후 개발에서 활성화하며 운영은 별도 승인을 받는다.

## 사진·로그 범위 확인

현재 채팅 전송 계약은 `clientMessageId`와 `text`만 받고 DB `ChatMessage.body`에 저장한다.
채팅 첨부 업로드 구현은 없다. `/data/uploads/{prod,dev}` → `/data/photos` 마운트는
프로필 사진 저장소이므로 현 채팅 사본의 범위에는 포함하지 않는다. 첨부 기능 추가 시 재검토한다.

Nginx 접근 로그 형식에는 요청 본문이 없고 현재 채팅 코드도 본문을 로깅하지 않는다.
그러나 Alloy는 컨테이너 stdout/stderr를 수집하며 예외 처리기는 예상하지 못한 예외 stack을 기록한다.
Loki의 336시간 보관 설정만으로 과거·실서버 본문 부재를 증명할 수 없다.
실제 로깅 설정·합성 본문 검사·과거 로그 취급 기준을 BE·INFRA가 확인하기 전에는
Loki 제외나 전체 외부 사본의 부재를 승인하지 않는다. 필요 시 로그 사본 공급 계약을 추가한다.
