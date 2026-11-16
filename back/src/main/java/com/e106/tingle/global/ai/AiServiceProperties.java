package com.e106.tingle.global.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 사용자 로그인 키와 분리한 AI 서비스 전용 설정. 개발·운영은 모두 필수다(ServerSettingsCheck). */
@ConfigurationProperties("app.ai.service")
public record AiServiceProperties(@DefaultValue("") String environment, @DefaultValue("") String keyId,
	@DefaultValue("") String privateKeyFile, @DefaultValue("") String baseUrl) {

	public AiServiceProperties {
		environment = environment == null ? "" : environment;
		keyId = keyId == null ? "" : keyId;
		privateKeyFile = privateKeyFile == null ? "" : privateKeyFile;
		baseUrl = baseUrl == null ? "" : baseUrl;
	}
}
