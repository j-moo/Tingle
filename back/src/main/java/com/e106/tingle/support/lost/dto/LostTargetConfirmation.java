package com.e106.tingle.support.lost.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record LostTargetConfirmation(@Positive long deviceId, @NotNull UUID ownerGeneration,
	@NotNull Boolean confirmed) {
}
