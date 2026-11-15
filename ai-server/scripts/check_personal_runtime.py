"""Local Docker/Uvicorn smoke checks; never deploys or changes repository data.

docker build -t tingle-ai-personal-runtime-161:review .
python -m scripts.check_personal_runtime --image tingle-ai-personal-runtime-161:review
"""

import argparse
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen
from uuid import uuid4

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa
import jwt

ROOT = Path(__file__).resolve().parents[1]
PATH = "/internal/ai/personal-analyses"


def docker(*args):
    return subprocess.run(["docker", *args], check=True, capture_output=True,
                          encoding="utf-8", timeout=30).stdout.strip()


def http(base, path, *, payload=None, token=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    request = Request(base + path, data=payload, headers=headers)
    try:
        response = urlopen(request, timeout=3)
    except HTTPError as error:
        response = error
    with response:
        return response.status, json.load(response)


def check(image, environment, damage=None, *, personal_prod_enabled=False):
    enabled = environment == "dev" or (environment == "prod" and personal_prod_enabled)
    private = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    name = "personal-review-161-" + uuid4().hex[:12]
    with tempfile.TemporaryDirectory(prefix="personal-review-161-") as temporary:
        root = Path(temporary)
        (root / "public.pem").write_bytes(private.public_key().public_bytes(
            serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo,
        ))
        mounts = ["--mount", f"type=bind,source={root},target=/review,readonly"]
        if damage:
            shutil.copytree(ROOT / "app", root / "app", ignore=shutil.ignore_patterns("__pycache__"))
            reference = root / "app/personal_analysis/data/reference.json"
            if damage == "missing":
                reference.unlink()
            else:
                reference.write_text("{}", encoding="utf-8")
            mounts += ["--mount", f"type=bind,source={root / 'app'},target=/app/app,readonly"]
        try:
            docker("run", "--rm", "-d", "--name", name, "-p", "127.0.0.1::8000", *mounts,
                   "-e", "APP_ENV=" + environment,
                   "-e", "PERSONAL_ANALYSIS_PROD_ENABLED=" + str(personal_prod_enabled).lower(), "-e",
                   'AI_SERVICE_PUBLIC_KEYS={"synthetic-smoke-key":"/review/public.pem"}', image)
            port = docker("port", name, "8000/tcp").rsplit(":", 1)[1]
            base = "http://127.0.0.1:" + port
            for attempt in range(40):
                try:
                    assert http(base, "/health")[0] == 200
                    break
                except (URLError, ConnectionError, TimeoutError):
                    time.sleep(0.25)
            else:
                raise AssertionError("Uvicorn did not become ready")
            now = int(time.time())
            token = jwt.encode({
                "iss": f"urn:tingle:be:{environment}", "aud": f"urn:tingle:ai:{environment}",
                "sub": "tingle-be", "iat": now, "nbf": now, "exp": now + 300,
                "jti": str(uuid4()), "scope": "ai:execute",
            }, private, algorithm="RS256", headers={"kid": "synthetic-smoke-key", "typ": "tingle-service+jwt"})
            paths = http(base, "/openapi.json")[1]["paths"]
            outcomes = {}
            for kind, expected in (("succeeded", "SUCCEEDED"), ("insufficient", "INSUFFICIENT")):
                payload = (ROOT / f"tests/fixtures/personal-analysis-api/{kind}.request.json").read_bytes()
                status, result = http(base, PATH, payload=payload, token=token)
                assert status == (200 if enabled else 404)
                if enabled:
                    assert result["status"] == expected
                    proposal = json.loads((ROOT / f"tests/fixtures/personal-analysis-api/{kind}.response.proposal.json")
                                          .read_bytes())
                    assert result == proposal
                outcomes[kind] = {"http": status, "status": result.get("status")}
            if enabled:
                assert PATH in paths
                assert http(base, PATH, payload=b" " * 16385, token=token)[0] == 400
                assert http(base, PATH, payload=b" " * 16385)[0] == 401
                forbidden = jwt.encode({
                    "iss": f"urn:tingle:be:{environment}", "aud": f"urn:tingle:ai:{environment}",
                    "sub": "tingle-be", "iat": now, "nbf": now, "exp": now + 300,
                    "jti": str(uuid4()), "scope": "other",
                }, private, algorithm="RS256", headers={"kid": "synthetic-smoke-key", "typ": "tingle-service+jwt"})
                assert http(base, PATH, payload=b"not-json", token=forbidden)[0] == 403
                invalid = json.loads((ROOT / "tests/fixtures/personal-analysis-api/succeeded.request.json").read_bytes())
                invalid["analysisVersion"] = "unsupported-version"
                assert http(base, PATH, payload=json.dumps(invalid).encode(), token=token)[0] == 409
            else:
                assert PATH not in paths
            for path in ("/internal/ai/risk-detections", "/internal/ai/explanations"):
                assert path in paths
                assert http(base, path, payload=b"{}", token=token)[0] in (400, 422)
            # A new process in the exact container image also checks import/runtime absence.
            state = json.loads(docker("exec", name, "python", "-B", "-c",
                "import json,sys; from app.main import app; print(json.dumps({"
                "'runtime':hasattr(app.state,'personal_analysis_runtime'),"
                "'modules':[n for n in sys.modules if n.startswith('app.personal_analysis')]}))"))
            assert state["runtime"] is enabled
            if not enabled:
                assert state["modules"] == []
            print(json.dumps({"environment": environment, "personalProdEnabled": personal_prod_enabled,
                              "damage": damage, "health": 200,
                              "outcomes": outcomes, **state}), flush=True)
        finally:
            subprocess.run(["docker", "rm", "-f", name], capture_output=True, timeout=30)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", default="tingle-ai-personal-runtime-161:review")
    args = parser.parse_args()
    for environment, damage in (("dev", None), ("prod", None), ("prod", "missing"), ("prod", "integrity")):
        check(args.image, environment, damage)
    check(args.image, "prod", personal_prod_enabled=True)
