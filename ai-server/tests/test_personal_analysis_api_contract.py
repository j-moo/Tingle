"""Confirmed BE contract and explicit, UNAPPROVED display proposal coverage."""

import json
from copy import deepcopy
from dataclasses import replace
from hashlib import sha256
from itertools import product
from uuid import UUID

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from starlette.exceptions import HTTPException

from app.personal_analysis import core
from app.personal_analysis.reference import CATALOGUE
from app.personal_analysis.service import analyze_personal
from app.personal_analysis_api import adapter as module
from app.personal_analysis_api.adapter import (
    APPROVED_DATA_HASHES, APPROVED_VERSION_BINDING, APPROVED_VERSION_BINDINGS,
    PersonalAnalysisAdapter, PresentationPolicy, PresentationUnavailable,
    VersionBinding, VersionUnsupported, format_expression_reading, reference_evidence, to_core_input,
    verify_reference_binding,
)
from app.personal_analysis_api.dto import PersonalAnalysisRequest
from app.personal_analysis_api.proposals import (
    CANDIDATE_READING_TEMPLATE_IDS, proposed_label_only_policy,
)
from app.personal_analysis_api.review_samples import (
    FIXTURE_DIR, build_review_samples, json_text, synthetic_request,
)
from app.personal_analysis_api.router import PersonalAnalysisRuntime, router

