"""BE 문항별 최종 fact 계약. 승인된 고정 문장과 유한 조합만 등록한다."""

from dataclasses import dataclass
from itertools import combinations

from .dto import Evidence


@dataclass(frozen=True)
class FactItem:
    question: str
    preference_question: str
    topic: str
    label: str
    particle: str

    @property
    def source_ref(self) -> str:
        return f"survey:SIS-Q-1.1-100:{self.question}"

    @property
    def subject_particle(self) -> str:
        return "이" if self.particle == "은" else "가"


# BE 확정 source_ref의 정확한 문자열. 실제 producer 산출물 대조는 별도다.
FACT_ITEMS = {
    "e1": FactItem("Q034", "Q069", "contact", "연락 빈도", "는"),
    "e2": FactItem("Q036", "Q071", "planning", "만남 빈도", "는"),
    "e3": FactItem("Q038", "Q073", "planning", "데이트 계획 방식", "은"),
    "e4": FactItem("Q052", "Q087", "planning", "데이트 시간대", "는"),
    "e5": FactItem("Q053", "Q088", "activity", "하루 데이트 이동 범위", "는"),
}

TIME_SLOTS = (("morning", "오전"), ("afternoon", "오후"),
              ("evening", "저녁"), ("night", "늦은 밤"))


def _time_slot_fact(labels: tuple[str, ...]) -> str:
    # 승인된 네 표시 이름의 유한 조합만 초기 등록한다. 자유 문자열은 받지 않는다.
    if len(labels) == 1:
        times = labels[0]
    else:
        particle = "이나" if (ord(labels[-2][-1]) - ord("가")) % 28 else "나"
        times = ", ".join(labels[:-1]) + particle + " " + labels[-1]
    return f"두 분 다 {times}에 만나는 게 편하대요."


TIME_SLOT_FACTS = {
    tuple(code for code, _ in slots): _time_slot_fact(tuple(label for _, label in slots))
    for count in range(1, len(TIME_SLOTS) + 1)
    for slots in combinations(TIME_SLOTS, count)
}

# 입력 문장 → 공개 표현. 원응답을 읽거나 임의 문장 구간을 캡처하지 않는다.
# Q052는 BE가 실제 교집합만 요약한다. AI는 원응답 없이 승인된 15개 정렬
# 문장과 irregular 단독 문장만 대조하며, irregular 혼합 문장은 거부한다.
SHARED_FACTS = {
    "Q034": {
        text: text for text in (
            "두 분 다 매일 연락하지 않아도 괜찮대요.",
            "두 분 다 하루 한 번 정도 연락하는 게 좋대요.",
            "두 분 다 하루에 몇 번씩 연락하는 게 좋대요.",
            "두 분 다 틈날 때마다 연락하는 게 좋대요.",
            "두 분 다 거의 실시간으로 연락하는 게 좋대요.",
        )
    },
    "Q036": {
        text: text for text in (
            "두 분 다 격주 정도로 만나도 괜찮대요.",
            "두 분 다 일주일에 한 번쯤 만나고 싶대요.",
            "두 분 다 일주일에 두 번쯤 만나고 싶대요.",
            "두 분 다 일주일에 세 번쯤 만나고 싶대요.",
            "두 분 다 일주일에 네 번 이상 만나고 싶대요.",
        )
    },
    "Q038": {
        text: text for text in (
            "두 분 다 데이트는 당일에 정하는 편이래요.",
            "두 분 다 큰 방향만 정해 두고 당일에 고르는 편이래요.",
            "두 분 다 데이트 계획은 그때그때 다르대요.",
            "두 분 다 데이트는 대부분 미리 정해 두는 편이래요.",
            "두 분 다 시간과 장소까지 미리 꼼꼼히 정하는 편이래요.",
        )
    },
    "Q052": {
        **{text: text for text in TIME_SLOT_FACTS.values()},
        "두 분 다 일정이 불규칙한 편이래요.": "두 분 다 일정이 불규칙한 편이래요.",
    },
    "Q053": {
        text: text for text in (
            "두 분 다 하루 데이트는 동네 안에서 다니는 게 좋대요.",
            "두 분 다 하루 데이트는 한곳에 오래 머무는 게 좋대요.",
            "두 분 다 하루 데이트는 한 지역 안에서 여러 곳을 다니는 게 좋대요.",
            "두 분 다 하루 데이트는 근교 정도까지 이동하는 게 좋대요.",
            "두 분 다 하루 데이트는 먼 곳까지 이동하는 게 좋대요.",
        )
    },
}


def fact_item(item: Evidence) -> FactItem | None:
    """ID·문항쌍·topic·kind·direction·고정 문법이 모두 일치할 때만 해석한다."""
    spec = FACT_ITEMS.get(item.id)
    if spec is None or spec.topic != item.topic:
        return None
    label = spec.label
    if item.kind == "ONE_WAY_PREFERENCE_FIT":
        if item.direction == "A_TO_B":
            expected = f"B가 바라는 {label}에 A가 잘 맞아요."
        elif item.direction == "B_TO_A":
            expected = f"A가 바라는 {label}에 B가 잘 맞아요."
        else:
            return None
    elif item.direction != "BOTH":
        return None
    elif item.kind == "MUTUAL_PREFERENCE_FIT":
        expected = f"{label}{spec.particle} 두 분이 서로 바라는 정도가 잘 맞아요."
    elif item.kind == "DIFFERENCE":
        expected = f"{label}{spec.particle} 두 분 생각이 달라요."
    elif item.kind == "SHARED_REPORTED_BEHAVIOR":
        return spec if item.fact in SHARED_FACTS.get(spec.question, {}) else None
    else:
        return None
    return spec if item.fact == expected else None


def shared_phrasing(item: Evidence, spec: FactItem) -> str:
    return SHARED_FACTS[spec.question][item.fact]
