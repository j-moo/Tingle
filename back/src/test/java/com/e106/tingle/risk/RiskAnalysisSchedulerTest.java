package com.e106.tingle.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class RiskAnalysisSchedulerTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withUserConfiguration(RiskAnalysisScheduler.class)
		.withBean(RiskAnalysisDispatcher.class, () -> mock(RiskAnalysisDispatcher.class));

	@Test
	void neitherMissingFlagsNorEitherFlagAloneStartsScheduling() {
		runner.run(context -> assertThat(context).doesNotHaveBean(RiskAnalysisScheduler.class));
		runner.withPropertyValues("app.risk.external-enabled=true")
			.run(context -> assertThat(context).doesNotHaveBean(RiskAnalysisScheduler.class));
		runner.withPropertyValues("app.risk.scheduling-enabled=true")
			.run(context -> assertThat(context).doesNotHaveBean(RiskAnalysisScheduler.class));
		runner.withPropertyValues("app.risk.external-enabled=false", "app.risk.scheduling-enabled=true")
			.run(context -> assertThat(context).doesNotHaveBean(RiskAnalysisScheduler.class));
	}

	@Test
	void bothExplicitFlagsCreateTheScheduler() {
		runner.withPropertyValues("app.risk.external-enabled=true", "app.risk.scheduling-enabled=true",
			"app.risk.poll-ms=3600000")
			.run(context -> assertThat(context).hasNotFailed().hasSingleBean(RiskAnalysisScheduler.class));
	}
}
