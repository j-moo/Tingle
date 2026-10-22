package com.e106.tingle.push;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 푸시 전달 토큰 저장용 암호화. JWT 서명 키와 별도의 256비트 키를 사용한다.
 * 암호문을 다른 설치나 회원에게 옮겨도 복호화되지 않도록 소유 문맥을 인증한다.
 */
final class PushTokenCipher {

	private static final String VERSION = "v1";
	private static final int KEY_BYTES = 32;
	private static final int NONCE_BYTES = 12;
	private static final int TAG_BITS = 128;
	private static final String TRANSFORMATION = "AES/GCM/NoPadding";

	private final SecretKey key;
	private final SecureRandom random = new SecureRandom();

	PushTokenCipher(String encodedKey) {
		byte[] bytes;
		try {
			bytes = Base64.getDecoder().decode(Objects.requireNonNull(encodedKey));
		} catch (IllegalArgumentException | NullPointerException e) {
			throw new IllegalArgumentException("푸시 토큰 암호화 키는 Base64 형식의 32바이트여야 합니다.");
		}
		try {
			if (bytes.length != KEY_BYTES) {
				throw new IllegalArgumentException("푸시 토큰 암호화 키는 Base64 형식의 32바이트여야 합니다.");
			}
			key = new SecretKeySpec(bytes, "AES");
		} finally {
			Arrays.fill(bytes, (byte)0);
		}
	}

	String encrypt(UUID installationId, long userId, String token) {
		if (token == null || token.isBlank()) {
			throw new IllegalArgumentException("푸시 토큰이 필요합니다.");
		}
		byte[] context = contextOf(installationId, userId);
		byte[] nonce = new byte[NONCE_BYTES];
		random.nextBytes(nonce);
		try {
			Cipher cipher = Cipher.getInstance(TRANSFORMATION);
			cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
			cipher.updateAAD(context);
			byte[] ciphertext = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
			return VERSION + "." + encode(nonce) + "." + encode(ciphertext);
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("푸시 토큰을 암호화할 수 없습니다.");
		}
	}

	String decrypt(UUID installationId, long userId, String ciphertext) {
		try {
			String[] parts = Objects.requireNonNull(ciphertext).split("\\.", -1);
			if (parts.length != 3 || !VERSION.equals(parts[0])) {
				throw new IllegalArgumentException();
			}
			byte[] nonce = Base64.getUrlDecoder().decode(parts[1]);
			byte[] payload = Base64.getUrlDecoder().decode(parts[2]);
			if (nonce.length != NONCE_BYTES || payload.length <= TAG_BITS / Byte.SIZE) {
				throw new IllegalArgumentException();
			}
			Cipher cipher = Cipher.getInstance(TRANSFORMATION);
			cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
			cipher.updateAAD(contextOf(installationId, userId));
			return new String(cipher.doFinal(payload), StandardCharsets.UTF_8);
		} catch (GeneralSecurityException | IllegalArgumentException | NullPointerException e) {
			throw new IllegalStateException("푸시 토큰을 복호화할 수 없습니다.");
		}
	}

	private static byte[] contextOf(UUID installationId, long userId) {
		Objects.requireNonNull(installationId);
		if (userId <= 0) {
			throw new IllegalArgumentException("회원 식별자가 올바르지 않습니다.");
		}
		return (VERSION + ":push-installations:" + installationId + ":" + userId)
			.getBytes(StandardCharsets.UTF_8);
	}

	private static String encode(byte[] bytes) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}
}
