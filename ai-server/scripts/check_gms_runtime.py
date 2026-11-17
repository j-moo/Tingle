"""Verify the real Docker CMD offline with required keys and dev/prod providers.

Only test containers mount synthetic releases and sitecustomize/MockTransport.
Safety rules and personal v2 run beside both providers in the same application.
The image contains no test switches. Docker networking is disabled.
"""

import argparse
from hashlib import sha256
import json
from pathlib import Path
import subprocess
import sys
from tempfile import TemporaryDirectory
from uuid import uuid4

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa

from scripts.check_personal_runtime import docker


ROOT = Path(__file__).resolve().parents[1]
SOURCE_FILES = ("config.py", "main.py", "development.py", "match_explanation/http_api.py",
                "match_explanation/runtime.py", "match_explanation/development.py", "icebreakers/http_api.py")


SITECUSTOMIZE = r'''
import json
from pathlib import Path
import httpx2 as httpx
from app.match_explanation.gms import ENDPOINT, MODEL, GmsClient

def handle(request):
    assert str(request.url) == ENDPOINT
    mode = Path('/run/gms-test/mode.txt').read_text().strip()
    if mode == 'timeout': raise httpx.ReadTimeout('synthetic timeout', request=request)
    if mode == 'error': return httpx.Response(503)
    body = json.loads(request.content)
    assert body['model'] == MODEL
    data = json.loads(body['input'][1]['content'])
    if 'topic' in data:
        assert data == {'topic': 'walk'}
        result = {'questions': ['산책할 때 어떤 풍경을 좋아하세요?',
            '여행 중 가장 즐거운 순간은 언제인가요?', '걷기 좋은 계절은 언제라고 생각하세요?']}
        if mode == 'review': result['questions'][0] = '산책할 때 전화번호를 알려 주실래요?'
    elif 'candidate' in data:
        result = dict(supported=mode != 'review', directionCorrect=True, privacySafe=True, evidenceIds=['e1'])
    else:
        assert set(data) == {'viewer', 'evidence', 'allowedPhrasings'}
        result = dict(text=data['allowedPhrasings']['e1'][0], evidenceIds=['e1'])
    text = '{invalid' if mode == 'invalid' else json.dumps(result, ensure_ascii=False)
    return httpx.Response(200, json=dict(status='completed', model=MODEL, error=None, output=[
        dict(type='message', role='assistant', content=[dict(type='output_text', text=text)])]))

original = GmsClient.__init__
def initialize(self, key, **kwargs):
    original(self, key, transport=httpx.MockTransport(handle))
GmsClient.__init__ = initialize
'''

