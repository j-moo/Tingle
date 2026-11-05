"""승인된 공개 관심사 및 공통 데이트 활동 키로 고정 질문을 선택한다."""

import re
from typing import Literal, TypedDict
import unicodedata


# BE general-ko-v1.json의 질문과 버전.
GENERAL = (
    "요즘 즐겁게 하는 취미가 있나요?",
    "하루 중 가장 좋아하는 시간은 언제인가요?",
    "이번 주에 기대하는 작은 일이 있나요?",
)
GENERAL_VERSION = "icebreakers-general-ko-v1"
Q040_VERSION = "q040-template-v1"
SHARED_VERSION = "icebreakers-shared-topic-ko-v1"

# 참조 ZIP의 shared_topic_templates.py에 있는 허용 키와 문구.
TOPICS = {
    "MOVIE": "최근에 재미있게 본 영화가 있나요?",
    "MUSIC": "요즘 자주 듣는 노래가 있나요?",
    "READING": "최근에 재미있게 읽은 책이 있나요?",
    "TRAVEL": "기억에 남는 여행지가 있나요?",
    "FOOD": "요즘 가장 먹고 싶은 음식이 있나요?",
    "SPORTS": "요즘 즐겨 하는 운동이 있나요?",
    "CAFE": "좋아하는 카페 분위기가 있나요?",
}

# BE에서 전달한 Q040 공식 코드를 라우터가 변환한 내부 키.
DATE_TOPICS = {
    "DATE_REST": "쉬는 날에는 주로 어떻게 휴식을 취하세요?",
    "DATE_TALK": "식사하면서 가장 즐겁게 이야기하는 주제가 있나요?",
    "DATE_CULTURE": "전시와 공연 중에는 어느 쪽에 더 관심이 가세요?",
    "DATE_WALK": "산책과 여행 중에 요즘 더 끌리는 활동이 있나요?",
    "DATE_ACTIVE": "요즘 새롭게 해보고 싶은 활동이 있나요?",
}

_UNSAFE = re.compile(
    r"https?://|www\.|[\w-]+(?:\.[\w-]+)*\.[^\W\d_]{2,}|"
    r"(?:\d{1,3}\.){3}\d{1,3}|[\w.+-]+@[\w.-]+|\d[\d -]{7,}\d|"
    r"연락처|전화|카카오|카톡|계정|주소|실명|성함|종교|흡연|담배|건강|질병|"
    r"정치|성희롱|성관계|외모|연봉|송금|계좌|비밀번호|주민|학교|직장"
)


class IcebreakerResult(TypedDict):
    questions: list[str]
    source: Literal["GENERATED", "GENERAL_FALLBACK"]
    promptVersion: str


def _validate_questions(questions: tuple[str, str, str]) -> None:
    seen = set()
    for question in questions:
        if type(question) is not str:
            raise ValueError("Invalid question")
        normalized = unicodedata.normalize("NFKC", question).casefold()
        key = "".join(char for char in normalized if char.isalnum())
        if (
            not 1 <= len(question) <= 200
            or not key
            or key in seen
            or _UNSAFE.search(normalized)
            or any(unicodedata.category(char).startswith("C") for char in question)
        ):
            raise ValueError("Rejected question")
        seen.add(key)


def suggest_date_questions(
    verified_topic_key: str | None = None,
) -> tuple[str, str, str]:
    """BE가 권한과 공통 응답을 확인해 선택한 단일 DATE_* 키를 받는다.

    승인된 키가 없으면 호출자는 None을 전달한다. 반환값은 질문 문구다.
    """
    if type(verified_topic_key) is not str or verified_topic_key not in DATE_TOPICS:
        return GENERAL

    questions = (DATE_TOPICS[verified_topic_key], GENERAL[1], GENERAL[2])
    try:
        _validate_questions(questions)
    except ValueError:
        return GENERAL
    return questions


def suggest(
    my_approved_public_topics: list[str] | tuple[str, ...] | None = None,
    partner_approved_public_topics: list[str] | tuple[str, ...] | None = None,
) -> IcebreakerResult:
    """양쪽의 공개 허용 검사를 마친 키 목록을 호출자가 전달한다.

    None은 공개 여부가 확인되지 않았거나 목록이 없음을 뜻한다.
    """
    if (
        isinstance(my_approved_public_topics, (list, tuple))
        and isinstance(partner_approved_public_topics, (list, tuple))
        and all(type(key) is str for key in my_approved_public_topics)
        and all(type(key) is str for key in partner_approved_public_topics)
    ):
        partner_keys = set(partner_approved_public_topics)
        for key in my_approved_public_topics:
            if key in partner_keys and key in TOPICS:
                questions = (TOPICS[key], GENERAL[1], GENERAL[2])
                try:
                    _validate_questions(questions)
                except ValueError:
                    break
                return {
                    "questions": list(questions),
                    "source": "GENERATED",
                    "promptVersion": SHARED_VERSION,
                }

    return {
        "questions": list(GENERAL),
        "source": "GENERAL_FALLBACK",
        "promptVersion": GENERAL_VERSION,
    }
