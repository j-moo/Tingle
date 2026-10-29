from dataclasses import dataclass
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, StrictBool, StringConstraints

Viewer = Literal["A", "B"]
Direction = Literal["A_TO_B", "B_TO_A", "BOTH"]
Kind = Literal[
    "MUTUAL_PREFERENCE_FIT", "SHARED_REPORTED_BEHAVIOR",
    "ONE_WAY_PREFERENCE_FIT", "DIFFERENCE",
]
NonEmpty = Annotated[str, StringConstraints(strict=True, min_length=1, max_length=200)]
MatchingId = Annotated[int, Field(strict=True, ge=1, le=2**63 - 1)]


class FrozenModel(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)


class Evidence(FrozenModel):
    # e1 형태의 임시 ID와 아래 길이/개수 한도는 보수적인 개발용 제한이다.
    # 정식 주제 코드·최종 FE 길이·서비스 한도로 확정하는 것이 아니다.
    id: Annotated[str, StringConstraints(strict=True, pattern=r"^e[1-9][0-9]{0,2}$")]
    direction: Direction
    kind: Kind
    topic: Annotated[str, StringConstraints(strict=True, min_length=1, max_length=40)]
    fact: Annotated[str, StringConstraints(strict=True, min_length=1, max_length=500)]


class ModelInput(FrozenModel):
    """계약 §3.3의 모델 경계. AiInput HTTP 본문과 다르다."""

    viewer: Viewer
    evidence: tuple[Evidence, ...] = Field(min_length=1, max_length=12)


class ModelOutput(FrozenModel):
    text: Annotated[str, StringConstraints(strict=True, min_length=1, max_length=600)]
    evidenceIds: tuple[NonEmpty, ...] = Field(min_length=1, max_length=12)


class SemanticVerdict(FrozenModel):
    supported: StrictBool
    directionCorrect: StrictBool
    privacySafe: StrictBool
    evidenceIds: tuple[NonEmpty, ...] = Field(min_length=1, max_length=12)


class Explanation(FrozenModel):
    """현재 OpenAPI 내부 Explanation의 필수 5필드만 반환한다."""

    source: Literal["GENERATED", "RULE_FALLBACK"]
    text: Annotated[str, StringConstraints(strict=True, min_length=1, max_length=600)]
    evidenceKeys: tuple[str, ...]
    promptVersion: Annotated[str, StringConstraints(strict=True, min_length=1, max_length=100)] | None
    encounterId: MatchingId


class Reference(FrozenModel):
    """당시 판정 연결의 불투명 내부 참조. 새 API/DB 필드가 아니다.

    evaluation_ref/version_ref의 실제 저장 형식과 Manifest 대응은 -76/-103에서
    결정한다. 어댑터는 현재 프로필이 아닌 동일 판정의 원본/버전을 조회해야 한다.
    """

    encounter_id: MatchingId
    evaluation_ref: NonEmpty
    version_ref: NonEmpty


class SourceEvidence(FrozenModel):
    """신뢰하는 서버 어댑터가 실제 원본 비교와 대조한 요약 사실.

    source_ref는 실제 비교 항목의 불투명 참조다. reasonKeys만으로 fact를
    복원하거나 모델이 만든 사실을 이 레코드로 승격해서는 안 된다.
    """

    reference: Reference
    source_ref: NonEmpty
    evidence: Evidence


class ResolvedContext(FrozenModel):
    reference: Reference
    viewer: Viewer
    evidence: tuple[SourceEvidence, ...] = Field(max_length=12)
    conflicting_source_refs: frozenset[str] = frozenset()


class DisclosureScope(FrozenModel):
    """동일 판정·열람자에 적용한 서버의 전송/표시 허용 결정.

    두 허용 목록은 별개다. 빈 목록은 거부이며 서비스가 정책을 추측하지 않는다.
    이 값은 서버 정책 적용 결과이지 앱이 제출하는 동의/권한 플래그가 아니다.
    """

    reference: Reference
    viewer: Viewer
    version: NonEmpty
    model_evidence_ids: frozenset[str] = frozenset()
    display_evidence_ids: frozenset[str] = frozenset()


@dataclass(frozen=True)
class ExplanationResult:
    """내부 처리 결과. reason/used_evidence_ids는 FE 응답 필드가 아니다.

    explanation=None의 HTTP/공개 상태 대응은 BE 연동 대상이다. 내부 Explanation을
    공개 ExplanationStatus로 직접 직렬화하거나 별도 Job/jobId를 만들지 않는다.
    """

    explanation: Explanation | None
    used_evidence_ids: tuple[str, ...] = ()
    reason: str | None = None
