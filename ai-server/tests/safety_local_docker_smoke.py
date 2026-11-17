"""Real Docker CMD, network disabled, ephemeral synthetic releases/JWT only.

This mounts test consistency pins read-only. It creates no approved Release,
does not bake synthetic artifacts into the image, and never calls GMS.
"""

import argparse
import json
from pathlib import Path
import subprocess
import tempfile
import time
from uuid import uuid4

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa
import jwt

from safety48_runtime_support import message, request_for, synthetic_release


CLIENT = """
import json,sys,urllib.request,urllib.error
data=json.load(sys.stdin)
headers={'Content-Type':'application/json'}
if data.get('token'): headers['Authorization']='Bearer '+data['token']
request=urllib.request.Request('http://127.0.0.1:8000'+data['path'],headers=headers,
    data=json.dumps(data['payload']).encode() if 'payload' in data else None)
try: response=urllib.request.urlopen(request,timeout=3)
except urllib.error.HTTPError as error: response=error
print(json.dumps({'status':response.status,'body':json.loads(response.read()),
    'cacheControl':response.headers.get('Cache-Control')}))
"""


def docker(*args, stdin=None):
    result = subprocess.run(["docker", *args], input=stdin, capture_output=True,
                            text=True, encoding="utf-8", timeout=40)
    if result.returncode:
        raise RuntimeError("Docker smoke command failed: " + args[0])
    return result.stdout.strip()


def request(name, path, payload=None, token=None):
    data = {"path": path, "token": token}
    if payload is not None:
        data["payload"] = payload
    return json.loads(docker("exec", "-i", name, "python", "-c", CLIENT, stdin=json.dumps(data)))


def credential(key, environment, scope="ai:execute"):
    now = int(time.time())
    return jwt.encode(dict(iss=f"urn:tingle:be:{environment}", aud=f"urn:tingle:ai:{environment}",
        sub="tingle-be", iat=now, nbf=now, exp=now+300, jti=str(uuid4()), scope=scope), key,
        algorithm="RS256", headers={"kid": "docker-synthetic", "typ": "tingle-service+jwt"})


def verify(image):
    config = json.loads(docker("image", "inspect", image))[0]["Config"]
    assert config["Cmd"] == ["python", "-m", "app.main"]
    assert config["User"] == "appuser" and "APP_ENV=prod" in config["Env"]
    docker("run", "--rm", "--network", "none", "--entrypoint", "python", image, "-c",
           "import importlib.util; assert importlib.util.find_spec('tokenizers') is None")
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    observations = []
    with tempfile.TemporaryDirectory(prefix="safety-local-docker-") as temporary:
        for environment in ("dev", "prod"):
            for case in ("valid", "absent", "missing-pin", "missing-artifact", "old-source"):
                directory = Path(temporary) / f"{environment}-{case}"
                directory.mkdir()
                release = synthetic_release(directory, tokenized=False)
                if case == "missing-artifact":
                    release.artifact.unlink()
                if case == "old-source":
                    artifact = json.loads(release.artifact.read_bytes())
                    artifact["sources"]["runtime.py"] = "485f25f0d1b7aee55b7a98ae9cf9c213306478cedb576638c623632295009a7c"
                    release.rewrite_artifact(artifact)
                (directory / "public.pem").write_bytes(key.public_key().public_bytes(
                    serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo))
                name = "safety-local-smoke-" + uuid4().hex[:12]
                descriptor = "" if case == "absent" else "/review/" + release.descriptor.name
                pin = "" if case in {"absent", "missing-pin"} else release.test_descriptor_pin
                try:
                    docker("run", "--detach", "--name", name, "--network", "none",
                        "--mount", f"type=bind,source={directory},target=/review,readonly",
                        "--env", "APP_ENV=" + environment,
                        "--env", "GMS_API_KEY=synthetic-safety-docker-startup-only",
                        "--env", "RISK_RELEASE_DESCRIPTOR_FILE=" + descriptor,
                        "--env", "RISK_LOCAL_RULES_DESCRIPTOR_SHA256=" + pin,
                        "--env", 'AI_SERVICE_PUBLIC_KEYS={"docker-synthetic":"/review/public.pem"}', image)
                    deadline = time.monotonic() + 25
                    ready = False
                    while time.monotonic() < deadline:
                        state = json.loads(docker("inspect", name))[0]["State"]
                        if not state["Running"]:
                            assert case not in {"valid", "absent"} and state["ExitCode"] != 0
                            break
                        try:
                            ready = request(name, "/health")["status"] == 200
                        except RuntimeError:
                            pass
                        if ready:
                            break
                        time.sleep(0.2)
                    if case not in {"valid", "absent"}:
                        assert not ready and not state["Running"]
                        observations.append([environment, case, "STARTUP_REJECTED"])
                        continue
                    assert ready
                    token = credential(key, environment)
                    route = "/internal/ai/risk-detections"
                    result = request(name, route, request_for(release), token)
                    assert result["status"] == (422 if case == "absent" else 200)
                    assert request(name, route, {}, None)["status"] == 401
                    if case == "valid":
                        for text, kind in (("합성 정상 인사", "NORMAL"),
                                ("원금 보장하니 인증금을 송금해.", "RISK"), ("그거 보내 줘. 알지?", "INSUFFICIENT")):
                            result = request(name, route, request_for(release, [message(text)]), token)
                            assert result["status"] == 200 and result["body"]["verdict"]["kind"] == kind
                            assert result["cacheControl"] == "no-store"
                        assert request(name, route, {}, credential(key, environment, "wrong"))["status"] == 403
                        assert request(name, route, request_for(release, [message("https://example.invalid")]), token)["status"] == 400
                        release.artifact.write_bytes(release.artifact.read_bytes() + b" ")
                        assert request(name, route, request_for(release), token)["status"] == 422
                    observations.append([environment, case, "PASS"])
                finally:
                    docker("rm", "--force", name)
    return dict(syntheticOnly=True, approval="NOT_VERIFIED", tokenizerInstalled=False,
                actualCmd=config["Cmd"], checks=observations)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", required=True)
    parser.add_argument("--report", type=Path)
    options = parser.parse_args()
    result = verify(options.image)
    if options.report is not None:
        options.report.write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps(result))
