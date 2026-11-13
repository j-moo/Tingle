"""BE !331 Cf policy: reject raw format characters without changing normalization."""

import unicodedata

from pydantic import ValidationError
import pytest

from app.icebreakers.contract import IcebreakerResponse
from app.icebreakers.prompts import GMS_PROMPT_VERSION

QUESTIONS = ["산책할 때 어떤 풍경을 좋아하세요?", "여행 중 가장 즐거운 순간은 언제인가요?", "걷기 좋은 계절은 언제라고 생각하세요?"]
CF_CHARACTERS = ("\u200b", "\u200c", "\u200d", "\u2060", "\ufeff", "\u00ad", "\u061c", "\u202e", "\u2066", "\U000e0001")


def generated(questions):
    return IcebreakerResponse(questions=questions, source="GENERATED", promptVersion=GMS_PROMPT_VERSION)


@pytest.mark.parametrize("char", CF_CHARACTERS, ids=lambda char: f"U+{ord(char):04X}")
@pytest.mark.parametrize("position", ["start", "middle", "end"])
def test_cf_in_raw_question_is_rejected(char, position):
    assert unicodedata.category(char) == "Cf"
    first = QUESTIONS[0]
    first = {"start": char + first, "middle": first[:2] + char + first[2:], "end": first + char}[position]
    with pytest.raises(ValidationError):
        generated([first, *QUESTIONS[1:]])


def test_every_python_unicode_cf_character_is_rejected():
    # Cover supplementary-plane tags as well as the familiar zero-width characters.
    for point in range(0x110000):
        char = chr(point)
        if unicodedata.category(char) == "Cf":
            with pytest.raises(ValidationError):
                generated([QUESTIONS[0][:2] + char + QUESTIONS[0][2:], *QUESTIONS[1:]])


def test_raw_cf_is_rejected_before_normalization(monkeypatch):
    def forbidden(*_): pytest.fail("Cf must be rejected at the raw-text boundary")
    with monkeypatch.context() as patch:
        patch.setattr("app.icebreakers.contract.unicodedata.normalize", forbidden)
        with pytest.raises(ValidationError):
            generated(["\u200b" + QUESTIONS[0], *QUESTIONS[1:]])


def test_normal_korean_and_existing_normalization_are_preserved():
    assert generated(QUESTIONS).questions == QUESTIONS
    assert generated(["  " + QUESTIONS[0] + "  ", *QUESTIONS[1:]]).questions == QUESTIONS
    # Normalize for unsafe-word and duplicate checks, preserving the returned text.
    decomposed = unicodedata.normalize("NFD", QUESTIONS[0])
    assert generated([decomposed, *QUESTIONS[1:]]).questions[0] == decomposed
    with pytest.raises(ValidationError):
        generated([QUESTIONS[0], decomposed, QUESTIONS[2]])
    with pytest.raises(ValidationError):
        generated([unicodedata.normalize("NFD", "연락처를 알려 주실래요?"), *QUESTIONS[1:]])


@pytest.mark.parametrize("version", [None, "", "icebreakers-gms-", "icebreakers-gms-invalid", "icebreakers-gms-0000000000000000"])
def test_generated_response_requires_the_exact_prompt_version(version):
    with pytest.raises(ValidationError):
        IcebreakerResponse(questions=QUESTIONS, source="GENERATED", promptVersion=version)
