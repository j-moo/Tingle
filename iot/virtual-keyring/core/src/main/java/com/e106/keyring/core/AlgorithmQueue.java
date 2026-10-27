package com.e106.keyring.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Received profiles wait here before calculation; separate from confirmation and upload storage. */
public final class AlgorithmQueue {
	private final Map<UUID, Runnable> jobs = new LinkedHashMap<>();

	public void submit(UUID peer, Runnable calculation) {
		jobs.put(peer, calculation);
	}

	public void runNext() {
		if (!jobs.isEmpty()) {
			UUID first = jobs.keySet().iterator().next();
			jobs.remove(first).run();
		}
	}

	public void remove(UUID peer) {
		jobs.remove(peer);
	}

	public void clear() {
		jobs.clear();
	}

	public int size() {
		return jobs.size();
	}
}
