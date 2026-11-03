"""-146 contract tests. Confirmed version/evidence; test-only display and auth.

Reading/TypeReference fields were compared with Notion's 2026-10-27 result
page (dfdc23fd598982dc885281a14be12080); the repository OpenAPI has the same
two nested schemas. Its older UUID/full-manifest envelope is not used here.
"""

import json
import logging
from copy import deepcopy
from dataclasses import replace
from pathlib import Path

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.main import app as production_app
from app.personal_analysis import core
from app.personal_analysis.dto import SurveyMetadata
from app.personal_analysis.reference import CALCULATION_VERSION, CATALOGUE
from app.personal_analysis.service import analyze_personal
from app.personal_analysis_api import adapter as adapter_module
from app.personal_analysis_api.adapter import (
    APPROVED_VERSION_BINDINGS, APPROVED_VERSION_BINDING, PersonalAnalysisAdapter,
    PresentationPolicy, VersionBinding, reference_evidence, to_core_input,
)
from app.personal_analysis_api.dto import PersonalAnalysisRequest, Reading
from app.personal_analysis_api.router import (
    InternalAPIError, PersonalAnalysisRuntime, router,
)
from conftest import request_for

PATH = "/internal/ai/personal-analyses"
SECRET = "synthetic-sensitive-sentinel"
HEADERS = {"X-Test-Service-Token": SECRET}


def be_request(core_request):
    return dict(
        analysisId=2048, surveyVersion=core_request["surveyVersion"],
        analysisVersion=APPROVED_VERSION_BINDING.analysis_version,
        answers=deepcopy(core_request["answers"]),
    )


def test_only_difference(row, index, answers):
    # Synthetic approved presentation, for exercising the boundary only.
    return Reading(
        key="test-only-difference", templateId="test-only-difference-template",
        text="계약 테스트 전용 문장입니다.", priority=7,
        evidenceQuestionIds=[f"Q{row['selfId']:03}", f"Q{row['id']:03}"],
    )


# Delegate the confirmed evidence rule; wording/auth above remain test-only.
def test_only_reference_evidence(row, answers):
    return reference_evidence(row, answers)


# Helpers are not pytest test cases despite their explicit test-only names.
test_only_difference.__test__ = False
test_only_reference_evidence.__test__ = False


@pytest.fixture
def configured_adapter(js_reference):
    templates = {
        (r["key"], r["title"]): "test-only-reading-template"
        for case in js_reference["cases"] for r in case["result"]["readings"]
    }
    return PersonalAnalysisAdapter(
        version_bindings=(APPROVED_VERSION_BINDING,),
        presentation=PresentationPolicy(templates, test_only_difference, test_only_reference_evidence),
    )


@pytest.fixture
def api_client(configured_adapter):
    local_app = FastAPI()
    local_app.include_router(router)
    local_app.state.personal_analysis_runtime = PersonalAnalysisRuntime(
        authorize=lambda request: request.headers.get("X-Test-Service-Token") == SECRET,
        adapter=configured_adapter,
    )
    with TestClient(local_app) as client:
        yield client


def post(client, payload):
    return client.post(PATH, json=payload, headers=HEADERS)


@pytest.fixture
def gap_request(normal_request):
    actual = next(a["value"] for a in normal_request["answers"] if a["questionId"] == "Q030")
    for answer in normal_request["answers"]:
        if answer["questionId"] == "Q065":
            answer["value"] = 1 if actual >= 3 else 5
        elif answer["questionId"] == "Q098":
            answer["value"] = "close"
    assert analyze_personal(normal_request).selfPreferenceDifferences
    return normal_request


@pytest.mark.parametrize("name,type_status,status", [
    ("normal", "ASSIGNED", "SUCCEEDED"),
    ("partial-score", "ASSIGNED", "SUCCEEDED"),
    ("borderline", "BORDERLINE", "SUCCEEDED"),
    ("tied", "TIED", "SUCCEEDED"),
    ("all-unknown", "INSUFFICIENT", "INSUFFICIENT"),
    ("preference-only", "INSUFFICIENT", "INSUFFICIENT"),
    ("self-similarity", "ASSIGNED", "SUCCEEDED"),
])
def test_required_http_outcomes(api_client, js_reference, name, type_status, status):
    case = next(c for c in js_reference["cases"] if c["name"] == name)
    core_request = request_for(case["answers"])
    expected = analyze_personal(core_request)
    response = post(api_client, be_request(core_request))
    assert response.status_code == 200
    assert response.headers["Cache-Control"] == "no-store"
    result = response.json()
    assert result["status"] == status and result["typeStatus"] == type_status
    for key in ("scores", "primaryType", "tiedTypes", "missingQuestionIds"):
        assert result[key] == getattr(expected, key)
    assert len(result["readings"]) == len(expected.readings)
    assert len(result["selfPreferenceDifferences"]) == len(expected.selfPreferenceDifferences)
    assert len(result["typeRecommendations"]) == len(expected.typeReferences)
    if name == "partial-score":
        assert result["scores"]["E"] is None
    if name == "tied":
        assert result["primaryType"] is None and len(result["tiedTypes"]) == 16
    if name == "all-unknown":
        assert len(result["missingQuestionIds"]) == 60
    if name == "preference-only":
        assert len(result["typeRecommendations"]) == 3


