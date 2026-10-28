"""Executable JS oracle parity plus validation, service failure and packaging checks."""

import json
import math
import os
import shutil
import subprocess
import sys
import traceback
import warnings
from copy import deepcopy
from pathlib import Path

import pytest

from app.personal_analysis import core
from app.personal_analysis.dto import PersonalAnalysisInput
from app.personal_analysis.reference import CATALOGUE, PROVENANCE, REFERENCE, load_reference
from app.personal_analysis.service import CalculationFailure, analyze_personal
from app.personal_analysis.validation import AnalysisInputError, engine_answers, valid_answer
from conftest import request_for

# JS and CPython both use binary64. Absolute 1e-12 permits only arithmetic noise,
# far below the existing 1e-10 tie tolerance. No rounding; no relative tolerance.
ABS_TOLERANCE = 1e-12


def assert_same(actual, expected, path="result"):
    if isinstance(expected, dict):
        assert isinstance(actual, dict), path
        assert actual.keys() == expected.keys(), path
        for key in expected:
            assert_same(actual[key], expected[key], f"{path}.{key}")
    elif isinstance(expected, list):
        assert isinstance(actual, list), path
        assert len(actual) == len(expected), path
        for index, (left, right) in enumerate(zip(actual, expected)):
            assert_same(left, right, f"{path}[{index}]")
    elif type(expected) in (int, float):
        assert type(actual) in (int, float), path
        assert math.isfinite(actual), path
        assert actual == pytest.approx(expected, abs=ABS_TOLERANCE, rel=0), path
    else:
        assert actual == expected, path


def test_all_js_fixtures_and_service_outputs_match(js_reference):
    assert len(js_reference["cases"]) == 147
    for case in js_reference["cases"]:
        answers = {int(k): v for k, v in case["answers"].items()}
        before = deepcopy(answers)
        expected = case["result"]
        actual = dict(profile=core.profile_from_answers(answers), classification=core.classify_answers(answers),
                      readings=core.readings(answers), gaps=core.preference_gaps(answers),
                      references=core.type_recommendations(answers))
        assert_same(actual, expected, case["name"])
        payload = request_for(answers)
        payload_before = deepcopy(payload)
        result = analyze_personal(payload)
        dto = PersonalAnalysisInput.model_validate(payload)
        assert analyze_personal(dto).model_dump_json() == result.model_dump_json()
        assert_same(result.classification, expected["classification"], case["name"])
        assert_same(result.readings, expected["readings"], case["name"])
        assert_same(result.selfPreferenceDifferences, expected["gaps"], case["name"])
        assert_same(result.typeReferences, expected["references"]["selected"], case["name"])
        assert result.typeReferenceVersion == expected["references"]["version"]
        assert result.typeStatus == expected["classification"]["status"].upper()
        assert result.status == ("INSUFFICIENT" if result.typeStatus == "INSUFFICIENT" else "SUCCEEDED")
        assert len(result.typeReferences) <= 3
        assert answers == before and payload == payload_before


@pytest.mark.parametrize("name,status,mode", [
    ("normal", "ASSIGNED", "PREFERENCE_FIT"),
    ("partial-score", "ASSIGNED", "PREFERENCE_FIT"),
    ("all-unknown", "INSUFFICIENT", "SELF_SIMILARITY"),
    ("preference-only", "INSUFFICIENT", "PREFERENCE_FIT"),
    ("borderline", "BORDERLINE", "SELF_SIMILARITY"),
    ("tied", "TIED", "SELF_SIMILARITY"),
    ("self-similarity", "ASSIGNED", "SELF_SIMILARITY"),
])
def test_required_outcomes(js_reference, name, status, mode):
    case = next(c for c in js_reference["cases"] if c["name"] == name)
    result = analyze_personal(request_for(case["answers"]))
    assert result.typeStatus == status and result.referenceMode == mode
    if name == "normal":
        assert result.primaryType == "STEADYSPARK"
        assert result.scores["E"] == 31.25
    if name == "partial-score":
        assert result.scores["E"] is None and result.status == "SUCCEEDED"
    if name == "all-unknown":
        assert set(result.scores.values()) == {None}
        assert result.readings == [] and result.typeReferences == []
        assert len(result.missingQuestionIds) == 60
    if name == "preference-only":
        assert len(result.typeReferences) == 3
    if status in ("TIED", "INSUFFICIENT"):
        assert result.primaryType is None
    else:
        assert result.primaryType is not None
    if status == "TIED":
        assert len(result.tiedTypes) == 16
        assert result.tiedTypes == sorted(result.tiedTypes)
    else:
        assert result.tiedTypes == []