PROBE = r'''
import json
import os
from pathlib import Path
import time
import urllib.error
import urllib.request
import uuid
import jwt

def call(path, body=None, token=None):
    headers = {'Content-Type': 'application/json'}
    if token: headers['Authorization'] = 'Bearer ' + token
    raw = json.dumps(body).encode() if body is not None else None
    try:
        with urllib.request.urlopen(urllib.request.Request('http://127.0.0.1:8000' + path,
            data=raw, headers=headers), timeout=5) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as error:
        return error.code, json.load(error)

for attempt in range(60):
    try:
        if call('/health')[0] == 200: break
    except OSError: pass
    time.sleep(.1)
else: raise AssertionError('Application did not become healthy')

root = Path('/run/gms-test')
env = os.environ['APP_ENV']
now = int(time.time())
token = jwt.encode(dict(iss=f'urn:tingle:be:{env}', aud=f'urn:tingle:ai:{env}', sub='tingle-be',
    iat=now, nbf=now, exp=now+300, jti=str(uuid.uuid4()), scope='ai:execute'),
    (root/'private.pem').read_bytes(), algorithm='RS256', headers={'kid':'synthetic-gms','typ':'tingle-service+jwt'})
mode = (root/'mode.txt').read_text().strip()
results = {}
for name, path in [('match','/internal/ai/explanations'), ('ice','/internal/ai/icebreakers')]:
    body = json.loads((root/(name+'.json')).read_bytes())
    assert call(path,body)[0] == 401
    status, result = call(path,body,token)
    expected = 'GENERATED' if mode == 'success' else 'RULE_FALLBACK' if name == 'match' else 'COMMON_TOPIC_TEMPLATE'
    assert status == 200 and result['source'] == expected, (name, status, result.get('source'))
    results[name] = result
    body['manifest']['engineVersion'] = ''
    assert call(path,body,token)[0] == 400
safety = json.loads((root/'safety-request.json').read_bytes())
assert call('/internal/ai/risk-detections',safety)[0] == 401
local_outcomes = []
for text, kind in [('합성 정상 인사','NORMAL'), ('원금 보장하니 인증금을 송금해.','RISK'),
                   ('그거 보내 줘. 알지?','INSUFFICIENT')]:
    safety['contextBundle'][0]['text'] = text
    status, result = call('/internal/ai/risk-detections',safety,token)
    assert status == 200 and result['verdict']['kind'] == kind
    local_outcomes.append(kind)
personal = json.loads((root/'personal-request.json').read_bytes())
status, result = call('/internal/ai/personal-analyses',personal,token)
assert status == 200 and result == json.loads((root/'personal-expected.json').read_bytes())
import importlib.util
assert importlib.util.find_spec('tokenizers') is None
print(json.dumps(dict(mode=mode, results=results, safety=local_outcomes,
                     personal=result['status'], tokenizerInstalled=False), ensure_ascii=False))
'''


