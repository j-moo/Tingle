"""Default-OFF registration, actual local tokens, and file/code binding probes."""

import builtins
import json
import shutil

from fastapi import FastAPI
from fastapi.testclient import TestClient
import pytest

from app.config import Settings
from app.middleware.request_id import RequestIdMiddleware, install_error_handlers
from app.risk_detection import local_engine, releases
from app.risk_detection.http_api import Message
from app.risk_detection.releases import TokenizedLocalRuleReleaseCatalog, DevelopmentReleaseError
from app.risk_detection.runtime import create_development_router
from app.risk_detection.tokenization import LocalFileTokenizer, serialize_context
from safety48_runtime_support import message, request_for, synthetic_release, synthetic_tokenizer
from test_risk_detection_http import ROUTE, assert_error


def client(release=None, *, enabled=True, environment="test"):
    app = FastAPI()
    install_error_handlers(app)
    app.add_middleware(RequestIdMiddleware)
    # Domain unit tests only. Real cryptographic auth and actual main are tested
    # by the separate loopback/subprocess module, without this test double.
    app.include_router(create_development_router(
        lambda _: True, environment=environment, enabled=enabled,
        descriptor_file=str(release.descriptor) if release else "",
        descriptor_sha256=release.test_descriptor_pin if release else "",
    ))
    return TestClient(app)


def test_release_pin_default(monkeypatch):
    monkeypatch.delenv("RISK_LOCAL_RULES_DESCRIPTOR_SHA256", raising=False)
    assert Settings(_env_file=None).risk_local_rules_descriptor_sha256 == ""


@pytest.mark.parametrize("environment", ["local", "test", "dev", "prod"])
@pytest.mark.parametrize("enabled", [False, True])
def test_release_registration_ignores_legacy_switch_and_environment(tmp_path, environment, enabled):
    release = synthetic_release(tmp_path)
    response = client(release, environment=environment, enabled=enabled).post(
        ROUTE, json=request_for(release, [message("원금 보장하니 인증금을 송금해.")]),
    )
    assert response.status_code == 200
    assert response.json()["verdict"]["kind"] == "RISK"
    assert response.json()["verdict"]["findings"] == [
        dict(sequence=1, category="FRAUD", noticeType="MONEY_REQUEST", audience="RECIPIENT")]


@pytest.mark.parametrize("environment,enabled", [
    (environment, enabled)
    for environment in ("local", "test", "dev", "prod", "production", "unknown")
    for enabled in (False, True)
    if not (enabled and environment in {"local", "test", "dev"})
])
def test_missing_release_does_not_import_tokenizers(environment, enabled, monkeypatch):
    original = builtins.__import__

    def guarded(name, *args, **kwargs):
        assert name != "tokenizers", "Disabled runtime imported the optional package"
        return original(name, *args, **kwargs)

    monkeypatch.setattr(builtins, "__import__", guarded)
    response = client(enabled=enabled, environment=environment).post(ROUTE, content=b"invalid-body-not-parsed")
    assert_error(response, 422, "POLICY_UNRESOLVED")


def test_partial_release_configuration_fails_closed():
    with pytest.raises(DevelopmentReleaseError, match="^Safety Guard development runtime unavailable$"):
        create_development_router(lambda _: True, environment="prod", descriptor_file="missing-release.json")


@pytest.mark.parametrize("pin", ["", "0" * 64, "not-a-review-pin"])
def test_a_release_path_alone_cannot_self_approve_activation(tmp_path, pin):
    release = synthetic_release(tmp_path)
    with pytest.raises(DevelopmentReleaseError, match="^Safety Guard development runtime unavailable$"):
        create_development_router(lambda _: True, environment="dev", enabled=True,
                                  descriptor_file=str(release.descriptor), descriptor_sha256=pin)


def test_actual_token_ids_cover_canonical_context_and_special_tokens(tmp_path):
    tokenizer = synthetic_tokenizer()
    raw = tokenizer.to_str().encode()
    parsed = [Message(**message("이전 합성 문맥", fresh=False)), Message(**message("합성 !!!", 2))]
    text = serialize_context(parsed)
    counter = LocalFileTokenizer(raw)
    expected = tokenizer.encode(text, add_special_tokens=True).ids
    assert expected[0] == 1 and expected[-1] == 2
    assert counter.count(text) == len(expected)
    assert counter.count(text) != len(text)
    assert json.loads(text) == {"contextBundle": [m.model_dump() for m in parsed]}


@pytest.mark.parametrize("mode", ["truncation", "padding"])
def test_tokenizer_may_not_silently_shorten_or_pad_full_context(tmp_path, mode):
    tokenizer = synthetic_tokenizer()
    if mode == "truncation":
        tokenizer.enable_truncation(max_length=2)
    else:
        tokenizer.enable_padding()
    release = synthetic_release(tmp_path, tokenizer)
    with pytest.raises(DevelopmentReleaseError):
        client(release)


@pytest.mark.parametrize("missing", [False, True])
def test_missing_or_wrong_optional_runtime_never_falls_back_to_estimation(tmp_path, monkeypatch, missing):
    release = synthetic_release(tmp_path)
    import tokenizers
    if missing:
        original = builtins.__import__

        def without_tokenizers(name, *args, **kwargs):
            if name == "tokenizers":
                raise ImportError("synthetic-private-path")
            return original(name, *args, **kwargs)

        monkeypatch.setattr(builtins, "__import__", without_tokenizers)
    else:
        monkeypatch.setattr(tokenizers, "__version__", "unsupported")
    with pytest.raises(DevelopmentReleaseError, match="^Safety Guard development runtime unavailable$"):
        client(release)


