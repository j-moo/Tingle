"""Validate complete snapshots before computation; never sanitize bad answers away."""

from .dto import PersonalAnalysisInput
from .reference import CALCULATION_VERSION, CATALOGUE, QUESTION_IDS


class AnalysisInputError(ValueError):
    def __init__(self, code: str, question_ids: tuple[str, ...] = ()) -> None:
        self.code = code
        self.question_ids = question_ids
        # Do not put submitted values or metadata in error messages.
        super().__init__(code)


def _same_value(actual, expected) -> bool:
    if isinstance(expected, str):
        return type(actual) is str and actual == expected
    # Equality with the finite catalogue options also rejects NaN/Infinity and
    # avoids converting arbitrarily large invalid integers to a Python float.
    return type(actual) in (int, float) and actual == expected


def valid_answer(question: dict, value) -> bool:
    if value == "unknown":
        return question["allowUnknown"]
    if value == "any":
        return question["allowAny"]
    alternatives = question["responseSchema"]["oneOf"]
    for schema in alternatives:
        if "enum" in schema and any(_same_value(value, option) for option in schema["enum"]):
            return True
        if schema.get("type") != "array" or not isinstance(value, list):
            continue
        allowed = schema["items"]["enum"]
        if not schema["minItems"] <= len(value) <= schema["maxItems"]:
            continue
        if not all(any(_same_value(v, option) for option in allowed) for v in value):
            continue
        if any(value[i] == value[j] for i in range(len(value)) for j in range(i)):
            continue
        return True
    return False


def engine_answers(request: PersonalAnalysisInput) -> dict[int, object]:
    if request.surveyVersion != CATALOGUE["surveyVersion"]:
        raise AnalysisInputError("UNSUPPORTED_SURVEY_VERSION")
    if request.calculationVersion != CALCULATION_VERSION:
        raise AnalysisInputError("UNSUPPORTED_CALCULATION_VERSION")
    seen = set()
    for answer in request.answers:
        if answer.questionId not in QUESTION_IDS:
            raise AnalysisInputError("INVALID_QUESTION_ID")
        if answer.questionId in seen:
            raise AnalysisInputError("DUPLICATE_QUESTION_ID", (answer.questionId,))
        seen.add(answer.questionId)
    missing = tuple(qid for qid in QUESTION_IDS if qid not in seen)
    if missing:
        raise AnalysisInputError("MISSING_ANSWERS", missing)
    by_id = {q["questionId"]: q for q in CATALOGUE["questions"]}
    invalid = tuple(a.questionId for a in request.answers if not valid_answer(by_id[a.questionId], a.value))
    if invalid:
        raise AnalysisInputError("INVALID_ANSWER", invalid)
    # Use the pinned catalogue's mapping, independently of input array order.
    return {QUESTION_IDS[a.questionId]: a.value for a in request.answers}
