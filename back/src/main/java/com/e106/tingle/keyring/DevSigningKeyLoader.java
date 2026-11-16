package com.e106.tingle.keyring;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import com.e106.tingle.keyring.proof.ServerSettingsSigner;

/**
 * Shared loader for the already provisioned development signing pair. Never generates fallback keys.
 * 운영도 이 키를 쓴다(11-11 결정 B, 펌웨어 고정 공개키와 같은 쌍). 실서비스는 운영 전용 키와 펌웨어 재빌드가 필요하다.
 */
public final class DevSigningKeyLoader {
	private DevSigningKeyLoader() {
	}

	public static ServerSettingsSigner load(DevSettingsProperties properties, Environment environment) {
		if (!environment.acceptsProfiles(Profiles.of("dev", "test", "prod"))) {
			throw new IllegalStateException("Settings signing requires dev/test/prod profile");
		}
		try {
			var factory = KeyFactory.getInstance("Ed25519");
			return new ServerSettingsSigner(factory.generatePrivate(new PKCS8EncodedKeySpec(
				pem(properties.privateKeyFile(), "PRIVATE KEY"))), factory.generatePublic(new X509EncodedKeySpec(
				pem(properties.publicKeyFile(), "PUBLIC KEY"))));
		} catch (Exception exception) {
			// 키 파일이 없으면 기동하지 않는다(개발·운영 같음). 키 내용은 메시지에 넣지 않는다.
			throw new IllegalStateException("키링 설정 서명 키를 읽지 못했습니다. KEYRING_DEV_SETTINGS_PRIVATE_KEY_FILE, "
				+ "KEYRING_DEV_SETTINGS_PUBLIC_KEY_FILE 에 Ed25519 PEM 파일 경로가 필요합니다");
		}
	}

	private static byte[] pem(String file, String label) throws Exception {
		if (file == null || file.isBlank()) {
			throw new IllegalArgumentException();
		}
		String text = Files.readString(Path.of(file));
		String begin = "-----BEGIN " + label + "-----";
		String end = "-----END " + label + "-----";
		if (!text.strip().startsWith(begin) || !text.strip().endsWith(end)) {
			throw new IllegalArgumentException();
		}
		return Base64.getDecoder().decode(text.replace(begin, "").replace(end, "").replaceAll("\\s", ""));
	}
}
