"""실제 승인 export가 아닌 합성 BE 계약/불변 파일 검사."""

import copy
import hashlib
import json
from pathlib import Path
import sys

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
from prepare_match_from_be_metadata import be_preimage, prepare
from app.match_explanation.releases import ReleaseError, ReleaseRegistry

# 파트별 테스트는 다른 파트의 fixture를 읽지 않는다. BE와 같은 합성 계약의
# 독립 기대값을 이 테스트에 고정하고, 각 파트가 동일한 SHA를 검사한다.
EXPECTED_PREIMAGE = (
    b"tingle-rules-1\n"
    b"MATCHING:8b3d4dd0-75d1-5429-b686-27a6a0470500:0:"
    b"d5d5a0ade9f607d9834d213613b5557e2c28a2e6aeccf289d5796f8f007ce390\n"
    b"PRODUCT_POLICY:6999125c-bb2e-5915-bf07-86ca691d99b2:0:"
    b"9bcfb91fa3582e50a7eb28ab6ca28c9a488b86be859cb2d51fb4d2a857455d5f"
)


def metadata():
    return {
        "engineVersion": "tingle-rules-1",
        "manifest": {
            "releaseId": "f73ba248-c5f7-531e-938f-f60962bcc57b",
            "engineVersion": "tingle-rules-1",
            "contentHash": "8b8139559a9313a9acb88123fd616d517f293ac4a6af2ccd43775a7009087fa6",
            "matchingVersion": "r2-c-20261106", "productPolicyVersion": "tingle-policy-v2",
            "surveyVersion": "NOT_USED", "wordingVersion": "NOT_USED", "scoringVersion": "NOT_USED",
            "typeModelVersion": "NOT_USED", "analysisVersion": "NOT_USED", "typeReferenceVersion": "NOT_USED",
        },
        "artifacts": [
            {"kind": "PRODUCT_POLICY", "id": "6999125c-bb2e-5915-bf07-86ca691d99b2", "revision": 0,
             "contentHash": "9bcfb91fa3582e50a7eb28ab6ca28c9a488b86be859cb2d51fb4d2a857455d5f"},
            {"kind": "MATCHING", "id": "8b3d4dd0-75d1-5429-b686-27a6a0470500", "revision": 0,
             "contentHash": "d5d5a0ade9f607d9834d213613b5557e2c28a2e6aeccf289d5796f8f007ce390"},
        ],
    }


def test_revision_zero_and_exact_be_preimage_preserve_all_manifest_fields(tmp_path):
    value = metadata()
    original = copy.deepcopy(value)
    actual = prepare(value, tmp_path / "new")
    raw = (tmp_path / "new/be-hash-preimage.artifact.txt").read_bytes()
    assert value == original and actual == value["manifest"]
    assert raw == EXPECTED_PREIMAGE
    assert not raw.endswith(b"\n") and not raw.startswith(b"\xef\xbb\xbf") and b"\r" not in raw
    assert hashlib.sha256(raw).hexdigest() == actual["contentHash"]
    assert all(item["revision"] == 0 for item in value["artifacts"])
    ReleaseRegistry([tmp_path / "new/ai-descriptor.json"]).verify(actual)


def test_enum_order_is_not_alphabetical():
    kinds = ["MIGRATION", "ANALYSIS", "QUESTIONNAIRE"]
    rows = [{"kind": kind, "id": f"00000000-0000-4000-8000-00000000000{i}",
             "revision": 0, "contentHash": "a" * 64} for i, kind in enumerate(kinds)]
    assert [line.split(":")[0] for line in be_preimage("engine", rows).decode().splitlines()[1:]] == [
        "QUESTIONNAIRE", "ANALYSIS", "MIGRATION"]


@pytest.mark.parametrize("change", ["hash", "engine", "missing", "duplicate-kind", "duplicate-id",
                                  "negative-revision", "bool-revision", "unknown-kind", "extra-field"])
def test_inconsistent_metadata_writes_nothing(tmp_path, change):
    value = metadata()
    if change == "hash": value["manifest"]["contentHash"] = "f" * 64
    elif change == "engine": value["engineVersion"] = "other"
    elif change == "missing": value["artifacts"].pop()
    elif change == "duplicate-kind": value["artifacts"].append(copy.deepcopy(value["artifacts"][0]))
    elif change == "duplicate-id": value["artifacts"][1]["id"] = value["artifacts"][0]["id"]
    elif change == "negative-revision": value["artifacts"][0]["revision"] = -1
    elif change == "bool-revision": value["artifacts"][0]["revision"] = True
    elif change == "unknown-kind": value["artifacts"][0]["kind"] = "UNKNOWN"
    else: value["artifacts"][0]["approved"] = True
    with pytest.raises(ValueError): prepare(value, tmp_path / "blocked")
    assert not (tmp_path / "blocked").exists()


def test_create_only_and_mutation_fail_closed(tmp_path):
    value = metadata()
    output = tmp_path / "candidate"
    prepare(value, output)
    with pytest.raises(FileExistsError): prepare(value, output)
    registry = ReleaseRegistry([output / "ai-descriptor.json"])
    with (output / "be-hash-preimage.artifact.txt").open("ab") as stream: stream.write(b"\n")
    with pytest.raises(ReleaseError): registry.verify(value["manifest"])


def test_json_bundle_cannot_stand_in_for_be_release_preimage():
    value = metadata()
    bundle = json.dumps(value, sort_keys=True).encode()
    assert hashlib.sha256(bundle).hexdigest() != value["manifest"]["contentHash"]
