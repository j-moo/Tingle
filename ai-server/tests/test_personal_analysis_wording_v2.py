"""Preserve successful result fields and refuse unverified presentation evidence."""
from copy import deepcopy
from dataclasses import replace
from itertools import product
from typing import get_args
from uuid import UUID

from fastapi import FastAPI
from fastapi.testclient import TestClient
from pydantic import ValidationError
from starlette.exceptions import HTTPException
import pytest

from app.personal_analysis import core
from app.personal_analysis.dto import PersonalAnalysisResult
from app.personal_analysis.reference import CATALOGUE
from app.personal_analysis.service import CalculationFailure, analyze_personal
from app.personal_analysis.validation import AnalysisInputError, valid_answer
from app.personal_analysis_api import adapter as module
from app.personal_analysis_api.adapter import (
    APPROVED_VERSION_BINDING, FormatterEvidenceMismatch, PersonalAnalysisAdapter, PresentationUnavailable, to_core_input,
)
from app.personal_analysis_api.proposals import proposed_label_only_policy
from app.personal_analysis_api.router import PersonalAnalysisRuntime, router
from app.personal_analysis_api.dto import PersonalAnalysisResponse
from app.security.service_auth import ServiceAuthError
from scripts.export_personal_wording_review import (
    assert_wording, gap_cases, known_issues, reading_cases, request, values,
)

PATH = "/internal/ai/personal-analyses"
QUESTION_BY_ORDINAL = {q["ordinal"]: q for q in CATALOGUE["questions"]}
TRACE_ID = "11111111-2222-4333-8444-555555555555"
PRIVATE_MARKERS = ("private-answer", "private-member", "private-jwt", "private-original-text", "987654321")


def assert_diagnostic(caplog, response, template_id, error_type):
    records = [r for r in caplog.records if r.name == "app.personal_analysis_api.router"]
    assert len(records) == 1
    record, = records
    assert (record.requestId, record.templateId, record.errorType) == (
        response.headers["X-Request-Id"], template_id, error_type)
    assert record.getMessage() == (
        f"personal_analysis_error requestId={record.requestId} templateId={template_id} errorType={error_type}")
    assert record.exc_info is None and record.stack_info is None
    assert record.args == (record.requestId, template_id, error_type)
    assert response.headers["Cache-Control"] == "no-store"
    assert "WWW-Authenticate" not in response.headers
    assert not any(marker in caplog.text + response.text for marker in PRIVATE_MARKERS)


@pytest.fixture(scope="module")
def formatter_cases():
    cases = {}
    for case in reading_cases():
        cases.setdefault(case["id"], case)
    return cases


@pytest.fixture
def wording_client():
    app = FastAPI()
    app.include_router(router)
    app.state.personal_analysis_runtime = PersonalAnalysisRuntime(
        authorize=lambda _: True, adapter=PersonalAnalysisAdapter(presentation=proposed_label_only_policy()))
    with TestClient(app) as client:
        yield client


@pytest.fixture(scope="module")
def all_cases():
    return [*reading_cases(), *gap_cases()]


def test_v1_findings_are_reproduced_and_all_v2_sentences_are_checked(all_cases):
    assert known_issues(all_cases) == {"기존 수정 필요": 38, "EXPERIENCE 조사": 8, "TIME 조사": 5}
    assert sum(c["kind"] == "차이" for c in all_cases) == 18482
    assert len({c["id"] for c in all_cases if c["kind"] != "차이"}) == 15
    for case in all_cases:
        assert_wording(case)


@pytest.mark.parametrize("kind", ["succeeded", "insufficient"])
def test_every_reading_variant_in_both_real_result_states_preserves_non_text(kind):
    adapter = PersonalAnalysisAdapter(presentation=proposed_label_only_policy())
    for case in reading_cases():
        # Suppress two competing reading branches, leaving enough type coverage.
        patch = {39: "unknown", 44: "unknown", **case["patch"]} if kind == "succeeded" else case["patch"]
        req = request(patch, kind)
        raw = analyze_personal(to_core_input(req, APPROVED_VERSION_BINDING))
        actual = adapter.analyze(req)
        assert actual.status == raw.status == ("SUCCEEDED" if kind == "succeeded" else "INSUFFICIENT")
        for field in ("scores", "typeStatus", "primaryType", "tiedTypes", "missingQuestionIds"):
            assert getattr(actual, field) == getattr(raw, field)
        selected = next(r for r in actual.readings if r.templateId == case["id"])
        assert selected.text == case["after"]
        for converted, original in zip(actual.readings, raw.readings, strict=True):
            assert (converted.key, converted.priority, converted.evidenceQuestionIds) == (
                original["key"], original["priority"], [f"Q{e['id']:03}" for e in original["evidence"]])


