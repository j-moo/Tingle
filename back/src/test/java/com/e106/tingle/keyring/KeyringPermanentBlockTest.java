package com.e106.tingle.keyring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class KeyringPermanentBlockTest {
	private static final Instant BLOCKED = Instant.parse("2026-11-06T07:00:00Z");

	@Test
	void restoredCredentialAndUnboundStateCannotRegisterBlockedDevice() {
		Keyring device = new Keyring("lost-test", "test-firmware");
		ReflectionTestUtils.setField(device, "permanentlyBlockedAt", BLOCKED);
		ReflectionTestUtils.setField(device, "credentialRevokedAt", null);
		ReflectionTestUtils.setField(device, "credentialKeyId", "reinstated-key");
		ReflectionTestUtils.setField(device, "credentialPublicKey", "reinstated-public-key");
		assertThatThrownBy(() -> device.register(42, UUID.randomUUID())).isInstanceOf(IllegalStateException.class)
			.hasMessage("Device is permanently blocked");
		assertThat(device.getState()).isEqualTo(KeyringState.UNBOUND);
		assertThat(device.getOwnerUserId()).isNull();
		assertThat(device.getPermanentlyBlockedAt()).isEqualTo(BLOCKED);
	}

	@Test
	void inconsistentRegisteredOwnerCannotResetAcknowledgeOrConfirmEpoch() {
		Keyring device = new Keyring("lost-registered-test", "test-firmware");
		UUID generation = UUID.randomUUID();
		device.register(42, generation);
		ReflectionTestUtils.setField(device, "permanentlyBlockedAt", BLOCKED);
		assertThatThrownBy(() -> device.releaseAfterVerifiedReset(generation))
			.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> device.acknowledgeSettings(1, BLOCKED.plusSeconds(1)))
			.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> device.confirmOwnershipEpoch(0)).isInstanceOf(IllegalStateException.class);
		assertThat(device.getOwnerUserId()).isEqualTo(42);
		assertThat(device.getOwnerGeneration()).isEqualTo(generation);
		assertThat(device.getSyncedSettingsVersion()).isNull();
		assertThat(device.getPermanentlyBlockedAt()).isEqualTo(BLOCKED);
	}
}
