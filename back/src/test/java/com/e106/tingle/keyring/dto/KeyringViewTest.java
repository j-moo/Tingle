package com.e106.tingle.keyring.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.e106.tingle.keyring.KeyringStatus;

class KeyringViewTest {

	@Test
	void toStringHidesDeviceSerialAndLabel() {
		KeyringView view = new KeyringView(7, "SERIAL-ABC-123", KeyringStatus.READY, "fw-1", "SERIAL-ABC");
		assertThat(view.toString()).contains("deviceId=7", "REDACTED", "READY", "fw-1")
			.doesNotContain("SERIAL-ABC");
	}

	@Test
	void defaultLabelIsFirstTenCodePointsOfSerial() {
		assertThat(KeyringView.displayLabel(null, "XIAO-7ce081e55efaee51")).isEqualTo("XIAO-7ce08");
		assertThat(KeyringView.displayLabel(null, "SHORT")).isEqualTo("SHORT");
		assertThat(KeyringView.displayLabel("내 키링", "XIAO-7ce081e55efaee51")).isEqualTo("내 키링");
	}
}
