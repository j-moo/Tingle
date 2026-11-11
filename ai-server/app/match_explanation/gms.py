import json
import os
from collections.abc import Mapping

import httpx2 as httpx

from .dto import ModelInput, ModelOutput
from .prompts import GENERATION_PROMPT, VERIFICATION_PROMPT
from .validation import unique_json_object

ENDPOINT = "https://gms.ssafy.io/gmsapi/api.openai.com/v1/responses"
MODEL = "gpt-6-luna"


class GmsError(Exception):
    """비밀값이나 제공업체 응답을 포함하지 않는 내부 예외."""


class GmsConfigurationError(GmsError):
    """요청 설정이나 인증을 고쳐야 하는 오류."""


class GmsClient:
    """기존 httpx2로 Responses API를 호출한다. 재시도/대체 모델은 사용하지 않는다.

    GMS_API_KEY는 실행 시 환경변수로만 읽고 요청/응답/인증정보를 로그에 남기지 않는다.
    from_environment()가 None이어도 서비스의 규칙 fallback을 실행할 수 있다.
    """

    def __init__(
        self, api_key: str, *, transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        if not api_key.strip() or "\n" in api_key or "\r" in api_key:
            raise ValueError("Invalid GMS configuration")
        self._api_key = api_key
        self._transport = transport

    @classmethod
    def from_environment(cls, environ: Mapping[str, str] | None = None) -> "GmsClient | None":
        values = os.environ if environ is None else environ
        api_key = values.get("GMS_API_KEY", "")
        return cls(api_key) if api_key.strip() else None

    async def generate(self, request: ModelInput) -> str:
        return await self.request_text(GENERATION_PROMPT, request.model_dump(mode="json"))

    async def verify(self, request: ModelInput, output: ModelOutput) -> str:
        return await self.request_text(VERIFICATION_PROMPT, {
            **request.model_dump(mode="json"),
            "candidate": output.model_dump(mode="json"),
        })

    async def request_text(self, prompt: str, payload: dict) -> str:
        """Send an already prepared payload and return a single validated text part."""
        body = {
            "model": MODEL, "stream": False, "max_output_tokens": 2048,
            "input": [
                {"role": "system", "content": prompt},
                {"role": "user", "content": json.dumps(payload, ensure_ascii=False)},
            ],
        }
        try:
            async with httpx.AsyncClient(
                transport=self._transport, trust_env=False, follow_redirects=False,
                timeout=httpx.Timeout(8.0, connect=3.0),
            ) as client:
                async with client.stream(
                    "POST", ENDPOINT, json=body,
                    headers={"Authorization": f"Bearer {self._api_key}"},
                ) as response:
                    status = response.status_code
                    if 300 <= status < 500 and status not in (408, 429):
                        raise GmsConfigurationError("GMS authentication or configuration failed")
                    if status != 200:
                        raise GmsError("GMS request failed")
                    data = bytearray()
                    async for chunk in response.aiter_bytes():
                        if len(data) + len(chunk) > 32_768:
                            raise GmsError("GMS response limit exceeded")
                        data.extend(chunk)
            envelope = json.loads(data, object_pairs_hook=unique_json_object)
            return _response_text(envelope)
        except httpx.TimeoutException:
            raise TimeoutError("GMS timeout") from None
        except (httpx.LocalProtocolError, UnicodeEncodeError):
            raise GmsConfigurationError("Invalid GMS request configuration") from None
        except (httpx.HTTPError, ValueError, KeyError, TypeError, AttributeError, RecursionError):
            raise GmsError("Invalid GMS response") from None


def _response_text(envelope: dict) -> str:
    if envelope.get("status") != "completed" or envelope.get("model") != MODEL:
        raise GmsError("Unexpected GMS response")
    if envelope.get("error") is not None or envelope.get("incomplete_details") is not None:
        raise GmsError("Incomplete GMS response")
    texts = []
    for item in envelope["output"]:
        if item["type"] == "reasoning":
            continue
        if (item["type"] != "message" or item.get("role") != "assistant"
                or item.get("status", "completed") != "completed"):
            raise GmsError("Unexpected GMS output")
        content = item["content"]
        if type(content) is not list or not content:
            raise GmsError("Unexpected GMS content")
        for part in content:
            if part["type"] != "output_text" or not isinstance(part.get("text"), str):
                raise GmsError("Unexpected GMS content")
            texts.append(part["text"])
    if len(texts) != 1 or not texts[0].strip():
        raise GmsError("Missing GMS text")
    return texts[0]
