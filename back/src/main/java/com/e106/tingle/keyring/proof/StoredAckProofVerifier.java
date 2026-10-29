package com.e106.tingle.keyring.proof;

import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.HexFormat;
import java.util.Optional;

import com.e106.tingle.keyring.KeyringSyncRun;

/** 서버에 고정한 실행과 신뢰 공개키로 저장 ACK 서명을 검증한다. 현재 소유·버전은 서비스에서 재검사한다. */
public final class StoredAckProofVerifier {

	private final KeyringProofVerifier verifier = new KeyringProofVerifier();

	/** 잘못된 키·증명은 empty, 암호 실행 환경의 알고리즘 부재는 예외로 구분한다. */
	public Optional<String> verify(KeyringSyncRun run, String deviceSerial, String keyId,
		String protocolVersion, String signature, String trustedPublicKey) {
		if (run == null || run.getKeyringId() == null || run.getSettingsVersion() == null || keyId == null
			|| !keyId.equals(run.getCredentialKeyId())
			|| !KeyringProofTranscript.PROTOCOL_VERSION.equals(protocolVersion)
			|| !protocolVersion.equals(run.getProtocolVersion())) {
			return Optional.empty();
		}
		try {
			byte[] encodedKey = KeyringProofTranscript.decodeBase64Url(trustedPublicKey, 44);
			byte[] signatureBytes = KeyringProofTranscript.decodeBase64Url(signature, 64);
			PublicKey trustedKey = KeyFactory.getInstance("Ed25519")
				.generatePublic(new X509EncodedKeySpec(encodedKey));
			KeyringProofTranscript transcript = KeyringProofTranscript.stored(keyId, deviceSerial,
				run.getKeyringId(), run.getOwnerGeneration(), run.getSyncRunId(), run.getNonce(),
				run.getSettingsVersion(), run.getPayloadHash());
			if (!verifier.verify(protocolVersion, transcript, signature, trustedKey)) {
				return Optional.empty();
			}
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			digest.update(transcript.bytes());
			return Optional.of(HexFormat.of().formatHex(digest.digest(signatureBytes)));
		} catch (IllegalArgumentException | InvalidKeySpecException exception) {
			return Optional.empty();
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("Stored acknowledgement algorithm is unavailable", exception);
		}
	}
}
