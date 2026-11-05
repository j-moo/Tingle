from .dto import Evidence, Viewer

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
        topic = TOPIC_TEXT[item.topic]
        if item.kind == "SHARED_REPORTED_BEHAVIOR":
            sentence = f"두 분은 {topic}에 공통점이 있어요."
        elif item.kind == "MUTUAL_PREFERENCE_FIT":
            sentence = f"서로의 {topic}이 각자의 선호와 맞아요."
        elif item.kind == "ONE_WAY_PREFERENCE_FIT":
            owner = "A" if item.direction == "A_TO_B" else "B"
            if viewer == owner:
                sentence = f"상대의 {topic}이 당신의 선호와 맞아요."
            else:
                sentence = f"당신의 {topic}이 상대의 선호와 맞아요."
        else:
            sentence = f"{topic}에는 차이가 있어요."
        sentences.append(sentence)
        used.append(item.id)
    return " ".join(sentences), tuple(used)
