import json
import unicodedata
from pathlib import Path

import pytest

from app.icebreakers import templates


def test_general_fallback_matches_be_baseline():
    repo_root = Path(__file__).resolve().parents[2]
    be_fallback = repo_root / "back/src/main/resources/icebreakers/general-ko-v1.json"
    assert templates.suggest() == json.loads(be_fallback.read_text(encoding="utf-8"))


@pytest.mark.parametrize(
    ("key", "question"),
    [
        ("MOVIE", "최근에 재미있게 본 영화가 있나요?"),
        ("MUSIC", "요즘 자주 듣는 노래가 있나요?"),
        ("READING", "최근에 재미있게 읽은 책이 있나요?"),
        ("TRAVEL", "기억에 남는 여행지가 있나요?"),
        ("FOOD", "요즘 가장 먹고 싶은 음식이 있나요?"),
        ("SPORTS", "요즘 즐겨 하는 운동이 있나요?"),
        ("CAFE", "좋아하는 카페 분위기가 있나요?"),
    ],
)
def test_each_allowed_overlap_uses_one_fixed_question(key, question):
    result = templates.suggest([key], (key,))
    assert result == {
        "questions": [question, *templates.GENERAL[1:]],
        "source": "GENERATED",
        "promptVersion": "icebreakers-shared-topic-ko-v1",
    }
    normalized = {
        unicodedata.normalize("NFKC", item).casefold() for item in result["questions"]
    }
    assert len(normalized) == 3


def test_duplicate_keys_and_multiple_overlaps_use_first_allowed_topic():
    result = templates.suggest(
        ["RELIGION", "MUSIC", "MUSIC", "MOVIE"], ["MOVIE", "RELIGION", "MUSIC", "MUSIC"]
    )
    assert result["questions"] == [templates.TOPICS["MUSIC"], *templates.GENERAL[1:]]


@pytest.mark.parametrize(
    ("mine", "partner"),
    [
        (["MOVIE"], ["MUSIC"]),
        (["RELIGION", "SMOKING"], ["RELIGION", "SMOKING"]),
        (["movie"], ["movie"]),
        (["전화번호 010-0000-0000"], ["전화번호 010-0000-0000"]),
        (None, ["MOVIE"]),
        (["MOVIE"], None),
        ([], ["MOVIE"]),
        ("MOVIE", ["MOVIE"]),
        (["MOVIE", {"key": "MOVIE", "public": False}], ["MOVIE"]),
    ],
)
def test_unusable_topics_return_general_fallback(mine, partner):
    assert templates.suggest(mine, partner) == templates.suggest()


@pytest.mark.parametrize(
    "broken_question",
    [
        "하루 중 가장 좋아하는 시간은 언제인가요？",
        "종교는 무엇인가요?",
        "이메일 test@example.invalid로 연락해요?",
        "010-0000-0000으로 전화해요?",
        "https://example.invalid를 볼까요?",
        "숨은\n개행이 있나요?",
        "가" * 201,
        None,
    ],
)
def test_rejected_template_returns_all_three_general_questions(monkeypatch, broken_question):
    monkeypatch.setitem(templates.TOPICS, "MOVIE", broken_question)
    assert templates.suggest(["MOVIE"], ["MOVIE"]) == templates.suggest()


def test_mutating_response_does_not_change_fallback():
    templates.suggest()["questions"].clear()
    assert templates.suggest()["questions"] == list(templates.GENERAL)
