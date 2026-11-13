package com.e106.tingle.encounter.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record EncounterSyncRequest(
	@NotNull @Positive Long deviceId,
	@NotNull @Size(min = 1, max = 100) List<@NotNull @Valid Submission> submissions
) {
	public record Submission(
		@NotNull UUID submissionId,
		@NotNull UUID eventId,
		@NotNull Instant occurredAt,
		@NotNull @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{0,29}") String policyVersion,
		@NotBlank @Size(max = 100) String matchingRuleVersion,
		@NotNull UUID settingsSnapshotId,
		@NotNull @Size(max = 1024) String peerCredential,
		@NotNull @Valid DeviceProof evidence
	) {
		/** Only a signed TER2 REVIEW may omit its peer credential; the cryptographic intake checks the kind. */
		@AssertTrue
		@JsonIgnore
		public boolean isPeerCredentialConsistent() {
			return peerCredential != null && (!peerCredential.isBlank()
				|| (evidence != null && "tingle-encounter-v2".equals(evidence.protocolVersion())));
		}
	}

	public record DeviceProof(@NotBlank String protocolVersion, @NotBlank String payload) {
	}
}
