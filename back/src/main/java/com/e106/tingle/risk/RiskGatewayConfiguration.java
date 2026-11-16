package com.e106.tingle.risk;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.e106.tingle.global.ai.AiInternalClient;

/** 위험 감지 전송 어댑터는 개발·운영 모두 늘 등록된다. 실제 전송은 회원 동의와 승인된 Release(app.risk.release)가 있을 때만 한다. */
@Configuration(proxyBeanMethods = false)
public class RiskGatewayConfiguration {

	/**
	 * 특수 토큰(문장 앞뒤에 붙는 표시) 수는 토크나이저마다 다르지만 보통 1~4개다. 상한 계산이라 넉넉히 16으로 잡는다.
	 * 승인된 토크나이저가 더 많이 붙이면 app.risk.tokenizer.special-tokens 로 올린다.
	 * 실제 토크나이저 구현을 붙일 때는 이 기본 빈을 지운다(일반 설정 클래스의 ConditionalOnMissingBean 은 등록 순서에 따라 둘 다 남을 수 있다).
	 */
	@Bean
	@ConditionalOnMissingBean
	RiskTokenizer riskTokenizer(@Value("${app.risk.tokenizer.special-tokens:16}") int specialTokens) {
		return new ByteBoundRiskTokenizer(specialTokens);
	}

	@Bean
	RiskAnalysisGateway httpRiskAnalysisGateway(AiInternalClient client, RiskTokenizer tokenizer) {
		return new HttpRiskAnalysisGateway(client, tokenizer);
	}
}