PATH = "/internal/ai/personal-analyses"
EXPRESSION_TEMPLATE_ID = "PA-R-EXPRESSION-01"
BRANCH_CASES = json.loads("[{\"templateId\":\"PA-R-EXPRESSION-01\",\"patch\":{\"30\":4,\"32\":2},\"title\":\"먼저 다가가지만, 좋아한다는 말은 자주 하지 않아요.\",\"text\":\"관계를 시작하는 행동과 말로 마음을 전하는 빈도가 다르게 나타났어요. 말 대신 어떤 행동으로 표현하는지 함께 이야기해볼 수 있어요.\",\"priority\":10,\"evidenceQuestionIds\":[\"Q030\",\"Q032\"],\"key\":\"expression\"},{\"templateId\":\"PA-R-EXPRESSION-02\",\"patch\":{\"32\":4,\"33\":2},\"title\":\"애정 표현은 자주 하고, 개인적인 고민은 골라서 나눠요.\",\"text\":\"좋아한다는 말을 하는 것과 내 고민을 공개하는 것은 다르게 선택했어요. 이를 마음을 여는 속도라고 단정하지 않아요.\",\"priority\":10,\"evidenceQuestionIds\":[\"Q032\",\"Q033\"],\"key\":\"expression\"},{\"templateId\":\"PA-R-EXPRESSION-03\",\"patch\":{\"32\":4,\"33\":3},\"title\":\"좋아하는 마음을 말로 전하는 편이에요.\",\"text\":\"말로 표현하는 빈도는 ‘자주 표현하는 편이에요’, 고민을 나누는 범위는 ‘주제에 따라 달라요’라고 답했어요.\",\"priority\":3,\"evidenceQuestionIds\":[\"Q032\",\"Q033\"],\"key\":\"expression\"},{\"templateId\":\"PA-R-EXPRESSION-04\",\"patch\":{\"30\":3,\"32\":2,\"33\":3},\"title\":\"좋아하는 마음을 자주 말하는 편은 아니에요.\",\"text\":\"말로 표현하는 빈도는 ‘가끔 표현하는 편이에요’, 고민을 나누는 범위는 ‘주제에 따라 달라요’라고 답했어요.\",\"priority\":3,\"evidenceQuestionIds\":[\"Q032\",\"Q033\"],\"key\":\"expression\"},{\"templateId\":\"PA-R-EXPRESSION-05\",\"patch\":{\"32\":3,\"33\":3},\"title\":\"애정 표현과 고민 공유를 상황에 맞춰 선택해요.\",\"text\":\"말로 표현하는 빈도는 ‘필요한 순간에 표현해요’, 고민을 나누는 범위는 ‘주제에 따라 달라요’라고 답했어요.\",\"priority\":3,\"evidenceQuestionIds\":[\"Q032\",\"Q033\"],\"key\":\"expression\"},{\"templateId\":\"PA-R-CONFLICT-01\",\"patch\":{\"42\":4,\"43\":4},\"title\":\"서운함은 빨리 이야기하고, 감정이 커지면 잠깐 쉬고 싶어요.\",\"text\":\"대화를 시작하는 시점과 대화 중 필요한 휴식은 별개예요. 잠시 멈출 때 다시 이야기할 시간을 정하면 기대를 맞추기 쉬워요.\",\"priority\":10,\"evidenceQuestionIds\":[\"Q042\",\"Q043\"],\"key\":\"conflict\"},{\"templateId\":\"PA-R-CONFLICT-02\",\"patch\":{\"42\":2,\"43\":2},\"title\":\"생각을 정리한 뒤, 시작한 대화는 이어가고 싶어요.\",\"text\":\"바로 말을 꺼내기보다 준비할 시간이 필요하고, 대화가 시작되면 계속 이야기하는 쪽을 골랐어요.\",\"priority\":9,\"evidenceQuestionIds\":[\"Q042\",\"Q043\"],\"key\":\"conflict\"},{\"templateId\":\"PA-R-CONFLICT-03\",\"patch\":{\"42\":3,\"43\":3},\"title\":\"갈등 대화에서 시작 시점과 쉬는 방식이 중요해요.\",\"text\":\"대화 시작은 ‘상황에 따라 달라요’, 감정이 올라왔을 때는 ‘상황에 따라 달라요’가 편하다고 답했어요.\",\"priority\":3,\"evidenceQuestionIds\":[\"Q042\",\"Q043\"],\"key\":\"conflict\"},{\"templateId\":\"PA-R-EXPERIENCE-01\",\"patch\":{\"38\":4,\"39\":4},\"title\":\"새로운 곳에 가는 것을 좋아하고, 약속은 미리 준비해요.\",\"text\":\"경험의 새로움과 일정의 즉흥성은 같지 않아요. 새로운 장소를 탐색하면서 시간과 장소는 정해두는 쪽이에요.\",\"priority\":9,\"evidenceQuestionIds\":[\"Q038\",\"Q039\"],\"key\":\"experience\"},{\"templateId\":\"PA-R-EXPERIENCE-02\",\"patch\":{\"38\":4,\"39\":3},\"title\":\"만나기 전에 시간과 장소를 정해두는 편이에요.\",\"text\":\"약속 준비는 ‘대부분 미리 정해요’, 장소 선택은 ‘그때그때 비슷해요’라고 답했어요.\",\"priority\":2,\"evidenceQuestionIds\":[\"Q038\",\"Q039\"],\"key\":\"experience\"},{\"templateId\":\"PA-R-EXPERIENCE-03\",\"patch\":{\"38\":2,\"39\":3},\"title\":\"당일의 상황을 보며 약속을 정하는 편이에요.\",\"text\":\"약속 준비는 ‘큰 방향만 정하고 당일에 골라요’, 장소 선택은 ‘그때그때 비슷해요’라고 답했어요.\",\"priority\":2,\"evidenceQuestionIds\":[\"Q038\",\"Q039\"],\"key\":\"experience\"},{\"templateId\":\"PA-R-EXPERIENCE-04\",\"patch\":{\"38\":3,\"39\":3},\"title\":\"데이트의 계획과 장소를 유연하게 선택해요.\",\"text\":\"약속 준비는 ‘상황에 따라 달라요’, 장소 선택은 ‘그때그때 비슷해요’라고 답했어요.\",\"priority\":2,\"evidenceQuestionIds\":[\"Q038\",\"Q039\"],\"key\":\"experience\"},{\"templateId\":\"PA-R-TIME-01\",\"patch\":{\"32\":4,\"37\":2},\"title\":\"각자 보내는 시간이 있어도 애정 표현은 자주 하고 싶어요.\",\"text\":\"함께 있는 시간의 비중과 마음을 표현하는 빈도를 다르게 선택했어요. 각자 시간을 보낸다는 이유로 마음이 적다고 해석하지 않아요.\",\"priority\":9,\"evidenceQuestionIds\":[\"Q037\",\"Q032\"],\"key\":\"time\"},{\"templateId\":\"PA-R-TIME-02\",\"patch\":{\"34\":3,\"36\":3},\"title\":\"연락하는 빈도와 만나는 빈도를 따로 정해요.\",\"text\":\"연락은 ‘하루 몇 번’, 만남은 ‘주 2회’를 골랐어요. 서로 단위가 다른 항목이므로 선택 단계의 차이로 선호를 단정하지 않아요.\",\"priority\":1,\"evidenceQuestionIds\":[\"Q034\",\"Q036\"],\"key\":\"time\"},{\"templateId\":\"PA-R-SUPPORT-01\",\"patch\":{\"44\":\"explain\",\"45\":\"listen\"},\"title\":\"상대가 힘들 때 내가 먼저 하는 일이 있어요.\",\"text\":\"도움의 첫 반응은 ‘끝까지 듣기’, 다툰 뒤에는 ‘이유 설명’를 선택했어요. 상대가 받고 싶은 방식도 확인해보면 좋아요.\",\"priority\":2,\"evidenceQuestionIds\":[\"Q045\",\"Q044\"],\"key\":\"support\"}]")


