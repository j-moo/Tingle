package com.e106.tingle.keyring.proof;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;

/** 개발 설정 계약의 서명 원문. 기기 STORED 증명의 키와 프로토콜을 재사용하지 않는다. */
public final class ServerSettingsTranscript {

	public static final String PROTOCOL_VERSION = "tingle-dev-settings-proof-v1";
	public static final String PROTOCOL_VERSION_2 = "tingle-dev-settings-proof-v2";
	public static final String PAYLOAD_FORMAT = "tingle-dev-settings-v1";
	public static final String PROFILE_PAYLOAD_FORMAT = "tingle-profile-storage-v1";
	public static final String PEER_PAYLOAD_FORMAT = "tingle-keyring-settings-v3";
	private static final int PROFILE_PAYLOAD_BYTES = 196;
	private static final int[] PROFILE_TAGS = {0x01, 0x10, 0x11, 0x20, 0x21, 0x30, 0x31, 0x32, 0x40, 0x41};
	private static final int[] PROFILE_LENGTHS = {46, 9, 39, 5, 39, 3, 2, 2, 16, 1};

	private final byte[] bytes;
	private final String serverKeyId;

	private ServerSettingsTranscript(String serverKeyId, String... fields) {
		this.serverKeyId = serverKeyId;
		bytes = (String.join("\n", fields) + "\n").getBytes(StandardCharsets.UTF_8);
	}

	private ServerSettingsTranscript(String serverKeyId, byte[] bytes) {
		this.serverKeyId = serverKeyId;
		this.bytes = bytes.clone();
	}

	/** payload 원문 대신 SHA-256을 결속한다. JSON 직렬화 순서에 의존하지 않는다. */
	public static ServerSettingsTranscript create(String serverKeyId, String serial, String deviceKeyId, long deviceId,
		UUID ownerGeneration, UUID syncRunId, long settingsVersion, String payloadFormat,
		byte[] payload, String nonce, String deviceNonce, Instant serverTime) {
		return create(serverKeyId, serial, deviceKeyId, deviceId, ownerGeneration, syncRunId, settingsVersion,
			payloadFormat, payload, nonce, deviceNonce, serverTime, null);
	}

	public static ServerSettingsTranscript create(String serverKeyId, String serial, String deviceKeyId, long deviceId,
		UUID ownerGeneration, UUID syncRunId, long settingsVersion, String payloadFormat,
		byte[] payload, String nonce, String deviceNonce, Instant serverTime, Long ownershipEpoch) {
		if (deviceId <= 0 || settingsVersion <= 0 || ownerGeneration == null || syncRunId == null
			|| !validPayloadFraming(payloadFormat, payload) || serverTime == null
			|| serverTime.getEpochSecond() <= 0 || serverTime.getNano() != 0
			|| (ownershipEpoch != null && ownershipEpoch < 0)) {
			throw new IllegalArgumentException("Invalid settings signature input");
		}
		KeyringProofTranscript.decodeBase64Url(nonce, 32);
		KeyringProofTranscript.decodeBase64Url(deviceNonce, 32);
		if (PEER_PAYLOAD_FORMAT.equals(payloadFormat)
			&& !validPeerBinding(payload, serial, deviceKeyId, deviceId, ownerGeneration, syncRunId,
				settingsVersion, nonce, serverTime, ownershipEpoch)) {
			throw new IllegalArgumentException("Peer grant does not match settings signature input");
		}
		String protocol = ownershipEpoch == null ? PROTOCOL_VERSION : PROTOCOL_VERSION_2;
		ServerSettingsTranscript base = new ServerSettingsTranscript(identifier(serverKeyId),
			"TINGLE-KEYRING-SERVER-SETTINGS", protocol,
			serverKeyId, identifier(serial), identifier(deviceKeyId), Long.toString(deviceId),
			ownerGeneration.toString(), syncRunId.toString(), Long.toString(settingsVersion),
			identifier(payloadFormat), hash(payload), nonce,
			deviceNonce, Long.toString(serverTime.getEpochSecond()));
		return ownershipEpoch == null ? base : new ServerSettingsTranscript(serverKeyId,
			(new String(base.bytes(), StandardCharsets.UTF_8) + ownershipEpoch + "\n")
				.getBytes(StandardCharsets.UTF_8));
	}

