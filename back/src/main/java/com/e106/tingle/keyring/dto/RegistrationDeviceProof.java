package com.e106.tingle.keyring.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegistrationDeviceProof(
	@NotBlank @Pattern(regexp = "[A-Za-z0-9._-]{1,32}") String protocolVersion,
	@NotBlank @Pattern(regexp = "[A-Za-z0-9._-]{1,64}") String keyId,
	@NotBlank @Size(max = 128) String signature) {

	@Override
	public String toString() {
		return "RegistrationDeviceProof[protocolVersion=" + protocolVersion + ", keyId=" + keyId
			+ ", signature=REDACTED]";
	}
}
