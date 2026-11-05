package com.e106.tingle.keyring.reset;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import com.e106.tingle.keyring.DevSettingsProperties;
import com.e106.tingle.keyring.DevSigningKeyLoader;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = {"app.keyring-reset.enabled", "app.keyring.dev-settings.enabled"}, havingValue = "true")
public class ResetProtocolConfiguration {
	@Bean
	ResetDeviceProtocol resetDeviceProtocol(DevSettingsProperties properties, Environment environment) {
		return new SignedResetDeviceProtocol(DevSigningKeyLoader.load(properties, environment));
	}
}
