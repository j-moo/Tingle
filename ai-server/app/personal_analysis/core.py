"""Python port of engine100 and type-recommendations100 personal functions.

Inputs use the validated engine ordinals (1..100). Arithmetic and stable sorting
follow JS operation order. Pair matching and real-person eligibility are excluded.
"""

from copy import deepcopy
from math import floor

from .reference import DOMAINS, LABELS, MODEL, QUESTIONS

SCALES = {
    "E": ([1, 6, 11, 16], [6, 16]),
    "A": ([2, 7, 12, 17], [7, 17]),
    "C": ([3, 8, 13, 18], [8, 18]),
    "N": ([4, 9, 14, 19], [9, 19]),
    "I": ([5, 10, 15, 20], [10, 15, 20]),
    "avoidance": ([21, 22, 23, 24, 25, 26], [21, 22, 23, 24]),
    "anxiety": ([27, 28, 29], []),
}
TRAITS = ["E", "A", "C", "I"]


def known(value) -> bool:
    return value is not None and value != "unknown" and value != "any"


def numeric(value) -> bool:
    return type(value) in (int, float)


def js_sum(values):
    """Sequential JS reduce addition (Python 3.12 sum compensates float errors)."""
    total = 0
    for value in values:
        total += value
    return total


def profile_from_answers(answers: dict) -> dict:
    features = {}
    for key, (ids, reverse) in SCALES.items():
        features[key] = (
            (js_sum(6 - answers[i] if i in reverse else answers[i] for i in ids) / len(ids) - 1) * 25
            if all(numeric(answers.get(i)) for i in ids) else None
        )
    for i in range(30, 61):
        features[f"q{i}"] = deepcopy(answers[i]) if known(answers.get(i)) else None
    return features


def feature_distance(key: str, actual, center) -> float:
    if key == "q40":
        left, right = set(actual), set(center)
        union = left | right
        return 1 - len(left & right) / len(union) if union else 0
    if key in ("q44", "q45", "q53"):
        return int(actual != center)
    return abs(actual - center) / (4 if key.startswith("q") else 100)


def classify_profile(features: dict) -> dict:
    usable = [k for k in MODEL["weights"] if known(features.get(k))]
    coverage = js_sum(MODEL["weights"][k] for k in usable)
    groups_present = sum(any(k in usable for k in keys) for keys in MODEL["groups"].values())
    if coverage + 1e-10 < 0.8 or groups_present < 6:
        return dict(status="insufficient", primary=None, ranking=[], coverage=coverage,
                    groupsPresent=groups_present, features=deepcopy(features))
    ranking = []
    for prototype in MODEL["prototypes"]:
        distance = js_sum(
            MODEL["weights"][k] * feature_distance(k, features[k], prototype["features"][k])
            for k in usable
        ) / coverage
        ranking.append({**deepcopy(prototype), "distance": distance, "fit": 100 * (1 - distance)})
    # All type codes are ASCII uppercase; Python order equals JS localeCompare here.
    ranking.sort(key=lambda p: (p["distance"], p["type"]))
    tied = [p for p in ranking if abs(p["distance"] - ranking[0]["distance"]) <= 1e-10]
    gap = ranking[0]["fit"] - ranking[1]["fit"]
    return dict(features=deepcopy(features), ranking=ranking, coverage=coverage,
                groupsPresent=groups_present, gap=gap, tied=tied,
                primary=ranking[0] if len(tied) == 1 else None,
                status="tied" if len(tied) > 1 else "borderline" if gap <= 3 else "assigned")


def classify_answers(answers: dict) -> dict:
    return classify_profile(profile_from_answers(answers))


def target_feature(question_id: int) -> str:
    return TRAITS[question_id - 61] if question_id <= 64 else f"q{question_id - 35}"


def interval_fit(actual, low, high, minimum, maximum):
    low, high = min(maximum, max(minimum, low)), min(maximum, max(minimum, high))
    if low == minimum and high == maximum:
        return None
    distance = max(low - actual, 0, actual - high)
    scale = max(low - minimum, maximum - high)
    return min(1, max(0, 1 - distance / scale)) if scale else None


def wish_fit(question_id: int, wish, actual, tolerance="some"):
    if not known(wish) or not known(actual):
        return None
    if question_id <= 64:
        span = {"close": 0, "some": 10, "wide": 20}.get(tolerance, 10)
        band = {"low": (0, 40), "mid": (30, 70), "high": (60, 100)}.get(wish)
        return interval_fit(actual, band[0] - span, band[1] + span, 0, 100) if band else None
    self_question = QUESTIONS[question_id - 35]
    if all(numeric(o["value"]) for o in self_question["options"]):
        span = {"close": 0, "some": 1, "wide": 2}.get(tolerance, 1)
        return interval_fit(actual, 1 if question_id == 89 else wish - span, wish + span, 1, 5)
    allowed = wish if isinstance(wish, list) else [wish]
    values = actual if isinstance(actual, list) else [actual]
    if question_id == 87:
        values = [v for v in values if v != "irregular"]
        allowed = [v for v in allowed if v != "irregular"]
        if not values or not allowed:
            return None
    return int(any(v in allowed for v in values))


