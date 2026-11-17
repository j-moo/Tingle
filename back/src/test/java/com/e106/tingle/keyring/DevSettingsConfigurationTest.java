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
		DevSettingsIssuer issuer = new DevSettingsConfiguration().devSettingsIssuer(
			new DevSettingsProperties(secret.toString(), pub.toString()), peer(rawHex(keys)),
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
		assertThatThrownBy(() -> new DevSettingsConfiguration().devSettingsIssuer(
			new DevSettingsProperties(secret.toString(), pub.toString()), peer("00".repeat(32)),
			mock(JdbcTemplate.class))).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("KEYRING_PEER_SERVER_PUBLIC_KEY_HEX");
	}

	@Test
	void productionWithoutConfiguredKeyFilesFailsClosedInsteadOfGeneratingOne() {
		assertThatThrownBy(() -> new DevSettingsConfiguration().devSettingsIssuer(
			new DevSettingsProperties("", ""), peer("00".repeat(32)), mock(JdbcTemplate.class)))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("KEYRING_DEV_SETTINGS_PRIVATE_KEY_FILE");
	}

	@Test
	void localProfileLoadsTheRepositoryTestOnlyKey() {
		// local 은 application-local.yml 의 기본값으로 저장소의 테스트 전용 키를 읽어 그대로 뜬다.
		DevSettingsIssuer issuer = new DevSettingsConfiguration().devSettingsIssuer(new DevSettingsProperties(
			"src/test/resources/keyring/test-only-settings-private.pem",
			"src/test/resources/keyring/test-only-settings-public.pem"),
			peer("03a107bff3ce10be1d70dd18e74bc09967e4d6309ba50d5f1ddc8664125531b8"), mock(JdbcTemplate.class));
		assertThat(issuer.publicKeyRawHex())
			.isEqualTo("03a107bff3ce10be1d70dd18e74bc09967e4d6309ba50d5f1ddc8664125531b8");
	}

	@Test
	void missingDedicatedKeysFailClosedWithoutLeakingPaths() {
		assertThatThrownBy(() -> new DevSettingsConfiguration().devSettingsIssuer(
			new DevSettingsProperties("synthetic-missing-key", "synthetic-missing-public"), peer("00".repeat(32)),
			mock(JdbcTemplate.class))).isInstanceOf(IllegalStateException.class)
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
