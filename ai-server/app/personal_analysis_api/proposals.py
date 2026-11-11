"""UNAPPROVED presentation for tests and BE review samples only.

Importing this module does not register a policy, runtime, verifier or router.
Numeric fit continues to select/order gaps in the unchanged core; this proposed
wording omits fit from text. Template IDs and priorities remain candidates.
"""

from math import isfinite
from types import MappingProxyType

from app.personal_analysis import core
from app.personal_analysis.reference import LABELS

from .adapter import (
    AnswerContext, PresentationPolicy, PresentationUnavailable, difference_evidence,
    format_expression_reading, question_id,
)
from .dto import Reading

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
    return Reading(
        key=f"self-preference-{question_id(row['selfId'])}-{question_id(row['id'])}",
        templateId="PA-D-SELF-WISH-FIT",
        text=f"{row['label']}: 나의 응답은 ‘{row['self']}’이고, 상대에게 바라는 응답은 ‘{row['wish']}’입니다.",
        evidenceQuestionIds=evidence, priority=2 - index,
    )


def proposed_label_only_policy() -> PresentationPolicy:
    """Explicit opt-in for review. Never used by PersonalAnalysisAdapter defaults."""
    return PresentationPolicy(
        reading_template_ids=CANDIDATE_READING_TEMPLATE_IDS,
        difference_reading=proposed_label_only_difference,
        reading_text_formatters={"PA-R-EXPRESSION-01": format_expression_reading},
    )
