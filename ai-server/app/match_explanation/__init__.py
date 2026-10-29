"""근거 조회 이후의 내부 서비스. HTTP AiInput/권한/저장은 BE 연동 대상이다.

ResolvedContext와 DisclosureScope는 서버가 조회·검증해 만드는 내부 값이다.
클라이언트 본문을 이 객체로 변환해서는 안 된다. scope 기본값과 권한 기본값은
모두 거부이며, 실제 정보 사용 정책이 합의되기 전에는 실사용 근거를 허용하지 않는다.
"""

from .dto import (
    DisclosureScope,
    Evidence,
    Explanation,
    ExplanationResult,
    ModelInput,
    Reference,
    ResolvedContext,
    SourceEvidence,
)
from .service import MatchExplanationService

__all__ = [
    "DisclosureScope", "Evidence", "Explanation", "ExplanationResult",
    "MatchExplanationService", "ModelInput", "Reference", "ResolvedContext",
    "SourceEvidence",
]
