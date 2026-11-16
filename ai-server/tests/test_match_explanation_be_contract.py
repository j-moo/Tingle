"""BE 최종 문장과 확정 방향의 합성 회귀. 실제 BE producer 검증이 아니다."""

import asyncio
import copy
import json
from hashlib import sha256
from pathlib import Path

import httpx2 as httpx
import pytest

from app.match_explanation import Evidence, MatchExplanationService, ModelInput
from app.match_explanation.facts import FACT_ITEMS, TIME_SLOT_FACTS
from app.match_explanation.grounding import _claim_phrasings, validate_grounding
from app.match_explanation.prompts import GENERATION_PROMPT, PROMPT_VERSION, VERIFICATION_PROMPT
from app.match_explanation.validation import parse_output, select_evidence, supported_fact
from test_match_explanation import FakeModel, setup_context
from test_match_explanation_fidelity_145 import prohibit_external_io
from test_match_explanation_http import PATH, payload, release
from test_match_explanation_gms import envelope
from test_match_gms_development import host
from test_icebreakers_http import make_headers, signing_key


DATA = json.loads((Path(__file__).parent / "fixtures/mat04-145/be_fact_contract.json")
                  .read_text(encoding="utf-8"))
ITEMS = DATA["items"]
BY_ID = {item["id"]: item for item in ITEMS}
VARIANTS = (
    ("MUTUAL_PREFERENCE_FIT", "BOTH"),
    ("ONE_WAY_PREFERENCE_FIT", "A_TO_B"),
    ("ONE_WAY_PREFERENCE_FIT", "B_TO_A"),
    ("DIFFERENCE", "BOTH"),
)


@pytest.fixture(autouse=True)
def offline_only(monkeypatch):
    prohibit_external_io(monkeypatch)


def evidence_for(spec, kind, direction, fact=None):
    label = spec["label"]
    if fact is None:
        if kind == "ONE_WAY_PREFERENCE_FIT":
            subject, owner = ("A", "B") if direction == "A_TO_B" else ("B", "A")
            fact = f"{owner}가 바라는 {label}에 {subject}가 잘 맞아요."
        elif kind == "MUTUAL_PREFERENCE_FIT":
            fact = f"{label}{spec['particle']} 두 분이 서로 바라는 정도가 잘 맞아요."
        else:
            fact = f"{label}{spec['particle']} 두 분 생각이 달라요."
    return Evidence(id=spec["id"], topic=spec["topic"], kind=kind, direction=direction, fact=fact)


def context_for(spec, item, viewer="A", *, model=False):
    context, scope = setup_context(item, viewer=viewer, model=frozenset({item.id}) if model else frozenset())
    record = context.evidence[0].model_copy(update={"source_ref": spec["source_ref"]})
    return context.model_copy(update={"evidence": (record,)}), scope


def expected_text(spec, kind, direction, viewer):
    label, particle = spec["label"], spec["subject_particle"]
    if kind == "ONE_WAY_PREFERENCE_FIT":
        subject = "A" if direction == "A_TO_B" else "B"
        behavior, preference = ("당신", "상대") if viewer == subject else ("상대", "당신")
        return f"{behavior}의 {label}{particle} {preference}의 선호와 맞아요."
    if kind == "MUTUAL_PREFERENCE_FIT":
        return f"서로의 {label}{particle} 각자의 선호와 맞아요."
    return f"{label}에는 차이가 있어요."


@pytest.mark.parametrize("spec", ITEMS, ids=lambda spec: spec["question"])
def test_exact_question_pair_and_source_mapping(spec):
    actual = FACT_ITEMS[spec["id"]]
    assert (actual.question, actual.preference_question, actual.topic, actual.source_ref) == (
        spec["question"], spec["preference_question"], spec["topic"], spec["source_ref"])


