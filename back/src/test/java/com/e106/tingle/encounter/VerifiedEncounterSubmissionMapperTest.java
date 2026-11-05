package com.e106.tingle.encounter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;

import com.e106.tingle.encounter.EncounterSubmissionProofVerifier.Claims;
import com.e106.tingle.encounter.dto.EncounterSyncRequest.DeviceProof;
import com.e106.tingle.encounter.dto.EncounterSyncRequest.Submission;
import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.proposal.evaluation.HistoricalEvaluationEvidence.Snapshot;

import tools.jackson.databind.json.JsonMapper;

/** 합성 proof 검증자를 사용한다. 실제 서명·키링 수용 검증이 아니다. */
class VerifiedEncounterSubmissionMapperTest {
	private static final Instant MET = Instant.parse("2026-11-02T00:00:00Z");
	private static final String INPUT_HASH = "a".repeat(64);
	private static final String PROOF_HASH = "4bf5122f344554c53bde2ebb8cd2b7e3d1600ad631c385a5d7cce23c7785459a";
	private final UUID snapshotId = UUID.randomUUID();
	private final UUID generation = UUID.randomUUID();
	private final EncounterSubmissionProofVerifier proof = mock(EncounterSubmissionProofVerifier.class);
	private final StoredEncounterInputLookup inputs = mock(StoredEncounterInputLookup.class);
	private final EncounterReceipts receipts = new EncounterReceipts(mock(JdbcTemplate.class),
		JsonMapper.builder().build());
	private Submission submission;
	private VerifiedEncounterSubmissionMapper mapper;
	private Snapshot snapshot;

	@BeforeEach
	void prepareSyntheticProofMapping() {
		submission = new Submission(UUID.randomUUID(), UUID.randomUUID(), MET, "policy-1", "rule-1", snapshotId,
			"synthetic-peer-credential", new DeviceProof("synthetic-encounter-1", "AQ=="));
		snapshot = new Snapshot(1, snapshotId, INPUT_HASH, 2L, Snapshot.sourceRef(1, 2));
		mapper = new VerifiedEncounterSubmissionMapper(proof, inputs, receipts,
			Clock.fixed(MET.plusSeconds(60), ZoneOffset.UTC));
	}

	@Test
	void bindsTheOriginalSubmissionAndAckWithoutIssuingAReceiptOrChangingItsHash() {
		when(proof.verify(1, 11, submission)).thenReturn(claims("valid"));
		when(inputs.find(any(), eq(MET))).thenReturn(Optional.of(snapshot));
		var prepared = mapper.prepare(1, 11, submission);
		assertThat(prepared.snapshot()).isEqualTo(snapshot);
		assertThat(prepared.participant().requestHash()).isEqualTo(receipts.canonicalHash(submission));
		assertThat(prepared.participant().ownerGeneration()).isEqualTo(generation);
		assertThat(prepared.claims().peerUserId()).isEqualTo(2);
	}

	@ParameterizedTest
	@ValueSource(strings = {"user", "device", "peerUser", "peerDevice", "submission", "event", "time",
		"policy", "rule", "snapshot", "generation", "settings", "inputHash", "proofHash"})
	void rejectsUnboundOrInvalidProofClaimsBeforeLookingUpStoredInput(String mutation) {
		when(proof.verify(1, 11, submission)).thenReturn(claims(mutation));
		reject(() -> mapper.prepare(1, 11, submission));
		verifyNoInteractions(inputs);
	}

	@Test
	void missingAckCannotBeReplacedWithCurrentSettings() {
		when(proof.verify(1, 11, submission)).thenReturn(claims("valid"));
		when(inputs.find(any(), eq(MET))).thenReturn(Optional.empty());
		reject(() -> mapper.prepare(1, 11, submission));
	}

	@Test
	void differentStoredSourceCannotSatisfyTheOriginalProof() {
		when(proof.verify(1, 11, submission)).thenReturn(claims("valid"));
		when(inputs.find(any(), eq(MET))).thenReturn(Optional.of(
			new Snapshot(1, snapshotId, INPUT_HASH, 2L, "settings_snapshot:1:3")));
		reject(() -> mapper.prepare(1, 11, submission));
	}

	@Test
	void unavailableOfficialProofCannotProducePreparedInput() {
		when(proof.verify(1, 11, submission)).thenReturn(null);
		reject(() -> mapper.prepare(1, 11, submission));
		verifyNoInteractions(inputs);
	}

	@Test
	void futureEncounterCannotReachTheProofSupplier() {
		mapper = new VerifiedEncounterSubmissionMapper(proof, inputs, receipts,
			Clock.fixed(MET.minusSeconds(1), ZoneOffset.UTC));
		reject(() -> mapper.prepare(1, 11, submission));
		verifyNoInteractions(proof, inputs);
	}

	private Claims claims(String changed) {
		return new Claims("user".equals(changed) ? 9 : 1, "device".equals(changed) ? 99 : 11,
			"peerUser".equals(changed) ? 1 : 2, "peerDevice".equals(changed) ? 11 : 22,
			"submission".equals(changed) ? UUID.randomUUID() : submission.submissionId(),
			"event".equals(changed) ? UUID.randomUUID() : submission.eventId(),
			"time".equals(changed) ? MET.plusSeconds(1) : MET,
			"policy".equals(changed) ? "other" : submission.policyVersion(),
			"rule".equals(changed) ? "other" : submission.matchingRuleVersion(),
			"snapshot".equals(changed) ? UUID.randomUUID() : snapshotId,
			"generation".equals(changed) ? null : generation, "settings".equals(changed) ? 0 : 2,
			"inputHash".equals(changed) ? "not-a-hash" : INPUT_HASH,
			"proofHash".equals(changed) ? "b".repeat(64) : PROOF_HASH);
	}

	private void reject(Runnable operation) {
		assertThatThrownBy(operation::run).isInstanceOfSatisfying(BusinessException.class,
			exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.POLICY_UNRESOLVED));
	}
}
