"""Raw ASGI chunks exercise limits independently of HTTP client framing rules."""

import asyncio
import json
import time
from unittest.mock import patch
from uuid import uuid4

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from fastapi import FastAPI, Request
import jwt
import pytest

from app.match_explanation.runtime import create_runtime_router
from app.middleware.request_id import RequestIdMiddleware, install_error_handlers
from app.personal_analysis import core
from app.personal_analysis_api import adapter as adapter_module
from app.personal_analysis_api.adapter import PersonalAnalysisAdapter
from app.personal_analysis_api.proposals import proposed_label_only_policy
from app.personal_analysis_api.review_samples import synthetic_request
from app.personal_analysis_api.router import PERSONAL_ANALYSIS_BODY_LIMIT, PersonalAnalysisRuntime, router
from app.risk_detection.http_api import create_router
from app.security.service_auth import ServiceJWTVerifier
from scripts.measure_personal_body import json_bytes, largest_canonical_request, measure

PATH = "/internal/ai/personal-analyses"
REQUEST_ID = str(uuid4())


@pytest.fixture(scope="module")
def credentials():
    private = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    public = private.public_key().public_bytes(
        serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    verifier = ServiceJWTVerifier("test", {"synthetic-limit-key": public})

    def token(scope):
        now = int(time.time())
        return jwt.encode({
            "iss": "urn:tingle:be:test", "aud": "urn:tingle:ai:test", "sub": "tingle-be",
            "iat": now, "nbf": now, "exp": now + 300, "jti": str(uuid4()), "scope": scope,
        }, private, algorithm="RS256", headers={"kid": "synthetic-limit-key", "typ": "tingle-service+jwt"})

    return verifier, token("ai:execute"), token("unrelated:scope")


def make_app(verifier, *, personal=True):
    app = FastAPI()
    install_error_handlers(app)
    app.add_middleware(RequestIdMiddleware)
    def authenticate(request):
        verifier(request)
        return request.state.service_identity

    app.include_router(create_router(authenticate=authenticate))
    app.include_router(create_runtime_router(authenticate))
    if personal:
        app.include_router(router)
        app.state.personal_analysis_runtime = PersonalAnalysisRuntime(
            authorize=verifier,
            adapter=PersonalAnalysisAdapter(presentation=proposed_label_only_policy()),
        )
    return app


def exchange(app, chunks, *, token=None, declared=None, path=PATH):
    headers = [(b"content-type", b"application/json"), (b"x-request-id", REQUEST_ID.encode())]
    if token is not None:
        headers.append((b"authorization", ("Bearer " + token).encode()))
    if declared is not None:
        headers.append((b"content-length", str(declared).encode()))
    reads, messages = [], []

    async def receive():
        index = len(reads)
        assert index < len(chunks), "Read past the supplied body"
        reads.append(index)
        return {"type": "http.request", "body": chunks[index], "more_body": index < len(chunks) - 1}

    async def send(message):
        messages.append(message)

    asyncio.run(app({
        "type": "http", "asgi": {"version": "3.0"}, "http_version": "1.1",
        "method": "POST", "scheme": "http", "path": path, "raw_path": path.encode(),
        "query_string": b"", "headers": headers, "server": ("test", 80), "client": ("test", 1),
    }, receive, send))
    start = next(message for message in messages if message["type"] == "http.response.start")
    body = b"".join(message.get("body", b"") for message in messages if message["type"] == "http.response.body")
    result_headers = dict(start["headers"])
    assert result_headers[b"cache-control"] == b"no-store"
    assert result_headers[b"x-request-id"] == REQUEST_ID.encode()
    return start["status"], json.loads(body), len(reads)


def test_reproducible_catalogue_budget():
    assert measure() == {
        "succeeded": {"compact_bytes": 3551, "pretty_bytes": 6236},
        "insufficient": {"compact_bytes": 4042, "pretty_bytes": 6695},
        "largest_canonical": {"compact_bytes": 4650, "pretty_bytes": 7970},
    }
    assert PERSONAL_ANALYSIS_BODY_LIMIT >= 2 * measure()["largest_canonical"]["pretty_bytes"]


@pytest.mark.parametrize("kind", ["succeeded", "insufficient", "largest"])
@pytest.mark.parametrize("boundary", [False, True])
def test_valid_results_through_limit(credentials, kind, boundary):
    verifier, token, _ = credentials
    app = make_app(verifier)
    payload = largest_canonical_request() if kind == "largest" else synthetic_request(kind)
    body = json_bytes(payload, pretty=True)
    baseline = exchange(app, [body], token=token)[1]
    if boundary:
        body += b" " * (PERSONAL_ANALYSIS_BODY_LIMIT - len(body))
        assert len(body) == PERSONAL_ANALYSIS_BODY_LIMIT
    with patch.object(core, "classify_answers", wraps=core.classify_answers) as calculate:
        status, result, reads = exchange(app, [body[:2000], body[2000:]], token=token, declared=len(body))
    assert status == 200 and result == baseline and reads == 2
    assert calculate.call_count == 1
    if kind != "largest":
        assert result["status"] == {"succeeded": "SUCCEEDED", "insufficient": "INSUFFICIENT"}[kind]


@pytest.mark.parametrize("declared,expected_reads", [
    (PERSONAL_ANALYSIS_BODY_LIMIT + 1, 0), (None, 2), (1, 2),
])
def test_oversized_body_never_parses_or_calculates(credentials, declared, expected_reads, caplog):
    verifier, token, _ = credentials
    app = make_app(verifier)
    body = json_bytes(synthetic_request("succeeded"))
    first = body + b" " * (PERSONAL_ANALYSIS_BODY_LIMIT - len(body))
    with (
        patch.object(Request, "json", side_effect=AssertionError("JSON parsed")) as parse,
        patch.object(adapter_module, "analyze_personal", side_effect=AssertionError("Adapter ran")) as analyze,
        patch.object(core, "classify_answers", side_effect=AssertionError("Calculation ran")) as calculate,
    ):
        status, result, reads = exchange(app, [first, b" ", b"synthetic-sensitive-tail"], token=token, declared=declared)
    assert status == 400 and result["code"] == "INVALID_REQUEST"
    assert set(result) == {"code", "message"}
    assert reads == expected_reads  # Reject overflow immediately, without reading the tail.
    assert parse.call_count == analyze.call_count == calculate.call_count == 0
    assert "synthetic-sensitive-tail" not in json.dumps(result) + caplog.text


@pytest.mark.parametrize("auth,code,status", [
    ("missing", "AUTH_REQUIRED", 401), ("invalid", "ACCESS_TOKEN_INVALID", 401),
    ("scope", "FORBIDDEN", 403),
])
@pytest.mark.parametrize("declared", [None, 1, PERSONAL_ANALYSIS_BODY_LIMIT + 1])
def test_authentication_precedes_limit_and_body(credentials, auth, code, status, declared):
    verifier, _, forbidden = credentials
    token = {"missing": None, "invalid": "not.a.jwt", "scope": forbidden}[auth]
    with (
        patch.object(Request, "body", side_effect=AssertionError("Body read")) as body,
        patch.object(Request, "json", side_effect=AssertionError("JSON parsed")) as parse,
        patch.object(adapter_module, "analyze_personal") as analyze,
        patch.object(core, "classify_answers") as calculate,
    ):
        actual, result, reads = exchange(make_app(verifier), [b" " * (PERSONAL_ANALYSIS_BODY_LIMIT + 1)],
                                         token=token, declared=declared)
    assert actual == status and result["code"] == code and reads == 0
    assert body.call_count == parse.call_count == analyze.call_count == calculate.call_count == 0


@pytest.mark.parametrize("path", ["/internal/ai/risk-detections", "/internal/ai/explanations"])
def test_other_routers_unchanged_by_personal_limit(credentials, path):
    verifier, token, _ = credentials
    # Over the personal ceiling, retain risk's policy gate and explanation's DTO validator.
    body = b"{}" + b" " * PERSONAL_ANALYSIS_BODY_LIMIT
    kwargs = dict(token=token, declared=len(body), path=path)
    before = exchange(make_app(verifier, personal=False), [body], **kwargs)
    after = exchange(make_app(verifier), [body], **kwargs)
    assert before == after
    expected = (422, "POLICY_UNRESOLVED", 0) if path.endswith("risk-detections") else (400, "INVALID_REQUEST", 1)
    assert (after[0], after[1]["code"], after[2]) == expected
