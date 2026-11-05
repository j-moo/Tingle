#!/bin/sh
# 매일 백업 (인프라 계획 8절). 서버 ubuntu 계정 crontab 에서 실행한다:
#   0 19 * * * sh /srv/tingle/repo/infra/backup-daily.sh >> /backup/daily.log 2>&1   (서버는 UTC — 한국 시각 04:00)
# 운영 · 개발 DB(pg_dump) 와 업로드 사진(/data/uploads) 을 /backup 에 받고 7일치만 남긴다.
# 두 DB 백업 계열은 생성 후 최대 7일. Python 3가 필요하며 만료 처리 실패 시 중단한다.
# 복원: sh infra/restore-db.sh dev|prod <덤프 파일> (BE 멈춤 · 백업 증거 세대 교체 · pg_restore · BE 다시 켬)
set -eu
umask 077
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
python3 "$script_dir/backup-retention.py" --directory /backup --apply
day=$(date -u +%Y%m%d)
cd /backup
for db in tingle tingle_dev; do
  docker exec tingle-db-1 sh -c "pg_dump -U \"\$POSTGRES_USER\" -Fc $db" > "daily-$db-$day.dump.tmp"
  mv "daily-$db-$day.dump.tmp" "daily-$db-$day.dump"
done
tar czf "daily-uploads-$day.tar.gz" -C /data uploads
# 업로드 사진은 DB 백업과 별도 수명이다. 기존 사진 정리 범위만 유지한다.
find /backup -maxdepth 1 -type f -name 'daily-uploads-*.tar.gz' -mtime +6 -delete
if [ -n "${BACKUP_EVIDENCE_DIR:-}" ]; then
  if ! sh "$script_dir/backup-evidence-refresh.sh"; then
    echo "backup evidence refresh failed; completed backups are retained" >&2
  fi
fi
echo "$(date '+%F %T') 백업 완료: $(ls daily-*-$day.* | tr '\n' ' ')"
