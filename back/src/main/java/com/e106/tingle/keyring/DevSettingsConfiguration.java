package com.e106.tingle.keyring;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
public class DevSettingsConfiguration {
	@Bean
	@ConditionalOnProperty(prefix = "app.keyring.dev-settings", name = "enabled", havingValue = "true")
	DevSettingsIssuer devSettingsIssuer(DevSettingsProperties properties, Environment environment,
		JdbcTemplate jdbc) {
		return new DevSettingsIssuer(jdbc, DevSigningKeyLoader.load(properties, environment));
	}
}
