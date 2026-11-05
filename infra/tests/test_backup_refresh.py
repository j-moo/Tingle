"""Shell integration using fake executables and disposable paths, never real backups."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

INFRA = Path(__file__).resolve().parents[1]


class RefreshTest(unittest.TestCase):
    def test_each_namespace_is_attempted_and_failures_are_aggregated(self):
        for failing in ('tingle', 'tingle_dev', 'all', ''):
            with self.subTest(failing=failing), tempfile.TemporaryDirectory() as temp:
                root = Path(temp)
                executable = root / 'python3'
                executable.write_text('#!/bin/sh\necho "$*" >> "$CALLS"\n'
                                      'case " $* " in *" --namespace tingle "*) ns=tingle;; '
                                      '*) ns=tingle_dev;; esac\n'
                                      '[ "$FAIL_NAMESPACE" != all ] && [ "$FAIL_NAMESPACE" != "$ns" ]\n')
                executable.chmod(0o755)
                calls = root / 'calls'
                env = {**os.environ, 'PATH': str(root) + ':' + os.environ['PATH'],
                       'CALLS': str(calls), 'FAIL_NAMESPACE': failing,
                       'BACKUP_EVIDENCE_DIR': str(root / 'metadata'), 'BACKUP_EVIDENCE_SCOPE_ID': 'dev-db'}
                result = subprocess.run(['sh', str(INFRA / 'backup-evidence-refresh.sh')],
                                        env=env, capture_output=True, text=True)
                lines = calls.read_text().splitlines()
                self.assertEqual(len(lines), 2)
                for namespace, line in zip(('tingle', 'tingle_dev'), lines):
                    self.assertIn('--namespace ' + namespace + ' ', line)
                    self.assertIn(str(root / 'metadata' / (namespace + '.restore-generation')), line)
                self.assertEqual(result.returncode, 1 if failing else 0, result.stderr)

    def test_daily_backup_completion_survives_evidence_failure(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); backups = root / 'backups'; backups.mkdir()
            data = root / 'data'; (data / 'uploads').mkdir(parents=True)
            # Redirect only absolute storage paths in a copy of the actual daily script.
            script = (INFRA / 'backup-daily.sh').read_text()
            script = script.replace('--directory /backup', '--directory ' + str(backups))
            script = script.replace('cd /backup', 'cd ' + str(backups))
            script = script.replace('find /backup', 'find ' + str(backups))
            script = script.replace('-C /data', '-C ' + str(data))
            (root / 'daily.sh').write_text(script)
            (root / 'backup-evidence-refresh.sh').write_text('#!/bin/sh\nexit 9\n')
            for command, body in (('python3', 'exit 0'), ('docker', 'echo synthetic-dump')):
                executable = root / command
                executable.write_text('#!/bin/sh\n' + body + '\n'); executable.chmod(0o755)
            env = {**os.environ, 'PATH': str(root) + ':' + os.environ['PATH'],
                   'BACKUP_EVIDENCE_DIR': str(root / 'metadata')}
            result = subprocess.run(['sh', str(root / 'daily.sh')], env=env, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn('백업 완료', result.stdout)
            self.assertIn('evidence refresh failed', result.stderr)
            self.assertEqual(len(list(backups.glob('daily-*.dump'))), 2)
            self.assertEqual(len(list(backups.glob('daily-uploads-*.tar.gz'))), 1)


if __name__ == '__main__':
    unittest.main()
