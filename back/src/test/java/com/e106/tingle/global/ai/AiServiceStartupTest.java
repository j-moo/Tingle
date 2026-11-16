package com.e106.tingle.global.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import com.e106.tingle.global.config.ServerSettingsCheck;
import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.icebreaker.IcebreakerProperties;

import tools.jackson.databind.json.JsonMapper;

/** 실제 서비스 설정 바인딩과 bean 생성 실패를 검증한다. DB·HTTP·운영 키는 사용하지 않는다. */
class AiServiceStartupTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withUserConfiguration(SigningConfiguration.class);
	@TempDir
	Path temporary;

	@Test
	void serverMissingKeyConfigurationOrMissingFilePreventsContextStartup() {
		assertStartupFailure(enabled(""));
		assertStartupFailure(enabled(temporary.resolve("absent-private-key.pem").toString()));
	}

	@Test
	void serverCorruptKeyPreventsContextStartupWithoutExposingTheKeyMaterial() throws IOException {
		Path file = temporary.resolve("corrupt-private-key.pem");
		Files.writeString(file, "synthetic-corrupt-private-key-material");
		assertStartupFailure(enabled(file.toString()));
	}

	@Test
	void serverWeakRsaKeyPreventsContextStartup() throws Exception {
		assertStartupFailure(enabled(keyFile(1024).toString()));
	}

	@Test
	void serverInvalidIssuerEnvironmentOrKeyIdPreventsContextStartup() throws Exception {
		ApplicationContextRunner configured = enabled(keyFile(2048).toString());
		assertStartupFailure(configured.withPropertyValues("app.ai.service.environment=production"));
		assertStartupFailure(configured.withPropertyValues("app.ai.service.key-id="));
		assertStartupFailure(configured.withPropertyValues("app.ai.service.key-id=invalid/key"));
	}

	@Test
	void serverMissingBaseUrlEnvironmentOrIcebreakerManifestPreventsContextStartup() throws Exception {
		ApplicationContextRunner configured = enabled(keyFile(2048).toString());
		for (String blank : new String[] {"app.ai.service.base-url=", "app.ai.service.environment=",
			"app.ai.service.environment=local", "app.icebreaker.manifest-location=",
			"app.icebreaker.manifest-location=classpath:absent.json"}) {
			assertStartupFailure(configured.withPropertyValues(blank));
		}
	}

	@Test
	void serverValidSyntheticServiceKeyStartsAndIssuesTokens() throws Exception {
		enabled(keyFile(2048).toString()).run(context -> {
			assertThat(context).hasNotFailed().hasSingleBean(AiServiceTokenProvider.class);
			AiServiceTokenProvider provider = context.getBean(AiServiceTokenProvider.class);
			provider.requireAvailable();
			assertThat(provider.issue()).isNotBlank();
		});
	}

	@Test
	void localConfigurationStartsEvenWithAnUnusableKeyPath() {
		runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("local"))
			.withPropertyValues("app.ai.service.private-key-file=" + temporary.resolve("never-read.pem"))
			.run(context -> {
				assertThat(context).hasNotFailed().hasSingleBean(AiServiceTokenProvider.class);
				assertThatThrownBy(() -> context.getBean(AiServiceTokenProvider.class).requireAvailable())
					.isInstanceOfSatisfying(BusinessException.class,
						error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
			});
	}

	private ApplicationContextRunner enabled(String file) {
		// 개발·운영(dev·prod)은 키가 없으면 기동하지 않는다. local·test 만 키 없이 뜬다.
		return runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("dev"))
			.withPropertyValues("app.ai.service.environment=dev", "app.ai.service.base-url=http://ai-dev:8000",
				"app.icebreaker.manifest-location=classpath:icebreakers/general-ko-v1.json",
				"app.ai.service.key-id=synthetic-key", "app.ai.service.private-key-file=" + file);
	}

	private void assertStartupFailure(ApplicationContextRunner configured) {
		configured.run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalStateException.class)
				.rootCause().hasMessageStartingWith("AI service signing configuration is unavailable");
		});
	}

	private Path keyFile(int bits) throws Exception {
		KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
		generator.initialize(bits);
		byte[] key = generator.generateKeyPair().getPrivate().getEncoded();
		String encoded = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(key);
		Path file = temporary.resolve("synthetic-" + bits + ".pem");
		Files.writeString(file, "-----BEGIN PRIVATE KEY-----\n" + encoded + "\n-----END PRIVATE KEY-----\n");
		return file;
	}

	@TestConfiguration(proxyBeanMethods = false)
	@EnableConfigurationProperties({AiServiceProperties.class, IcebreakerProperties.class})
	@Import({AiServiceTokenProvider.class, ServerSettingsCheck.class})
	static class SigningConfiguration {

		@Bean
		Clock clock() {
			return Clock.systemUTC();
		}

		@Bean
		JsonMapper jsonMapper() {
			return JsonMapper.builder().build();
		}
	}
}
