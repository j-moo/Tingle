package com.e106.tingle.encounter;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.e106.tingle.encounter.EncounterEvaluationGateway.Input;
import com.e106.tingle.encounter.EncounterEvaluationGateway.ParticipantInput;
import com.e106.tingle.encounter.EncounterEvaluationGateway.VerifiedResult;
import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.proposal.MatchProposal;
import com.e106.tingle.proposal.evaluation.HistoricalEvaluationEvidence;
import com.e106.tingle.proposal.evaluation.HistoricalEvaluationEvidence.Snapshot;
import com.e106.tingle.proposal.evaluation.HistoricalEvaluationStore;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** 워커가 잠근 현재 실행의 실제 판정과 당시 근거를 같은 완료 트랜잭션에서 확정한다. */
@Service
@RequiredArgsConstructor
public class EncounterEvaluationCompletion {

	private final JdbcTemplate jdbc;
	private final JsonMapper mapper;
	private final HistoricalEvaluationStore historical;
	private final EntityManager entityManager;
	private final StoredEncounterInputLookup inputs;

	@Transactional(propagation = Propagation.MANDATORY)
	public Map<String, Object> complete(Input input, VerifiedResult result) {
		if (input == null || result == null || result.historicalEvidence() == null
			|| result.evaluation() == null || result.evaluation().isEmpty()
			|| result.evaluation().containsKey(HistoricalEvaluationEvidence.STORAGE_KEY)
			|| result.evaluation().containsKey("matchExplanations")) {
			throw new BusinessException(ErrorCode.POLICY_UNRESOLVED);
		}
		HistoricalEvaluationEvidence evidence = result.historicalEvidence();
		if (evidence.matchingId() != input.matchingId() || !evidence.metAt().equals(input.metAt())
			|| !evidence.policyVersion().equals(input.policyVersion())
			|| evidence.left().userId() != input.leftUserId() || evidence.right().userId() != input.rightUserId()
			|| !matches(evidence.left(), input.left()) || !matches(evidence.right(), input.right())
			|| input.releaseId() == null
			|| !input.releaseId().toString().equalsIgnoreCase(evidence.manifest().get("releaseId"))
			|| !input.matchingRuleVersion().equals(evidence.manifest().get("matchingVersion"))) {
			throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
		}
		if (inputs.find(input.left(), input.metAt()).filter(evidence.left()::equals).isEmpty()
			|| inputs.find(input.right(), input.metAt()).filter(evidence.right()::equals).isEmpty()) {
			throw new BusinessException(ErrorCode.POLICY_UNRESOLVED);
		}
		String decision = mapper.writeValueAsString(result.evaluation());
		int updated = jdbc.update("update matching set evaluation=cast(? as jsonb) "
			+ "where id=? and (evaluation is null or evaluation='{}'::jsonb)", decision, input.matchingId());
		if (updated != 1) {
			throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
		}
		historical.store(evidence);
		// JPA의 이전 WAITING/제안 시각이 뒤따르는 JDBC 제안 전이를 덮어쓰지 않게 먼저 반영한다.
		entityManager.flush();
		// 같은 행의 후속 JDBC 제안을 커밋 시점의 JPA dirty checking이 다시 덮지 않게 분리한다.
		entityManager.detach(entityManager.getReference(MatchProposal.class, input.matchingId()));
		String encoded = jdbc.queryForObject("select evaluation::text from matching where id=?",
			String.class, input.matchingId());
		Map<String, Object> finalized = mapper.readValue(encoded,
			new TypeReference<LinkedHashMap<String, Object>>() { });
		if (finalized == null || !finalized.containsKey(HistoricalEvaluationEvidence.STORAGE_KEY)) {
			throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
		}
		return finalized;
	}

	private static boolean matches(Snapshot snapshot, ParticipantInput participant) {
		return snapshot != null && participant != null && snapshot.userId() == participant.userId()
			&& snapshot.snapshotId().equals(participant.settingsSnapshotId())
			&& snapshot.inputHash().equals(participant.inputHash()) && snapshot.settingsVersion() != null
			&& snapshot.settingsVersion() == participant.settingsVersion()
			&& Snapshot.sourceRef(participant.userId(), participant.settingsVersion())
				.equals(snapshot.inputSourceRef());
	}
}