def test_input_mapping_and_analysis_id_never_reaches_core(normal_request, configured_adapter, monkeypatch):
    seen = []
    original = adapter_module.analyze_personal

    def capture(payload):
        seen.append(payload.model_dump())
        return original(payload)

    monkeypatch.setattr(adapter_module, "analyze_personal", capture)
    payload = be_request(normal_request)
    first = configured_adapter.analyze(PersonalAnalysisRequest.model_validate(payload))
    payload["analysisId"] = 2**63 - 1
    payload["answers"].reverse()
    second = configured_adapter.analyze(PersonalAnalysisRequest.model_validate(payload))
    assert first == second
    assert set(seen[0]) == {"surveyVersion", "calculationVersion", "answers", "metadata"}
    assert seen[0]["calculationVersion"] == CALCULATION_VERSION
    assert seen[0]["metadata"] == dict(ownAge=None, desiredAgeMin=None, desiredAgeMax=None)
    assert seen[0]["answers"] == normal_request["answers"]
    assert "analysisId" not in seen[0]


def test_nullable_metadata_preserves_core_result(normal_request):
    request = PersonalAnalysisRequest.model_validate(be_request(normal_request))
    mapped = to_core_input(request, APPROVED_VERSION_BINDING)
    result = analyze_personal(mapped)
    mapped.metadata = SurveyMetadata(ownAge=40, desiredAgeMin=25, desiredAgeMax=55)
    assert analyze_personal(mapped) == result


def test_result_field_conversion_and_public_item_schemas(api_client, gap_request):
    response = post(api_client, be_request(gap_request))
    assert response.status_code == 200
    actual, expected = response.json(), analyze_personal(gap_request)
    assert set(actual) == {
        "status", "surveyVersion", "analysisVersion", "scores", "typeStatus", "primaryType",
        "tiedTypes", "readings", "selfPreferenceDifferences", "typeRecommendations", "missingQuestionIds",
    }
    assert actual["analysisVersion"] == "SIS-A-1.0"
    assert "classification" not in actual and "calculationVersion" not in actual
    for converted, original in zip(actual["readings"], expected.readings, strict=True):
        assert converted == dict(
            key=original["key"], templateId="test-only-reading-template", text=original["text"],
            evidenceQuestionIds=[f"Q{e['id']:03}" for e in original["evidence"]], priority=original["priority"],
        )
    for converted, original in zip(actual["selfPreferenceDifferences"], expected.selfPreferenceDifferences, strict=True):
        assert converted == test_only_difference(original, expected.selfPreferenceDifferences.index(original), {
            int(a["questionId"][1:]): a["value"] for a in gap_request["answers"]
        }).model_dump()
    for converted, original in zip(actual["typeRecommendations"], expected.typeReferences, strict=True):
        assert converted == dict(
            typeCode=original["type"], score=original["score"], mode=expected.referenceMode,
            evidenceQuestionIds=test_only_reference_evidence(original, {
                int(a["questionId"][1:]): a["value"] for a in gap_request["answers"]
            }),
        )
    # Pin only the two compared schemas; root docs may be removed by the team.
    contract = Path(__file__).with_name("fixtures") / "personal-analysis-contract.json"
    schemas = json.loads(contract.read_text(encoding="utf-8"))["schemas"]
    for collection, name in (("readings", "Reading"), ("selfPreferenceDifferences", "Reading"), ("typeRecommendations", "TypeReference")):
        schema = schemas[name]
        assert schema["additionalProperties"] is False
        assert set(schema["required"]) == set(schema["properties"])
        for row in actual[collection]:
            assert set(row) == set(schema["required"])
            assert all(isinstance(qid, str) and qid.startswith("Q") for qid in row["evidenceQuestionIds"])
            if name == "TypeReference":
                assert schema["properties"]["score"]["minimum"] <= row["score"] <= schema["properties"]["score"]["maximum"]
                assert row["mode"] in schema["properties"]["mode"]["enum"]
            else:
                assert all(isinstance(row[k], str) and row[k] for k in ("key", "templateId", "text"))
                assert type(row["priority"]) is int