def request_for_patch(patch):
    request = synthetic_request("insufficient")
    for answer in request["answers"]:
        answer["value"] = deepcopy(patch.get(int(answer["questionId"][1:]), "unknown"))
    return PersonalAnalysisRequest.model_validate(request)


def context(request):
    return {int(answer.questionId[1:]): deepcopy(answer.value) for answer in request.answers}


def raw_result(request):
    return analyze_personal(to_core_input(request, APPROVED_VERSION_BINDING))


@pytest.fixture
def proposal_adapter():
    return PersonalAnalysisAdapter(presentation=proposed_label_only_policy())


@pytest.fixture
def proposal_client(proposal_adapter):
    app = FastAPI()
    app.include_router(router)
    # Explicit test app only. No token/JWT is installed in the production app.
    app.state.personal_analysis_runtime = PersonalAnalysisRuntime(
        authorize=lambda _: True, adapter=proposal_adapter,
    )
    app.state.observed_request_ids = []
    @app.middleware("http")
    async def observe(request, call_next):
        response = await call_next(request)
        app.state.observed_request_ids.append(getattr(request.state, "request_id", None))
        return response
    with TestClient(app) as client:
        yield client


def test_only_confirmed_binding_and_no_presentation_approval():
    assert APPROVED_VERSION_BINDINGS == (VersionBinding(
        "SIS-Q-1.1-100", "SIS-A-1.0", "local-personal-js100-port-1",
    ),)
    verify_reference_binding()
    policy = PersonalAnalysisAdapter().presentation
    assert not policy.reading_template_ids and policy.difference_reading is None
    assert not policy.reading_text_formatters
    assert policy.reference_evidence is reference_evidence
    assert len(CANDIDATE_READING_TEMPLATE_IDS) == len(set(CANDIDATE_READING_TEMPLATE_IDS.values())) == 15
    with pytest.raises(TypeError):
        CANDIDATE_READING_TEMPLATE_IDS[("new", "title")] = "new"


