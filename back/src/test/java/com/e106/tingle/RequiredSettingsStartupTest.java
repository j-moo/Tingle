package com.e106.tingle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Clock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import com.e106.tingle.chat.delivery.ChatMessagePushDispatcher;
import com.e106.tingle.chat.delivery.ChatMessagePushScheduler;
import com.e106.tingle.keyring.peer.PeerPolicyProperties;
import com.e106.tingle.push.PushConfiguration;
import com.e106.tingle.withdrawal.WithdrawalReceiptTokens;

/**
 * 스위치 대신 필수가 된 설정값. 비었으면 개발·운영 모두 기동하지 않고 어떤 환경변수가 빠졌는지 알려 준다.
 * 서명 키(DevSettingsConfigurationTest)·탈퇴 식별 키(WithdrawalIdentityIntegrationTest)·
 * 푸시 암호화 키(PushTokenEncryptionTest)는 각 테스트에서 본다.
 */
class RequiredSettingsStartupTest {

	@Test
	void missingPeerPolicyValuesPreventStartup() {
		ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(PeerPolicy.class)
			.withPropertyValues("app.keyring.peer.policy-version=1380991793", "app.keyring.peer.rule-version=268439809",
				"app.keyring.peer.lease-seconds=86400", "app.keyring.peer.opportunity-seconds=604800",
				"app.keyring.peer.presence-gap-seconds=30", "app.keyring.peer.server-public-key-hex=" + "0".repeat(64));
		runner.run(context -> assertThat(context).hasNotFailed());
		for (String[] missing : new String[][] {{"policy-version", "KEYRING_PEER_POLICY_VERSION"},
			{"rule-version", "KEYRING_PEER_RULE_VERSION"}, {"lease-seconds", "KEYRING_PEER_LEASE_SECONDS"},
			{"opportunity-seconds", "KEYRING_PEER_OPPORTUNITY_SECONDS"},
			{"presence-gap-seconds", "KEYRING_PEER_PRESENCE_GAP_SECONDS"},
			{"server-public-key-hex", "KEYRING_PEER_SERVER_PUBLIC_KEY_HEX"}}) {
			assertFails(runner.withPropertyValues("app.keyring.peer." + missing[0] + "="), missing[1]);
		}
		assertFails(runner.withPropertyValues("app.keyring.peer.lease-seconds=0"), "KEYRING_PEER_LEASE_SECONDS");
	}

	@Test
	void missingPushSettingsPreventStartup() {
		assertFails(new ApplicationContextRunner().withUserConfiguration(PushConfiguration.class)
			.withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
			.withBean(Clock.class, Clock::systemUTC), "FIREBASE_PROJECT_ID");
		assertFails(new ApplicationContextRunner().withUserConfiguration(ChatMessagePushScheduler.class)
			.withBean(ChatMessagePushDispatcher.class, () -> mock(ChatMessagePushDispatcher.class)),
			"CHAT_MESSAGE_PUSH_POLL_MS");
	}

	@Test
	void missingWithdrawalReceiptSecretPreventsStartup() {
		assertFails(new ApplicationContextRunner().withUserConfiguration(WithdrawalReceiptTokens.class),
			"WITHDRAWAL_RECEIPT_SECRET");
	}

	private static void assertFails(ApplicationContextRunner runner, String variable) {
		runner.run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).rootCause().isInstanceOf(IllegalStateException.class)
				.hasMessageContaining(variable);
		});
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(PeerPolicyProperties.class)
	static class PeerPolicy {
	}
}
