"""Rule engine integration and failure probes, using synthetic data only."""

from copy import deepcopy
import ipaddress
import json
from pathlib import Path
import socket

import pytest

from app.risk_detection import local_engine
from app.risk_detection.local_engine import EngineUnavailableError, LocalRuleRiskEngine
from app.risk_detection.rule_engine import MODEL_VERSION, PROMPT_VERSION
from app.risk_detection.validation import RiskInputError
from test_risk_detection_http import ROUTE, assert_error, client_for, finding, payload, release


CASES = json.loads((Path(__file__).parent / "fixtures/safety48-rules/zip_r2_proposed_40.json")
                   .read_text(encoding="utf-8"))


@pytest.fixture(autouse=True)
def forbid_external_network(monkeypatch):
    original = socket.socket.connect

    def denied(*args, **kwargs):
        pytest.fail("External network access is forbidden in Safety Guard tests")

    def loopback_only(connection, address):
        # Windows asyncio implements socketpair with a numeric loopback socket.
        if not isinstance(address, tuple) or not ipaddress.ip_address(address[0]).is_loopback:
            denied()
        return original(connection, address)

    monkeypatch.setattr(socket, "create_connection", denied)
    monkeypatch.setattr(socket.socket, "connect", loopback_only)


def engine(counter=None):
    # A synthetic counter for in-process contract tests, never an actual tokenizer.
    return LocalRuleRiskEngine(count_input_tokens=counter if counter is not None else lambda _: 100)


def message(text, sequence=1, speaker="A", fresh=True, url_masked=False):
    return dict(sequence=sequence, speaker=speaker, text=text, fresh=fresh, urlMasked=url_masked)


@pytest.mark.parametrize("case", CASES, ids=lambda case: f"zip-r2-{case['id']:02}")
def test_proposed_cases_through_existing_http(release, case, caplog):
    request = payload(release)
    request["contextBundle"] = [
        {**{k: v for k, v in raw.items() if k != "url_masked"}, "urlMasked": raw["url_masked"]}
        for raw in case["messages"]
    ]
    response = client_for(release, engine()).post(ROUTE, json=request)
    assert response.status_code == 200
    result = response.json()
    # Findings are an unordered set of evidence; the ZIP golden test also
    # canonicalizes them. No severity/priority ordering is part of the contract.
    canonical = lambda findings: sorted(tuple(sorted(item.items())) for item in findings)
    assert canonical(result["verdict"]["findings"]) == canonical(case["expectedFindings"])
    assert result == {
        "attemptId": request["attemptId"], "manifest": request["manifest"],
        "verdict": {"kind": case["expectedKind"], "findings": result["verdict"]["findings"],
                    "confidence": None, "modelVersion": MODEL_VERSION, "promptVersion": PROMPT_VERSION},
    }
    assert response.headers["cache-control"] == "no-store"
    for item in request["contextBundle"]:
        assert item["text"] not in response.text
        assert item["text"] not in caplog.text


def test_counter_receives_the_entire_canonical_context_without_manifest_or_ids(release):
    request = payload(release)
    request["contextBundle"] = [message("원금 보장해. 인증금 보내.", fresh=False),
                                 message("지금 보내.", sequence=2)]
    before = deepcopy(request)
    seen = []

    def counter(text):
        seen.append(text)
        return 3000

    response = client_for(release, engine(counter)).post(ROUTE, json=request)
    assert response.status_code == 200
    assert len(seen) == 1 and json.loads(seen[0]) == {"contextBundle": request["contextBundle"]}
    assert request == before
    assert "manifest" not in seen[0] and "contextId" not in seen[0] and "attemptId" not in seen[0]
    assert response.json()["verdict"]["findings"] == [
        dict(sequence=2, category="FRAUD", noticeType="MONEY_REQUEST", audience="RECIPIENT")]


def test_counter_over_limit_is_rejected_before_rules_run(release, monkeypatch):
    def must_not_run(_):
        pytest.fail("Rules must not run after the 3,000-token limit")

    monkeypatch.setattr(local_engine, "classify", must_not_run)
    response = client_for(release, engine(lambda _: 3001)).post(ROUTE, json=payload(release))
    assert_error(response, 400, "INVALID_REQUEST")