@pytest.mark.parametrize("field,value", [
    ("surveyVersion", "unknown-version"), ("analysisVersion", "latest"),
    ("analysisVersion", CALCULATION_VERSION), ("analysisVersion", "DRAFT-PERSONAL-REF-1"),
])
def test_unsupported_version_is_409_before_calculation(api_client, normal_request, monkeypatch, field, value):
    def should_not_run(_):
        pytest.fail("Unsupported versions must not execute the core")
    monkeypatch.setattr(adapter_module, "analyze_personal", should_not_run)
    payload = be_request(normal_request)
    payload[field] = value
    response = post(api_client, payload)
    assert response.status_code == 409
    assert response.json()["code"] == "ANALYSIS_VERSION_UNSUPPORTED"


def test_confirmed_version_does_not_approve_presentation_by_default(api_client, normal_request):
    assert APPROVED_VERSION_BINDINGS == (APPROVED_VERSION_BINDING,)
    runtime = api_client.app.state.personal_analysis_runtime
    api_client.app.state.personal_analysis_runtime = replace(runtime, adapter=PersonalAnalysisAdapter())
    response = post(api_client, be_request(normal_request))
    assert response.status_code == 503
    assert response.json()["code"] == "SERVICE_UNAVAILABLE"


def test_binding_cannot_select_unknown_core(api_client, normal_request):
    runtime = api_client.app.state.personal_analysis_runtime
    bad = VersionBinding("SIS-Q-1.1-100", "SIS-A-1.0", "unimplemented")
    api_client.app.state.personal_analysis_runtime = replace(runtime, adapter=replace(runtime.adapter, version_bindings=(bad,)))
    assert post(api_client, be_request(normal_request)).status_code == 409


@pytest.mark.parametrize("case", ["missing", "duplicate", "id", "value", "metadata", "extra", "version-length", "no-value"])
def test_invalid_inputs_are_422(api_client, normal_request, case):
    payload = be_request(normal_request)
    if case == "missing":
        payload["answers"].pop()
    elif case == "duplicate":
        payload["answers"].append(deepcopy(payload["answers"][0]))
    elif case == "id":
        payload["answers"][0]["questionId"] = SECRET
    elif case == "value":
        payload["answers"][0]["value"] = SECRET
    elif case == "metadata":
        payload["metadata"] = {"ownAge": 30}
    elif case == "extra":
        payload[SECRET] = SECRET
    elif case == "version-length":
        payload["analysisVersion"] = "x" * 31
    else:
        del payload["answers"][0]["value"]
    response = post(api_client, payload)
    assert response.status_code == 422
    assert response.json()["code"] == "INVALID_REQUEST"
    assert SECRET not in response.text and "detail" not in response.json()


@pytest.mark.parametrize("value", [None, True, 1.0, "2048", 0, -1, 2**63])
def test_analysis_id_is_strict_positive_int64(api_client, normal_request, value):
    payload = be_request(normal_request)
    payload["analysisId"] = value
    assert post(api_client, payload).status_code == 422


@pytest.mark.parametrize("value", [None, True, "3", [], {}, 0, 6, 2.5])
def test_invalid_answer_values(api_client, normal_request, value):
    payload = be_request(normal_request)
    payload["answers"][0]["value"] = value
    assert post(api_client, payload).status_code == 422


@pytest.mark.parametrize("qid,valid", [("Q001", False), ("Q061", True), ("Q097", True), ("Q098", False)])
def test_any_is_not_unknown_or_missing(api_client, normal_request, qid, valid):
    payload = be_request(normal_request)
    next(a for a in payload["answers"] if a["questionId"] == qid)["value"] = "any"
    response = post(api_client, payload)
    assert response.status_code == (200 if valid else 422)
    if valid:
        assert qid not in response.json()["missingQuestionIds"]


def test_missing_ids_are_self_unknown_only(api_client, normal_request):
    payload = be_request(normal_request)
    for answer in payload["answers"]:
        if answer["questionId"] in ("Q001", "Q060", "Q061", "Q100"):
            answer["value"] = "unknown"
    response = post(api_client, payload)
    assert response.status_code == 200
    assert response.json()["missingQuestionIds"] == ["Q001", "Q060"]


