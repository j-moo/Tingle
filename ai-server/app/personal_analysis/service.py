"""Validated, deterministic service entry point, independent of FastAPI and DB."""

from pydantic import ValidationError

from . import core
from .dto import PersonalAnalysisInput, PersonalAnalysisResult
from .reference import CALCULATION_VERSION, CATALOGUE
from .validation import AnalysisInputError, engine_answers


class CalculationFailure(RuntimeError):
    code = "CALCULATION_FAILED"

    def __init__(self) -> None:
        super().__init__(self.code)


def analyze_personal(payload: dict | PersonalAnalysisInput) -> PersonalAnalysisResult:
    """Compute a complete snapshot. Exceptions never echo submitted values.

    Metadata is validated but not used in personal analysis. Completion, ownership,
    revision, idempotency and result persistence remain responsibilities of BE.
    """
    try:
        # Revalidate nested DTOs too; dumping invalid instances can leak values in warnings.
        request = PersonalAnalysisInput.model_validate(payload)
    except ValidationError:
        raise AnalysisInputError("INVALID_REQUEST") from None
    answers = engine_answers(request)
    try:
        classification = core.classify_answers(answers)
        references = core.type_recommendations(answers)
        type_status = classification["status"].upper()
        primary = classification["primary"]
        return PersonalAnalysisResult(
            surveyVersion=CATALOGUE["surveyVersion"], calculationVersion=CALCULATION_VERSION,
            status="INSUFFICIENT" if type_status == "INSUFFICIENT" else "SUCCEEDED",
            scores={key: classification["features"][key] for key in core.SCALES},
            typeStatus=type_status, primaryType=primary["type"] if primary else None,
            tiedTypes=[p["type"] for p in classification["tied"]] if type_status == "TIED" else [],
            classification=classification, readings=core.readings(answers),
            selfPreferenceDifferences=core.preference_gaps(answers),
            referenceMode="PREFERENCE_FIT" if references["mode"] == "partner-preferences" else "SELF_SIMILARITY",
            typeReferenceVersion=references["version"], typeReferences=references["selected"],
            missingQuestionIds=[f"Q{i:03}" for i in range(1, 61) if answers[i] == "unknown"],
        )
    except Exception:
        # A computation defect is never returned as a successful insufficient result.
        raise CalculationFailure() from None
