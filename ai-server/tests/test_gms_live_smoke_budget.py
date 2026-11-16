"""Exercise the live-smoke entry point with MockTransport; no real keys/network."""

import json

import httpx2 as httpx
import pytest

import match_gms_live_smoke as smoke
from app.match_explanation import service as service_module
from app.match_explanation.gms import GmsClient
from app.match_explanation.prompts import GENERATION_PROMPT
from safety_asgi_contract_support import no_external_io
from test_match_explanation_gms import envelope


@pytest.fixture(autouse=True)
def isolated(monkeypatch, tmp_path, no_external_io):
    monkeypatch.chdir(tmp_path)
    monkeypatch.delenv("GMS_API_KEY", raising=False)

    # Keep the smoke's real factory and startup checks, injecting only offline clients.
    def offline_app():
        from app.config import Settings
        from app.main import create_app
        provider = GmsClient.from_environment()
        return create_app(Settings(), _match_gms_client=provider, _icebreaker_gms_client=provider)
    monkeypatch.setattr(smoke, "create_app", offline_app)


@pytest.mark.parametrize("args", [[], ["--live", "--case", "e1", "--case", "e2"],
    ["--live", "--input-usd-per-million", "nan", "--output-usd-per-million", "1"],
    ["--live", "--input-usd-per-million", "1"],
    ["--live", "--input-usd-per-million", "-1", "--output-usd-per-million", "1"]])
def test_invalid_call_scope_or_pricing_stops_before_credential_lookup(monkeypatch, args):
    monkeypatch.setattr(GmsClient, "from_environment", lambda *a: pytest.fail("Credentials accessed"))
    with pytest.raises(SystemExit) as error:
        smoke.main(args)
    assert error.value.code == 2


def test_missing_isolated_key_is_blocked_without_requests(capsys):
    assert smoke.main(["--live"]) == 2
    assert json.loads(capsys.readouterr().out)["reason"] == "DEVELOPMENT_GMS_KEY_MISSING"


@pytest.mark.parametrize("usage", [None, {}, {"input_tokens": True, "output_tokens": 2, "total_tokens": 3},
    {"input_tokens": -1, "output_tokens": 2, "total_tokens": 1},
    {"input_tokens": 1, "output_tokens": 2, "total_tokens": 999}])
def test_missing_or_invalid_provider_usage_is_never_reported_as_free(usage):
    result = smoke.usage_record({"usage": usage}, 1, 2)
    assert result["usageStatus"] == result["costStatus"] == "NOT_VERIFIED"
    assert result["estimatedCostUsd"] is None


def test_unpriced_valid_usage_remains_unverified_cost():
    result = smoke.usage_record({"usage": {"input_tokens": 10, "output_tokens": 2, "total_tokens": 12}})
    assert result["usageStatus"] == "REPORTED" and result["estimatedCostUsd"] is None
    assert result["costStatus"] == "NOT_VERIFIED"


def test_unrepresentable_cost_keeps_usage_without_publishing_infinity():
    result = smoke.usage_record({"usage": {"input_tokens": 10, "output_tokens": 2, "total_tokens": 12}},
                                1e308, 1e308)
    assert result["usageStatus"] == "REPORTED"
    assert result["costStatus"] == "NOT_VERIFIED" and result["estimatedCostUsd"] is None
    json.dumps(result, allow_nan=False)


@pytest.mark.parametrize("extra_model_scope_attempt", [False, True])
def test_default_smoke_records_usage_and_detects_extra_model_scope_attempt(
    monkeypatch, capsys, extra_model_scope_attempt,
):
    calls = []
    generation_attempts = []
    original_generate = GmsClient.generate

    async def tracked_generate(client, request):
        generation_attempts.append(len(calls))
        return await original_generate(client, request)

    monkeypatch.setattr(GmsClient, "generate", tracked_generate)
    if extra_model_scope_attempt:
        original_select = service_module.select_evidence

        def ignore_empty_model_scope(context, scope):
            display, model, reason = original_select(context, scope)
            # Simulate a regression: disclosed evidence is sent despite an empty model scope.
            return display, display if display and not scope.model_evidence_ids else model, reason

        monkeypatch.setattr(service_module, "select_evidence", ignore_empty_model_scope)

    def handler(request):
        body = json.loads(request.content)
        data = json.loads(body["input"][1]["content"])
        calls.append(body)
        if body["input"][0]["content"] == GENERATION_PROMPT:
            output = {"text": data["allowedPhrasings"]["e1"][0], "evidenceIds": ["e1"]}
        else:
            output = {"supported": True, "directionCorrect": True, "privacySafe": True, "evidenceIds": ["e1"]}
        return httpx.Response(200, json=envelope(json.dumps(output), usage={
            "input_tokens": 10, "output_tokens": 2, "total_tokens": 12}))
    provider = GmsClient("synthetic-secret-only", transport=httpx.MockTransport(handler))
    monkeypatch.setenv("GMS_API_KEY", "synthetic-secret-only")
    monkeypatch.setattr(GmsClient, "from_environment", lambda: provider)
    exit_code = smoke.main(["--live", "--input-usd-per-million", "1", "--output-usd-per-million", "2"])
    assert len(calls) == 2
    assert generation_attempts == ([0, 2] if extra_model_scope_attempt else [0])
    assert exit_code == (1 if extra_model_scope_attempt else 0)
    raw = capsys.readouterr().out
    assert "synthetic-secret-only" not in raw and "Bearer " not in raw
    rows = [json.loads(line) for line in raw.splitlines()]
    assert next(row for row in rows if "kind" in row)["status"] == "PASS"
    gates = {row["gate"]: row for row in rows if "gate" in row}
    assert set(gates) == {"model-scope-empty", "wrong-direction", "wrong-source", "model-outside-display"}
    assert gates["model-scope-empty"]["http_status"] == 200
    assert gates["model-scope-empty"]["status"] == ("FAIL" if extra_model_scope_attempt else "PASS")
    assert gates["model-scope-empty"]["gms_attempts"] == (1 if extra_model_scope_attempt else 0)
    assert all(row["status"] == "PASS" for gate, row in gates.items() if gate != "model-scope-empty")
    assert all(row["gms_calls"] == 0 for row in gates.values())
    assert all(row["gms_attempts"] == 0 for gate, row in gates.items() if gate != "model-scope-empty")
    records = next(row["providerMeasurements"] for row in rows if "providerMeasurements" in row)
    assert len(records) == 2 and all(item["seconds"] >= 0 for item in records)
    assert all(item["inputTokens"] == 10 and item["outputTokens"] == 2 for item in records)
    assert all(item["estimatedCostUsd"] == pytest.approx(0.000014) for item in records)
    assert all(row.get("releaseApproval", "NOT_VERIFIED") == "NOT_VERIFIED" for row in rows)


