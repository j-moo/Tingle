package com.e106.tingle.keyring.reset;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.e106.tingle.keyring.DevSettingsProperties;

class ResetProtocolConfigurationTest {
	@Test
	void defaultOffAndResetFlagAloneNeverLoadKeysOrRegisterAPhysicalProtocol() {
		new ApplicationContextRunner().withUserConfiguration(ResetProtocolConfiguration.class)
			.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(ResetDeviceProtocol.class));
		new ApplicationContextRunner().withUserConfiguration(ResetProtocolConfiguration.class)
			.withPropertyValues("app.keyring-reset.enabled=true")
			.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(ResetDeviceProtocol.class));
	}

	@Test
	void bothFlagsWithoutExistingDedicatedKeysFailClosedAndDoNotExposePaths() {
		context("dev").run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).hasRootCauseMessage(
				"Dedicated development settings signing keys unavailable");
			assertThat(context.getStartupFailure().toString()).doesNotContain("synthetic-secret-path");
		});
	}

	@Test
	void developmentResetSigningCannotBeEnabledInAProductionProfile() {
		context("dev", "prod").run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).hasRootCauseMessage(
				"Development settings signing requires dev/test profile");
		});
	}

	private ApplicationContextRunner context(String... profiles) {
		return new ApplicationContextRunner().withUserConfiguration(ResetProtocolConfiguration.class)
			.withBean(DevSettingsProperties.class, () -> new DevSettingsProperties(true,
				"synthetic-secret-path", "synthetic-public-path"))
			.withPropertyValues("app.keyring-reset.enabled=true", "app.keyring.dev-settings.enabled=true")
			.withInitializer(context -> context.getEnvironment().setActiveProfiles(profiles));
	}
}
