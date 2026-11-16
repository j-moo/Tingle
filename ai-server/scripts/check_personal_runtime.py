"""Verify the existing Docker CMD with identical dev/prod personal analysis.

No deployment, external network, approval artifacts or real user data.
Run: python -m scripts.check_personal_runtime --image IMAGE --report OUTPUT.json
"""
import argparse
from hashlib import sha256
import json
from pathlib import Path
import shutil
import subprocess
from tempfile import TemporaryDirectory
import uuid

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa

ROOT = Path(__file__).resolve().parents[1]
SOURCE_FILES = (
    "config.py", "main.py", "personal_analysis_api/proposals.py", "personal_analysis_api/adapter.py",
    "personal_analysis_api/router.py", "personal_analysis/core.py", "personal_analysis/reference.py",
    "personal_analysis/data/reference.json", "personal_analysis/data/questionnaire-SIS-Q-1.1-100.json",
    "personal_analysis/data/provenance.json",
)

PROBE = r'''
from hashlib import sha256
import importlib.util
import json
import os
from pathlib import Path
import time
import urllib.error
import urllib.request
import uuid
import jwt

base = 'http://127.0.0.1:8000'
path = '/internal/ai/personal-analyses'
def call(path, body=None, token=None):
    headers = {'Content-Type': 'application/json'}
    if token: headers['Authorization'] = 'Bearer ' + token
    raw = body if isinstance(body, bytes) else json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(base + path, data=raw, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=5) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as error:
        return error.code, json.load(error)

for attempt in range(60):
    try:
        if call('/health')[0] == 200: break
    except OSError: pass
    time.sleep(.1)
else: raise AssertionError('Application did not become healthy')

environment = os.environ['APP_ENV']
key = Path('/run/personal-test/private.pem').read_bytes()
def token(env=environment, scope='ai:execute'):
    now = int(time.time())
    return jwt.encode(dict(iss=f'urn:tingle:be:{env}', aud=f'urn:tingle:ai:{env}', sub='tingle-be',
        iat=now, nbf=now, exp=now+300, jti=str(uuid.uuid4()), scope=scope), key, algorithm='RS256',
        headers={'kid':'synthetic-personal-image','typ':'tingle-service+jwt'})

paths = call('/openapi.json')[1]['paths']
assert {path,'/health','/internal/ai/explanations','/internal/ai/icebreakers','/internal/ai/risk-detections'} <= paths.keys()
assert call(path,b'not-json')[0] == 401
assert call(path,b'not-json',token('dev' if environment == 'prod' else 'prod'))[0] == 401
assert call(path,b'not-json',token(scope='other'))[0] == 403
assert call(path,b' ' * 16385,token())[0] == 400
assert call(path,b' ' * 16385)[0] == 401
outcomes = []
for kind in ('succeeded','insufficient'):
    body = json.loads(Path('/run/personal-test/'+kind+'.request.json').read_bytes())
    expected = json.loads(Path('/run/personal-test/'+kind+'.response.proposal.json').read_bytes())
    status,result = call(path,body,token())
    assert status == 200 and result == expected
    assert call(path,body,token())[1] == result
    raw = json.dumps(result,ensure_ascii=False,sort_keys=True,separators=(',',':')).encode()
    outcomes.append(dict(case=kind,httpStatus=status,status=result['status'],responseSha256=sha256(raw).hexdigest()))
body['surveyVersion'] = 'unsupported-synthetic'
assert call(path,body,token())[0] == 409
packaged = list(Path('/app').rglob('*'))
assert not any(p.is_file() and ('tests' in p.relative_to('/app').parts or p.suffix in {'.pem','.key'}) for p in packaged)
assert {str(p.relative_to('/app/app')) for p in Path('/app/app').rglob('*.json')} == {
    'personal_analysis/data/reference.json','personal_analysis/data/provenance.json',
    'personal_analysis/data/questionnaire-SIS-Q-1.1-100.json','personal_analysis_api/presentation-review.json'}
assert not Path('/app/app/personal_analysis_api/production.py').exists()
print(json.dumps(dict(environment=environment,health=200,routes=sorted(paths),outcomes=outcomes,
    tokenizerInstalled=importlib.util.find_spec('tokenizers') is not None,packagedTestMaterials=False)))
'''


def docker(*args, data=None):
    result = subprocess.run(["docker", *args], input=data, text=True, encoding="utf-8",
                            capture_output=True, timeout=90)
    if result.returncode:
        raise RuntimeError(f"Owned Docker verification failed ({args[0]}): {result.stderr[:2000]}")
    return result.stdout.strip()


def verify(image: str, report: Path | None = None):
    image_id = docker("image", "inspect", "--format", "{{.Id}}", image)
    command = json.loads(docker("image", "inspect", "--format", "{{json .Config.Cmd}}", image))
    assert command == ["python", "-m", "app.main"]
    cases = []
    with TemporaryDirectory(prefix="personal-runtime-") as name:
        directory = Path(name)
        pair = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        (directory / "private.pem").write_bytes(pair.private_bytes(
            serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
        (directory / "public.pem").write_bytes(pair.public_key().public_bytes(
            serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo))
        for kind in ("succeeded", "insufficient"):
            for suffix in ("request.json", "response.proposal.json"):
                filename = f"{kind}.{suffix}"
                shutil.copyfile(ROOT / "tests/fixtures/personal-analysis-api" / filename, directory / filename)
        for environment in ("dev", "prod"):
            container = "personal-runtime-" + uuid.uuid4().hex[:12]
            try:
                docker("run", "--detach", "--rm", "--network", "none", "--name", container,
                       "--mount", f"type=bind,source={directory},target=/run/personal-test,readonly",
                       "--env", f"APP_ENV={environment}", "--env",
                       'AI_SERVICE_PUBLIC_KEYS={"synthetic-personal-image":"/run/personal-test/public.pem"}', image)
                observed = json.loads(docker("exec", "-i", container, "python", "-B", "-", data=PROBE))
                assert observed["tokenizerInstalled"] is False
                cases.append(observed)
            finally:
                docker("rm", "--force", container)
    assert cases[0]["outcomes"] == cases[1]["outcomes"]
    program = ('import json;from hashlib import sha256;from pathlib import Path;names='
               + json.dumps(SOURCE_FILES)
               + ';print(json.dumps({n:sha256((Path("app")/n).read_bytes()).hexdigest() for n in names}))')
    image_hashes = json.loads(docker("run", "--rm", "--network", "none", "--entrypoint", "python",
                                    image, "-B", "-c", program))
    assert image_hashes == {name: sha256((ROOT / "app" / name).read_bytes()).hexdigest() for name in SOURCE_FILES}
    value = dict(realGmsCalls=0, image=image, imageId=image_id, command=command, network="none",
                 devProdEqual=True, sourceBytesMatch=True, sourceSha256=image_hashes, cases=cases)
    if report is not None:
        report.parent.mkdir(parents=True, exist_ok=True)
        report.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(dict(imageId=image_id, environments=len(cases), devProdEqual=True, sourceBytesMatch=True)))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", default="tingle-ai-personal-runtime-161:review")
    parser.add_argument("--report", type=Path)
    options = parser.parse_args()
    verify(options.image, options.report)
