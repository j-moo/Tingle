package com.e106.tingle.keyring.reset;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** Signed GET_RESET_CHALLENGE fields; outer BLE requestId/type are transport metadata, not authorization. */
public record ResetChallenge(@NotBlank String resetProtocolVersion, @NotBlank String keyId,
	@NotBlank String deviceSerial, @NotNull @PositiveOrZero Long ownershipEpoch,
	@NotBlank String localOwnerGeneration, @NotNull UUID challengeId, @NotBlank String deviceNonce,
	@NotNull Integer expiresInSeconds, @NotBlank @Size(max = 128) String signature) {
	@Override
	public String toString() {
		return "ResetChallenge[REDACTED]";
	}
}
