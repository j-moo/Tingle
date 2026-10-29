"""임시 합성 descriptor·artifact만 사용한다. 실제 승인 release 등록 증거가 아니다."""

import hashlib
import json
from uuid import uuid4

import pytest

from app.match_explanation.releases import MANIFEST_FIELDS, ReleaseError, ReleaseRegistry


def descriptor(tmp_path, artifact_bytes=b'{"synthetic": true}\n', identifier=None):
    artifact = tmp_path / "synthetic.bundle"
    artifact.write_bytes(artifact_bytes)
    manifest = {name: "synthetic-v1" for name in MANIFEST_FIELDS}
    manifest["releaseId"] = identifier or str(uuid4())
    manifest["contentHash"] = hashlib.sha256(artifact_bytes).hexdigest()
    path = tmp_path / "descriptor.json"
    path.write_text(json.dumps({"manifest": manifest, "artifact": artifact.name}), encoding="utf-8")
    return path, artifact, manifest


def test_empty_default_registry_rejects_every_request(tmp_path):
    _, _, manifest = descriptor(tmp_path)
    with pytest.raises(ReleaseError):
        ReleaseRegistry().verify(manifest)


def test_registered_exact_artifact_bytes_and_all_eleven_manifest_fields_match(tmp_path):
    path, _, manifest = descriptor(tmp_path)
    registry = ReleaseRegistry([path])
    registry.verify(manifest)
    assert set(manifest) == MANIFEST_FIELDS


def test_json_whitespace_changes_hash_without_normalizing_or_hashing_descriptor(tmp_path):
    path, artifact, manifest = descriptor(tmp_path, b'{ "synthetic" : true }\r\n')
    registry = ReleaseRegistry([path])
    registry.verify(manifest)
    assert manifest["contentHash"] != hashlib.sha256(path.read_bytes()).hexdigest()
    artifact.write_bytes(b'{"synthetic":true}\n')
    with pytest.raises(ReleaseError):
        registry.verify(manifest)


def test_bundle_bytes_can_cover_multiple_approved_sources_without_new_wire_fields(tmp_path):
    path, _, manifest = descriptor(tmp_path, b'{"files":{"one":"synthetic-A","two":"synthetic-B"}}')
    ReleaseRegistry([path]).verify(manifest)


@pytest.mark.parametrize("field", sorted(MANIFEST_FIELDS))
def test_each_request_manifest_field_must_match_registration(tmp_path, field):
    path, _, manifest = descriptor(tmp_path)
    registry = ReleaseRegistry([path])
    changed = {**manifest, field: str(uuid4()) if field == "releaseId" else "b" * 64}
    with pytest.raises(ReleaseError):
        registry.verify(changed)


@pytest.mark.parametrize("name", ["../outside", "nested/file", "C:\\secret", "https://example.org/file", ".", ".."])
def test_descriptor_cannot_select_url_or_non_basename_artifact(tmp_path, name):
    path, _, manifest = descriptor(tmp_path)
    path.write_text(json.dumps({"manifest": manifest, "artifact": name}), encoding="utf-8")
    with pytest.raises(ReleaseError):
        ReleaseRegistry([path])


def test_missing_and_changed_artifact_are_closed(tmp_path):
    path, artifact, manifest = descriptor(tmp_path)
    registry = ReleaseRegistry([path])
    artifact.unlink()
    with pytest.raises(ReleaseError):
        registry.verify(manifest)
    with pytest.raises(ReleaseError):
        ReleaseRegistry([path])


def test_descriptor_change_cannot_redefine_registered_uuid(tmp_path):
    path, _, manifest = descriptor(tmp_path)
    registry = ReleaseRegistry([path])
    changed = {**manifest, "productPolicyVersion": "other"}
    path.write_text(json.dumps({"manifest": changed, "artifact": "synthetic.bundle"}), encoding="utf-8")
    with pytest.raises(ReleaseError):
        registry.verify(changed)
    with pytest.raises(ReleaseError):
        registry.verify(manifest)


def test_same_uuid_cannot_register_two_different_descriptors(tmp_path):
    one = tmp_path / "one"
    two = tmp_path / "two"
    one.mkdir()
    two.mkdir()
    identifier = str(uuid4())
    first, _, _ = descriptor(one, b"synthetic-one", identifier)
    second, _, _ = descriptor(two, b"synthetic-two", identifier)
    with pytest.raises(ReleaseError):
        ReleaseRegistry([first, second])


def test_request_path_and_unknown_descriptor_fields_are_not_registration(tmp_path):
    path, _, manifest = descriptor(tmp_path)
    with pytest.raises(ReleaseError):
        ReleaseRegistry([path]).verify({**manifest, "artifact": "arbitrary"})
    path.write_text(json.dumps({"manifest": manifest, "artifact": "synthetic.bundle", "url": "arbitrary"}),
                    encoding="utf-8")
    with pytest.raises(ReleaseError):
        ReleaseRegistry([path])


def test_duplicate_descriptor_json_keys_are_rejected(tmp_path):
    path, _, manifest = descriptor(tmp_path)
    path.write_text('{"manifest":' + json.dumps(manifest) + ',"artifact":"synthetic.bundle",'
                    '"artifact":"synthetic.bundle"}', encoding="utf-8")
    with pytest.raises(ReleaseError):
        ReleaseRegistry([path])
