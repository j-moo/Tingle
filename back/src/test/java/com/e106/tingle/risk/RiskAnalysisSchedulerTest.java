package com.e106.tingle.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class RiskAnalysisSchedulerTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withUserConfiguration(RiskAnalysisScheduler.class)
		.withBean(RiskAnalysisDispatcher.class, () -> mock(RiskAnalysisDispatcher.class))
		.withPropertyValues("app.risk.poll-ms=3600000");

	@Test
	void schedulerIsCreatedWithoutAnyFlag() {
		runner.run(context -> assertThat(context).hasNotFailed().hasSingleBean(RiskAnalysisScheduler.class));
	}

	@Test
	void onlyTestsCanTurnTheSchedulerOff() {
		runner.withPropertyValues("app.risk.scheduling-enabled=false")
			.run(context -> assertThat(context).doesNotHaveBean(RiskAnalysisScheduler.class));
	}
}
