"""All environments reject genuinely broken reference data at startup."""

import os
from pathlib import Path
import shutil
import subprocess
import sys

import pytest

ROOT = Path(__file__).resolve().parents[1]


@pytest.mark.parametrize("environment", ["local", "test", "dev", "prod"])
@pytest.mark.parametrize("damage", ["missing", "integrity"])
def test_broken_personal_data_fails_startup_in_every_environment(tmp_path, environment, damage):
    shutil.copytree(ROOT / "app", tmp_path / "app", ignore=shutil.ignore_patterns("__pycache__"))
    reference = tmp_path / "app/personal_analysis/data/reference.json"
    if damage == "missing":
        reference.unlink()  # Only the temporary copy is changed.
    else:
        reference.write_text("{}", encoding="utf-8")  # Valid JSON, wrong pinned SHA-256.
    env = {key: value for key, value in os.environ.items()
           if key.upper() in {"SYSTEMROOT", "WINDIR", "PATH", "TEMP", "TMP"}}
    env.update(APP_ENV=environment, AI_SERVICE_PUBLIC_KEYS="{}", RISK_RELEASE_DESCRIPTOR_FILE="",
               EXPLANATION_RELEASE_DESCRIPTOR_FILES="[]", PYTHONPATH=str(tmp_path), PYTHONIOENCODING="utf-8")
    completed = subprocess.run([sys.executable, "-B", "-c", "from app.main import app"], cwd=tmp_path, env=env,
                               capture_output=True, encoding="utf-8", timeout=30)
    assert completed.returncode != 0
    assert ("FileNotFoundError" if damage == "missing" else "reference integrity check failed") in completed.stderr
