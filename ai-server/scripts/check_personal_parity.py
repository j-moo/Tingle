"""Re-run the original JS oracle and summarize full Python parity numerically."""

import json
import subprocess
from pathlib import Path

from app.personal_analysis import core


def main():
    oracle = Path(__file__).resolve().parents[1] / "tests/js_personal_reference.mjs"
    run = subprocess.run(["node", str(oracle)], capture_output=True, encoding="utf-8", check=True, timeout=60)
    reference = json.loads(run.stdout)
    numeric_comparisons = 0
    max_error = 0.0

    def compare(actual, expected):
        nonlocal numeric_comparisons, max_error
        if isinstance(expected, dict):
            assert actual.keys() == expected.keys()
            for key in expected:
                compare(actual[key], expected[key])
        elif isinstance(expected, list):
            assert len(actual) == len(expected)
            for left, right in zip(actual, expected):
                compare(left, right)
        elif type(expected) in (int, float):
            numeric_comparisons += 1
            error = abs(actual - expected)
            max_error = max(max_error, error)
            assert error <= 1e-12
        else:
            assert actual == expected

    for case in reference["cases"]:
        a = {int(k): v for k, v in case["answers"].items()}
        compare(dict(profile=core.profile_from_answers(a), classification=core.classify_answers(a),
                     readings=core.readings(a), gaps=core.preference_gaps(a), references=core.type_recommendations(a)), case["result"])
    for case in reference["profiles"]:
        compare(core.classify_profile(case["features"]), case["result"])
    for case in reference["wishCases"]:
        compare(core.wish_fit(case["id"], case["wish"], case["actual"], case["tolerance"]), case["result"])
    print(json.dumps(dict(status="PASS", fullAnswerCases=len(reference["cases"]),
                         profileBoundaryCases=len(reference["profiles"]), wishFitCases=len(reference["wishCases"]),
                         numericComparisons=numeric_comparisons, maxAbsoluteError=max_error,
                         absoluteTolerance=1e-12, relativeTolerance=0)))


if __name__ == "__main__":
    main()