@pytest.mark.parametrize("failure", ["timeout", "503", "403", "refusal", "extra-review"])
def test_smoke_failure_or_extra_provider_attempt_cannot_pass_or_exceed_two_calls(monkeypatch, capsys, failure):
    calls = []
    def handler(request):
        calls.append(1)
        if failure == "timeout":
            raise httpx.ReadTimeout("synthetic-private-error", request=request)
        if failure.isdigit():
            return httpx.Response(int(failure), text="synthetic-private-error")
        if failure == "refusal":
            result = envelope("unused")
            result["output"][-1]["content"] = [{"type": "refusal", "refusal": "synthetic-private-error"}]
            return httpx.Response(200, json=result)
        body = json.loads(request.content)
        data = json.loads(body["input"][1]["content"])
        output = ({"text": data["allowedPhrasings"]["e1"][0], "evidenceIds": ["e1"]}
                  if len(calls) == 1 else
                  {"supported": True, "directionCorrect": True, "privacySafe": True, "evidenceIds": ["e1"]})
        return httpx.Response(200, json=envelope(json.dumps(output)))
    provider = GmsClient("synthetic-secret-only", transport=httpx.MockTransport(handler))
    monkeypatch.setenv("GMS_API_KEY", "synthetic-secret-only")
    monkeypatch.setattr(GmsClient, "from_environment", lambda: provider)
    if failure == "extra-review":
        original = GmsClient.verify
        async def twice(client, request, output):
            await original(client, request, output)
            return await original(client, request, output)
        monkeypatch.setattr(GmsClient, "verify", twice)
    assert smoke.main(["--live"]) == 1
    assert len(calls) == (2 if failure == "extra-review" else 1)
    raw = capsys.readouterr().out
    assert "synthetic-private-error" not in raw and "synthetic-secret-only" not in raw
    rows = [json.loads(line) for line in raw.splitlines()]
    result = next(row for row in rows if "actualServiceVerification" in row)
    assert result["actualServiceVerification"] == "NOT_VERIFIED"
    assert all(item["costStatus"] == "NOT_VERIFIED" for item in result["providerMeasurements"])


@pytest.mark.parametrize("failure", ["refusal", "incomplete"])
def test_rejected_output_records_reported_usage_but_cannot_pass(monkeypatch, capsys, failure):
    calls = []
    def handler(request):
        calls.append(1)
        result = envelope("unused", usage={"input_tokens": 10, "output_tokens": 2, "total_tokens": 12})
        if failure == "refusal":
            result["output"][-1]["content"] = [{"type": "refusal", "refusal": "synthetic-private-rejection"}]
        else:
            result["status"] = "incomplete"
        return httpx.Response(200, json=result)
    provider = GmsClient("synthetic-secret-only", transport=httpx.MockTransport(handler))
    monkeypatch.setenv("GMS_API_KEY", "synthetic-secret-only")
    monkeypatch.setattr(GmsClient, "from_environment", lambda: provider)
    assert smoke.main(["--live", "--input-usd-per-million", "1", "--output-usd-per-million", "2"]) == 1
    assert len(calls) == 1
    raw = capsys.readouterr().out
    assert "synthetic-private-rejection" not in raw and "synthetic-secret-only" not in raw
    rows = [json.loads(line) for line in raw.splitlines()]
    result = next(row for row in rows if "actualServiceVerification" in row)
    assert result["actualServiceVerification"] == "NOT_VERIFIED"
    assert len(result["providerMeasurements"]) == 1
    record = result["providerMeasurements"][0]
    assert record["usageStatus"] == "REPORTED" and record["totalTokens"] == 12
    assert record["estimatedCostUsd"] == pytest.approx(0.000014)
