package com.e106.tingle.encounter.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

public record EncounterSyncResponse(List<Receipt> results) {
	public EncounterSyncResponse {
		results = List.copyOf(results);
	}

	public record Receipt(UUID submissionId, UUID eventId, String receiptStatus, UUID receiptId,
		boolean canDeleteLocalRecord, String encounterStatus, Instant counterpartDeadlineAt,
		boolean retryable, String errorCode,
		@JsonInclude(JsonInclude.Include.NON_NULL) ServerReceipt serverProof) {

		public Receipt(UUID submissionId, UUID eventId, String receiptStatus, UUID receiptId,
			boolean canDeleteLocalRecord, String encounterStatus, Instant counterpartDeadlineAt,
			boolean retryable, String errorCode) {
			this(submissionId, eventId, receiptStatus, receiptId, canDeleteLocalRecord, encounterStatus,
				counterpartDeadlineAt, retryable, errorCode, null);
		}

		public static Receipt unresolved(EncounterSyncRequest.Submission submission) {
			return new Receipt(submission.submissionId(), submission.eventId(), "NOT_STORED", null,
				false, null, null, false, "POLICY_UNRESOLVED");
		}
	}

	public record ServerReceipt(String proofProtocolVersion, String serverKeyId, long deviceId,
		UUID ownerGeneration, long ownershipEpoch, UUID eventId, UUID submissionId, String envelopeHash,
		UUID receiptId, long receivedAtEpochSeconds, String receiptStatus, String deletePermission,
		String serverSignature) {
	}
}
