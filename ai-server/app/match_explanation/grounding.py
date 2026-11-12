"""Check every generated clause against renderings of its cited public evidence."""

import re

from .dto import Evidence, ModelInput, ModelOutput, Viewer
from .fallback import rule_fallback


SHARED_ACTIVITY_FACT = re.compile(
    r"(?:두 사람|A와 B) 모두 (공원 산책을|산책을|독서를|운동을) (선호한|즐긴)다고 응답했다\."
)


# Each full match enables only the corresponding renderings. Captures are limited
# to explicit alternatives, so no arbitrary fragment of a fact becomes public text.
DETAIL_PHRASINGS = {
    ("MUTUAL_PREFERENCE_FIT", "contact"): (
        (
            r"두 사람 모두 (짧게 자주|여유 있게) 연락하는 방식을 선호하고 "
            r"각자의 실제 연락 방식도 상대의 선호와 맞는다\.",
            ("{} 연락하는 방식이 서로의 선호와 맞아요.",
             "{} 연락하는 방식이 서로의 선호와 맞고,"),
        ),
    ),
    ("SHARED_REPORTED_BEHAVIOR", "contact"): (
        (
            r"(?:두 사람|A와 B) 모두 (짧게 자주|여유 있게) 연락하는 편(?:이다|이라고 응답했다)\.",
            ("두 분은 {} 연락하는 편이라는 공통점이 있어요.",
             "두 분 모두 {} 연락해요. 공통점은 바로 이 점이에요."),
        ),
    ),
    ("SHARED_REPORTED_BEHAVIOR", "planning"): (
        (
            r"(?:두 사람|A와 B) 모두 약속을 (미리|그때그때) 정하는 편(?:이다|이라고 응답했다)\.",
            ("두 분은 약속 시간을 {} 정하는 편이라는 공통점이 있어요.",
             "약속을 {} 정한다는 공통점도 있어요."),
        ),
    ),
    ("SHARED_REPORTED_BEHAVIOR", "experience"): (
        (
            r"(?:두 사람|A와 B) 모두 (익숙한|새로운) 활동을 찾는 편(?:이다|이라고 응답했다)\.",
            ("두 분은 {} 활동을 찾는 편이라는 공통점이 있어요.",),
        ),
    ),
    ("DIFFERENCE", "planning"): (
        (
            r"약속을 준비하는 시점에는 차이가 있다\.",
            ("약속을 준비하는 시점에는 차이가 있어요.",
             "약속 준비 시점이 달라요.",
             "약속을 준비하는 시점에는 차이가 있어 함께 맞춰 보면 좋겠어요."),
        ),
    ),
    ("DIFFERENCE", "experience"): (
        (
            r"A는 익숙한 활동을 주로 찾고 B는 새로운 활동을 찾는 편이어서 "
            r"경험을 선택하는 방식에 차이가 있다\.",
            ("익숙한 활동과 새로운 활동을 찾는 정도에는 차이가 있어 함께 골라보면 좋겠어요.",),
        ),
    ),
}


def _claim_phrasings(viewer: Viewer, item: Evidence) -> set[str]:
    canonical, _ = rule_fallback(viewer, (item,))
    phrases = {canonical}
    # D01: this exact public fact entails reading enjoyment, not preference alone.
    if (item.kind, item.topic, item.direction, item.fact) == (
        "SHARED_REPORTED_BEHAVIOR", "activity", "BOTH",
        "두 사람 모두 독서를 즐긴다고 응답했다.",
    ):
        phrases.add("두 분 모두 책 읽는 활동을 즐겨요.")
    for pattern, variants in DETAIL_PHRASINGS.get((item.kind, item.topic), ()):
        match = re.fullmatch(pattern, item.fact)
        if match:
            phrases.update(variant.format(*match.groups()) for variant in variants)

    if (item.kind, item.topic) == ("SHARED_REPORTED_BEHAVIOR", "activity"):
        match = SHARED_ACTIVITY_FACT.fullmatch(item.fact)
        if match:
            activity, verb = match.groups()
            phrases.add(f"두 분 모두 {activity} {verb}다는 공통점이 있어요.")
            # Preserve the existing variants for each activity and response.
            if activity == "산책을":
                phrases.add("두 분 모두 산책을 즐긴다는 연결점이 있어요.")
            elif (activity, verb) == ("공원 산책을", "즐긴"):
                phrases.add("두 분은 공원에서 산책하는 활동을 즐긴다는 공통점이 있어요.")

    if item.kind == "ONE_WAY_PREFERENCE_FIT":
        owner, other = ("A", "B") if item.direction == "A_TO_B" else ("B", "A")
        preference, behavior = ("당신", "상대") if viewer == owner else ("상대", "당신")
        if item.topic == "contact" and re.search(rf"{other}의 (?:실제 )?연락 빈도가 ", item.fact):
            phrases.add(f"{behavior}의 연락 빈도가 {preference}의 선호와 맞아요.")
        elif item.topic == "planning":
            phrases.add(f"{behavior}의 약속 준비 방식이 {preference}의 선호와 맞아요.")
        elif item.topic == "activity":
            subject = "당신이" if behavior == "당신" else "상대가"
            phrases.add(f"{subject} 즐기는 활동이 {preference}의 선호와 맞아요.")
    return phrases


def validate_grounding(output: ModelOutput, request: ModelInput) -> None:
    """Require full text coverage and one unambiguous claim per cited ID."""
    # The service supplies only selected model + display evidence and parsed IDs.
    phrases_by_id = {
        item.id: _claim_phrasings(request.viewer, item)
        for item in request.evidence if item.id in output.evidenceIds
    }
    remaining = output.text
    for _ in output.evidenceIds:
        # A rendering can include a fixed second sentence. Consume the longest
        # matching phrase as a unit, and never treat an unmatched suffix as style.
        matches = [
            (phrase, evidence_id)
            for evidence_id, phrases in phrases_by_id.items()
            for phrase in phrases
            if remaining == phrase or remaining.startswith(phrase + " ")
        ]
        longest = max((len(phrase) for phrase, _ in matches), default=0)
        best = [(phrase, evidence_id) for phrase, evidence_id in matches if len(phrase) == longest]
        if len(best) != 1:
            raise ValueError("INVALID_OUTPUT")
        phrase, evidence_id = best[0]
        del phrases_by_id[evidence_id]
        remaining = remaining[len(phrase) + 1:]
    if remaining or not output.text.endswith("."):
        raise ValueError("INVALID_OUTPUT")
