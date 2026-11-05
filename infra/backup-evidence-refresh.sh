#!/bin/sh
# Also run periodically (e.g. every minute); daily-only snapshots fail the 5m freshness guard.
# Run after completed retention and backups, never before a restore generation is rotated.
set -eu
umask 027
: "${BACKUP_EVIDENCE_DIR:?Metadata directory required}"
: "${BACKUP_EVIDENCE_SCOPE_ID:?Approved scope required}"
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
status=0
for namespace in tingle tingle_dev; do
  if ! python3 "$script_dir/backup-evidence.py" --directory /backup \
    --output "$BACKUP_EVIDENCE_DIR/$namespace.json" \
    --generation-file "$BACKUP_EVIDENCE_DIR/$namespace.restore-generation" \
    --namespace "$namespace" --scope-id "$BACKUP_EVIDENCE_SCOPE_ID"; then
    echo "backup evidence refresh failed: $namespace" >&2
    status=1
  fi
done
exit "$status"
