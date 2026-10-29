package com.e106.tingle.keyring.dto;

import java.time.Instant;
import java.util.UUID;

public record Challenge(UUID challengeId, String nonce, Instant expiresAt, String proofProtocolVersion) {

	@Override
	public String toString() {
		return "Challenge[challengeId=" + challengeId + ", nonce=REDACTED, expiresAt=" + expiresAt
			+ ", proofProtocolVersion=" + proofProtocolVersion + "]";
	}
}
