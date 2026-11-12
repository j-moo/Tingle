"""Wire models and question policy compatible with the BE icebreaker gateway."""

import re
import unicodedata
from typing import Annotated, Literal, Self
from uuid import UUID

from pydantic import AfterValidator, BaseModel, ConfigDict, Field, field_validator, model_validator

from . import templates
from .prompts import GMS_PROMPT_VERSION


# Java String.strip()/isBlank() use Character.isWhitespace, excluding NBSPs.
_JAVA_WHITESPACE = "\t\n\v\f\r\x1c\x1d\x1e\x1f \u1680\u2000\u2001\u2002\u2003\u2004\u2005\u2006\u2008\u2009\u200a\u2028\u2029\u205f\u3000"
_UNSAFE = re.compile(
    r"https?://|www\."
    r"|(?<![0-9])(?:[0-9]{1,3}\.){3}[0-9]{1,3}(?![0-9])"
    r"|(?a:[\w.+-]+@[\w.-]+)|[0-9][0-9 -]{7,}[0-9]"
    r"|연락처|전화번호|카카오톡|종교|흡연|성희롱|송금|계좌번호"
)
# Java's default regex \s is ASCII, even though String.strip() is Unicode-aware.
_SPACE_RUN = re.compile(r"\s+", re.ASCII)


def _contains_domain(text: str) -> bool:
    # The BE regex is unanchored: one letter, number or hyphen before the dot
    # and two Unicode letters after it suffice. isalpha() excludes letter-numbers.
    for index in range(1, len(text) - 2):
        if text[index] == "." and text[index + 1:index + 3].isalpha():
            previous = text[index - 1]
            if previous == "-" or unicodedata.category(previous)[0] in "LN":
                return True
    return False


def _canonical_uuid(value: str) -> str:
    if str(UUID(value)) != value:
        raise ValueError("Expected a canonical UUID")
    return value


def _version(value: str) -> str:
    if not value.strip(_JAVA_WHITESPACE) or len(value.encode("utf-16-le")) > 200:
        raise ValueError("Expected a nonblank version of at most 100 UTF-16 units")
    return value


UuidText = Annotated[str, Field(min_length=36, max_length=36), AfterValidator(_canonical_uuid)]
VersionText = Annotated[str, Field(min_length=1, max_length=100), AfterValidator(_version)]


class StrictModel(BaseModel):
    model_config = ConfigDict(strict=True, extra="forbid", frozen=True)


class Manifest(StrictModel):
    releaseId: UuidText
    surveyVersion: VersionText
    wordingVersion: VersionText
    scoringVersion: VersionText
    typeModelVersion: VersionText
    analysisVersion: VersionText
    typeReferenceVersion: VersionText
    matchingVersion: VersionText
    productPolicyVersion: VersionText
    engineVersion: VersionText
    contentHash: str = Field(min_length=64, max_length=64, pattern=r"^[0-9a-f]{64}$")


CommonTopicKey = Literal["rest", "talk", "culture", "walk", "active"]


class IcebreakerRequest(StrictModel):
    attemptId: UuidText
    contextId: int = Field(ge=1, le=2**63 - 1)
    manifest: Manifest
    commonTopicKey: CommonTopicKey | None = None

    @field_validator("commonTopicKey")
    @classmethod
    def reject_explicit_null(cls, key: CommonTopicKey | None) -> CommonTopicKey:
        # Pydantic skips field validators for the omitted default.
        if key is None:
            raise ValueError("Omit commonTopicKey when unavailable")
        return key


class IcebreakerResponse(StrictModel):
    questions: list[str] = Field(min_length=3, max_length=3)
    source: Literal["COMMON_TOPIC_TEMPLATE", "GENERAL_FALLBACK", "GENERATED"]
    promptVersion: VersionText

    @model_validator(mode="after")
    def check_source_version(self) -> Self:
        expected = {"COMMON_TOPIC_TEMPLATE": templates.Q040_VERSION,
                    "GENERAL_FALLBACK": templates.GENERAL_VERSION,
                    "GENERATED": GMS_PROMPT_VERSION}[self.source]
        if self.promptVersion != expected:
            raise ValueError("Unexpected icebreaker prompt version")
        return self

    @field_validator("questions")
    @classmethod
    def check_questions(cls, questions: list[str]) -> list[str]:
        seen: set[str] = set()
        result: list[str] = []
        for question in questions:
            trimmed = question.strip(_JAVA_WHITESPACE)
            canonical = _SPACE_RUN.sub(" ", unicodedata.normalize("NFKC", trimmed)).lower()
            if (not 1 <= len(trimmed) <= 200
                    or any(ord(char) < 32 or 0x7F <= ord(char) <= 0x9F
                           or 0xD800 <= ord(char) <= 0xDFFF for char in trimmed)
                    or canonical in seen or _UNSAFE.search(canonical) or _contains_domain(canonical)):
                raise ValueError("Rejected icebreaker question")
            seen.add(canonical)
            result.append(trimmed)
        return result