@pytest.mark.parametrize("kind", ["succeeded", "insufficient"])
def test_valid_expression_evidence_returns_http_200_in_both_states(kind):
    req = request({30: 4, 32: 2}, kind)
    adapter = PersonalAnalysisAdapter(presentation=proposed_label_only_policy())
    app = FastAPI()
    app.include_router(router)
    app.state.personal_analysis_runtime = PersonalAnalysisRuntime(authorize=lambda _: True, adapter=adapter)
    with TestClient(app) as client:
        response = client.post(PATH, json=req.model_dump())
    assert response.status_code == 200
    assert response.json() == adapter.analyze(req).model_dump(mode="json")
    assert response.json()["status"] == ("SUCCEEDED" if kind == "succeeded" else "INSUFFICIENT")
    reading = next(r for r in response.json()["readings"] if r["templateId"] == "PA-R-EXPRESSION-01")
    assert reading["text"] == (
        "호감이 생기면 먼저 마음을 표현하는 편이라고 답했어요. "
        "연인에게 좋아한다는 말은 가끔 한다고 답했어요.")
    assert reading["evidenceQuestionIds"] == ["Q030", "Q032"]
    assert response.headers["Cache-Control"] == "no-store" and response.headers["X-Request-Id"]


@pytest.mark.parametrize("kind", ["succeeded", "insufficient"])
@pytest.mark.parametrize("fault", ["missing", "mismatch", "reversed", "extra", "float-id"])
@pytest.mark.parametrize("template_id", sorted(proposed_label_only_policy().reading_text_formatters))
def test_invalid_formatter_evidence_is_http_500_with_safe_diagnostic(
    kind, fault, template_id, formatter_cases, monkeypatch, caplog, wording_client,
):
    policy = proposed_label_only_policy()
    patch = formatter_cases[template_id]["patch"]
    if kind == "succeeded":
        patch = {39: "unknown", 44: "unknown", **patch}
    req = request(patch, kind)
    req.analysisId = 987654321
    raw = analyze_personal(to_core_input(req, APPROVED_VERSION_BINDING))
    assert raw.status == ("SUCCEEDED" if kind == "succeeded" else "INSUFFICIENT")
    damaged = deepcopy(raw)
    row = next(r for r in damaged.readings if policy.reading_template_ids[r["key"], r["title"]] == template_id)
    row["text"] = "private-original-text"
    if fault == "missing":
        del row["evidence"][1]["answer"]
    elif fault == "mismatch":
        row["evidence"][1]["answer"] = "private-answer"
    elif fault == "reversed":
        row["evidence"].reverse()
    elif fault == "extra":
        row["evidence"].append(deepcopy(row["evidence"][0]))
    else:
        row["evidence"][0]["id"] = 30.0
    monkeypatch.setattr(module, "analyze_personal", lambda *_: damaged)
    response = wording_client.post(PATH, json=req.model_dump(), headers={
        "X-Request-Id": TRACE_ID, "Authorization": "Bearer private-jwt", "X-Member-Id": "private-member"})
    assert response.status_code == 500
    assert response.json() == {
        "code": "INTERNAL_ERROR", "message": "개인 분석 처리 중 오류가 발생했습니다.",
    }
    assert response.headers["X-Request-Id"] == TRACE_ID
    assert_diagnostic(caplog, response, template_id, "FORMATTER_EVIDENCE_MISMATCH")


