package com.e106.keyring.core;

import java.util.LinkedHashSet;
import java.util.UUID;

/** One confirmation at a time, in bilateral-condition completion order; never score-sorted. */
public final class CandidateQueue {
	private final LinkedHashSet<UUID> waiting = new LinkedHashSet<>();
	private UUID active;

	public boolean add(UUID peer) {
		return waiting.add(peer);
	}

	public boolean acquire(UUID peer) {
		if (active != null) {
			return active.equals(peer);
		}
		if (waiting.isEmpty() || !waiting.iterator().next().equals(peer)) {
			return false;
		}
		active = peer;
		return true;
	}

	public void remove(UUID peer) {
		waiting.remove(peer);
		if (peer.equals(active)) {
			active = null;
		}
	}

	public void clear() {
		waiting.clear();
		active = null;
	}

	public int size() {
		return waiting.size();
	}
}
