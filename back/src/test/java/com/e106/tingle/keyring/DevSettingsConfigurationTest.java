package com.e106.tingle.keyring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;

class DevSettingsConfigurationTest {
	@Test
	void defaultDisabledDoesNotLoadKeysOrCreateIssuer() {
		new ApplicationContextRunner().withUserConfiguration(DevSettingsConfiguration.class).run(context -> {
			assertThat(context).hasNotFailed().doesNotHaveBean(DevSettingsIssuer.class);
		});
	}

	@Test
	void productionLoadsTheSharedSigningKeyFromConfiguredFiles(@TempDir Path dir) throws Exception {
		// 11-11 결정 B: 운영도 펌웨어에 고정된 개발 서명 키 쌍을 그대로 읽는다.
		KeyPair keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
		Path secret = Files.writeString(dir.resolve("private.pem"),
			pem("PRIVATE KEY", keys.getPrivate().getEncoded()));
		Path pub = Files.writeString(dir.resolve("public.pem"),
			pem("PUBLIC KEY", keys.getPublic().getEncoded()));
		var environment = new StandardEnvironment();
		environment.setActiveProfiles("prod");
		DevSettingsIssuer issuer = new DevSettingsConfiguration().devSettingsIssuer(
			new DevSettingsProperties(true, secret.toString(), pub.toString()), environment, mock(JdbcTemplate.class));
		byte[] raw = keys.getPublic().getEncoded();
		assertThat(issuer.publicKeyRawHex())
			.isEqualTo(HexFormat.of().formatHex(Arrays.copyOfRange(raw, raw.length - 32, raw.length)));
	}

	@Test
	void productionWithoutConfiguredKeyFilesFailsClosedInsteadOfGeneratingOne() {
		var environment = new StandardEnvironment();
		environment.setActiveProfiles("prod");
		assertThatThrownBy(() -> new DevSettingsConfiguration().devSettingsIssuer(
			new DevSettingsProperties(true, "", ""), environment, mock(JdbcTemplate.class)))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("Dedicated development settings signing keys unavailable");
	}

	@Test
	void profilesOtherThanDevTestProdCannotLoadSigningKeys() {
		var environment = new StandardEnvironment();
		environment.setActiveProfiles("local");
		assertThatThrownBy(() -> new DevSettingsConfiguration().devSettingsIssuer(
			new DevSettingsProperties(true, "secret-path", "public-path"), environment, mock(JdbcTemplate.class)))
			.isInstanceOf(IllegalStateException.class).hasMessageContaining("dev/test/prod profile");
	}

	@Test
	void enabledWithoutDedicatedKeysFailsClosedWithoutLeakingPaths() {
		var environment = new StandardEnvironment();
		environment.setActiveProfiles("dev");
		assertThatThrownBy(() -> new DevSettingsConfiguration().devSettingsIssuer(
			new DevSettingsProperties(true, "synthetic-missing-key", "synthetic-missing-public"),
			environment, mock(JdbcTemplate.class))).isInstanceOf(IllegalStateException.class)
			.hasMessage("Dedicated development settings signing keys unavailable").hasNoCause();
	}

	private static String pem(String label, byte[] der) {
		return "-----BEGIN " + label + "-----\n" + Base64.getMimeEncoder().encodeToString(der)
			+ "\n-----END " + label + "-----\n";
	}
}
