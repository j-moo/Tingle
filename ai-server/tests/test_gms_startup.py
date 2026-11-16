"""Exercise the Docker command and legacy factories in fresh, isolated processes."""

import os
from pathlib import Path
import subprocess
import sys

import pytest


ROOT = Path(__file__).resolve().parents[1]


@pytest.mark.parametrize("environment", ["dev", "prod"])
@pytest.mark.parametrize("key", [None, "", " ", "\t\n", "synthetic-private-marker\rkey"])
@pytest.mark.parametrize("entrypoint", ["main", "development", "match-development"])
def test_required_key_fails_before_serving_or_provider_requests(tmp_path, environment, key, entrypoint):
    env = {name: value for name, value in os.environ.items()
           if name.upper() in {"SYSTEMROOT", "WINDIR", "PATH", "TEMP", "TMP"}}
    env.update(APP_ENV=environment, PYTHONPATH=str(ROOT), PYTHONIOENCODING="utf-8")
    if key is not None:
        env["GMS_API_KEY"] = key
    if entrypoint == "main":
        command = [sys.executable, "-B", "-m", "app.main"]
    else:
        module = "app.development" if entrypoint == "development" else "app.match_explanation.development"
        command = [sys.executable, "-B", "-c", f"from {module} import create_app; create_app()"]
    result = subprocess.run(command, cwd=tmp_path, env=env, capture_output=True,
                            encoding="utf-8", timeout=30)
    assert result.returncode != 0
    assert "GMS_API_KEY is required and must be valid" in result.stderr
    assert "synthetic-private-marker" not in result.stdout + result.stderr
