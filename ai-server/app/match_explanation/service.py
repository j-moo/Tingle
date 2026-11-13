import asyncio
import math
from collections.abc import Callable
from typing import Protocol

from .dto import (
    DisclosureScope, Explanation, ExplanationResult, ModelInput, ModelOutput,
    ResolvedContext,
)
from .fallback import rule_fallback
from .grounding import validate_grounding
from .prompts import PROMPT_VERSION
from .validation import parse_output, select_evidence, validate_verdict


class Generator(Protocol):
    async def generate(self, request: ModelInput) -> str: ...


class Verifier(Protocol):
    async def verify(self, request: ModelInput, output: ModelOutput) -> str: ...


class MatchExplanationService:
    """조회/점수 계산/저장/제안 상태 변경이 없는 내부 비동기 서비스.

    authorize는 서버의 현재 권한 재검사이며 시작/의미 검증 전/반환 직전에 수행한다.
    누락/실패 시 모델·fallback을 반환하지 않는다. 모델/검토자는 별도 호출로
    동작하고, 공개 근거 문장 검증과 모델 검토를 모두 통과해야 GENERATED다.
    """

    def __init__(
        self, *, generator: Generator | None = None, verifier: Verifier | None = None,
        authorize: Callable[[ResolvedContext], bool] | None = None,
        timeout_seconds: float = 9.0,
    ) -> None:
        if not math.isfinite(timeout_seconds) or timeout_seconds <= 0:
            raise ValueError("timeout_seconds must be finite and positive")
        self.generator = generator
        self.verifier = verifier
        self.authorize = authorize
        # 생성·검토는 순차 실행되며 전체 기한을 공유한다. BE 내부 클라이언트의
        # 10초 상한 안에서 fallback을 반환하도록 전체 9초를 적용한다.
        # 개별 GMS 호출의 8초 제한은 유지한다. 운영 SLA 확정값이 아니다.
        self.timeout_seconds = timeout_seconds

    def _authorized(self, context: ResolvedContext) -> bool:
        try:
            return self.authorize is not None and self.authorize(context) is True
        except Exception:
            # 외부 예외에 포함될 수 있는 원문/식별자를 기록·반환하지 않는다.
            return False

    async def explain(
        self, context: ResolvedContext, *, scope: DisclosureScope | None = None,
    ) -> ExplanationResult:
        if not self._authorized(context):
            return ExplanationResult(None, reason="AUTHORIZATION_FAILED")
        if scope is None:
            return ExplanationResult(None, reason="SCOPE_NOT_APPROVED")
        try:
            display, model, empty_reason = select_evidence(context, scope)
        except ValueError:
            return ExplanationResult(None, reason="INTEGRITY_FAILURE")
        if not display:
            return ExplanationResult(None, reason=empty_reason)

        text, used_ids = rule_fallback(context.viewer, display)
        source = "RULE_FALLBACK"
        prompt_version = None
        reason = "MODEL_NOT_CONFIGURED"
        if not model:
            reason = "MODEL_SCOPE_NOT_APPROVED"
        elif self.generator is not None and self.verifier is not None:
            request = ModelInput(viewer=context.viewer, evidence=model)
            try:
                async with asyncio.timeout(self.timeout_seconds):
                    raw = await self.generator.generate(request)
                    # 생성 대기 중 철회됐다면 후보를 추가 전송하지 않는다.
                    # 이미 시작한 호출은 회수할 수 없으므로 반환 전 검사도 유지한다.
                    if not self._authorized(context):
                        return ExplanationResult(None, reason="AUTHORIZATION_FAILED")
                    output = parse_output(raw, request)
                    validate_grounding(output, request)
                    verdict = await self.verifier.verify(request, output)
                    validate_verdict(verdict, output)
                text, used_ids = output.text, output.evidenceIds
                source, prompt_version, reason = "GENERATED", PROMPT_VERSION, None
            except TimeoutError:
                reason = "MODEL_TIMEOUT"
            except (ValueError, TypeError):
                reason = "INVALID_OUTPUT"
            except Exception:
                reason = "MODEL_ERROR"
        elif self.generator is not None:
            reason = "VERIFIER_NOT_CONFIGURED"

        if not self._authorized(context):
            return ExplanationResult(None, reason="AUTHORIZATION_FAILED")
        explanation = Explanation(
            source=source, text=text,
            # 공개용 키 표준/대응은 미합의다. 내부 reasonKeys/e1은 반환하지 않는다.
            evidenceKeys=(), promptVersion=prompt_version,
            encounterId=context.reference.encounter_id,
        )
        return ExplanationResult(explanation, used_ids, reason)