@pytest.mark.parametrize("value", [-1, True, 1.5, "100", None])
def test_invalid_counter_output_is_an_engine_failure(release, value):
    response = client_for(release, engine(lambda _: value)).post(ROUTE, json=payload(release))
    assert_error(response, 503, "SERVICE_UNAVAILABLE")


@pytest.mark.parametrize("stage", ["tokenizer", "rules"])
def test_engine_failure_is_not_insufficient_or_normal(release, monkeypatch, caplog, stage):
    secret = "synthetic-secret-tokenizer-path-or-message"

    def fail(_):
        raise TimeoutError(secret)

    instance = engine(fail if stage == "tokenizer" else None)
    if stage == "rules":
        monkeypatch.setattr(local_engine, "classify", fail)
    response = client_for(release, instance).post(ROUTE, json=payload(release))
    assert_error(response, 503, "SERVICE_UNAVAILABLE")
    assert "verdict" not in response.json()
    assert secret not in response.text and secret not in caplog.text


def test_ambiguous_valid_message_is_insufficient_not_failed(release):
    request = payload(release)
    request["contextBundle"] = [message("지금 보내.")]
    response = client_for(release, engine()).post(ROUTE, json=request)
    assert response.status_code == 200
    assert response.json()["verdict"]["kind"] == "INSUFFICIENT"
    assert response.json()["verdict"]["findings"] == []


