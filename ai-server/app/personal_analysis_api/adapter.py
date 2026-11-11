"""BE-confirmed version/evidence contract; presentation approval is separate.

The production app does not register this router. Candidate wording/template IDs
are never installed by the default PresentationPolicy.
"""

from collections.abc import Callable, Mapping
from copy import deepcopy
from dataclasses import dataclass, field
from hashlib import sha256
from types import MappingProxyType

from app.personal_analysis import core
from app.personal_analysis.dto import PersonalAnalysisInput, PersonalAnalysisResult, SurveyMetadata
from app.personal_analysis.reference import (
    CALCULATION_VERSION, CATALOGUE, DATA_DIR, DOMAINS, MODEL, PROVENANCE,
    QUESTIONS, QUESTION_IDS,
)
from app.personal_analysis.service import analyze_personal

from .dto import PersonalAnalysisRequest, PersonalAnalysisResponse, Reading, TypeReference


class VersionUnsupported(ValueError):
    def __init__(self) -> None:
        super().__init__("ANALYSIS_VERSION_UNSUPPORTED")


class PresentationUnavailable(RuntimeError):
    def __init__(self) -> None:
        super().__init__("Personal analysis contract or presentation is not configured")


@dataclass(frozen=True)
class VersionBinding:
    survey_version: str
    analysis_version: str
    calculation_version: str


# Confirmed by the user on 2026-10-29; no aliases or latest-version fallback.
APPROVED_VERSION_BINDING = VersionBinding(
    "SIS-Q-1.1-100", "SIS-A-1.0", "local-personal-js100-port-1",
)
APPROVED_VERSION_BINDINGS = (APPROVED_VERSION_BINDING,)
APPROVED_DATA_HASHES = MappingProxyType({
    "reference.json": "9f4d0c762a713c8c8bb8f69eb45c9e6206a33857ff482a4988d74db383585658",
    "questionnaire-SIS-Q-1.1-100.json": "d1199ef59596e78e2c2d3df8884a72a2b97c63099e443da7b9103f3d1e7bcb6e",
})
ORDINAL_TO_QUESTION_ID = MappingProxyType({ordinal: qid for qid, ordinal in QUESTION_IDS.items()})
AnswerContext = Mapping[int, object]


def verify_reference_binding() -> None:
    """A confirmed external version cannot silently select different data."""
    if (
        CATALOGUE["surveyVersion"] != APPROVED_VERSION_BINDING.survey_version
        or CALCULATION_VERSION != APPROVED_VERSION_BINDING.calculation_version
        or PROVENANCE["packaged"] != dict(APPROVED_DATA_HASHES)
    ):
        raise PresentationUnavailable()
    try:
        if any(sha256((DATA_DIR / name).read_bytes()).hexdigest() != expected
               for name, expected in APPROVED_DATA_HASHES.items()):
            raise PresentationUnavailable()
    except OSError:
        raise PresentationUnavailable() from None


def question_id(ordinal: int) -> str:
    if type(ordinal) is not int or ordinal not in ORDINAL_TO_QUESTION_ID:
        raise PresentationUnavailable()
    return ORDINAL_TO_QUESTION_ID[ordinal]


def numeric_preference(question: int) -> bool:
    return question <= 64 or all(core.numeric(o["value"]) for o in QUESTIONS[question - 35]["options"])


def difference_evidence(row: dict, answers: AnswerContext) -> list[str]:
    """Confirmed comparison-pair evidence plus an explicit numeric tolerance."""
    q = row["id"]
    if type(q) is not int or not 65 <= q <= 95 or row["selfId"] != q - 35:
        raise PresentationUnavailable()
    if not core.known(answers.get(q)) or not core.known(answers.get(q - 35)):
        raise PresentationUnavailable()
    ids = [question_id(row["selfId"]), question_id(q)]
    if numeric_preference(q) and answers.get(98) in ("close", "some", "wide"):
        ids.append(question_id(98))
    return ids


