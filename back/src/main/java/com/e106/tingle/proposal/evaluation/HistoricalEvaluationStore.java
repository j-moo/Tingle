package com.e106.tingle.proposal.evaluation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.proposal.MatchProposal;
import com.e106.tingle.proposal.ProposalRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** 실제 생산자의 검증을 통과한 당시 근거를 matching.evaluation에 한 번만 고정한다. */
@Service
public class HistoricalEvaluationStore {
	private final ProposalRepository proposals;
	private final JsonMapper mapper;
	private final HistoricalEvidenceVerifier verifier;
	private final EntityManager entityManager;

	@Autowired
	public HistoricalEvaluationStore(ProposalRepository proposals, JsonMapper mapper,
		ObjectProvider<HistoricalEvidenceVerifier> verifier, EntityManager entityManager) {
		this(proposals, mapper, verifier.getIfUnique(), entityManager);
	}

	public HistoricalEvaluationStore(ProposalRepository proposals, JsonMapper mapper,
		HistoricalEvidenceVerifier verifier, EntityManager entityManager) {
		this.proposals = proposals;
		this.mapper = mapper.rebuild().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
		this.verifier = verifier;
		this.entityManager = entityManager;
	}

	@Transactional
	public HistoricalEvaluationEvidence store(HistoricalEvaluationEvidence evidence) {
		if (evidence == null) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST);
		}
		MatchProposal matching = proposals.lockById(evidence.matchingId())
			.orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
		// 상위 트랜잭션이 먼저 읽은 JPA 캐시를 쓰지 않고 다른 writer가 확정한 JSON 영역까지 다시 읽는다.
		entityManager.refresh(matching, LockModeType.PESSIMISTIC_WRITE);
		if (!bound(matching, evidence)) {
			throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
		}
		if (!verified(matching, evidence)) {
			throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
		}
		Map<String, Object> evaluation = matching.getEvaluation();
		if (evaluation.containsKey(HistoricalEvaluationEvidence.STORAGE_KEY)) {
			HistoricalEvaluationEvidence prior = decode(evaluation.get(HistoricalEvaluationEvidence.STORAGE_KEY))
				.orElseThrow(() -> new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT));
			if (!prior.equals(evidence)) {
				throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
			}
			return prior;
		}
		Map<String, Object> payload = mapper.convertValue(evidence,
			new TypeReference<LinkedHashMap<String, Object>>() { });
		matching.recordHistoricalEvidence(payload);
		return evidence;
	}

	@Transactional(readOnly = true)
	public Optional<HistoricalEvaluationEvidence> find(long matchingId) {
		if (matchingId <= 0) {
			return Optional.empty();
		}
		return proposals.findById(matchingId).flatMap(matching -> {
			Map<String, Object> evaluation = matching.getEvaluation();
			if (evaluation == null) {
				return Optional.empty();
			}
			return decode(evaluation.get(HistoricalEvaluationEvidence.STORAGE_KEY))
				.filter(value -> bound(matching, value) && verified(matching, value));
		});
	}

	private Optional<HistoricalEvaluationEvidence> decode(Object value) {
		if (!(value instanceof Map<?, ?>)) {
			return Optional.empty();
		}
		try {
			return Optional.of(mapper.convertValue(value, HistoricalEvaluationEvidence.class));
		} catch (RuntimeException exception) {
			return Optional.empty();
		}
	}

	private boolean verified(MatchProposal matching, HistoricalEvaluationEvidence evidence) {
		try {
			return verifier != null && verifier.verify(matching, evidence);
		} catch (RuntimeException exception) {
			return false;
		}
	}

	private static boolean bound(MatchProposal matching, HistoricalEvaluationEvidence evidence) {
		Map<String, Object> evaluation = matching.getEvaluation();
		// 설명/근거 영역만 있는 행을 실제 확정 판정이라고 간주하지 않는다.
		boolean hasDecision = evaluation != null && evaluation.keySet().stream()
			.anyMatch(key -> !HistoricalEvaluationEvidence.STORAGE_KEY.equals(key) && !"matchExplanations".equals(key));
		return hasDecision && matching.getId() == evidence.matchingId()
			&& matching.getUserLow() == Math.min(evidence.left().userId(), evidence.right().userId())
			&& matching.getUserHigh() == Math.max(evidence.left().userId(), evidence.right().userId())
			&& evidence.metAt().equals(matching.getMetAt())
			&& evidence.policyVersion().equals(matching.getPolicyVersion());
	}
}
