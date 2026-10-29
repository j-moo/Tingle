import asyncio
import json

import httpx2 as httpx
import pytest

from app.match_explanation import (
    DisclosureScope, Evidence, MatchExplanationService, ModelInput, Reference,
    ResolvedContext, SourceEvidence,
)
from app.match_explanation.gms import ENDPOINT, MODEL, GmsClient, GmsError
from app.match_explanation.prompts import GENERATION_PROMPT, VERIFICATION_PROMPT


def request_input():
    return ModelInput(viewer="B", evidence=(Evidence(
        id="e1", direction="B_TO_A", kind="ONE_WAY_PREFERENCE_FIT", topic="contact",
        fact="A의 실제 연락 빈도가 B의 허용 범위에 들어간다.",
    ),))


def envelope(text, **changes):
    result = {
        "status": "completed", "model": MODEL, "error": None,
        "output": [
            {"type": "reasoning", "summary": []},
            {"type": "message", "role": "assistant", "content": [
                {"type": "output_text", "text": text},
            ]},
        ],
    }
    result.update(changes)
    return result


def test_missing_key_needs_no_configuration_or_network():
    assert GmsClient.from_environment({}) is None
    assert GmsClient.from_environment({"GMS_API_KEY": " "}) is None


def test_credentials_are_runtime_only_and_not_in_repr():
    client = GmsClient.from_environment({"GMS_API_KEY": "synthetic-secret"})
    assert isinstance(client, GmsClient)
    assert "synthetic-secret" not in repr(client)


def test_gms_service_generates_and_independently_verifies_minimal_payload(caplog):
    requests = []
    text = "상대의 연락 빈도가 당신의 선호와 맞아요."

    async def handler(request):
        requests.append(json.loads(request.content))
        assert str(request.url) == ENDPOINT
        assert request.method == "POST"
        assert request.headers["Authorization"] == "Bearer synthetic-secret"
        if len(requests) == 1:
            return httpx.Response(200, json=envelope(json.dumps({
                "text": text, "evidenceIds": ["e1"],
            })))
        return httpx.Response(200, json=envelope(json.dumps({
            "supported": True, "directionCorrect": True, "privacySafe": True,
            "evidenceIds": ["e1"],
        })))

    client = GmsClient("synthetic-secret", transport=httpx.MockTransport(handler))
    reference = Reference(
        encounter_id=1001,
        evaluation_ref="synthetic-evaluation", version_ref="synthetic-version",
    )
    context = ResolvedContext(reference=reference, viewer="B", evidence=(SourceEvidence(
        reference=reference, source_ref="synthetic-source-row",
        evidence=request_input().evidence[0],
    ),))
    scope = DisclosureScope(
        reference=reference, viewer="B", version="synthetic-scope",
        model_evidence_ids=frozenset(["e1"]), display_evidence_ids=frozenset(["e1"]),
    )
    result = asyncio.run(MatchExplanationService(
        generator=client, verifier=client, authorize=lambda _: True,
    ).explain(context, scope=scope))
    assert result.explanation.source == "GENERATED"
    assert result.explanation.text == text
    assert len(requests) == 2
    assert requests[0]["input"][0]["content"] == GENERATION_PROMPT
    assert requests[1]["input"][0]["content"] == VERIFICATION_PROMPT
    for body in requests:
        assert body["model"] == MODEL
        assert body["stream"] is False
        assert body["max_output_tokens"] == 2048
        assert "temperature" not in body and "reasoning" not in body
        payload = json.loads(body["input"][1]["content"])
        assert set(payload) <= {"viewer", "evidence", "candidate"}
        serialized = json.dumps(payload)
        for private in ("synthetic-evaluation", "synthetic-version", "synthetic-source-row",
                        str(reference.encounter_id), "synthetic-secret", "synthetic-scope"):
            assert private not in serialized
    assert "synthetic-secret" not in caplog.text


