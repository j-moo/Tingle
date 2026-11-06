package com.e106.tingle.support.lost.dto;

import java.time.Instant;
import java.util.UUID;

public record LostReauthenticationView(UUID reauthenticationId, long deviceId, UUID ownerGeneration,
	Instant verifiedAt, Instant expiresAt) {
}
