package com.e106.tingle.icebreaker;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Explicit disabled test adapter; runtime HTTP adapter remains OFF by default. */
public class PendingIcebreakerGateway implements IcebreakerGateway {
	@Override
	public boolean enabled() {
		return false;
	}

	@Override
	public Map<String, Object> manifest() {
		return Map.of();
	}

	@Override
	public Generated generate(long contextId, UUID attemptId, Map<String, Object> manifest, Instant deadlineAt) {
		throw new GatewayException(false);
	}
}
