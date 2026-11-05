"""Reproduce canonical UTF-8 byte budgets; never writes reference data/fixtures.

Run from ai-server: python -m scripts.measure_personal_body
JSON whitespace, escapes and equivalent numeric spellings have no finite maximum.
This enumerates the largest *canonical semantic* request for the approved binding.
"""

import json
from pathlib import Path

from app.personal_analysis.reference import CATALOGUE
from app.personal_analysis.validation import engine_answers
from app.personal_analysis_api.adapter import APPROVED_VERSION_BINDING, to_core_input
from app.personal_analysis_api.dto import PersonalAnalysisRequest


def json_bytes(value, *, pretty=False):
    if pretty:
        text = json.dumps(value, ensure_ascii=False, allow_nan=False, indent=2) + "\n"
    else:
        text = json.dumps(value, ensure_ascii=False, allow_nan=False, separators=(",", ":"))
    return text.encode("utf-8")


def largest_canonical_request():
    answers = []
    for question in CATALOGUE["questions"]:
        candidates = []
        if question["allowUnknown"]:
            candidates.append("unknown")
        if question["allowAny"]:
            candidates.append("any")
        for schema in question["responseSchema"]["oneOf"]:
            if "enum" in schema:
                candidates.extend(schema["enum"])
            elif schema.get("type") == "array":
                # Unique options: choose the longest encoded values up to maxItems.
                options = sorted(schema["items"]["enum"], key=lambda v: len(json_bytes(v)), reverse=True)
                candidates.append(options[:schema["maxItems"]])
        answers.append({
            "questionId": question["questionId"],
            "value": max(candidates, key=lambda v: len(json_bytes(v))),
        })
    payload = dict(
        analysisId=2**63 - 1,
        surveyVersion=APPROVED_VERSION_BINDING.survey_version,
        analysisVersion=APPROVED_VERSION_BINDING.analysis_version,
        answers=answers,
    )
    # Validate the DTO, approved version mapping and all 100 catalogue answers.
    engine_answers(to_core_input(PersonalAnalysisRequest.model_validate(payload), APPROVED_VERSION_BINDING))
    return payload


def measure():
    fixtures = Path(__file__).resolve().parents[1] / "tests/fixtures/personal-analysis-api"
    payloads = {
        name: json.loads((fixtures / f"{name}.request.json").read_text(encoding="utf-8"))
        for name in ("succeeded", "insufficient")
    }
    payloads["largest_canonical"] = largest_canonical_request()
    return {
        name: {"compact_bytes": len(json_bytes(payload)), "pretty_bytes": len(json_bytes(payload, pretty=True))}
        for name, payload in payloads.items()
    }


if __name__ == "__main__":
    print(json.dumps(measure(), indent=2))
