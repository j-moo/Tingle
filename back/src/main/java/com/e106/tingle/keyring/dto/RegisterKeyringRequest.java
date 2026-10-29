package com.e106.tingle.keyring.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record RegisterKeyringRequest(
	@NotBlank @Pattern(regexp = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
	String challengeId,
	@NotBlank @Pattern(regexp = "[A-Za-z0-9._-]{1,64}") String deviceSerial,
	@NotNull @Valid RegistrationDeviceProof proof) {

	@Override
	public String toString() {
		return "RegisterKeyringRequest[challengeId=" + challengeId + ", deviceSerial=REDACTED, proof=REDACTED]";
	}
}
