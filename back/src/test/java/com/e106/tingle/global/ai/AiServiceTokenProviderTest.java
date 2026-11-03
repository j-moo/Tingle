package com.e106.tingle.global.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;

import tools.jackson.databind.json.JsonMapper;

class AiServiceTokenProviderTest {

	private final JsonMapper json = JsonMapper.builder().build();
	private final Clock clock = Clock.fixed(Instant.parse("2026-10-29T03:30:00Z"), ZoneOffset.UTC);
	@TempDir
	Path temporary;

	@Test
	void signsOnlyTheServiceIdentityWithStringAudienceIntegerTimesAndANewUuid() throws Exception {
		KeyPair pair = pair(2048);
		AiServiceTokenProvider provider = provider(properties(keyFile(pair)));
		String token = provider.issue();
		String[] parts = token.split("\\.");
		assertThat(parts).hasSize(3);
		var header = json.readTree(Base64.getUrlDecoder().decode(parts[0]));
		assertThat(header.size()).isEqualTo(3);
		assertThat(header.path("alg").asText()).isEqualTo("RS256");
		assertThat(header.path("typ").asText()).isEqualTo("tingle-service+jwt");
		assertThat(header.path("kid").asText()).isEqualTo("synthetic-service-key");
		var claims = json.readTree(Base64.getUrlDecoder().decode(parts[1]));
		assertThat(claims.size()).isEqualTo(8);
		assertThat(claims.path("iss").asText()).isEqualTo("urn:tingle:be:test");
		assertThat(claims.path("aud").isString()).isTrue();
		assertThat(claims.path("aud").asText()).isEqualTo("urn:tingle:ai:test");
		assertThat(claims.path("sub").asText()).isEqualTo("tingle-be");
		assertThat(claims.path("scope").asText()).isEqualTo("ai:execute");
		for (String field : List.of("iat", "nbf", "exp")) {
			assertThat(claims.path(field).isIntegralNumber()).isTrue();
		}
		assertThat(claims.path("iat").asLong()).isEqualTo(clock.instant().getEpochSecond());
		assertThat(claims.path("nbf").asLong()).isEqualTo(claims.path("iat").asLong());
		assertThat(claims.path("exp").asLong() - claims.path("iat").asLong()).isEqualTo(300);
		assertThat(UUID.fromString(claims.path("jti").asText()).version()).isEqualTo(4);
		assertThat(provider.issue()).isNotEqualTo(token);
		Signature verifier = Signature.getInstance("SHA256withRSA");
		verifier.initVerify(pair.getPublic());
		verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
		assertThat(verifier.verify(Base64.getUrlDecoder().decode(parts[2]))).isTrue();
	}

	@Test
	void matchingTokenUsesOnlyTheEvaluationScopeAndRetainsTheSignedServiceIdentity() throws Exception {
		KeyPair pair = pair(2048);
		String token = provider(properties(keyFile(pair))).issueMatchingEvaluation();
		String[] parts = token.split("\\.");
		var claims = json.readTree(Base64.getUrlDecoder().decode(parts[1]));
		assertThat(claims.path("scope").asText()).isEqualTo("matching:evaluate");
		assertThat(claims.path("aud").asText()).isEqualTo("urn:tingle:ai:test");
		assertThat(claims.path("sub").asText()).isEqualTo("tingle-be");
		Signature verifier = Signature.getInstance("SHA256withRSA");
		verifier.initVerify(pair.getPublic());
		verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
		assertThat(verifier.verify(Base64.getUrlDecoder().decode(parts[2]))).isTrue();
	}

	@Test
	void missingOrInvalidEnabledConfigurationPreventsConstruction() throws Exception {
		String key = keyFile(pair(2048));
		List<AiServiceProperties> invalid = List.of(new AiServiceProperties(true, "other", "key", key, ""),
			new AiServiceProperties(true, "test", "bad/key", key, ""),
			new AiServiceProperties(true, "test", "x".repeat(65), key, ""),
			new AiServiceProperties(true, "test", "key", "", ""),
			new AiServiceProperties(true, "test", "key", temporary.resolve("absent").toString(), ""));
		for (AiServiceProperties properties : invalid) {
			assertConstructionFailure(properties);
		}
	}

	@Test
	void disabledConfigurationStillConstructsWithoutAnyValidKeyOrIdentitySettings() {
		assertUnavailable(provider(new AiServiceProperties(false, "other", "bad/key", "invalid\0path", "")));
	}

	@Test
	void rejectsMalformedWrongFormatAndSmallRsaKeys() throws Exception {
		Path broken = temporary.resolve("broken.pem");
		Files.writeString(broken, "synthetic invalid key material");
		assertConstructionFailure(properties(broken.toString()));
		Files.writeString(broken, "-----BEGIN RSA PRIVATE KEY-----\nAAAA\n-----END RSA PRIVATE KEY-----");
		assertConstructionFailure(properties(broken.toString()));
		assertConstructionFailure(properties(keyFile(pair(1024))));
	}

	@Test
	void loadsTheFileOnceAndRequiresARestartToChangeTheSigningKey() throws Exception {
		String file = keyFile(pair(2048));
		AiServiceTokenProvider provider = provider(properties(file));
		Files.writeString(Path.of(file), "synthetic replacement not loaded");
		assertThat(provider.issue()).isNotBlank();
		assertConstructionFailure(properties(file));
	}

	private AiServiceTokenProvider provider(AiServiceProperties properties) {
		return new AiServiceTokenProvider(properties, clock, json);
	}

	private AiServiceProperties properties(String file) {
		return new AiServiceProperties(true, "test", "synthetic-service-key", file, "https://ai.invalid");
	}

	private String keyFile(KeyPair pair) throws Exception {
		Path file = temporary.resolve(UUID.randomUUID() + ".pem");
		String encoded = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(pair.getPrivate().getEncoded());
		Files.writeString(file, "-----BEGIN PRIVATE KEY-----\n" + encoded + "\n-----END PRIVATE KEY-----\n");
		return file.toString();
	}

	private KeyPair pair(int bits) throws Exception {
		KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
		generator.initialize(bits);
		return generator.generateKeyPair();
	}

	private void assertUnavailable(AiServiceTokenProvider provider) {
		assertThatThrownBy(provider::requireAvailable).isInstanceOfSatisfying(BusinessException.class,
			exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
		assertThatThrownBy(provider::issue).isInstanceOfSatisfying(BusinessException.class,
			exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
	}

	private void assertConstructionFailure(AiServiceProperties properties) {
		assertThatThrownBy(() -> provider(properties)).isInstanceOf(IllegalStateException.class)
			.hasMessage("AI service signing configuration is unavailable").hasNoCause();
	}
}
