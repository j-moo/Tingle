"""Synthetic development artifacts only. Never an approved/reusable release.

No private key or JWT is written to a fixture or artifact. Runtime code does
not import this module and never generates a release automatically.
"""

from dataclasses import dataclass
import hashlib
import json
import os
from pathlib import Path
from uuid import uuid4

import pytest

from app.risk_detection.releases import FORMAT, source_hashes
from app.risk_detection.rule_engine import MODEL_VERSION, PROMPT_VERSION
from app.risk_detection.tokenization import INPUT_VERSION, TOKENIZERS_VERSION
from test_risk_detection_http import VERSION_FIELDS


ROOT = Path(__file__).resolve().parents[1]


def synthetic_tokenizer():
    tokenizers = pytest.importorskip("tokenizers", reason="Optional local tokenizer runtime is required")
    tokenizer = tokenizers.Tokenizer(tokenizers.models.WordLevel(
        {"[UNK]": 0, "[CLS]": 1, "[SEP]": 2, "!": 3, "합성": 4}, unk_token="[UNK]",
    ))
    tokenizer.pre_tokenizer = tokenizers.pre_tokenizers.Sequence([
        tokenizers.pre_tokenizers.WhitespaceSplit(), tokenizers.pre_tokenizers.Punctuation(behavior="isolated"),
    ])
    tokenizer.post_processor = tokenizers.processors.TemplateProcessing(
        single="[CLS] $A [SEP]", special_tokens=[("[CLS]", 1), ("[SEP]", 2)],
    )
    return tokenizer


@dataclass
class SyntheticRelease:
    descriptor: Path
    artifact: Path
    tokenizer: Path
    manifest: dict

    @property
    def test_descriptor_pin(self):
        # Test trust input only; this is not an approval record or runtime API.
        return hashlib.sha256(self.descriptor.read_bytes()).hexdigest()

    def rewrite_artifact(self, value):
        self.artifact.write_text(json.dumps(value, ensure_ascii=False), encoding="utf-8")
        self.manifest["contentHash"] = hashlib.sha256(self.artifact.read_bytes()).hexdigest()
        self.descriptor.write_text(json.dumps({"manifest": self.manifest, "artifact": self.artifact.name}),
                                   encoding="utf-8")


def synthetic_release(directory: Path, tokenizer=None) -> SyntheticRelease:
    tokenizer = tokenizer if tokenizer is not None else synthetic_tokenizer()
    tokenizer_file = directory / "synthetic-only-tokenizer.json"
    tokenizer_file.write_text(tokenizer.to_str(), encoding="utf-8")
    manifest = {field: "synthetic-test-only" for field in VERSION_FIELDS}
    manifest.update(releaseId=str(uuid4()))
    release = SyntheticRelease(directory / "synthetic-only-release.json",
                               directory / "synthetic-only-rules.json", tokenizer_file, manifest)
    release.rewrite_artifact({
        "format": FORMAT, "scope": "development-only", "modelVersion": MODEL_VERSION,
        "promptVersion": PROMPT_VERSION, "inputVersion": INPUT_VERSION,
        "tokenizersVersion": TOKENIZERS_VERSION, "sources": source_hashes(),
        "tokenizer": {"file": tokenizer_file.name, "sha256": hashlib.sha256(tokenizer_file.read_bytes()).hexdigest(),
                      "addSpecialTokens": True},
    })
    return release


def message(text, sequence=1, speaker="A", fresh=True):
    return dict(sequence=sequence, speaker=speaker, text=text, fresh=fresh, urlMasked=False)


def request_for(release, messages=None):
    return {"attemptId": str(uuid4()), "contextId": 123, "manifest": dict(release.manifest),
            "contextBundle": messages if messages is not None else [message("합성 대화")]}


def isolated_environment(directory: Path, *, environment="test", enabled=True, release=None, public_keys=None):
    # No project .env, real credentials, model endpoint or caller AI switches.
    env = {key: value for key, value in os.environ.items()
           if key.upper() in {"SYSTEMROOT", "WINDIR", "PATH", "TEMP", "TMP"}}
    paths = [str(ROOT)]
    try:
        import tokenizers
        paths.append(str(Path(tokenizers.__file__).resolve().parent.parent))
    except ImportError:
        pass
    env.update({
        "APP_ENV": environment, "RISK_LOCAL_RULES_ENABLED": "true" if enabled else "false",
        "RISK_RELEASE_DESCRIPTOR_FILE": str(release.descriptor) if release else "",
        "RISK_LOCAL_RULES_DESCRIPTOR_SHA256": release.test_descriptor_pin if release else "",
        "AI_SERVICE_PUBLIC_KEYS": json.dumps(public_keys or {}),
        "EXPLANATION_RELEASE_DESCRIPTOR_FILES": "[]", "PYTHONPATH": os.pathsep.join(paths),
        "PYTHONIOENCODING": "utf-8", "PYTHONDONTWRITEBYTECODE": "1", "TOKENIZERS_PARALLELISM": "false",
    })
    return env
