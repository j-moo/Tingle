package com.e106.tingle.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.e106.tingle.global.ai.AiInternalClient;
import com.e106.tingle.risk.RiskAnalysisTypes.Input;
import com.e106.tingle.risk.RiskAnalysisTypes.Message;

class RiskGatewayConfigurationTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withUserConfiguration(RiskGatewayConfiguration.class);

	@Test
	void usesTheRegisteredTokenizerAndCommonClientWithoutAnyFlag() {
		AiInternalClient client = mock(AiInternalClient.class);
		RiskTokenizer tokenizer = mock(RiskTokenizer.class);
		Input input = new Input(List.of(new Message(1, "A", "synthetic", true, false)), null);
		when(tokenizer.applyAsInt(input)).thenReturn(19);
		runner.withBean(AiInternalClient.class, () -> client).withBean(RiskTokenizer.class, () -> tokenizer)
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
	void withoutRegisteredTokenizerCountsTheSerializedContextBytes() {
		// AI 서버의 json.dumps(..., ensure_ascii=False, separators=(",", ":")) 결과와 같은 길이여야 한다.
		String ai = """
			{"contextBundle":[{"sequence":1,"speaker":"A","text":"안녕 \\"반가워\\"\\n","fresh":true,"urlMasked":false}]}"""
			.strip();
		Input input = new Input(List.of(new Message(1, "A", "안녕 \"반가워\"\n", true, false)), null);
		runner.withBean(AiInternalClient.class, () -> mock(AiInternalClient.class))
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context.getBean(RiskAnalysisGateway.class).countTokens(input))
					.isEqualTo(ai.getBytes(StandardCharsets.UTF_8).length + 16);
			});
	}

	@Test
	void withoutCommonClientFailsAtStartup() {
		runner.withBean(RiskTokenizer.class, () -> mock(RiskTokenizer.class))
			.run(context -> assertThat(context).hasFailed());
	}
}
