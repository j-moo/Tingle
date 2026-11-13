from .dto import Evidence, Viewer
from .facts import fact_item, shared_phrasing
from .validation import supported_fact

TOPIC_TEXT = {
    "contact": "연락 방식",
    "planning": "약속을 준비하는 방식",
    "activity": "즐기는 활동",
    "experience": "새로운 경험을 찾는 방식",
}


def rule_fallback(
    viewer: Viewer, evidence: tuple[Evidence, ...],
) -> tuple[str, tuple[str, ...]]:
    """수치·원응답을 복원하지 않고 검증된 종류/주제/방향만 서술한다."""
    matches = [item for item in evidence if item.kind != "DIFFERENCE"][:2]
    differences = [item for item in evidence if item.kind == "DIFFERENCE"][:1]
    sentences = []
    used = []
    for item in (*matches, *differences):
        if not supported_fact(item):
            continue
        spec = fact_item(item)
        topic = spec.label if spec else TOPIC_TEXT[item.topic]
        particle = spec.subject_particle if spec else "이"
        if item.kind == "SHARED_REPORTED_BEHAVIOR":
            sentence = (shared_phrasing(item, spec) if spec
                        else f"두 분은 {topic}에 공통점이 있어요.")
        elif item.kind == "MUTUAL_PREFERENCE_FIT":
            sentence = f"서로의 {topic}{particle} 각자의 선호와 맞아요."
        elif item.kind == "ONE_WAY_PREFERENCE_FIT":
            subject = "A" if item.direction == "A_TO_B" else "B"
            if viewer == subject:
                sentence = f"당신의 {topic}{particle} 상대의 선호와 맞아요."
            else:
                sentence = f"상대의 {topic}{particle} 당신의 선호와 맞아요."
        else:
            sentence = f"{topic}에는 차이가 있어요."
        sentences.append(sentence)
        used.append(item.id)
    return " ".join(sentences), tuple(used)