@pytest.mark.parametrize(("text", "categories"), [
    pytest.param("내가 널 때릴 거야. 어제 누가 전화했어?", ("THREAT",), id="threat-before-ordinary"),
    pytest.param("친구가 ‘널 죽이겠어!’라고 말했어. 널 때리겠어.", ("THREAT",), id="threat-after-report"),
    pytest.param("친구가 ‘널 죽이겠어!’라고 말했어. 그 말을 듣고 무서웠어.", (), id="report-only"),
    pytest.param("비밀번호를 절대 알려 주지 마. 인증번호를 입력하면 안 돼.", (), id="credential-prohibitions"),
    pytest.param("비밀번호나 인증번호를 알려주면 안 돼.", (), id="do-not-tell"),
    pytest.param("비밀번호와 인증번호를 다른 사람에게 보내면 안 돼.", (), id="do-not-send"),
    pytest.param("인증번호를 보내지 마. 지금 비밀번호를 나에게 알려 줘.", ("OTHER",), id="request-after-warning"),
    pytest.param("인증번호를 보내지 말고 비밀번호를 나에게 알려 줘.", ("OTHER",), id="request-in-next-clause"),
    pytest.param("비밀번호를 다른 사람에게 알려 주지 말고 나에게 보내.", ("OTHER",),
                 id="same-credential-request-after-warning"),
    pytest.param("비밀번호는 아무에게도 보내지 말고 나한테만 알려 주세요.", ("OTHER",),
                 id="informal-recipient-after-warning"),
    pytest.param("비밀번호는 아무에게도 보내지 말고 저한테만 알려 주세요.", ("OTHER",),
                 id="polite-recipient-after-warning"),
    pytest.param("인증번호는 아무에게도 알려 주지 말고 제게만 보내 주세요.", ("OTHER",),
                 id="polite-dative-recipient-after-warning"),
    pytest.param("비밀번호를 저한테만 알려 주세요.", ("OTHER",), id="direct-polite-request"),
    pytest.param("비밀번호는 아무에게도 보내지 마세요.", (), id="polite-prevention-only"),
    pytest.param("비밀번호를 알려 주지 말고 사진을 저한테만 보내 주세요.", (),
                 id="different-object-with-polite-recipient"),
    pytest.param("비밀번호를 알려 주면 안 돼. 너를 죽이겠어.", ("THREAT",), id="threat-after-warning"),
    pytest.param("인증번호를 다른 사람한테 보내면 안 돼, 대신 여기로 넘겨 줘.", ("OTHER",),
                 id="verification-code-request-after-warning"),
    pytest.param("비밀번호는 공개 채팅에 입력하지 말고 내게만 따로 알려 줘.", ("OTHER",),
                 id="private-password-request-after-public-warning"),
    pytest.param("인증번호를 다른 사람한테 보내지 말고, 그런 요구는 거절해.", (),
                 id="credential-warning-with-refusal-advice"),
    pytest.param("친구가 ‘널 때리겠어’라고 말했는데, 나도 널 때리겠어.", ("THREAT",),
                 id="direct-threat-in-reporting-sentence"),
    pytest.param("친구가 ‘널 때리겠어’라고 말했는데, 나는 그 말을 듣고 자리를 피했어.", (),
                 id="reported-threat-with-ordinary-followup"),
    pytest.param("비밀번호를 보내지 마, 그래도 약속 장소에 오면 널 때리겠어.", ("THREAT",),
                 id="direct-threat-in-credential-warning-sentence"),
    pytest.param("내일 몇 시에 만날까? 네 사진을 퍼뜨리겠어.", ("THREAT",),
                 id="photo-leak-threat-after-ordinary-question"),
    pytest.param("인증번호를 다른 사람에게 알려 주지 말고 나에게 보내, 거절하면 널 때리겠어.",
                 ("OTHER", "THREAT"), id="credential-request-and-direct-threat"),
    pytest.param("비밀번호를 알려 주지 말고 사진을 나에게 보내.", (), id="different-object-after-warning"),
    pytest.param("널 때리겠어, 비밀번호는 다른 사람에게 보내지 마.", ("THREAT",),
                 id="direct-threat-before-warning-in-same-sentence"),
    pytest.param("친구가 내일 찾아가서, 널 때리겠다고 말했어.", (), id="indirect-report-across-comma"),
    pytest.param("널 때리겠다는 말은 하지 않을게.", (),
                 id="denied-threat-review"),
    pytest.param("널 해치겠다는 말은 하지 않을게. 거절을 존중해.", (),
                 id="denied-threat-earlier-review"),
    pytest.param("찾아가서 때리겠다는 말은 하지 않을게.", (),
                 id="denied-threat-after-approach"),
    pytest.param("널 때리겠어.", ("THREAT",),
                 id="direct-threat"),
    pytest.param("널 때리겠다는 말은 진심이야.", ("THREAT",),
                 id="affirmed-threat-statement"),
    pytest.param("널 해치겠다는 말은 하지 않을게. 그래도 널 때리겠어.", ("THREAT",),
                 id="denied-threat-then-direct"),
    pytest.param("널 때리겠어. 다른 말은 하지 않을게.", ("THREAT",),
                 id="direct-threat-then-unrelated-denial"),
    pytest.param("비밀번호를\n나에게 보내.", ("OTHER",),
                 id="credential-newline-review"),
    pytest.param("비밀번호를 나에게 보내.", ("OTHER",),
                 id="credential-no-newline"),
    pytest.param("인증번호를 저에게\n알려 주세요.", ("OTHER",),
                 id="credential-newline-after-recipient"),
    pytest.param("비밀번호를\n나에게 보내지 마세요.", (),
                 id="credential-wrapped-prohibition"),
    pytest.param("비밀번호를\n나에게 보내\n지 마세요.", (),
                 id="credential-prohibition-on-next-line"),
    pytest.param("비밀번호를\n나에게 사진을 보내.", (),
                 id="credential-wrapped-different-object"),
    pytest.param("비밀번호는 따로 보관해\n나에게 사진을 보내.", (),
                 id="credential-independent-unpunctuated-lines"),
    pytest.param("친구가 비밀번호를\n나에게 보내라고 말했어.", (),
                 id="credential-wrapped-report"),
    pytest.param("비밀번호를 기억해.\n나에게 사진을 보내.", (),
                 id="credential-punctuation-before-newline"),
    pytest.param("친구가 비밀번호를\n나에게 보내라고\n말했어.", (),
                 id="credential-report-with-wrapped-suffix"),
    pytest.param("친구가\n비밀번호를\n나에게 보내라고 말했어.", (),
                 id="credential-report-with-wrapped-subject"),
    pytest.param("친구가 ‘안부 전해’라고 말했어. 비밀번호를\n나에게 보내.", ("OTHER",),
                 id="wrapped-direct-request-after-report"),
    pytest.param("널 때리겠다는 말은 하지 않지만 네 동생을 패겠어.", ("THREAT",),
                 id="direct-threat-after-denial-in-same-sentence"),
    pytest.param("비밀번호를\n입력\n하면 로그인돼.", (),
                 id="credential-wrapped-routine-login"),
    pytest.param("비밀번호를\n나에게 보내\n는 건 안 돼.", (),
                 id="credential-wrap-before-unresolved-prohibition"),
    pytest.param("비밀번호를\n나에게\n보내 줘.", ("OTHER",),
                 id="credential-wrap-on-multiple-request-lines"),
])
def test_review_p2_reporting_and_credential_requests(release, text, categories):
    request = payload(release)
    request["contextBundle"] = [message(text, sequence=7)]
    response = client_for(release, engine()).post(ROUTE, json=request)
    assert response.status_code == 200
    verdict = response.json()["verdict"]
    expected_findings = {
        "THREAT": finding(sequence=7, category="THREAT", notice="HARASSMENT", audience="BOTH"),
        "OTHER": finding(sequence=7, category="OTHER", notice="PERSONAL_INFO"),
    }
    assert verdict["kind"] == ("RISK" if categories else "NORMAL")
    assert sorted(verdict["findings"], key=lambda item: item["category"]) == [
        expected_findings[category] for category in sorted(categories)]


