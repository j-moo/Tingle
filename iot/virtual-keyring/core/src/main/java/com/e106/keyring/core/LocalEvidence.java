package com.e106.keyring.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.UUID;

/** Format-compatible synthetic proof, explicitly not a server-verifiable credential or signature. */
public final class LocalEvidence {
	public static final String PROTOCOL = "virtual-keyring-contract-demo-1";
	public static final String INDEPENDENT_PROTOCOL = "virtual-keyring-local-observation-1";
	public final UUID deviceId;
	public final UUID snapshotId;
	public final String peerCredential;
	public final String payload;

	public LocalEvidence(UUID deviceId, UUID snapshotId, String peerCredential, String payload) {
		this.deviceId = deviceId;
		this.snapshotId = snapshotId;
		this.peerCredential = peerCredential;
		this.payload = payload;
	}

	public static LocalEvidence create(Encounter event, UUID localId, UUID submissionId) {
		if (!event.hasProfiles()) {
			throw new IllegalArgumentException("LEGACY_RECORD_HAS_NO_CONTRACT_EVIDENCE");
		}
		UUID peer = event.peerOf(localId);
		UUID snapshot = event.deviceA.equals(localId) ? event.snapshotA : event.snapshotB;
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			String protocol = event.independent ? INDEPENDENT_PROTOCOL : PROTOCOL;
			digest.update((protocol + "/" + localId + "/" + submissionId).getBytes(StandardCharsets.UTF_8));
			digest.update(Packet.event(event.independent ? Packet.LOCAL_RECORD : Packet.PREPARE, event).encode());
			return new LocalEvidence(localId, snapshot, "lab-credential:" + peer,
				Base64.getEncoder().encodeToString(digest.digest()));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException(exception);
		}
	}
}