@pytest.mark.parametrize("body", ['{"answers":', '"' + SECRET + '"', '{"' + SECRET + '":'])
def test_malformed_and_wrong_shape_json_are_sanitized(api_client, body):
    response = api_client.post(PATH, content=body, headers={**HEADERS, "Content-Type": "application/json"})
    assert response.status_code == (422 if body.startswith('"') else 400)
    assert response.json()["code"] == "INVALID_REQUEST"
    assert SECRET not in response.text


@pytest.mark.parametrize("headers", [{}, {"X-Test-Service-Token": "wrong"}])
def test_authentication_precedes_body_parsing(api_client, headers):
    response = api_client.post(PATH, content="{" + SECRET, headers=headers)
    assert response.status_code == 401
    assert response.json()["code"] == "AUTH_REQUIRED"


@pytest.mark.parametrize("body_case", ["valid", "empty", "malformed"])
def test_production_analysis_route_is_absent_and_never_executes(normal_request, monkeypatch, body_case):
    calls = []

    def unexpected_analysis(*args, **kwargs):
        calls.append(True)
        raise AssertionError("Production must not execute personal analysis")

    monkeypatch.setattr(PersonalAnalysisAdapter, "analyze", unexpected_analysis)
    monkeypatch.setattr(adapter_module, "analyze_personal", unexpected_analysis)
    assert not hasattr(production_app.state, "personal_analysis_runtime")
    assert all(getattr(route, "path", None) != PATH for route in production_app.routes)
    with TestClient(production_app) as client:
        health = client.get("/health")
        assert health.status_code == 200 and health.json() == {"status": "ok"}
        openapi = client.get("/openapi.json")
        assert openapi.status_code == 200
        paths = set(openapi.json()["paths"])
        assert {"/health", "/internal/ai/risk-detections", "/internal/ai/explanations"} <= paths
        assert "/internal/ai/personal-analyses" not in paths
        if body_case == "valid":
            response = post(client, be_request(normal_request))
        elif body_case == "empty":
            response = client.post(PATH, json={})
        else:
            response = client.post(PATH, content="{", headers={"Content-Type": "application/json"})
        assert response.status_code == 404
        assert response.json()["code"] == "RESOURCE_NOT_FOUND"
        assert set(response.json()) == {"code", "message"}
    assert calls == []


def test_explicit_test_router_without_authentication_fails_closed():
    local_app = FastAPI()
    local_app.include_router(router)
    with TestClient(local_app) as client:
        response = client.post(PATH, json={})
        assert response.status_code == 503
        assert response.json()["code"] == "SERVICE_UNAVAILABLE"


def test_authenticator_can_report_forbidden_without_leaking(api_client):
    def forbidden(_):
        raise InternalAPIError(403, "FORBIDDEN")
    runtime = api_client.app.state.personal_analysis_runtime
    api_client.app.state.personal_analysis_runtime = replace(runtime, authorize=forbidden)
    response = post(api_client, {})
    assert response.status_code == 403 and response.json()["code"] == "FORBIDDEN"


@pytest.mark.parametrize("authorized", [True, False])
def test_existing_async_verifier_can_be_connected(api_client, normal_request, authorized):
    async def verifier(_):
        return authorized
    runtime = api_client.app.state.personal_analysis_runtime
    api_client.app.state.personal_analysis_runtime = replace(runtime, authorize=verifier)
    response = post(api_client, be_request(normal_request))
    assert response.status_code == (200 if authorized else 401)


def test_analysis_id_is_available_to_request_tracing(configured_adapter, normal_request):
    local_app = FastAPI()
    local_app.include_router(router)
    local_app.state.personal_analysis_runtime = PersonalAnalysisRuntime(
        authorize=lambda _: True, adapter=configured_adapter,
    )
    observed = []

    @local_app.middleware("http")
    async def trace_id(request, call_next):
        response = await call_next(request)
        observed.append(request.state.analysis_id)
        return response
    with TestClient(local_app) as client:
        response = post(client, be_request(normal_request))
    assert response.status_code == 200
    assert observed == [2048]
    assert "analysisId" not in response.json()


def test_explicit_test_app_openapi_describes_sanitized_errors(api_client):
    response = api_client.get("/openapi.json")
    assert response.status_code == 200
    schema = response.json()
    assert PATH in schema["paths"]
    for status in (400, 401, 403, 409, 422, 500, 503):
        error = schema["paths"][PATH]["post"]["responses"][str(status)]["content"]["application/json"]["schema"]
        assert error == {"$ref": "#/components/schemas/ErrorResponse"}
    assert set(schema["components"]["schemas"]["ErrorResponse"]["properties"]) == {"code", "message"}