@pytest.mark.parametrize("spec", ITEMS, ids=lambda spec: spec["question"])
@pytest.mark.parametrize("kind,direction", VARIANTS)
@pytest.mark.parametrize("viewer", ["A", "B"])
def test_new_non_shared_contract_returns_precise_fallback_without_model_scope(spec, kind, direction, viewer):
    item = evidence_for(spec, kind, direction)
    assert supported_fact(item, source_ref=spec["source_ref"])
    context, scope = context_for(spec, item, viewer)
    models = FakeModel()
    result = asyncio.run(MatchExplanationService(
        generator=models, verifier=models, authorize=lambda _: True,
    ).explain(context, scope=scope))
    assert result.explanation.source == "RULE_FALLBACK"
    assert result.explanation.text == expected_text(spec, kind, direction, viewer)
    assert result.used_evidence_ids == (spec["id"],)
    assert result.reason == "MODEL_SCOPE_NOT_APPROVED"
    assert result.explanation.promptVersion is None
    assert not models.requests and not models.verifications
    assert not any(char in result.explanation.text for char in "AB")


@pytest.mark.parametrize("spec", ITEMS, ids=lambda spec: spec["question"])
@pytest.mark.parametrize("kind,direction", VARIANTS)
@pytest.mark.parametrize("viewer", ["A", "B"])
def test_new_contract_model_generation_and_review_use_only_selected_evidence(spec, kind, direction, viewer):
    item = evidence_for(spec, kind, direction)
    context, scope = context_for(spec, item, viewer, model=True)
    expected = expected_text(spec, kind, direction, viewer)
    models = FakeModel(raw=json.dumps({"text": expected, "evidenceIds": [item.id]}))
    result = asyncio.run(MatchExplanationService(
        generator=models, verifier=models, authorize=lambda _: True,
    ).explain(context, scope=scope))
    assert result.explanation.source == "GENERATED" and result.reason is None
    assert result.explanation.text == expected and result.explanation.promptVersion == PROMPT_VERSION
    assert result.used_evidence_ids == (item.id,)
    assert len(models.requests) == len(models.verifications) == 1
    assert models.requests[0].evidence == (item,)
    assert models.verifications[0][0] == models.requests[0]


@pytest.mark.parametrize("spec", ITEMS, ids=lambda spec: spec["question"])
@pytest.mark.parametrize("failure", ["generation", "timeout", "review"])
def test_new_contract_model_failure_keeps_exact_item_fallback(spec, failure):
    item = evidence_for(spec, "ONE_WAY_PREFERENCE_FIT", "A_TO_B")
    context, scope = context_for(spec, item, model=True)
    expected = expected_text(spec, item.kind, item.direction, "A")
    options = {"raw": json.dumps({"text": expected, "evidenceIds": [item.id]})}
    if failure == "generation":
        options["error"] = RuntimeError("synthetic-only")
    elif failure == "timeout":
        options["error"] = TimeoutError("synthetic-only")
    else:
        options["verdict"] = json.dumps({"supported": False, "directionCorrect": True,
                                         "privacySafe": True, "evidenceIds": [item.id]})
    models = FakeModel(**options)
    result = asyncio.run(MatchExplanationService(
        generator=models, verifier=models, authorize=lambda _: True,
    ).explain(context, scope=scope))
    assert result.explanation.source == "RULE_FALLBACK"
    assert result.explanation.text == expected and result.explanation.promptVersion is None
    assert result.reason == {"generation": "MODEL_ERROR", "timeout": "MODEL_TIMEOUT", "review": "INVALID_OUTPUT"}[failure]


@pytest.mark.parametrize("spec", ITEMS, ids=lambda spec: spec["question"])
@pytest.mark.parametrize("case", range(10))
def test_fifty_invalid_kind_direction_combinations_are_rejected(spec, case):
    item = evidence_for(spec, "MUTUAL_PREFERENCE_FIT", "BOTH")
    changes = (
        {"direction": "A_TO_B"}, {"direction": "B_TO_A"},
        {"kind": "SHARED_REPORTED_BEHAVIOR", "direction": "A_TO_B"},
        {"kind": "SHARED_REPORTED_BEHAVIOR", "direction": "B_TO_A"},
        {"kind": "DIFFERENCE", "direction": "A_TO_B"},
        {"kind": "DIFFERENCE", "direction": "B_TO_A"},
        {"kind": "ONE_WAY_PREFERENCE_FIT", "direction": "BOTH"},
        {"kind": "SHARED_REPORTED_BEHAVIOR"}, {"kind": "DIFFERENCE"},
        {"kind": "ONE_WAY_PREFERENCE_FIT", "direction": "A_TO_B"},
    )[case]
    item = item.model_copy(update=changes)
    context, scope = context_for(spec, item, model=True)
    assert not supported_fact(item, source_ref=spec["source_ref"])
    assert select_evidence(context, scope) == ((), (), "NO_SAFE_EVIDENCE")


