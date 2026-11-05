import importlib.util
import os
import stat
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location('evidence', Path(__file__).resolve().parents[1] / 'backup-evidence.py')
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class EvidenceTest(unittest.TestCase):
    @unittest.skipUnless(os.geteuid() == 0, 'Requires disposable root Linux container')
    def test_other_uid_reads_via_directory_group(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); root.chmod(0o755)
            backups = root / 'backup'; backups.mkdir()
            generation = root / 'generation'; generation.write_text('restore-1')
            folder = root / 'metadata'; folder.mkdir(); os.chown(folder, 0, 1000)
            output = folder / 'inventory.json'
            MODULE.publish(backups, output, generation, 'tingle_dev', 'local-db', datetime.now(timezone.utc))
            code = ('import os, pathlib, sys; os.setgroups([]); os.setgid(int(sys.argv[2])); '
                    'os.setuid(1001); pathlib.Path(sys.argv[1]).read_bytes()')
            allowed = subprocess.run([sys.executable, '-c', code, str(output), '1000'], capture_output=True)
            denied = subprocess.run([sys.executable, '-c', code, str(output), '1001'], capture_output=True)
            self.assertEqual(allowed.returncode, 0, allowed.stderr)
            self.assertNotEqual(denied.returncode, 0)
            self.assertIn(b'PermissionError', denied.stderr)

    def test_unfinished_dump_preserves_last_snapshot_until_next_refresh(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); backups = root / 'backup'; backups.mkdir()
            generation = root / 'generation'; generation.write_text('restore-1')
            output = root / 'inventory.json'
            args = (backups, output, generation, 'tingle_dev', 'local-db', datetime.now(timezone.utc))
            MODULE.publish(*args)
            previous = output.read_bytes()
            unfinished = backups / 'daily-tingle_dev-20261105.dump.tmp'
            unfinished.write_bytes(b'synthetic')
            with self.assertRaisesRegex(ValueError, 'still being written'):
                MODULE.publish(*args)
            self.assertEqual(output.read_bytes(), previous)
            unfinished.unlink()
            MODULE.publish(*args)

    def test_persistent_lock_and_group_readable_atomic_replacement(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); backups = root / 'backup'; backups.mkdir()
            generation = root / 'generation'; generation.write_text('restore-1')
            output = root / 'metadata' / 'inventory.json'
            args = (backups, output, generation, 'tingle_dev', 'local-db', datetime.now(timezone.utc))
            previous_umask = os.umask(0o077)
            try:
                MODULE.publish(*args)
                MODULE.publish(*args)  # Existing lock inode must not block the next run.
            finally:
                os.umask(previous_umask)
            self.assertEqual(stat.S_IMODE(output.stat().st_mode), 0o640)
            self.assertEqual(stat.S_IMODE(output.parent.stat().st_mode), 0o750)
            self.assertEqual(output.stat().st_gid, output.parent.stat().st_gid)
            self.assertTrue(output.with_name(output.name + '.lock').exists())

    def test_live_lock_blocks_and_sigkill_releases_without_removing_file(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); backups = root / 'backup'; backups.mkdir()
            generation = root / 'generation'; generation.write_text('restore-1')
            output = root / 'inventory.json'; lock = root / 'inventory.json.lock'
            args = (backups, output, generation, 'tingle', 'local-db', datetime.now(timezone.utc))
            MODULE.publish(*args)
            previous = output.read_bytes()
            code = ('import fcntl, sys, time; f = open(sys.argv[1], "w"); '
                    'fcntl.flock(f, fcntl.LOCK_EX); print("locked", flush=True); time.sleep(60)')
            process = subprocess.Popen([sys.executable, '-c', code, str(lock)], stdout=subprocess.PIPE,
                                       text=True)
            try:
                self.assertEqual(process.stdout.readline().strip(), 'locked')
                with self.assertRaises(BlockingIOError):
                    MODULE.publish(*args)
                self.assertEqual(output.read_bytes(), previous)
                process.kill()
                process.wait(timeout=5)
                MODULE.publish(*args)
                self.assertTrue(lock.exists())
            finally:
                if process.poll() is None:
                    process.kill()
                    process.wait(timeout=5)
                process.stdout.close()

    def test_namespace_expiry_and_actual_absence_are_distinct(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); backups = root / 'backup'; backups.mkdir()
            generation = root / 'generation'; generation.write_text('restore-1')
            output = root / 'metadata' / 'inventory.json'
            daily = backups / 'daily-tingle_dev-20261001.dump'; daily.write_bytes(b'synthetic')
            (backups / 'db-tingle-20261001-000000-abcdef1.dump').write_bytes(b'production')
            now = datetime(2026, 11, 5, tzinfo=timezone.utc)
            def scan():
                return MODULE.publish(backups, output, generation, 'tingle_dev', 'local-db', now)
            first = scan()
            self.assertEqual(len(first['entries']), 1)
            self.assertTrue(first['entries'][0]['present'])  # Expired but still physically present.
            daily.unlink()  # Disposable fixture only, never a real dump.
            second = scan()
            self.assertEqual(first['entries'][0]['id'], second['entries'][0]['id'])
            self.assertFalse(second['entries'][0]['present'])
            generation.write_text('restore-2')
            self.assertEqual(scan()['entries'], [])  # Old generation evidence must not survive restore.

    def test_invalid_storage_never_replaces_valid_snapshot(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); backups = root / 'backup'; backups.mkdir()
            generation = root / 'generation'; generation.write_text('restore-1')
            output = root / 'inventory.json'
            args = (backups, output, generation, 'tingle', 'local-db', datetime.now(timezone.utc))
            MODULE.publish(*args)
            previous = output.read_bytes()
            (backups / 'unknown.dump').write_bytes(b'not approved')
            with self.assertRaises(ValueError):
                MODULE.publish(*args)
            self.assertEqual(output.read_bytes(), previous)


if __name__ == '__main__':
    unittest.main()
