package com.e106.tingle.keyring.reset;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.e106.tingle.keyring.DevSettingsProperties;
import com.e106.tingle.keyring.DevSigningKeyLoader;

@Configuration(proxyBeanMethods = false)
public class ResetProtocolConfiguration {
	@Bean
	ResetDeviceProtocol resetDeviceProtocol(DevSettingsProperties properties) {
		return new SignedResetDeviceProtocol(DevSigningKeyLoader.load(properties));
	}
}
