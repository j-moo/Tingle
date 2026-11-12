"""Optional GMS generation; fixed Q040 templates remain the failure path."""

import asyncio
import json
import re

from app.match_explanation.gms import GmsClient
from app.match_explanation.validation import unique_json_object

from . import templates
from .contract import CommonTopicKey, IcebreakerResponse
from .prompts import GENERATION_PROMPT, GMS_PROMPT_VERSION

_TOPIC_WORDS = {
    "rest": ("휴식", "쉬", "여유"), "talk": ("대화", "이야기", "주제"),
    "culture": ("전시", "공연", "문화", "영화", "책", "음악", "작품"),
    "walk": ("산책", "걷", "풍경", "여행"), "active": ("활동", "운동", "도전", "취미"),
}
_UNSUPPORTED = re.compile(r"[a-zA-Z0-9]|두 분|두 사람|당신은|상대는|만나|만날|뵐|보내|알려|공유|주입|명령|지시|프롬프트")


class GmsIcebreakerGenerator:
    def __init__(self, client: GmsClient):
        self.client = client

    async def suggest(self, topic: CommonTopicKey, fallback: IcebreakerResponse) -> IcebreakerResponse:
        try:
            async with asyncio.timeout(8.0):
                # Never send context IDs, manifest, survey answers or chat text.
                raw = await self.client.request_text(GENERATION_PROMPT, {"topic": topic})
            value = json.loads(raw, object_pairs_hook=unique_json_object)
            if type(value) is not dict or set(value) != {"questions"}:
                raise ValueError("Unexpected question response")
            result = IcebreakerResponse(questions=value["questions"], source="GENERATED",
                                        promptVersion=GMS_PROMPT_VERSION)
            templates._validate_questions(tuple(result.questions))
            if any(not question.endswith("?") or _UNSUPPORTED.search(question)
                   or not any(word in question for word in _TOPIC_WORDS[topic])
                   for question in result.questions):
                raise ValueError("Question outside approved topic")
            return result
        except Exception:
            # Cancellation is BaseException and must propagate to the caller.
            return fallback
