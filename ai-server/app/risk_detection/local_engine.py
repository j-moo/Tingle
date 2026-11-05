"""Offline rules; runtime registration is explicit and development-only."""

from typing import Callable

from app.risk_detection.http_api import Manifest
from app.risk_detection.rule_engine import classify
from app.risk_detection.tokenization import serialize_context
from app.risk_detection.validation import approved_messages, validated_verdict


class EngineUnavailableError(RuntimeError):
    """Sanitized failure mapped to HTTP 503 by the existing router."""


class LocalRuleRiskEngine:
    """RiskEngine implementation with no provider or storage.

    A token counter is mandatory and has no estimate/default implementation.
    It receives the entire canonical JSON context consumed by the rules, with
    old/new messages and metadata. A real, versioned tokenizer must be supplied
    before runtime registration. Synthetic test counters prove transport only.
    ReleaseCatalog verification and the 3,000-token limit belong to the router.
    """

    def __init__(self, *, count_input_tokens: Callable[[str], int]):
        if not callable(count_input_tokens):
            raise TypeError("A token counter is required")
        self._count_input_tokens = count_input_tokens

    @staticmethod
    def _input(messages: list[dict], manifest: dict):
        Manifest.model_validate(manifest)
        return approved_messages(messages)

    def count_tokens(self, messages: list[dict], manifest: dict) -> int:
        parsed = self._input(messages, manifest)
        text = serialize_context(parsed)
        try:
            tokens = self._count_input_tokens(text)
            if type(tokens) is not int or tokens < 0:
                raise ValueError
            return tokens
        except Exception:
            raise EngineUnavailableError("Risk token counter unavailable") from None

    def analyze(self, messages: list[dict], manifest: dict) -> dict:
        parsed = self._input(messages, manifest)
        try:
            return validated_verdict(classify(parsed), parsed)
        except Exception:
            # Failure must never become NORMAL or INSUFFICIENT.
            raise EngineUnavailableError("Risk rule analysis unavailable") from None
