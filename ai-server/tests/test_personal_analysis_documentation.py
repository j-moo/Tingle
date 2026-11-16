"""Keep current runtime documentation separate from the historical v1 inventory."""
import json
from pathlib import Path
import re
from urllib.parse import unquote, urlsplit

import pytest

from app.personal_analysis_api.proposals import PROPOSAL_REVIEW_VERSION, proposed_label_only_policy
from app.security.service_auth import ENVIRONMENTS

ROOT = Path(__file__).resolve().parents[1]
DOCS = ROOT / "app/personal_analysis_api"
MARKDOWN = ("README.md", "PRODUCTION_POLICY_VERIFICATION.md", "WORDING_REVIEW_V2.md")


def unique_object(pairs):
    result = {}
    for name, value in pairs:
        if name in result:
            raise ValueError(f"Duplicate documentation field: {name}")
        result[name] = value
    return result


def test_review_json_describes_the_current_factory_without_promoting_historical_approval():
    document = json.loads((DOCS / "presentation-review.json").read_bytes(), object_pairs_hook=unique_object)
    assert {"approval", "approvalSource", "productionPolicy"}.isdisjoint(document)
    current = document["currentRuntime"]
    assert set(current["environments"]) == ENVIRONMENTS
    assert current["policyFactory"] == (
        f"{proposed_label_only_policy.__module__}.{proposed_label_only_policy.__name__}")
    assert current["wordingReviewVersion"] == PROPOSAL_REVIEW_VERSION
    assert current["route"] == "POST /internal/ai/personal-analyses"
    assert current["normalStatuses"] == ["SUCCEEDED", "INSUFFICIENT"]
    historical = document["historicalReview"]
    assert historical["approval"] == "UNAPPROVED"
    assert historical["approvalSource"] is None and historical["presentationVersion"] is None
    assert historical["inspectedDevelop"] != current["sourceCommit"]
    assert len(historical["readings"]) == 15
    assert len(historical["differenceRenderer"]["labelPairs"]) == 31


@pytest.mark.parametrize("filename", MARKDOWN)
def test_current_documentation_local_links_resolve(filename):
    document = DOCS / filename
    targets = re.findall(r"\[[^\]]+\]\(([^)]+)\)", document.read_text(encoding="utf-8"))
    assert targets, "Documentation should link to the implementation or validation"
    for target in targets:
        parsed = urlsplit(target)
        if parsed.scheme or not parsed.path:
            continue
        resolved = (document.parent / unquote(parsed.path)).resolve()
        assert resolved.is_relative_to(ROOT.resolve()), target
        assert resolved.exists(), target
