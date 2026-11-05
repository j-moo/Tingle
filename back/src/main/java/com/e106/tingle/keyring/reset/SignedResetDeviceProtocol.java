package com.e106.tingle.keyring.reset;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Map;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.keyring.proof.KeyringProofTranscript;
import com.e106.tingle.keyring.proof.ServerSettingsSigner;

import tools.jackson.databind.json.JsonMapper;

/** IoT !217 reset v1. Pure local cryptography; no network, implicit erasure or runtime fallback key. */
public final class SignedResetDeviceProtocol implements ResetDeviceProtocol {
	public static final String VERSION = "tingle-keyring-reset-v1";
	public static final String SCOPE = "OWNER_DATA_V1";
	private final ServerSettingsSigner signer;
	private final JsonMapper mapper = JsonMapper.builder().build();

	public SignedResetDeviceProtocol(ServerSettingsSigner signer) {
		this.signer = signer;
	}

	@Override
	public boolean verifyChallenge(Context context, ResetChallenge challenge) {
		try {
			if (challenge == null || !VERSION.equals(challenge.resetProtocolVersion())
				|| !context.keyId().equals(challenge.keyId()) || !context.serial().equals(challenge.deviceSerial())
				|| challenge.ownershipEpoch() == null || context.ownershipEpoch() != challenge.ownershipEpoch()
				|| challenge.expiresInSeconds() == null || challenge.expiresInSeconds() != 60
				|| !(context.ownerGeneration().toString().equals(challenge.localOwnerGeneration())
					|| "NONE".equals(challenge.localOwnerGeneration()))) {
				return false;
			}
			return verify(context, challengeTranscript(challenge), challenge.signature());
		} catch (IllegalArgumentException | NullPointerException exception) {
			return false;
		}
	}

	@Override
	public String issue(Context context) {
		try {
			Map<String, Object> command = new java.util.LinkedHashMap<>();
			command.put("protocolVersion", 1);
			command.put("type", "RESET_DEVICE");
			command.put("requestId", context.runId().toString());
			command.put("resetProtocolVersion", VERSION);
			command.put("serverKeyId", signer.keyId());
			command.put("keyId", context.keyId());
			command.put("deviceSerial", context.serial());
			command.put("deviceId", context.deviceId());
			command.put("ownerGeneration", context.ownerGeneration().toString());
			command.put("resetRunId", context.runId().toString());
			command.put("fromOwnershipEpoch", context.ownershipEpoch());
			command.put("toOwnershipEpoch", nextEpoch(context));
			command.put("challengeId", context.challengeId().toString());
			command.put("deviceNonce", context.deviceNonce());
			command.put("resetNonce", context.nonce());
			command.put("deletionScope", SCOPE);
			command.put("recordPolicy", policy(context));
			command.put("serverSignature", signer.sign(authorizationTranscript(context, signer.keyId())));
			return mapper.writeValueAsString(command) + "\n";
		} catch (IllegalArgumentException | NullPointerException exception) {
			throw new BusinessException(ErrorCode.INVALID_DEVICE_PROOF);
		}
	}

	@Override
	public boolean verify(Context context, String proof) {
		try {
			return verify(context, clearedTranscript(context), proof);
		} catch (IllegalArgumentException | NullPointerException exception) {
			return false;
		}
	}

	public static byte[] challengeTranscript(ResetChallenge challenge) {
		return text("TINGLE-KEYRING-RESET-CHALLENGE", VERSION, identifier(challenge.keyId()),
			identifier(challenge.deviceSerial()), epoch(challenge.ownershipEpoch()),
			"NONE".equals(challenge.localOwnerGeneration()) ? "NONE"
				: java.util.UUID.fromString(challenge.localOwnerGeneration()).toString(),
			challenge.challengeId().toString(), nonce(challenge.deviceNonce()), "60");
	}

	public static byte[] authorizationTranscript(Context context, String serverKeyId) {
		return text("TINGLE-KEYRING-RESET-AUTH", VERSION, identifier(serverKeyId), identifier(context.keyId()),
			identifier(context.serial()), positive(context.deviceId()), context.ownerGeneration().toString(),
			context.runId().toString(), epoch(context.ownershipEpoch()), epoch(nextEpoch(context)),
			context.challengeId().toString(), nonce(context.deviceNonce()), nonce(context.nonce()), SCOPE,
			policy(context));
	}

	public static byte[] clearedTranscript(Context context) {
		return text("TINGLE-KEYRING-RESET-CLEARED", VERSION, identifier(context.keyId()), identifier(context.serial()),
			positive(context.deviceId()), context.ownerGeneration().toString(), context.runId().toString(),
			epoch(context.ownershipEpoch()), epoch(nextEpoch(context)), nonce(context.nonce()), SCOPE,
			policy(context), "LOGICAL", "CLEARED");
	}

	private boolean verify(Context context, byte[] transcript, String signature) {
		try {
			var key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(
				KeyringProofTranscript.decodeBase64Url(context.publicKey(), 44)));
			Signature verifier = Signature.getInstance("Ed25519");
			verifier.initVerify(key);
			verifier.update(transcript);
			return verifier.verify(KeyringProofTranscript.decodeBase64Url(signature, 64));
		} catch (java.security.GeneralSecurityException | IllegalArgumentException | NullPointerException exception) {
			return false;
		}
	}

	private static long nextEpoch(Context context) {
		if (context.ownershipEpoch() < 0 || context.ownershipEpoch() == Long.MAX_VALUE) {
			throw new IllegalArgumentException();
		}
		return context.ownershipEpoch() + 1;
	}

	private static String policy(Context context) {
		return context.discardUnsent() ? "DISCARD_WITH_CONSENT" : "REQUIRE_SYNCED";
	}

	private static String epoch(long value) {
		if (value < 0) {
			throw new IllegalArgumentException();
		}
		return Long.toString(value);
	}

	private static String positive(long value) {
		if (value <= 0) {
			throw new IllegalArgumentException();
		}
		return Long.toString(value);
	}

	private static String nonce(String value) {
		KeyringProofTranscript.decodeBase64Url(value, 32);
		return value;
	}

	private static String identifier(String value) {
		if (value == null || !value.matches("[A-Za-z0-9._-]{1,64}")) {
			throw new IllegalArgumentException();
		}
		return value;
	}

	private static byte[] text(String... fields) {
		return (String.join("\n", fields) + "\n").getBytes(StandardCharsets.UTF_8);
	}
}
