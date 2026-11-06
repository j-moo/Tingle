package com.e106.tingle.keyring.reset;

import java.time.Instant;
import java.util.UUID;

/**
 * No default implementation. A reviewed device contract must implement local signing and verification.
 * Never performs BLE/network I/O or accepts an application flag as device completion.
 */
public interface ResetDeviceProtocol {
	String issue(Context context);

	default boolean verifyChallenge(Context context, ResetChallenge challenge) {
		return false;
	}

	/** Verify device signature and exact reset/owner/nonce/consent binding, including archived completion receipts. */
	boolean verify(Context context, String proof);

	record Context(UUID runId, long deviceId, long ownerUserId, UUID ownerGeneration, String serial,
		String keyId, String publicKey, String nonce, boolean discardUnsent, Instant issuedAt, Instant expiresAt,
		long ownershipEpoch, UUID challengeId, String deviceNonce) {
	}
}
