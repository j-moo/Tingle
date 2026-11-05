package com.e106.tingle.keyring.proof;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/** 개발 설정 계약의 서명 원문. 기기 STORED 증명의 키와 프로토콜을 재사용하지 않는다. */
public final class ServerSettingsTranscript {

	public static final String PROTOCOL_VERSION = "tingle-dev-settings-proof-v1";
	public static final String PROTOCOL_VERSION_2 = "tingle-dev-settings-proof-v2";
	public static final String PAYLOAD_FORMAT = "tingle-dev-settings-v1";

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
			|| payload == null || payload.length != 1 || (payload[0] != 0 && payload[0] != 1)
			|| !PAYLOAD_FORMAT.equals(payloadFormat) || serverTime == null
			|| serverTime.getEpochSecond() <= 0 || serverTime.getNano() != 0
			|| (ownershipEpoch != null && ownershipEpoch < 0)) {
			throw new IllegalArgumentException("Invalid settings signature input");
		}
		KeyringProofTranscript.decodeBase64Url(nonce, 32);
		KeyringProofTranscript.decodeBase64Url(deviceNonce, 32);
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
