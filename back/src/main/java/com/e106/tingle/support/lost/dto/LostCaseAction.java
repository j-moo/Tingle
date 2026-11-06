package com.e106.tingle.support.lost.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LostCaseAction(@NotBlank String action, Long deviceId, UUID ownerGeneration,
	@Size(max = 2000) String message) {
}
