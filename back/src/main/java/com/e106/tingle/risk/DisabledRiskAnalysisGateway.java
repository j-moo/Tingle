package com.e106.tingle.risk;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.risk.RiskAnalysisTypes.Input;
import com.e106.tingle.risk.RiskAnalysisTypes.Verdict;

/** 운영 분석을 제공하지 않는 기본값. 환경의 다른 서비스 API 키로 자동 활성화하지 않는다. */
@Component
@ConditionalOnProperty(name = "app.risk.external-enabled", havingValue = "false", matchIfMissing = true)
public class DisabledRiskAnalysisGateway implements RiskAnalysisGateway {

	@Override
	public void requireAvailable() {
		throw disabled();
	}

	@Override
	public int countTokens(Input input) {
		throw disabled();
	}

	@Override
	public Verdict analyze(Input input) {
		throw disabled();
	}

	private BusinessException disabled() {
		return new BusinessException(ErrorCode.POLICY_UNRESOLVED);
	}
}
