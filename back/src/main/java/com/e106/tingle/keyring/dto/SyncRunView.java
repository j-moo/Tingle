package com.e106.tingle.keyring.dto;

import java.time.Instant;
import java.util.UUID;

import com.e106.tingle.keyring.KeyringSyncState;

public record SyncRunView(UUID syncRunId, UUID settingsSnapshotId, long deviceId, UUID ownerGeneration,
	long settingsVersion, long currentSettingsVersion, KeyringSyncState state, boolean stale,
	String payload, String payloadEncoding, String payloadFormat, String payloadHash, String nonce,
	String keyId, String proofProtocolVersion, Instant createdAt, Instant ackedAt, Instant serverTime) {
	@Override
	public String toString() {
		return "SyncRunView[syncRunId=" + syncRunId + ", state=" + state + ", payload=REDACTED]";
	}
}