@pytest.mark.parametrize("missing", ["templates", "differences", "reference-evidence"])
def test_unagreed_presentation_is_never_replaced_with_empty_success(api_client, gap_request, missing):
    runtime = api_client.app.state.personal_analysis_runtime
    policy = runtime.adapter.presentation
    if missing == "templates":
        policy = replace(policy, reading_template_ids={})
    elif missing == "differences":
        policy = replace(policy, difference_reading=None)
    else:
        policy = replace(policy, reference_evidence=None)
    api_client.app.state.personal_analysis_runtime = replace(runtime, adapter=replace(runtime.adapter, presentation=policy))
    response = post(api_client, be_request(gap_request))
    assert response.status_code == 503 and response.json()["code"] == "SERVICE_UNAVAILABLE"
    assert "scores" not in response.json()


@pytest.mark.parametrize("failure", ["calculation", "presentation", "authentication"])
def test_exceptions_never_echo_sensitive_data(api_client, normal_request, monkeypatch, caplog, capsys, failure):
    caplog.set_level(logging.DEBUG)
    def broken(*_):
        raise RuntimeError(SECRET)
    runtime = api_client.app.state.personal_analysis_runtime
    if failure == "calculation":
        monkeypatch.setattr(core, "classify_answers", broken)
    elif failure == "presentation":
        policy = replace(runtime.adapter.presentation, reference_evidence=broken)
        api_client.app.state.personal_analysis_runtime = replace(runtime, adapter=replace(runtime.adapter, presentation=policy))
    else:
        api_client.app.state.personal_analysis_runtime = replace(runtime, authorize=broken)
    response = post(api_client, be_request(normal_request))
    assert response.status_code == (503 if failure == "authentication" else 500)
    assert response.json()["code"] == ("SERVICE_UNAVAILABLE" if failure == "authentication" else "INTERNAL_ERROR")
    assert response.headers["Cache-Control"] == "no-store"
    captured = capsys.readouterr()
    assert SECRET not in response.text + caplog.text + captured.out + captured.err


def test_validation_does_not_log_body_or_credentials(api_client, normal_request, caplog, capsys):
    caplog.set_level(logging.DEBUG)
    payload = be_request(normal_request)
    payload["answers"][0]["value"] = {SECRET: SECRET}
    response = post(api_client, payload)
    assert response.status_code == 422
    captured = capsys.readouterr()
    assert SECRET not in response.text + caplog.text + captured.out + captured.err


def test_q97_catalogue_policy_is_explicitly_preserved(api_client, normal_request):
    assert "age" in CATALOGUE["questions"][96]["responseSchema"]["oneOf"][0]["items"]["enum"]
    baseline = post(api_client, be_request(normal_request)).json()
    payload = be_request(normal_request)
    payload["answers"][96]["value"] = ["age"]
    response = post(api_client, payload)
    assert response.status_code == 200 and response.json() == baseline


@pytest.mark.parametrize("authorization_result", [None, 1, "true", [], {}])
def test_verifier_requires_exact_true_before_parsing(api_client, monkeypatch, authorization_result):
    runtime = api_client.app.state.personal_analysis_runtime
    api_client.app.state.personal_analysis_runtime = replace(
        runtime, authorize=lambda _: authorization_result,
    )
    calls = []
    monkeypatch.setattr(PersonalAnalysisAdapter, "analyze", lambda *args: calls.append(True))
    response = api_client.post(PATH, content="{" + SECRET, headers=HEADERS)
    assert response.status_code == 401
    assert response.json()["code"] == "AUTH_REQUIRED"
    assert response.headers["Cache-Control"] == "no-store"
    assert SECRET not in response.text
    assert calls == []


@pytest.mark.parametrize("status,code", [(401, "AUTH_REQUIRED"), (403, "FORBIDDEN")])
def test_service_verifier_errors_precede_invalid_body(api_client, monkeypatch, status, code):
    def deny(_):
        raise InternalAPIError(status, code)

    runtime = api_client.app.state.personal_analysis_runtime
    api_client.app.state.personal_analysis_runtime = replace(runtime, authorize=deny)
    calls = []
    monkeypatch.setattr(PersonalAnalysisAdapter, "analyze", lambda *args: calls.append(True))
    response = api_client.post(PATH, content="{" + SECRET, headers=HEADERS)
    assert response.status_code == status
    assert response.json()["code"] == code
    assert response.headers["Cache-Control"] == "no-store"
    assert SECRET not in response.text
    assert calls == []
