"""Offline Korean rules ported from ZIP r2; synthetic precision probes only.

No network calls, logging, automatic reporting, blocking or account actions.
Reporting/quotation handling is deliberately narrow, not semantic understanding.
"""

import re
from typing import Sequence

from app.risk_detection.http_api import Message


MODEL_VERSION = "safety48-local-rules-r2-v1"
PROMPT_VERSION = "safety48-no-llm-r2-v1"


# Only the mappings supported by current BE/AI response validators.
MAPPINGS = {
    "FRAUD": ("MONEY_REQUEST", "RECIPIENT"),
    "HARASSMENT": ("HARASSMENT", "BOTH"),
    "THREAT": ("HARASSMENT", "BOTH"),
    "PERSONAL_INFO": ("PERSONAL_INFO", "RECIPIENT"),
    "OFF_PLATFORM": ("OFF_PLATFORM", "RECIPIENT"),
}

# These patterns identify reported or preventive clauses.
_REPORTING = re.compile(
    r"(?:(?:어제.{0,18})?(?:친구|상대|누가|모르는\s*사람)(?:가|이|는)?\s*.{0,80}?"
    r"(?:라고|다고|[\"'”’]고).{0,12}?(?:말했|말해|해서|했어|들었|협박했)|"
    r"어제.{0,18}(?:누가|모르는\s*사람)|"
    r"(?:소설|영화|드라마|뉴스|기사|책).{0,24}(?:장면|대사|내용)|"
    r"(?:사기를\s*당했|피해를\s*당했|성적인\s*사진을\s*요구해서\s*차단했))"
)
_PREVENTION = re.compile(
    r"(?:믿지\s*마|말하면\s*안\s*돼|하면\s*안\s*돼|요구는\s*거절해|그런\s*요구를\s*거절해|"
    r"보내지\s*마|송금하지\s*마|입금하지\s*마)"
)

_TRANSFER_PROHIBITION = re.compile(
    r"\s*(?:(?:지금|아직|절대)(?:은|는)?\s*)?(?:송금|입금)\s*하지\s*마(?:세요|십시오)?"
    r"[\s.,!?，。！？]*"
)

# Keep punctuation and connectors inside paired quotes with the reported speech.
_QUOTED_TEXT = r"""“[^”]*”|‘[^’]*’|"[^"]*"|'[^']*'"""
_CONTRAST = r"(?:하지만|그런데|근데|그래도|그렇지만)"
_QUOTES_OR_BOUNDARY = re.compile(
    rf"{_QUOTED_TEXT}|(?P<boundary>[!?。！？\r\n]+|(?<!\d)\.+|\.+(?!\d))"
)
_QUOTES_OR_CLAUSE = re.compile(
    rf"{_QUOTED_TEXT}|(?P<boundary>[,，]|(?:했는데|말고|안\s*돼)(?=\s)|(?<!\S)(?=(?:{_CONTRAST}|대신)\s))"
)
_REPORT_CONTRAST = re.compile(rf"^{_CONTRAST}\s+")

_NEGATED_ACTION = r"\s*(?:하)?\s*(?:지\s*(?:않|마|말)|(?:면|서는|어서는)\s*안\s*(?:돼|되|됩))"
_REJECTED_REQUEST = (
    r"\s*(?:달라|줘|주(?:세요|십시오))?\s*[\"'”’]?\s*(?:라는|는)\s*(?:요구|요청)"
    r"\s*(?:은|는|을|를)?\s*거절\s*(?:하세요|하십시오|해(?:요|라)?)"
    r"(?=\s*(?:[,.!?，。！？]|$))"
)
_PAYMENT_PRESSURE = (
    r"\s*(?:(?:부터\s*)?해\s*|하\s*)?(?:(?:주|드리)\s*)?지\s*않으면\s*"
    r"(?:안\s*(?:돼(?:요)?|됩니다)|(?:이\s*)?기회(?:를)?\s*놓쳐(?:요)?)"
    r"(?=\s*(?:[,.!?，。！？]|$))"
)
_NEGATED_PAYMENT = (
    # A consequence for not paying makes the conditional a payment demand.
    rf"(?!{_PAYMENT_PRESSURE})"
    # After 송금/입금 the conjugated verb can precede the negative auxiliary.
    r"(?:\s*(?:부터\s*)?해)?"
    rf"(?:{_REJECTED_REQUEST}|(?:\s*(?:주|드리))?(?:\s*(?:은|는|도))?"
    rf"(?:{_NEGATED_ACTION}|\s*(?:하)?\s*지(?:는|도)\s*(?:않|마|말)))"
)

