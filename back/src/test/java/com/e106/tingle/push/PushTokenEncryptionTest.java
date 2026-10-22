package com.e106.tingle.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.push.dto.RegisterPushRequest;

class PushTokenEncryptionTest {

	@Test
	@DisplayName("키가 누락되면 등록 시 503 오류를 내며 평문 저장으로 대체하지 않는다")
	void missingKeyRejectsRegistration() {
		PushTokenEncryption encryption = new PushTokenEncryption("");

		assertThatThrownBy(() -> encryption.encrypt(UUID.randomUUID(), 1L, "synthetic-token"))
			.isInstanceOfSatisfying(BusinessException.class,
				exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
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
