package com.e106.tingle.keyring.proof;

import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.HexFormat;
import java.util.Optional;

import com.e106.tingle.keyring.RegistrationChallenge;

/** 서버가 serial/keyId에 대응시킨 저장 공개키로 등록 증명을 확인한다. */
public final class RegistrationProofVerifier {

	private final KeyringProofVerifier verifier = new KeyringProofVerifier();

	/** 신뢰키·증명 형식 불일치는 empty, 암호 실행 환경의 알고리즘 부재는 예외다. */
	public Optional<String> verify(RegistrationChallenge challenge, String deviceSerial, String keyId,
		String protocolVersion, String signature, String trustedPublicKey) {
		if (challenge == null || challenge.getExpiresAt() == null
			|| !KeyringProofTranscript.PROTOCOL_VERSION.equals(protocolVersion)
			|| !protocolVersion.equals(challenge.getProtocolVersion())) {
			return Optional.empty();
		}
		try {
			byte[] encodedKey = KeyringProofTranscript.decodeBase64Url(trustedPublicKey, 44);
			byte[] signatureBytes = KeyringProofTranscript.decodeBase64Url(signature, 64);
			PublicKey trustedKey = KeyFactory.getInstance("Ed25519")
				.generatePublic(new X509EncodedKeySpec(encodedKey));
			KeyringProofTranscript transcript = KeyringProofTranscript.registration(keyId, deviceSerial,
				challenge.getChallengeId(), challenge.getNonce(), challenge.getExpiresAt().getEpochSecond());
			if (!verifier.verify(protocolVersion, transcript, signature, trustedKey)) {
				return Optional.empty();
			}
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			digest.update(transcript.bytes());
			return Optional.of(HexFormat.of().formatHex(digest.digest(signatureBytes)));
		} catch (IllegalArgumentException | InvalidKeySpecException exception) {
			return Optional.empty();
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("Registration proof algorithm is unavailable", exception);
		}
	}
}
