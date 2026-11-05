"""Bind an explicitly supplied development release to actual code/tokenizer bytes.

This validates consistency and integrity, not team approval. No release ID,
descriptor or artifact is generated or implicitly selected by runtime code.
"""

import hashlib
from pathlib import Path

from app.risk_detection.http_api import Manifest, ReleaseCatalog, _json, _read_bounded
from app.risk_detection.rule_engine import MODEL_VERSION, PROMPT_VERSION
from app.risk_detection.tokenization import INPUT_VERSION, TOKENIZERS_VERSION, LocalFileTokenizer


SOURCE_FILES = (
    "__init__.py", "http_api.py", "local_engine.py", "rule_engine.py", "validation.py",
    "tokenization.py", "releases.py", "runtime.py",
)
SOURCE_ROOT = Path(__file__).resolve().parent
FORMAT = "safety48-development-rules-v1"


class DevelopmentReleaseError(RuntimeError):
    """A fixed message prevents source paths or configuration contents leaking."""

    def __init__(self):
        super().__init__("Safety Guard development runtime unavailable")


def source_hashes() -> dict[str, str]:
    """Fingerprint the fixed source set; callers cannot supply arbitrary paths."""
    return {name: hashlib.sha256(_read_bounded(SOURCE_ROOT / name, 131072)).hexdigest()
            for name in SOURCE_FILES}


def _sibling(root: Path, name: str) -> Path:
    if (type(name) is not str or not name or name in {".", ".."}
            or any(character in name for character in ("/", "\\", ":"))):
        raise ValueError("Invalid development release path")
    target = root / name
    if target.is_symlink() or target.resolve().parent != root:
        raise ValueError("Invalid development release path")
    return target


class DevelopmentReleaseCatalog(ReleaseCatalog):
    """Keep the original manifest checks and add pinned implementation checks."""

    def __init__(self, descriptor_file: str, *, descriptor_sha256: str):
        super().__init__(descriptor_file)
        try:
            if not descriptor_file or not descriptor_sha256:
                raise ValueError
            self._descriptor = Path(descriptor_file).absolute()
            self._descriptor_raw = _read_bounded(self._descriptor, 65536)
            # A separately configured trust pin must come from release review.
            # Never derive an activation pin from an arbitrary supplied file.
            if hashlib.sha256(self._descriptor_raw).hexdigest() != descriptor_sha256:
                raise ValueError
            descriptor = _json(self._descriptor_raw)
            self._manifest = Manifest.model_validate(descriptor["manifest"])
            if not super().supports(self._manifest):
                raise ValueError
            root = self._descriptor.resolve().parent
            self._artifact = _sibling(root, descriptor["artifact"])
            self._artifact_raw = _read_bounded(self._artifact, 1048576)
            artifact = _json(self._artifact_raw)
            expected = {
                "format": FORMAT, "scope": "development-only",
                "modelVersion": MODEL_VERSION, "promptVersion": PROMPT_VERSION,
                "inputVersion": INPUT_VERSION, "tokenizersVersion": TOKENIZERS_VERSION,
                "sources": source_hashes(),
            }
            # Do not repurpose the shared survey/matching manifest fields as
            # tokenizer/preprocessing versions. contentHash binds this artifact;
            # rule/input/tokenizer versions live inside its verified bytes.
            if (type(artifact) is not dict or set(artifact) != set(expected) | {"tokenizer"}
                    or any(artifact[key] != value for key, value in expected.items())):
                raise ValueError
            reference = artifact["tokenizer"]
            if (type(reference) is not dict or set(reference) != {"file", "sha256", "addSpecialTokens"}
                    or reference["addSpecialTokens"] is not True):
                raise ValueError
            self._tokenizer_file = _sibling(root, reference["file"])
            self._tokenizer_raw = _read_bounded(self._tokenizer_file, 16777216)
            if hashlib.sha256(self._tokenizer_raw).hexdigest() != reference["sha256"]:
                raise ValueError
            self._sources = expected["sources"]
            self.tokenizer = LocalFileTokenizer(self._tokenizer_raw)
            if not self.supports(self._manifest):
                raise ValueError
        except Exception:
            raise DevelopmentReleaseError() from None

    def supports(self, manifest: Manifest) -> bool:
        try:
            return (manifest == self._manifest and super().supports(manifest)
                    and _read_bounded(self._descriptor, 65536) == self._descriptor_raw
                    and _read_bounded(self._artifact, 1048576) == self._artifact_raw
                    and _read_bounded(self._tokenizer_file, 16777216) == self._tokenizer_raw
                    and source_hashes() == self._sources)
        except Exception:
            return False
