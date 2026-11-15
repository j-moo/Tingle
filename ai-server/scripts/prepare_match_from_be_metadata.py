"""BE metadata preimage 재구성. 승인/서버 등록 없이 새 디렉터리에만 쓴다.

2026-11-13 인계 도구의 알고리즘을 재사용한다. Safety 후보 생성 의존성을
제거하고 BE RuleArtifact의 최초 revision=0 계약을 반영했다.
입력: {manifest: <정확한 11필드>, engineVersion, artifacts:
       [{kind,id,revision,contentHash}, ...]}.
실제 export의 출처·승인 여부는 이 도구로 입증할 수 없다.
"""

import argparse
import hashlib
import json
from pathlib import Path
import re
import sys
from uuid import UUID

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from app.match_explanation.releases import ReleaseRegistry, _manifest, _object

# RuleKind의 선언 순서. 문자열 알파벳 정렬과 다르다.
KINDS = ("QUESTIONNAIRE", "WORDING", "SCORING", "TYPE_MODEL", "ANALYSIS",
         "TYPE_REFERENCE", "MATCHING", "PRODUCT_POLICY", "MIGRATION")


def encoded(value):
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2) + "\n").encode("utf-8")


def be_preimage(engine, members):
    if (type(engine) is not str or not engine.strip() or len(engine) > 100
            or "\n" in engine or "\r" in engine):
        raise ValueError("Invalid engine version")
    if type(members) is not list or not 1 <= len(members) <= len(KINDS):
        raise ValueError("Invalid artifact list")
    seen_kinds, seen_ids = set(), set()
    rows = []
    for item in members:
        if type(item) is not dict or set(item) != {"kind", "id", "revision", "contentHash"}:
            raise ValueError("Invalid artifact metadata fields")
        kind = item["kind"]
        if type(kind) is not str or kind not in KINDS or kind in seen_kinds:
            raise ValueError("Unknown or duplicate artifact kind")
        identifier = str(UUID(item["id"]))
        if identifier != item["id"] or identifier in seen_ids:
            raise ValueError("Noncanonical or duplicate artifact UUID")
        # RuleArtifact.change(): -1에서 증가하므로 첫 등록은 0이다.
        if type(item["revision"]) is not int or not 0 <= item["revision"] <= 2**31 - 1:
            raise ValueError("Invalid revision")
        if type(item["contentHash"]) is not str or re.fullmatch(r"[0-9a-f]{64}", item["contentHash"]) is None:
            raise ValueError("Invalid artifact hash")
        seen_kinds.add(kind)
        seen_ids.add(identifier)
        rows.append((KINDS.index(kind), f'{kind}:{identifier}:{item["revision"]}:{item["contentHash"]}'))
    # ReleaseInput: QUESTIONNAIRE 포함 또는 MATCHING+PRODUCT_POLICY 두 종류만 허용.
    if "QUESTIONNAIRE" not in seen_kinds and seen_kinds != {"MATCHING", "PRODUCT_POLICY"}:
        raise ValueError("Unsupported BE release composition")
    return "\n".join([engine, *(row for _, row in sorted(rows))]).encode("utf-8")


def prepare(export, output):
    if type(export) is not dict or set(export) != {"manifest", "engineVersion", "artifacts"}:
        raise ValueError("Invalid metadata export fields")
    manifest = _manifest(export["manifest"])
    if str(UUID(manifest["releaseId"])) != manifest["releaseId"]:
        raise ValueError("Noncanonical release UUID")
    if manifest["engineVersion"] != export["engineVersion"]:
        raise ValueError("Engine version mismatch")
    raw = be_preimage(export["engineVersion"], export["artifacts"])
    if hashlib.sha256(raw).hexdigest() != manifest["contentHash"]:
        raise ValueError("BE manifest does not bind the exact metadata preimage")
    output = Path(output)
    output.mkdir(parents=True, exist_ok=False)
    files = {
        "be-hash-preimage.artifact.txt": raw,
        "manifest.json": encoded(manifest),
        "ai-descriptor.json": encoded({"manifest": manifest, "artifact": "be-hash-preimage.artifact.txt"}),
        "candidate-status.json": encoded({
            "approval": "NOT_VERIFIED", "exportAuthority": "NOT_VERIFIED_BY_THIS_TOOL",
            "serverRegistration": "NOT_ATTEMPTED", "activationAuthorized": False,
            "metadataIntegrity": "MATCHED_SUPPLIED_MANIFEST",
            "warning": "Hash consistency is not approval or export authenticity.",
        }),
    }
    for name, value in files.items():
        with (output / name).open("xb") as stream:
            stream.write(value)
    ReleaseRegistry([output / "ai-descriptor.json"]).verify(manifest)
    return manifest


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("metadata", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    prepare(json.loads(args.metadata.read_bytes(), object_pairs_hook=_object), args.output)
    print("Metadata consistency PASS; approval/actual-server registration NOT_VERIFIED.")
