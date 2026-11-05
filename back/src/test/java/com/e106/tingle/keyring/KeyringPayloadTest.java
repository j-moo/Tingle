package com.e106.tingle.keyring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;

class KeyringPayloadTest {

	@Test
	void productionFallbackFailsClosedWithoutInventingDeviceBytes() {
		KeyringPayloadProvider provider = new KeyringPayloadConfiguration().unavailableDevicePayload();
		assertThatThrownBy(() -> provider.prepare(1, 2, UUID.randomUUID()))
			.isInstanceOfSatisfying(BusinessException.class, exception -> {
				assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.POLICY_UNRESOLVED);
				assertThat(exception.getErrorCode().getStatus().value()).isEqualTo(422);
			});
	}

	@Test
	void copiesIncomingAndOutgoingBytesAndNeverPrintsPayloadBody() {
		byte[] input = "synthetic-private-payload".getBytes(StandardCharsets.UTF_8);
		KeyringPayload payload = new KeyringPayload(1, 2, UUID.randomUUID(), 3, "test-format-v1", input);
		input[0] = 'X';
		assertThat(new String(payload.bytes(), StandardCharsets.UTF_8)).isEqualTo("synthetic-private-payload");
		byte[] exported = payload.bytes();
		exported[1] = 'Y';
		assertThat(new String(payload.bytes(), StandardCharsets.UTF_8)).isEqualTo("synthetic-private-payload");
		assertThat(payload.bytes()).isNotSameAs(exported);
		assertThat(payload.toString()).contains("test-format-v1", "REDACTED")
			.doesNotContain("synthetic-private-payload");
	}

	@Test
	void rejectsZeroVersionAndMissingOrEmptyBytes() {
		UUID generation = UUID.randomUUID();
		assertThatThrownBy(() -> new KeyringPayload(1, 2, generation, 0, "test", new byte[] {1}))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new KeyringPayload(1, 2, generation, 1, "test", new byte[0]))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new KeyringPayload(1, 2, generation, 1, "test", null))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rejectsMissingInvalidAndOverlongFormat() {
		UUID generation = UUID.randomUUID();
		for (String format : new String[] {null, "", "has space", "한글", "x".repeat(65)}) {
			assertThatThrownBy(() -> new KeyringPayload(1, 2, generation, 1, format, new byte[] {1}))
				.isInstanceOf(IllegalArgumentException.class);
		}
	}

	@Test
	void rejectsMissingOwnerGenerationAndNonpositiveOwnerOrDevice() {
		UUID generation = UUID.randomUUID();
		assertThatThrownBy(() -> new KeyringPayload(0, 2, generation, 1, "test", new byte[] {1}))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new KeyringPayload(1, 0, generation, 1, "test", new byte[] {1}))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new KeyringPayload(1, 2, null, 1, "test", new byte[] {1}))
			.isInstanceOf(IllegalArgumentException.class);
	}
}
