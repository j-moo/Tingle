package com.e106.tingle.keyring;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Constructor;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;

/** 11-11 결정 B로 운영에서도 서명 키가 켜지지만 1바이트 ON/OFF·TPS1 개발 형식은 열리지 않는다. */
class KeyringSyncProductionGuardTest {
	private static final String NONCE = "A".repeat(43);

	@Test
	void productionRejectsDevelopmentOnlyPayloadFormatsBeforeTouchingAnyState() {
		context("prod").run(context -> {
			KeyringSyncService service = context.getBean(KeyringSyncService.class);
			for (String format : new String[] {null, "tingle-profile-storage-v1"}) {
				assertThatThrownBy(() -> service.create(1L, UUID.randomUUID(), 1L, UUID.randomUUID(), NONCE, format))
					.isInstanceOf(BusinessException.class)
					.extracting(exception -> ((BusinessException)exception).getErrorCode())
					.isEqualTo(ErrorCode.POLICY_UNRESOLVED);
			}
		});
	}

	@Test
	void developmentStillReachesTheNormalFlowForTheSameFormats() {
		// 의존성이 비어 있으므로 가드를 지나 첫 조회에서 멈춘다. 개발 형식이 개발에서 막히지 않았다는 뜻이다.
		context("dev").run(context -> assertThatThrownBy(() -> context.getBean(KeyringSyncService.class)
			.create(1L, UUID.randomUUID(), 1L, UUID.randomUUID(), NONCE, null))
			.isInstanceOf(NullPointerException.class));
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
