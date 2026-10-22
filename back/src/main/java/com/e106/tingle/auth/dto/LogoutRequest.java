package com.e106.tingle.auth.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;

public record LogoutRequest(@NotBlank String refreshToken, UUID releaseInstallationId) {

	@Override
	public String toString() {
		return "LogoutRequest[refreshToken=[REDACTED], releaseInstallationId=" + releaseInstallationId + "]";
	}
}
