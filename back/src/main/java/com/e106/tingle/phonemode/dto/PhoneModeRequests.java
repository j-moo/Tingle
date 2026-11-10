package com.e106.tingle.phonemode.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class PhoneModeRequests {
	private PhoneModeRequests() {
	}

	public record Register(@NotNull UUID installationId, @NotNull Boolean replaceExisting) {
	}

	public record Issue(@NotNull UUID deviceId) {
	}

	public record Submit(@NotNull Integer protocolVersion, @NotNull @Size(max = 43) String ownToken,
		@NotNull @Size(max = 22) String ownNonce, @NotNull @Size(max = 43) String peerToken,
		@NotNull @Size(max = 22) String peerNonce) {
	}

	public record DeviceResponse(UUID deviceId, String status) {
	}
}
