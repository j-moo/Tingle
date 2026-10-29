package com.e106.tingle.icebreaker;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

/** Common service authentication must be wired before enabling external generation. */
@Component
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
