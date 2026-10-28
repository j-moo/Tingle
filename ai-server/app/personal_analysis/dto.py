"""Local Python DTOs. These do not publish the unagreed BE HTTP contract."""

from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, JsonValue, StrictInt


class StrictDTO(BaseModel):
    model_config = ConfigDict(
        extra="forbid", strict=True, hide_input_in_errors=True, revalidate_instances="always",
    )


class Answer(StrictDTO):
    questionId: str
    value: JsonValue


Age = Annotated[StrictInt, Field(ge=19, le=100)]


class SurveyMetadata(StrictDTO):
    ownAge: Age | None
    desiredAgeMin: Age | None
    desiredAgeMax: Age | None


class PersonalAnalysisInput(StrictDTO):
    surveyVersion: str
    calculationVersion: str
    answers: list[Answer]
    metadata: SurveyMetadata


class PersonalAnalysisResult(StrictDTO):
    surveyVersion: str
    calculationVersion: str
    status: Literal["SUCCEEDED", "INSUFFICIENT"]
    scores: dict[str, float | None]
    typeStatus: Literal["ASSIGNED", "BORDERLINE", "TIED", "INSUFFICIENT"]
    primaryType: str | None
    tiedTypes: list[str]
    # Original JS structures are retained, including evidence text and ordering.
    classification: dict[str, JsonValue]
    readings: list[dict[str, JsonValue]]
    selfPreferenceDifferences: list[dict[str, JsonValue]]
    referenceMode: Literal["PREFERENCE_FIT", "SELF_SIMILARITY"]
    typeReferenceVersion: str
    typeReferences: list[dict[str, JsonValue]] = Field(max_length=3)
    missingQuestionIds: list[str]