@pytest.mark.parametrize("fault", [
    "missing-auth", "auth-failure", "service-auth-unavailable", "http503", "reference-io", "reference-version", "result-version",
    "missing-template", "missing-difference", "missing-reference", "reference-domains", "difference-label",
    "formatter-service-unavailable",
])
def test_existing_unavailability_keeps_http_503_and_safe_diagnostic(fault, monkeypatch, caplog, wording_client, tmp_path):
    req = request({30: 4, 32: 2}, "succeeded")
    req.analysisId = 987654321
    runtime = wording_client.app.state.personal_analysis_runtime
    policy = runtime.adapter.presentation
    error_type = "PRESENTATION_UNAVAILABLE"

    def unavailable(*_):
        raise PresentationUnavailable()

    if fault == "missing-auth":
        runtime = replace(runtime, authorize=None)
        error_type = "AUTH_NOT_CONFIGURED"
    elif fault in ("auth-failure", "service-auth-unavailable"):
        def broken_auth(_):
            if fault == "service-auth-unavailable":
                raise ServiceAuthError("SERVICE_UNAVAILABLE")
            raise RuntimeError("private-member private-jwt private-answer")
        runtime = replace(runtime, authorize=broken_auth)
        error_type = "AUTH_VERIFIER_UNAVAILABLE"
    elif fault == "http503":
        def http_unavailable(*_):
            raise HTTPException(503, detail="private-answer private-member")
        monkeypatch.setattr(module, "analyze_personal", http_unavailable)
        error_type = "HTTP_SERVICE_UNAVAILABLE"
    elif fault == "reference-io":
        monkeypatch.setattr(module, "DATA_DIR", tmp_path / "private-answer")
    elif fault == "reference-version":
        monkeypatch.setattr(module, "CATALOGUE", {**module.CATALOGUE, "surveyVersion": "private-answer"})
    elif fault.startswith("missing-") or fault == "formatter-service-unavailable":
        field = {"missing-template": "reading_template_ids", "missing-difference": "difference_reading",
                 "missing-reference": "reference_evidence", "formatter-service-unavailable": "reading_text_formatters"}[fault]
        value = {} if fault == "missing-template" else (
            {"PA-R-EXPRESSION-01": unavailable} if fault == "formatter-service-unavailable" else None)
        runtime = replace(runtime, adapter=replace(runtime.adapter, presentation=replace(policy, **{field: value})))
    else:
        damaged = deepcopy(analyze_personal(to_core_input(req, APPROVED_VERSION_BINDING)))
        if fault == "result-version":
            damaged.calculationVersion = "private-answer"
        elif fault == "reference-domains":
            damaged.typeReferences[0]["domains"] = []
        else:
            damaged.selfPreferenceDifferences[0]["label"] = "private-answer"
        monkeypatch.setattr(module, "analyze_personal", lambda *_: damaged)
    wording_client.app.state.personal_analysis_runtime = runtime
    response = wording_client.post(PATH, json=req.model_dump(), headers={
        "X-Request-Id": TRACE_ID, "Authorization": "Bearer private-jwt", "X-Member-Id": "private-member"})
    assert response.status_code == 503
    message = "서비스 인증을 처리할 수 없습니다." if fault == "service-auth-unavailable" else "개인 분석 서비스의 계약 또는 인증 설정을 확인해야 합니다."
    assert response.json() == {"code": "SERVICE_UNAVAILABLE", "message": message}
    assert response.headers["X-Request-Id"] == TRACE_ID
    assert_diagnostic(caplog, response, "UNRESOLVED", error_type)


@pytest.mark.parametrize("headers", [[], [("X-Request-Id", "private-jwt")], [("X-Request-Id", TRACE_ID)] * 2])
def test_formatter_log_uses_existing_sanitized_request_id(headers, monkeypatch, caplog, wording_client):
    req = request({30: 4, 32: 2})
    damaged = deepcopy(analyze_personal(to_core_input(req, APPROVED_VERSION_BINDING)))
    row = next(r for r in damaged.readings if r["key"] == "expression")
    row["evidence"][0]["answer"] = "private-answer"
    monkeypatch.setattr(module, "analyze_personal", lambda *_: damaged)
    response = wording_client.post(PATH, json=req.model_dump(), headers=headers)
    assert response.status_code == 500
    assert UUID(response.headers["X-Request-Id"]).version == 4
    assert_diagnostic(caplog, response, "PA-R-EXPRESSION-01", "FORMATTER_EVIDENCE_MISMATCH")


