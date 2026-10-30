import importlib.util
import os
import stat
from datetime import datetime, timedelta, timezone
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location("backup_retention", Path(__file__).parents[1] / "backup-retention.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class BackupRetentionTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir=Path(__file__).parent)
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.now = datetime(2026, 10, 30, tzinfo=timezone.utc)

    def put(self, name):
        path = self.root / name
        path.write_bytes(b"synthetic dump")
        return path

    def test_daily_boundary_and_read_only_default(self):
        path = self.put("daily-tingle-20261023.dump")
        before = MODULE.run(self.root, self.now - timedelta(microseconds=1))
        self.assertEqual("retain", before["files"][0]["action"])
        self.assertEqual("expire", MODULE.run(self.root, self.now)["files"][0]["action"])
        self.assertTrue(path.exists())
        MODULE.run(self.root, self.now, apply=True)
        self.assertFalse(path.exists())

    def test_deployment_age_not_count_or_mtime(self):
        old = self.put("db-tingle-20261022-235959-abcdef0.dump")
        fresh = self.put("db-tingle-20261023-000001-abcdef1.dump")
        MODULE.run(self.root, self.now, apply=True)
        self.assertFalse(old.exists())
        self.assertTrue(fresh.exists())

    def test_future_invalid_unknown_block_all_deletion(self):
        for bad in ("daily-tingle-20261031.dump", "daily-tingle-20261301.dump", "unexpected.dump"):
            with self.subTest(bad=bad):
                old = self.put("daily-tingle_dev-20261001.dump")
                bad_path = self.put(bad)
                self.assertTrue(MODULE.run(self.root, self.now, apply=True)["blocked"])
                self.assertTrue(old.exists())
                bad_path.unlink()

    def test_uploads_and_partial_dumps_are_untouched(self):
        upload = self.put("daily-uploads-20261001.tar.gz")
        partial = self.put("daily-tingle-20261001.dump.tmp")
        MODULE.run(self.root, self.now, apply=True)
        self.assertTrue(upload.exists())
        self.assertTrue(partial.exists())

    def test_symlink_is_blocked(self):
        target = self.put("daily-tingle-20261001.dump")
        link = self.root / "daily-tingle_dev-20261001.dump"
        try:
            link.symlink_to(target)
        except OSError:
            self.skipTest("Symlink creation unavailable")
        self.assertTrue(MODULE.run(self.root, self.now, apply=True)["blocked"])
        self.assertTrue(target.exists())

    def test_symlink_lstat_guard_without_platform_link_permission(self):
        target = self.put("daily-tingle-20261001.dump")
        actual = Path.lstat

        def lstat(path):
            info = actual(path)
            if path == target:
                values = list(info)
                values[0] = stat.S_IFLNK | 0o777
                return os.stat_result(values)
            return info

        with patch.object(Path, "lstat", lstat):
            self.assertTrue(MODULE.run(self.root, self.now, apply=True)["blocked"])
        self.assertTrue(target.exists())


if __name__ == "__main__":
    unittest.main()