_GUARANTEE = re.compile(
    r"(?:원금\s*(?:100\s*%|전액)?\s*보장|"
    r"(?:수익|이익).{0,20}보장|"
    r"손실\s*없는\s*(?:비밀\s*)?투자|손실\s*없이.{0,12}(?:두|[0-9]+)\s*배|"
    r"투자하면.{0,12}무조건\s*(?:두|[0-9]+)\s*배로\s*돌려)"
    r"(?!\s*(?:로\s*돌려)?\s*(?:(?:하|주|드리)?\s*지(?:는|도)?\s*(?:않|못|마|말)|"
    r"(?:안|못)\s*(?:하|해|되|돼)|(?:할|줄|드릴)\s*수\s*없))"
)
_PAYMENT = re.compile(
    rf"(?:(?:입금|송금)(?={_PAYMENT_PRESSURE}|\s*(?:부터\s*)?(?:해|하(?:세요|십시오|셔야|면|자|기\s*바라)|부탁))|"
    r"(?:돈|금액|수수료|인증금|보증금|가입비|보증료|투자금)"
    r"(?:\s*[0-9]+(?:,[0-9]{3})*\s*(?:[십백천]?[만억]|[십백천])?\s*원)?"
    r"\s*(?:을|를|은|는|도|부터)?\s*(?:(?:지금|먼저|바로)\s*)?"
    r"(?:보내|내(?=\s*(?:주|라|세요|야|[.!?]|$)))|"
    rf"내\s*(?:개인\s*)?계좌.{{0,12}}(?:넣|보내))(?!{_NEGATED_PAYMENT})"
)
# Additional narrow combinations found in the older draft's synthetic cases.
# An institution/prize mention or a fee alone is never enough for FRAUD.
_PRETEXT = re.compile(r"은행\s*담당자|계좌를\s*풀려면|당첨금|세관|해외\s*배송")
_UPFRONT_FEE = re.compile(rf"(?:보증금|수수료|해제비|인증금).{{0,24}}(?:보내|입금|송금)(?!{_NEGATED_PAYMENT})")
_NO_VERIFICATION = re.compile(r"(?:확인(?:\s*전화)?|조회|(?:사전\s*)?검증).{0,12}(?:하지\s*(?:마|말)|금지)")
_THREAT_STATEMENT_DENIAL = (
    r"(?:다는\s*(?:말|얘기|이야기)\s*(?:은|는|을|를|도)?|다고\s*말)"
    r"\s*(?:하지(?:는|도)?\s*(?:않|마|말)|(?:안|못)\s*(?:하|해)|한\s*적(?:이|은|도)?\s*없)"
)
_THREAT = (
    re.compile(r"(?:너|널|네|당신).{0,28}(?:죽이겠|때리겠|패겠|해치겠|죽여버리겠|"
               rf"(?:죽일|때릴|해칠)\s*거야)(?!{_THREAT_STATEMENT_DENIAL})"),
    re.compile(r"(?:찾아가|집\s*앞|기다렸다가).{0,28}(?:때리겠|때릴\s*거야|패겠|해치겠)"
               rf"(?!{_THREAT_STATEMENT_DENIAL})"),
    # Leave 다 for the shared denial pattern in "버린다는 말".
    re.compile(r"(?:네|너의|당신의|사적인).{0,18}(?:사진|영상|사적인\s*이야기).{0,32}"
               r"(?:퍼뜨리|유포하|공개하|공개할|뿌리겠|"
               r"(?:단톡방|단체\s*채팅방).{0,8}올려\s*버린(?=다))"
               rf"(?!{_NEGATED_ACTION}|(?:겠)?{_THREAT_STATEMENT_DENIAL})"),
    re.compile(r"거절하면.{0,18}때리러.{0,8}간"),
)
_PHOTO_PROHIBITION = (
    r"\s*(?:을|은|도)?\s*(?:계속\s*)?(?:보내|공유(?:해|하))"
    rf"(?:(?:\s*주)?{_NEGATED_ACTION}|{_REJECTED_REQUEST})"
)
_HARASSMENT_PHOTO = rf"사진(?!{_PHOTO_PROHIBITION})"
_HARASSMENT = (
    re.compile(r"(?:너|니|네|당신)\s*(?:같은|는|가)?\s*(?:진짜\s*)?"
               r"(?:멍청이|멍청한\s*놈|쓰레기(?:야|는|다)|벌레(?:야|는|다))"),
    re.compile(r"(?:사람\s*취급도\s*(?:못|안)|네가\s*울\s*때까지.{0,15}(?:조롱|비웃어\s*줄게)|"
               r"계속\s*(?:욕할|조롱할))"),
    re.compile(rf"(?:싫다|싫어|거절).{{0,32}}성적인\s*(?:말|{_HARASSMENT_PHOTO}|영상)"
               r".{0,24}(?:보낼|요구할|계속)"),
    re.compile(r"(?:싫어도|싫다고\s*해도).{0,30}"
               rf"(?:성적인\s*(?:말|{_HARASSMENT_PHOTO})|(?:몸|벗은)\s*{_HARASSMENT_PHOTO}|"
               r"야한\s*이야기|모욕|욕|조롱)"),
)
_CREDENTIAL = re.compile(
    r"비밀\s*번호|인증\s*번호|로그인.{0,12}(?:문자|SMS)로\s*온\s*(?:여섯|6)\s*자리\s*(?:숫자|코드)"
)
_CREDENTIAL_SHARING_ACTION = r"(?:보내|알려|넘겨)"
_CREDENTIAL_ACTION = re.compile(rf"{_CREDENTIAL_SHARING_ACTION}|입력(?:\s*하)?|줘|달라")
_CREDENTIAL_PROHIBITION = re.compile(
    r"\s*(?:주\s*)?(?:지\s*(?:마(?:세요)?|말(?:고)?|않\S*)|"
    r"(?:면|서는|어서는|라고\s*하면)\s*안\s*(?:돼|되|됩니다)|금지|"
    r"라는\s*(?:말|요구)(?:은|는|을|를)\s*(?:믿지\s*마|거절해))"
)
_CREDENTIAL_CONTINUATION = re.compile(
    r"[\s,，]*(?:(?:대신|그리고|지금|바로|따로|그거(?:를)?|그걸|그것(?:을)?|"
    r"(?:나|저)(?:에게|한테)만?|(?:내|제)게만?|여기(?:로|에)만?|이\s*채팅(?:방)?(?:으로|에))[\s,，]*)*"
)
_CREDENTIAL_REQUEST_WRAP = re.compile(
    rf"(?:{_CREDENTIAL.pattern})(?:[^\S\r\n]*(?:을|를|은|는))?"
    rf"{_CREDENTIAL_CONTINUATION.pattern}"
    rf"(?:{_CREDENTIAL_SHARING_ACTION}"
    r"(?:라|세요|십시오|\s*(?:줘요?|주(?:세요|십시오))|\s*야\s*해요?)?|줘|달라)"
    r"(?=\s*(?:[.!?。！？]|$))"
)
_ROUTINE_LOGIN = re.compile(r"(?:을|를|은|는)?\s*(?:(?:새로|다시|직접)\s+)?입력하면\s*로그인(?:이\s*)?(?:돼|되)")
_OFF_PLATFORM = re.compile(
    r"보안\s*(?:경고|설정|검사).{0,32}(?:무시|끄고|꺼)"
    rf"(?!{_NEGATED_ACTION}).{{0,45}}(?:앱|설치|링크)|"
    r"보안\s*경고.{0,32}닫고.{0,24}앱.{0,12}(?:설치해|깔아)|"
    r"(?:앱|원격\s*제어).{0,16}(?:설치|깔).{0,32}보안\s*(?:경고|설정|검사).{0,16}(?:끄|꺼|무시)"
    rf"(?!{_NEGATED_ACTION})"
)
_AMBIGUOUS = re.compile(
    r"^(?:(?P<send>(?:지금|그거|그것|다|전부)?\s*보내(?:\s*줘)?[.!?~]*(?:\s*알지[?]?)?)|"
    r"(?:아까|어제).{0,12}(?:대로|처럼).{0,8}(?:처리해|부탁할게)[.!?~]*|"
    r"그\s*말\s*그대로야[.!?~]*)$"
)
_PHOTO_REQUEST = re.compile(r"^사진\s*보내\s*줘[.!?~]*$")
_COERCED_PHOTO = re.compile(r"거절은\s*안\s*받아.{0,24}벗은\s*모습")
_THREAT_FOLLOWUP = re.compile(r"^오늘\s*끝장내자[.!?~]*$")