def test_5832_wish_fit_cases_and_thresholds_match_js(js_reference):
    assert len(js_reference["wishCases"]) == 5832
    for case in js_reference["wishCases"]:
        assert_same(core.wish_fit(case["id"], case["wish"], case["actual"], case["tolerance"]), case["result"])
    for case in js_reference["profiles"]:
        assert_same(core.classify_profile(case["features"]), case["result"], case["name"])
    profiles = {c["name"]: c["result"] for c in js_reference["profiles"]}
    assert profiles["gap-2.99999999"]["status"] == "borderline"
    assert profiles["gap-3.00000001"]["status"] == "assigned"
    assert profiles["tie-epsilon-1e-11"]["status"] == "tied"
    assert profiles["tie-epsilon-1e-7"]["status"] == "borderline"
    assert profiles["coverage-at-minimum"]["status"] != "insufficient"
    assert profiles["coverage-below-minimum"]["status"] == "insufficient"


def test_reference_data_and_sources_are_unchanged(js_reference):
    assert_same(REFERENCE, js_reference["reference"])
    assert PROVENANCE["artifacts"] == js_reference["hashes"]
    catalogue_path = Path(__file__).resolve().parents[2] / "docs/contracts/survey/questionnaire-SIS-Q-1.1-100.json"
    packaged = Path(__file__).resolve().parents[1] / "app/personal_analysis/data" / catalogue_path.name
    assert packaged.read_bytes() == catalogue_path.read_bytes()
    assert {q["ordinal"] for q in CATALOGUE["questions"]} == set(range(1, 101))
    # The old Q97 includes age; it must remain in this unchanged survey version.
    assert "age" in CATALOGUE["questions"][96]["responseSchema"]["oneOf"][0]["items"]["enum"]


@pytest.mark.parametrize("value", [None, True, False, "3", 0, 6, 2.5, [], {}, [3], 10**400, float("nan"), float("inf")])
def test_reject_invalid_scalar_answers(normal_request, value):
    normal_request["answers"][0]["value"] = value
    with pytest.raises(AnalysisInputError) as error:
        analyze_personal(normal_request)
    assert error.value.code in ("INVALID_ANSWER", "INVALID_REQUEST")


@pytest.mark.parametrize("value", [[], ["age", "age"], ["age", "smoking", "religion"], ["unknown"], ["any"], "age", ["unsupported"], [["age"]], [True]])
def test_reject_invalid_multiselect(normal_request, value):
    normal_request["answers"][96]["value"] = value
    with pytest.raises(AnalysisInputError, match="INVALID_ANSWER"):
        analyze_personal(normal_request)


@pytest.mark.parametrize("question_id", ["Q000", "Q101", "Q1", "Q01", "q001", "Q001 ", "age"])
def test_reject_unknown_question_ids(normal_request, question_id):
    normal_request["answers"][0]["questionId"] = question_id
    with pytest.raises(AnalysisInputError, match="INVALID_QUESTION_ID"):
        analyze_personal(normal_request)


def test_duplicate_missing_and_unanswered_are_distinct(normal_request):
    duplicate = deepcopy(normal_request)
    duplicate["answers"].append(deepcopy(duplicate["answers"][0]))
    with pytest.raises(AnalysisInputError, match="DUPLICATE_QUESTION_ID"):
        analyze_personal(duplicate)
    missing = deepcopy(normal_request)
    missing["answers"].pop(0)
    with pytest.raises(AnalysisInputError, match="MISSING_ANSWERS") as error:
        analyze_personal(missing)
    assert error.value.question_ids == ("Q001",)
    normal_request["answers"][0]["value"] = "unknown"
    assert analyze_personal(normal_request).scores["E"] is None
    normal_request["answers"][0]["value"] = "any"
    with pytest.raises(AnalysisInputError, match="INVALID_ANSWER"):
        analyze_personal(normal_request)


