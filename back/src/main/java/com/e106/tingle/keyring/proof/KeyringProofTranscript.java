package com.e106.tingle.keyring.proof;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Pattern;

/** 서버가 보관한 기대값으로 생성하는 서명 원문. 요청에 담긴 원문을 신뢰하지 않는다. */
public final class KeyringProofTranscript {

	public static final String PROTOCOL_VERSION = "tingle-device-proof-v1";
	public static final String PROTOCOL_VERSION_2 = "tingle-device-proof-v2";
	public static final String APPLIED_PROTOCOL_VERSION = "tingle-settings-applied-v1";

	private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
	private static final Pattern SERIAL = Pattern.compile("[A-Za-z0-9._-]{1,64}");
	private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
	private static final Pattern BASE64_URL = Pattern.compile("[A-Za-z0-9_-]+");

	private final byte[] bytes;
	private final String protocolVersion;

	private KeyringProofTranscript(String... fields) {
		this.bytes = (String.join("\n", fields) + "\n").getBytes(StandardCharsets.UTF_8);
		this.protocolVersion = fields[1];
	}

	/** 등록 nonce의 회원 귀속·만료·단회 소비는 별도 트랜잭션에서 검사해야 한다. */
	public static KeyringProofTranscript registration(String keyId, String serialNumber, UUID challengeId,
		String nonce, long expiresAtEpochSeconds) {
		return new KeyringProofTranscript("TINGLE-KEYRING-REGISTER", PROTOCOL_VERSION,
			identifier(keyId, KEY_ID), identifier(serialNumber, SERIAL), identifier(challengeId),
			nonce(nonce), positive(expiresAtEpochSeconds));
	}

	/** 기기가 비휘발성 저장을 완료한 ACK만 이 원문으로 서명한다. */
	public static KeyringProofTranscript stored(String keyId, String serialNumber, long deviceId,
		UUID ownerGeneration, UUID syncRunId, String nonce, long settingsVersion, String payloadHash) {
		return new KeyringProofTranscript("TINGLE-KEYRING-STORED", PROTOCOL_VERSION,
			identifier(keyId, KEY_ID), identifier(serialNumber, SERIAL), positive(deviceId),
			identifier(ownerGeneration), identifier(syncRunId), nonce(nonce), positive(settingsVersion),
			identifier(payloadHash, HASH), "STORED");
	}

	public static KeyringProofTranscript registrationV2(String keyId, String serial, UUID challenge,
		String nonce, long expires, long ownershipEpoch) {
		return new KeyringProofTranscript("TINGLE-KEYRING-REGISTER", PROTOCOL_VERSION_2,
			identifier(keyId, KEY_ID), identifier(serial, SERIAL), identifier(challenge), nonce(nonce),
			positive(expires), nonnegative(ownershipEpoch), "UNOWNED");
	}

	public static KeyringProofTranscript storedV2(String keyId, String serial, long deviceId,
		UUID ownerGeneration, UUID run, String nonce, long version, String hash, long ownershipEpoch) {
		return new KeyringProofTranscript("TINGLE-KEYRING-STORED", PROTOCOL_VERSION_2,
			identifier(keyId, KEY_ID), identifier(serial, SERIAL), positive(deviceId), identifier(ownerGeneration),
			identifier(run), nonce(nonce), positive(version), identifier(hash, HASH), "STORED",
			nonnegative(ownershipEpoch));
	}

	public static KeyringProofTranscript applied(String keyId, String serial, long deviceId,
		UUID ownerGeneration, long ownershipEpoch, UUID run, String nonce, long version, String hash) {
		return new KeyringProofTranscript("TINGLE-KEYRING-APPLIED", APPLIED_PROTOCOL_VERSION,
			identifier(keyId, KEY_ID), identifier(serial, SERIAL), positive(deviceId), identifier(ownerGeneration),
			nonnegative(ownershipEpoch), identifier(run), nonce(nonce), positive(version), identifier(hash, HASH),
			"APPLIED");
	}

	public byte[] bytes() {
		return bytes.clone();
	}

	public String protocolVersion() {
		return protocolVersion;
	}

	public static byte[] decodeBase64Url(String encoded, int expectedLength) {
		if (encoded == null || encoded.length() != (expectedLength * 8 + 5) / 6
			|| !BASE64_URL.matcher(encoded).matches()) {
			throw new IllegalArgumentException("Invalid proof encoding");
		}
		byte[] decoded = Base64.getUrlDecoder().decode(encoded);
		if (decoded.length != expectedLength
			|| !Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(encoded)) {
			throw new IllegalArgumentException("Non-canonical proof encoding");
		}
		return decoded;
	}

	private static String nonce(String value) {
		decodeBase64Url(value, 32);
		return value;
	}

	private static String identifier(String value, Pattern pattern) {
		if (value == null || !pattern.matcher(value).matches()) {
			throw new IllegalArgumentException("Invalid proof field");
		}
		return value;
	}

	private static String identifier(UUID value) {
		if (value == null) {
			throw new IllegalArgumentException("Missing proof identifier");
		}
		return value.toString();
	}

	private static String positive(long value) {
		if (value <= 0) {
			throw new IllegalArgumentException("Proof number must be positive");
		}
		return Long.toString(value);
	}

	private static String nonnegative(long value) {
		if (value < 0) {
			throw new IllegalArgumentException("Ownership epoch must be nonnegative");
		}
		return Long.toString(value);
	}
}
