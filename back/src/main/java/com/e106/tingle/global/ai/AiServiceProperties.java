package com.e106.tingle.global.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 사용자 로그인 키와 분리한 AI 서비스 전용 설정. 기본값은 호출 금지다. */
@ConfigurationProperties("app.ai.service")
public record AiServiceProperties(@DefaultValue("false") boolean enabled,
	@DefaultValue("local") String environment, @DefaultValue("") String keyId,
	@DefaultValue("") String privateKeyFile, @DefaultValue("") String baseUrl) {

	public AiServiceProperties {
		environment = environment == null ? "local" : environment;
		keyId = keyId == null ? "" : keyId;
		privateKeyFile = privateKeyFile == null ? "" : privateKeyFile;
		baseUrl = baseUrl == null ? "" : baseUrl;
	}
}
