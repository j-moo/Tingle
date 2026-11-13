"""같은 두 근거에서 표시 허용 목록만 바꿔 실제 ASGI 응답의 주제를 검증한다."""

import copy
import hashlib
import json
import re
from uuid import uuid4

from cryptography.hazmat.primitives import serialization
import pytest

from app.match_explanation.gms import GmsClient
from app.security.service_auth import ServiceAuthenticator, ServiceAuthSettings
from test_match_explanation_service_auth import (
    KID, NOW, PATH, client_for, private_key, token,
)


@pytest.fixture(scope="module")
def shared_request(tmp_path_factory, private_key):
    """두 요청의 식별자, release, 인증과 근거 순서를 동일하게 유지한다."""
    temporary = tmp_path_factory.mktemp("display-subset-145")
    public = temporary / "synthetic-public.pem"
    public.write_bytes(private_key.public_key().public_bytes(
        serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo,
    ))
    auth = ServiceAuthenticator(ServiceAuthSettings(
        environment="test", public_keys={KID: public},
    ), now=lambda: NOW)
    artifact = temporary / "synthetic.bundle"
    artifact.write_bytes(b'{"synthetic": true}\n')
    versions = ("surveyVersion", "wordingVersion", "scoringVersion", "typeModelVersion",
                "analysisVersion", "typeReferenceVersion", "matchingVersion",
                "productPolicyVersion", "engineVersion")
    manifest = {"releaseId": str(uuid4()), **{field: "synthetic-v1" for field in versions},
                "contentHash": hashlib.sha256(artifact.read_bytes()).hexdigest()}
    descriptor = temporary / "synthetic-descriptor.json"
    descriptor.write_text(
        json.dumps({"manifest": manifest, "artifact": artifact.name}), encoding="utf-8",
    )
    reference = {"encounter_id": 1001, "evaluation_ref": "c" * 64,
                 "version_ref": manifest["releaseId"]}
    evidence = [
        {"id": "e1", "direction": "A_TO_B", "kind": "ONE_WAY_PREFERENCE_FIT",
         "topic": "contact", "fact": "A의 연락 방식이 B의 선호에 맞는다."},
        {"id": "e2", "direction": "A_TO_B", "kind": "ONE_WAY_PREFERENCE_FIT",
         "topic": "planning", "fact": "A의 약속 준비 방식이 B의 선호에 맞는다."},
    ]
    bundle = {
        "context": {
            "reference": reference, "viewer": "B", "conflicting_source_refs": [],
            "evidence": [
                {"reference": dict(reference), "source_ref": f"synthetic-source-{item['id']}",
                 "evidence": item}
                for item in evidence
            ],
        },
        "scope": {"reference": dict(reference), "viewer": "B", "version": "synthetic-public-v1",
                  "model_evidence_ids": [], "display_evidence_ids": []},
    }
    body = {"attemptId": str(uuid4()), "contextId": 1001, "manifest": manifest,
            "contextBundle": bundle}
    headers = {"Authorization": "Bearer " + token(private_key)}
    return auth, descriptor, body, headers


@pytest.mark.parametrize("display_ids,required_topic,forbidden_topic", [
    pytest.param(["e1"], r"연락\s*방식", r"약속|준비|planning", id="contact-only"),
    pytest.param(["e2"], r"약속.*준비", r"연락|contact", id="planning-only"),
])
def test_only_display_allowed_topic_survives_runtime_response(
    shared_request, monkeypatch, display_ids, required_topic, forbidden_topic,
):
    def forbid_gms(*args, **kwargs):
        pytest.fail("표시 범위 검사에서는 실제 GMS 클라이언트나 모델을 호출하면 안 됩니다")

    for method in ("from_environment", "generate", "verify"):
        monkeypatch.setattr(GmsClient, method, forbid_gms)

    auth, descriptor, original_body, headers = shared_request
    body = copy.deepcopy(original_body)
    body["contextBundle"]["scope"]["display_evidence_ids"] = list(display_ids)
    with client_for(auth, [descriptor]) as client:
        response = client.post(PATH, json=body, headers=headers)
    result = response.json()
    print(json.dumps({
        "display_evidence_ids": display_ids,
        "http_status": response.status_code,
        "response": result,
    }, ensure_ascii=False))

    assert response.status_code == 200, f"요청이 거부되어 문장 검증에 도달하지 못함: {result}"
    assert result["source"] == "RULE_FALLBACK"
    text = result["text"]
    assert isinstance(text, str) and text.strip()
    assert re.search(required_topic, text)
    assert not re.search(forbidden_topic, text, re.IGNORECASE)
