"""Shared v2 presentation policy for personal analysis in every environment.

Importing this module does not register a policy, runtime, verifier or router.
Numeric fit continues to select/order gaps in the unchanged core; this proposed
wording omits fit from text. Wording review is a team process, not a runtime gate.
"""

from math import isfinite
from types import MappingProxyType

from app.personal_analysis import core
from app.personal_analysis.reference import LABELS

from .adapter import (
    AnswerContext, PresentationPolicy, PresentationUnavailable, difference_evidence,
    format_expression_reading, question_id, verified_reading_labels,
)
from .dto import Reading

PROPOSAL_REVIEW_VERSION = "mr357-wording-review-20261115-v2"

CANDIDATE_READING_TEMPLATE_IDS = MappingProxyType({
    ("expression", "먼저 다가가지만, 좋아한다는 말은 자주 하지 않아요."): "PA-R-EXPRESSION-01",
    ("expression", "애정 표현은 자주 하고, 개인적인 고민은 골라서 나눠요."): "PA-R-EXPRESSION-02",
    ("expression", "좋아하는 마음을 말로 전하는 편이에요."): "PA-R-EXPRESSION-03",
    ("expression", "좋아하는 마음을 자주 말하는 편은 아니에요."): "PA-R-EXPRESSION-04",
    ("expression", "애정 표현과 고민 공유를 상황에 맞춰 선택해요."): "PA-R-EXPRESSION-05",
    ("conflict", "서운함은 빨리 이야기하고, 감정이 커지면 잠깐 쉬고 싶어요."): "PA-R-CONFLICT-01",
    ("conflict", "생각을 정리한 뒤, 시작한 대화는 이어가고 싶어요."): "PA-R-CONFLICT-02",
    ("conflict", "갈등 대화에서 시작 시점과 쉬는 방식이 중요해요."): "PA-R-CONFLICT-03",
    ("experience", "새로운 곳에 가는 것을 좋아하고, 약속은 미리 준비해요."): "PA-R-EXPERIENCE-01",
    ("experience", "만나기 전에 시간과 장소를 정해두는 편이에요."): "PA-R-EXPERIENCE-02",
    ("experience", "당일의 상황을 보며 약속을 정하는 편이에요."): "PA-R-EXPERIENCE-03",
    ("experience", "데이트의 계획과 장소를 유연하게 선택해요."): "PA-R-EXPERIENCE-04",
    ("time", "각자 보내는 시간이 있어도 애정 표현은 자주 하고 싶어요."): "PA-R-TIME-01",
    ("time", "연락하는 빈도와 만나는 빈도를 따로 정해요."): "PA-R-TIME-02",
    ("support", "상대가 힘들 때 내가 먼저 하는 일이 있어요."): "PA-R-SUPPORT-01",
})


def format_conflict_reading(row: dict, answers: AnswerContext) -> str:
    timing, pause = verified_reading_labels(row, answers, (42, 43))
    return (
        f"서운한 일이 생겼을 때 대화를 시작하는 시점에 대한 답변은 ‘{timing}’입니다. "
        f"대화 중 감정이 격해졌을 때 필요한 방식에 대한 답변은 ‘{pause}’입니다."
    )


def format_experience_reading(row: dict, answers: AnswerContext) -> str:
    preparation, place = verified_reading_labels(row, answers, (38, 39))
    return (
        f"데이트 시간과 장소를 미리 정하는 방식에 대한 답변은 ‘{preparation}’입니다. "
        f"익숙한 곳과 새로운 곳을 고르는 경향에 대한 답변은 ‘{place}’입니다."
    )


def format_time_reading(row: dict, answers: AnswerContext) -> str:
    contact, meeting = verified_reading_labels(row, answers, (34, 36))
    return (
        f"특별한 일이 없는 날 원하는 연락 빈도에 대한 답변은 ‘{contact}’입니다. "
        f"서로 시간이 될 때 원하는 만남 빈도에 대한 답변은 ‘{meeting}’입니다."
    )


def format_support_reading(row: dict, answers: AnswerContext) -> str:
    help_style, reconciliation = verified_reading_labels(row, answers, (45, 44))
    return (
        f"가까운 사람이 힘들다고 할 때 먼저 하는 일에 대한 답변은 ‘{help_style}’입니다. "
        f"연인과 다툰 뒤 먼저 하는 화해 행동에 대한 답변은 ‘{reconciliation}’입니다."
    )


def proposed_label_only_difference(row: dict, index: int, answers: AnswerContext) -> Reading:
    evidence = difference_evidence(row, answers)
    fit = row["fit"]
    if (
        type(index) is not int or index not in (0, 1)
        or type(fit) not in (int, float) or not isfinite(fit) or not 0 <= fit < 1
        or row["label"] != LABELS[f"q{row['selfId']}"]
        or row["self"] != core.answer_label(row["selfId"], answers[row["selfId"]])
        or row["wish"] != core.answer_label(row["id"], answers[row["id"]])
    ):
        raise PresentationUnavailable()
    text = f"{row['label']}: 나의 응답은 ‘{row['self']}’이고, 상대에게 바라는 응답은 ‘{row['wish']}’입니다."
    if row["id"] == 89:
        # The selected survey upper bound is not the Q098-expanded calculation interval.
        text = (
            f"{row['label']}: 나의 음주 빈도에 대한 답변은 ‘{row['self']}’입니다. "
            f"상대의 음주 빈도에 대해 받아들일 수 있는 상한으로 선택한 답변은 ‘{row['wish']}’입니다."
        )
    if row["id"] == 87 and any("irregular" in answers[q] for q in (52, 87)):
        text += " 이 항목의 계산에서는 ‘일정이 불규칙함’을 제외한 시간대를 비교했어요."
    return Reading(
        key=f"self-preference-{question_id(row['selfId'])}-{question_id(row['id'])}",
        templateId="PA-D-SELF-WISH-FIT",
        text=text,
        evidenceQuestionIds=evidence, priority=2 - index,
    )


def proposed_label_only_policy() -> PresentationPolicy:
    """Shared application policy; standalone adapter defaults remain unchanged."""
    return PresentationPolicy(
        reading_template_ids=CANDIDATE_READING_TEMPLATE_IDS,
        difference_reading=proposed_label_only_difference,
        reading_text_formatters={
            "PA-R-EXPRESSION-01": format_expression_reading,
            "PA-R-CONFLICT-01": format_conflict_reading,
            "PA-R-CONFLICT-03": format_conflict_reading,
            "PA-R-EXPERIENCE-02": format_experience_reading,
            "PA-R-EXPERIENCE-03": format_experience_reading,
            "PA-R-EXPERIENCE-04": format_experience_reading,
            "PA-R-TIME-02": format_time_reading,
            "PA-R-SUPPORT-01": format_support_reading,
        },
    )