def _normalize(text: str) -> str:
    return re.sub(r"\s+", " ", text.strip())


def _split_at_boundaries(text: str, pattern: re.Pattern) -> list[str]:
    start = 0
    parts = []
    for match in pattern.finditer(text):
        if match.lastgroup == "boundary":
            parts.append(text[start:match.end()])
            start = match.end()
    parts.append(text[start:])
    return parts


def _direct_spans(sentences: Sequence[str], *, exclude_prevention: bool = True,
                  bridge_transfer_prohibition: bool = False) -> list[str]:
    # Keep adjacent direct clauses together, while excluded clauses break the span.
    # Search for reports before splitting so a quotation can span a comma.
    spans = []
    direct = []
    for sentence in sentences:
        # Quoted text can contain line wraps. Fold them without shifting report offsets.
        reports = tuple(_REPORTING.finditer(sentence.replace("\n", " ")))
        start = 0
        for clause in _split_at_boundaries(sentence, _QUOTES_OR_CLAUSE):
            end = start + len(clause)
            reported = any(report.start() < end and report.end() > start for report in reports)
            if not reported and bridge_transfer_prohibition and _TRANSFER_PROHIBITION.fullmatch(clause):
                # Keep the surrounding fraud context, without counting the warning as a demand.
                direct.append(". ")
            elif reported or (exclude_prevention and _PREVENTION.search(clause)):
                spans.append(_normalize("".join(direct)))
                direct.clear()
            else:
                direct.append(clause)
            start = end
    spans.append(_normalize("".join(direct)))
    return [span for span in spans if span]


