#!/usr/bin/env python3
"""Publish a metadata-only, atomic DB-dump inventory. Never deletes a backup.

Run after backup/retention, and periodically for freshness. The generation file
is operator-owned and must change before a restore. Output is outside /backup.
All DB dumps for one environment are conservatively included, not inspected.
"""
import argparse
from datetime import datetime, timezone
import fcntl
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import tempfile

SPEC = importlib.util.spec_from_file_location('retention', Path(__file__).with_name('backup-retention.py'))
RETENTION = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RETENTION)


def publish(directory, output, generation_file, namespace, scope_id, now):
    target = Path(output).absolute()
    if any(p.is_symlink() for p in (target, *target.parents)):
        raise ValueError('Metadata paths must not traverse symlinks')
    target.parent.mkdir(parents=True, exist_ok=True, mode=0o750)
    target.parent.chmod(0o750)
    lock = target.with_name(target.name + '.lock')
    descriptor = os.open(lock, os.O_CREAT | os.O_WRONLY | os.O_NOFOLLOW, 0o600)
    try:
        # Keep the inode: unlinking a flock file allows a second independent lock.
        # The kernel releases this lock on close, SIGKILL, or process termination.
        fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
        return _publish(directory, output, generation_file, namespace, scope_id, now)
    finally:
        os.close(descriptor)


def _publish(directory, output, generation_file, namespace, scope_id, now):
    if namespace not in ('tingle', 'tingle_dev'):
        raise ValueError('Unsupported namespace')
    generation_path = Path(generation_file).absolute()
    target = Path(output).absolute()
    root, before = RETENTION.inventory(directory, now)
    if target.is_relative_to(root) or target == generation_path:
        raise ValueError('Metadata must be outside backup storage and generation file')
    for path in (target, generation_path):
        if any(p.is_symlink() for p in (path, *path.parents)):
            raise ValueError('Metadata paths must not traverse symlinks')
    generation = generation_path.read_text(encoding='utf-8').strip()
    if not generation or len(generation) > 100 or not all(c.isascii() and (c.isalnum() or c in '._:-')
                                                         for c in generation):
        raise ValueError('Invalid restore generation')
    if not scope_id or len(scope_id) > 100:
        raise ValueError('Invalid scope')
    if any(r['action'] == 'blocked' for r in before):
        raise ValueError('Unrecognized or unstable backup storage')
    if any(r['name'].endswith('.tmp') for r in before):
        raise ValueError('Backup still being written')
    present = {}
    for record in before:
        name = record['name']
        selected = name.startswith('daily-' + namespace + '-') or (namespace == 'tingle' and
                                                                  name.startswith('db-tingle-'))
        if selected:
            # Identity changes when a same-name dump is replaced. No paths or data enter the BE file.
            opaque = hashlib.sha256(json.dumps([name, record['identity']], separators=(',', ':')).encode()).hexdigest()
            present[opaque] = {'id': opaque, 'present': True, 'expiresAt': record['expiresAt']}
    _, after = RETENTION.inventory(directory, now)
    if before != after or generation_path.read_text(encoding='utf-8').strip() != generation:
        raise ValueError('Storage or restore generation changed during scan')
    entries = dict(present)
    if target.exists():
        previous = json.loads(target.read_text(encoding='utf-8'))
        if (previous['schemaVersion'], previous['namespace'], previous['scopeId']) != (1, namespace, scope_id):
            raise ValueError('Previous inventory scope mismatch')
        if previous['restoreGeneration'] == generation:
            for entry in previous['entries']:
                if entry['id'] not in entries:
                    entries[entry['id']] = {**entry, 'present': False}
    if len(entries) > 10000:
        raise ValueError('Inventory history limit reached; explicit reconciliation required')
    document = {'schemaVersion': 1, 'namespace': namespace, 'scopeId': scope_id,
                'restoreGeneration': generation, 'inspectedAt': now.isoformat(), 'complete': True,
                'entries': sorted(entries.values(), key=lambda e: e['id'])}
    target.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix='.inventory-', dir=target.parent)
    try:
        with os.fdopen(fd, 'w', encoding='utf-8') as stream:
            # The operator provisions the directory group for the BE container.
            os.fchown(stream.fileno(), -1, target.parent.stat().st_gid)
            os.fchmod(stream.fileno(), 0o640)
            json.dump(document, stream, separators=(',', ':'))
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, target)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)
    return document


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    for option in ('directory', 'output', 'generation-file', 'namespace', 'scope-id'):
        parser.add_argument('--' + option, required=True)
    args = parser.parse_args()
    try:
        publish(args.directory, args.output, args.generation_file, args.namespace, args.scope_id,
                datetime.now(timezone.utc))
    except (OSError, ValueError, KeyError, TypeError) as error:
        parser.exit(2, type(error).__name__ + ': evidence not refreshed\n')