@pytest.mark.parametrize("first_fresh", [True, False])
def test_credential_fragments_in_separate_messages_are_not_joined(release, first_fresh):
    request = payload(release)
    request["contextBundle"] = [
        message("비밀번호를", sequence=2, fresh=first_fresh),
        message("나에게 보내.", sequence=7),
    ]
    response = client_for(release, engine()).post(ROUTE, json=request)
    assert response.status_code == 200
    verdict = response.json()["verdict"]
    assert verdict["kind"] == "NORMAL"
    assert verdict["findings"] == []


@pytest.mark.parametrize(("text", "expected"), [
    pytest.param("인증 번호를 나한테 보내.", finding(category="OTHER", notice="PERSONAL_INFO"),
                 id="credential-spacing"),
    pytest.param("로그인할 때 문자로 온 여섯 자리 숫자, 그거 나한테 알려 줘.",
                 finding(category="OTHER", notice="PERSONAL_INFO"), id="credential-paraphrase"),
    pytest.param("비밀번호를 새로 입력하면 로그인돼.", None, id="routine-login"),
    pytest.param("비밀번호를 내가 준 링크에 입력하면 로그인돼.",
                 finding(category="OTHER", notice="PERSONAL_INFO"), id="credential-entry-on-shared-link"),
    pytest.param("또 연락하면 네 사진 단톡방에 다 올려 버린다.",
                 finding(category="THREAT", notice="HARASSMENT", audience="BOTH"), id="photo-leak-in-group-chat"),
    pytest.param("걱정 마. 네 사진은 절대 유포하지 않을게.", None, id="denied-photo-leak"),
    pytest.param("투자하면 무조건 두 배로 돌려줄게. 시작하려면 가입비부터 보내.", finding(),
                 id="guaranteed-double-return"),
    pytest.param("투자하면 무조건 두 배로 돌려주지는 않아. 투자금은 네가 직접 관리해.", None,
                 id="denied-double-return-with-funds-mention"),
    pytest.param("투자하면 무조건 두 배로 돌려주지는 않아. 가입비를 보내.", None,
                 id="denied-double-return-with-payment-request"),
    pytest.param("투자하면 무조건 두 배로 돌려줄게. 투자금은 네가 직접 관리해.", None,
                 id="positive-guarantee-without-payment-request"),
    pytest.param("수익은 보장하지 않아. 투자금은 네가 직접 관리해.", None,
                 id="denied-profit-guarantee-with-funds-mention"),
    pytest.param("수익을 보장 안 해. 투자금부터 보내 줘.", None,
                 id="negative-guarantee-with-explicit-transfer"),
    pytest.param("수익을 보장해. 투자금은 바로 보내 주세요.", finding(),
                 id="positive-guarantee-with-explicit-transfer"),
    pytest.param("원금 보장해. 가입비부터 내.", finding(), id="positive-guarantee-with-fee-request"),
    pytest.param("원금 보장해. 가입비는 내지 마세요.", None, id="positive-guarantee-with-denied-payment"),
    pytest.param("원금 보장해. 투자금을 보내지는 않아.", None, id="positive-guarantee-with-denied-transfer"),
    pytest.param("원금 보장해. 송금 한도는 네가 확인해.", None, id="transfer-mentioned-without-request"),
    pytest.param("은행 담당자야. 수수료를 먼저 송금해. 확인 전화는 하지 마.", finding(),
                 id="upfront-payment-with-pretext-and-verification-block"),
    pytest.param("원금 보장 투자라고 적혀 있던데, 입금은 하지 않았어.", None, id="denied-deposit"),
    pytest.param("보안 경고가 뜨면 그냥 닫고 이 앱을 설치해.",
                 finding(category="OTHER", notice="OFF_PLATFORM"), id="dismiss-warning-to-install"),
    pytest.param("보안 경고는 무시하지 말고 앱 설치를 취소해.", None, id="heed-security-warning"),
    pytest.param("그만하라며? 그래도 네가 울 때까지 매일 비웃어 줄게.",
                 finding(category="HARASSMENT", notice="HARASSMENT", audience="BOTH"), id="continued-ridicule"),
])
def test_alternate_risk_wording_through_http(release, text, expected):
    request = payload(release)
    request["contextBundle"] = [message(text)]
    response = client_for(release, engine()).post(ROUTE, json=request)
    assert response.status_code == 200
    verdict = response.json()["verdict"]
    assert verdict["kind"] == ("RISK" if expected else "NORMAL")
    assert verdict["findings"] == ([expected] if expected else [])


