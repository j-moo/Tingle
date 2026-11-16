"""Export unapproved v2 wording, v1 comparisons and exact working-tree evidence.

Run from ai-server with --output-dir outside the repository. No HTTP, GMS, DB,
policy registration, Git mutation or human approval is performed.
"""
import argparse
from collections import Counter
from contextlib import contextmanager
from copy import deepcopy
import csv
import difflib
import importlib
from itertools import permutations, product
import json
from pathlib import Path
import subprocess
import sys
import tempfile

from app.personal_analysis import core
from app.personal_analysis.reference import CATALOGUE
from app.personal_analysis.service import analyze_personal
from app.personal_analysis_api import adapter as current
from app.personal_analysis_api.dto import PersonalAnalysisRequest
from app.personal_analysis_api.proposals import (
    CANDIDATE_READING_TEMPLATE_IDS, PROPOSAL_REVIEW_VERSION, proposed_label_only_policy,
)
from app.personal_analysis_api.review_samples import synthetic_request

ROOT = Path(__file__).resolve().parents[2]
BASELINE = "19eddf45f310745080160e68f01ee432a7e9122a"
INITIAL_REVIEW_BASELINE = "a8d5fc31af035be3e0794ab48024a47e2af96bb2"
V1_SOURCE = "3e981cca1b3e13b0ce3bb219ae4bd31af4b04769"
QUESTIONS = {q["ordinal"]: q for q in CATALOGUE["questions"]}
SCOPE = [
    "ai-server/app/personal_analysis_api/adapter.py", "ai-server/app/personal_analysis_api/proposals.py",
    "ai-server/app/personal_analysis_api/router.py",
    "ai-server/app/personal_analysis_api/review_samples.py", "ai-server/app/personal_analysis_api/README.md",
    "ai-server/app/personal_analysis_api/WORDING_REVIEW_V2.md", "ai-server/scripts/export_personal_wording_review.py",
    "ai-server/tests/test_personal_analysis_api_contract.py", "ai-server/tests/test_personal_analysis_production.py",
    "ai-server/tests/test_personal_analysis_wording_v2.py",
    "ai-server/tests/test_service_auth_startup.py",
    *["ai-server/tests/fixtures/personal-analysis-api/" + name for name in (
        "succeeded.response.proposal.json", "insufficient.response.proposal.json", "sample-manifest.json")],
]
PROTECTED = ["ai-server/app/personal_analysis", "ai-server/app/personal_analysis_api/dto.py",
             "ai-server/app/personal_analysis_api/production.py",
             "ai-server/app/personal_analysis_api/presentation-review.json", "ai-server/app/main.py",
             "ai-server/app/config.py", "ai-server/app/security", "ai-server/app/match_explanation", "back"]
HEADERS = ["구분", "templateId", "v1 완성 문장", "v2 완성 문장 / 경계 결과", "검토 권고",
           "최종 결정(사용자)", "발생 조건·치환 입력", "근거 문항 순서", "계산·fit", "priority",
           "상태·선정 규칙", "변경 이유", "남은 정책 판단", "정책 상태", "검토자", "결정일", "변경 사항", "검토 CASE"]


def git(*args):
    return subprocess.check_output(["git", *args], cwd=ROOT).decode("utf-8").replace("\r\n", "\n")


def values(q, unknown=False):
    schema = QUESTIONS[q]["responseSchema"]["oneOf"][0]
    result = list(schema["enum"]) if "enum" in schema else [list(p)
        for size in range(schema["minItems"], schema["maxItems"] + 1)
        for p in permutations(schema["items"]["enum"], size)]
    return result + (["unknown"] if unknown else [])


def request(patch, kind="insufficient"):
    body = synthetic_request(kind)
    for a in body["answers"]:
        q = int(a["questionId"][1:])
        if kind == "insufficient" or q in patch:
            a["value"] = deepcopy(patch.get(q, "unknown"))
    return PersonalAnalysisRequest.model_validate(body)


def stripped(response):
    result = response.model_dump()
    for field in ("readings", "selfPreferenceDifferences"):
        for row in result[field]:
            row.pop("text")
    return result


