"""Read-only local rules preflight. Integrity checks never grant policy approval.

No .env/credentials, provider, engine, network, registration or file writes.
The descriptor pin must be supplied independently by the release reviewer.
"""

import argparse
import hashlib
import importlib
import json
from pathlib import Path
import re
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))


def check(*, environment="local", enabled=False, descriptor_file="", descriptor_sha256=""):
    result = {
        "ready": False, "approval": "NOT_VERIFIED", "externalService": "NOT_VERIFIED",
        "activationAuthorized": False,
    }

    def report(code):
        return {**result, "code": code}

    if enabled is not True:
        return report("DISABLED")
    if environment not in {"local", "test", "dev"}:
        return report("ENVIRONMENT_BLOCKED")
    if not descriptor_file:
        return report("DESCRIPTOR_MISSING")
    if not descriptor_sha256:
        return report("PIN_MISSING")
    if re.fullmatch(r"[0-9a-f]{64}", descriptor_sha256) is None:
        return report("PIN_INVALID")

    from app.risk_detection.http_api import _read_bounded
    from app.risk_detection.releases import DevelopmentReleaseCatalog, DevelopmentReleaseError
    from app.risk_detection.tokenization import TOKENIZERS_VERSION

    try:
        raw = _read_bounded(Path(descriptor_file), 65536)
    except (OSError, ValueError):
        return report("DESCRIPTOR_UNREADABLE")
    if hashlib.sha256(raw).hexdigest() != descriptor_sha256:
        return report("PIN_MISMATCH")
    try:
        tokenizer = importlib.import_module("tokenizers")
        if tokenizer.__version__ != TOKENIZERS_VERSION:
            return report("TOKENIZER_VERSION_MISMATCH")
    except (ImportError, OSError, AttributeError):
        return report("TOKENIZER_UNAVAILABLE")
    try:
        DevelopmentReleaseCatalog(descriptor_file, descriptor_sha256=descriptor_sha256)
    except DevelopmentReleaseError:
        return report("RELEASE_BINDING_INVALID")
    # Only file/code/tokenizer consistency is established. No engine is started.
    return {**result, "ready": True, "code": "LOCAL_BINDINGS_VERIFIED"}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--environment", default="local")
    parser.add_argument("--enabled", action="store_true")
    parser.add_argument("--descriptor", default="")
    parser.add_argument("--descriptor-sha256", default="")
    args = parser.parse_args(argv)
    result = check(environment=args.environment, enabled=args.enabled,
                   descriptor_file=args.descriptor, descriptor_sha256=args.descriptor_sha256)
    print(json.dumps(result, sort_keys=True))
    return 0 if result["ready"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
