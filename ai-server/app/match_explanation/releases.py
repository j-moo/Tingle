"""고정 등록 descriptor와 불변 artifact의 정확한 파일 bytes를 검증한다.

요청이 파일 경로를 선택하지 않는다. 여러 파일은 승인된 단일 불변 bundle로
등록한다. 실제 승인 release는 기본 등록하지 않는다.
"""

import hashlib
import json
import re
from collections.abc import Iterable, Mapping
from dataclasses import dataclass
from pathlib import Path
from types import MappingProxyType
from uuid import UUID

MANIFEST_FIELDS = frozenset({
    "releaseId", "surveyVersion", "wordingVersion", "scoringVersion", "typeModelVersion",
    "analysisVersion", "typeReferenceVersion", "matchingVersion", "productPolicyVersion",
    "engineVersion", "contentHash",
})


class ReleaseError(ValueError):
    """등록·파일·요청 manifest가 검증되지 않았으며 내부 경로를 노출하지 않는다."""


def _object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ReleaseError("Invalid release descriptor")
        result[key] = value
    return result


def _manifest(value: object) -> dict[str, str]:
    if not isinstance(value, dict) or set(value) != MANIFEST_FIELDS:
        raise ReleaseError("Invalid release manifest")
    if any(type(item) is not str for item in value.values()):
        raise ReleaseError("Invalid release manifest")
    try:
        UUID(value["releaseId"])
    except ValueError:
        raise ReleaseError("Invalid release manifest") from None
    if re.fullmatch(r"[0-9a-f]{64}", value["contentHash"]) is None:
        raise ReleaseError("Invalid release manifest")
    versions = MANIFEST_FIELDS - {"releaseId", "contentHash"}
    if any(not value[field].strip() or len(value[field]) > 100 for field in versions):
        raise ReleaseError("Invalid release manifest")
    return value


@dataclass(frozen=True)
class _Release:
    descriptor: Path
    descriptor_bytes: bytes
    artifact: Path
    manifest: Mapping[str, str]

    def verify(self) -> None:
        if self.descriptor.read_bytes() != self.descriptor_bytes:
            raise ReleaseError("Release descriptor changed")
        if hashlib.sha256(self.artifact.read_bytes()).hexdigest() != self.manifest["contentHash"]:
            raise ReleaseError("Release artifact changed")


class ReleaseRegistry:
    """서버 설정에서만 descriptor 경로를 등록한다. 동일 UUID의 재등록은 거부한다."""

    def __init__(self, descriptor_paths: Iterable[Path] = ()) -> None:
        releases = {}
        try:
            for configured in descriptor_paths:
                path = Path(configured).resolve(strict=True)
                raw = path.read_bytes()
                descriptor = json.loads(raw, object_pairs_hook=_object)
                if not isinstance(descriptor, dict) or set(descriptor) != {"manifest", "artifact"}:
                    raise ReleaseError("Invalid release descriptor")
                manifest = _manifest(descriptor["manifest"])
                name = descriptor["artifact"]
                if (type(name) is not str or not name or name in {".", ".."}
                        or any(char in name for char in ("/", "\\", ":"))):
                    raise ReleaseError("Artifact must be a registered basename")
                artifact = (path.parent / name).resolve(strict=True)
                if artifact.parent != path.parent or artifact == path:
                    raise ReleaseError("Invalid registered artifact")
                identifier = str(UUID(manifest["releaseId"]))
                if identifier in releases:
                    raise ReleaseError("Duplicate immutable release identifier")
                release = _Release(path, raw, artifact, MappingProxyType(dict(manifest)))
                release.verify()
                releases[identifier] = release
        except (OSError, UnicodeError, ValueError, TypeError):
            raise ReleaseError("Release registration failed") from None
        self._releases = MappingProxyType(releases)

    def verify(self, manifest: Mapping[str, str]) -> None:
        try:
            expected = _manifest(dict(manifest))
            release = self._releases.get(str(UUID(expected["releaseId"])))
            if release is None or expected != release.manifest:
                raise ReleaseError("Unregistered or mismatched release")
            release.verify()
        except (OSError, UnicodeError, ValueError, TypeError):
            raise ReleaseError("Release verification failed") from None
