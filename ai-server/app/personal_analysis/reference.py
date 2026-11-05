"""Pinned data extracted from the existing JS implementation, packaged for Docker."""

import hashlib
import json
from pathlib import Path

DATA_DIR = Path(__file__).with_name("data")
CALCULATION_VERSION = "local-personal-js100-port-1"


def load_reference() -> tuple[dict, dict, dict]:
    provenance = json.loads((DATA_DIR / "provenance.json").read_text(encoding="utf-8"))
    for name, expected in provenance["packaged"].items():
        if hashlib.sha256((DATA_DIR / name).read_bytes()).hexdigest() != expected:
            raise RuntimeError("Personal analysis reference integrity check failed")
    reference = json.loads((DATA_DIR / "reference.json").read_text(encoding="utf-8"))
    catalogue = json.loads(
        (DATA_DIR / "questionnaire-SIS-Q-1.1-100.json").read_text(encoding="utf-8")
    )
    return reference, catalogue, provenance


REFERENCE, CATALOGUE, PROVENANCE = load_reference()
MODEL = REFERENCE["model"]
QUESTIONS = {q["id"]: q for q in REFERENCE["questions"]}
QUESTION_IDS = {q["questionId"]: q["ordinal"] for q in CATALOGUE["questions"]}
LABELS = REFERENCE["featureLabels"]
DOMAINS = REFERENCE["domainDefs"]
