package com.e106.tingle;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class RuntimeConfigurationTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withPropertyValues("spring.config.location=classpath:application.yml")
		.withInitializer(new ConfigDataApplicationContextInitializer());

	@Test
	void commonConfigurationDoesNotChangeSchemaOrStartCompose() {
		contextRunner.withPropertyValues("spring.profiles.active=configuration-review").run(context -> {
			assertThat(context.getEnvironment().getProperty("spring.jpa.hibernate.ddl-auto"))
				.isEqualTo("validate");
			assertThat(context.getEnvironment().getProperty("spring.docker.compose.enabled"))
				.isEqualTo("false");
			assertThat(context.getEnvironment().getProperty("spring.config.import"))
				.isEqualTo("optional:file:.env[.properties]");
		});
	}

	@Test
	void localConfigurationUsesUpdate() {
		contextRunner.withPropertyValues("spring.profiles.active=local").run(context -> {
			assertThat(context.getEnvironment().getProperty("spring.jpa.hibernate.ddl-auto"))
				.isEqualTo("update");
			assertThat(context.getEnvironment().getProperty("spring.docker.compose.enabled"))
				.isEqualTo("true");
		});
	}

	@Test
	void testConfigurationUsesCreateDropWithoutCompose() {
		contextRunner.withPropertyValues("spring.profiles.active=test").run(context -> {
			assertThat(context.getEnvironment().getProperty("spring.jpa.hibernate.ddl-auto"))
				.isEqualTo("create-drop");
			assertThat(context.getEnvironment().getProperty("spring.docker.compose.enabled"))
				.isEqualTo("false");
		});
	}
}