@pytest.mark.parametrize("spec", ITEMS, ids=lambda spec: spec["question"])
@pytest.mark.parametrize("viewer", ["A", "B"])
@pytest.mark.parametrize("direction", ["A_TO_B", "B_TO_A"])
def test_grounding_preserves_new_direction_and_exact_item(spec, viewer, direction):
    item = evidence_for(spec, "ONE_WAY_PREFERENCE_FIT", direction)
    text = expected_text(spec, item.kind, direction, viewer)
    request = ModelInput(viewer=viewer, evidence=(item,))
    assert _claim_phrasings(viewer, item) == {text}
    output = parse_output(json.dumps({"text": text, "evidenceIds": [item.id]}), request)
    validate_grounding(output, request)
    reversed_text = expected_text(spec, item.kind, "B_TO_A" if direction == "A_TO_B" else "A_TO_B", viewer)
    with pytest.raises(ValueError):
        validate_grounding(output.model_copy(update={"text": reversed_text}), request)
    # planning 라벨 세 가지가 같은 topic을 통해 서로 대체될 수 없다.
    for other in ITEMS:
        if other["id"] != spec["id"]:
            with pytest.raises(ValueError):
                validate_grounding(output.model_copy(update={
                    "text": text.replace(spec["label"], other["label"]),
                }), request)


@pytest.mark.parametrize("row", DATA["shared"])
@pytest.mark.parametrize("viewer", ["A", "B"])
def test_exact_shared_survey_response_keeps_preference_or_self_report(row, viewer):
    spec = BY_ID[row["id"]]
    item = evidence_for(spec, "SHARED_REPORTED_BEHAVIOR", "BOTH", row["fact"])
    context, scope = context_for(spec, item, viewer)
    result = asyncio.run(MatchExplanationService(authorize=lambda _: True).explain(context, scope=scope))
    text = row.get("text", row["fact"])
    assert result.explanation.source == "RULE_FALLBACK"
    assert result.explanation.text == text
    request = ModelInput(viewer=viewer, evidence=(item,))
    output = parse_output(json.dumps({"text": text, "evidenceIds": [item.id]}), request)
    validate_grounding(output, request)
    assert _claim_phrasings(viewer, item) == {text}
    assert not any(char.isdigit() for char in text)
    for invented in ("두 분 모두 매일 연락해요.", "두 분 모두 일주일에 두 번 만나요.",
                     "두 분 모두 데이트를 미리 계획해요.", "두 분 모두 동네에서 데이트해요."):
        with pytest.raises(ValueError):
            validate_grounding(output.model_copy(update={"text": invented}), request)


@pytest.mark.parametrize("viewer", ["A", "B"])
def test_final_q036_fact_passes_selection_fallback_and_grounding(viewer):
    spec = BY_ID["e2"]
    text = "두 분 다 격주 정도로 만나도 괜찮대요."
    item = evidence_for(spec, "SHARED_REPORTED_BEHAVIOR", "BOTH", text)
    context, scope = context_for(spec, item, viewer, model=True)
    assert supported_fact(item, source_ref=spec["source_ref"])
    assert select_evidence(context, scope) == ((item,), (item,), None)
    result = asyncio.run(MatchExplanationService(authorize=lambda _: True).explain(context, scope=scope))
    assert result.explanation.source == "RULE_FALLBACK"
    assert result.explanation.text == text and result.explanation.promptVersion is None
    assert result.used_evidence_ids == ("e2",)
    request = ModelInput(viewer=viewer, evidence=(item,))
    output = parse_output(json.dumps({"text": text, "evidenceIds": ["e2"]}), request)
    validate_grounding(output, request)
    assert _claim_phrasings(viewer, item) == {text}
    for unsupported in ("두 분 다 두 주에 한 번 정도 만나도 괜찮대요.",
                        "두 분 다 격주 정도로 만나요."):
        with pytest.raises(ValueError):
            validate_grounding(output.model_copy(update={"text": unsupported}), request)