def reference_evidence(row: dict, answers: AnswerContext) -> list[str]:
    """Confirmed rule B: participating score rows, then measured Q096/Q098."""
    ids = []
    if row["referenceMode"] == "partner-preferences":
        for item in row["rows"]:
            q = item["id"]
            if (
                type(q) is not int or not 61 <= q <= 95
                or item["feature"] != core.target_feature(q)
                or item["feature"] not in MODEL["weights"]
                or not core.known(answers.get(q))
                or item["wish"] != answers[q]
                or not any(d["id"] == item["domain"] and q in d["ids"] for d in DOMAINS)
            ):
                raise PresentationUnavailable()
            ids.append(question_id(q))
        if not ids or {d["id"] for d in row["domains"]} != {r["domain"] for r in row["rows"]}:
            raise PresentationUnavailable()
        priority = answers.get(96)
        if isinstance(priority, list) and any(d["id"] in priority for d in row["domains"]):
            ids.append(question_id(96))
        if answers.get(98) in ("close", "some", "wide") and any(
            numeric_preference(item["id"]) for item in row["rows"]
        ):
            ids.append(question_id(98))
    elif row["referenceMode"] == "self-similarity":
        for item in row["rows"]:
            key = item["feature"]
            if key not in MODEL["weights"] or not core.known(item["self"]):
                raise PresentationUnavailable()
            if key.startswith("q"):
                q = int(key[1:])
                if not core.known(answers.get(q)) or item["self"] != answers[q]:
                    raise PresentationUnavailable()
                ids.append(question_id(q))
            else:
                if key not in core.SCALES or not all(core.numeric(answers.get(q)) for q in core.SCALES[key][0]):
                    raise PresentationUnavailable()
                ids.extend(question_id(q) for q in core.SCALES[key][0])
        if not ids:
            raise PresentationUnavailable()
    else:
        raise PresentationUnavailable()
    return list(dict.fromkeys(ids))


def format_expression_reading(row: dict, answers: AnswerContext) -> str:
    """Describe the two catalogue answers only when the core evidence agrees."""
    fallback = row["text"]
    approach, frequency = answers.get(30), answers.get(32)
    if not core.numeric(approach) or not core.numeric(frequency):
        return fallback
    approach_text = {
        4: "먼저 마음을 표현하는 편이라고",
        5: "먼저 마음을 분명하게 표현한다고",
    }.get(approach)
    frequency_text = {
        1: "거의 하지 않는다고",
        2: "가끔 한다고",
    }.get(frequency)
    if approach_text is None or frequency_text is None:
        return fallback

    evidence = row.get("evidence")
    if not isinstance(evidence, list) or len(evidence) != 2:
        return fallback
    for item, ordinal, value in zip(evidence, (30, 32), (approach, frequency), strict=True):
        if (
            not isinstance(item, dict) or item.get("id") != ordinal
            or item.get("answer") != core.answer_label(ordinal, value)
        ):
            return fallback

    return (
        f"호감이 생기면 {approach_text} 답했어요. "
        f"연인에게 좋아한다는 말은 {frequency_text} 답했어요."
    )


@dataclass(frozen=True)
class PresentationPolicy:
    # Fixed IDs/wording and difference priorities still require approval.
    reading_template_ids: Mapping[tuple[str, str], str] = field(default_factory=dict)
    difference_reading: Callable[[dict, int, AnswerContext], Reading] | None = None
    # Evidence semantics are confirmed, independently of wording approval.
    reference_evidence: Callable[[dict, AnswerContext], list[str]] | None = reference_evidence
    # Optional wording for successful results, keyed by the existing branch ID.
    reading_text_formatters: Mapping[str, Callable[[dict, AnswerContext], str]] = field(default_factory=dict)

    def __post_init__(self) -> None:
        object.__setattr__(self, "reading_template_ids", MappingProxyType(dict(self.reading_template_ids)))
        object.__setattr__(self, "reading_text_formatters", MappingProxyType(dict(self.reading_text_formatters)))