def reading_cases():
    """Every distinct ID/v1-text/v2-text, including the four normal variants."""
    policy = proposed_label_only_policy()
    seen = set()
    for key, ids in [("expression", (30, 32, 33)), ("conflict", (42, 43)),
                     ("experience", (38, 39)), ("time", (37, 32, 34, 36)), ("support", (45, 44))]:
        for combination in product(*(values(q, True) for q in ids)):
            answers = dict(zip(ids, combination))
            for row in core.readings(answers):
                if row["key"] != key:
                    continue
                tid = policy.reading_template_ids[key, row["title"]]
                formatter = policy.reading_text_formatters.get(tid)
                text = formatter(row, answers) if formatter else row["text"]
                identity = tid, row["text"], text
                if identity not in seen:
                    seen.add(identity)
                    yield dict(kind="reading", id=tid, before=row["text"], after=text,
                               patch=answers, mode="insufficient")
    for approach, frequency in product((4, 5), (1, 2)):
        patch = {30: approach, 32: frequency}
        raw = next(r for r in core.readings(patch) if r["key"] == "expression")
        text = policy.reading_text_formatters["PA-R-EXPRESSION-01"](raw, patch)
        yield dict(kind="정상 표현", id="PA-R-EXPRESSION-01", before=text, after=text, patch=patch, mode="succeeded")


def gap_cases():
    policy = proposed_label_only_policy()
    for q in range(65, 96):
        for actual, wish in product(values(q - 35), values(q)):
            fits = {t: core.wish_fit(q, wish, actual, t) for t in ("close", "some", "wide", "unknown")}
            eligible = {t: f for t, f in fits.items() if f is not None and f < 1}
            if not eligible:
                continue
            patch = {q - 35: actual, q: wish, 98: next(iter(eligible))}
            row, = core.preference_gaps(patch)
            before = f"{row['label']}: 나의 응답은 ‘{row['self']}’이고, 상대에게 바라는 응답은 ‘{row['wish']}’입니다."
            after = policy.difference_reading(row, 0, patch).text
            yield dict(kind="차이", id="PA-D-SELF-WISH-FIT", before=before, after=after,
                       patch=patch, mode="insufficient", fits=fits, q=q)


def known_issues(cases):
    problems = {name: set() for name in ("기존 수정 필요", "EXPERIENCE 조사", "TIME 조사")}
    for c in cases:
        identity = c["id"], c["before"]
        text = c["before"]
        if ((c["id"] == "PA-R-EXPRESSION-01" and "말 대신 어떤 행동" in text)
                or c["id"] == "PA-R-CONFLICT-03" or "‘이유 설명’를" in text
                or "‘사과 표현’를" in text or c.get("q") == 89):
            problems["기존 수정 필요"].add(identity)
        if "‘익숙한 곳’라고" in text or "‘새로운 곳’라고" in text:
            problems["EXPERIENCE 조사"].add(identity)
        if "‘주 4회 이상’를" in text:
            problems["TIME 조사"].add(identity)
    return {k: len(v) for k, v in problems.items()}


def assert_wording(c):
    forbidden = ("‘익숙한 곳’라고", "‘새로운 곳’라고", "‘주 4회 이상’를", "‘이유 설명’를",
                 "‘사과 표현’를", "’가 편하다고", "말 대신 어떤 행동", "관계를 시작하는 행동")
    assert not any(s in c["after"] for s in forbidden), c
    if c.get("q") == 89:
        assert "상한으로 선택한 답변" in c["after"]
    if c.get("q") == 87 and any("irregular" in c["patch"][q] for q in (52, 87)):
        assert "계산에서는 ‘일정이 불규칙함’을 제외" in c["after"]
    assert "fit=" not in c["after"] and "성공 확률" not in c["after"]


