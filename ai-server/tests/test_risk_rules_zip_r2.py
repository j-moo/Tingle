"""ZIP r2's 77 probes and the older draft's 48 proposed cases.

The no-fresh batch probe uses current HTTP rejection instead of prototype
INSUFFICIENT. The other 6 of the ZIP's 83 probes are icebreaker-only.
Synthetic test counter only; this module never registers a production engine.
"""

import json
from pathlib import Path

import pytest

from app.risk_detection.local_engine import LocalRuleRiskEngine
from app.risk_detection.validation import RiskInputError
from test_risk_detection_http import VERSION_FIELDS


TEST_MANIFEST = {field: "synthetic-rules-v1" for field in VERSION_FIELDS}
TEST_MANIFEST.update(releaseId="11111111-1111-4111-8111-111111111111", contentHash="0" * 64)


def _message(sequence, speaker, text, fresh, url_masked=False):
    return dict(sequence=sequence, speaker=speaker, text=text, fresh=fresh, urlMasked=url_masked)


def classify(messages):
    engine = LocalRuleRiskEngine(count_input_tokens=lambda _: 100)
    return engine.analyze(messages, TEST_MANIFEST)


# Original ZIP r2 rule probes.
def m(text: str, *, sequence: int = 1, speaker: str = "A", fresh: bool = True) -> dict:
    return _message(sequence=sequence, speaker=speaker, text=text, fresh=fresh)


@pytest.mark.parametrize("text,expected", [
    ("원금 100% 보장하니 가입 인증금부터 송금해.", "FRAUD"),
    ("손실 없는 투자야. 보증금부터 입금해.", "FRAUD"),
    ("오늘 집 앞에서 기다렸다가 널 때리겠다.", "THREAT"),
    ("돈 안 보내면 네 사진을 퍼뜨리겠어.", "THREAT"),
    ("너 같은 멍청이는 사람 취급도 못 받아.", "HARASSMENT"),
    ("싫어도 계속 성적인 말을 보낼 거야.", "HARASSMENT"),
    ("비밀번호와 인증번호를 나에게 보내.", "OTHER"),
    ("보안 경고는 무시하고 그 앱을 설치해.", "OTHER"),
])
def test_obvious_risks(text, expected):
    output = classify([m(text)])
    assert output["kind"] == "RISK"
    assert expected in {finding["category"] for finding in output["findings"]}
    assert all(finding["sequence"] == 1 for finding in output["findings"])
    assert "text" not in str(output)


@pytest.mark.parametrize("text", [
    "오늘 점심 뭐 먹을까?",
    "밥값은 반씩 내자.",
    "어제 빌린 오천 원은 내일 갚을게.",
    "투자 공부를 시작했는데 손실 위험부터 알아보려고 해.",
    "괜찮으면 카톡으로 연락할까?",
    "오늘 공연 진짜 죽여준다.",
    "소설의 악역이 널 가만두지 않겠다고 말하는 장면이야.",
    "어제 모르는 사람이 나한테 죽이겠다고 해서 무서웠어.",
    "누가 계속 성적인 사진을 요구해서 차단했어.",
    "수익 보장이라면서 송금하라는 말은 믿지 마.",
    "화가 나도 상대를 때리겠다고 말하면 안 돼.",
    "보안 경고 내용이 왜 뜨는지 같이 알아보자.",
])
def test_normal_and_quoted_messages(text):
    output = classify([m(text)])
    assert output["kind"] == "NORMAL"
    assert output["findings"] == []


def test_ambiguous_short_message_is_not_clean():
    assert classify([m("지금 보내.")])["kind"] == "INSUFFICIENT"


def test_context_followup_is_fresh_evidence():
    output = classify([
        m("원금 보장 투자에 들어오려면 인증금을 보내야 해.", fresh=False),
        m("지금 보내.", sequence=2),
    ])
    assert output["kind"] == "RISK"
    assert output["findings"] == [{
        "sequence": 2, "category": "FRAUD",
        "noticeType": "MONEY_REQUEST", "audience": "RECIPIENT",
    }]


def test_other_speaker_refusal_is_normal():
    output = classify([
        m("수익을 보장하니 인증금을 보내.", fresh=False),
        m("안 보낼게. 그런 요구는 거절해.", speaker="B", sequence=2),
    ])
    assert output["kind"] == "NORMAL"


def test_only_old_risk_cannot_generate_new_finding():
    # Current HTTP rejects a batch with no fresh message before inference.
    with pytest.raises(RiskInputError, match="Invalid approved context"):
        classify([m("원금 보장. 보증금 입금해.", fresh=False)])


def test_multiple_findings_same_new_message():
    output = classify([m("너 같은 멍청이. 찾아가서 널 때리겠다.")])
    assert {(f["category"], f["noticeType"], f["audience"]) for f in output["findings"]} == {
        ("THREAT", "HARASSMENT", "BOTH"),
        ("HARASSMENT", "HARASSMENT", "BOTH"),
    }


def test_new_user_ids_or_raw_text_never_enter_output():
    text = "원금 보장하니 보증금을 입금해."
    result = classify([m(text)])
    assert text not in str(result)
    assert "userId" not in result
    assert "contextId" not in result
    assert result["confidence"] is None


# Original 40 proposed cases; not approved ground truth.
CASES = json.loads((Path(__file__).parent / 'fixtures/safety48-rules/zip_r2_proposed_40.json').read_text(encoding='utf-8'))


@pytest.mark.parametrize('case', CASES, ids=lambda item: f"safety48-{item['id']:02}")
def test_proposed_golden_case(case):
    messages = [_message(**raw) for raw in case['messages']]
    output = classify(messages)
    assert output['kind'] == case['expectedKind']
    def canonical(findings):
        return sorted((f['sequence'], f['category'], f['noticeType'], f['audience']) for f in findings)
    assert canonical(output['findings']) == canonical(case['expectedFindings'])


