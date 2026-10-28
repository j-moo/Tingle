"""Validate ERD21 owned API documents without executing servers or databases.

Run with Python plus jsonschema/openapi-spec-validator installed. Optional
--dependencies points to an existing package directory; no downloads are made.
The machine report is owned-api-erd-20261028.json beside this script;
the Markdown summary is maintained separately.
"""

from __future__ import annotations

import argparse
import copy
import datetime
import hashlib
import json
import pathlib
import re
import subprocess
import sys
import warnings

ROOT = pathlib.Path(__file__).resolve().parents[2]
HTTP = {"get", "put", "post", "patch", "delete", "head", "options", "trace"}
EXCLUDED = {"setSurveyMetadata", "getJob", "createReport", "cancelProposalAcceptance"}
CORRECTIONS = {"getPreferences", "setPreferences", "getSettings", "setSettings"}
DOCS = [
    "docs/contracts/api/README.md",
    "docs/contracts/api/encounters-proposals.md",
    "docs/contracts/api/ai-match-explanation.md",
    "docs/contracts/api/survey-sessions.md",
    "docs/contracts/survey/versioning.md",
    "docs/contracts/api/chat.md",
    "docs/contracts/api/chat-notices.md",
]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dependencies", type=pathlib.Path)
    parser.add_argument("--baseline", default="HEAD", help="Git revision before OpenAPI integration")
    args = parser.parse_args()
    if args.dependencies:
        sys.path.insert(0, str(args.dependencies.resolve()))
    from jsonschema import Draft202012Validator, FormatChecker, RefResolver
    from openapi_spec_validator import validate

    spec_path = ROOT / "docs/contracts/api/openapi.json"
    spec = json.loads(spec_path.read_text(encoding="utf-8"))
    baseline_sha = subprocess.check_output(
        ["git", "rev-parse", args.baseline], cwd=ROOT, text=True
    ).strip()
    baseline = json.loads(subprocess.check_output(
        ["git", "show", f"{baseline_sha}:docs/contracts/api/openapi.json"], cwd=ROOT
    ).decode("utf-8"))

    def operations(doc):
        return {
            op["operationId"]: (path, method, op)
            for path, item in doc["paths"].items()
            for method, op in item.items()
            if method in HTTP
        }

    active = operations(spec)
    before = operations(baseline)
    owned = set(spec["x-erd21-owned-alignment"]["ownedOperationIds"])
    assert len(owned) == 40
    assert len(active) == 67 and len(before) == 71
    assert len(spec["x-excluded-operations"]) == 15
    assert len(set(active) & owned) == 36
    assert not EXCLUDED & set(active)
    assert len(active) == sum(method in HTTP for item in spec["paths"].values() for method in item)
    normalized_paths = {}
    for path, item in spec["paths"].items():
        normalized = re.sub(r"\{[^{}]+\}", "{}", path)
        for method in item:
            if method in HTTP:
                assert (normalized, method) not in normalized_paths
                normalized_paths[(normalized, method)] = path
    validate(spec)

    refs = 0

    def pointer(ref):
        assert ref.startswith("#/"), f"Unexpected external reference: {ref}"
        node = spec
        for part in ref[2:].split("/"):
            node = node[part.replace("~1", "/").replace("~0", "~")]
        return node

    def walk(node):
        nonlocal refs
        if isinstance(node, dict):
            for key, value in node.items():
                if key == "$ref":
                    pointer(value)
                    refs += 1
                else:
                    walk(value)
        elif isinstance(node, list):
            for value in node:
                walk(value)

    walk(spec)
    resolver = RefResolver.from_schema(spec)
    formats = FormatChecker()
    examples = 0

    def check(schema, example, label):
        nonlocal examples
        errors = list(Draft202012Validator(schema, resolver=resolver, format_checker=formats).iter_errors(example))
        assert not errors, f"{label}: {errors[0].message if errors else ''}"
        examples += 1

    def check_schema_tree(schema, label):
        if not isinstance(schema, dict):
            return
        for index, example in enumerate(schema.get("examples", [])):
            check(schema, example, f"{label}/examples/{index}")
        if "example" in schema:
            check(schema, schema["example"], f"{label}/example")
        for key, child in schema.items():
            if key in {"example", "examples", "default", "enum", "const"}:
                continue
            if isinstance(child, dict):
                if key in {"properties", "$defs", "patternProperties", "dependentSchemas"}:
                    for name, subschema in child.items():
                        check_schema_tree(subschema, f"{label}/{key}/{name}")
                else:
                    check_schema_tree(child, f"{label}/{key}")
            elif isinstance(child, list):
                for index, subschema in enumerate(child):
                    check_schema_tree(subschema, f"{label}/{key}/{index}")

    for name, schema in spec["components"]["schemas"].items():
        Draft202012Validator.check_schema(schema)
        check_schema_tree(schema, f"schema/{name}")

    def media_examples(media, label):
        for mime, value in media.items():
            schema = value.get("schema")
            if not schema:
                continue
            if "example" in value:
                check(schema, value["example"], f"{label}/{mime}/example")
            for name, example in value.get("examples", {}).items():
                example = pointer(example["$ref"]) if "$ref" in example else example
                if "value" in example:
                    check(schema, example["value"], f"{label}/{mime}/{name}")

    path_parameters = 0
    for oid, (path, method, op) in active.items():
        params = spec["paths"][path].get("parameters", []) + op.get("parameters", [])
        params = [pointer(p["$ref"]) if "$ref" in p else p for p in params]
        expected = set(re.findall(r"\{([^{}]+)\}", path))
        actual = {p["name"] for p in params if p["in"] == "path"}
        assert actual == expected, (oid, expected, actual)
        assert all(p.get("required") is True for p in params if p["in"] == "path")
        path_parameters += len(expected)
        for param in params:
            if "example" in param and "schema" in param:
                check(param["schema"], param["example"], oid + "/parameter")
        media_examples(op.get("requestBody", {}).get("content", {}), oid + "/request")
        for status, response in op["responses"].items():
            media_examples(response.get("content", {}), oid + "/response/" + status)
        if oid in owned:
            assert "/v1/" not in path and "/me/" not in path
            assert op["x-contract-status"] == "REVIEW_REQUIRED"
            assert op.get("x-auth-role") == before[oid][2].get("x-auth-role"), oid
            assert op.get("security") == before[oid][2].get("security"), oid
            assert op.get("x-change-history") == before[oid][2].get("x-change-history"), oid
            # Preserve every established permission/deadline HTTP status.
            for status in ["401", "403", "404", "410"]:
                assert status not in before[oid][2]["responses"] or status in op["responses"], (oid, status)
                if status in before[oid][2]["responses"]:
                    previous_codes = set(before[oid][2]["responses"][status].get("x-error-codes", []))
                    current_codes = set(op["responses"][status].get("x-error-codes", []))
                    assert previous_codes <= current_codes, (oid, status, previous_codes - current_codes)
            for status, response in op["responses"].items():
                if not status.isdigit() or int(status) < 400:
                    continue
                example = response.get("content", {}).get("application/json", {}).get("example")
                if example and response.get("x-error-codes"):
                    assert example["code"] in response["x-error-codes"], (oid, status)
                    assert example.get("fields", []) == [], (oid, status)

    for entry in spec["x-excluded-operations"]:
        op = entry["operation"]
        media_examples(op.get("requestBody", {}).get("content", {}), "excluded/" + op["operationId"])
        for status, response in op["responses"].items():
            media_examples(response.get("content", {}), "excluded/" + op["operationId"] + "/" + status)
    assert spec["security"] == baseline["security"]
    assert spec["components"]["securitySchemes"] == baseline["components"]["securitySchemes"]
    assert spec["components"]["schemas"]["Manifest"] == baseline["components"]["schemas"]["Manifest"]
    assert len(spec["components"]["schemas"]["Manifest"]["required"]) == 11
    assert spec["x-excluded-operations"][:11] == baseline["x-excluded-operations"]
    for entry in spec["x-excluded-operations"][11:]:
        assert entry["operation"]["operationId"] in EXCLUDED
        assert entry["operation"]["x-contract-status"] == "EXCLUDED"

    def schema_names(node):
        if isinstance(node, dict):
            for key, value in node.items():
                if key == "$ref" and value.startswith("#/components/schemas/"):
                    yield value.split("/")[-1]
                else:
                    yield from schema_names(value)
        elif isinstance(node, list):
            for item in node:
                yield from schema_names(item)

    protected = set()

    def protect(op):
        queue = list(schema_names(op))
        while queue:
            name = queue.pop()
            if name in protected:
                continue
            protected.add(name)
            queue.extend(schema_names(baseline["components"]["schemas"][name]))

    for oid, (path, method, op) in before.items():
        if oid in owned:
            continue
        now_path, now_method, current = active[oid]
        assert method == now_method
        if oid not in CORRECTIONS:
            assert (path, op) == (now_path, current), oid
        else:
            assert now_path == path.replace("/api/users/", "/api/users/{userId}/")
            before_copy, after_copy = copy.deepcopy(op), copy.deepcopy(current)
            for copy_op in [before_copy, after_copy]:
                copy_op.pop("parameters", None)
                copy_op["responses"].pop("403", None)
            assert before_copy == after_copy, oid
            params = current["parameters"]
            user_param = next(p for p in params if p.get("name") == "userId")
            assert user_param["schema"]["format"] == "int64" and user_param["required"]
            assert "FORBIDDEN" in current["responses"]["403"]["x-error-codes"]
        protect(op)
    for entry in baseline["x-excluded-operations"]:
        protect(entry["operation"])
    for name in protected:
        assert spec["components"]["schemas"][name] == baseline["components"]["schemas"][name], name

    def get_schema(oid, status=None):
        op = active[oid][2]
        media = op["requestBody"]["content"]["application/json"] if status is None else op["responses"][str(status)]["content"]["application/json"]
        return pointer(media["schema"]["$ref"]) if "$ref" in media["schema"] else media["schema"]

    negative = 0
    for schema, example in [
        (get_schema("getProposal", 200), {**get_schema("getProposal", 200)["examples"][0], "chatRoomId": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"}),
        (get_schema("generateExplanation"), {**get_schema("generateExplanation")["examples"][0], "contextId": "uuid"}),
        (get_schema("generateExplanation"), {**get_schema("generateExplanation")["examples"][0], "jobId": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"}),
        (get_schema("getExplanation", 200), {"status": "PENDING", "source": "GENERATED", "text": "unverified", "evidenceKeys": [], "promptVersion": None}),
        (get_schema("saveSurveyAnswer"), {"value": [1, 2]}),
    ]:
        assert list(Draft202012Validator(schema, resolver=resolver, format_checker=formats).iter_errors(example))
        negative += 1
    decline = active["declineProposal"][2]["responses"]["200"]["content"]["application/json"]["example"]
    assert (decline["status"], decline["myDecision"], decline["chatAvailable"], decline["chatRoomId"]) == ("REJECTED", "DECLINE", False, None)
    for oid in ["getProposal", "acceptProposal", "declineProposal"]:
        example = active[oid][2]["responses"]["200"]["content"]["application/json"]["example"]
        date = datetime.datetime.fromisoformat
        assert date(example["expiresAt"]) - date(example["createdAt"]) == datetime.timedelta(hours=24), oid
    receipt = get_schema("syncEncounters", 200)["examples"][0]["results"][0]
    assert (receipt["receiptStatus"], receipt["receiptId"], receipt["canDeleteLocalRecord"], receipt["errorCode"]) == ("NOT_STORED", None, False, "POLICY_UNRESOLVED")
    for key in ["getRiskConsent", "setRiskConsent"]:
        assert active[key][2]["x-implementation-gate"] == "BLOCKED_UNTIL_STORAGE_AND_POLICY_APPROVAL"
    assert active["evaluatePair"][2]["x-implementation-gate"] == "BLOCKED_UNTIL_INPUT_EVIDENCE_AND_FILE_MAPPING_APPROVAL"

    links = 0
    table_rows = 0
    for name in DOCS:
        file = ROOT / name
        text = file.read_text(encoding="utf-8")
        for target in re.findall(r"\]\(([^)]+)\)", text):
            if target.startswith(("http:", "https:", "#", "mailto:")):
                continue
            path = target.split("#")[0].split(" ")[0].strip("<>")
            if path:
                assert (file.parent / path).resolve().exists(), (name, target)
                links += 1
        expected = None
        for line in text.splitlines():
            if line.startswith("|"):
                cells = len(re.split(r"(?<!\\)\|", line)) - 2
                if expected is None:
                    expected = cells
                assert cells == expected, (name, line)
                table_rows += 1
            else:
                expected = None
    ai_path = "docs/contracts/api/ai-match-explanation.md"
    ai_before = subprocess.check_output(["git", "show", f"{baseline_sha}:{ai_path}"], cwd=ROOT).decode("utf-8")
    ai_after = (ROOT / ai_path).read_text(encoding="utf-8")
    experimental = lambda text: text[text.index("## 4."):text.index("## 8.")]
    assert experimental(ai_before) == experimental(ai_after)
    erd = json.loads((ROOT / "docs/verification/erd21-alignment-20261028.json").read_text(encoding="utf-8"))
    assert (erd["tables"], erd["columns"], erd["logical_relations"]) == (21, 151, 35)

    result = {
        "date": "2026-10-28", "baselineCommit": baseline_sha,
        "openapiVersion": spec["openapi"], "openapiSchemaValidation": "PASS",
        "localRefsResolved": refs, "schemaAndMediaExamplesValidated": examples,
        "negativeExamplesRejected": negative, "pathParameterOccurrences": path_parameters,
        "duplicateActiveOperationIds": 0, "normalizedPathCollisions": 0,
        "activeOperations": len(active), "ownedOperations": len(owned),
        "activeOwnedOperations": 36, "newExcludedOperations": sorted(EXCLUDED),
        "totalExcludedOperations": 15, "otherOwnerOperationsPreserved": 27,
        "otherOwnerAuthorizedPathCorrections": sorted(CORRECTIONS),
        "protectedSharedSchemaDefinitionsUnchanged": len(protected),
        "manifest11FieldsUnchanged": True, "originalExcludedSnapshotsUnchanged": 11,
        "ownedAuthenticationPermissionDeadlineStatusesPreserved": True,
        "ownedChangeHistoryPreserved": True, "aiExperimentalSections4to7Unchanged": True,
        "localMarkdownLinksChecked": links, "markdownTableRowsChecked": table_rows,
        "erdDesign": {"tables": 21, "columns": 151, "logicalRelations": 35},
        "openapiSha256": hashlib.sha256(spec_path.read_bytes()).hexdigest(),
        "notionSaveReadback": "Separate browser evidence; this script does not verify remote storage",
        "serverBuildTests": "NOT_RUN_DOCUMENTATION_ONLY", "ci": "NOT_RUN",
        "databaseExecution": False, "deployment": False,
    }
    out = ROOT / "docs/verification/owned-api-erd-20261028.json"
    out.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    warnings.filterwarnings("ignore", category=DeprecationWarning)
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    main()
