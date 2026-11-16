package com.e106.tingle.keyring.peer;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 키링 TSV3 정책 값(app.keyring.peer). 개발·운영이 같은 값을 쓴다.
 * 비었거나 펌웨어가 아는 값과 다르면 발급마다 POLICY_UNRESOLVED 가 나므로 기동 단계에서 막는다.
 */
@ConfigurationProperties("app.keyring.peer")
public record PeerPolicyProperties(Long policyVersion, Long ruleVersion, Long leaseSeconds, Long opportunitySeconds,
	Integer presenceGapSeconds, String serverPublicKeyHex) {

	static final long POLICY_VERSION = 0x52504331L;
	static final long RULE_VERSION = 0x10001101L;

	public PeerPolicyProperties {
		require(policyVersion != null && policyVersion == POLICY_VERSION, "KEYRING_PEER_POLICY_VERSION",
			Long.toString(POLICY_VERSION));
		require(ruleVersion != null && ruleVersion == RULE_VERSION, "KEYRING_PEER_RULE_VERSION",
			Long.toString(RULE_VERSION));
		require(leaseSeconds != null && leaseSeconds > 0, "KEYRING_PEER_LEASE_SECONDS", "1 이상");
		require(opportunitySeconds != null && opportunitySeconds > 0 && opportunitySeconds <= 365L * 86400,
			"KEYRING_PEER_OPPORTUNITY_SECONDS", "1~31536000");
		require(presenceGapSeconds != null && presenceGapSeconds >= 1 && presenceGapSeconds <= 300,
			"KEYRING_PEER_PRESENCE_GAP_SECONDS", "1~300");
		require(serverPublicKeyHex != null && serverPublicKeyHex.matches("[0-9a-f]{64}"),
			"KEYRING_PEER_SERVER_PUBLIC_KEY_HEX", "Ed25519 공개키 32바이트 소문자 hex");
	}

	private static void require(boolean valid, String name, String expected) {
		if (!valid) {
			throw new IllegalStateException(name + " 값이 없거나 올바르지 않습니다 (필요: " + expected + ")");
		}
	}
}