def answer_label(question_id: int, value) -> str:
    if value is None or value == "unknown":
        return "미측정"
    if value == "any":
        return "제외 조건 없음" if question_id == 97 else "모든 영역을 고르게" if question_id == 96 else "크게 상관없음"
    question = QUESTIONS.get(question_id)
    if question is None:
        return "미측정"
    labels = {o["value"]: o["label"] for o in question["options"]}
    if isinstance(value, list):
        return " · ".join(labels.get(v, "") for v in value)
    return labels.get(value, "미측정")


def readings(answers: dict) -> list[dict]:
    out = []
    a = answers
    label = lambda i: answer_label(i, a.get(i))
    high = lambda i: numeric(a.get(i)) and a[i] >= 4
    low = lambda i: numeric(a.get(i)) and a[i] <= 2

    def add(key, title, text, ids, priority=1):
        if all(known(a.get(i)) for i in ids):
            out.append(dict(key=key, title=title, text=text, priority=priority, evidence=[
                dict(id=i, question=QUESTIONS[i]["text"], answer=label(i)) for i in ids
            ]))

    if high(30) and low(32):
        add("expression", "먼저 다가가지만, 좋아한다는 말은 자주 하지 않아요.",
            "관계를 시작하는 행동과 말로 마음을 전하는 빈도가 다르게 나타났어요. 말 대신 어떤 행동으로 표현하는지 함께 이야기해볼 수 있어요.", [30, 32], 10)
    elif high(32) and low(33):
        add("expression", "애정 표현은 자주 하고, 개인적인 고민은 골라서 나눠요.",
            "좋아한다는 말을 하는 것과 내 고민을 공개하는 것은 다르게 선택했어요. 이를 마음을 여는 속도라고 단정하지 않아요.", [32, 33], 10)
    else:
        add("expression", "좋아하는 마음을 말로 전하는 편이에요." if high(32) else
            "좋아하는 마음을 자주 말하는 편은 아니에요." if low(32) else "애정 표현과 고민 공유를 상황에 맞춰 선택해요.",
            f"말로 표현하는 빈도는 ‘{label(32)}’, 고민을 나누는 범위는 ‘{label(33)}’라고 답했어요.", [32, 33], 3)
    if high(42) and high(43):
        add("conflict", "서운함은 빨리 이야기하고, 감정이 커지면 잠깐 쉬고 싶어요.",
            "대화를 시작하는 시점과 대화 중 필요한 휴식은 별개예요. 잠시 멈출 때 다시 이야기할 시간을 정하면 기대를 맞추기 쉬워요.", [42, 43], 10)
    elif low(42) and low(43):
        add("conflict", "생각을 정리한 뒤, 시작한 대화는 이어가고 싶어요.",
            "바로 말을 꺼내기보다 준비할 시간이 필요하고, 대화가 시작되면 계속 이야기하는 쪽을 골랐어요.", [42, 43], 9)
    else:
        add("conflict", "갈등 대화에서 시작 시점과 쉬는 방식이 중요해요.",
            f"대화 시작은 ‘{label(42)}’, 감정이 올라왔을 때는 ‘{label(43)}’가 편하다고 답했어요.", [42, 43], 3)
    if high(38) and high(39):
        add("experience", "새로운 곳에 가는 것을 좋아하고, 약속은 미리 준비해요.",
            "경험의 새로움과 일정의 즉흥성은 같지 않아요. 새로운 장소를 탐색하면서 시간과 장소는 정해두는 쪽이에요.", [38, 39], 9)
    else:
        add("experience", "만나기 전에 시간과 장소를 정해두는 편이에요." if high(38) else
            "당일의 상황을 보며 약속을 정하는 편이에요." if low(38) else "데이트의 계획과 장소를 유연하게 선택해요.",
            f"약속 준비는 ‘{label(38)}’, 장소 선택은 ‘{label(39)}’라고 답했어요.", [38, 39], 2)
    if low(37) and high(32):
        add("time", "각자 보내는 시간이 있어도 애정 표현은 자주 하고 싶어요.",
            "함께 있는 시간의 비중과 마음을 표현하는 빈도를 다르게 선택했어요. 각자 시간을 보낸다는 이유로 마음이 적다고 해석하지 않아요.", [37, 32], 9)
    else:
        add("time", "연락하는 빈도와 만나는 빈도를 따로 정해요.",
            f"연락은 ‘{label(34)}’, 만남은 ‘{label(36)}’를 골랐어요. 서로 단위가 다른 항목이므로 선택 단계의 차이로 선호를 단정하지 않아요.", [34, 36], 1)
    add("support", "상대가 힘들 때 내가 먼저 하는 일이 있어요.",
        f"도움의 첫 반응은 ‘{label(45)}’, 다툰 뒤에는 ‘{label(44)}’를 선택했어요. 상대가 받고 싶은 방식도 확인해보면 좋아요.", [45, 44], 2)
    # Stable: equal priorities retain expression/conflict/experience/time/support order.
    return sorted(out, key=lambda r: -r["priority"])[:3]


