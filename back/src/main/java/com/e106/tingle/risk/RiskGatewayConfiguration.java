package com.e106.tingle.risk;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.e106.tingle.global.ai.AiInternalClient;

/** 공통 AI 인증 활성화만으로 대화를 전송하지 않는다. 별도 승인과 로컬 tokenizer 등록이 필요하다. */
@Configuration(proxyBeanMethods = false)
public class RiskGatewayConfiguration {

	@Bean
	@ConditionalOnProperty(name = "app.risk.external-enabled", havingValue = "true")
	RiskAnalysisGateway httpRiskAnalysisGateway(AiInternalClient client, RiskTokenizer tokenizer) {
		return new HttpRiskAnalysisGateway(client, tokenizer);
	}
}
