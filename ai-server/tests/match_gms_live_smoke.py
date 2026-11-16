"""명시적 --live 실행만 실제 GMS를 호출하는 가상 데이터 ASGI smoke.

기존 실행 환경의 개발용 GMS_API_KEY만 사용한다. 키/서비스 JWT/개인 원문은
출력하지 않는다. 임시 합성 release와 메모리에서 만든 서명 키는 운영 승인이 아니다.
"""

import argparse
import copy
import json
import math
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
from app.match_explanation import gms as gms_module
from app.match_explanation.gms import GmsClient
from app.match_explanation.prompts import GENERATION_PROMPT, PROMPT_VERSION, VERIFICATION_PROMPT


def usage_record(envelope, input_rate=None, output_rate=None):
    """Keep numeric usage only. Missing usage/pricing never becomes zero cost."""
    result = {"usageStatus": "NOT_VERIFIED", "inputTokens": None, "outputTokens": None,
              "totalTokens": None, "costStatus": "NOT_VERIFIED", "estimatedCostUsd": None}
    usage = envelope.get("usage")
    if not isinstance(usage, dict):
        return result
    values = [usage.get(name) for name in ("input_tokens", "output_tokens", "total_tokens")]
    if any(type(value) is not int or value < 0 for value in values) or values[2] != sum(values[:2]):
        return result
    result.update(usageStatus="REPORTED", inputTokens=values[0], outputTokens=values[1], totalTokens=values[2])
    if input_rate is not None and output_rate is not None:
        try:
            estimate = (values[0] * input_rate + values[1] * output_rate) / 1_000_000
        except OverflowError:
            return result
        if math.isfinite(estimate):
            result.update(costStatus="ESTIMATED_FROM_SUPPLIED_RATES", estimatedCostUsd=estimate)
    return result


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--live", action="store_true", help="승인된 가상 evidence로 실제 GMS 생성·검토 실행")
    parser.add_argument("--case", choices=["e1", "e2", "e3", "e4", "e5"], action="append")
    parser.add_argument("--shared", action="store_true", help="확정 SHARED 대표 문장으로 검증(Q052 저녁·밤, Q053 nearby)")
    parser.add_argument("--input-usd-per-million", type=float, help="별도로 확인한 입력 100만 토큰 단가")
    parser.add_argument("--output-usd-per-million", type=float, help="별도로 확인한 출력 100만 토큰 단가")
    args = parser.parse_args(argv)
    if not args.live:
        parser.error("실제 호출에는 --live를 명시해야 합니다")
    if len(args.case or []) > 1:
        parser.error("한 번에 합성 case 하나만 허용합니다(생성·검토 최대 2회)")
    rates = (args.input_usd_per_million, args.output_usd_per_million)
    if ((rates[0] is None) != (rates[1] is None)
            or any(rate is not None and (not math.isfinite(rate) or rate < 0) for rate in rates)):
        parser.error("입력·출력 단가는 모두 생략하거나 유한한 음이 아닌 값으로 함께 지정하세요")
    if not os.environ.get("GMS_API_KEY", "").strip():
        print(json.dumps({"status": "BLOCKED", "reason": "DEVELOPMENT_GMS_KEY_MISSING"}))
        return 2
    fixture = json.loads((Path(__file__).parent / "fixtures/mat04-145/be_fact_contract.json")
                         .read_text(encoding="utf-8"))
    cases = [item for item in fixture["items"] if item["id"] in (args.case or ["e1"])]
    calls = []
    attempts = 0
    measurements = []
    original_request = GmsClient.request_text
    original_response_text = gms_module._response_text

    def tracked_response_text(envelope):
        # Refusal/incomplete output can still report billable numeric usage.
        # Record that metadata without changing the original text rejection.
        measurements[-1].update(usage_record(envelope, *rates))
        return original_response_text(envelope)

    async def tracked_request(client, prompt, data):
        nonlocal attempts
        # Count even attempts rejected by the provider call budget below.
        attempts += 1
        # viewer와 승인된 합성 요약만 전송한다. 원본/참조/토큰을 외부로 보내지 않는다.
        expected = {"viewer", "evidence", "allowedPhrasings" if prompt == GENERATION_PROMPT else "candidate"}
        if prompt not in {GENERATION_PROMPT, VERIFICATION_PROMPT} or set(data) != expected:
            raise ValueError("Unexpected smoke payload")
        # A regression in the later closed-gate probes must not incur extra calls.
        if len(calls) >= 2:
            raise RuntimeError("Synthetic smoke provider call budget exhausted")
        calls.append("generation" if prompt == GENERATION_PROMPT else "verification")
        measurement = {"phase": calls[-1], **usage_record({})}
        measurements.append(measurement)
        started = perf_counter()
        try:
            return await original_request(client, prompt, data)
        finally:
            measurement["seconds"] = round(perf_counter() - started, 3)

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
        with (patch.dict(os.environ, environment), patch.object(GmsClient, "request_text", tracked_request),
              patch.object(gms_module, "_response_text", tracked_response_text)):
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
                    print(json.dumps({"providerMeasurements": measurements[before:],
                                      "actualServiceVerification": "PASS" if success else "NOT_VERIFIED",
                                      "releaseApproval": "NOT_VERIFIED", "activationAuthorized": False}), flush=True)
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
                        attempt_count = attempts
                        guarded = client.post("/internal/ai/explanations", json=blocked, headers=headers)
                        safe = (guarded.status_code == expected_status and len(calls) == count
                                and attempts == attempt_count)
                        if gate == "model-scope-empty":
                            safe = safe and guarded.json().get("source") == "RULE_FALLBACK"
                        passed = passed and safe
                        print(json.dumps({"case": spec["id"], "gate": gate, "status": "PASS" if safe else "FAIL",
                                          "http_status": guarded.status_code, "gms_calls": len(calls) - count,
                                          "gms_attempts": attempts - attempt_count}), flush=True)
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
