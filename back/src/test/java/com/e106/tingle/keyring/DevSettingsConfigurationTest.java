package com.e106.tingle.keyring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;

class DevSettingsConfigurationTest {
	@Test
	void defaultDisabledDoesNotLoadKeysOrCreateIssuer() {
		new ApplicationContextRunner().withUserConfiguration(DevSettingsConfiguration.class).run(context -> {
			assertThat(context).hasNotFailed().doesNotHaveBean(DevSettingsIssuer.class);
		});
	}

	@Test
	void developmentFlagCannotEnableProductionEvenAlongsideDevProfile() {
		var environment = new StandardEnvironment();
		environment.setActiveProfiles("dev", "prod");
		assertThatThrownBy(() -> new DevSettingsConfiguration().devSettingsIssuer(
			new DevSettingsProperties(true, "secret-path", "public-path"), environment, mock(JdbcTemplate.class)))
			.isInstanceOf(IllegalStateException.class).hasMessageContaining("dev/test profile");
	}

	@Test
	void enabledWithoutDedicatedKeysFailsClosedWithoutLeakingPaths() {
		var environment = new StandardEnvironment();
		environment.setActiveProfiles("dev");
		assertThatThrownBy(() -> new DevSettingsConfiguration().devSettingsIssuer(
			new DevSettingsProperties(true, "synthetic-missing-key", "synthetic-missing-public"),
			environment, mock(JdbcTemplate.class))).isInstanceOf(IllegalStateException.class)
			.hasMessage("Dedicated development settings signing keys unavailable").hasNoCause();
	}
}
