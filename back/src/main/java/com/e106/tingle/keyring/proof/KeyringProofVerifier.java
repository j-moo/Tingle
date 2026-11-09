package com.e106.tingle.keyring.proof;

import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.interfaces.EdECPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;

/** 서버가 serial/keyId에 대응시킨 공개키만 전달한다. 요청 공개키로 검증해서는 안 된다. */
public final class KeyringProofVerifier {

	public boolean verifyEncodedKey(KeyringProofTranscript transcript, String signature, String trustedPublicKey) {
		try {
			byte[] encoded = KeyringProofTranscript.decodeBase64Url(trustedPublicKey, 44);
			PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(encoded));
			return verify(transcript.protocolVersion(), transcript, signature, key);
		} catch (IllegalArgumentException | InvalidKeySpecException exception) {
			return false;
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("Ed25519 is unavailable", exception);
		}
	}

	/** 형식·서명 불일치는 false, JCA 실행 환경의 알고리즘 부재는 예외로 구분한다. */
	public boolean verify(String protocolVersion, KeyringProofTranscript expectedTranscript,
		String encodedSignature, PublicKey trustedPublicKey) {
		if (!(KeyringProofTranscript.PROTOCOL_VERSION.equals(protocolVersion)
			|| KeyringProofTranscript.PROTOCOL_VERSION_2.equals(protocolVersion)
			|| KeyringProofTranscript.APPLIED_PROTOCOL_VERSION.equals(protocolVersion)) || expectedTranscript == null
			|| !protocolVersion.equals(expectedTranscript.protocolVersion())
			|| !(trustedPublicKey instanceof EdECPublicKey key)
			|| !"Ed25519".equals(key.getParams().getName())) {
			return false;
		}
		byte[] signature;
		try {
			signature = KeyringProofTranscript.decodeBase64Url(encodedSignature, 64);
		} catch (IllegalArgumentException exception) {
			return false;
		}
		try {
			Signature verifier = Signature.getInstance("Ed25519");
			verifier.initVerify(trustedPublicKey);
			verifier.update(expectedTranscript.bytes());
			return verifier.verify(signature);
		} catch (InvalidKeyException | SignatureException exception) {
			return false;
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("Ed25519 is unavailable", exception);
		}
	}
}
