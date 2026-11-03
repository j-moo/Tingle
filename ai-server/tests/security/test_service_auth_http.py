from dataclasses import replace
import logging
from uuid import UUID

from fastapi import FastAPI
from fastapi.testclient import TestClient
import pytest
from starlette.requests import Request

from app.main import app as production_app
from app.middleware.request_id import RequestIDMiddleware
from app.personal_analysis import core
from app.personal_analysis_api.adapter import PersonalAnalysisAdapter
from app.personal_analysis_api.proposals import proposed_label_only_policy
from app.personal_analysis_api.review_samples import synthetic_request
from app.personal_analysis_api.router import PersonalAnalysisRuntime, router
from app.security import service_auth as auth
from .synthetic_jwt import NOW

PATH = "/internal/ai/personal-analyses"
SENTINEL = "synthetic-sensitive-sentinel"
# Expectations come from the confirmed contract, independently of production constants.
CONTRACT_REALM = 'Bearer realm="tingle-ai"'


@pytest.fixture
def secured_client(verifier):
    host = FastAPI()
    host.state.personal_analysis_runtime = PersonalAnalysisRuntime(
        authorize=verifier,
        # Explicitly UNAPPROVED policy in this test app only.
        adapter=PersonalAnalysisAdapter(presentation=proposed_label_only_policy()),
    )
    host.include_router(router)
    with TestClient(RequestIDMiddleware(host)) as client:
        yield client, host


@pytest.mark.parametrize("kind,status", [("succeeded","SUCCEEDED"),("insufficient","INSUFFICIENT")])
@pytest.mark.parametrize("scope", ["ai:execute", "other ai:execute more", "  ai:execute  "])
def test_existing_adapter_with_real_service_auth(secured_client, token_factory, kind, status, scope):
    client, _ = secured_client
    response = client.post(PATH, json=synthetic_request(kind), headers={"Authorization":f"Bearer {token_factory({'scope':scope})}"})
    assert response.status_code == 200
    assert response.json()["status"] == status
    assert response.headers["Cache-Control"] == "no-store"
    assert UUID(response.headers["X-Request-Id"]).version == 4
    assert "WWW-Authenticate" not in response.headers


def failure_headers(case, token_factory, rsa_keys):
    token = token_factory()
    if case == "missing": return []
    if case == "duplicate": return [("Authorization",f"Bearer {token}")]*2
    if case == "combined": return [("Authorization",f"Bearer {token}, Bearer {token}")]
    if case == "multiple": return [("Authorization",f"Bearer {token} {token}")]
    if case == "expired": token=token_factory({"iat":NOW-400,"nbf":NOW-400,"exp":NOW-100})
    if case == "forged": token=token_factory(key=rsa_keys[1])
    if case == "environment": token=token_factory({"iss":"urn:tingle:be:prod","aud":"urn:tingle:ai:prod"})
    if case == "kid": token=token_factory(headers={"kid":"unknown"})
    if case == "aud-array": token=token_factory({"aud":["urn:tingle:ai:test"]})
    if case == "scope-type": token=token_factory({"scope":["ai:execute"]})
    if case == "scope-missing": token=token_factory(omit=["scope"])
    if case == "scope-empty": token=token_factory({"scope":""})
    if case == "scope-insufficient": token=token_factory({"scope":"other"})
    if case == "scope-plain-ai": token=token_factory({"scope":"ai"})
    if case == "scope-prefix": token=token_factory({"scope":"prefix-ai:execute"})
    if case == "scope-suffix": token=token_factory({"scope":"ai:execute-only"})
    if case == "user-jwt": token=token_factory({"sub":"1234"},headers={"typ":"JWT"})
    if case == "lifetime": token=token_factory({"exp":NOW+301})
    if case == "malformed": token="not.a.jwt"
    if case == "empty": return [("Authorization", "Bearer")]
    if case == "scheme": return [("Authorization",f"Basic {token}")]
    return [("Authorization",f"Bearer {token}")]