@pytest.mark.parametrize("failure,reason", [
    ("http-error", "MODEL_ERROR"), ("transport-error", "MODEL_ERROR"),
    ("timeout", "MODEL_TIMEOUT"), ("invalid-verdict", "INVALID_OUTPUT"),
])
def test_gms_verification_failure_returns_only_safe_fallback(failure, reason, caplog):
    calls = []

    def handler(request):
        calls.append(request)
        if len(calls) == 1:
            return httpx.Response(200, json=envelope(json.dumps({
                "text": "상대의 연락 빈도가 당신의 선호와 맞아요.", "evidenceIds": ["e1"],
            })))
        if failure == "http-error":
            return httpx.Response(503, text="sensitive upstream detail")
        if failure == "transport-error":
            raise httpx.ConnectError("sensitive upstream detail", request=request)
        if failure == "timeout":
            raise httpx.ReadTimeout("sensitive upstream detail", request=request)
        return httpx.Response(200, json=envelope("{}"))

    reference = Reference(encounter_id=1001, evaluation_ref="synthetic-evaluation",
                          version_ref="synthetic-version")
    context = ResolvedContext(reference=reference, viewer="B", evidence=(SourceEvidence(
        reference=reference, source_ref="synthetic-source-row", evidence=request_input().evidence[0],
    ),))
    scope = DisclosureScope(reference=reference, viewer="B", version="synthetic-scope",
                            model_evidence_ids=frozenset(["e1"]),
                            display_evidence_ids=frozenset(["e1"]))
    before = context.model_dump_json()
    client = GmsClient("synthetic-secret", transport=httpx.MockTransport(handler))
    result = asyncio.run(MatchExplanationService(
        generator=client, verifier=client, authorize=lambda _: True,
    ).explain(context, scope=scope))
    assert len(calls) == 2
    assert result.reason == reason
    assert result.explanation.source == "RULE_FALLBACK"
    assert result.explanation.text == "상대의 연락 방식이 당신의 선호와 맞아요."
    assert result.used_evidence_ids == ("e1",)
    assert context.model_dump_json() == before
    assert "sensitive" not in repr(result) + caplog.text
    assert "synthetic-secret" not in repr(result) + caplog.text


@pytest.mark.parametrize("status", [301, 400, 401, 403, 429, 500, 503])
def test_http_errors_do_not_retry_redirect_or_expose_body(status, caplog):
    calls = []

    def handler(request):
        calls.append(request)
        return httpx.Response(status, text="sensitive upstream body", headers={
            "Location": "https://example.invalid/other-host",
        })

    client = GmsClient("synthetic-secret", transport=httpx.MockTransport(handler))
    with pytest.raises(GmsError) as caught:
        asyncio.run(client.generate(request_input()))
    assert len(calls) == 1
    assert "sensitive" not in str(caught.value) + caplog.text
    assert "synthetic-secret" not in str(caught.value) + caplog.text


@pytest.mark.parametrize("changes", [
    {"status": "incomplete"}, {"status": "failed"}, {"model": "unexpected-model"},
    {"error": {"message": "sensitive detail"}}, {"incomplete_details": {"reason": "max_output_tokens"}},
    {"output": []},
    {"output": [{"type": "message", "role": "assistant", "content": [
        {"type": "refusal", "refusal": "no"},
    ]}]},
    {"output": [{"type": "function_call", "arguments": "do not execute"}]},
    {"output": [{"type": "message", "role": "user", "content": []}]},
    {"output": [{"type": "message", "role": "assistant", "content": [
        {"type": "output_text", "text": "one"}, {"type": "output_text", "text": "two"},
    ]}]},
])
def test_incomplete_refused_changed_model_or_ambiguous_response_is_rejected(changes):
    transport = httpx.MockTransport(lambda _: httpx.Response(200, json=envelope("{}", **changes)))
    with pytest.raises(GmsError):
        asyncio.run(GmsClient("synthetic-secret", transport=transport).generate(request_input()))


@pytest.mark.parametrize("body", ["not json", "null", "[]", "{}", "x" * 32_769],
                         ids=["invalid-json", "null", "array", "missing-fields", "oversized"])
def test_malformed_or_oversized_envelope_is_rejected(body):
    transport = httpx.MockTransport(lambda _: httpx.Response(200, text=body))
    with pytest.raises(GmsError):
        asyncio.run(GmsClient("synthetic-secret", transport=transport).generate(request_input()))


def test_provider_timeout_is_converted_without_sensitive_detail():
    def handler(request):
        raise httpx.ReadTimeout("sensitive timeout detail", request=request)

    client = GmsClient("synthetic-secret", transport=httpx.MockTransport(handler))
    with pytest.raises(TimeoutError) as caught:
        asyncio.run(client.generate(request_input()))
    assert str(caught.value) == "GMS timeout"


def test_transport_failure_is_sanitized():
    def handler(request):
        raise httpx.ConnectError("sensitive network detail", request=request)

    client = GmsClient("synthetic-secret", transport=httpx.MockTransport(handler))
    with pytest.raises(GmsError) as caught:
        asyncio.run(client.generate(request_input()))
    assert "sensitive" not in str(caught.value)
