"""Internal, default-OFF GMS assembly; no environment or credential discovery.

The separate approval pin is a trust input supplied after team review, never
derived at startup. A development rules artifact alone cannot authorize GMS.
This module validates pinned bytes, not the existence of human approval.
"""

from collections.abc import Callable
from dataclasses import asdict, dataclass, field
import hashlib
from pathlib import Path

from app.match_explanation.gms import ENDPOINT, MODEL, GmsClient
from app.risk_detection.execution import ExecutionLimits
from app.risk_detection.gms_primary import PRIMARY_PROMPT, GmsPrimaryAdapter
from app.risk_detection.http_api import ReleaseCatalog, _json, _read_bounded
from app.risk_detection.local_engine import LocalRuleRiskEngine
from app.risk_detection.luna_shadow import LunaShadowEngine, ModelIdentity
from app.risk_detection.releases import DevelopmentReleaseCatalog, DevelopmentReleaseError, SOURCE_FILES


FORMAT = "safety48-gms-primary-v1"
PROMPT_VERSION = "safety48-gms-primary-v1"
GMS_SOURCE_FILES = tuple("risk_detection/" + name for name in SOURCE_FILES) + (
    "main.py", "risk_detection/gms_primary.py", "risk_detection/gms_runtime.py",
    "match_explanation/gms.py", "match_explanation/dto.py",
    "match_explanation/prompts.py", "match_explanation/validation.py",
)
SOURCE_ROOT = Path(__file__).resolve().parent.parent


def gms_source_hashes() -> dict[str, str]:
    return {name: hashlib.sha256(_read_bounded(SOURCE_ROOT / name, 131072)).hexdigest()
            for name in GMS_SOURCE_FILES}


@dataclass(frozen=True)
class GmsPrimaryConfiguration:
    # Python composition only: Settings, environment and HTTP cannot select it.
    enabled: bool = False
    approval_file: str = ""
    approval_sha256: str = ""
    client_factory: Callable[[], GmsClient] | None = field(default=None, repr=False)
    limits: ExecutionLimits = field(default_factory=ExecutionLimits)


class GmsReleaseCatalog(ReleaseCatalog):
    """Recheck both rule provenance and a separate exact GMS approval snapshot."""

    def __init__(self, rules: DevelopmentReleaseCatalog, configuration: GmsPrimaryConfiguration):
        super().__init__(rules.descriptor_file)
        self.rules, self.tokenizer, self._manifest = rules, rules.tokenizer, rules._manifest
        self._configuration = configuration
        try:
            if not configuration.approval_file or not configuration.approval_sha256:
                raise ValueError
            self._approval_file = Path(configuration.approval_file).absolute()
            self._approval_raw = _read_bounded(self._approval_file, 65536)
            if hashlib.sha256(self._approval_raw).hexdigest() != configuration.approval_sha256:
                raise ValueError
            self._approval = _json(self._approval_raw)
            if self._approval != self._binding() or not self.supports(self._manifest):
                raise ValueError
        except Exception:
            raise DevelopmentReleaseError() from None

    def _binding(self):
        return {
            "format": FORMAT, "scope": "development-only",
            "manifest": self._manifest.model_dump(),
            "rulesDescriptorSha256": hashlib.sha256(self.rules._descriptor_raw).hexdigest(),
            "endpoint": ENDPOINT, "modelVersion": MODEL, "promptVersion": PROMPT_VERSION,
            "promptSha256": hashlib.sha256(PRIMARY_PROMPT.encode("utf-8")).hexdigest(),
            "executionLimits": asdict(self._configuration.limits),
            "sources": gms_source_hashes(),
        }

    def supports(self, manifest):
        try:
            return (self.rules.supports(manifest)
                    and _read_bounded(self._approval_file, 65536) == self._approval_raw
                    and self._approval == self._binding())
        except Exception:
            return False


class GmsPrimaryEngine(LunaShadowEngine):
    """Reuse !296's primary/fallback/deadlines; never schedule Shadow work.

    The inherited review dependency aliases the real adapter but is unreachable:
    the HTTP layer only starts Shadow when start_shadow is callable. No fake
    model or test module is part of runtime assembly.
    """

    start_shadow = None

    def __init__(self, *, catalog: GmsReleaseCatalog, configuration: GmsPrimaryConfiguration):
        if not callable(configuration.client_factory):
            raise DevelopmentReleaseError()
        client = configuration.client_factory()
        if type(client) is not GmsClient:
            raise DevelopmentReleaseError()
        identity = ModelIdentity(MODEL, PROMPT_VERSION)
        adapter = GmsPrimaryAdapter(client=client, identity=identity)
        super().__init__(primary=adapter, review=adapter,
                         rules=LocalRuleRiskEngine(count_input_tokens=catalog.tokenizer.count),
                         identity=identity, limits=configuration.limits)