def _requests_credentials(text: str) -> bool:
    # Join line wraps only inside a complete, direct request to share a credential.
    if "\n" in text:
        unfolded = text.replace("\n", " ")
        # A reported request can have line wraps outside the request itself.
        reports = tuple(_REPORTING.finditer(unfolded))

        def join_wrap(match: re.Match) -> str:
            start, end = match.span()
            if any(report.start() < end and report.end() > start for report in reports):
                return match[0]
            return unfolded[start:end]

        text = _CREDENTIAL_REQUEST_WRAP.sub(join_wrap, text)
    sentences = _split_at_boundaries(text, _QUOTES_OR_BOUNDARY)
    spans = (span for sentence in sentences for span in _direct_spans([sentence], exclude_prevention=False))
    for span in spans:
        for credential in _CREDENTIAL.finditer(span):
            start = credential.end()
            action = _CREDENTIAL_ACTION.search(span, start)
            ignored = _ROUTINE_LOGIN.match(span, start)
            while action and action.start() - start <= 30:
                ignored = ignored or _CREDENTIAL_PROHIBITION.match(span, action.end())
                if ignored is None:
                    return True
                # Continue only through connectors, recipients and pronouns. A
                # different object should not inherit the credential request.
                start = ignored.end()
                continuation = _CREDENTIAL_CONTINUATION.match(span, start)
                action = _CREDENTIAL_ACTION.match(span, continuation.end())
                ignored = None
    return False


def _fraud(text: str) -> bool:
    return bool((_GUARANTEE.search(text) and _PAYMENT.search(text))
                or (_PRETEXT.search(text) and _UPFRONT_FEE.search(text) and _NO_VERIFICATION.search(text)))


