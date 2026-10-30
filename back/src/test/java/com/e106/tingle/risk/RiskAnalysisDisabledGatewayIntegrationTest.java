package com.e106.tingle.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Import;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.risk.RiskAnalysisTypes.Request;

/** Lightweight production wiring check only; no database, HTTP receiver, or model is started. */
class RiskAnalysisDisabledGatewayIntegrationTest {

	@Test
	void productionGatewayRejectsBeforeResolvingContextOrReadingStoredMessages() {
		RiskAnalysisStore store = mock(RiskAnalysisStore.class);
		RiskManifestCatalog manifests = mock(RiskManifestCatalog.class);
		RiskVerdicts verdicts = mock(RiskVerdicts.class);
		new ApplicationContextRunner().withUserConfiguration(RiskOnlyConfiguration.class)
			.withBean(Clock.class, Clock::systemUTC)
			.withBean(RiskAnalysisStore.class, () -> store)
			.withBean(RiskManifestCatalog.class, () -> manifests)
			.withBean(RiskVerdicts.class, () -> verdicts)
			.run(context -> {
				assertThat(context).hasSingleBean(RiskAnalysisGateway.class);
				assertThat(context.getBean(RiskAnalysisGateway.class)).isInstanceOf(DisabledRiskAnalysisGateway.class);
				Request input = new Request(UUID.randomUUID(), 1, null);
				assertThatThrownBy(() -> context.getBean(RiskAnalysisService.class).analyze(input))
					.isInstanceOf(BusinessException.class)
					.satisfies(error -> assertThat(((BusinessException)error).getErrorCode())
						.isEqualTo(ErrorCode.POLICY_UNRESOLVED));
				verifyNoInteractions(store, manifests, verdicts);
			});
	}

	@TestConfiguration(proxyBeanMethods = false)
	@Import({RiskAnalysisService.class, DisabledRiskAnalysisGateway.class})
	static class RiskOnlyConfiguration {
	}
}
