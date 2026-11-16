package com.e106.tingle.withdrawal;

import java.nio.charset.StandardCharsets;

/** 탈퇴 HMAC 비밀값은 필수다. 비었거나 32바이트보다 짧으면 기동하지 않는다(개발·운영 같음). */
final class WithdrawalSecrets {
	private WithdrawalSecrets() {
	}

	static byte[] require(String value, String name) {
		byte[] bytes = value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
		if (bytes.length < 32) {
			throw new IllegalStateException(name + " 값이 없거나 32바이트보다 짧습니다");
		}
		return bytes;
	}
}
