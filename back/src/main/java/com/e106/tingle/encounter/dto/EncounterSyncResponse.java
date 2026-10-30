package com.e106.tingle.encounter.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record EncounterSyncResponse(List<Receipt> results) {
	public EncounterSyncResponse {
		results = List.copyOf(results);
	}

	public record Receipt(UUID submissionId, UUID eventId, String receiptStatus, UUID receiptId,
		boolean canDeleteLocalRecord, String encounterStatus, Instant counterpartDeadlineAt,
		boolean retryable, String errorCode) {

		public static Receipt unresolved(EncounterSyncRequest.Submission submission) {
			return new Receipt(submission.submissionId(), submission.eventId(), "NOT_STORED", null,
				false, null, null, false, "POLICY_UNRESOLVED");
		}
	}
}
