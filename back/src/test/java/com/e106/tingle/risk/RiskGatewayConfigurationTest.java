package com.e106.tingle.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.e106.tingle.global.ai.AiInternalClient;
import com.e106.tingle.risk.RiskAnalysisTypes.Input;
import com.e106.tingle.risk.RiskAnalysisTypes.Message;

class RiskGatewayConfigurationTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withUserConfiguration(RiskGatewayConfiguration.class, DisabledRiskAnalysisGateway.class);

	@Test
	void defaultDoesNotRequireAnAiClientOrTokenizer() {
		runner.run(context -> {
			assertThat(context).hasNotFailed().hasSingleBean(RiskAnalysisGateway.class);
			assertThat(context.getBean(RiskAnalysisGateway.class)).isInstanceOf(DisabledRiskAnalysisGateway.class);
		});
	}

	@Test
	void commonAiEnablementDoesNotEnableRiskTransmission() {
		AiInternalClient client = mock(AiInternalClient.class);
		RiskTokenizer tokenizer = mock(RiskTokenizer.class);
		runner.withPropertyValues("app.ai.service.enabled=true")
			.withBean(AiInternalClient.class, () -> client).withBean(RiskTokenizer.class, () -> tokenizer)
			.run(context -> {
				assertThat(context).hasNotFailed().hasSingleBean(RiskAnalysisGateway.class);
				assertThat(context.getBean(RiskAnalysisGateway.class)).isInstanceOf(DisabledRiskAnalysisGateway.class);
				verifyNoInteractions(client, tokenizer);
			});
	}

	@Test
	void explicitEnablementUsesTheRegisteredTokenizerAndCommonClient() {
		AiInternalClient client = mock(AiInternalClient.class);
		RiskTokenizer tokenizer = mock(RiskTokenizer.class);
		Input input = new Input(List.of(new Message(1, "A", "synthetic", true, false)), null);
		when(tokenizer.applyAsInt(input)).thenReturn(19);
		runner.withPropertyValues("app.risk.external-enabled=true")
			.withBean(AiInternalClient.class, () -> client).withBean(RiskTokenizer.class, () -> tokenizer)
			.run(context -> {
				assertThat(context).hasNotFailed().hasSingleBean(RiskAnalysisGateway.class);
				RiskAnalysisGateway gateway = context.getBean(RiskAnalysisGateway.class);
				assertThat(gateway).isInstanceOf(HttpRiskAnalysisGateway.class);
				gateway.requireAvailable();
				assertThat(gateway.countTokens(input)).isEqualTo(19);
				verify(client).requireAvailable();
				verify(tokenizer).applyAsInt(input);
			});
	}

	@Test
	void enabledWithoutTokenizerFailsAtStartup() {
		runner.withPropertyValues("app.risk.external-enabled=true")
			.withBean(AiInternalClient.class, () -> mock(AiInternalClient.class))
			.run(context -> assertThat(context).hasFailed());
	}

	@Test
	void enabledWithoutCommonClientFailsAtStartup() {
		runner.withPropertyValues("app.risk.external-enabled=true")
			.withBean(RiskTokenizer.class, () -> mock(RiskTokenizer.class))
			.run(context -> assertThat(context).hasFailed());
	}
}
