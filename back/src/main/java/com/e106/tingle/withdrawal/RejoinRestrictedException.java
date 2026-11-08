package com.e106.tingle.withdrawal;

import java.time.Instant;

/** Contains only the time the caller's verified social account can rejoin. */
public class RejoinRestrictedException extends RuntimeException {
	private final Instant rejoinAvailableAt;

	public RejoinRestrictedException(Instant rejoinAvailableAt) {
		super("REJOIN_RESTRICTED");
		this.rejoinAvailableAt = rejoinAvailableAt;
	}

	public Instant getRejoinAvailableAt() {
		return rejoinAvailableAt;
	}
}
