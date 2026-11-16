"""Validate supplied Match files locally; never register with a running server.

The separately supplied manifest must come from the reviewed BE export.
Hash agreement alone cannot establish export authenticity or release approval.
"""

import argparse
import json
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.match_explanation.releases import ReleaseRegistry, _manifest, _object


def check(descriptor, manifest_file):
    result = {
        "approval": "NOT_VERIFIED", "externalService": "NOT_VERIFIED",
        "serverRegistration": "NOT_ATTEMPTED", "activationAuthorized": False,
    }
    try:
        raw = Path(manifest_file).read_bytes()
        try:
            parsed = json.loads(raw, object_pairs_hook=_object)
        except RecursionError:
            return {**result, "fileIntegrity": "FAIL"}
        manifest = _manifest(parsed)
        ReleaseRegistry([descriptor]).verify(manifest)
    except (OSError, ValueError, TypeError):
        return {**result, "fileIntegrity": "FAIL"}
    return {**result, "fileIntegrity": "PASS"}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--descriptor", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    args = parser.parse_args(argv)
    result = check(args.descriptor, args.manifest)
    print(json.dumps(result, sort_keys=True))
    return 0 if result["fileIntegrity"] == "PASS" else 2


if __name__ == "__main__":
    raise SystemExit(main())
