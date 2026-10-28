"""HTTP wiring exists only in this test app, pending BE path/auth agreement."""

import pytest
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from fastapi.testclient import TestClient

from app.main import app as production_app
from app.personal_analysis import core
from app.personal_analysis.dto import PersonalAnalysisResult
from app.personal_analysis.service import CalculationFailure, analyze_personal
from app.personal_analysis.validation import AnalysisInputError


@pytest.fixture
def local_client():
    local_app = FastAPI()

    @local_app.exception_handler(AnalysisInputError)
    async def input_error(_, error):
        return JSONResponse(status_code=422, content={"code": error.code})

    @local_app.exception_handler(CalculationFailure)
    async def computation_error(_, error):
        return JSONResponse(status_code=500, content={"code": error.code})

    @local_app.post("/_test/personal-analysis", response_model=PersonalAnalysisResult)
    async def local_analysis(request: Request):
        try:
            payload = await request.json()
        except ValueError:
            raise AnalysisInputError("INVALID_REQUEST") from None
        return analyze_personal(payload)

    with TestClient(local_app) as client:
        yield client


@pytest.mark.parametrize("unknown,status", [(False, "SUCCEEDED"), (True, "INSUFFICIENT")])
def test_local_http_computation(local_client, normal_request, unknown, status):
    if unknown:
        for answer in normal_request["answers"]:
            answer["value"] = "unknown"
    response = local_client.post("/_test/personal-analysis", json=normal_request)
    assert response.status_code == 200
    assert response.json()["status"] == status


@pytest.mark.parametrize("case,code", [
    ("version", "UNSUPPORTED_SURVEY_VERSION"),
    ("calculation", "UNSUPPORTED_CALCULATION_VERSION"),
    ("missing", "MISSING_ANSWERS"),
    ("duplicate", "DUPLICATE_QUESTION_ID"),
    ("answer", "INVALID_ANSWER"),
    ("metadata", "INVALID_REQUEST"),
])
def test_local_http_input_errors(local_client, normal_request, case, code, caplog):
    if case == "version":
        normal_request["surveyVersion"] = "synthetic-private-token"
    elif case == "calculation":
        normal_request["calculationVersion"] = "latest"
    elif case == "missing":
        normal_request["answers"].pop()
    elif case == "duplicate":
        normal_request["answers"].append(normal_request["answers"][0])
    elif case == "answer":
        normal_request["answers"][0]["value"] = "synthetic-private-token"
    else:
        normal_request["metadata"]["token"] = "synthetic-private-token"
    response = local_client.post("/_test/personal-analysis", json=normal_request)
    assert response.status_code == 422
    assert response.json() == {"code": code}
    assert "synthetic-private-token" not in caplog.text


def test_calculation_failure_is_not_insufficient(local_client, normal_request, monkeypatch, caplog):
    def broken(_):
        raise RuntimeError("synthetic-private-token")
    monkeypatch.setattr(core, "classify_answers", broken)
    response = local_client.post("/_test/personal-analysis", json=normal_request)
    assert response.status_code == 500
    assert response.json() == {"code": "CALCULATION_FAILED"}
    assert "synthetic-private-token" not in caplog.text


def test_no_production_analysis_route_is_published():
    assert set(production_app.openapi()["paths"]) == {"/health"}