@pytest.mark.parametrize(("text", "risky"), [
    pytest.param("원금 보장해. 보증금을 보내 줘.", True,
                 id="without-amount"),
    pytest.param("원금 보장해. 보증금 5만 원을 보내 줘.", True,
                 id="korean-amount"),
    pytest.param("원금 보장해. 보증금 50000원을 보내 줘.", True,
                 id="numeric-amount"),
    pytest.param("원금 보장해. 보증금 5만 원이야.", False,
                 id="amount-only"),
    pytest.param("원금 보장해. 보증금 5만 원은 보내지 마.", False,
                 id="prohibited-payment"),
    pytest.param("투자금 5만 원은 네가 직접 관리해.", False,
                 id="self-managed-funds"),
    pytest.param("원금 보장해. 보증금 50,000원을 먼저 보내 줘.", True,
                 id="grouped-digits"),
    pytest.param("원금 보장해. 가입비 5만원부터 내.", True,
                 id="attached-amount-and-fee-request"),
    pytest.param("원금 보장해. 보증금 5만 원을 보내지는 않아.", False,
                 id="negated-payment"),
    pytest.param("원금 보장해. 보증금 5만 원을 보내면 안 돼.", False,
                 id="preventive-advice"),
    pytest.param("투자하면 무조건 두 배로 돌려주지는 않아. 보증금 5만 원을 보내 줘.", False,
                 id="denied-guarantee"),
    pytest.param("원금 보장해. 보증금 5만 원을 보내 주지 마세요.", False,
                 id="polite-prohibition"),
])
def test_payment_request_with_optional_won_amount(release, text, risky):
    request = payload(release)
    request["contextBundle"] = [
        message("합성 대화입니다.", sequence=2, speaker="B", fresh=False),
        message(text, sequence=7),
    ]
    response = client_for(release, engine()).post(ROUTE, json=request)
    assert response.status_code == 200
    verdict = response.json()["verdict"]
    assert verdict["kind"] == ("RISK" if risky else "NORMAL")
    assert verdict["findings"] == ([finding(sequence=7)] if risky else [])


