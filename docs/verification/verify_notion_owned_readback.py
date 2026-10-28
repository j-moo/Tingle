"""Validate browser-read Notion examples against the saved OpenAPI document."""
import argparse
import hashlib
import json
import pathlib
import re
import sys

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--readback", type=pathlib.Path, required=True)
parser.add_argument("--dependencies", type=pathlib.Path, required=True)
args = parser.parse_args()
sys.path.insert(0, str(args.dependencies.resolve()))
from jsonschema import Draft202012Validator, FormatChecker, RefResolver

root = pathlib.Path(__file__).resolve().parents[2]
spec = json.loads((root / "docs/contracts/api/openapi.json").read_text(encoding="utf-8"))
raw = args.readback.read_bytes()
pages = json.loads(raw)
ops = []
for path, item in spec["paths"].items():
    for method, op in item.items():
        if isinstance(op, dict) and "operationId" in op:
            ops.append((path, method, op, False))
for item in spec["x-excluded-operations"]:
    ops.append((item["path"], item["method"].lower(), item["operation"], True))
resolver = RefResolver.from_schema(spec)
checks = []
errors = []
for name, page in pages.items():
    if name == "index":
        continue
    page_id = re.findall(r"[0-9a-f]{32}", page["url"])[-1]
    candidates = [x for x in ops if page_id in x[2].get("x-notion-page", "")]
    if not candidates and name == "cancel":
        candidates = [x for x in ops if x[2]["operationId"] == "cancelProposalAcceptance"]
    assert len(candidates) == 1, (name, len(candidates))
    path, method, op, excluded = candidates[0]
    examples = [json.loads(n["text"]) for n in page["nodes"]
                if n["role"] == "코드 블록" and n["text"].strip().startswith("{")]
    schemas = []
    body = op.get("requestBody", {}).get("content", {}).get("application/json")
    if body:
        schemas.append(("request", body["schema"]))
    responses = [v["content"]["application/json"]["schema"]
                 for status, v in op["responses"].items()
                 if status.startswith("2") and "application/json" in v.get("content", {})]
    if responses:
        schemas.append(("response", responses[0]))
    assert len(examples) == len(schemas), (name, len(examples), len(schemas))
    for sample, (kind, schema) in zip(examples, schemas):
        found = list(Draft202012Validator(schema, resolver=resolver,
                                         format_checker=FormatChecker()).iter_errors(sample))
        errors.extend({"page": name, "kind": kind, "error": e.message} for e in found)
    checks.append({"page": name, "notionPage": page["url"], "operationId": op["operationId"],
                   "path": path, "method": method.upper(), "excluded": excluded,
                   "jsonExamples": len(examples), "source": "internal browser reload readback"})
assert len(checks) == 16, len(checks)
report = {"date": "2026-10-28", "status": "PASS" if not errors else "FAIL",
          "rootPages": checks, "rootJsonExamples": sum(x["jsonExamples"] for x in checks),
          "errors": errors, "rawBrowserReadbackSha256": hashlib.sha256(raw).hexdigest(),
          "otherPageEvidence": {"surveySix": "notion-survey-six-20261028.json",
                                "surveyRemainingSeven": "notion-survey-rest-20261028.json",
                                "chatEleven": "notion-chat-owned-20261028.json"},
          "totalOwnedPages": 40, "apiIndexGuideReloaded": True,
          "boundaries": ["Documentation only", "No server, DB, deployment, device or policy approval proof"]}
(root / "docs/verification/notion-owned-api-final-20261028.json").write_text(
    json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps({"status": report["status"], "pages": len(checks),
                  "examples": report["rootJsonExamples"], "errors": errors}, ensure_ascii=False))
if errors:
    raise SystemExit(1)
