package com.e106.tingle.proposal.explanation;

import java.util.UUID;

import org.springframework.stereotype.Service;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.proposal.explanation.dto.ExplanationStatus;
import com.e106.tingle.proposal.explanation.dto.ExplanationTask;

@Service
public class ExplanationService {
	private final ExplanationAccess access;
	private final ExplanationTransactions transactions;
	private final RuleExplanationProvider provider;

	public ExplanationService(ExplanationAccess access, ExplanationTransactions transactions,
		RuleExplanationProvider provider) {
		this.access = access;
		this.transactions = transactions;
		this.provider = provider;
	}

	public ExplanationTask request(long proposalId, long viewerId, UUID key) {
		if (key == null) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST);
		}
		access.require(proposalId, viewerId);
		ExplanationTransactions.Lease lease = transactions.begin(proposalId, viewerId, key);
		if (!lease.created()) {
			return task(proposalId, get(proposalId, viewerId));
		}
		ExplanationStatus accepted = transactions.accept(proposalId, viewerId, lease);
		if (!"PENDING".equals(accepted.status())) {
			return task(proposalId, accepted);
		}
		ExplanationStatus result;
		try {
			result = provider.explain(new RuleExplanationProvider.Input(proposalId, viewerId,
				lease.run().attemptId(), lease.run().inputFingerprint(), lease.evaluation()));
			if (result == null || "PENDING".equals(result.status())) {
				result = RuleExplanationProvider.unavailable();
			}
		} catch (RuntimeException exception) {
			// 제공자 오류·원문은 응답과 로그에 복사하지 않는다. 다음 요청에서 자동 재실행하지 않는다.
			result = RuleExplanationProvider.unavailable();
		}
		return task(proposalId, transactions.complete(proposalId, viewerId, lease.run(), result));
	}

	public ExplanationStatus get(long proposalId, long viewerId) {
		return transactions.read(proposalId, viewerId);
	}

	private static ExplanationTask task(long proposalId, ExplanationStatus result) {
		return new ExplanationTask(proposalId, "MATCH_EXPLANATION", result.status(),
			"/api/match-proposals/" + proposalId + "/explanation",
			"FAILED".equals(result.status()) ? "SERVICE_UNAVAILABLE" : null, false,
			"PENDING".equals(result.status()) ? 1 : 0);
	}
}
