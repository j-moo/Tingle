package com.e106.tingle.keyring.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

public record SyncAckRequest(@Positive long settingsVersion,
	@NotBlank @Pattern(regexp = "[0-9a-f]{64}") String payloadHash,
	@NotNull @Valid RegistrationDeviceProof proof) {
	@Override
	public String toString() {
		return "SyncAckRequest[settingsVersion=" + settingsVersion + ", proof=REDACTED]";
	}
}