# Synthetic SG-013–015 reproductions and paired controls, not original review messages.
@pytest.mark.parametrize(("text", "expected"), [
    pytest.param("인증번호를\n나에게 보내야 해.", finding(7, "OTHER", "PERSONAL_INFO"),
                 id="sg-013-wrapped-credential-request"),
    pytest.param("인증번호를 나에게 보내야 해.", finding(7, "OTHER", "PERSONAL_INFO"),
                 id="sg-013-single-line-credential-request"),
    pytest.param("비밀 번호를\n저한테\t알려야\n해요.", finding(7, "OTHER", "PERSONAL_INFO"),
                 id="sg-013-lf-tab-and-wrapped-ending"),
    pytest.param("인증번호를\n나에게 보내야 하는 건 아니야.", None, id="sg-013-no-obligation"),
    pytest.param("인증번호를\n나에게 보내지 마세요.", None, id="sg-013-prohibition"),
    pytest.param("친구가 인증번호를\n보내야 한다고 말했어.", None, id="sg-013-reported-request"),
    pytest.param("인증번호는 기억해.\n사진은 나에게 보내야 해.", None, id="sg-013-different-object"),
    pytest.param("친구가 ‘인증번호를\n보내야 해’라고 말했어.", None, id="sg-013-wrapped-quote"),
    pytest.param("친구가 ‘인증번호를\n보내야 해’라고 말했어. 그래도 인증번호를\n나에게 보내야 해.",
                 finding(7, "OTHER", "PERSONAL_INFO"), id="sg-013-wrapped-quote-with-separate-request"),
    pytest.param("원금 보장해. 지금 송금해 주지 마세요.", None, id="sg-014-transfer-prohibition"),
    pytest.param("원금 보장해. 지금 송금해 주세요.", finding(7), id="sg-014-direct-transfer"),
    pytest.param("원금 보장해. 송금해\n주지\t마세요.", None, id="sg-014-wrapped-prohibition"),
    pytest.param("원금 보장해. 송금해 주면 안 돼.", None, id="sg-014-conditional-prohibition"),
    pytest.param("원금 보장해. 송금해주지마세요.", None, id="sg-014-unspaced-prohibition"),
    pytest.param("은행 담당자야. 수수료를 먼저 송금해 주지 마세요. 확인 전화는 하지 마.",
                 None, id="sg-014-upfront-fee-prohibition"),
    pytest.param("은행 담당자야. 수수료를 먼저 송금해 주세요. 확인 전화는 하지 마.",
                 finding(7), id="sg-014-upfront-fee-request"),
    pytest.param("원금 보장해. 송금해 주지 말고, 대신 보증금을 보내 줘.",
                 finding(7), id="sg-014-prohibition-with-separate-request"),
    pytest.param("원금 보장해. 송금부터 해 주지 마세요.", None, id="sg-014-priority-transfer-prohibition"),
    pytest.param("원금 보장이라며 송금해 달라는 요구는 거절하세요.", None,
                 id="sg-014-reject-transfer-request"),
    pytest.param("원금 보장이라며 ‘송금해 주세요’라는 요구는 거절하세요.", None,
                 id="sg-014-reject-quoted-transfer-request"),
    pytest.param("원금 보장해. ‘송금해 주세요’라는 요구를 거절하지 마.", finding(7),
                 id="sg-014-do-not-reject-transfer"),
    pytest.param("지금 송금해 주지 마세요.", None, id="sg-014-transfer-warning-alone"),
    pytest.param("원금 보장해.", None, id="sg-014-guarantee-alone"),
    pytest.param("하루 수익 30%를 보장해. 보증금은 보내지 마세요.", None,
                 id="sg-014-yield-guarantee-without-payment"),
    pytest.param("친구가 ‘원금 보장하니 돈을 보내’라고 말했어. 믿지 마.", None,
                 id="sg-014-reported-fraud-warning"),
    pytest.param("원금 보장해. 지금 송금해 주지 마세요. 내일 송금해 주세요.", finding(7),
                 id="sg-014-deferred-transfer"),
    pytest.param("원금 보장해. 지금은 송금하지 마세요. 대신 보증금을 먼저 보내 줘.", finding(7),
                 id="sg-014-transfer-after-warning"),
    pytest.param("원금 보장해. 송금하지 마세요. 저녁값을 보내 줘.", None,
                 id="sg-014-unrelated-reimbursement"),
    pytest.param("원금 보장해. 송금해 주지 않으면 안 돼.", finding(7), id="sg-014-double-negative"),
    pytest.param("원금 보장해. 송금하지 않으면 안 됩니다.", finding(7), id="sg-014-double-negative-no-auxiliary"),
    pytest.param("원금 보장해. 송금해 주지 않으면 기회를 놓쳐요.", finding(7), id="sg-014-pressure"),
    pytest.param("원금 보장해. 송금해 주지 않으면 안전해요.", None, id="sg-014-benign-condition"),
    pytest.param("원금 보장이라며 ‘송금해 주세요’라는 요구는 거절하세요. 그래도 원금 보장해. 보증금을 보내 줘.",
                 finding(7), id="sg-014-quoted-warning-with-separate-fraud"),
    pytest.param("네 사진을 단톡방에 올려 버린다는 말은 한 적 없어.", None,
                 id="sg-015-denied-photo-threat"),
    pytest.param("네 사진을 단톡방에 올려 버린다.", finding(7, "THREAT", "HARASSMENT", "BOTH"),
                 id="sg-015-direct-photo-threat"),
    pytest.param("네 사진을 유포하겠다는 말은\n한 적이 없어.", None, id="sg-015-wrapped-denial"),
    pytest.param("네 사진을 퍼뜨리겠다고 말한 적 없어.", None, id="sg-015-indirect-denial"),
    pytest.param("네 사진을 퍼뜨리지 마세요.", None, id="sg-015-photo-sharing-prohibition"),
    pytest.param("네 사진을 유포하겠다는 말은 한 적 없어. 그래도 네 사진을 퍼뜨리겠어.",
                 finding(7, "THREAT", "HARASSMENT", "BOTH"), id="sg-015-denial-with-separate-threat"),
    pytest.param("사진을 보내지 마세요.", None, id="sg-015-simple-photo-prevention"),
    pytest.param("싫다고 해도 성적인 사진을 보내면 안 돼.", None, id="sg-015-photo-prevention"),
    pytest.param("싫다고 해도 성적인 사진을 보낼 거야.", finding(7, "HARASSMENT", "HARASSMENT", "BOTH"),
                 id="sg-015-direct-sexual-photo-threat"),
    pytest.param("싫다고 해도 성적인 사진을 보내\n주지\t마세요.", None, id="sg-015-wrapped-photo-prevention"),
    pytest.param("거절했으면 성적인 사진을 계속 보내면 안 돼.", None, id="sg-015-repeated-photo-prevention"),
    pytest.param("‘싫어도 성적인 사진을 보내’라는 요구는 거절하세요.", None,
                 id="sg-015-reject-quoted-photo-request"),
    pytest.param("싫다고 해도 성적인 사진을 안 보내면 안 돼.", finding(7, "HARASSMENT", "HARASSMENT", "BOTH"),
                 id="sg-015-photo-double-negation"),
    pytest.param("싫다고 해도 성적인 사진을 보내면 안 돼. 그래도 싫어도 벗은 사진은 보내 줘.",
                 finding(7, "HARASSMENT", "HARASSMENT", "BOTH"), id="sg-015-prevention-with-separate-request"),
])
def test_sg_013_to_015_rule_regressions_through_http(release, text, expected):
    request = payload(release)
    request["contextBundle"] = [message(text, sequence=7)]
    response = client_for(release, engine()).post(ROUTE, json=request)

    assert response.status_code == 200
    verdict = response.json()["verdict"]
    assert verdict["kind"] == ("RISK" if expected else "NORMAL")
    assert verdict["findings"] == ([expected] if expected else [])