@pytest.mark.parametrize("change", [
    "source", "source-set", "scope", "format", "modelVersion", "promptVersion", "inputVersion",
    "tokenizersVersion", "tokenizer-hash", "tokenizer-path", "special-tokens", "extra-field",
    "missing-tokenizer", "broken-tokenizer", "artifact-hash",
    "descriptor-duplicate", "artifact-duplicate",
])
def test_wrong_implementation_or_release_is_rejected_before_registration(tmp_path, change):
    release = synthetic_release(tmp_path)
    artifact = json.loads(release.artifact.read_text(encoding="utf-8"))
    if change == "source":
        artifact["sources"]["rule_engine.py"] = "0" * 64
    elif change == "source-set":
        artifact["sources"].pop("validation.py")
    elif change in {"scope", "format", "modelVersion", "promptVersion", "inputVersion", "tokenizersVersion"}:
        artifact[change] = "unsupported"
    elif change == "tokenizer-hash":
        artifact["tokenizer"]["sha256"] = "0" * 64
    elif change == "tokenizer-path":
        artifact["tokenizer"]["file"] = "../synthetic-only-tokenizer.json"
    elif change == "special-tokens":
        artifact["tokenizer"]["addSpecialTokens"] = False
    elif change == "extra-field":
        artifact["approved"] = True  # No invented approval field is accepted.
    elif change == "missing-tokenizer":
        release.tokenizer.unlink()
    elif change == "broken-tokenizer":
        import hashlib
        release.tokenizer.write_bytes(b"{}")
        artifact["tokenizer"]["sha256"] = hashlib.sha256(b"{}").hexdigest()
    release.rewrite_artifact(artifact)
    if change == "artifact-hash":
        release.artifact.write_bytes(b"changed")
    elif change == "descriptor-duplicate":
        raw = release.descriptor.read_text(encoding="utf-8")
        release.descriptor.write_text(raw[:-1] + ',"artifact":"other.json"}', encoding="utf-8")
    elif change == "artifact-duplicate":
        import hashlib
        raw = release.artifact.read_text(encoding="utf-8")
        release.artifact.write_text(raw[:-1] + ',"scope":"development-only"}', encoding="utf-8")
        release.manifest["contentHash"] = hashlib.sha256(release.artifact.read_bytes()).hexdigest()
        release.descriptor.write_text(json.dumps({"manifest": release.manifest, "artifact": release.artifact.name}),
                                      encoding="utf-8")
    with pytest.raises(DevelopmentReleaseError, match="^Safety Guard development runtime unavailable$"):
        client(release)


@pytest.mark.parametrize("field", ["engineVersion", "analysisVersion"])
def test_shared_manifest_versions_keep_their_meaning_but_must_match_in_full(tmp_path, field):
    release = synthetic_release(tmp_path)
    request = request_for(release)
    request["manifest"][field] = "different-synthetic-version"
    response = client(release).post(ROUTE, json=request)
    assert_error(response, 422, "POLICY_UNRESOLVED")


@pytest.mark.parametrize("change", ["descriptor", "artifact", "tokenizer", "source"])
@pytest.mark.parametrize("during", [False, True])
def test_source_or_release_changes_are_rejected_before_and_after_analysis(tmp_path, monkeypatch, change, during):
    release = synthetic_release(tmp_path)
    # Source tampering only touches an isolated copy, never the actual worktree.
    source_copy = tmp_path / "source-copy"
    source_copy.mkdir()
    for name in releases.SOURCE_FILES:
        shutil.copyfile(releases.SOURCE_ROOT / name, source_copy / name)
    monkeypatch.setattr(releases, "SOURCE_ROOT", source_copy)
    host = client(release)

    def mutate():
        target = source_copy / "rule_engine.py" if change == "source" else getattr(release, change)
        target.write_bytes(target.read_bytes() + b"\n ")

    if during:
        classify = local_engine.classify

        def changed(messages):
            result = classify(messages)
            mutate()
            return result

        monkeypatch.setattr(local_engine, "classify", changed)
    else:
        mutate()
    response = host.post(ROUTE, json=request_for(release))
    assert_error(response, 422, "POLICY_UNRESOLVED")


@pytest.mark.parametrize("total,status", [(3000, 200), (3001, 400)])
def test_actual_tokenizer_limit_is_enforced_without_character_estimates(tmp_path, total, status):
    release = synthetic_release(tmp_path)
    counter = TokenizedLocalRuleReleaseCatalog(str(release.descriptor),
                                        descriptor_sha256=release.test_descriptor_pin).tokenizer
    overhead = counter.count(serialize_context([Message(**message("!"))])) - 1
    messages = [message("!" * (total - overhead))]
    assert len(messages[0]["text"]) <= 4000
    assert counter.count(serialize_context([Message(**messages[0])])) == total
    response = client(release).post(ROUTE, json=request_for(release, messages))
    assert response.status_code == status
    if status == 400:
        assert_error(response, 400, "INVALID_REQUEST")


@pytest.mark.parametrize("stage", ["tokenizer", "rules", "invalid-verdict"])
def test_enabled_runtime_errors_never_turn_into_normal(tmp_path, monkeypatch, stage):
    release = synthetic_release(tmp_path)

    def fail(*args):
        raise RuntimeError("synthetic-sensitive-error")

    if stage == "tokenizer":
        monkeypatch.setattr(LocalFileTokenizer, "count", fail)
    elif stage == "rules":
        monkeypatch.setattr(local_engine, "classify", fail)
    else:
        monkeypatch.setattr(local_engine, "classify", lambda _: {"kind": "NORMAL", "findings": [{}]})
    response = client(release).post(ROUTE, json=request_for(release))
    assert_error(response, 503, "SERVICE_UNAVAILABLE")
    assert "verdict" not in response.json() and "synthetic-sensitive-error" not in response.text