def preference_gaps(answers: dict) -> list[dict]:
    gaps = []
    for i in range(65, 96):
        actual, wish = answers.get(i - 35), answers.get(i)
        if not known(actual) or not known(wish):
            continue
        fit = wish_fit(i, wish, actual, answers.get(98))
        if fit is not None and fit < 1:
            gaps.append(dict(id=i, selfId=i - 35, label=LABELS[f"q{i - 35}"],
                             self=answer_label(i - 35, actual), wish=answer_label(i, wish), fit=fit))
    return sorted(gaps, key=lambda r: r["fit"])[:2]


def value_label(feature: str, value) -> str:
    # JS Math.round uses ties toward +infinity; Python round uses ties to even.
    return answer_label(int(feature[1:]), value) if feature.startswith("q") else f"{LABELS[feature]} {floor(value + 0.5)}점"


def reference_reasons(rows: list[dict], answers: dict) -> list[dict]:
    priority = answers.get(96) if isinstance(answers.get(96), list) else []
    ordered = sorted(rows, key=lambda r: (-r["fit"], -int(r["domain"] in priority)))
    used, out = set(), []
    for row in [r for r in ordered if r["fit"] >= 0.7][:2]:
        used.add(row["feature"])
        out.append(dict(title=f"{row['label']}: 내 선호와 가까워요",
                        text=f"나는 ‘{answer_label(row['id'], row['wish'])}’를 골랐고, 이 유형의 초기 대표값은 ‘{value_label(row['feature'], row['actual'])}’예요."))
    differences = sorted((r for r in rows if r["fit"] < 1 and r["feature"] not in used), key=lambda r: r["fit"])
    if differences:
        row = differences[0]
        out.append(dict(title="함께 살펴볼 차이",
                        text=f"{row['label']}에서 내 선호는 ‘{answer_label(row['id'], row['wish'])}’, 유형 대표값은 ‘{value_label(row['feature'], row['actual'])}’예요."))
    return out[:3]


def type_recommendations(answers: dict) -> dict:
    candidates = []
    for prototype in MODEL["prototypes"]:
        rows = []
        for i in range(61, 96):
            feature = target_feature(i)
            if feature not in MODEL["weights"] or not known(answers.get(i)):
                continue
            fit = wish_fit(i, answers[i], prototype["features"][feature], answers.get(98))
            if fit is None:
                continue
            domain = next(d["id"] for d in DOMAINS if i in d["ids"])
            rows.append(dict(id=i, feature=feature, label=LABELS[feature], domain=domain,
                             wish=deepcopy(answers[i]), actual=deepcopy(prototype["features"][feature]), fit=fit))
        domains = []
        for domain in DOMAINS:
            items = [r for r in rows if r["domain"] == domain["id"]]
            if items:
                weight = 2 if isinstance(answers.get(96), list) and domain["id"] in answers[96] else 1
                domains.append(dict(id=domain["id"], name=domain["name"],
                                    fit=js_sum(r["fit"] for r in items) / len(items), weight=weight))
        if domains:
            score = 100 * js_sum(d["fit"] * d["weight"] for d in domains) / js_sum(d["weight"] for d in domains)
            candidates.append({**deepcopy(prototype), "score": score, "referenceMode": "partner-preferences",
                               "rows": rows, "domains": domains, "reasons": reference_reasons(rows, answers)})
    mode = "partner-preferences"
    if not candidates:
        mode = "self-similarity"
        classification = classify_answers(answers)
        for prototype in classification["ranking"]:
            rows = [dict(feature=k, label=LABELS[k], self=deepcopy(classification["features"][k]),
                         actual=deepcopy(prototype["features"][k]),
                         fit=1 - feature_distance(k, classification["features"][k], prototype["features"][k]))
                    for k in MODEL["weights"] if known(classification["features"].get(k))]
            nearest = sorted(rows, key=lambda r: -r["fit"])[:2]
            reasons = [dict(title=f"나와 비슷한 {r['label']}",
                            text=f"내 응답은 ‘{value_label(r['feature'], r['self'])}’, 이 유형의 초기 대표값은 ‘{value_label(r['feature'], r['actual'])}’예요. 상대 선호가 없어 나와의 유사성으로 제시했어요.") for r in nearest]
            candidates.append({**deepcopy(prototype), "score": prototype["fit"], "referenceMode": mode,
                               "rows": rows, "domains": [], "reasons": reasons})
    candidates.sort(key=lambda p: (-p["score"], p["type"]))
    return dict(version="SIS-TYPE-REF-1", mode=mode, all=candidates, selected=candidates[:3])