@pytest.mark.parametrize(("prior_text", "speaker", "kind"), [
    ("인증번호를 나한테 알려 줘.", "A", "RISK"),
    ("인증번호를 나한테 알려 줘.", "B", "INSUFFICIENT"),
    ("인증번호를 다른 사람에게 알려 주지 마.", "A", "INSUFFICIENT"),
])
def test_credential_followup_uses_only_a_prior_same_speaker_request(release, prior_text, speaker, kind):
    request = payload(release)
    request["contextBundle"] = [message(prior_text, fresh=False), message("지금 보내.", 2, speaker=speaker)]
    response = client_for(release, engine()).post(ROUTE, json=request)
    assert response.status_code == 200
    verdict = response.json()["verdict"]
    assert verdict["kind"] == kind
    assert verdict["findings"] == (
        [finding(sequence=2, category="OTHER", notice="PERSONAL_INFO")] if kind == "RISK" else [])


@pytest.mark.parametrize("invalid", [None, 0])
def test_no_default_or_noncallable_token_counter(invalid):
    with pytest.raises(TypeError):
        LocalRuleRiskEngine()
    with pytest.raises(TypeError):
        LocalRuleRiskEngine(count_input_tokens=invalid)


@pytest.mark.parametrize("case", ["old-only", "duplicate", "old-after-new", "blank", "raw-url",
                                  "raw-email", "raw-phone", "extra-field", "too-many", "too-long"])
