import json
import re
import unicodedata
from collections import defaultdict

from .dto import (
    DisclosureScope, Evidence, ModelInput, ModelOutput, ResolvedContext,
    SemanticVerdict, SourceEvidence,
)

# 미확정 주제 전체를 승인하는 목록이 아니라 이 구현이 처리하는 최소 범위다.
# 실제 전송/표시 허용은 반드시 별도의 DisclosureScope를 통과해야 한다.
TOPICS = frozenset({"contact", "planning", "activity", "experience"})
FORBIDDEN = re.compile(
    r"[\d@%]|https?://|www\.|"
    r"점수|확률|성공률|매칭률|퍼센트|원응답|설문|문항|비공개|"
    r"사진|이미지|프로필|실명|이름|생년|전화번호|연락처|주소|직장|회사|학교|학번|"
    r"연봉|소득|종교|성적|병력|질환|흡연|음주|주민|여권|인증|비밀번호|"
    r"키링|토큰|프롬프트|시스템|명령|지시|역할|"
    r"만남\s*(?:시각|시간|장소)|[가-힣]{2,10}역에서|"
    r"system|assistant|developer|ignore|instruction|password|secret|token|api.?key",
    re.IGNORECASE,
)
INTERNAL_TERMS = re.compile(r"[a-zA-Z]|허용\s*범위|충족|판정|양방향|한\s*방향")
MUTUAL_CLAIM = re.compile(r"두\s*(?:분|사람)|서로|모두|양쪽|공통|닮|같은")
NEGATED_FIT = re.compile(r"들어가지\s*않|맞지\s*않|충족하지\s*않|포함되지\s*않")
NEGATED_SHARED = re.compile(r"공통점[이가은는]*\s*없|같지\s*않|서로\s*다르")


def safe_text(text: str, *, output: bool = False) -> bool:
    normalized = unicodedata.normalize("NFKC", text)
    if normalized != text or not text.strip() or text != text.strip():
        return False
    if any(unicodedata.category(char).startswith("C") for char in text):
        return False
    if FORBIDDEN.search(text):
        return False
    return not output or not INTERNAL_TERMS.search(text)


def select_evidence(
    context: ResolvedContext, scope: DisclosureScope,
) -> tuple[tuple[Evidence, ...], tuple[Evidence, ...], str | None]:
    """무결성 실패는 전체 거부, 항목 충돌/미승인/지원 밖 근거는 제외한다."""
    if scope.reference != context.reference or scope.viewer != context.viewer:
        raise ValueError("INTEGRITY_FAILURE")
    ids = [record.evidence.id for record in context.evidence]
    if len(ids) != len(set(ids)):
        raise ValueError("INTEGRITY_FAILURE")
    if any(record.reference != context.reference for record in context.evidence):
        raise ValueError("INTEGRITY_FAILURE")
    if any(not value.strip() for value in (
        context.reference.evaluation_ref, context.reference.version_ref, scope.version,
    )) or any(not record.source_ref.strip() for record in context.evidence):
        raise ValueError("INTEGRITY_FAILURE")

    by_source: dict[tuple[str, str], list[SourceEvidence]] = defaultdict(list)
    for record in context.evidence:
        by_source[(record.source_ref, record.evidence.direction)].append(record)
    conflicts = set(context.conflicting_source_refs)
    for (source_ref, _), records in by_source.items():
        claims = {(r.evidence.kind, r.evidence.topic, r.evidence.fact) for r in records}
        if len(claims) > 1:
            conflicts.add(source_ref)

    display = []
    seen_sources: set[tuple[str, str]] = set()
    for record in context.evidence:
        item = record.evidence
        source_key = (record.source_ref, item.direction)
        if record.source_ref in conflicts or source_key in seen_sources:
            continue
        if item.id not in scope.display_evidence_ids:
            continue
        if item.topic not in TOPICS or not safe_text(item.fact):
            continue
        if item.kind in {"MUTUAL_PREFERENCE_FIT", "ONE_WAY_PREFERENCE_FIT"}:
            if NEGATED_FIT.search(item.fact):
                continue
        if item.kind == "SHARED_REPORTED_BEHAVIOR" and NEGATED_SHARED.search(item.fact):
            continue
        if item.kind == "ONE_WAY_PREFERENCE_FIT":
            if item.direction == "BOTH":
                continue
        elif item.direction != "BOTH":
            # 차이도 방향을 새로 추론하지 않는 BOTH 요약만 우선 지원한다.
            continue
        display.append(item)
        seen_sources.add(source_key)
    model = tuple(item for item in display if item.id in scope.model_evidence_ids)
    empty_reason = None
    if not display:
        if not context.evidence:
            empty_reason = "NO_EVIDENCE"
        elif not set(ids) & scope.display_evidence_ids:
            empty_reason = "DISPLAY_SCOPE_NOT_APPROVED"
        elif any(record.source_ref in conflicts for record in context.evidence):
            empty_reason = "EVIDENCE_CONFLICT"
        else:
            empty_reason = "NO_SAFE_EVIDENCE"
    return tuple(display), model, empty_reason


def _unique_object(pairs: list[tuple[str, object]]) -> dict:
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate JSON key")
        result[key] = value
    return result


def parse_output(raw: str, request: ModelInput) -> ModelOutput:
    if not isinstance(raw, str) or len(raw) > 8_000:
        raise ValueError("INVALID_OUTPUT")
    output = ModelOutput.model_validate(json.loads(raw, object_pairs_hook=_unique_object))
    ids = output.evidenceIds
    evidence = {item.id: item for item in request.evidence}
    if len(ids) != len(set(ids)) or not set(ids) <= evidence.keys():
        raise ValueError("INVALID_OUTPUT")
    if not safe_text(output.text, output=True):
        raise ValueError("INVALID_OUTPUT")
    cited = [evidence[key] for key in ids]
    if all(item.kind == "ONE_WAY_PREFERENCE_FIT" for item in cited):
        if MUTUAL_CLAIM.search(output.text):
            raise ValueError("INVALID_OUTPUT")
    # 주제 단어/ID 일치만으로 의미 검증을 대체하지 않는다.
    return output


def validate_verdict(raw: str, output: ModelOutput) -> None:
    if not isinstance(raw, str) or len(raw) > 8_000:
        raise ValueError("INVALID_OUTPUT")
    verdict = SemanticVerdict.model_validate(json.loads(raw, object_pairs_hook=_unique_object))
    if not (verdict.supported and verdict.directionCorrect and verdict.privacySafe):
        raise ValueError("INVALID_OUTPUT")
    if len(verdict.evidenceIds) != len(set(verdict.evidenceIds)):
        raise ValueError("INVALID_OUTPUT")
    if set(verdict.evidenceIds) != set(output.evidenceIds):
        raise ValueError("INVALID_OUTPUT")