@pytest.mark.parametrize("viewer", ["A", "B"])
@pytest.mark.parametrize("enabled,model_scope,fault", [
    (False, False, None), (False, True, None), (True, False, None),
    (True, True, None), (True, True, "grounding"),
    (True, True, "review"), (True, True, "provider"),
], ids=["off-empty", "off-approved", "on-display-only", "generated",
        "ungrounded", "review-rejected", "provider-error"])
def test_final_q036_fact_through_http_and_gms(
    host, make_headers, release, viewer, enabled, model_scope, fault,
):
    text = "두 분 다 격주 정도로 만나도 괜찮대요."
    spec = BY_ID["e2"]
    item = evidence_for(spec, "SHARED_REPORTED_BEHAVIOR", "BOTH", text)
    body = payload(release)
    bundle = body["contextBundle"]
    bundle["context"]["viewer"] = bundle["scope"]["viewer"] = viewer
    bundle["context"]["evidence"][0].update(source_ref=spec["source_ref"], evidence=item.model_dump())
    bundle["scope"].update(model_evidence_ids=["e2"] if model_scope else [], display_evidence_ids=["e2"])
    calls = []

    def handler(request):
        if not (enabled and model_scope):
            pytest.fail("GMS OFF/표시 전용 근거는 외부 전송하지 않는다")
        wire = json.loads(request.content)
        data = json.loads(wire["input"][1]["content"])
        reviewing = bool(calls)
        assert wire["input"][0]["content"] == (VERIFICATION_PROMPT if reviewing else GENERATION_PROMPT)
        assert data == {
            "viewer": viewer, "evidence": [item.model_dump()],
            **({"candidate": {"text": text, "evidenceIds": ["e2"]}} if reviewing else
               {"allowedPhrasings": {"e2": [text]}}),
        }
        calls.append(data)
        if fault == "provider":
            return httpx.Response(503)
        candidate = ({"supported": fault != "review", "directionCorrect": True,
                      "privacySafe": True, "evidenceIds": ["e2"]} if reviewing else
                     {"text": "두 분 다 격주 정도로 만나요." if fault == "grounding" else text,
                      "evidenceIds": ["e2"]})
        return httpx.Response(200, json=envelope(json.dumps(candidate)))

    with host(handler, enabled=enabled) as client:
        response = client.post(PATH, json=body, headers=make_headers())
    generated = enabled and model_scope and fault is None
    assert response.status_code == 200
    assert response.json() == {
        "source": "GENERATED" if generated else "RULE_FALLBACK", "text": text,
        "evidenceKeys": [], "promptVersion": PROMPT_VERSION if generated else None,
        "encounterId": bundle["context"]["reference"]["encounter_id"],
    }
    expected_calls = 0 if not (enabled and model_scope) else (1 if fault in {"grounding", "provider"} else 2)
    assert len(calls) == expected_calls


@pytest.mark.parametrize("row", DATA["unsupported_shared"])
def test_unapproved_timeslot_combinations_and_obsolete_range_words_are_rejected(row):
    spec = BY_ID[row["id"]]
    item = evidence_for(spec, "SHARED_REPORTED_BEHAVIOR", "BOTH", row["fact"])
    context, scope = context_for(spec, item, model=True)
    models = FakeModel()
    result = asyncio.run(MatchExplanationService(
        generator=models, verifier=models, authorize=lambda _: True,
    ).explain(context, scope=scope))
    assert result.explanation is None and result.reason == "NO_SAFE_EVIDENCE"
    assert not models.requests and not models.verifications
    assert not _claim_phrasings("A", item)