@pytest.mark.parametrize("operation", ["count_tokens", "analyze"])
def test_direct_engine_input_checks_match_the_http_boundary(release, case, operation):
    messages = [message("합성 대화")]
    if case == "old-only":
        messages[0]["fresh"] = False
    elif case == "duplicate":
        messages *= 2
    elif case == "old-after-new":
        messages += [message("이전 문맥", 2, fresh=False)]
    elif case == "blank":
        messages[0]["text"] = " \n\t "
    elif case.startswith("raw-"):
        messages[0]["text"] = {"raw-url": "https://example.invalid", "raw-email": "test@example.invalid",
                               "raw-phone": "010-0000-0000"}[case]
    elif case == "extra-field":
        messages[0]["userId"] = 123
    elif case == "too-many":
        messages = [message("합성", i + 1) for i in range(11)]
    elif case == "too-long":
        messages = [message("가" * 2001), message("나" * 2000, 2)]
    with pytest.raises(RiskInputError, match="^Invalid approved context$"):
        getattr(engine(), operation)(messages, release[2])


@pytest.mark.parametrize("speaker", ["A", "B"])
def test_short_followup_needs_the_latest_same_speaker_solicitation(release, speaker):
    opposite = "B" if speaker == "A" else "A"
    messages = [message("원금 보장하니 인증금을 보내.", speaker=speaker, fresh=False),
                message("밥값은 반씩 내자.", 2, speaker=speaker, fresh=False),
                message("원금 보장하니 인증금을 보내.", 3, speaker=opposite, fresh=False),
                message("지금 보내.", 4, speaker=speaker)]
    assert engine().analyze(messages, release[2])["kind"] == "INSUFFICIENT"


def test_45_findings_coalesce_without_losing_a_fresh_notice(release):
    text = ("원금 보장하니 인증금 송금해. 너 같은 멍청이. 네 사진을 퍼뜨리겠다. "
            "비밀번호를 보내. 보안 경고 무시하고 앱 설치해.")
    request = payload(release)
    request["contextBundle"] = [message(text, i + 1) for i in range(9)]
    response = client_for(release, engine()).post(ROUTE, json=request)
    assert response.status_code == 200
    verdict = response.json()["verdict"]
    assert verdict["kind"] == "RISK"
    assert len(verdict["findings"]) == 36
    expected = {
        (sequence, category, notice, audience)
        for sequence in range(1, 10)
        for category, notice, audience in [
            ("FRAUD", "MONEY_REQUEST", "RECIPIENT"),
            ("THREAT", "HARASSMENT", "BOTH"),
            ("OTHER", "PERSONAL_INFO", "RECIPIENT"),
            ("OTHER", "OFF_PLATFORM", "RECIPIENT"),
        ]
    }
    assert {(f["sequence"], f["category"], f["noticeType"], f["audience"])
            for f in verdict["findings"]} == expected


@pytest.mark.parametrize("change", ["old-evidence", "duplicate", "wrong-mapping", "normal-with-risk"])
def test_invalid_internal_findings_are_rejected_even_without_http(release, monkeypatch, change):
    messages = [message("원금 보장하니 인증금을 보내.", fresh=False), message("지금 보내.", 2)]
    result = engine().analyze(messages, release[2])
    if change == "old-evidence":
        result["findings"][0]["sequence"] = 1
    elif change == "duplicate":
        result["findings"] *= 2
    elif change == "wrong-mapping":
        result["findings"][0]["audience"] = "SENDER"
    else:
        result["kind"] = "NORMAL"
    monkeypatch.setattr(local_engine, "classify", lambda _: result)
    with pytest.raises(EngineUnavailableError, match="^Risk rule analysis unavailable$"):
        engine().analyze(messages, release[2])


@pytest.mark.parametrize("mutate", ["artifact", "descriptor"])
def test_actual_rules_still_require_release_integrity_after_analysis(release, monkeypatch, mutate):
    classify = local_engine.classify

    def changed(messages):
        result = classify(messages)
        if mutate == "artifact":
            release[1].write_bytes(b"changed-synthetic-rules")
        else:
            release[0].write_text("{}", encoding="utf-8")
        return result

    monkeypatch.setattr(local_engine, "classify", changed)
    response = client_for(release, engine()).post(ROUTE, json=payload(release))
    assert_error(response, 422, "POLICY_UNRESOLVED")
