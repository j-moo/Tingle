package com.e106.tingle.push;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;

/**
 * 키가 없으면 등록을 거부한다. 기존 인증 서버와 본인 설치 해제는 키 없이도 동작한다.
 */
@Component
class PushTokenEncryption {

	private final PushTokenCipher cipher;

	PushTokenEncryption(@Value("${app.push.token-encryption-key:}") String encodedKey) {
		cipher = encodedKey == null || encodedKey.isBlank() ? null : new PushTokenCipher(encodedKey);
	}

	String encrypt(UUID installationId, long userId, String token) {
		return configuredCipher().encrypt(installationId, userId, token);
	}

	String decrypt(UUID installationId, long userId, String ciphertext) {
		try {
			return configuredCipher().decrypt(installationId, userId, ciphertext);
		} catch (IllegalStateException e) {
			throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
		}
	}

	private PushTokenCipher configuredCipher() {
		if (cipher == null) {
			throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
		}
		return cipher;
	}
}
