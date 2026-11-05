package com.e106.tingle.keyring;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.JdbcTemplate;

import com.e106.tingle.keyring.proof.ServerSettingsSigner;

@Configuration(proxyBeanMethods = false)
public class DevSettingsConfiguration {
	@Bean
	@ConditionalOnProperty(prefix = "app.keyring.dev-settings", name = "enabled", havingValue = "true")
	DevSettingsIssuer devSettingsIssuer(DevSettingsProperties properties, Environment environment,
		JdbcTemplate jdbc) {
		if (!environment.acceptsProfiles(Profiles.of("dev", "test"))
			|| environment.acceptsProfiles(Profiles.of("prod"))) {
			throw new IllegalStateException("Development settings signing requires dev/test profile");
		}
		try {
			var factory = KeyFactory.getInstance("Ed25519");
			var privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(
				pem(properties.privateKeyFile(), "PRIVATE KEY")));
			var publicKey = factory.generatePublic(new X509EncodedKeySpec(
				pem(properties.publicKeyFile(), "PUBLIC KEY")));
			return new DevSettingsIssuer(jdbc, new ServerSettingsSigner(privateKey, publicKey));
		} catch (Exception exception) {
			// 경로와 키 내용을 startup 예외에 포함하지 않는다. 임시 키로 대체하지 않는다.
			throw new IllegalStateException("Dedicated development settings signing keys unavailable");
		}
	}

	private byte[] pem(String file, String label) throws Exception {
		if (file == null || file.isBlank()) {
			throw new IllegalArgumentException("Missing signing key file");
		}
		String text = Files.readString(Path.of(file));
		String begin = "-----BEGIN " + label + "-----";
		String end = "-----END " + label + "-----";
		if (!text.strip().startsWith(begin) || !text.strip().endsWith(end)) {
			throw new IllegalArgumentException("Expected PEM key");
		}
		return Base64.getDecoder().decode(text.replace(begin, "").replace(end, "").replaceAll("\\s", ""));
	}
}