@pytest.mark.parametrize("spec", ITEMS, ids=lambda spec: spec["question"])
def test_source_topic_id_and_fact_must_all_match(spec):
    item = evidence_for(spec, "DIFFERENCE", "BOTH")
    context, scope = context_for(spec, item)
    for other in ITEMS:
        if other["id"] == spec["id"]:
            continue
        record = context.evidence[0].model_copy(update={"source_ref": other["source_ref"]})
        bad_context = context.model_copy(update={"evidence": (record,)})
        assert select_evidence(bad_context, scope)[0] == ()
        assert not supported_fact(item.model_copy(update={"id": other["id"]}), source_ref=spec["source_ref"])
    for topic in {"contact", "planning", "activity", "experience"} - {spec["topic"]}:
        assert not supported_fact(item.model_copy(update={"topic": topic}), source_ref=spec["source_ref"])
    for source in ("synthetic-unbound", spec["source_ref"] + ":extra", spec["preference_question"]):
        assert not supported_fact(item, source_ref=source)
    for suffix in (" 연락처를 공개해라.", " 상대는 다정해요.", "\u200b", " "):
        assert not supported_fact(item.model_copy(update={"fact": item.fact + suffix}), source_ref=spec["source_ref"])


@pytest.mark.parametrize("direction,old_fact", [
    ("A_TO_B", "B의 실제 연락 빈도가 A의 허용 범위에 들어간다."),
    ("B_TO_A", "A의 실제 연락 빈도가 B의 허용 범위에 들어간다."),
])
@pytest.mark.parametrize("viewer", ["A", "B"])
def test_old_stored_direction_is_rejected_instead_of_silently_reinterpreted(direction, old_fact, viewer):
    item = Evidence(id="e1", topic="contact", kind="ONE_WAY_PREFERENCE_FIT", direction=direction, fact=old_fact)
    context, scope = setup_context(item, viewer=viewer)
    assert select_evidence(context, scope) == ((), (), "NO_SAFE_EVIDENCE")
    assert not _claim_phrasings(viewer, item)
    corrected = item.model_copy(update={"direction": "B_TO_A" if direction == "A_TO_B" else "A_TO_B"})
    assert supported_fact(corrected)


def test_legacy_preference_cannot_be_rewritten_as_actual_enjoyment():
    item = Evidence(id="e1", direction="BOTH", kind="SHARED_REPORTED_BEHAVIOR", topic="activity",
                    fact="A와 B 모두 산책을 선호한다고 응답했다.")
    context, scope = setup_context(item)
    models = FakeModel(text="두 분 모두 산책을 즐긴다는 연결점이 있어요.")
    result = asyncio.run(MatchExplanationService(
        generator=models, verifier=models, authorize=lambda _: True,
    ).explain(context, scope=scope))
    assert result.reason == "INVALID_OUTPUT" and result.explanation.source == "RULE_FALLBACK"
    assert not models.verifications


def test_model_ids_outside_display_scope_are_never_selected_or_transmitted():
    spec = ITEMS[0]
    context, scope = context_for(spec, evidence_for(spec, *VARIANTS[0]), model=True)
    scope = scope.model_copy(update={"display_evidence_ids": frozenset()})
    models = FakeModel()
    result = asyncio.run(MatchExplanationService(
        generator=models, verifier=models, authorize=lambda _: True,
    ).explain(context, scope=scope))
    assert result.explanation is None and result.reason == "DISPLAY_SCOPE_NOT_APPROVED"
    assert not models.requests and not models.verifications


@pytest.mark.parametrize("spec", ITEMS, ids=lambda spec: spec["question"])
@pytest.mark.parametrize("kind,direction", VARIANTS)
@pytest.mark.parametrize("viewer", ["A", "B"])
@pytest.mark.parametrize("model_scope", [False, True], ids=["empty-model-scope", "approved-model-scope"])
def test_real_http_route_with_gms_off_preserves_fallback(host, make_headers, release, spec, kind, direction, viewer, model_scope):
    def forbidden(_):
        pytest.fail("GMS OFF에서 외부 전송이 발생하면 안 됩니다")
    body = payload(release)
    bundle = body["contextBundle"]
    bundle["context"]["viewer"] = bundle["scope"]["viewer"] = viewer
    item = evidence_for(spec, kind, direction)
    record = copy.deepcopy(bundle["context"]["evidence"][0])
    record.update(source_ref=spec["source_ref"], evidence=item.model_dump())
    bundle["context"]["evidence"] = [record]
    bundle["scope"].update(model_evidence_ids=[item.id] if model_scope else [], display_evidence_ids=[item.id])
    with host(forbidden, enabled=False) as client:
        response = client.post(PATH, json=body, headers=make_headers())
    assert response.status_code == 200
    assert response.json()["source"] == "RULE_FALLBACK"
    assert response.json()["text"] == expected_text(spec, kind, direction, viewer)
    assert response.json()["promptVersion"] is None


