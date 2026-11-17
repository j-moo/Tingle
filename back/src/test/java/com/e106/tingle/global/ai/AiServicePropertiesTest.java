package com.e106.tingle.global.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MapPropertySource;

class AiServicePropertiesTest {

	@Test
	void defaultsToEmptyWithoutReadingTheMobileLoginConfiguration() {
		Binder binder = new Binder(ConfigurationPropertySources.from(new MapPropertySource("synthetic", Map.of(
			"app.auth.secret", "synthetic-mobile-secret", "GMS_API_KEY", "synthetic-provider-key"))));
		AiServiceProperties properties = binder.bindOrCreate("app.ai.service", Bindable.of(AiServiceProperties.class));
		assertThat(properties).isEqualTo(new AiServiceProperties("", "", "", ""));
	}

	@Test
	void bindsOnlyTheExplicitServiceNamespace() {
		Binder binder = new Binder(ConfigurationPropertySources.from(new MapPropertySource("synthetic", Map.of(
			"app.ai.service.environment", "test",
			"app.ai.service.key-id", "synthetic", "app.ai.service.private-key-file", "synthetic.pem",
			"app.ai.service.base-url", "http://127.0.0.1:8000"))));
		assertThat(binder.bindOrCreate("app.ai.service", Bindable.of(AiServiceProperties.class)))
			.isEqualTo(new AiServiceProperties("test", "synthetic", "synthetic.pem", "http://127.0.0.1:8000"));
	}
}