def test_each_registered_formatter_refuses_unverified_evidence():
    policy = proposed_label_only_policy()
    checked = set()
    for case in reading_cases():
        if case["id"] in checked or case["id"] not in policy.reading_text_formatters:
            continue
        answers = case["patch"]
        row = next(r for r in core.readings(answers)
                   if policy.reading_template_ids[r["key"], r["title"]] == case["id"])
        row["evidence"][0]["answer"] = "wrong-label"
        with pytest.raises(FormatterEvidenceMismatch):
            policy.reading_text_formatters[case["id"]](row, answers)
        checked.add(case["id"])
    assert checked == set(policy.reading_text_formatters)


def test_core_and_response_result_states_are_exactly_the_two_real_service_states():
    expected = {"SUCCEEDED", "INSUFFICIENT"}
    assert set(get_args(PersonalAnalysisResult.model_fields["status"].annotation)) == expected
    assert set(get_args(PersonalAnalysisResponse.model_fields["status"].annotation)) == expected
    actual = {analyze_personal(to_core_input(request({30: 4, 32: 2}, kind), APPROVED_VERSION_BINDING)).status
              for kind in ("succeeded", "insufficient")}
    assert actual == expected


@pytest.mark.parametrize("status", ["PENDING", "RUNNING", "FAILED"])
def test_be_lifecycle_states_are_not_valid_core_results(status):
    raw = analyze_personal(to_core_input(request({30: 4, 32: 2}), APPROVED_VERSION_BINDING))
    with pytest.raises(ValidationError) as error:
        PersonalAnalysisResult.model_validate({**raw.model_dump(), "status": status})
    assert [(e["loc"], e["type"]) for e in error.value.errors(include_input=False)] == [
        (("status",), "literal_error")]


def test_core_failure_raises_instead_of_returning_a_failed_result_or_invoking_formatters(monkeypatch, wording_client):
    def broken_calculation(_):
        raise RuntimeError("synthetic-calculation-failure")

    def forbidden_presentation(*_):
        pytest.fail("A calculation failure must not reach any presentation formatter")

    monkeypatch.setattr(core, "classify_answers", broken_calculation)
    monkeypatch.setattr(module, "to_be_response", forbidden_presentation)
    req = request({30: 4, 32: 2})
    with pytest.raises(CalculationFailure, match="^CALCULATION_FAILED$"):
        PersonalAnalysisAdapter(presentation=proposed_label_only_policy()).analyze(req)
    response = wording_client.post(PATH, json=req.model_dump())
    assert response.status_code == 500
    assert response.json() == {"code": "INTERNAL_ERROR", "message": "개인 분석 처리 중 오류가 발생했습니다."}
    assert "synthetic-calculation-failure" not in response.text


@pytest.mark.parametrize("q", range(65, 96), ids=lambda q: f"Q{q:03}")
@pytest.mark.parametrize("scenario", ["self-unknown", "wish-unknown", "wish-any"])
def test_all_31_pairs_preserve_schema_permitted_unknown_and_any_semantics(q, scenario, wording_client):
    adapter = PersonalAnalysisAdapter(presentation=proposed_label_only_policy())
    actual, wish = values(q - 35)[0], values(q)[0]
    patches = {"self-unknown": {q - 35: "unknown", q: wish},
               "wish-unknown": {q - 35: actual, q: "unknown"},
               "wish-any": {q - 35: actual, q: "any"}}
    patch = patches[scenario]
    # Read both the actual per-question JSON schema and the validator contract.
    for ordinal, value in patch.items():
        question = QUESTION_BY_ORDINAL[ordinal]
        if value in ("unknown", "any"):
            assert {"const": value} in question["responseSchema"]["oneOf"]
            assert question["allowUnknown" if value == "unknown" else "allowAny"] is True
        assert valid_answer(question, value)
    req = request(patch)
    assert adapter.analyze(req).selfPreferenceDifferences == []
    response = wording_client.post(PATH, json=req.model_dump())
    assert response.status_code == 200
    assert response.json()["status"] == "INSUFFICIENT"
    assert response.json()["selfPreferenceDifferences"] == []


