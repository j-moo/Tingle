package com.e106.tingle.keyring;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;

@Configuration(proxyBeanMethods = false)
public class KeyringPayloadConfiguration {
	@Bean
	@ConditionalOnMissingBean(KeyringPayloadProvider.class)
	KeyringPayloadProvider unavailableDevicePayload() {
		return (userId, deviceId, generation) -> {
			throw new BusinessException(ErrorCode.POLICY_UNRESOLVED);
		};
	}
}
