"""Fresh interpreters load a temporary app copy with genuinely broken data."""

import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

import pytest

ROOT = Path(__file__).resolve().parents[1]
PROBE = """
import json
import sys
from app.main import app
from fastapi.testclient import TestClient
with TestClient(app) as client:
    paths = client.get('/openapi.json').json()['paths']
    print(json.dumps({
        'health': client.get('/health').status_code,
        'personal': client.post('/internal/ai/personal-analyses', json={}).status_code,
        'runtime': hasattr(app.state, 'personal_analysis_runtime'),
        'paths': list(paths),
        'risk': client.post('/internal/ai/risk-detections', json={}).status_code,
        'explanation': client.post('/internal/ai/explanations', json={}).status_code,
        'modules': [name for name in sys.modules if name.startswith('app.personal_analysis')],
    }))
"""


@pytest.mark.parametrize("environment", ["prod", "local", "test", "dev"])
@pytest.mark.parametrize("damage", ["missing", "integrity"])
def test_broken_personal_data_is_isolated_only_when_disabled(tmp_path, environment, damage):
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
    completed = subprocess.run([sys.executable, "-B", "-c", PROBE], cwd=tmp_path, env=env,
                               capture_output=True, encoding="utf-8", timeout=30)
    if environment != "prod":
        assert completed.returncode != 0
        assert ("FileNotFoundError" if damage == "missing" else "reference integrity check failed") in completed.stderr
        return
    assert completed.returncode == 0, completed.stderr
    result = json.loads(completed.stdout)
    assert result["health"] == 200 and result["personal"] == 404
    assert result["runtime"] is False and result["modules"] == []
    assert "/internal/ai/personal-analyses" not in result["paths"]
    for path in ("/internal/ai/risk-detections", "/internal/ai/explanations"):
        assert path in result["paths"]
    # Existing risk auth and explanation DTO boundaries still handle requests.
    assert result["risk"] == 503 and result["explanation"] == 400
