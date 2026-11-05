"""Offline engine boundary checks; reuse the existing HTTP DTOs and limits."""

from app.risk_detection.http_api import Message, Verdict


class RiskInputError(ValueError):
    """Invalid approved context, without echoing message contents."""


def approved_messages(messages: list[dict]) -> list[Message]:
    """Adapt the read-only local draft's checks to the current wire contract."""
    try:
        if type(messages) is not list or not 1 <= len(messages) <= 10:
            raise ValueError
        parsed = [Message.model_validate(message) for message in messages]
        previous, fresh_seen = 0, False
        for message in parsed:
            if (message.sequence <= previous or (fresh_seen and not message.fresh)
                    or not message.text.strip()):
                raise ValueError
            previous = message.sequence
            fresh_seen |= message.fresh
        if not fresh_seen or sum(len(message.text) for message in parsed) > 4000:
            raise ValueError
        return parsed
    except (ValueError, TypeError):
        raise RiskInputError("Invalid approved context") from None


def validated_verdict(result: dict, messages: list[Message]) -> dict:
    """Keep direct engine use within the same finding mappings as HTTP."""
    verdict = Verdict.model_validate(result)
    if (verdict.kind == "RISK") != bool(verdict.findings):
        raise ValueError("Invalid verdict")
    fresh = {message.sequence for message in messages if message.fresh}
    supported = {
        ("FRAUD", "MONEY_REQUEST", "RECIPIENT"),
        ("HARASSMENT", "HARASSMENT", "BOTH"),
        ("THREAT", "HARASSMENT", "BOTH"),
        ("OTHER", "PERSONAL_INFO", "RECIPIENT"),
        ("OTHER", "OFF_PLATFORM", "RECIPIENT"),
    }
    seen = set()
    for finding in verdict.findings:
        mapping = (finding.category, finding.noticeType, finding.audience)
        key = (finding.sequence, *mapping)
        if mapping not in supported or finding.sequence not in fresh or key in seen:
            raise ValueError("Invalid finding")
        seen.add(key)
    return verdict.model_dump()