def verify(image: str, report: Path | None = None):
    # Host-only test helpers; never copied into the production image.
    sys.path.insert(0, str(ROOT / "tests"))
    from safety48_runtime_support import request_for, synthetic_release

    command = json.loads(docker("image", "inspect", "--format", "{{json .Config.Cmd}}", image))
    assert command == ["python", "-m", "app.main"]
    image_id = docker("image", "inspect", "--format", "{{.Id}}", image)
    key_failures, cases = [], []
    for environment in ("dev", "prod"):
        for key in (None, "", " "):
            arguments = ["docker", "run", "--rm", "--network", "none", "-e", f"APP_ENV={environment}"]
            if key is not None:
                arguments += ["-e", f"GMS_API_KEY={key}"]
            result = subprocess.run([*arguments, image], capture_output=True, encoding="utf-8", timeout=30)
            assert result.returncode != 0 and "GMS_API_KEY is required" in result.stderr
            key_failures.append(dict(environment=environment, key="missing" if key is None else "blank", rejected=True))
    with TemporaryDirectory(prefix="gms-image-synthetic-") as temporary:
        directory = Path(temporary)
        safety = synthetic_release(directory, tokenized=False)
        (directory / "safety-request.json").write_text(json.dumps(request_for(safety)), encoding="utf-8")
        personal = ROOT / "tests/fixtures/personal-analysis-api"
        (directory / "personal-request.json").write_bytes((personal / "succeeded.request.json").read_bytes())
        (directory / "personal-expected.json").write_bytes((personal / "succeeded.response.proposal.json").read_bytes())
        signing_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        (directory / "private.pem").write_bytes(signing_key.private_bytes(serialization.Encoding.PEM,
            serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
        (directory / "public.pem").write_bytes(signing_key.public_key().public_bytes(
            serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo))
        artifact = directory / "ice.bundle"
        artifact.write_bytes(b"synthetic icebreaker release")
        versions = ("surveyVersion", "wordingVersion", "scoringVersion", "typeModelVersion", "analysisVersion",
                    "typeReferenceVersion", "matchingVersion", "productPolicyVersion", "engineVersion")
        manifest = dict(releaseId=str(uuid4()), contentHash=sha256(artifact.read_bytes()).hexdigest(),
                        **dict.fromkeys(versions, "synthetic-v1"))
        (directory / "ice-descriptor.json").write_text(json.dumps(dict(manifest=manifest, artifact=artifact.name)), encoding="utf-8")
        reference = dict(encounter_id=1001, evaluation_ref="c" * 64, version_ref=manifest["releaseId"])
        base = dict(attemptId=str(uuid4()), contextId=1001, manifest=manifest)
        match = dict(**base, contextBundle=dict(
            context=dict(reference=reference, viewer="B", conflicting_source_refs=[], evidence=[dict(
                reference=reference, source_ref="synthetic-source", evidence=dict(id="e1", direction="A_TO_B",
                    kind="ONE_WAY_PREFERENCE_FIT", topic="contact", fact="A의 실제 연락 빈도가 B의 허용 범위에 들어간다."))]),
            scope=dict(reference=reference, viewer="B", version="synthetic-public", model_evidence_ids=["e1"], display_evidence_ids=["e1"])))
        for name, body in (("match", match), ("ice", dict(**base, commonTopicKey="walk"))):
            (directory / f"{name}.json").write_text(json.dumps(body), encoding="utf-8")
        (directory / "sitecustomize.py").write_text(SITECUSTOMIZE, encoding="utf-8")
        for environment in ("dev", "prod"):
            container = "gms-runtime-test-" + uuid4().hex
            (directory / "mode.txt").write_text("success", encoding="utf-8")
            try:
                docker("run", "--detach", "--rm", "--network", "none", "--name", container,
                       "--mount", f"type=bind,source={directory},target=/run/gms-test,readonly",
                       "-e", f"APP_ENV={environment}", "-e", "GMS_API_KEY=synthetic-image-only",
                       "-e", "PYTHONPATH=/run/gms-test:/app",
                       "-e", 'AI_SERVICE_PUBLIC_KEYS={"synthetic-gms":"/run/gms-test/public.pem"}',
                       "-e", "RISK_RELEASE_DESCRIPTOR_FILE=/run/gms-test/" + safety.descriptor.name,
                       "-e", "RISK_LOCAL_RULES_DESCRIPTOR_SHA256=" + safety.test_descriptor_pin,
                       "-e", "ICEBREAKER_RELEASE_DESCRIPTOR_FILE=/run/gms-test/ice-descriptor.json", image)
                observed = []
                for mode in ("success", "timeout", "error", "review", "invalid"):
                    (directory / "mode.txt").write_text(mode, encoding="utf-8")
                    observed.append(json.loads(docker("exec", "-i", container, "python", "-B", "-", data=PROBE)))
                cases.append(dict(environment=environment, outcomes=observed))
            finally:
                docker("rm", "--force", container)
    assert cases[0]["outcomes"] == cases[1]["outcomes"]
    program = ('import json;from hashlib import sha256;from pathlib import Path;names='
               + json.dumps(SOURCE_FILES)
               + ';print(json.dumps({n:sha256((Path("app")/n).read_bytes()).hexdigest() for n in names}))')
    image_hashes = json.loads(docker("run", "--rm", "--network", "none", "--entrypoint", "python",
                                    image, "-B", "-c", program))
    assert image_hashes == {name: sha256((ROOT / "app" / name).read_bytes()).hexdigest() for name in SOURCE_FILES}
    result = dict(imageId=image_id, command=command, network="none", realGmsCalls=0,
                  syntheticOnly=True, approval="NOT_VERIFIED", sameAppSafetyAndPersonal=True,
                  provider="MockTransport", devProdEqual=True, sourceBytesMatch=True,
                  sourceSha256=image_hashes, keyFailures=key_failures, cases=cases)
    if report is not None:
        report.parent.mkdir(parents=True, exist_ok=True)
        report.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(dict(imageId=image_id, devProdEqual=True, realGmsCalls=0, provider="MockTransport")))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", required=True)
    parser.add_argument("--report", type=Path)
    options = parser.parse_args()
    verify(options.image, options.report)
