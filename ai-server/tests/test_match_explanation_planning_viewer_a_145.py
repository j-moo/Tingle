"""열람자 A의 한 방향 planning 설명을 실제 runtime HTTP 경로로 한 번 확인한다."""

import copy
import json

import pytest

from app.match_explanation.gms import GmsClient
from test_match_explanation_service_auth import (
    PATH, client_for, configured, private_key, token,
)


def test_viewer_a_receives_b_planning_fit_via_runtime_route(configured, private_key, monkeypatch):
    def forbid_model_client(*args, **kwargs):
        pytest.fail("규칙 전용 시험에서 실제 GMS 클라이언트를 만들면 안 됩니다")

    monkeypatch.setattr(GmsClient, "from_environment", forbid_model_client)
    auth, descriptor, original_body, _, _ = configured
    body = copy.deepcopy(original_body)
    context = body["contextBundle"]["context"]
    scope = body["contextBundle"]["scope"]
    context["viewer"] = scope["viewer"] = "A"
    record = context["evidence"][0]
    record["evidence"] = {
        "id": "e1", "direction": "A_TO_B", "kind": "ONE_WAY_PREFERENCE_FIT",
        "topic": "planning", "fact": "B의 실제 약속 준비 방식이 A의 선호에 맞는다.",
    }
    context["evidence"] = [record]
    scope["model_evidence_ids"] = []
    scope["display_evidence_ids"] = ["e1"]

    # 기존 합성 인증·release와 실제 runtime 라우터를 사용한다. POST는 한 번뿐이다.
    with client_for(auth, [descriptor]) as client:
        response = client.post(PATH, json=body, headers={
            "Authorization": "Bearer " + token(private_key),
        })
    result = response.json()
    print(json.dumps({
        "viewer": context["viewer"],
        "allowed_evidence": [record["evidence"]],
        "model_evidence_ids": scope["model_evidence_ids"],
        "display_evidence_ids": scope["display_evidence_ids"],
        "http_status": response.status_code,
        "response": result,
    }, ensure_ascii=False))

    assert response.status_code == 200, "설명까지 도달하지 못했으므로 방향은 미검증"
    assert result["source"] == "RULE_FALLBACK"
    # 독립된 기대 문장으로 방향·주제·긍정 의미를 함께 확인한다.
    assert result["text"] == "상대의 약속을 준비하는 방식이 당신의 선호와 맞아요."
