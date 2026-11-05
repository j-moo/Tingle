package com.e106.tingle.safety.dto;

import java.time.Instant;

public record BlockResult(long proposalId, Instant blockedAt) {
}
