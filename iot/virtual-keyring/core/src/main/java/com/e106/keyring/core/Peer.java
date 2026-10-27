package com.e106.keyring.core;

import java.util.Objects;
import java.util.UUID;

public final class Peer {
	public final UUID deviceId;
	public final UUID snapshotId;
	public final long settingsVersion;
	public final boolean ready;

	public Peer(UUID deviceId, UUID snapshotId, long settingsVersion, boolean ready) {
		this.deviceId = Objects.requireNonNull(deviceId);
		this.snapshotId = Objects.requireNonNull(snapshotId);
		if (settingsVersion < 0 || (ready && settingsVersion == 0)) {
			throw new IllegalArgumentException("INVALID_SETTINGS_VERSION");
		}
		this.settingsVersion = settingsVersion;
		this.ready = ready;
	}
}
