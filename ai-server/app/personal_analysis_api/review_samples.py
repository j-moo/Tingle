"""Generate deterministic synthetic review fixtures without HTTP/auth/runtime setup.

Run from ai-server: python -B -m app.personal_analysis_api.review_samples
These responses use an UNAPPROVED display proposal. They are not BE envelopes.
"""

import json
from copy import deepcopy
from hashlib import sha256
from pathlib import Path

from app.personal_analysis import core
from app.personal_analysis.reference import CATALOGUE

from .adapter import (
    APPROVED_DATA_HASHES, APPROVED_VERSION_BINDING, PersonalAnalysisAdapter,
    question_id,
)
from .dto import PersonalAnalysisRequest
from .proposals import PROPOSAL_REVIEW_VERSION, proposed_label_only_policy

FIXTURE_DIR = Path(__file__).resolve().parents[2] / "tests/fixtures/personal-analysis-api"


def synthetic_request(kind: str) -> dict:
    """Every value comes from the pinned catalogue or explicit synthetic overrides."""
    if kind not in ("succeeded", "insufficient"):
        raise ValueError("Unknown synthetic sample")
    answers = {q["ordinal"]: "unknown" for q in CATALOGUE["questions"]}
    for question in CATALOGUE["questions"][:60]:
        schema = question["responseSchema"]["oneOf"][0]
        answers[question["ordinal"]] = deepcopy(
            schema["enum"][0] if "enum" in schema else [schema["items"]["enum"][0]]
        )
    for q in range(61, 96):
        answers[q] = "any"
    answers.update({30: 4, 31: 5, 32: 2, 33: 3, 65: 1, 79: ["apologize"], 96: ["approach"], 98: "close"})
    if kind == "insufficient":
        keep = {1, 6, 11, 16, 30, 32, 44, 45, 65, 79, 96, 98}
        answers = {q: value if q in keep else "unknown" for q, value in answers.items()}
    return dict(
        analysisId=14601 if kind == "succeeded" else 14602,
        surveyVersion=APPROVED_VERSION_BINDING.survey_version,
        analysisVersion=APPROVED_VERSION_BINDING.analysis_version,
        answers=[dict(questionId=question_id(q), value=value) for q, value in answers.items()],
    )


def json_text(value) -> str:
    return json.dumps(value, ensure_ascii=False, allow_nan=False, indent=2) + "\n"


def build_review_samples() -> dict[str, dict]:
    adapter = PersonalAnalysisAdapter(presentation=proposed_label_only_policy())
    files = {}
    for kind, status in (("succeeded", "SUCCEEDED"), ("insufficient", "INSUFFICIENT")):
        request = synthetic_request(kind)
        response = adapter.analyze(PersonalAnalysisRequest.model_validate(request)).model_dump()
        if response["status"] != status:
            raise RuntimeError("Synthetic sample status changed")
        files[f"{kind}.request.json"] = request
        files[f"{kind}.response.proposal.json"] = response
    files["sample-manifest.json"] = dict(
        purpose="BE review only; synthetic inputs; no HTTP authentication or production activation",
        confirmed=dict(
            surveyVersion=APPROVED_VERSION_BINDING.survey_version,
            analysisVersion=APPROVED_VERSION_BINDING.analysis_version,
            calculationVersion=APPROVED_VERSION_BINDING.calculation_version,
            evidence="Participating score inputs plus explicitly consumed Q096/Q098 conditions (rule B)",
            insufficient="AI result fields preserved; public filtering belongs to BE",
        ),
        presentation=dict(
            approval="UNAPPROVED",
            policy=PROPOSAL_REVIEW_VERSION,
            templateIds="15 fixed reading IDs and PA-D-SELF-WISH-FIT remain candidates",
            gapText="Survey upper-bound wording for Q089; explicit irregular exclusion note for Q087; fit/order unchanged and fit not displayed",
            renderers="Verified readings in SUCCEEDED and INSUFFICIENT; formatter evidence mismatch uses existing 500 INTERNAL_ERROR; other presentation unavailability remains 503; never inferred-text fallback",
            priority="First gap 2, second gap 1; proposal, not a production severity score",
        ),
        source=dict(
            coreSha256=sha256(Path(core.__file__).read_bytes()).hexdigest(),
            packagedSha256=dict(APPROVED_DATA_HASHES),
        ),
        files={name: sha256(json_text(value).encode("utf-8")).hexdigest() for name, value in files.items()},
    )
    return files


def write_review_samples() -> None:
    FIXTURE_DIR.mkdir(parents=True, exist_ok=True)
    for name, value in build_review_samples().items():
        (FIXTURE_DIR / name).write_text(json_text(value), encoding="utf-8", newline="\n")
        print(FIXTURE_DIR / name)


if __name__ == "__main__":
    write_review_samples()