def to_core_input(request: PersonalAnalysisRequest, binding: VersionBinding) -> PersonalAnalysisInput:
    """analysisId stays at the HTTP boundary; absent metadata is explicitly null."""
    return PersonalAnalysisInput(
        surveyVersion=binding.survey_version,
        calculationVersion=binding.calculation_version,
        answers=request.answers,
        metadata=SurveyMetadata(ownAge=None, desiredAgeMin=None, desiredAgeMax=None),
    )


def to_be_response(
    result: PersonalAnalysisResult, binding: VersionBinding, policy: PresentationPolicy,
    answers: AnswerContext,
) -> PersonalAnalysisResponse:
    """Preserve order, evidence, scores, and the response DTO schema.

    An explicitly enabled presentation policy may format only the targeted
    Reading.text for successful PA-R-EXPRESSION-01 results with verified
    Q030/Q032 answers. BE alone owns public filtering.
    """
    if (result.surveyVersion, result.calculationVersion) != (
        binding.survey_version, binding.calculation_version,
    ):
        raise PresentationUnavailable()
    readings = []
    formatters = policy.reading_text_formatters if result.status == "SUCCEEDED" else {}
    for row in result.readings:
        template_id = policy.reading_template_ids.get((row["key"], row["title"]))
        if not template_id:
            raise PresentationUnavailable()
        formatter = formatters.get(template_id)
        readings.append(Reading(
            key=row["key"], templateId=template_id, priority=row["priority"],
            text=formatter(row, answers) if formatter else row["text"],
            evidenceQuestionIds=[question_id(e["id"]) for e in row["evidence"]],
        ))
    if result.selfPreferenceDifferences and policy.difference_reading is None:
        raise PresentationUnavailable()
    differences = [
        Reading.model_validate(policy.difference_reading(row, index, answers))
        for index, row in enumerate(result.selfPreferenceDifferences)
    ]
    if result.typeReferences and policy.reference_evidence is None:
        raise PresentationUnavailable()
    modes = {"partner-preferences": "PREFERENCE_FIT", "self-similarity": "SELF_SIMILARITY"}
    recommendations = [TypeReference(
        typeCode=row["type"], score=row["score"], mode=modes[row["referenceMode"]],
        evidenceQuestionIds=policy.reference_evidence(row, answers),
    ) for row in result.typeReferences]
    return PersonalAnalysisResponse(
        status=result.status, surveyVersion=result.surveyVersion, analysisVersion=binding.analysis_version,
        scores=result.scores, typeStatus=result.typeStatus, primaryType=result.primaryType,
        tiedTypes=result.tiedTypes, readings=readings, selfPreferenceDifferences=differences,
        typeRecommendations=recommendations, missingQuestionIds=result.missingQuestionIds,
    )


@dataclass(frozen=True)
class PersonalAnalysisAdapter:
    version_bindings: tuple[VersionBinding, ...] = APPROVED_VERSION_BINDINGS
    presentation: PresentationPolicy = field(default_factory=PresentationPolicy)

    def analyze(self, payload: PersonalAnalysisRequest) -> PersonalAnalysisResponse:
        request = PersonalAnalysisRequest.model_validate(payload)
        binding = next((b for b in self.version_bindings if (
            b.survey_version, b.analysis_version,
        ) == (request.surveyVersion, request.analysisVersion)), None)
        if binding not in APPROVED_VERSION_BINDINGS:
            raise VersionUnsupported()
        verify_reference_binding()
        core_request = to_core_input(request, binding)
        result = analyze_personal(core_request)
        # Per-call context is built only after the unchanged service validated all answers.
        answers = MappingProxyType({
            QUESTION_IDS[a.questionId]: deepcopy(a.value) for a in core_request.answers
        })
        return to_be_response(result, binding, self.presentation, answers)
