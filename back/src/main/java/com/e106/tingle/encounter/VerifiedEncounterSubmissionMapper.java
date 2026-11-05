package com.e106.tingle.encounter;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;

import com.e106.tingle.encounter.EncounterEvaluationGateway.ParticipantInput;
import com.e106.tingle.encounter.EncounterSubmissionProofVerifier.Claims;
import com.e106.tingle.encounter.dto.EncounterSyncRequest.Submission;
import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.proposal.evaluation.HistoricalEvaluationEvidence.Snapshot;

/** 증명 공급자의 결과와 원래 제출·저장 ACK를 결합한다. 접수 영수증이나 삭제 허용을 만들지 않는다. */
public final class VerifiedEncounterSubmissionMapper {
	private final EncounterSubmissionProofVerifier verifier;
	private final StoredEncounterInputLookup inputs;
	private final EncounterReceipts receipts;
	private final Clock clock;

	public VerifiedEncounterSubmissionMapper(EncounterSubmissionProofVerifier verifier,
		StoredEncounterInputLookup inputs, EncounterReceipts receipts, Clock clock) {
		this.verifier = Objects.requireNonNull(verifier);
		this.inputs = Objects.requireNonNull(inputs);
		this.receipts = Objects.requireNonNull(receipts);
		this.clock = Objects.requireNonNull(clock);
	}

	public Prepared prepare(long userId, long deviceId, Submission submission) {
		if (userId <= 0 || deviceId <= 0 || submission == null || submission.submissionId() == null
			|| submission.eventId() == null || submission.settingsSnapshotId() == null
			|| submission.policyVersion() == null || submission.matchingRuleVersion() == null
			|| submission.peerCredential() == null || submission.peerCredential().isBlank()
			|| submission.occurredAt() == null
			|| submission.occurredAt().isAfter(clock.instant()) || submission.evidence() == null) {
			throw unresolved();
		}
		Claims claims = verifier.verify(userId, deviceId, submission);
		if (claims == null || claims.userId() != userId || claims.deviceId() != deviceId
			|| claims.peerUserId() <= 0 || claims.peerUserId() == userId
			|| claims.peerDeviceId() <= 0 || claims.peerDeviceId() == deviceId
			|| !Objects.equals(claims.submissionId(), submission.submissionId())
			|| !Objects.equals(claims.eventId(), submission.eventId())
			|| !Objects.equals(claims.occurredAt(), submission.occurredAt())
			|| !Objects.equals(claims.policyVersion(), submission.policyVersion())
			|| !Objects.equals(claims.matchingRuleVersion(), submission.matchingRuleVersion())
			|| !Objects.equals(claims.settingsSnapshotId(), submission.settingsSnapshotId())
			|| claims.ownerGeneration() == null || claims.settingsVersion() <= 0
			|| claims.inputHash() == null || !claims.inputHash().matches("[a-f0-9]{64}")
			|| !Objects.equals(claims.proofHash(), proofHash(submission))) {
			throw unresolved();
		}
		ParticipantInput participant = new ParticipantInput(userId, deviceId, submission.submissionId(),
			submission.eventId(), receipts.canonicalHash(submission), claims.settingsSnapshotId(),
			claims.settingsVersion(), claims.ownerGeneration(), claims.inputHash(), claims.proofHash());
		Snapshot snapshot = inputs.find(participant, submission.occurredAt()).orElseThrow(
			VerifiedEncounterSubmissionMapper::unresolved);
		if (snapshot.userId() != userId || !snapshot.snapshotId().equals(participant.settingsSnapshotId())
			|| !snapshot.inputHash().equals(participant.inputHash())
			|| !Objects.equals(snapshot.settingsVersion(), participant.settingsVersion())
			|| !Snapshot.sourceRef(userId, participant.settingsVersion()).equals(snapshot.inputSourceRef())) {
			throw unresolved();
		}
		return new Prepared(claims, participant, snapshot);
	}

	private static String proofHash(Submission submission) {
		try {
			byte[] raw = Base64.getDecoder().decode(submission.evidence().payload());
			if (raw.length == 0) {
				throw unresolved();
			}
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
		} catch (IllegalArgumentException | NullPointerException exception) {
			throw unresolved();
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is required", exception);
		}
	}

	private static BusinessException unresolved() {
		return new BusinessException(ErrorCode.POLICY_UNRESOLVED);
	}

	/** 검증된 당시 입력일 뿐, 현재 권한 재검증·영속 저장·양측 대응을 대신하지 않는다. */
	public record Prepared(Claims claims, ParticipantInput participant, Snapshot snapshot) {
	}
}