@pytest.mark.parametrize("version", ["latest", "SIS-A-1.1", "local-personal-js100-port-1"])
def test_injected_unapproved_alias_cannot_execute_core(monkeypatch, version):
    request = PersonalAnalysisRequest.model_validate(synthetic_request("succeeded"))
    request.analysisVersion = version
    alias = replace(APPROVED_VERSION_BINDING, analysis_version=version)
    adapter = PersonalAnalysisAdapter(version_bindings=(alias,), presentation=proposed_label_only_policy())
    monkeypatch.setattr(module, "analyze_personal", lambda *_: pytest.fail("unapproved alias executed"))
    with pytest.raises(VersionUnsupported):
        adapter.analyze(request)


@pytest.mark.parametrize("change", ["reference.json", "questionnaire-SIS-Q-1.1-100.json", "missing", "provenance"])
def test_data_binding_drift_fails_503_before_core(proposal_client, monkeypatch, tmp_path, change):
    original = module.DATA_DIR
    for name in APPROVED_DATA_HASHES:
        (tmp_path / name).write_bytes((original / name).read_bytes())
    if change == "missing":
        (tmp_path / "reference.json").unlink()
    elif change == "provenance":
        monkeypatch.setattr(module, "PROVENANCE", {"packaged": {}})
    else:
        (tmp_path / change).write_bytes((tmp_path / change).read_bytes() + b" ")
    monkeypatch.setattr(module, "DATA_DIR", tmp_path)
    monkeypatch.setattr(module, "analyze_personal", lambda *_: pytest.fail("drift executed"))
    response = proposal_client.post(PATH, json=synthetic_request("succeeded"))
    assert response.status_code == 503 and response.json()["code"] == "SERVICE_UNAVAILABLE"


@pytest.mark.parametrize("case", BRANCH_CASES, ids=lambda c: c["templateId"])
def test_fifteen_branches_keep_text_priority_and_evidence(proposal_adapter, case):
    request = request_for_patch({int(k): v for k, v in case["patch"].items()})
    before = request.model_dump()
    raw = raw_result(request)
    converted = proposal_adapter.analyze(request)
    assert raw.status == converted.status == "INSUFFICIENT"
    assert len(raw.readings) == len(converted.readings) == 1
    row = converted.readings[0]
    assert raw.readings[0]["title"] == case["title"]
    assert row.model_dump() == dict(
        key=case["key"], templateId=case["templateId"], text=case["text"],
        priority=case["priority"], evidenceQuestionIds=case["evidenceQuestionIds"],
    )
    assert request.model_dump() == before


@pytest.mark.parametrize("q030,approach_text", [
    (4, "호감이 생기면 먼저 마음을 표현하는 편이라고 답했어요."),
    (5.0, "호감이 생기면 먼저 마음을 분명하게 표현한다고 답했어요."),
])
def test_expression_reading_reflects_q032_without_changing_other_fields(proposal_client, q030, approach_text):
    legacy_adapter = PersonalAnalysisAdapter(presentation=replace(
        proposed_label_only_policy(), reading_text_formatters={},
    ))
    baselines = []
    for q032, frequency_text in [
        (2, "연인에게 좋아한다는 말은 가끔 한다고 답했어요."),
        (1, "연인에게 좋아한다는 말은 거의 하지 않는다고 답했어요."),
    ]:
        request = synthetic_request("succeeded")
        for answer in request["answers"]:
            if answer["questionId"] == "Q030":
                answer["value"] = q030
            elif answer["questionId"] == "Q032":
                answer["value"] = q032
        before = legacy_adapter.analyze(PersonalAnalysisRequest.model_validate(request)).model_dump()
        assert before["status"] == "SUCCEEDED" and len(before["scores"]) == 7
        if q030 == 4:
            assert (before["typeStatus"], before["primaryType"]) == ("ASSIGNED", "EMBER")
        index = next(i for i, row in enumerate(before["readings"]) if row["templateId"] == EXPRESSION_TEMPLATE_ID)
        assert before["readings"][index]["priority"] == 10
        assert before["readings"][index]["evidenceQuestionIds"] == ["Q030", "Q032"]

        response = proposal_client.post(PATH, json=request)
        assert response.status_code == 200
        assert response.headers["Cache-Control"] == "no-store"
        expected = deepcopy(before)
        expected["readings"][index]["text"] = f"{approach_text} {frequency_text}"
        assert response.json() == expected
        assert expected["readings"][index]["text"] != before["readings"][index]["text"]
        baselines.append(before)
    assert baselines[0] == baselines[1]


