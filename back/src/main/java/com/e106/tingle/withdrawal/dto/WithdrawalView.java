package com.e106.tingle.withdrawal.dto;

import java.time.Instant;
import java.util.UUID;

public record WithdrawalView(UUID withdrawalId, String status, Instant preparedAt, Instant expiresAt,
	Instant completedAt, Instant rejoinAvailableAt, String externalCleanupStatus,
	boolean devicePermanentlyBlocked) {
}
