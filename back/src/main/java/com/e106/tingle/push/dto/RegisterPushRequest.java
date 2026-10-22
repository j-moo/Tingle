package com.e106.tingle.push.dto;

import java.util.UUID;

import com.e106.tingle.push.PushPlatform;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record RegisterPushRequest(
	@NotNull UUID installationId,
	@NotNull PushPlatform platform,
	@NotBlank String token
) {
	@Override
	public String toString() {
		return "RegisterPushRequest[installationId=" + installationId
			+ ", platform=" + platform + ", token=[REDACTED]]";
	}
}
