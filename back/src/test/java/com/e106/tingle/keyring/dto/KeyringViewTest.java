package com.e106.tingle.keyring.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.e106.tingle.keyring.KeyringStatus;

class KeyringViewTest {

	@Test
	void toStringHidesDeviceSerial() {
		KeyringView view = new KeyringView(7, "SERIAL-ABC-123", KeyringStatus.READY, "fw-1");
		assertThat(view.toString()).contains("deviceId=7", "REDACTED", "READY", "fw-1")
			.doesNotContain("SERIAL-ABC-123");
	}
}
