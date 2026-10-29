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

# 자유형식 fact의 일부 단어만 보고 kind를 신뢰하면 부정·주입 문구도
# 긍정적 fallback으로 바뀐다. 해석 가능한 요약 문장 전체만 허용한다.
# 이 문법은 원본 인증이 아니며, 신뢰된 서버의 실제 비교 결과가 전제다.
FACT_SUBJECTS = {
    "contact": r"(?:실제 )?연락 (?:빈도|방식)",
    "planning": r"(?:실제 )?(?:약속 )?준비 방식",
    "activity": r"(?:실제 )?(?:즐기는 )?활동",
    "experience": r"(?:새로운 )?경험을 (?:찾는|선택하는) 방식",
}
SHARED_ACTIONS = {
    "contact": r"(?:짧게 자주|여유 있게) 연락하는 편(?:이다|이라고 응답했다)",
    "planning": r"약속을 (?:미리|그때그때) 정하는 편(?:이다|이라고 응답했다)",
    "activity": r"(?:(?:공원 )?산책|독서|운동)(?:을|를) (?:선호한다고 응답했다|즐긴다고 응답했다)",
    "experience": r"(?:익숙한|새로운) 활동을 찾는 편(?:이다|이라고 응답했다)",
}


def supported_fact(item: Evidence) -> bool:
    """종류·주제·방향과 일치하는 보수적인 요약 문법. 미지원은 추측하지 않는다."""
    subject = FACT_SUBJECTS[item.topic]
    if item.kind == "ONE_WAY_PREFERENCE_FIT":
        if item.direction not in {"A_TO_B", "B_TO_A"}:
            return False
        owner, other = ("A", "B") if item.direction == "A_TO_B" else ("B", "A")
        patterns = (
            rf"{other}의 {subject}(?:이|가) {owner}의 (?:선호|허용 범위)에 (?:맞는다|들어간다)",
            rf"{owner}가\s*선호하는 {subject}(?:에| 범위에) {other}의 {subject}(?:이|가) (?:맞는다|들어간다)",
        )
    elif item.direction != "BOTH":
        return False
    elif item.kind == "MUTUAL_PREFERENCE_FIT":
        patterns = (rf"두 사람의 {subject}(?:이|가) 서로의 선호와 맞는다",)
        if item.topic == "contact":
            patterns += (
                r"두 사람 모두 (?:짧게 자주|여유 있게) 연락하는 방식을 선호하고 "
                r"각자의 실제 연락 방식도 상대의 선호와 맞는다",
            )
    elif item.kind == "SHARED_REPORTED_BEHAVIOR":
        patterns = (
            rf"두 사람의 {subject}에 공통점이 있다",
            rf"(?:두 사람|A와 B) 모두 {SHARED_ACTIONS[item.topic]}",
        )
    else:
        patterns = (rf"{subject}(?:에는|에) 차이가 있다",)
        if item.topic == "planning":
            patterns += (r"약속을 준비하는 시점에는 차이가 있다",)
        elif item.topic == "experience":
            patterns += (
                r"A는 익숙한 활동을 주로 찾고 B는 새로운 활동을 찾는 편이어서 "
                r"경험을 선택하는 방식에 차이가 있다",
            )
    return any(re.fullmatch(rf"(?:{pattern})\.", item.fact) for pattern in patterns)


# 단어 자체(공원·저녁·공통점)를 금지하지 않고 점수 단정, 시각/장소와
# 만남 행위의 결합, 인물 호칭을 이용한 신원 단정 등 주장 형태를 검사한다.
# 임의의 자연어 사실성·개인정보 검증을 대체하지 않는다.
UNSUPPORTED_CLAIMS = re.compile(
    r"(?:만\s*점|(?:백|[일이삼사오육칠팔구]?십[일이삼사오육칠팔구]?)\s*점)"
    r"(?=이|입|은|는|을|에|[.!?]|$)|"
    r"(?:궁합|상성|적합도|조화)(?:은|는|이|가)?\s*[영공일이삼사오육칠팔구십백]+\s*점|"
    r"(?:오늘|내일|모레|이번\s*주|다음\s*주)[^.!?]{0,30}(?:만[나날납났]|뵈|모여|봐요)|"
    r"(?:한|두|세|네|다섯|여섯|일곱|여덟|아홉|열|열한|열두)\s*시\s*"
    r"(?:반\s*)?(?:에|부터)[^.!?]{0,20}(?:만[나날납났]|뵈|모여|봐요)|"
    r"[가-힣]{2,20}(?:공원|카페|식당|도서관|광장|정류장|건물)"
    r"(?:\s*(?:입구|출구|앞|옆|안))?(?:에서|으로|에)\s*(?:만[나날납났]|모여|뵈|봐요|오세요|와요)|"
    r"(?:상대(?:방)?|당신|그분)(?:는|은|가)\s*[가-힣]{2,12}(?:님|씨)(?:이에요|이세요|입니다|예요|이라고)"
)


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
        if item.topic not in TOPICS or not safe_text(item.fact) or not supported_fact(item):
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
    if UNSUPPORTED_CLAIMS.search(output.text):
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