	private static boolean validPayloadFraming(String format, byte[] payload) {
		if (payload == null) {
			return false;
		}
		if (PAYLOAD_FORMAT.equals(format)) {
			return payload.length == 1 && (payload[0] == 0 || payload[0] == 1);
		}
		if (PEER_PAYLOAD_FORMAT.equals(format)) {
			return PeerSettingsCodec.validPayloadFraming(payload);
		}
		if (!PROFILE_PAYLOAD_FORMAT.equals(format) || payload.length != PROFILE_PAYLOAD_BYTES
			|| payload[0] != 'T' || payload[1] != 'P' || payload[2] != 'S' || payload[3] != '1') {
			return false;
		}
		int offset = 4;
		for (int index = 0; index < PROFILE_TAGS.length; index++) {
			if (payload[offset] != PROFILE_TAGS[index] || payload[offset + 1] != PROFILE_LENGTHS[index]
				|| payload[offset + 2] != 0) {
				return false;
			}
			offset += 3 + PROFILE_LENGTHS[index];
		}
		return offset == payload.length;
	}

	/** TSV3 must use the epoch-bearing proof and the exact identity/run/nonce signed into its grant. */
	private static boolean validPeerBinding(byte[] payload, String serial, String deviceKeyId, long deviceId,
		UUID ownerGeneration, UUID syncRunId, long settingsVersion, String nonce, Instant serverTime,
		Long ownershipEpoch) {
		if (ownershipEpoch == null || ownershipEpoch > 0xffff_ffffL || settingsVersion > 0xffff_ffffL
			|| serial == null || !serial.matches("XIAO-[0-9a-f]{16}")
			|| deviceKeyId == null || !deviceKeyId.matches("ed25519-[0-9a-f]{16}")) {
			return false;
		}
		byte[] grant = Arrays.copyOfRange(payload, 200, 461);
		ByteBuffer fields = ByteBuffer.wrap(grant).order(ByteOrder.LITTLE_ENDIAN);
		byte[] profileHash = HexFormat.of().parseHex(hash(Arrays.copyOfRange(payload, 4, 200)));
		return fields.getLong(9) == deviceId
			&& Integer.toUnsignedLong(fields.getInt(17)) == settingsVersion
			&& Integer.toUnsignedLong(fields.getInt(21)) == ownershipEpoch
			&& Arrays.equals(Arrays.copyOfRange(grant, 1, 9), HexFormat.of().parseHex(serial.substring(5)))
			&& Arrays.equals(Arrays.copyOfRange(grant, 29, 45), uuidBytes(ownerGeneration))
			&& Arrays.equals(Arrays.copyOfRange(grant, 45, 61), uuidBytes(syncRunId))
			&& Arrays.equals(Arrays.copyOfRange(grant, 61, 93), KeyringProofTranscript.decodeBase64Url(nonce, 32))
			&& Arrays.equals(Arrays.copyOfRange(grant, 133, 165), profileHash)
			&& Arrays.equals(Arrays.copyOfRange(grant, 165, 173), HexFormat.of().parseHex(deviceKeyId.substring(8)))
			&& fields.getLong(125) <= serverTime.getEpochSecond()
			&& serverTime.getEpochSecond() < fields.getLong(117);
	}

	private static byte[] uuidBytes(UUID value) {
		return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits())
			.putLong(value.getLeastSignificantBits()).array();
	}

	String serverKeyId() {
		return serverKeyId;
	}

	public byte[] bytes() {
		return bytes.clone();
	}

	private static String identifier(String value) {
		if (value == null || !value.matches("[A-Za-z0-9._-]{1,64}")) {
			throw new IllegalArgumentException("Invalid settings signature identifier");
		}
		return value;
	}

	private static String hash(byte[] payload) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	@Override
	public String toString() {
		return "ServerSettingsTranscript[REDACTED]";
	}
}
