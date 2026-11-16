package com.e106.tingle.keyring.reset;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.e106.tingle.keyring.DevSettingsProperties;

class ResetProtocolConfigurationTest {
	@Test
	void missingDedicatedKeysFailClosedAndDoNotExposePaths() {
		context("dev").run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).rootCause().hasMessageContaining(
				"KEYRING_DEV_SETTINGS_PRIVATE_KEY_FILE");
			assertThat(context.getStartupFailure().toString()).doesNotContain("synthetic-secret-path");
		});
	}

	@Test
	void productionResetSigningUsesConfiguredKeysAndFailsClosedWithoutThem() {
		// 11-11 결정 B: 운영도 같은 키 파일을 읽는다. 파일이 없으면 임의 키 없이 기동을 거절한다.
		context("prod").run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).rootCause().hasMessageContaining(
				"KEYRING_DEV_SETTINGS_PRIVATE_KEY_FILE");
		});
	}

	private ApplicationContextRunner context(String... profiles) {
		return new ApplicationContextRunner().withUserConfiguration(ResetProtocolConfiguration.class)
			.withBean(DevSettingsProperties.class, () -> new DevSettingsProperties(
				"synthetic-secret-path", "synthetic-public-path"))
			.withInitializer(context -> context.getEnvironment().setActiveProfiles(profiles));
	}
}
