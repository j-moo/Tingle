package com.e106.tingle.keyring.proof;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Canonical v2 relay commands. Authorization and durable receipt storage precede signing these bytes. */
public final class PeerCommandTranscripts {

	private PeerCommandTranscripts() {
	}

	public static byte[] owner(Binding binding, String deviceNonce, int permissions, long issued, long expires) {
		KeyringProofTranscript.decodeBase64Url(deviceNonce, 32);
		require(permissions > 0 && permissions <= 15 && issued > 0 && expires > issued
			&& expires - issued <= 300);
		return text("TINGLE-OWNER-SESSION", "tingle-owner-session-v2", binding,
			deviceNonce, Integer.toString(permissions), Long.toString(issued), Long.toString(expires));
	}

	public static byte[] receipt(Binding binding, UUID event, UUID submission, String envelopeHash,
		UUID receipt, long received) {
		require(event != null && submission != null && receipt != null && received > 0);
		return text("TINGLE-RECORD-RECEIPT", "tingle-record-receipt-v2", binding, event.toString(),
			submission.toString(), hash(envelopeHash), receipt.toString(), Long.toString(received),
			"STORED", "DELETE_ORIGINAL");
	}

	public static byte[] release(Binding binding, UUID event, String peerDeviceRefHex, UUID receipt,
		long received, String result) {
		require(event != null && receipt != null && received > 0 && peerDeviceRefHex != null
			&& peerDeviceRefHex.matches("[0-9a-f]{16}"));
		require("RELEASE_AFTER_DEPARTURE".equals(result) || "ERROR_REVIEWED".equals(result));
		boolean errorFingerprint = Character.digit(peerDeviceRefHex.charAt(0), 16) >= 8;
		require(errorFingerprint == "ERROR_REVIEWED".equals(result));
		return text("TINGLE-RECORD-RELEASE", "tingle-record-release-v2", binding, event.toString(),
			peerDeviceRefHex, receipt.toString(), Long.toString(received), result);
	}

	public static byte[] restore(Binding binding, UUID event, String peerDeviceRefHex, String peerOwnerRefHex,
		UUID grant, long issued, String peerCredentialHash) {
		require(event != null && grant != null && issued > 0 && peerDeviceRefHex != null
			&& peerDeviceRefHex.matches("[0-9a-f]{16}") && peerOwnerRefHex != null
			&& peerOwnerRefHex.matches("[0-9a-f]{32}"));
		return text("TINGLE-ACCOUNT-LOCK", "tingle-account-lock-v2", binding, event.toString(),
			peerDeviceRefHex, peerOwnerRefHex, grant.toString(), Long.toString(issued), hash(peerCredentialHash));
	}

	private static byte[] text(String domain, String protocol, Binding binding, String... fields) {
		require(binding != null);
		String prefix = String.join("\n", domain, protocol, binding.serverKeyId(), binding.serial(),
			binding.deviceKeyId(), Long.toString(binding.deviceId()), binding.ownerGeneration().toString(),
			Long.toString(binding.ownershipEpoch()));
		return (prefix + "\n" + String.join("\n", fields) + "\n").getBytes(StandardCharsets.UTF_8);
	}

	private static String hash(String value) {
		require(value != null && value.matches("[0-9a-f]{64}"));
		return value;
	}

	private static void require(boolean condition) {
		if (!condition) {
			throw new IllegalArgumentException("Invalid peer command transcript input");
		}
	}

	public record Binding(String serverKeyId, String serial, String deviceKeyId, long deviceId,
		UUID ownerGeneration, long ownershipEpoch) {
		public Binding {
			require(serverKeyId != null && serverKeyId.matches("dev-ed25519-[0-9a-f]{16}"));
			require(serial != null && serial.matches("XIAO-[0-9a-f]{16}"));
			require(deviceKeyId != null && deviceKeyId.matches("ed25519-[0-9a-f]{16}"));
			require(deviceId > 0 && ownerGeneration != null && ownershipEpoch >= 0);
		}
	}
}
