#!/bin/sh
# DB 복원 (S15P31E106-250). 서버 ubuntu 계정에서 직접 실행: sh infra/restore-db.sh dev /backup/daily-tingle_dev-20261105.dump
# 1) 해당 환경 BE 를 멈추고 2) 복원 세대 UUID 를 새로 발급해 백업 증거 세대 파일을 원자 교체(이전 증거 목록은 더 이상 쓰이지 않음)
# 3) pg_restore --clean. 다른 환경의 세대는 건드리지 않는다.
# BE 는 다시 켜지 않는다: 복원 gate(tingle.chat.restore.generation · marker-file · 삭제 저널 재실행)가 아직 연결 전이라,
#   과거 덤프로 되살아난 삭제 채팅을 지우고 검증하는 단계가 없다. 검증 전 서비스 재개는 보류(BE 합의, 2026-11-05).
#   BE 의 1회성 복원 실행기 · 저널 · gate 가 연결되면 같은 UUID 로 재실행 · 검증한 뒤에만 켜도록 붙인다(backup-evidence-v1.md "복원 세대와 INFRA 적용 기준").
# 실패할 수 있는 준비(덤프 · 환경 대조, 세대 파일 작성)는 BE 를 멈추기 전에 끝낸다. 중간에 실패해도 BE 는 꺼진 채 — 원인 확인 후 다시 실행하면 된다
#   (켤 서비스는 실행 상태가 아니라 환경으로 정하므로 재실행해도 같다). 복원 중에는 develop · master 머지(Jenkins 배포)를 하지 않는다.
set -eu
[ $# -eq 2 ] || { echo "사용법: sh infra/restore-db.sh dev|prod <덤프 파일>"; exit 1; }
dump=$2
# 덤프 이름으로 환경을 대조한다(운영 DB 를 개발 덤프로 덮거나 개발에 운영 데이터를 넣는 실수 방지)
case $1:$(basename "$dump") in
  dev:daily-tingle_dev-*.dump) db=tingle_dev services=back-dev serve=back-dev ;;
  prod:daily-tingle-*.dump | prod:db-tingle-*.dump)
    db=tingle services='back-blue back-green'
    serve=back-blue                                                  # 운영 색 = nginx 상태 파일(deploy-bluegreen.sh, 없으면 blue)
    ! grep -q green /srv/tingle/nginx-state/back.conf 2>/dev/null || serve=back-green ;;
  *) echo "환경(dev|prod)과 덤프 이름이 맞지 않음: $1 $dump"; exit 1 ;;
esac
evidence=${BACKUP_EVIDENCE_DIR:-/srv/tingle/backup-evidence}
[ -f "$dump" ] || { echo "덤프 파일 없음: $dump"; exit 1; }
root=$(cd "$(dirname "$0")/.." && pwd)
dc="docker compose --env-file /srv/tingle/.env --env-file /srv/tingle/app.env -f $root/infra/docker-compose.yml"

# 새 세대 파일을 미리 써 둔다(폴더가 없거나 쓸 수 없으면 여기서 멈춤 — BE 는 아직 그대로)
generation=$(cat /proc/sys/kernel/random/uuid)
tmp=$(mktemp "$evidence/.generation-XXXXXX")
trap 'rm -f "$tmp"' EXIT
trap 'exit 130' INT TERM                                    # 중단해도 EXIT trap 으로 임시 파일 정리
printf '%s\n' "$generation" > "$tmp"
chgrp "$(stat -c %g "$evidence")" "$tmp"
chmod 640 "$tmp"

$dc stop -t 25 $services                                       # 이미 꺼져 있어도 그대로 통과(재실행)

mv "$tmp" "$evidence/$db.restore-generation"
echo "$(date '+%F %T') $db 복원 세대: $generation"

docker exec -i tingle-db-1 sh -c "pg_restore -U \"\$POSTGRES_USER\" -d $db --clean --if-exists" < "$dump"
echo "$(date '+%F %T') $db 복원 완료: $dump"

echo "BE($serve)는 꺼 둔 상태로 끝남. 삭제 저널 재실행 · 복원 검증(BE 복원 실행기) 전에는 켜지 않는다 — 되살아난 삭제 채팅이 서비스될 수 있음"