@pytest.mark.parametrize("key,value,code", [
    ("surveyVersion", "SIS-Q-NEW", "UNSUPPORTED_SURVEY_VERSION"),
    ("surveyVersion", "SIS-Q-1.1-100-four-options", "UNSUPPORTED_SURVEY_VERSION"),
    ("calculationVersion", "latest", "UNSUPPORTED_CALCULATION_VERSION"),
    ("calculationVersion", "DRAFT-PERSONAL-REF-1", "UNSUPPORTED_CALCULATION_VERSION"),
])
def test_explicit_version_selection(normal_request, key, value, code):
    normal_request[key] = value
    with pytest.raises(AnalysisInputError, match=code):
        analyze_personal(normal_request)


@pytest.mark.parametrize("metadata", [None, {}, {"ownAge": None},
    dict(ownAge=18, desiredAgeMin=None, desiredAgeMax=None),
    dict(ownAge=101, desiredAgeMin=None, desiredAgeMax=None),
    dict(ownAge=True, desiredAgeMin=None, desiredAgeMax=None),
    dict(ownAge="25", desiredAgeMin=None, desiredAgeMax=None),
    dict(ownAge=25.0, desiredAgeMin=None, desiredAgeMax=None),
    dict(ownAge=None, desiredAgeMin=19, desiredAgeMax=101),
    dict(ownAge=None, desiredAgeMin=None, desiredAgeMax=None, token="synthetic-secret"),
])
def test_metadata_validation(normal_request, metadata):
    normal_request["metadata"] = metadata
    with pytest.raises(AnalysisInputError, match="INVALID_REQUEST"):
        analyze_personal(normal_request)


@pytest.mark.parametrize("field", ["surveyVersion", "calculationVersion", "answers", "metadata"])
def test_required_fields(normal_request, field):
    normal_request.pop(field)
    with pytest.raises(AnalysisInputError, match="INVALID_REQUEST"):
        analyze_personal(normal_request)


def test_mapping_order_float_number_and_metadata_invariance(normal_request):
    original = analyze_personal(normal_request)
    normal_request["answers"].reverse()
    normal_request["metadata"] = dict(ownAge=100, desiredAgeMin=100, desiredAgeMax=19)
    # Min > Max remains schema-valid in the current contract; no hidden age policy.
    assert analyze_personal(normal_request) == original
    mapped = engine_answers(PersonalAnalysisInput.model_validate(normal_request))
    assert mapped[1] == next(a["value"] for a in normal_request["answers"] if a["questionId"] == "Q001")
    for answer in normal_request["answers"]:
        if type(answer["value"]) is int:
            answer["value"] = float(answer["value"])
    assert_same(analyze_personal(normal_request).model_dump(), original.model_dump())


def test_every_catalogue_option_matches_validation():
    for question in CATALOGUE["questions"]:
        schema = question["responseSchema"]["oneOf"][0]
        for option in question["options"]:
            value = [option["value"]] if schema.get("type") == "array" else option["value"]
            assert valid_answer(question, value)
        assert valid_answer(question, "unknown")
        assert valid_answer(question, "any") == question["allowAny"]
        assert not valid_answer(question, None)


def test_result_and_input_mutations_cannot_change_future_computations(normal_request):
    original = analyze_personal(normal_request)
    altered = analyze_personal(normal_request)
    altered.classification["ranking"][0]["features"]["q40"].append("synthetic-change")
    altered.typeReferences[0]["features"]["E"] = -999
    assert analyze_personal(normal_request) == original


def test_failure_and_validation_do_not_log_input(normal_request, monkeypatch, caplog, capsys):
    def broken(_):
        raise RuntimeError("synthetic-private-token")
    monkeypatch.setattr(core, "classify_answers", broken)
    with pytest.raises(CalculationFailure, match="^CALCULATION_FAILED$") as error:
        analyze_personal(normal_request)
    assert error.value.__suppress_context__
    bad = deepcopy(normal_request)
    bad["metadata"]["secret"] = "synthetic-private-token"
    with pytest.raises(AnalysisInputError, match="^INVALID_REQUEST$"):
        analyze_personal(bad)
    assert caplog.text == ""
    captured = capsys.readouterr()
    assert captured.out == captured.err == ""


@pytest.mark.parametrize("construction", ["validated", "constructed", "copied"])
def test_valid_dto_variants_preserve_output(normal_request, construction):
    expected = analyze_personal(normal_request).model_dump_json()
    if construction == "constructed":
        payload = PersonalAnalysisInput.model_construct(**normal_request)
    else:
        payload = PersonalAnalysisInput.model_validate(normal_request)
        if construction == "copied":
            payload = payload.model_copy(deep=True)
    with warnings.catch_warnings(record=True) as caught:
        warnings.simplefilter("always")
        assert analyze_personal(payload).model_dump_json() == expected
    assert not caught


