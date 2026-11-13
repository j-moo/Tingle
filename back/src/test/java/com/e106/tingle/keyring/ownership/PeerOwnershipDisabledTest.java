package com.e106.tingle.keyring.ownership;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class PeerOwnershipDisabledTest {

	@Test
	void defaultOffDoesNotRequireJdbcOrTheOptionalSchema() {
		new ApplicationContextRunner().withUserConfiguration(PeerOwnershipService.class)
			.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(PeerOwnershipService.class));
	}
}