def _detect(message: Message, history: Sequence[Message]) -> tuple[list[str], bool]:
    sentences = _split_at_boundaries(message.text, _QUOTES_OR_BOUNDARY)
    spans = _direct_spans(sentences)
    signals: list[str] = []
    if any(_fraud(span) for span in _direct_spans(sentences, bridge_transfer_prohibition=True)):
        signals.append("FRAUD")
    if any(pattern.search(span) for span in spans for pattern in _HARASSMENT):
        signals.append("HARASSMENT")
    if any(pattern.search(span) for span in spans for pattern in _THREAT):
        signals.append("THREAT")
    if _requests_credentials(message.text):
        signals.append("PERSONAL_INFO")
    if any(_OFF_PLATFORM.search(span) for span in spans):
        signals.append("OFF_PLATFORM")

    text = _normalize(message.text)
    if len(spans) == 1 and _REPORT_CONTRAST.match(spans[0]) and spans[0] != text:
        text = _REPORT_CONTRAST.sub("", spans[0])
    ambiguous = _AMBIGUOUS.fullmatch(text)
    if not signals and (ambiguous or _PHOTO_REQUEST.fullmatch(text) or _THREAT_FOLLOWUP.fullmatch(text)):
        # Only the latest same-speaker context can explain a narrow follow-up.
        # Older threats/solicitations never become findings by themselves.
        for earlier in reversed(history):
            if earlier.speaker == message.speaker:
                prior_sentences = _split_at_boundaries(earlier.text, _QUOTES_OR_BOUNDARY)
                prior = _direct_spans(prior_sentences)
                if ambiguous and any(_fraud(span) for span in
                                     _direct_spans(prior_sentences, bridge_transfer_prohibition=True)):
                    signals.append("FRAUD")
                elif ambiguous and ambiguous.lastgroup == "send" and _requests_credentials(earlier.text):
                    signals.append("PERSONAL_INFO")
                elif (_PHOTO_REQUEST.fullmatch(text)
                      and any(_COERCED_PHOTO.search(span) for span in prior)):
                    signals.append("HARASSMENT")
                elif (_THREAT_FOLLOWUP.fullmatch(text)
                      and any(pattern.search(span) for span in prior for pattern in _THREAT)):
                    signals.append("THREAT")
                break
    return signals, bool(ambiguous and not signals)


def _coalesce_overflow_findings(findings: list[dict]) -> list[dict]:
    """Keep legacy results up to 40; coalesce equivalent notices above it.

    Only HARASSMENT/THREAT share a noticeType/audience in the five allowed
    mappings. The MVP-approved representative is THREAT; this loses the
    separate HARASSMENT category label, not evidence or notice recipients.
    Ten fresh messages have at most four distinct notice units each.
    """
    if len(findings) <= 40:
        return findings
    notices: dict[tuple, dict] = {}
    for finding in findings:
        key = (finding["sequence"], finding["noticeType"], finding["audience"])
        if key not in notices or finding["category"] == "THREAT":
            notices[key] = finding
    return list(notices.values())


def classify(messages: Sequence[Message]) -> dict:
    """Classify only fresh messages; never return raw content or user IDs.

    Caller is responsible for auth, consent, privacy filtering, and wire input
    validation. This function is NOT a substitute for those existing layers.
    """
    findings: list[dict] = []
    ambiguous = False
    previous: list[Message] = []
    for message in messages:
        if message.fresh:
            signals, unresolved = _detect(message, previous)
            ambiguous |= unresolved
            for key in signals:
                category = key if key in {"FRAUD", "HARASSMENT", "THREAT"} else "OTHER"
                notice_type, audience = MAPPINGS[key]
                findings.append({
                    "sequence": message.sequence,
                    "category": category,
                    "noticeType": notice_type,
                    "audience": audience,
                })
        previous.append(message)

    findings = _coalesce_overflow_findings(findings)
    if findings:
        kind = "RISK"
    elif ambiguous or not any(m.fresh for m in messages):
        kind = "INSUFFICIENT"
    else:
        kind = "NORMAL"
    return {
        "kind": kind,
        "findings": findings,
        "confidence": None,
        "modelVersion": MODEL_VERSION,
        "promptVersion": PROMPT_VERSION,
    }
