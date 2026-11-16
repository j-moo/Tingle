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
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;

import com.e106.tingle.keyring.peer.PeerPolicyProperties;

class DevSettingsConfigurationTest {
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
			new DevSettingsProperties(secret.toString(), pub.toString()), peer(rawHex(keys)), environment,
			mock(JdbcTemplate.class));
		assertThat(issuer.publicKeyRawHex()).isEqualTo(rawHex(keys));
	}

	@Test
	void peerServerPublicKeyDifferentFromSigningKeyPreventsStartup(@TempDir Path dir) throws Exception {
		KeyPair keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
		Path secret = Files.writeString(dir.resolve("private.pem"),
			pem("PRIVATE KEY", keys.getPrivate().getEncoded()));
		Path pub = Files.writeString(dir.resolve("public.pem"),
			pem("PUBLIC KEY", keys.getPublic().getEncoded()));
		var environment = new StandardEnvironment();
		environment.setActiveProfiles("prod");
		assertThatThrownBy(() -> new DevSettingsConfiguration().devSettingsIssuer(
			new DevSettingsProperties(secret.toString(), pub.toString()), peer("00".repeat(32)), environment,
			mock(JdbcTemplate.class))).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("KEYRING_PEER_SERVER_PUBLIC_KEY_HEX");
	}

	@Test
	void productionWithoutConfiguredKeyFilesFailsClosedInsteadOfGeneratingOne() {
		var environment = new StandardEnvironment();
		environment.setActiveProfiles("prod");
		assertThatThrownBy(() -> new DevSettingsConfiguration().devSettingsIssuer(
			new DevSettingsProperties("", ""), peer("00".repeat(32)), environment, mock(JdbcTemplate.class)))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("KEYRING_DEV_SETTINGS_PRIVATE_KEY_FILE");
	}

	@Test
	void profilesOtherThanDevTestProdCannotLoadSigningKeys() {
		var environment = new StandardEnvironment();
		environment.setActiveProfiles("local");
		assertThatThrownBy(() -> new DevSettingsConfiguration().devSettingsIssuer(
			new DevSettingsProperties("secret-path", "public-path"), peer("00".repeat(32)), environment,
			mock(JdbcTemplate.class)))
			.isInstanceOf(IllegalStateException.class).hasMessageContaining("dev/test/prod profile");
	}

	@Test
	void missingDedicatedKeysFailClosedWithoutLeakingPaths() {
		var environment = new StandardEnvironment();
		environment.setActiveProfiles("dev");
		assertThatThrownBy(() -> new DevSettingsConfiguration().devSettingsIssuer(
			new DevSettingsProperties("synthetic-missing-key", "synthetic-missing-public"), peer("00".repeat(32)),
			environment, mock(JdbcTemplate.class))).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("KEYRING_DEV_SETTINGS_PRIVATE_KEY_FILE").hasNoCause();
	}

	private static PeerPolicyProperties peer(String serverPublicKeyHex) {
		return new PeerPolicyProperties(1380991793L, 268439809L, 86400L, 604800L, 30, serverPublicKeyHex);
	}

	private static String rawHex(KeyPair keys) {
		byte[] raw = keys.getPublic().getEncoded();
		return HexFormat.of().formatHex(Arrays.copyOfRange(raw, raw.length - 32, raw.length));
	}

	private static String pem(String label, byte[] der) {
		return "-----BEGIN " + label + "-----\n" + Base64.getMimeEncoder().encodeToString(der)
			+ "\n-----END " + label + "-----\n";
	}
}
