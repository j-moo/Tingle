package com.e106.tingle.encounter.peer;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.e106.tingle.auth.MemberAccessRepository;
import com.e106.tingle.encounter.EncounterEvaluationGateway;
import com.e106.tingle.encounter.EncounterEvaluationWorker;
import com.e106.tingle.encounter.EncounterReceipts;
import com.e106.tingle.encounter.MatchingEvaluationRuntimeVerifier;
import com.e106.tingle.encounter.StoredEncounterInputLookup;
import com.e106.tingle.encounter.VerifiedEncounterIntake;
import com.e106.tingle.encounter.VerifiedEncounterSubmissionMapper;
import com.e106.tingle.encounter.dto.EncounterSyncRequest.DeviceProof;
import com.e106.tingle.encounter.dto.EncounterSyncRequest.Submission;
import com.e106.tingle.encounter.proof.Ter2ProofVerifier.Kind;
import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.json.JsonMapper;

/** Optional business promotion after both independent raw receipts commit. It cannot undo those receipts. */
@Service
@RequiredArgsConstructor
public class Ter2BusinessPromotion {

	private final JdbcTemplate jdbc;
	private final Ter2ReceiptService rawReceipts;
	private final Ter2BusinessProofVerifier proofs;
	private final Ter2BusinessReleaseResolver releases;
	private final StoredEncounterInputLookup inputs;
	private final EncounterReceipts receipts;
	private final EncounterEvaluationWorker worker;
	private final MemberAccessRepository members;
	private final ObjectProvider<MatchingEvaluationRuntimeVerifier> runtimes;
	private final ObjectProvider<EncounterEvaluationGateway> gateways;
	private final PlatformTransactionManager transactions;
	private final Clock clock;
	private final JsonMapper mapper;

	public void promote(long userId, long deviceId, Submission submission) {
		if (TransactionSynchronizationManager.isActualTransactionActive()) {
			throw new IllegalStateException("TER2 promotion requires committed independent raw receipts");
		}
		var original = rawReceipts.verifySubmission(userId, deviceId, submission);
		if (original.kind() != Kind.CONFIRMED || original.peer() == null) {
			throw unresolved();
		}
		MatchingEvaluationRuntimeVerifier runtime = runtimes.getIfAvailable();
		if (runtime == null || gateways.getIfAvailable() == null) {
			throw unresolved();
		}
		releases.resolve(submission.policyVersion(), submission.matchingRuleVersion(), original.occurredAt());
		List<Raw> rows = jdbc.query("""
			select owner_user_id,device_id,submission_id,event_id,occurred_at,raw_payload
			from keyring_peer_encounter where event_id=? and occurred_at=? and device_id in (?,?)
			and encounter_status='VERIFIED_BOTH' and retired=false and raw_payload is not null
			order by owner_user_id,device_id
			""", (row, index) -> new Raw(row.getLong(1), row.getLong(2), row.getObject(3, UUID.class),
				row.getObject(4, UUID.class), row.getTimestamp(5).toInstant(), row.getBytes(6)), original.eventId(),
			Timestamp.from(original.occurredAt()), original.own().deviceId(), original.peer().deviceId());
		if (rows.size() != 2 || rows.getFirst().userId() == rows.getLast().userId()
			|| rows.getFirst().deviceId() == rows.getLast().deviceId()) {
			throw unresolved();
		}
		var adapter = new VerifiedEncounterSubmissionMapper(proofs, inputs, receipts, clock);
		var intake = new VerifiedEncounterIntake(jdbc, mapper, clock, adapter, inputs, receipts, worker, members,
			(policy, rule) -> releases.resolve(policy, rule, original.occurredAt()), transactions, runtime);
		for (Raw raw : rows) {
			boolean own = raw.deviceId() == original.own().deviceId();
			UUID run = own ? original.own().syncRunId() : original.peer().syncRunId();
			byte[] peer = own ? original.peer().raw() : original.own().raw();
			Submission stored = new Submission(raw.submissionId(), raw.eventId(), raw.occurredAt(),
				submission.policyVersion(), submission.matchingRuleVersion(), run, encode(peer),
				new DeviceProof("tingle-encounter-v2", encode(raw.payload())));
			// Each call re-verifies its independently signed envelope and commits its own business receipt.
			intake.accept(raw.userId(), raw.deviceId(), stored);
		}
	}

	private static String encode(byte[] value) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
	}

	private static BusinessException unresolved() {
		return new BusinessException(ErrorCode.POLICY_UNRESOLVED);
	}

	private record Raw(long userId, long deviceId, UUID submissionId, UUID eventId, Instant occurredAt,
		byte[] payload) {
	}
}
