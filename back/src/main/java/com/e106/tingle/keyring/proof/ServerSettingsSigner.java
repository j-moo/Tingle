package com.e106.tingle.keyring.proof;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.EdECPrivateKey;
import java.security.interfaces.EdECPublicKey;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;

/** 명시적으로 전달한 전용 Ed25519 키만 사용한다. 서비스 JWT 키/기기 키를 조회하지 않는다. */
public final class ServerSettingsSigner {

	private final PrivateKey privateKey;
	private final PublicKey publicKey;
	private final String rawHex;
	private final String keyId;

	public ServerSettingsSigner(PrivateKey privateKey, PublicKey publicKey) {
		if (!(privateKey instanceof EdECPrivateKey key) || !"Ed25519".equals(key.getParams().getName())
			|| !(publicKey instanceof EdECPublicKey publicEdKey)
			|| !"Ed25519".equals(publicEdKey.getParams().getName())) {
			throw new IllegalArgumentException("Dedicated Ed25519 settings key required");
		}
		byte[] encoded = publicKey.getEncoded();
		byte[] prefix = HexFormat.of().parseHex("302a300506032b6570032100");
		if (encoded == null || encoded.length != 44
			|| !Arrays.equals(prefix, Arrays.copyOf(encoded, prefix.length))) {
			throw new IllegalArgumentException("Canonical Ed25519 SPKI required");
		}
		this.privateKey = privateKey;
		this.publicKey = publicKey;
		rawHex = HexFormat.of().formatHex(Arrays.copyOfRange(encoded, 12, 44));
		keyId = "dev-ed25519-" + rawHex.substring(0, 16);
		try {
			byte[] challenge = "TINGLE-SETTINGS-KEY-PAIR-CHECK".getBytes(StandardCharsets.US_ASCII);
			Signature signer = Signature.getInstance("Ed25519");
			signer.initSign(privateKey);
			signer.update(challenge);
			Signature verifier = Signature.getInstance("Ed25519");
			verifier.initVerify(publicKey);
			verifier.update(challenge);
			if (!verifier.verify(signer.sign())) {
				throw new IllegalArgumentException("Settings key pair does not match");
			}
		} catch (GeneralSecurityException exception) {
			throw new IllegalArgumentException("Invalid settings key pair", exception);
		}
	}

	public String keyId() {
		return keyId;
	}

	public String publicKeyRawHex() {
		return rawHex;
	}

	public String publicKeySpkiBase64url() {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(publicKey.getEncoded());
	}

	public String sign(ServerSettingsTranscript transcript) {
		Objects.requireNonNull(transcript);
		if (!keyId.equals(transcript.serverKeyId())) {
			throw new IllegalArgumentException("Transcript server key does not match signer");
		}
		return sign(transcript.bytes());
	}

	/** Reset authorization uses its own domain-separated canonical transcript with the same reviewed dev key. */
	public String sign(byte[] transcript) {
		if (transcript == null || transcript.length == 0 || transcript.length > 4096) {
			throw new IllegalArgumentException("Invalid signing transcript");
		}
		try {
			Signature signer = Signature.getInstance("Ed25519");
			signer.initSign(privateKey);
			signer.update(transcript);
			return Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign());
		} catch (GeneralSecurityException exception) {
			throw new IllegalStateException("Settings signing unavailable", exception);
		}
	}
}