@pytest.mark.parametrize("case,status,code,challenge", [
    ("missing",401,"AUTH_REQUIRED",CONTRACT_REALM),
    *[(c,400,"INVALID_REQUEST",CONTRACT_REALM+', error="invalid_request"') for c in ("duplicate","combined","multiple")],
    ("expired",401,"ACCESS_TOKEN_EXPIRED",CONTRACT_REALM+', error="invalid_token"'),
    *[(c,401,"ACCESS_TOKEN_INVALID",CONTRACT_REALM+', error="invalid_token"') for c in ("forged","environment","kid","aud-array","scope-type","user-jwt","lifetime","malformed","empty","scheme")],
    *[(c,403,"FORBIDDEN",CONTRACT_REALM+', error="insufficient_scope", scope="ai:execute"') for c in ("scope-missing","scope-empty","scope-insufficient","scope-plain-ai","scope-prefix","scope-suffix")],
    ("outage",503,"SERVICE_UNAVAILABLE",None),
])
@pytest.mark.parametrize("request_id_case", ["missing", "valid", "invalid", "duplicate"])
def test_auth_failures_precede_any_body_read_and_calculation(secured_client, token_factory, rsa_keys, monkeypatch, caplog, capsys, case, status, code, challenge, request_id_case):
    client, host = secured_client
    headers = failure_headers(case,token_factory,rsa_keys)
    incoming_id = "AAAAAAAA-2222-4333-8444-BBBBBBBBBBBB"
    if request_id_case == "valid":
        headers.append(("X-Request-Id", incoming_id))
    elif request_id_case == "invalid":
        headers.append(("X-Request-Id", SENTINEL))
    elif request_id_case == "duplicate":
        headers.extend([("X-Request-Id", incoming_id)] * 2)
    if case == "outage":
        def unavailable(*_): raise RuntimeError(SENTINEL)
        monkeypatch.setattr(auth,"time",unavailable)
    async def no_body(*_a, **_kw): pytest.fail("Authentication failure parsed the body")
    def no_calculation(*_a, **_kw): pytest.fail("Authentication failure executed the calculator")
    monkeypatch.setattr(Request,"body",no_body)
    monkeypatch.setattr(Request,"json",no_body)
    monkeypatch.setattr(PersonalAnalysisAdapter,"analyze",no_calculation)
    monkeypatch.setattr(core,"classify_answers",no_calculation)
    caplog.set_level(logging.DEBUG)
    response=client.post(PATH,content="{"+SENTINEL,headers=[*headers,("Content-Type","application/json")])
    assert response.status_code == status
    assert response.json() == {"code":code,"message":auth.ERRORS[code][1]}
    assert response.headers.get("WWW-Authenticate") == challenge
    assert response.headers["Cache-Control"] == "no-store"
    returned_id = response.headers["X-Request-Id"]
    if request_id_case == "valid":
        assert returned_id == incoming_id
    else:
        assert UUID(returned_id).version == 4
        assert returned_id != incoming_id
    assert len(response.headers.get_list("X-Request-Id")) == 1
    assert len(response.headers.get_list("Cache-Control")) == 1
    captured=capsys.readouterr()
    output=response.text+caplog.text+captured.out+captured.err
    assert SENTINEL not in output
    for name,value in headers:
        if name.lower() == "authorization": assert value not in output


@pytest.mark.parametrize("body,status,code", [("{",400,"INVALID_REQUEST"),("{}",422,"INVALID_REQUEST")])
def test_auth_success_preserves_sanitized_body_errors(secured_client,token_factory,body,status,code):
    response=secured_client[0].post(PATH,content=body,headers={"Authorization":f"Bearer {token_factory()}","Content-Type":"application/json"})
    assert response.status_code == status and response.json()["code"] == code
    assert set(response.json()) == {"code","message"}
    assert response.headers["Cache-Control"] == "no-store"
    assert UUID(response.headers["X-Request-Id"]).version == 4


def test_presentation_remains_unapproved_by_default(secured_client,token_factory):
    client,host=secured_client
    runtime=host.state.personal_analysis_runtime
    host.state.personal_analysis_runtime=replace(runtime,adapter=PersonalAnalysisAdapter())
    response=client.post(PATH,json=synthetic_request("succeeded"),headers={"Authorization":f"Bearer {token_factory()}"})
    assert response.status_code == 503 and response.json()["code"] == "SERVICE_UNAVAILABLE"


@pytest.mark.parametrize("body", ["{}","{",None])
def test_production_routes_still_closed(body,monkeypatch):
    monkeypatch.setattr(PersonalAnalysisAdapter,"analyze",lambda *_:pytest.fail("Production route executed"))
    with TestClient(production_app) as client:
        assert client.get("/health").json() == {"status":"ok"}
        paths = set(client.get("/openapi.json").json()["paths"])
        assert {"/health", "/internal/ai/risk-detections", "/internal/ai/explanations"} <= paths
        assert "/internal/ai/personal-analyses" not in paths
        assert client.post(PATH,content=body).status_code == 404


@pytest.mark.parametrize("changes,code", [({},None),({"scope":"other"},"FORBIDDEN"),({"sub":"user"},"ACCESS_TOKEN_INVALID")])
def test_auth_results_preserve_incoming_request_id(secured_client,token_factory,changes,code):
    request_id="AAAAAAAA-2222-4333-8444-BBBBBBBBBBBB"
    response=secured_client[0].post(PATH,json=synthetic_request("succeeded"),headers={
        "Authorization":f"Bearer {token_factory(changes)}","X-Request-Id":request_id,
    })
    assert response.headers["X-Request-Id"] == request_id
    assert response.headers["Cache-Control"] == "no-store"
    if code: assert response.json()["code"] == code
    else: assert response.status_code == 200
