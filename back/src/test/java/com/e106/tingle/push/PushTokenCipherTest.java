package com.e106.tingle.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PushTokenCipherTest {

	private final UUID installationId = UUID.randomUUID();
	private final PushTokenCipher cipher = new PushTokenCipher(keyWith((byte)7));

	@Test
	@DisplayName("같은 토큰도 매번 다른 암호문으로 저장하며 올바른 소유 문맥에서만 원문을 복원한다")
	void encryptsWithFreshNonceAndRoundTrips() {
		String token = "synthetic-push-token-한글";
		String first = cipher.encrypt(installationId, 1L, token);
		String second = cipher.encrypt(installationId, 1L, token);

		assertThat(first).startsWith("v1.").doesNotContain(token).isNotEqualTo(second);
		assertThat(cipher.decrypt(installationId, 1L, first)).isEqualTo(token);
		assertThat(cipher.decrypt(installationId, 1L, second)).isEqualTo(token);
	}

	@Test
	@DisplayName("다른 회원이나 설치로 암호문을 복사하면 복호화를 거부한다")
	void rejectsDifferentOwnerOrInstallation() {
		String encrypted = cipher.encrypt(installationId, 1L, "synthetic-token");

		assertThatIllegalStateException().isThrownBy(() -> cipher.decrypt(installationId, 2L, encrypted));
		assertThatIllegalStateException().isThrownBy(() -> cipher.decrypt(UUID.randomUUID(), 1L, encrypted));
	}

	@Test
	@DisplayName("저장된 암호문 변조나 잘못된 키에 원문을 반환하지 않는다")
	void rejectsTamperingAndWrongKey() {
		String encrypted = cipher.encrypt(installationId, 1L, "synthetic-token");
		String[] parts = encrypted.split("\\.");
		byte[] payload = Base64.getUrlDecoder().decode(parts[2]);
		payload[0] ^= 1;
		String tampered = parts[0] + "." + parts[1] + "."
			+ Base64.getUrlEncoder().withoutPadding().encodeToString(payload);

		assertThatIllegalStateException().isThrownBy(() -> cipher.decrypt(installationId, 1L, tampered));
		PushTokenCipher otherCipher = new PushTokenCipher(keyWith((byte)8));
		assertThatIllegalStateException().isThrownBy(() -> otherCipher.decrypt(installationId, 1L, encrypted));
	}

	@Test
	@DisplayName("손상된 저장 형식과 지원하지 않는 버전을 거부하고 예외에 토큰을 노출하지 않는다")
	void rejectsMalformedStorageWithoutLeakingToken() {
		for (String invalid : Arrays.asList(null, "", "raw-secret-token", "v2.AA.AA", "v1.!.!", "v1.AA.AA")) {
			assertThatIllegalStateException().isThrownBy(() -> cipher.decrypt(installationId, 1L, invalid))
				.withMessage("푸시 토큰을 복호화할 수 없습니다.").withNoCause();
		}
	}

	@Test
	@DisplayName("누락되거나 잘못된 길이의 키를 기본 키로 대체하지 않는다")
	void rejectsMissingOrInvalidKey() {
		for (String invalid : Arrays.asList(null, "", "not-base64!", keyWithLength(16), keyWithLength(33))) {
			assertThatIllegalArgumentException().isThrownBy(() -> new PushTokenCipher(invalid))
				.withMessage("푸시 토큰 암호화 키는 Base64 형식의 32바이트여야 합니다.").withNoCause();
		}
	}

	@Test
	@DisplayName("빈 토큰이나 유효하지 않은 소유 문맥을 암호화하지 않는다")
	void rejectsInvalidTokenAndContext() {
		for (String token : Arrays.asList(null, "", " \t\n")) {
			assertThatIllegalArgumentException().isThrownBy(() -> cipher.encrypt(installationId, 1L, token));
		}
		assertThatIllegalArgumentException().isThrownBy(() -> cipher.encrypt(installationId, 0L, "synthetic-token"));
	}

	private static String keyWith(byte value) {
		byte[] key = new byte[32];
		Arrays.fill(key, value);
		return Base64.getEncoder().encodeToString(key);
	}

	private static String keyWithLength(int length) {
		return Base64.getEncoder().encodeToString(new byte[length]);
	}
}
