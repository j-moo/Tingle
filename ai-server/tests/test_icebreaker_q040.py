import json
from pathlib import Path

import pytest

from app.icebreakers import templates


BE_RESOURCES = Path(__file__).resolve().parents[2] / "back/src/main/resources"
DATE_CASES = (
    ("rest", "DATE_REST", "쉬는 날에는 주로 어떻게 휴식을 취하세요?"),
    ("talk", "DATE_TALK", "식사하면서 가장 즐겁게 이야기하는 주제가 있나요?"),
    ("culture", "DATE_CULTURE", "전시와 공연 중에는 어느 쪽에 더 관심이 가세요?"),
    ("walk", "DATE_WALK", "산책과 여행 중에 요즘 더 끌리는 활동이 있나요?"),
    ("active", "DATE_ACTIVE", "요즘 새롭게 해보고 싶은 활동이 있나요?"),
)


@pytest.fixture(scope="module")
def be_fallback_questions():
    path = BE_RESOURCES / "icebreakers/general-ko-v1.json"
    return tuple(json.loads(path.read_text(encoding="utf-8"))["questions"])


def test_date_allowlist_covers_only_the_five_q040_options():
    path = BE_RESOURCES / "survey/questionnaires/SIS-Q-1.1-100.json"
    catalog = json.loads(path.read_text(encoding="utf-8"))
    q040 = next(q for q in catalog["questions"] if q["questionId"] == "Q040")
    assert [option["value"] for option in q040["options"]] == [
        code for code, _, _ in DATE_CASES
    ]
    assert set(templates.DATE_TOPICS) == {key for _, key, _ in DATE_CASES}


@pytest.mark.parametrize(
    ("key", "question"), [(key, question) for _, key, question in DATE_CASES]
)
def test_verified_date_topic_replaces_only_first_question(key, question, be_fallback_questions):
    questions = templates.suggest_date_questions(key)
    assert questions == (question, *be_fallback_questions[1:])
    assert len(set(questions)) == 3


@pytest.mark.parametrize(
    "invalid_key",
    [
        None,
        "",
        "unknown",
        "any",
        "rest",
        "talk",
        "culture",
        "walk",
        "active",
        "MOVIE",
        "DATE_UNKNOWN",
        "date_culture",
        "DATE_CULTURE ",
        "DATE_CULTURE 연락처 010-0000-0000",
        True,
        ["DATE_CULTURE"],
        ("DATE_CULTURE", "DATE_WALK"),
        ["culture", "walk"],
        {"topic": "DATE_CULTURE", "server_verified": True},
        {"Q040": ["culture"], "Q075": ["culture"]},
    ],
)
def test_unusable_date_key_uses_exact_be_fallback(invalid_key, be_fallback_questions):
    assert templates.suggest_date_questions(invalid_key) == be_fallback_questions


@pytest.mark.parametrize(
    "unsafe_question",
    ["하루 중 가장 좋아하는 시간은 언제인가요？", "연락처가 어떻게 되나요?"],
)
def test_rejected_date_template_uses_exact_be_fallback(
    monkeypatch, unsafe_question, be_fallback_questions
):
    monkeypatch.setitem(templates.DATE_TOPICS, "DATE_CULTURE", unsafe_question)
    assert templates.suggest_date_questions("DATE_CULTURE") == be_fallback_questions


def test_date_topics_are_separate_from_existing_hobbies():
    assert templates.suggest(["DATE_CULTURE"], ["DATE_CULTURE"]) == templates.suggest()
