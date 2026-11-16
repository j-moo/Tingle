"""Synthetic files only; diagnostics must never activate rules or approve policy."""

import importlib
import json
from pathlib import Path
from types import SimpleNamespace

import pytest

from scripts.check_safety_runtime import check, main
from safety48_runtime_support import synthetic_release


def test_matching_pin_cannot_reuse_release_bound_to_pre_fix_http_source(tmp_path):
    from app.risk_detection.releases import DevelopmentReleaseCatalog, DevelopmentReleaseError, source_hashes
    # SHA-256 of origin/develop 19eddf45's HTTP source, not an approval pin.
    previous_source = "b40712c6c1e6accda6dfb0747bac03948205bc68a2d21b7961bcc447b1e6fcba"
    assert source_hashes()["http_api.py"] != previous_source
    release = synthetic_release(tmp_path)
    artifact = json.loads(release.artifact.read_bytes())
    artifact["sources"]["http_api.py"] = previous_source
    release.rewrite_artifact(artifact)
    previous_pin = release.test_descriptor_pin
    with pytest.raises(DevelopmentReleaseError):
        DevelopmentReleaseCatalog(str(release.descriptor), descriptor_sha256=previous_pin)
    result = check(environment="test", enabled=True, descriptor_file=str(release.descriptor),
                   descriptor_sha256=previous_pin)
    assert result["code"] == "RELEASE_BINDING_INVALID" and not result["ready"]


@pytest.mark.parametrize("environment,enabled,code", [
    ("local", False, "DISABLED"), ("prod", True, "ENVIRONMENT_BLOCKED"),
    ("test", True, "DESCRIPTOR_MISSING"),
])
def test_closed_preflight_never_reads_files_or_imports_optional_dependencies(monkeypatch, environment, enabled, code):
    def forbidden(*args, **kwargs):
        pytest.fail("Closed preflight accessed a dependency")
    monkeypatch.setattr(Path, "open", forbidden)
    monkeypatch.setattr(importlib, "import_module", forbidden)
    assert check(environment=environment, enabled=enabled)["code"] == code


@pytest.mark.parametrize("pin,code", [("", "PIN_MISSING"), ("bad", "PIN_INVALID"), ("0" * 64, "PIN_MISMATCH")])
def test_descriptor_does_not_approve_its_own_pin(tmp_path, pin, code):
    release = synthetic_release(tmp_path)
    result = check(environment="test", enabled=True, descriptor_file=str(release.descriptor), descriptor_sha256=pin)
    assert result["code"] == code and not result["ready"]
    assert result["approval"] == "NOT_VERIFIED" and not result["activationAuthorized"]


def test_valid_synthetic_binding_is_read_only_and_cannot_grant_approval(tmp_path, monkeypatch):
    release = synthetic_release(tmp_path)
    before = {p.name: p.read_bytes() for p in tmp_path.iterdir()}
    from app.risk_detection.local_engine import LocalRuleRiskEngine
    monkeypatch.setattr(LocalRuleRiskEngine, "__init__", lambda *a, **k: pytest.fail("Preflight started an engine"))
    result = check(environment="test", enabled=True, descriptor_file=str(release.descriptor),
                   descriptor_sha256=release.test_descriptor_pin)
    assert result["code"] == "LOCAL_BINDINGS_VERIFIED" and result["ready"]
    assert result["approval"] == result["externalService"] == "NOT_VERIFIED"
    assert result["activationAuthorized"] is False
    assert {p.name: p.read_bytes() for p in tmp_path.iterdir()} == before


@pytest.mark.parametrize("mode,code", [("missing", "TOKENIZER_UNAVAILABLE"), ("version", "TOKENIZER_VERSION_MISMATCH")])
def test_optional_dependency_is_distinct_from_release_pin(tmp_path, monkeypatch, mode, code):
    release = synthetic_release(tmp_path)
    original = importlib.import_module
    def resolve(name, *args, **kwargs):
        if name == "tokenizers":
            if mode == "missing":
                raise ImportError("synthetic-private-error")
            return SimpleNamespace(__version__="wrong")
        return original(name, *args, **kwargs)
    monkeypatch.setattr(importlib, "import_module", resolve)
    result = check(environment="test", enabled=True, descriptor_file=str(release.descriptor),
                   descriptor_sha256=release.test_descriptor_pin)
    assert result["code"] == code and not result["ready"]
    assert "synthetic-private" not in json.dumps(result)


def test_changed_artifact_and_unreadable_descriptor_are_sanitized(tmp_path):
    release = synthetic_release(tmp_path)
    pin = release.test_descriptor_pin
    release.artifact.write_bytes(b"changed")
    args = dict(environment="test", enabled=True, descriptor_file=str(release.descriptor), descriptor_sha256=pin)
    assert check(**args)["code"] == "RELEASE_BINDING_INVALID"
    release.descriptor.unlink()
    result = check(**args)
    assert result["code"] == "DESCRIPTOR_UNREADABLE"
    assert str(tmp_path) not in json.dumps(result)


def test_cli_does_not_consume_activation_environment(monkeypatch, capsys):
    monkeypatch.setenv("RISK_LOCAL_RULES_ENABLED", "true")
    assert main([]) == 2
    result = json.loads(capsys.readouterr().out)
    assert result["code"] == "DISABLED"
