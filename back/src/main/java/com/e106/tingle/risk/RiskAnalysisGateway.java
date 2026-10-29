package com.e106.tingle.risk;

import com.e106.tingle.risk.RiskAnalysisTypes.Input;
import com.e106.tingle.risk.RiskAnalysisTypes.Request;
import com.e106.tingle.risk.RiskAnalysisTypes.Verdict;

/** 실제 모델 어댑터는 입력 전체를 해당 모델의 로컬 tokenizer로 계산해야 한다. */
public interface RiskAnalysisGateway {

	void requireAvailable();

	int countTokens(Input input);

	Verdict analyze(Input input);

	default Verdict analyze(Request request, Input input) {
		return analyze(input);
	}
}
