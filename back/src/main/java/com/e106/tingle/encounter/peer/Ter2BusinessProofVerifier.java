package com.e106.tingle.encounter.peer;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.e106.tingle.encounter.EncounterSubmissionProofVerifier;
import com.e106.tingle.encounter.dto.EncounterSyncRequest.Submission;
import com.e106.tingle.encounter.proof.Ter2ProofVerifier.Kind;
import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.keyring.ownership.PeerOwnershipService;

import lombok.RequiredArgsConstructor;

/** Converts the actual cryptographic/historical TER2 verifier's result, not HTTP success flags. */
@Component
@RequiredArgsConstructor
public class Ter2BusinessProofVerifier implements EncounterSubmissionProofVerifier {

	private final Ter2ReceiptService proofs;
	private final PeerOwnershipService ownership;
	private final JdbcTemplate jdbc;

	@Override
	public Claims verify(long userId, long deviceId, Submission submission) {
		var proof = proofs.verifySubmission(userId, deviceId, submission);
		if (proof.kind() != Kind.CONFIRMED || proof.peer() == null) {
			throw unresolved();
		}
		var peer = ownership.findAt(proof.peer().deviceId(), proof.peer().ownerGeneration(),
			proof.peer().ownershipEpoch(), proof.occurredAt()).orElseThrow(Ter2BusinessProofVerifier::unresolved);
		List<String> hashes = jdbc.queryForList("select input_hash from settings_snapshot where user_id=? "
			+ "and settings_version=? and input_format='settings-source-evidence-v1' and redacted_at is null",
			String.class, userId, proof.own().settingsVersion());
		if (hashes.size() != 1 || hashes.getFirst() == null || !hashes.getFirst().matches("[0-9a-f]{64}")) {
			throw unresolved();
		}
		// The source-evidence hash is deliberately distinct from the grant's 196B TPS1 hash.
		return new Claims(userId, deviceId, peer.ownerUserId(), proof.peer().deviceId(), proof.submissionId(),
			proof.eventId(), proof.occurredAt(), submission.policyVersion(), submission.matchingRuleVersion(),
			proof.own().syncRunId(), proof.own().ownerGeneration(), proof.own().settingsVersion(), hashes.getFirst(),
			proof.envelopeHash(), proof.own().ownershipEpoch(), proof.peer().ownerGeneration(),
			proof.peer().ownershipEpoch(), proof.expiresAt(), proof.own().opportunitySeconds());
	}

	private static BusinessException unresolved() {
		return new BusinessException(ErrorCode.POLICY_UNRESOLVED);
	}
}
