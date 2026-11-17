package com.e106.tingle.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.e106.tingle.push.dto.RegisterPushRequest;

class PushTokenEncryptionTest {

	@Test
	@DisplayName("키가 없으면 시작 단계에서 오류를 내며 평문 저장으로 대체하지 않는다")
	void missingKeyPreventsStartup() {
		for (String key : new String[] {null, "", " "}) {
			assertThatThrownBy(() -> new PushTokenEncryption(key)).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("PUSH_TOKEN_ENCRYPTION_KEY");
		}
	}

	@Test
	@DisplayName("설정된 키가 잘못됐으면 시작 단계에서 오류를 낸다")
	void invalidKeyIsNotTreatedAsMissingConfiguration() {
		assertThatIllegalArgumentException().isThrownBy(() -> new PushTokenEncryption("invalid-key!"));
	}

	@Test
	@DisplayName("요청 객체의 진단용 문자열에 토큰을 포함하지 않는다")
	void requestStringRedactsToken() {
		RegisterPushRequest request = new RegisterPushRequest(UUID.randomUUID(), PushPlatform.ANDROID,
			"synthetic-secret-token");

		assertThat(request.toString()).contains("[REDACTED]").doesNotContain(request.token());
	}
}
