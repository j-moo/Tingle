import json
import shutil
import subprocess
from pathlib import Path

import pytest

from app.personal_analysis.reference import CALCULATION_VERSION


@pytest.fixture(scope="session")
def js_reference():
    node = shutil.which("node")
    if node is None:
        pytest.fail("Node.js is required for the independent JS/Python parity check")
    completed = subprocess.run(
        [node, str(Path(__file__).with_name("js_personal_reference.mjs"))],
        capture_output=True, encoding="utf-8", timeout=60, check=True,
    )
    return json.loads(completed.stdout)


def request_for(answers):
    return {
        "surveyVersion": "SIS-Q-1.1-100",
        "calculationVersion": CALCULATION_VERSION,
        "answers": [{"questionId": f"Q{int(i):03}", "value": v} for i, v in answers.items()],
        "metadata": {"ownAge": None, "desiredAgeMin": None, "desiredAgeMax": None},
    }


@pytest.fixture
def normal_request(js_reference):
    normal = next(c for c in js_reference["cases"] if c["name"] == "normal")
    return request_for(normal["answers"])