@pytest.mark.parametrize("qid", ["Q030", "Q032"])
def test_expression_reading_requires_both_measured_answers(proposal_client, qid):
    request = synthetic_request("succeeded")
    answer = next(answer for answer in request["answers"] if answer["questionId"] == qid)
    answer["value"] = "unknown"
    response = proposal_client.post(PATH, json=request)
    assert response.status_code == 200
    assert all(row["templateId"] != EXPRESSION_TEMPLATE_ID for row in response.json()["readings"])

    answer["value"] = 0
    response = proposal_client.post(PATH, json=request)
    assert response.status_code == 422
    assert response.json()["code"] == "INVALID_REQUEST"


@pytest.mark.parametrize("answers", [{30: 4}, {30: 4, 32: 0}, {30: 6, 32: 2}, {30: 4, 32: True}])
def test_expression_formatter_falls_back_for_unverified_codes(answers):
    row = core.readings({30: 4, 32: 2})[0]
    assert format_expression_reading(row, answers) == row["text"]


@pytest.mark.parametrize("evidence_issue", ["missing-question", "missing-answer", "mismatched-answer"])
def test_expression_reading_falls_back_when_evidence_is_unusable(proposal_adapter, evidence_issue):
    request = PersonalAnalysisRequest.model_validate(synthetic_request("succeeded"))
    raw = raw_result(request)
    row = next(row for row in raw.readings if row["key"] == "expression")
    if evidence_issue == "missing-question":
        row["evidence"].pop()
    elif evidence_issue == "missing-answer":
        del row["evidence"][1]["answer"]
    else:
        row["evidence"][1]["answer"] = core.answer_label(32, 1)
    actual = module.to_be_response(raw, APPROVED_VERSION_BINDING, proposal_adapter.presentation, context(request))
    reading = next(reading for reading in actual.readings if reading.templateId == EXPRESSION_TEMPLATE_ID)
    assert reading.text == row["text"]
    assert reading.evidenceQuestionIds == [f"Q{item['id']:03}" for item in row["evidence"]]


@pytest.mark.parametrize("q,actual,wish,tolerance", product(
    [65, 89], range(1, 6), range(1, 6), ["close", "some", "wide", "unknown"],
))
def test_gap_boundaries_keep_core_fit_order_but_omit_fit_text(proposal_adapter, q, actual, wish, tolerance):
    request = request_for_patch({q - 35: actual, q: wish, 98: tolerance})
    raw = raw_result(request)
    converted = proposal_adapter.analyze(request)
    assert len(converted.selfPreferenceDifferences) == len(raw.selfPreferenceDifferences)
    for index, (item, original) in enumerate(zip(converted.selfPreferenceDifferences, raw.selfPreferenceDifferences, strict=True)):
        assert 0 <= original["fit"] < 1
        assert item.text == (
            f"{original['label']}: 나의 응답은 ‘{original['self']}’이고, "
            f"상대에게 바라는 응답은 ‘{original['wish']}’입니다."
        )
        assert "적합도" not in item.text and "fit" not in item.text
        assert item.priority == 2 - index
        expected = [f"Q{q-35:03}", f"Q{q:03}"] + (["Q098"] if tolerance != "unknown" else [])
        assert item.evidenceQuestionIds == expected


