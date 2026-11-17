#!/bin/sh
# Also run periodically (e.g. every minute); daily-only snapshots fail the 5m freshness guard.
# 서버 ubuntu 계정 crontab (S15P31E106-250 · 407). 운영(tingle) · 개발(tingle_dev) 둘 다 늘 돈다 — back 이 두 환경 모두 이 파일을 읽는다.
# 두 줄은 namespace · 범위 ID · lock 을 나눈다(같은 lock 이면 한쪽이 매번 건너뜀). 로그는 /backup 밖에 둔다(/backup 의 모르는 파일은 보관 정리를 막는다):
#   * * * * * BACKUP_EVIDENCE_DIR=/srv/tingle/backup-evidence BACKUP_EVIDENCE_NAMESPACES=tingle BACKUP_EVIDENCE_SCOPE_ID=tingle-prod-db-dumps-v1 flock -n /srv/tingle/backup-evidence/.refresh-tingle.lock sh /srv/tingle/repo/infra/backup-evidence-refresh.sh >> /srv/tingle/logs/backup-evidence.log 2>&1
#   * * * * * BACKUP_EVIDENCE_DIR=/srv/tingle/backup-evidence BACKUP_EVIDENCE_NAMESPACES=tingle_dev BACKUP_EVIDENCE_SCOPE_ID=tingle-dev-db-dumps-v1 flock -n /srv/tingle/backup-evidence/.refresh.lock sh /srv/tingle/repo/infra/backup-evidence-refresh.sh >> /srv/tingle/logs/backup-evidence.log 2>&1
# 처음 한 번 준비(ubuntu, 순서대로):
#   sudo install -d -o ubuntu -g ubuntu -m 750 /srv/tingle/backup-evidence   # 배포로 root 소유로 먼저 생겼어도 소유자 · 권한을 맞춤
#   mkdir -p /srv/tingle/logs
#   for ns in tingle tingle_dev; do f=/srv/tingle/backup-evidence/$ns.restore-generation; [ -e "$f" ] || { cat /proc/sys/kernel/random/uuid > "$f" && chmod 640 "$f"; }; done
# Run after completed retention and backups, never before a restore generation is rotated.
set -eu
umask 027
: "${BACKUP_EVIDENCE_DIR:?Metadata directory required}"
: "${BACKUP_EVIDENCE_SCOPE_ID:?Approved scope required}"
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
status=0
for namespace in ${BACKUP_EVIDENCE_NAMESPACES:-tingle tingle_dev}; do
  if ! python3 "$script_dir/backup-evidence.py" --directory /backup \
    --output "$BACKUP_EVIDENCE_DIR/$namespace.json" \
    --generation-file "$BACKUP_EVIDENCE_DIR/$namespace.restore-generation" \
    --namespace "$namespace" --scope-id "$BACKUP_EVIDENCE_SCOPE_ID"; then
    echo "backup evidence refresh failed: $namespace" >&2
    status=1
  fi
done
exit "$status"
