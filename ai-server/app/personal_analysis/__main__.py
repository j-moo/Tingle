"""Local file runner: python -m app.personal_analysis path/to/input.json."""

import argparse
import json
import sys
from pathlib import Path

from .service import CalculationFailure, analyze_personal
from .validation import AnalysisInputError

#JSON 파일을 받는 CLI
def main() -> int:
    # Keep redirected JSON portable on Windows as well as Linux containers.
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path)
    args = parser.parse_args()
    try:
        payload = json.loads(args.input.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, ValueError):
        print('{"code":"INVALID_INPUT_FILE"}', file=sys.stderr)
        return 2
    try:
        result = analyze_personal(payload)
    except (AnalysisInputError, CalculationFailure) as error:
        print(json.dumps({"code": error.code}), file=sys.stderr)
        return 2 if isinstance(error, AnalysisInputError) else 3
    print(result.model_dump_json(indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