# Original ZIP r2 adversarial regression probes.

def msg(text, seq=1, speaker="A", fresh=True):
    return _message(sequence=seq, speaker=speaker, text=text, fresh=fresh)


@pytest.mark.parametrize("text,expected_category", [
    ("어제 모르는 사람이 협박했어. 그런데 내가 오늘 네 집에 찾아가 널 때리겠다.", "THREAT"),
    ("소설 속 악역이 협박하는 장면이야. 하지만 나는 네 사진을 퍼뜨리겠다.", "THREAT"),
    ("원금 보장하면 사기니까 믿지 마. 하지만 지금부터 내 투자는 원금 보장해. 인증금 송금해.", "FRAUD"),
    ("어제 모르는 사람이 나한테 돈을 요구했어. 그런데 나는 네 비밀번호와 인증번호를 보내 달라고 할 거야.", "OTHER"),
    ("상대가 그런 요구를 거절했대. 그래도 나는 네 집 앞에서 기다렸다가 널 때리겠다.", "THREAT"),
])
def test_reporting_does_not_suppress_separate_new_direct_threat(text, expected_category):
    result = classify([msg(text)])
    assert result["kind"] == "RISK"
    assert expected_category in {f["category"] for f in result["findings"]}


@pytest.mark.parametrize("text", [
    "어제 모르는 사람이 나한테 '네 사진을 퍼뜨리겠다'고 해서 무서웠어.",
    "소설 속 악역이 너를 때리겠다고 말하는 장면이야.",
    "수익 보장이라고 인증금을 보내라는 요구는 믿지 마.",
    "그런 요구는 거절해. 협박하지 말라고 이야기했어.",
])
def test_quoted_or_reported_threat_only_not_risk(text):
    result = classify([msg(text)])
    assert result["kind"] == "NORMAL"
    assert result["findings"] == []


def test_new_message_cannot_use_old_only_context_as_finding():
    result = classify([msg("원금 보장해. 인증금 보내.", fresh=False),
                       msg("그런 사기는 안 돼.", seq=2, speaker="B")])
    assert result["kind"] == "NORMAL"
    assert result["findings"] == []


def test_mixed_reporting_and_threat_does_not_leak_message_body():
    raw = "영화 속 악역이 저런 말을 해. 하지만 내가 네 사진을 퍼뜨리겠다."
    result = classify([msg(raw)])
    assert result["kind"] == "RISK"
    assert raw not in str(result)
    assert "contextId" not in result
    assert "userId" not in result


DRAFT_CASES = json.loads((Path(__file__).parent / "fixtures/safety48-rules/draft_48_proposed.json")
                        .read_text(encoding="utf-8"))["cases"]


@pytest.mark.parametrize("case", DRAFT_CASES, ids=lambda case: case["id"])
def test_previous_draft_inputs_are_classified_instead_of_replaying_outputs(case):
    output = classify(case["input"]["contextBundle"])
    assert output["kind"] == case["expected"]["kind"]

    def canonical(findings):
        return sorted((f["sequence"], f["category"], f["noticeType"], f["audience"]) for f in findings)

    assert canonical(output["findings"]) == canonical(case["expected"]["findings"])


@pytest.mark.parametrize("text", [
    "어제 모르는 사람이 '널 때릴 거야'라고 해서 무서웠어.",
    "친구가 '네 사진을 퍼뜨리겠다'고 협박했어.",
    "상대가 '널 해칠 거야'라고 말해서 무서웠어.",
    "영화 속 악역이 너는 쓰레기야라고 말하는 대사야.",
    "누가 '너는 진짜 벌레야'라고 말했어.",
    "원금 보장 투자라고 해도 투자금을 보내지 마.",
    "은행 담당자에게 보증금을 보내라는 말은 믿지 마.",
    "당첨금 받으려면 수수료를 보내라는 요구는 거절해.",
    "수수료부터 송금하지 마. 사기를 당했어.",
    "앱을 설치한 다음 보안 검사를 켜고 결과를 확인해.",
    "보안 경고를 읽고 설치를 취소했어.",
    "은행 담당자에게 계좌 해제 수수료가 맞는지 먼저 확인하자.",
    "해외 배송 해제비를 보내기 전에 세관에 조회해 보자.",
    "너는 벌레를 잘 잡는구나.",
    "너는 쓰레기를 분리수거했어.",
    "오늘 투자금은 각자 관리하자.",
    "싫어도 벗은 사진을 보내라는 요구는 거절해.",
])
def test_complementary_normal_and_reported_wording(text):
    result = classify([_message(1, "A", text, True)])
    assert result["kind"] == "NORMAL"
    assert result["findings"] == []


@pytest.mark.parametrize("report,threat,category", [
    ("친구가 '널 때릴 거야'라고 말했어.", "나는 네 사진을 퍼뜨리겠다.", "THREAT"),
    ("상대가 '널 해칠 거야'라고 말해서 무서웠어.", "내가 널 때릴 거야.", "THREAT"),
    ("어제 사기를 당했어.", "당첨금 받으려면 수수료를 보내. 확인은 금지.", "FRAUD"),
])
@pytest.mark.parametrize("contrast", ["하지만", "그런데", "근데", "그래도", "그렇지만"])
def test_report_followed_by_separate_direct_intent_is_not_suppressed(report, threat, category, contrast):
    result = classify([_message(1, "B", f"{report} {contrast} {threat}", True)])
    assert result["kind"] == "RISK"
    assert category in {f["category"] for f in result["findings"]}
