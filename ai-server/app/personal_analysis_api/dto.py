"""Internal request and the calculation-owned part of BE's public result.

Source: Notion 2026-10-27, personal result dfdc23fd598982dc885281a14be12080.
BE adds resultId/sessionId/manifest and manages PENDING/FAILED and persistence.
"""

from typing import Annotated, Literal

from pydantic import Field, StrictInt

from app.personal_analysis.dto import Answer, StrictDTO

Version = Annotated[str, Field(min_length=1, max_length=30)]
Nonempty = Annotated[str, Field(min_length=1)]
QuestionId = Annotated[str, Field(pattern=r"^Q(?:00[1-9]|0[1-9][0-9]|100)$")]
Score = Annotated[float, Field(ge=0, le=100, allow_inf_nan=False)]
ReferenceMode = Literal["PREFERENCE_FIT", "SELF_SIMILARITY"]


class PersonalAnalysisRequest(StrictDTO):
    analysisId: Annotated[StrictInt, Field(gt=0, le=2**63 - 1)]
    surveyVersion: Version
    analysisVersion: Version
    answers: list[Answer]


class Reading(StrictDTO):
    key: Nonempty
    templateId: Nonempty
    text: Nonempty
    evidenceQuestionIds: list[QuestionId]
    priority: StrictInt


class TypeReference(StrictDTO):
    typeCode: Nonempty
    score: Score
    mode: ReferenceMode
    evidenceQuestionIds: list[QuestionId]


class ErrorResponse(StrictDTO):
    code: Nonempty
    message: Nonempty


class PersonalAnalysisResponse(StrictDTO):
    status: Literal["SUCCEEDED", "INSUFFICIENT"]
    surveyVersion: Version
    analysisVersion: Version
    scores: dict[str, Score | None]
    typeStatus: Literal["ASSIGNED", "BORDERLINE", "TIED", "INSUFFICIENT"]
    primaryType: str | None
    tiedTypes: list[str]
    readings: Annotated[list[Reading], Field(max_length=3)]
    selfPreferenceDifferences: Annotated[list[Reading], Field(max_length=2)]
    typeRecommendations: Annotated[list[TypeReference], Field(max_length=3)]
    missingQuestionIds: list[QuestionId]
