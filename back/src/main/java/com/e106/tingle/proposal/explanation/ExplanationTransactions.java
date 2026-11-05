package com.e106.tingle.proposal.explanation;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.proposal.MatchProposal;
import com.e106.tingle.proposal.ProposalRepository;
import com.e106.tingle.proposal.explanation.dto.ExplanationStatus;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** 외부 설명 처리 전후의 짧은 저장 트랜잭션. 외부 처리는 이 빈에서 실행하지 않는다. */
@Service
public class ExplanationTransactions {
	private final ProposalRepository proposals;
	private final ExplanationAccess access;
	private final EntityManager entityManager;
	private final ObjectMapper mapper;
	private final Clock clock;

	public ExplanationTransactions(ProposalRepository proposals, ExplanationAccess access,
		EntityManager entityManager, ObjectMapper mapper, Clock clock) {
		this.proposals = proposals;
		this.access = access;
		this.entityManager = entityManager;
		this.mapper = mapper;
		this.clock = clock;
	}

	@Transactional
	public Lease begin(long proposalId, long viewerId, UUID key) {
		MatchProposal proposal = locked(proposalId, viewerId);
		Map<String, Object> original = expire(proposal, viewerId);
		ExplanationJournal.Claim claim = ExplanationJournal.claim(original, viewerId, key, mapper,
			ExplanationBinding.of(proposal, mapper), clock.instant());
		proposal.recordExplanations(claim.evaluation());
		Map<String, Object> input = original == null ? new LinkedHashMap<>()
			: mapper.convertValue(original, new TypeReference<LinkedHashMap<String, Object>>() { });
		input.remove(ExplanationJournal.FIELD);
		Lease lease = new Lease(claim.run(), claim.created(), input);
		if (claim.created()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					// 실제 성공 커밋 이후의 관측값이다. 트랜잭션 내부의 now를 커밋 시각으로 부르지 않는다.
					lease.committedAt = clock.instant();
				}
			});
		}
		return lease;
	}

	/** 접수 커밋 뒤 관측한 시각을 별도 트랜잭션으로 확정한다. 이 커밋까지 끝나야 제공자를 실행한다. */
	@Transactional
	public ExplanationStatus accept(long proposalId, long viewerId, Lease lease) {
		if (lease.committedAt == null) {
			// 외부 트랜잭션에 참여한 begin은 아직 커밋되지 않았다. 조회의 PENDING과 실행 허가를 구분한다.
			throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
		}
		MatchProposal proposal = locked(proposalId, viewerId);
		Map<String, Object> original = expire(proposal, viewerId);
		String binding = ExplanationBinding.of(proposal, mapper);
		ExplanationJournal.Run current = ExplanationJournal.current(original, viewerId, mapper, binding).orElse(null);
		if (current == null || !current.attemptId().equals(lease.run().attemptId())
			|| !current.inputFingerprint().equals(lease.run().inputFingerprint())) {
			return RuleExplanationProvider.unavailable();
		}
		Map<String, Object> next = ExplanationJournal.accept(original, viewerId, lease.run().attemptId(),
			lease.run().inputFingerprint(), lease.committedAt, mapper, binding);
		proposal.recordExplanations(next);
		expire(proposal, viewerId);
		return status(proposal, viewerId);
	}

	@Transactional
	public ExplanationStatus read(long proposalId, long viewerId) {
		MatchProposal proposal = locked(proposalId, viewerId);
		expire(proposal, viewerId);
		return status(proposal, viewerId);
	}

	/** 공용 작업 조회(GET /api/jobs)용. 설명 GET과 같은 열람 검사·기한 만료를 거친 본인 실행이며, 요청한 적이 없으면 빈 값이다. */
	@Transactional
	public Optional<ExplanationJournal.Run> current(long proposalId, long viewerId) {
		MatchProposal proposal = locked(proposalId, viewerId);
		expire(proposal, viewerId);
		return ExplanationJournal.current(proposal.getEvaluation(), viewerId, mapper,
			ExplanationBinding.of(proposal, mapper));
	}

	@Transactional
	public ExplanationStatus complete(long proposalId, long viewerId, ExplanationJournal.Run attempt,
		ExplanationStatus result) {
		MatchProposal proposal = locked(proposalId, viewerId);
		Map<String, Object> original = expire(proposal, viewerId);
		Map<String, Object> next = ExplanationJournal.finish(original, viewerId, attempt.attemptId(),
			attempt.inputFingerprint(), result, mapper, ExplanationBinding.of(proposal, mapper));
		if (next != original) {
			proposal.recordExplanations(next);
		}
		return status(proposal, viewerId);
	}

	/** 복구는 공개 응답을 만들지 않고 기한이 지난 내부 실행만 닫는다. */
	@Transactional
	public boolean recover(long proposalId) {
		MatchProposal proposal = proposals.lockById(proposalId).orElse(null);
		if (proposal == null) {
			return false;
		}
		entityManager.refresh(proposal, LockModeType.PESSIMISTIC_WRITE);
		Map<String, Object> original = proposal.getEvaluation();
		Map<String, Object> next = ExplanationJournal.expireAll(original, clock.instant());
		if (next == original) {
			return false;
		}
		proposal.recordExplanations(next);
		return true;
	}

	private Map<String, Object> expire(MatchProposal proposal, long viewerId) {
		Map<String, Object> original = proposal.getEvaluation();
		Map<String, Object> next = ExplanationJournal.expire(original, viewerId,
			clock.instant(), mapper, ExplanationBinding.of(proposal, mapper));
		if (next != original) {
			proposal.recordExplanations(next);
		}
		return next;
	}

	private ExplanationStatus status(MatchProposal proposal, long viewerId) {
		return ExplanationJournal.current(proposal.getEvaluation(), viewerId, mapper,
			ExplanationBinding.of(proposal, mapper)).map(ExplanationJournal.Run::result)
			.orElseGet(RuleExplanationProvider::unavailable);
	}

	private MatchProposal locked(long proposalId, long viewerId) {
		MatchProposal proposal = proposals.lockById(proposalId)
			.orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
		entityManager.refresh(proposal, LockModeType.PESSIMISTIC_WRITE);
		access.require(proposalId, viewerId);
		return proposal;
	}

	public static final class Lease {
		private final ExplanationJournal.Run run;
		private final boolean created;
		private final Map<String, Object> evaluation;
		private Instant committedAt;

		private Lease(ExplanationJournal.Run run, boolean created, Map<String, Object> evaluation) {
			this.run = run;
			this.created = created;
			this.evaluation = evaluation;
		}

		public ExplanationJournal.Run run() {
			return run;
		}

		public boolean created() {
			return created;
		}

		public Map<String, Object> evaluation() {
			return evaluation;
		}
	}
}