@pytest.mark.parametrize("patch,expected", [
    ({30: 1, 31: 1, 32: 1, 65: 5, 66: 5, 67: 5, 98: "close"}, [65, 66]),
    ({30: 1, 31: 2, 65: 5, 66: 4, 98: "close"}, [65, 66]),
    ({44: "explain", 79: ["apologize"], 98: "wide"}, [79]),
    ({40: ["walk", "talk"], 75: ["talk"]}, []),
    ({52: ["irregular"], 87: ["evening"]}, []),
    ({30: 1, 65: "any"}, []),
])
def test_gap_rank_ties_categories_and_unknown(proposal_adapter, patch, expected):
    request = request_for_patch(patch)
    raw, converted = raw_result(request), proposal_adapter.analyze(request)
    assert [row["id"] for row in raw.selfPreferenceDifferences] == expected
    assert [row.priority for row in converted.selfPreferenceDifferences] == [2, 1][:len(expected)]
    assert len({row.key for row in converted.selfPreferenceDifferences}) == len(expected)
    if expected == [79]:
        assert converted.selfPreferenceDifferences[0].evidenceQuestionIds == ["Q044", "Q079"]


@pytest.mark.parametrize("patch,expected", [
    ({61: "low", 65: 1, 79: ["apologize"], 96: ["approach"], 98: "close"}, ["Q061","Q065","Q079","Q096","Q098"]),
    ({61: "low", 65: 1, 79: ["apologize"], 96: "unknown", 98: "close"}, ["Q061","Q065","Q079","Q098"]),
    ({61: "low", 65: 1, 79: ["apologize"], 96: "any", 98: "close"}, ["Q061","Q065","Q079","Q098"]),
    ({61: "low", 65: 1, 79: ["apologize"], 96: ["money"], 98: "close"}, ["Q061","Q065","Q079","Q098"]),
    ({61: "low", 65: 1, 79: ["apologize"], 96: ["approach"], 98: "unknown"}, ["Q061","Q065","Q079","Q096"]),
    ({79: ["apologize"], 96: ["conflict"], 98: "wide"}, ["Q079","Q096"]),
    ({65: 3, 79: ["apologize"], 98: "wide"}, ["Q079"]),
    ({61: "low", 65: "any", 98: "close"}, ["Q061","Q098"]),
])
def test_reference_conditions_use_actual_rows(proposal_adapter, patch, expected):
    request = request_for_patch(patch)
    raw, converted = raw_result(request), proposal_adapter.analyze(request)
    assert converted.typeRecommendations
    for original, row in zip(raw.typeReferences, converted.typeRecommendations, strict=True):
        assert row.mode == "PREFERENCE_FIT" and row.evidenceQuestionIds == expected
        assert row.typeCode == original["type"] and row.score == original["score"]


def test_all_twenty_five_preference_features_and_zero_fit_rows(proposal_adapter):
    request = PersonalAnalysisRequest.model_validate(synthetic_request("succeeded"))
    for answer, question in zip(request.answers, CATALOGUE["questions"], strict=True):
        if 61 <= question["ordinal"] <= 95:
            schema = question["responseSchema"]["oneOf"][0]
            answer.value = schema["enum"][0] if "enum" in schema else [schema["items"]["enum"][0]]
    raw, converted = raw_result(request), proposal_adapter.analyze(request)
    expected = [f"Q{q:03}" for q in list(range(61,83)) + [86,88,94]] + ["Q096","Q098"]
    assert any(row["fit"] == 0 for item in raw.typeReferences for row in item["rows"])
    assert all(item.evidenceQuestionIds == expected for item in converted.typeRecommendations)


@pytest.mark.parametrize("missing", [None, "Q001", "Q004"])
def test_self_similarity_expands_only_consumed_model_features(proposal_adapter, missing):
    request = PersonalAnalysisRequest.model_validate(synthetic_request("succeeded"))
    for answer in request.answers:
        q = int(answer.questionId[1:])
        if 61 <= q <= 95:
            answer.value = "any"
        elif q == 96:
            answer.value = ["approach"]
        elif q == 98:
            answer.value = "wide"
        if answer.questionId == missing:
            answer.value = "unknown"
    converted = proposal_adapter.analyze(request)
    expected = [1,6,11,16,2,7,12,17,3,8,13,18,5,10,15,20,30,31,32,33,34,35,36,37,41,38,51,59,39,40,53,42,43,44,45,46,47]
    if missing == "Q001":
        expected = [q for q in expected if q not in (1,6,11,16)]
    assert converted.typeRecommendations
    for row in converted.typeRecommendations:
        assert row.mode == "SELF_SIMILARITY"
        assert row.evidenceQuestionIds == [f"Q{q:03}" for q in expected]


