"""Offline metadata integrity cannot imply real release approval or deployment."""

import json

import pytest

from scripts.check_match_release import check, main
from scripts.prepare_match_from_be_metadata import prepare
from test_match_be_release_metadata import metadata


@pytest.mark.parametrize("error_type", [RecursionError, RuntimeError])
def test_preflight_does_not_hide_internal_registry_failures(tmp_path, monkeypatch, error_type):
    output = tmp_path / "candidate"
    prepare(metadata(), output)
    def broken(*args, **kwargs):
        raise error_type("synthetic-internal-registry-failure")
    monkeypatch.setattr("scripts.check_match_release.ReleaseRegistry", broken)
    with pytest.raises(error_type, match="synthetic-internal-registry-failure"):
        check(output / "ai-descriptor.json", output / "manifest.json")


def test_exact_be_metadata_files_are_read_only_and_remain_unapproved(tmp_path):
    output = tmp_path / "candidate"
    prepare(metadata(), output)
    before = {p.name: p.read_bytes() for p in output.iterdir()}
    result = check(output / "ai-descriptor.json", output / "manifest.json")
    assert result == {
        "fileIntegrity": "PASS", "approval": "NOT_VERIFIED", "externalService": "NOT_VERIFIED",
        "serverRegistration": "NOT_ATTEMPTED", "activationAuthorized": False,
    }
    assert {p.name: p.read_bytes() for p in output.iterdir()} == before


@pytest.mark.parametrize("change", ["manifest", "descriptor", "artifact", "missing", "nested", "duplicate"])
def test_cli_rejects_invalid_files_without_leaking_paths_or_registering(tmp_path, change, capsys):
    output = tmp_path / "candidate"
    prepare(metadata(), output)
    manifest = output / "manifest.json"
    if change == "manifest":
        value = json.loads(manifest.read_bytes())
        value["productPolicyVersion"] = "unreviewed"
        manifest.write_text(json.dumps(value), encoding="utf-8")
    elif change == "descriptor":
        (output / "ai-descriptor.json").write_bytes(b"not JSON")
    elif change == "artifact":
        (output / "be-hash-preimage.artifact.txt").write_bytes(b"changed")
    elif change == "missing":
        manifest.unlink()
    elif change == "nested":
        manifest.write_bytes(b"[" * 20000 + b"0" + b"]" * 20000)
    else:
        raw = manifest.read_bytes()
        manifest.write_bytes(raw[:-2] + b',"releaseId":"duplicate"}')
    assert main(["--descriptor", str(output / "ai-descriptor.json"), "--manifest", str(manifest)]) == 2
    raw = capsys.readouterr().out
    assert str(tmp_path) not in raw
    result = json.loads(raw)
    assert result["fileIntegrity"] == "FAIL" and result["serverRegistration"] == "NOT_ATTEMPTED"
