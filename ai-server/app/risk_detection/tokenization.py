"""Versioned full-context serialization and strictly local tokenization.

The optional package is loaded only for explicitly tokenized releases.
Plain local rules do not require this package.
No vocabulary download, model lookup, text-length estimate or fallback counter.
"""

import json

from app.risk_detection.http_api import Message


INPUT_VERSION = "safety48-context-json-v1"
TOKENIZERS_VERSION = "0.22.2"


def serialize_context(messages: list[Message]) -> str:
    return json.dumps(
        {"contextBundle": [message.model_dump() for message in messages]},
        ensure_ascii=False, separators=(",", ":"),
    )


class LocalFileTokenizer:
    def __init__(self, raw: bytes):
        # from_str reads only the already verified local bytes. It cannot fetch
        # a named model or race with a replacement of tokenizer.json on disk.
        import tokenizers

        if tokenizers.__version__ != TOKENIZERS_VERSION:
            raise ValueError("Unsupported local tokenizer runtime")
        self._tokenizer = tokenizers.Tokenizer.from_str(raw.decode("utf-8"))
        if self._tokenizer.truncation is not None or self._tokenizer.padding is not None:
            raise ValueError("Tokenizer must count the complete unpadded input")

    def count(self, text: str) -> int:
        # Special tokens from the pinned tokenizer post-processor are counted.
        return len(self._tokenizer.encode(text, add_special_tokens=True).ids)