def test_complete_unknown_input_needs_no_fabricated_presentation():
    result = PersonalAnalysisAdapter().analyze(request_for_patch({}))
    assert result.status == result.typeStatus == "INSUFFICIENT"
    assert set(result.scores.values()) == {None}
    assert result.readings == result.selfPreferenceDifferences == result.typeRecommendations == []
    assert len(result.missingQuestionIds) == 60


def test_all_js_synthetic_cases_keep_core_fields_and_order(js_reference, proposal_adapter):
    for case in js_reference["cases"]:
        request = request_for_patch({int(k): v for k, v in case["answers"].items()})
        raw, converted = raw_result(request), proposal_adapter.analyze(request)
        for field in ("status","surveyVersion","scores","typeStatus","primaryType","tiedTypes","missingQuestionIds"):
            assert getattr(converted, field) == getattr(raw, field), (case["name"], field)
        for reading, row in zip(converted.readings, raw.readings, strict=True):
            assert reading.key == row["key"]
            assert reading.templateId == CANDIDATE_READING_TEMPLATE_IDS[(row["key"], row["title"])]
            assert reading.priority == row["priority"]
            assert reading.evidenceQuestionIds == [f"Q{item['id']:03}" for item in row["evidence"]]
            if converted.status == "SUCCEEDED" and reading.templateId == EXPRESSION_TEMPLATE_ID:
                assert reading.text != row["text"]
            else:
                assert reading.text == row["text"]
        assert [x.typeCode for x in converted.typeRecommendations] == [x["type"] for x in raw.typeReferences]
        assert [x.score for x in converted.typeRecommendations] == [x["score"] for x in raw.typeReferences]
        assert [x.priority for x in converted.selfPreferenceDifferences] == [2,1][:len(raw.selfPreferenceDifferences)]


@pytest.mark.parametrize("tamper", ["id", "feature", "domain", "domains", "mode"])
def test_bad_reference_source_never_gets_fallback_evidence(tamper):
    request = PersonalAnalysisRequest.model_validate(synthetic_request("succeeded"))
    row = deepcopy(raw_result(request).typeReferences[0])
    if tamper == "domains":
        row["domains"] = []
    elif tamper == "mode":
        row["referenceMode"] = "UNKNOWN"
    else:
        row["rows"][0][tamper] = 999 if tamper == "id" else "unknown"
    with pytest.raises(PresentationUnavailable):
        reference_evidence(row, context(request))


@pytest.mark.parametrize("tamper", ["label", "self", "wish", "fit"])
def test_bad_gap_source_never_becomes_generic_text(tamper):
    request = PersonalAnalysisRequest.model_validate(synthetic_request("succeeded"))
    row = deepcopy(raw_result(request).selfPreferenceDifferences[0])
    row[tamper] = float("nan") if tamper == "fit" else "unverified"
    with pytest.raises(PresentationUnavailable):
        proposed_label_only_policy().difference_reading(row, 0, context(request))


