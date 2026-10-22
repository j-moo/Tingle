#!/bin/sh
# 매일 백업 (인프라 계획 8절). 서버 ubuntu 계정 crontab 에서 실행한다:
#   0 19 * * * sh /srv/tingle/repo/infra/backup-daily.sh >> /backup/daily.log 2>&1   (서버는 UTC — 한국 시각 04:00)
# 운영 · 개발 DB(pg_dump) 와 업로드 사진(/data/uploads) 을 /backup 에 받고 7일치만 남긴다.
# master 배포 직전 백업(db-tingle-<시각>-<커밋>.dump, Jenkinsfile)과는 이름이 달라 서로 지우지 않는다.
# 복원: docker exec -i tingle-db-1 pg_restore -U 106hclk -d <DB> --clean < 파일
set -eu
umask 077
day=$(date +%Y%m%d)
cd /backup
for db in tingle tingle_dev; do
  docker exec tingle-db-1 sh -c "pg_dump -U \"\$POSTGRES_USER\" -Fc $db" > "daily-$db-$day.dump.tmp"
  mv "daily-$db-$day.dump.tmp" "daily-$db-$day.dump"
done
tar czf "daily-uploads-$day.tar.gz" -C /data uploads
find /backup -maxdepth 1 -name 'daily-*' -mtime +6 -delete
echo "$(date '+%F %T') 백업 완료: $(ls daily-*-$day.* | tr '\n' ' ')"
