#!/usr/bin/env python3
"""Inventory DB dumps; --apply explicitly enables seven-day expiry.

Names are produced by the UTC backup jobs. Daily dumps expire at midnight UTC
seven calendar dates after their filename date (possibly less than 168h from
creation later that day); deployment dumps expire 168h
after their filename timestamp. mtime and deployment count never extend expiry.
Uploads and unfinished .tmp files are never deletion targets. An invalid entry
blocks the whole apply operation. This does not authorize chat-level erasure or
prove absence of data in other stores.
"""

import argparse
from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
import re
import stat


DAILY = re.compile(r"daily-(tingle|tingle_dev)-(\d{8})\.dump")
DEPLOYMENT = re.compile(r"db-tingle-(\d{8}-\d{6})-([0-9a-f]{7,40})\.dump")
UPLOAD = re.compile(r"daily-uploads-\d{8}\.tar\.gz")


def inventory(directory, now):
    """Return immutable file identities and actions without changing storage."""
    if now.tzinfo is None:
        raise ValueError("Current time must have a timezone")
    root = Path(directory).absolute()
    if any(part.is_symlink() for part in (root, *root.parents)):
        raise ValueError("Backup directory must not traverse a symlink")
    if not root.is_dir():
        raise ValueError("Backup directory does not exist")
    records = []
    for path in sorted(root.iterdir()):
        info = path.lstat()
        record = {"name": path.name, "action": "blocked", "reason": "unrecognized"}
        if not stat.S_ISREG(info.st_mode):
            record["reason"] = "not_regular_file"
        elif UPLOAD.fullmatch(path.name) or path.name.endswith(".tmp") or path.name == "daily.log":
            record.update(action="ignored", reason="outside_db_dump_scope")
        else:
            daily = DAILY.fullmatch(path.name)
            deploy = DEPLOYMENT.fullmatch(path.name)
            try:
                if daily:
                    created = datetime.strptime(daily[2], "%Y%m%d").replace(tzinfo=timezone.utc)
                elif deploy:
                    created = datetime.strptime(deploy[1], "%Y%m%d-%H%M%S").replace(tzinfo=timezone.utc)
                else:
                    records.append(record)
                    continue
                if created > now:
                    record["reason"] = "future_timestamp"
                else:
                    expires = created + timedelta(days=7)
                    record.update(action="expire" if now >= expires else "retain",
                                  reason="filename_time", expiresAt=expires.isoformat(),
                                  identity=(info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns))
            except ValueError:
                record["reason"] = "invalid_timestamp"
        records.append(record)
    return root, records


def run(directory, now, apply=False):
    root, records = inventory(directory, now)
    blocked = any(record["action"] == "blocked" for record in records)
    if apply and not blocked:
        # Recheck every candidate before the first unlink; never follow links.
        for record in records:
            if record["action"] == "expire":
                current = (root / record["name"]).lstat()
                identity = (current.st_dev, current.st_ino, current.st_size, current.st_mtime_ns)
                if not stat.S_ISREG(current.st_mode) or identity != record["identity"]:
                    raise ValueError("Backup changed during retention planning")
        for record in records:
            if record["action"] == "expire":
                (root / record["name"]).unlink()
                record["action"] = "deleted"
    for record in records:
        record.pop("identity", None)
    return {"directory": str(root), "apply": apply, "blocked": blocked, "files": records}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", required=True)
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    try:
        result = run(args.directory, datetime.now(timezone.utc), args.apply)
    except (OSError, ValueError) as error:
        parser.exit(2, str(error) + "\n")
    print(json.dumps(result, ensure_ascii=False))
    return 2 if result["blocked"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
