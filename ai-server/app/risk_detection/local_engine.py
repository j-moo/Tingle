"""Offline rules; tokenizer-free by default, with an optional exact counter."""

from typing import Callable

from app.risk_detection.http_api import Manifest
from app.risk_detection.rule_engine import classify
from app.risk_detection.tokenization import serialize_context
from app.risk_detection.validation import approved_messages, validated_verdict


class EngineUnavailableError(RuntimeError):
    """Sanitized failure mapped to HTTP 503 by the existing router."""


class LocalRuleRiskEngine:
    """RiskEngine implementation with no provider or storage.

    Plain rules use the existing 10-message/4,000-character input boundary and
    do not consume a model token budget. Optional tokenized releases retain the
    exact canonical-context counter and router's 3,000-token limit. No estimate
    or invented token count replaces a missing or failed counter.
    """

    def __init__(self, *, count_input_tokens: Callable[[str], int] | None = None):
        if count_input_tokens is not None and not callable(count_input_tokens):
            raise TypeError("A token counter is required")
        self._count_input_tokens = count_input_tokens

    @property
    def requires_token_budget(self) -> bool:
        return self._count_input_tokens is not None

    @staticmethod
    def _input(messages: list[dict], manifest: dict):
        Manifest.model_validate(manifest)
        return approved_messages(messages)

    def count_tokens(self, messages: list[dict], manifest: dict) -> int:
        if self._count_input_tokens is None:
            raise EngineUnavailableError("No model token budget for local rules")
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
