package com.e106.tingle.keyring;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Constructor;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** 운영도 개발과 같이 1바이트 ON/OFF·TPS1 형식을 받는다(11-17 결정 D3 가). 프로필로 막는 가드가 없다. */
class KeyringSyncProductionGuardTest {
	private static final String NONCE = "A".repeat(43);

	@Test
	void productionAndDevelopmentReachTheSameNormalFlowForEveryFormat() {
		// 의존성이 비어 있으므로 형식 검사를 지나 첫 조회에서 멈춘다. 어느 프로필에서도 형식이 막히지 않았다는 뜻이다.
		for (String profile : new String[] {"dev", "prod"}) {
			for (String format : new String[] {null, "tingle-profile-storage-v1"}) {
				context(profile).run(context -> assertThatThrownBy(() -> context.getBean(KeyringSyncService.class)
					.create(1L, UUID.randomUUID(), 1L, UUID.randomUUID(), NONCE, format))
					.isInstanceOf(NullPointerException.class));
			}
		}
	}

	private static ApplicationContextRunner context(String profile) {
		return new ApplicationContextRunner()
			.withInitializer(context -> context.getEnvironment().setActiveProfiles(profile))
			.withBean(KeyringSyncService.class, KeyringSyncProductionGuardTest::emptyService);
	}

	private static KeyringSyncService emptyService() {
		try {
			Constructor<?> constructor = KeyringSyncService.class.getConstructors()[0];
			return (KeyringSyncService)constructor.newInstance(new Object[constructor.getParameterCount()]);
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}
}
