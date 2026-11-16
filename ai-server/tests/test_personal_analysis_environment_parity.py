"""The real personal-analysis policy and validation are identical in dev/prod."""
from copy import deepcopy

import pytest

from app.personal_analysis_api.review_samples import synthetic_request
from test_personal_analysis_production import PATH, headers, host, signing_key


@pytest.mark.parametrize("kind", ["succeeded", "insufficient"])
def test_dev_and_prod_return_identical_results_without_extra_configuration(host, headers, kind):
    results = []
    for environment in ("dev", "prod"):
        with host(environment=environment) as client:
            paths = client.get("/openapi.json").json()["paths"]
            assert {PATH, "/health", "/internal/ai/risk-detections", "/internal/ai/explanations",
                    "/internal/ai/icebreakers"} <= paths.keys()
            response = client.post(PATH, json=synthetic_request(kind), headers=headers(environment=environment))
            assert response.status_code == 200
            results.append(response.json())
    assert results[0] == results[1]


@pytest.mark.parametrize("case,status,code", [
    ("missing-answer", 422, "INVALID_REQUEST"),
    ("duplicate-answer", 422, "INVALID_REQUEST"),
    ("unsupported-version", 409, "ANALYSIS_VERSION_UNSUPPORTED"),
])
def test_dev_and_prod_preserve_identical_validation_failures(host, headers, case, status, code):
    payload = deepcopy(synthetic_request("succeeded"))
    if case == "missing-answer":
        payload["answers"].pop()
    elif case == "duplicate-answer":
        payload["answers"][0] = deepcopy(payload["answers"][1])
    else:
        payload["surveyVersion"] = "unsupported-version"
    results = []
    for environment in ("dev", "prod"):
        with host(environment=environment) as client:
            response = client.post(PATH, json=payload, headers=headers(environment=environment))
            assert response.status_code == status and response.json()["code"] == code
            results.append(response.json())
    assert results[0] == results[1]
