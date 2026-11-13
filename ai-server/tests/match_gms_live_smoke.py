"""명시적 --live 실행만 실제 GMS를 호출하는 가상 데이터 ASGI smoke.

기존 실행 환경의 개발용 GMS_API_KEY만 사용한다. 키/서비스 JWT/개인 원문은
출력하지 않는다. 임시 합성 release와 메모리에서 만든 서명 키는 운영 승인이 아니다.
"""

import argparse
import copy
import json
import os
import sys
from hashlib import sha256
from pathlib import Path
from tempfile import TemporaryDirectory
from time import perf_counter, time
from unittest.mock import patch
from uuid import uuid4

import jwt
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from fastapi.testclient import TestClient

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.match_explanation.development import create_app
from app.match_explanation.gms import GmsClient
from app.match_explanation.prompts import GENERATION_PROMPT, PROMPT_VERSION, VERIFICATION_PROMPT


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--live", action="store_true", help="승인된 가상 evidence로 실제 GMS 생성·검토 실행")
    parser.add_argument("--case", choices=["e1", "e2", "e3", "e4", "e5"], action="append")
    parser.add_argument("--shared", action="store_true", help="확정 SHARED 대표 문장으로 검증(Q052 저녁·밤, Q053 nearby)")
    args = parser.parse_args()
    if not args.live:
        parser.error("실제 호출에는 --live를 명시해야 합니다")
    if not os.environ.get("GMS_API_KEY", "").strip():
        print(json.dumps({"status": "BLOCKED", "reason": "DEVELOPMENT_GMS_KEY_MISSING"}))
        return 2
    fixture = json.loads((Path(__file__).parent / "fixtures/mat04-145/be_fact_contract.json")
                         .read_text(encoding="utf-8"))
    cases = [item for item in fixture["items"] if item["id"] in (args.case or [f"e{i}" for i in range(1, 6)])]
    calls = []
    original_request = GmsClient.request_text

    async def tracked_request(client, prompt, data):
        # viewer와 승인된 합성 요약만 전송한다. 원본/참조/토큰을 외부로 보내지 않는다.
        expected = {"viewer", "evidence", "allowedPhrasings" if prompt == GENERATION_PROMPT else "candidate"}
        if prompt not in {GENERATION_PROMPT, VERIFICATION_PROMPT} or set(data) != expected:
            raise ValueError("Unexpected smoke payload")
        calls.append("generation" if prompt == GENERATION_PROMPT else "verification")
        return await original_request(client, prompt, data)

    passed = True
    with TemporaryDirectory(prefix="match-gms-synthetic-") as temporary:
        folder = Path(temporary)
        signing_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        public = folder / "public.pem"
        public.write_bytes(signing_key.public_key().public_bytes(
            serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo))
        artifact = folder / "synthetic.bundle"
        artifact.write_bytes(b'{"synthetic":true}\n')
        versions = ("surveyVersion", "wordingVersion", "scoringVersion", "typeModelVersion",
                    "analysisVersion", "typeReferenceVersion", "matchingVersion", "productPolicyVersion", "engineVersion")
        manifest = {"releaseId": str(uuid4()), **{name: "synthetic-live-smoke-v1" for name in versions},
                    "contentHash": sha256(artifact.read_bytes()).hexdigest()}
        descriptor = folder / "descriptor.json"
        descriptor.write_text(json.dumps({"manifest": manifest, "artifact": artifact.name}), encoding="utf-8")
        reference = {"encounter_id": 145, "evaluation_ref": "c" * 64, "version_ref": manifest["releaseId"]}
        environment = {
            "APP_ENV": "dev", "MATCH_INSIGHT_GMS_DEV_ENABLED": "true",
            "AI_SERVICE_PUBLIC_KEYS": json.dumps({"synthetic-smoke": str(public)}),
            "EXPLANATION_RELEASE_DESCRIPTOR_FILES": json.dumps([str(descriptor)]),
            "RISK_LOCAL_RULES_ENABLED": "false", "RISK_RELEASE_DESCRIPTOR_FILE": "",
            "ICEBREAKER_DEV_ENABLED": "false", "ICEBREAKER_RELEASE_DESCRIPTOR_FILE": "",
        }
        with patch.dict(os.environ, environment), patch.object(GmsClient, "request_text", tracked_request):
            with TestClient(create_app()) as client:
                for spec in cases:
                    now = int(time())
                    token = jwt.encode({
                        "iss": "urn:tingle:be:dev", "aud": "urn:tingle:ai:dev", "sub": "tingle-be",
                        "iat": now, "nbf": now, "exp": now + 300, "jti": str(uuid4()), "scope": "ai:execute",
                    }, signing_key, algorithm="RS256", headers={"typ": "tingle-service+jwt", "kid": "synthetic-smoke"})
                    headers = {"Authorization": "Bearer " + token}
                    evidence = {"id": spec["id"], "topic": spec["topic"], "direction": "A_TO_B",
                                "kind": "ONE_WAY_PREFERENCE_FIT",
                                "fact": f"B가 바라는 {spec['label']}에 A가 잘 맞아요."}
                    if args.shared:
                        rows = [row for row in fixture["shared"] if row["id"] == spec["id"]]
                        shared = (next((row for row in rows if row.get("slots") == ["evening", "night"]), None)
                                  if spec["id"] == "e4" else
                                  next((row for row in rows if row.get("range") == "nearby"), None)
                                  if spec["id"] == "e5" else next(iter(rows), None))
                        if shared is None:
                            print(json.dumps({"case": spec["id"], "status": "BLOCKED", "reason": "SHARED_FIXTURE_MISSING"}), flush=True)
                            passed = False
                            continue
                        evidence.update(direction="BOTH", kind="SHARED_REPORTED_BEHAVIOR", fact=shared["fact"])
                    body = {"attemptId": str(uuid4()), "contextId": 145, "manifest": manifest,
                            "contextBundle": {
                                "context": {"reference": reference, "viewer": "A", "conflicting_source_refs": [],
                                            "evidence": [{"reference": reference, "source_ref": spec["source_ref"], "evidence": evidence}]},
                                "scope": {"reference": reference, "viewer": "A", "version": "synthetic-public-and-model",
                                          "model_evidence_ids": [spec["id"]], "display_evidence_ids": [spec["id"]]},
                            }}
                    before = len(calls)
                    started = perf_counter()
                    response = client.post("/internal/ai/explanations", json=body, headers=headers)
                    result = response.json()
                    success = (response.status_code == 200 and result.get("source") == "GENERATED"
                               and result.get("promptVersion") == PROMPT_VERSION
                               and calls[before:] == ["generation", "verification"])
                    passed = passed and success
                    print(json.dumps({"case": spec["id"], "kind": evidence["kind"], "status": "PASS" if success else "FAIL",
                                      "http_status": response.status_code, "source": result.get("source"),
                                      "promptVersion": result.get("promptVersion"), "text": result.get("text"),
                                      "gms_calls": calls[before:], "seconds": round(perf_counter() - started, 2)}, ensure_ascii=False), flush=True)
                    # 같은 앱에서 model scope 미승인 및 잘못된 계약이 외부 전송 없이 끝나야 한다.
                    for gate in ("model-scope-empty", "wrong-direction", "wrong-source", "model-outside-display"):
                        blocked = copy.deepcopy(body)
                        bundle = blocked["contextBundle"]
                        expected_status = 503
                        if gate == "model-scope-empty":
                            bundle["scope"]["model_evidence_ids"] = []
                            expected_status = 200
                        elif gate == "wrong-direction":
                            bundle["context"]["evidence"][0]["evidence"]["direction"] = "B_TO_A"
                        elif gate == "wrong-source":
                            bundle["context"]["evidence"][0]["source_ref"] += ":unapproved"
                        else:
                            bundle["scope"]["display_evidence_ids"] = []
                            expected_status = 400
                        count = len(calls)
                        guarded = client.post("/internal/ai/explanations", json=blocked, headers=headers)
                        safe = guarded.status_code == expected_status and len(calls) == count
                        if gate == "model-scope-empty":
                            safe = safe and guarded.json().get("source") == "RULE_FALLBACK"
                        passed = passed and safe
                        print(json.dumps({"case": spec["id"], "gate": gate, "status": "PASS" if safe else "FAIL",
                                          "http_status": guarded.status_code, "gms_calls": len(calls) - count}), flush=True)
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