@pytest.mark.parametrize("headers,expected", [
    ([], None),
    ([("X-Request-Id","11111111-2222-4333-8444-555555555555")], "11111111-2222-4333-8444-555555555555"),
    ([("X-Request-Id","AAAAAAAA-2222-4333-8444-BBBBBBBBBBBB")], "AAAAAAAA-2222-4333-8444-BBBBBBBBBBBB"),
    ([("X-Request-Id","untrusted-sensitive-sentinel")], None),
    ([("X-Request-Id","1"*37)], None),
    ([("X-Request-Id","11111111-2222-4333-8444-555555555555")]*2, None),
])
@pytest.mark.parametrize("body_case", ["valid", "malformed", "unconfigured-auth"])
def test_request_id_is_tracing_only(proposal_client, headers, expected, body_case, caplog):
    observed = proposal_client.app.state.observed_request_ids
    if body_case == "unconfigured-auth":
        runtime = proposal_client.app.state.personal_analysis_runtime
        proposal_client.app.state.personal_analysis_runtime = replace(runtime, authorize=None)
    if body_case == "valid":
        response = proposal_client.post(PATH, json=synthetic_request("succeeded"), headers=headers)
    else:
        response = proposal_client.post(PATH, content="{", headers=[*headers, ("Content-Type", "application/json")])
    assert response.status_code == {"valid":200,"malformed":400,"unconfigured-auth":503}[body_case]
    value = response.headers["X-Request-Id"]
    assert observed == [value]
    if expected:
        assert value == expected
    else:
        assert UUID(value).version == 4
    assert response.headers["Cache-Control"] == "no-store"
    assert "untrusted-sensitive-sentinel" not in response.text + caplog.text


@pytest.mark.parametrize("status,expected", [(400,(400,"INVALID_REQUEST")),(503,(503,"SERVICE_UNAVAILABLE")),(418,(500,"INTERNAL_ERROR"))])
def test_http_exception_is_not_always_misclassified_as_bad_json(proposal_client, monkeypatch, status, expected):
    def broken(*_):
        raise HTTPException(status_code=status, detail="private-sentinel")
    monkeypatch.setattr(module,"analyze_personal",broken)
    response = proposal_client.post(PATH,json=synthetic_request("succeeded"))
    assert (response.status_code,response.json()["code"]) == expected
    assert response.headers["Cache-Control"] == "no-store"
    assert "private-sentinel" not in response.text


@pytest.mark.parametrize("kind,status", [("succeeded","SUCCEEDED"),("insufficient","INSUFFICIENT")])
def test_sample_files_regenerate_exactly_and_preserve_internal_result(proposal_client, kind, status):
    generated = build_review_samples()
    for name,value in generated.items():
        assert (FIXTURE_DIR/name).read_text(encoding="utf-8") == json_text(value), name
    request = generated[f"{kind}.request.json"]
    assert len(request["answers"]) == len({a["questionId"] for a in request["answers"]}) == 100
    response = proposal_client.post(PATH,json=request)
    assert response.status_code == 200
    actual = response.json()
    assert actual == generated[f"{kind}.response.proposal.json"]
    assert actual["status"] == status
    assert "analysisId" not in actual and "requestId" not in actual
    assert actual["readings"] and len(actual["selfPreferenceDifferences"]) == 2
    assert len(actual["typeRecommendations"]) == 3
    if status == "SUCCEEDED":
        assert actual["typeStatus"] == "ASSIGNED" and actual["primaryType"] == "EMBER"
    if status == "INSUFFICIENT":
        assert actual["scores"]["E"] is not None
        assert any(value is None for value in actual["scores"].values())
        assert actual["primaryType"] is None and actual["missingQuestionIds"]
    manifest = generated["sample-manifest.json"]
    assert manifest["presentation"]["approval"] == "UNAPPROVED"
    for name,expected in manifest["files"].items():
        assert sha256((FIXTURE_DIR/name).read_bytes()).hexdigest() == expected


def test_candidate_is_reentrant_and_input_order_independent(proposal_adapter):
    request = PersonalAnalysisRequest.model_validate(synthetic_request("succeeded"))
    before = request.model_dump()
    baseline = proposal_adapter.analyze(request)
    other = PersonalAnalysisRequest.model_validate(synthetic_request("insufficient"))
    proposal_adapter.analyze(other)
    request.answers.reverse()
    assert proposal_adapter.analyze(request) == baseline
    assert request.model_dump()["answers"] == list(reversed(before["answers"]))
