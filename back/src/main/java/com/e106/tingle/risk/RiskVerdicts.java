package com.e106.tingle.risk;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.risk.RiskAnalysisTypes.Audience;
import com.e106.tingle.risk.RiskAnalysisTypes.Category;
import com.e106.tingle.risk.RiskAnalysisTypes.Finding;
import com.e106.tingle.risk.RiskAnalysisTypes.Input;
import com.e106.tingle.risk.RiskAnalysisTypes.Kind;
import com.e106.tingle.risk.RiskAnalysisTypes.Message;
import com.e106.tingle.risk.RiskAnalysisTypes.NoticeType;
import com.e106.tingle.risk.RiskAnalysisTypes.Response;
import com.e106.tingle.risk.RiskAnalysisTypes.Verdict;

@Component
public class RiskVerdicts {

	public Verdict validate(Verdict verdict, Input input) {
		if (verdict == null || verdict.kind() == null || verdict.findings() == null || input == null
			|| !validVersion(verdict.modelVersion()) || !validVersion(verdict.promptVersion())
			|| (verdict.confidence() != null && (!Double.isFinite(verdict.confidence())
				|| verdict.confidence() < 0 || verdict.confidence() > 1))
			|| (verdict.kind() == Kind.RISK) != !verdict.findings().isEmpty()) {
			throw invalid();
		}
		Set<Long> fresh = input.messages().stream().filter(Message::fresh).map(Message::sequence)
			.collect(Collectors.toSet());
		Set<Finding> unique = new HashSet<>();
		for (Finding finding : verdict.findings()) {
			if (finding == null || !fresh.contains(finding.sequence()) || !validMapping(finding)
				|| !unique.add(finding)) {
				throw invalid();
			}
		}
		return new Verdict(verdict.kind(), List.copyOf(verdict.findings()), verdict.confidence(),
			verdict.modelVersion(), verdict.promptVersion());
	}

	public Response response(UUID attemptId, Verdict verdict) {
		if (attemptId == null || verdict == null || verdict.kind() == null || verdict.findings() == null) {
			throw invalid();
		}
		boolean succeeded = verdict.kind() == Kind.NORMAL || verdict.kind() == Kind.RISK;
		List<String> categories = verdict.findings().stream().map(finding -> finding.category().name())
			.distinct().toList();
		return new Response(attemptId, succeeded ? "SUCCEEDED" : "FAILED", categories, verdict.confidence(),
			verdict.modelVersion(), verdict.promptVersion());
	}

	private boolean validMapping(Finding finding) {
		if (finding.category() == null || finding.noticeType() == null || finding.audience() == null) {
			return false;
		}
		if (finding.noticeType() == NoticeType.HARASSMENT) {
			return (finding.category() == Category.HARASSMENT || finding.category() == Category.THREAT)
				&& finding.audience() == Audience.BOTH;
		}
		if (finding.audience() != Audience.RECIPIENT) {
			return false;
		}
		return finding.noticeType() == NoticeType.MONEY_REQUEST ? finding.category() == Category.FRAUD
			: finding.category() == Category.OTHER;
	}

	private boolean validVersion(String value) {
		return value != null && !value.isBlank() && value.length() <= 100;
	}

	private BusinessException invalid() {
		return new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
	}
}
