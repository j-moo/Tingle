package com.e106.tingle.icebreaker;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface IcebreakerGateway {
	boolean enabled();

	Map<String, Object> manifest();

	Generated generate(long contextId, UUID attemptId, Map<String, Object> manifest, Instant deadlineAt);

	default Generated generate(long contextId, UUID attemptId, Map<String, Object> manifest, Instant deadlineAt,
		String commonTopicKey) {
		if (commonTopicKey != null) {
			throw new GatewayException(false);
		}
		return generate(contextId, attemptId, manifest, deadlineAt);
	}

	record Generated(List<String> questions, String source, String promptVersion) {
	}

	class GatewayException extends RuntimeException {
		private final boolean retryable;

		public GatewayException(boolean retryable) {
			super("ICEBREAKER_PROVIDER_FAILURE");
			this.retryable = retryable;
		}

		public boolean retryable() {
			return retryable;
		}
	}
}
