package com.e106.tingle.keyring;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

import com.e106.tingle.keyring.peer.PeerPolicyProperties;

@Configuration(proxyBeanMethods = false)
public class DevSettingsConfiguration {
	@Bean
	DevSettingsIssuer devSettingsIssuer(DevSettingsProperties properties, PeerPolicyProperties peer,
		Environment environment, JdbcTemplate jdbc) {
		DevSettingsIssuer issuer = new DevSettingsIssuer(jdbc, DevSigningKeyLoader.load(properties, environment));
		// 다르면 TSV3 발급이 모두 POLICY_UNRESOLVED 가 되므로 기동 단계에서 막는다.
		if (!issuer.publicKeyRawHex().equals(peer.serverPublicKeyHex())) {
			throw new IllegalStateException("KEYRING_PEER_SERVER_PUBLIC_KEY_HEX 가 키링 설정 서명 공개키와 다릅니다");
		}
		return issuer;
	}
}
