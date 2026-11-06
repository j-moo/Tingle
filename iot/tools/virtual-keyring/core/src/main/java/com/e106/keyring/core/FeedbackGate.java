package com.e106.keyring.core;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** One completion effect per peer until this device starts a new ON session. Serial executor only. */
public final class FeedbackGate {
	private final Set<UUID> notified = new HashSet<>();
	private long session;

	public boolean claim(UUID peer, long localSession) {
		if (localSession == 0) {
			return false;
		}
		if (session != localSession) {
			notified.clear();
			session = localSession;
		}
		return notified.add(peer);
	}
}
