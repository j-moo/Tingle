import asyncio
from uuid import UUID

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from fastapi.testclient import TestClient
import pytest

from app.middleware.request_id import RequestIDMiddleware


@pytest.fixture
def client():
    host=FastAPI()
    @host.get("/success")
    def success(request: Request):
        return JSONResponse({"requestId":request.state.request_id},headers={"Cache-Control":"public, max-age=60"})
    @host.get("/failure")
    def failure():
        raise RuntimeError("synthetic-sensitive-sentinel")
    with TestClient(RequestIDMiddleware(host),raise_server_exceptions=False) as client:
        yield client


@pytest.mark.parametrize("headers,expected", [
    ([],None),
    ([("X-Request-Id","11111111-2222-4333-8444-555555555555")],"11111111-2222-4333-8444-555555555555"),
    ([("X-Request-Id","AAAAAAAA-2222-4333-8444-BBBBBBBBBBBB")],"AAAAAAAA-2222-4333-8444-BBBBBBBBBBBB"),
    ([("X-Request-Id","synthetic-sensitive-sentinel")],None),
    ([("X-Request-Id","11111111222243338444555555555555")],None),
    ([("X-Request-Id","11111111-2222-4333-8444-555555555555")]*2,None),
    ([("X-Request-Id","11111111-2222-4333-8444-555555555555, 11111111-2222-4333-8444-555555555555")],None),
])
@pytest.mark.parametrize("path,status", [("/success",200),("/failure",500),("/missing",404)])
def test_request_id_and_no_store_for_success_and_failure(client,headers,expected,path,status,caplog):
    response=client.get(path,headers=headers)
    assert response.status_code == status
    value=response.headers["X-Request-Id"]
    assert value == expected if expected else UUID(value).version == 4
    assert response.headers["Cache-Control"] == "no-store"
    assert len(response.headers.get_list("X-Request-Id")) == 1
    if status == 200: assert response.json()["requestId"] == value
    assert "synthetic-sensitive-sentinel" not in response.text+caplog.text


def test_generated_request_ids_are_per_request(client):
    values={client.get("/success").headers["X-Request-Id"] for _ in range(10)}
    assert len(values) == 10


def test_auth_denial_never_reads_asgi_body(verifier):
    from app.personal_analysis_api.router import PersonalAnalysisRuntime, router
    host=FastAPI()
    host.include_router(router)
    host.state.personal_analysis_runtime=PersonalAnalysisRuntime(authorize=verifier)
    messages=[]
    async def receive(): pytest.fail("Unauthenticated body was received")
    async def send(message): messages.append(message)
    scope={"type":"http","asgi":{"version":"3.0"},"http_version":"1.1","method":"POST","scheme":"http",
           "path":"/internal/ai/personal-analyses","root_path":"","query_string":b"","headers":[],"server":("test",80),"client":("test",1)}
    asyncio.run(RequestIDMiddleware(host)(scope,receive,send))
    assert messages[0]["status"] == 401
    assert dict(messages[0]["headers"])[b"cache-control"] == b"no-store"


def test_non_http_scope_passes_unchanged():
    scope={"type":"websocket"}
    observed=[]
    async def app(actual_scope,receive,send): observed.append(actual_scope)
    asyncio.run(RequestIDMiddleware(app)(scope,None,None))
    assert observed == [scope]
    assert "state" not in scope