@pytest.mark.parametrize("construction", ["construct", "copy", "mutate"])
@pytest.mark.parametrize("field", ["surveyVersion", "answers", "metadata", "questionId", "value", "ownAge"])
@pytest.mark.parametrize("input_kind", ["dto", "dict"])
def test_invalid_dtos_do_not_disclose_input(
    normal_request, construction, field, input_kind, caplog, capsys,
):
    secret = "synthetic-private-token"
    payload = PersonalAnalysisInput.model_validate(normal_request)
    if field in ("questionId", "value"):
        target = payload.answers[0]
    elif field == "ownAge":
        target = payload.metadata
    else:
        target = payload
    # A set is not a JsonValue; lists violate the other selected field types.
    invalid = {secret} if field == "value" else [secret]
    if construction == "construct":
        target = type(target).model_construct(**{**target.model_dump(), field: invalid})
    elif construction == "copy":
        target = target.model_copy(update={field: invalid})
    else:
        setattr(target, field, invalid)
    if field in ("questionId", "value"):
        payload.answers[0] = target
    elif field == "ownAge":
        payload.metadata = target
    else:
        payload = target
    if input_kind == "dict":
        # Keep nested DTOs intact to exercise their own instance revalidation.
        payload = {name: getattr(payload, name) for name in PersonalAnalysisInput.model_fields}

    caplog.set_level("DEBUG")
    with warnings.catch_warnings(record=True) as caught:
        warnings.simplefilter("always")
        with pytest.raises(AnalysisInputError, match="^INVALID_REQUEST$") as error:
            analyze_personal(payload)
    captured = capsys.readouterr()
    displayed_error = "".join(traceback.format_exception(error.value))
    assert error.value.question_ids == ()
    assert error.value.__suppress_context__
    assert secret not in displayed_error
    assert secret not in "".join(str(warning.message) for warning in caught)
    assert secret not in captured.out + captured.err + caplog.text
    assert not caught
    assert captured.out == captured.err == caplog.text == ""


def test_packaged_data_integrity_fails_on_tampering(tmp_path, monkeypatch):
    from app.personal_analysis import reference
    shutil.copytree(reference.DATA_DIR, tmp_path / "data")
    (tmp_path / "data/reference.json").write_text("{}", encoding="utf-8")
    monkeypatch.setattr(reference, "DATA_DIR", tmp_path / "data")
    with pytest.raises(RuntimeError, match="integrity check failed"):
        load_reference()


def test_app_only_copy_computes_without_repository_or_node(normal_request, tmp_path):
    # Same application payload as Docker COPY app/; no docs/, tests/, JS runtime.
    source = Path(__file__).resolve().parents[1] / "app"
    shutil.copytree(source, tmp_path / "app", ignore=shutil.ignore_patterns("__pycache__"))
    env = {**os.environ, "PYTHONPATH": str(tmp_path), "PATH": ""}
    run = subprocess.run([sys.executable, "-c",
        "import json,sys; from app.personal_analysis import analyze_personal; "
        "r=analyze_personal(json.load(sys.stdin)); print(r.status,r.primaryType)"],
        input=json.dumps(normal_request), capture_output=True, encoding="utf-8",
        cwd=tmp_path, env=env, timeout=30, check=True)
    assert run.stdout.strip() == "SUCCEEDED STEADYSPARK"


def test_cli_success_and_invalid_file(normal_request, tmp_path):
    path = tmp_path / "input.json"
    path.write_text(json.dumps(normal_request), encoding="utf-8")
    run = subprocess.run([sys.executable, "-m", "app.personal_analysis", str(path)],
                         capture_output=True, encoding="utf-8", timeout=30, check=True)
    assert json.loads(run.stdout)["status"] == "SUCCEEDED"
    path.write_text("not-json synthetic-private-token", encoding="utf-8")
    run = subprocess.run([sys.executable, "-m", "app.personal_analysis", str(path)],
                         capture_output=True, encoding="utf-8", timeout=30)
    assert run.returncode == 2
    assert json.loads(run.stderr) == {"code": "INVALID_INPUT_FILE"}
