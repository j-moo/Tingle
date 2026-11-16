package com.e106.tingle.global.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 사용자 로그인 키와 분리한 AI 서비스 전용 설정. 키·주소가 없으면 호출하지 않는다(local·test 만 허용). */
@ConfigurationProperties("app.ai.service")
public record AiServiceProperties(@DefaultValue("local") String environment, @DefaultValue("") String keyId,
	@DefaultValue("") String privateKeyFile, @DefaultValue("") String baseUrl) {

	public AiServiceProperties {
		environment = environment == null ? "local" : environment;
		keyId = keyId == null ? "" : keyId;
		privateKeyFile = privateKeyFile == null ? "" : privateKeyFile;
		baseUrl = baseUrl == null ? "" : baseUrl;
	}
}