@contextmanager
def legacy_adapter(baseline):
    """Load only baseline presentation/DTO code; calculation code is byte-identical."""
    with tempfile.TemporaryDirectory(prefix="personal-wording-v1-") as temp:
        package = Path(temp) / "_wording_v1"
        package.mkdir()
        for name in ("__init__.py", "adapter.py", "proposals.py", "dto.py"):
            (package / name).write_text(git("show", f"{baseline}:ai-server/app/personal_analysis_api/{name}"),
                                        encoding="utf-8", newline="\n")
        sys.path.insert(0, temp)
        try:
            old = importlib.import_module("_wording_v1.adapter")
            old_policy = importlib.import_module("_wording_v1.proposals").proposed_label_only_policy()
            yield old, old_policy
        finally:
            sys.path.remove(temp)
            for name in list(sys.modules):
                if name == "_wording_v1" or name.startswith("_wording_v1."):
                    del sys.modules[name]


def export(output, baseline=BASELINE):
    assert git("rev-parse", baseline).strip() == BASELINE
    assert not git("diff", "--name-only", baseline, "--", *PROTECTED), "Protected source changed"
    changed = git("diff", "--name-only", baseline).splitlines()
    untracked = git("ls-files", "--others", "--exclude-standard").splitlines()
    assert set(changed + untracked) <= set(SCOPE), "Unexpected change outside reviewed scope"
    current.verify_reference_binding()
    policy = proposed_label_only_policy()
    cases = [*reading_cases(), *gap_cases()]
    reproduced = known_issues(cases)
    assert reproduced == {"기존 수정 필요": 38, "EXPERIENCE 조사": 8, "TIME 조사": 5}, reproduced
    data = []
    counts = Counter()
    with legacy_adapter(baseline) as (old, old_policy):
        for index, c in enumerate(cases, 1):
            assert_wording(c)
            req = request(c["patch"], c["mode"])
            answers = {int(a.questionId[1:]): a.value for a in req.answers}
            raw = analyze_personal(current.to_core_input(req, current.APPROVED_VERSION_BINDING))
            previous = old.to_be_response(raw, old.APPROVED_VERSION_BINDING, old_policy, answers)
            actual = current.to_be_response(raw, current.APPROVED_VERSION_BINDING, policy, answers)
            assert stripped(previous) == stripped(actual), c
            field = "selfPreferenceDifferences" if c["kind"] == "차이" else "readings"
            before = next(r for r in getattr(previous, field) if r.templateId == c["id"])
            after = next(r for r in getattr(actual, field) if r.templateId == c["id"])
            assert (before.text, after.text) == (c["before"], c["after"])
            counts["text 외 전체 필드 불변"] += 1
            counts[c["kind"]] += 1
            changed_text = before.text != after.text
            counts["문구 변경 사례" if changed_text else "문구 동일 사례"] += 1
            pending = "표시 문구·ID·priority 범위에 대한 사용자 정책 승인 필요. 현재 미승인."
            reason = "답변 재진술 및 고정 조사 제거" if changed_text else "기존 문구 보존"
            recommendation = "수정안 재검토" if changed_text else "기존 검토 유지(미승인)"
            calc = f"재현: {actual.status}/{actual.typeStatus}, primaryType={actual.primaryType}. 점수·유형·추천·선정·근거·순서 불변."
            if "fits" in c:
                numeric = all(core.numeric(o["value"]) for o in QUESTIONS[c["q"] - 35]["options"])
                note = "Q098 unknown은 기본 some 폭이며 근거에는 미포함." if numeric else "범주/배열 비교에는 Q098을 적용하지 않음."
                calc = json.dumps(c["fits"], ensure_ascii=False) + "; " + note
                reason = "설문에서 고른 허용 상한을 명시; Q098 계산은 그대로" if c["q"] == 89 else reason
            if c.get("q") == 87 and "제외한 시간대" in after.text:
                recommendation = "보류"
                reason = "측정된 원래 보기와 irregular를 제외한 계산 규칙을 구분"
                pending = "제외 안내 문구를 포함할지, 별도 안내로 보낼지 사용자 결정. irregular를 계산에 포함하는 변경은 이번 범위 아님."
            if c["id"] in ("PA-R-CONFLICT-01", "PA-R-SUPPORT-01"):
                pending = "v2 기본 후보는 사실 재진술만 반환. 일반 조언을 별도 영역에서 제공할지는 보류 대안에서 결정."
            input_text = ", ".join(f"Q{q:03}={json.dumps(v,ensure_ascii=False)}" for q, v in sorted(c["patch"].items()))
            evidence = ",".join(after.evidenceQuestionIds)
            priority = after.priority if c["kind"] != "차이" else "첫째 2 / 둘째 1; fit 순·동점 문항 번호순"
            if c["kind"] == "차이":
                numeric = all(core.numeric(o["value"]) for o in QUESTIONS[c["q"] - 35]["options"])
                evidence = f"Q{c['q']-35:03},Q{c['q']:03}" + (",Q098 (명시적 close/some/wide만)" if numeric else "")
            data.append([c["kind"], c["id"], before.text, after.text, recommendation, "", input_text,
                         evidence, calc, priority, "SUCCEEDED / INSUFFICIENT (재현=" + actual.status + "); 상위3/2 규칙 보존",
                         reason, pending, "UNAPPROVED", "", "", "", f"V2-{index:05}"])
        # Reproduce the old fallback and verify refusal in the existing error class.
        for fault in ("missing-answer", "mismatched-answer", "missing-evidence"):
            row = deepcopy(core.readings({30: 4, 32: 2})[0])
            if fault == "missing-answer":
                del row["evidence"][1]["answer"]
            elif fault == "mismatched-answer":
                row["evidence"][1]["answer"] = "not-the-measured-answer"
            else:
                row["evidence"].pop()
            assert old.format_expression_reading(row, {30: 4, 32: 2}) == row["text"]
            try:
                current.format_expression_reading(row, {30: 4, 32: 2})
            except current.FormatterEvidenceMismatch:
                pass
            else:
                raise AssertionError("Inferred fallback remained")
            data.append(["검증 실패", "PA-R-EXPRESSION-01", row["text"], "FormatterEvidenceMismatch → 기존 HTTP 500 INTERNAL_ERROR",
                         "수정안 재검토", "", fault, "검증 실패", "계산 결과를 부분 성공으로 반환하지 않음", "변경 없음",
                         "SUCCEEDED / INSUFFICIENT 모두 차단", "추론 fallback 차단 유지. bcd2c020의 formatter 503을 내부 오류 500으로 재분류",
                         "BE는 AI_HTTP_500과 AI_HTTP_503 모두 재시도. 새 DTO/오류 코드 없음. 상세는 WORDING_REVIEW_V2.md",
                         "UNAPPROVED", "", "", "", "BOUNDARY-" + fault])
        boundaries = [
            ("all-unknown", {}, "미측정으로 성향·선호를 만들지 않음"),
            ("self-unknown", {30: "unknown", 65: 5}, "자기 측정이 없으면 차이 없음"),
            ("wish-any", {30: 1, 65: "any"}, "명시적 무제약을 특정 선호로 만들지 않음"),
            ("wish-unknown", {30: 1, 65: "unknown"}, "상대 선호 미측정을 답변으로 만들지 않음"),
            ("tolerance-unknown", {30: 1, 65: 5, 98: "unknown"}, "기본 some 폭 적용; Q098은 evidence에서 제외"),
            *[("drinking-" + t, {54: 4, 89: 2, 98: t}, "음주 설문상 상한 2; Q098 허용폭에 따른 fit/차이 제외는 그대로")
              for t in ("close", "some", "wide", "unknown")],
            ("irregular-only", {52: ["irregular"], 87: ["evening"]}, "제외 후 비교할 시간대가 없으면 fit=None, 차이 없음"),
            ("irregular-overlap", {52: ["morning", "irregular"], 87: ["morning"]}, "측정 시간대 교집합 fit=1, 차이 없음"),
            ("irregular-disjoint", {52: ["morning", "irregular"], 87: ["evening", "irregular"]}, "irregular 제외 후 fit=0, 원래 답변과 제외 안내 분리"),
        ]
        for name, patch_values, note in boundaries:
            req = request(patch_values)
            answers = {int(a.questionId[1:]): a.value for a in req.answers}
            raw = analyze_personal(current.to_core_input(req, current.APPROVED_VERSION_BINDING))
            previous = old.to_be_response(raw, old.APPROVED_VERSION_BINDING, old_policy, answers)
            actual = current.to_be_response(raw, current.APPROVED_VERSION_BINDING, policy, answers)
            assert stripped(previous) == stripped(actual)
            counts["경계 응답 불변"] += 1
            fields = {"status", "typeStatus", "primaryType", "scores", "readings", "selfPreferenceDifferences"}
            data.append(["경계", name, json.dumps(previous.model_dump(include=fields), ensure_ascii=False),
                         json.dumps(actual.model_dump(include=fields), ensure_ascii=False), "수정안 재검토", "",
                         json.dumps(patch_values, ensure_ascii=False), "반환 evidence의 실제 값은 JSON에 보존", note,
                         "선정된 행의 priority 그대로", actual.status, "unknown/any/허용폭/irregular 경계 보존",
                         "공개 필터/앱 표시는 미검증", "UNAPPROVED", "", "", "", "BOUNDARY-" + name])
    for tid, advice, candidate in [
        ("PA-R-CONFLICT-01", "잠시 멈출 때 다시 이야기할 시간을 정하면 기대를 맞추기 쉬워요.",
         "잠시 대화를 멈춘다면, 다시 이야기할 시간을 함께 정해보는 방법도 있어요."),
        ("PA-R-SUPPORT-01", "상대가 받고 싶은 방식도 확인해보면 좋아요.",
         "도움이 필요한 사람에게 어떤 도움을 원하는지 물어보는 방법도 있어요."),
    ]:
        data.append(["일반 조언 대안", tid, advice, "일반적인 대화 제안: " + candidate, "보류", "", "선택된 사실 문장과 별도 영역 제공 후보",
                     "해당 조언 자체를 산출한 설문 근거 없음", "계산 결과가 아니며 추천 효과를 검증한 것도 아님", "해당 없음",
                     "현재 v2 formatter에는 이 조언을 주입하지 않음", "사실/일반 조언 분리", "조언 제공 여부·별도 영역·효과 표현의 완화 여부를 사용자 결정",
                     "UNAPPROVED", "", "", "", "ADVICE-" + tid])
    assert all(row[5] == "" and row[13] == "UNAPPROVED" and row[14:17] == ["", "", ""] for row in data)
    assert counts["차이"] == 18482
    assert len({c["id"] for c in cases if c["kind"] != "차이"}) == 15
    before_unique = {(c["id"], c["before"]) for c in cases}
    after_unique = {(c["id"], c["after"]) for c in cases}
    before_by_id = Counter(tid for tid, _ in before_unique)
    after_by_id = Counter(tid for tid, _ in after_unique)
    deltas = [dict(templateId=tid, v1=before_by_id[tid], v2=after_by_id[tid],
                   delta=after_by_id[tid] - before_by_id[tid]) for tid in sorted(before_by_id)
              if before_by_id[tid] != after_by_id[tid]]
    assert (len(before_unique), len(after_unique), len(cases), len(boundaries), len(data)) == (
        18601, 18603, 18607, 12, 18624)
    assert deltas == [dict(templateId="PA-R-CONFLICT-01", v1=1, v2=4, delta=3),
                      dict(templateId="PA-R-EXPRESSION-01", v1=5, v2=4, delta=-1)]
    patch = git("diff", "--no-ext-diff", "--binary", baseline, "--", *SCOPE)
    for name in sorted(untracked):
        content = (ROOT / name).read_text(encoding="utf-8").splitlines(keepends=True)
        patch += "".join(difflib.unified_diff([], content, fromfile="/dev/null", tofile="b/" + name))
    output.mkdir(parents=True, exist_ok=True)
    (output / "v2-working-tree.patch").write_text(patch, encoding="utf-8", newline="\n")
    meta = dict(reviewVersion=PROPOSAL_REVIEW_VERSION, preparedDate="2026-11-15", baseline=baseline, v1Source=V1_SOURCE,
                mr357="Merged 2026-11-15 14:29:44 Asia/Seoul; merge a8d5fc31",
                mr358="Merged 2026-11-15 19:15:31 Asia/Seoul; merge 19eddf45; changes preserved",
                initialReviewBaseline=INITIAL_REVIEW_BASELINE, approval="UNAPPROVED",
                approvalSource=None, presentationVersion=None, reviewer=None, decisionDate=None,
                sourceCommit=git("rev-parse", "HEAD").strip(), workingTreeDirty=bool(git("status", "--porcelain")),
                workingTree="Source snapshot: baseline plus included patch; sourceCommit identifies HEAD, not policy approval",
                changedFiles=sorted(changed + untracked),
                reproduced=reproduced, checks=dict(counts), grammarProblemsRemaining=0, rowCount=len(data),
                uniqueV1Sentences=len(before_unique), uniqueV2Sentences=len(after_unique),
                countDefinitions=dict(
                    uniqueKey="(templateId, 완성 문장)",
                    v1=dict(reading=115, normalExpression=4, difference=18482, total=len(before_unique)),
                    v2=dict(reading=121, difference=18482, total=len(after_unique)),
                    uniqueSentenceDeltas=deltas,
                    comparisonCases=dict(partialReadings=counts["reading"], normalExpression=counts["정상 표현"],
                                         difference=counts["차이"], total=len(cases),
                                         key="부분: ID/v1문장/v2문장; 정상 EXPRESSION 4개는 상태가 다른 별도 사례"),
                    normalExpressionDuplicatesInV2=4,
                    validBoundaryResponses=len(boundaries), forcedFormatterFailureRows=3, generalAdviceRows=2,
                    totalReviewRows=len(data),
                    excludedFromSentenceCounts="경계 응답 12 + 강제 검증 실패 3 + 일반 조언 대안 2",
                    enumeration="문구별 치환 전수. 배열 순서를 보존하고 동일 숫자 label은 중복 제거. Q098별 fit은 한 문구 행에 묶음. 전체 설문 동시 조합 수가 아님."),
                failureBehavior=dict(
                    classification="정상 응답 text 변경과 별개인 오류 발생 조건 변경",
                    before="formatter evidence 검증 실패 시 추론 원문 fallback",
                    after="FormatterEvidenceMismatch → HTTP 500 INTERNAL_ERROR; 다른 PresentationUnavailable은 HTTP 503 유지; 추론 fallback 없음",
                    beImpact="AI_HTTP_500과 AI_HTTP_503 모두 retryable. 저장소 기본 최대 3회(최초 포함), retry-delay 1m. 재시도 제거 효과 없음. 실제 운영 설정/DB/재처리 미확인·미실행.",
                    details="ai-server/app/personal_analysis_api/WORDING_REVIEW_V2.md"),
                actualAppDisplay="UNVERIFIED", operationalGate="UNCHANGED", protectedSource="UNCHANGED",
                pendingPolicyDecisions=["v2 문구·ID·priority·상태별 formatter 적용 승인",
                    "irregular 제외 안내의 최종 표현/배치", "일반 조언 제공 여부와 별도 영역", "표시 버전·승인 이력 및 과거 저장 text 처리"],
                sourceFiles=SCOPE, note="완성 문장별 치환 범위 전수; 전체 입력의 동시 출현 조합은 선정 규칙으로 명시. 사용자 승인란은 비워 둠.")
    (output / "review-data.json").write_text(json.dumps(dict(headers=HEADERS, rows=data, meta=meta), ensure_ascii=False), encoding="utf-8")
    (output / "review-summary.json").write_text(json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf-8")
    with (output / "wording-review-v2.csv").open("w", encoding="utf-8-sig", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(HEADERS)
        writer.writerows(data)
    print(json.dumps(meta, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--baseline", default=BASELINE)
    args = parser.parse_args()
    export(args.output_dir.resolve(), args.baseline)
