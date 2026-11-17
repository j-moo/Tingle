package com.e106.tingle.push;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;

/**
 * 푸시 토큰 암호화. 키(PUSH_TOKEN_ENCRYPTION_KEY)가 없으면 기동하지 않는다(개발·운영 같음).
 */
@Component
class PushTokenEncryption {

	private final PushTokenCipher cipher;

	PushTokenEncryption(@Value("${app.push.token-encryption-key:}") String encodedKey) {
		if (encodedKey == null || encodedKey.isBlank()) {
			throw new IllegalStateException("PUSH_TOKEN_ENCRYPTION_KEY 값이 필요합니다 (Base64 32바이트)");
		}
		cipher = new PushTokenCipher(encodedKey);
	}

	String encrypt(UUID installationId, long userId, String token) {
		return cipher.encrypt(installationId, userId, token);
	}

	String decrypt(UUID installationId, long userId, String ciphertext) {
		try {
			return cipher.decrypt(installationId, userId, ciphertext);
		} catch (IllegalStateException e) {
			throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
		}
	}
}