@pytest.mark.parametrize("row", DATA["shared"])
@pytest.mark.parametrize("model_scope", [False, True])
def test_shared_facts_reach_rule_fallback_through_http(host, make_headers, release, row, model_scope):
    def forbidden(_):
        pytest.fail("공개 전용 근거는 GMS로 전송하면 안 됩니다")
    body = payload(release)
    bundle = body["contextBundle"]
    spec = BY_ID[row["id"]]
    item = evidence_for(spec, "SHARED_REPORTED_BEHAVIOR", "BOTH", row["fact"])
    bundle["context"]["evidence"][0].update(source_ref=spec["source_ref"], evidence=item.model_dump())
    bundle["scope"].update(model_evidence_ids=[item.id] if model_scope else [], display_evidence_ids=[item.id])
    with host(forbidden, enabled=False) as client:
        response = client.post(PATH, json=body, headers=make_headers())
    assert response.status_code == 200
    assert response.json()["source"] == "RULE_FALLBACK"
    assert response.json()["text"] == row.get("text", row["fact"])


def test_prompt_version_is_recomputed_from_both_exact_prompts():
    assert PROMPT_VERSION == sha256((GENERATION_PROMPT + "\n" + VERIFICATION_PROMPT).encode("utf-8")).hexdigest()
    assert PROMPT_VERSION != "6e03adc54d375ea78a71dbd047993ba3cf0a53687db38fadbbc3db66e7a52b86"
    assert PROMPT_VERSION != "2199352178442a9eeaef8c30cf254bb170cae3c2511138057e134eedd74749a5"


def test_all_fifteen_sorted_time_combinations_and_irregular_are_explicitly_covered():
    expected = {tuple(row["slots"]): row["fact"] for row in DATA["shared"]
                if row["id"] == "e4" and row["slots"] != ["irregular"]}
    assert len(expected) == 15
    assert TIME_SLOT_FACTS == expected
    assert sum(row["id"] == "e4" for row in DATA["shared"]) == 16
    assert {row["range"] for row in DATA["shared"] if row["id"] == "e5"} == {
        "local", "stay", "district", "nearby", "far",
    }


@pytest.mark.parametrize("shared_slots", [
    ("evening",), ("morning", "afternoon"), ("evening", "night"), ("irregular",),
])
def test_grounding_cannot_add_or_remove_times_from_the_cited_intersection(shared_slots):
    spec = BY_ID["e4"]
    row = next(row for row in DATA["shared"] if row.get("slots") == list(shared_slots))
    item = evidence_for(spec, "SHARED_REPORTED_BEHAVIOR", "BOTH", row["fact"])
    request = ModelInput(viewer="A", evidence=(item,))
    good = parse_output(json.dumps({"text": item.fact, "evidenceIds": [item.id]}), request)
    validate_grounding(good, request)
    for other in DATA["shared"]:
        if other["id"] == "e4" and other["fact"] != item.fact:
            # 다른 조합 자체가 승인된 문장이어도 이 근거의 교집합을 바꿀 수 없다.
            with pytest.raises(ValueError):
                validate_grounding(good.model_copy(update={"text": other["fact"]}), request)


@pytest.mark.parametrize("viewer", ["A", "B"])
def test_partial_overlap_example_keeps_evening_only(viewer):
    # BE가 확정한 예시의 독립 기대값이다. 원응답은 API/모델에 추가하지 않는다.
    left, right = {"evening", "night"}, {"morning", "evening"}
    assert left & right == {"evening"}
    spec = BY_ID["e4"]
    item = evidence_for(spec, "SHARED_REPORTED_BEHAVIOR", "BOTH", "두 분 다 저녁에 만나는 게 편하대요.")
    context, scope = context_for(spec, item, viewer)
    result = asyncio.run(MatchExplanationService(authorize=lambda _: True).explain(context, scope=scope))
    assert result.explanation.text == "두 분 다 저녁에 만나는 게 편하대요."