@pytest.mark.parametrize("q", range(65, 96), ids=lambda q: f"Q{q:03}")
@pytest.mark.parametrize("scenario", ["self-any", "wish-array-containing-any"])
def test_any_outside_the_question_schema_is_rejected_before_presentation(q, scenario, monkeypatch, wording_client):
    ordinal, value = (q - 35, "any") if scenario == "self-any" else (q, ["any"])
    question = QUESTION_BY_ORDINAL[ordinal]
    if scenario == "self-any":
        assert question["allowAny"] is False
        assert {"const": "any"} not in question["responseSchema"]["oneOf"]
    assert not valid_answer(question, value)

    def forbidden_presentation(*_):
        pytest.fail("Schema-invalid input reached presentation")

    monkeypatch.setattr(module, "to_be_response", forbidden_presentation)
    req = request({q - 35: values(q - 35)[0], q: values(q)[0], ordinal: value})
    with pytest.raises(AnalysisInputError) as error:
        PersonalAnalysisAdapter(presentation=proposed_label_only_policy()).analyze(req)
    assert error.value.code == "INVALID_ANSWER"
    assert error.value.question_ids == (f"Q{ordinal:03}",)
    response = wording_client.post(PATH, json=req.model_dump())
    assert response.status_code == 422
    assert response.json() == {"code": "INVALID_REQUEST", "message": "개인 분석 요청 형식이나 답변이 올바르지 않습니다."}


@pytest.mark.parametrize("actual,wish,tolerance", product(range(1, 6), range(1, 6), ("close", "some", "wide", "unknown")))
def test_drinking_upper_bound_text_does_not_change_fit_or_tolerance(actual, wish, tolerance):
    span = {"close": 0, "some": 1, "wide": 2, "unknown": 1}[tolerance]
    upper = min(5, wish + span)
    expected = None if upper == 5 else 1 - max(0, actual - upper) / (5 - upper)
    assert core.wish_fit(89, wish, actual, tolerance) == expected
    result = PersonalAnalysisAdapter(presentation=proposed_label_only_policy()).analyze(request({54: actual, 89: wish, 98: tolerance}))
    assert bool(result.selfPreferenceDifferences) == (expected is not None and expected < 1)
    if result.selfPreferenceDifferences:
        row = result.selfPreferenceDifferences[0]
        assert "상한으로 선택한 답변" in row.text
        assert f"‘{core.answer_label(89, wish)}’" in row.text
        assert row.evidenceQuestionIds == ["Q054", "Q089"] + (["Q098"] if tolerance != "unknown" else [])


@pytest.mark.parametrize("actual,wish,fit,note", [
    (["morning", "irregular"], ["evening", "irregular"], 0, True),
    (["morning"], ["evening"], 0, False),
    (["irregular"], ["evening"], None, False),
    (["morning", "irregular"], ["morning"], 1, False),
])
def test_irregular_fact_and_calculation_are_separate(actual, wish, fit, note):
    assert core.wish_fit(87, wish, actual) == fit
    result = PersonalAnalysisAdapter(presentation=proposed_label_only_policy()).analyze(request({52: actual, 87: wish}))
    assert len(result.selfPreferenceDifferences) == (1 if fit == 0 else 0)
    if fit == 0:
        row = result.selfPreferenceDifferences[0]
        assert ("계산에서는 ‘일정이 불규칙함’을 제외" in row.text) is note
        assert core.answer_label(52, actual) in row.text and core.answer_label(87, wish) in row.text
        assert row.evidenceQuestionIds == ["Q052", "Q087"]


def test_general_advice_is_a_separate_unapproved_option():
    adapter = PersonalAnalysisAdapter(presentation=proposed_label_only_policy())
    for patch, advice in [({42: 4, 43: 4}, "기대를 맞추기 쉬워요"),
                          ({45: "listen", 44: "explain"}, "상대가 받고 싶은 방식")]:
        response = adapter.analyze(request(patch))
        assert response.readings and all(advice not in row.text for row in response.readings)