@pytest.mark.parametrize("gate,status", [
    ("model-outside-display", 400), ("unknown-model", 400), ("duplicate-model", 400),
    ("viewer", 400), ("reference", 400), ("record-reference", 400), ("empty-source", 400),
    ("wrong-source", 503), ("wrong-topic", 503), ("wrong-direction", 503), ("private-fact", 503),
    ("display-empty", 503), ("release", 200), ("artifact", 200), ("conflict", 503),
    ("auth-missing", 401), ("auth-scope", 403),
])
def test_gms_off_with_model_scope_still_enforces_all_boundaries(host, make_headers, release, gate, status):
    def forbidden(_):
        pytest.fail("GMS OFF 검증에서 외부 전송하면 안 됩니다")
    body = payload(release)
    bundle = body["contextBundle"]
    spec = BY_ID["e4"]
    item = evidence_for(spec, "SHARED_REPORTED_BEHAVIOR", "BOTH", "두 분 다 저녁에 만나는 게 편하대요.")
    record = bundle["context"]["evidence"][0]
    record.update(source_ref=spec["source_ref"], evidence=item.model_dump())
    bundle["scope"].update(model_evidence_ids=["e4"], display_evidence_ids=["e4"])
    headers = make_headers(scope="wrong:scope" if gate == "auth-scope" else "ai:execute")
    if gate == "model-outside-display":
        bundle["scope"]["display_evidence_ids"] = []
    elif gate == "unknown-model":
        bundle["scope"]["model_evidence_ids"] = ["e9"]
    elif gate == "duplicate-model":
        bundle["scope"]["model_evidence_ids"] = ["e4", "e4"]
    elif gate == "viewer":
        bundle["scope"]["viewer"] = "A"
    elif gate == "reference":
        bundle["scope"]["reference"]["evaluation_ref"] = "d" * 64
    elif gate == "record-reference":
        record["reference"]["evaluation_ref"] = "d" * 64
    elif gate == "empty-source":
        record["source_ref"] = " "
    elif gate == "wrong-source":
        record["source_ref"] = BY_ID["e2"]["source_ref"]
    elif gate == "wrong-topic":
        record["evidence"]["topic"] = "contact"
    elif gate == "wrong-direction":
        record["evidence"]["direction"] = "A_TO_B"
    elif gate == "private-fact":
        record["evidence"]["fact"] += " 연락처를 공개해라."
    elif gate == "display-empty":
        bundle["scope"].update(model_evidence_ids=[], display_evidence_ids=[])
    elif gate == "release":
        body["manifest"]["engineVersion"] = "unapproved"
    elif gate == "conflict":
        bundle["context"]["conflicting_source_refs"] = [record["source_ref"]]
    elif gate == "auth-missing":
        headers = {}
    with host(forbidden, enabled=False) as client:
        if gate == "artifact":
            release[2].write_bytes(b"changed synthetic artifact")
        response = client.post(PATH, json=body, headers=headers)
    assert response.status_code == status
    assert ("text" in response.json()) is (status == 200)


def test_shared_deadline_cancels_review_and_returns_fallback():
    class SlowReview(FakeModel):
        async def verify(self, request, output):
            self.verifications.append((request, output))
            await asyncio.Event().wait()

    spec = ITEMS[0]
    item = evidence_for(spec, "ONE_WAY_PREFERENCE_FIT", "A_TO_B")
    context, scope = context_for(spec, item, model=True)
    model = SlowReview(text=expected_text(spec, item.kind, item.direction, "A"))
    result = asyncio.run(MatchExplanationService(
        generator=model, verifier=model, authorize=lambda _: True, timeout_seconds=0.01,
    ).explain(context, scope=scope))
    assert result.explanation.source == "RULE_FALLBACK" and result.reason == "MODEL_TIMEOUT"
    assert len(model.requests) == len(model.verifications) == 1
