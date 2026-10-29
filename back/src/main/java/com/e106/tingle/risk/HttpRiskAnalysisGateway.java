package com.e106.tingle.risk;

import java.util.List;
import java.util.UUID;
import java.util.function.ToIntFunction;

import com.e106.tingle.global.ai.AiInternalClient;
import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.risk.RiskAnalysisTypes.Input;
import com.e106.tingle.risk.RiskAnalysisTypes.Manifest;
import com.e106.tingle.risk.RiskAnalysisTypes.Message;
import com.e106.tingle.risk.RiskAnalysisTypes.Request;
import com.e106.tingle.risk.RiskAnalysisTypes.Verdict;

/** 명시적으로 구성해야 하는 전송 어댑터. 운영 기본 Disabled gateway를 자동 교체하지 않는다. */
public final class HttpRiskAnalysisGateway implements RiskAnalysisGateway {

	private final AiInternalClient client;
	private final ToIntFunction<Input> tokenizer;

	public HttpRiskAnalysisGateway(AiInternalClient client, ToIntFunction<Input> tokenizer) {
		this.client = client;
		this.tokenizer = tokenizer;
	}

	@Override
	public void requireAvailable() {
		client.requireAvailable();
		if (tokenizer == null) {
			throw new BusinessException(ErrorCode.POLICY_UNRESOLVED);
		}
	}

	@Override
	public int countTokens(Input input) {
		if (tokenizer == null) {
			throw new BusinessException(ErrorCode.POLICY_UNRESOLVED);
		}
		return tokenizer.applyAsInt(input);
	}

	@Override
	public Verdict analyze(Input input) {
		throw new BusinessException(ErrorCode.INVALID_REQUEST);
	}

	@Override
	public Verdict analyze(Request request, Input input) {
		requireAvailable();
		WireRequest body = new WireRequest(request.attemptId(), request.contextId(), input.manifest(),
			input.messages());
		WireResponse response = client.post("/internal/ai/risk-detections", body, WireResponse.class,
			request.attemptId());
		if (response == null || !request.attemptId().equals(response.attemptId())
			|| !input.manifest().equals(response.manifest()) || response.verdict() == null) {
			throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
		}
		return response.verdict();
	}

	public record WireRequest(UUID attemptId, long contextId, Manifest manifest, List<Message> contextBundle) {
	}

	public record WireResponse(UUID attemptId, Manifest manifest, Verdict verdict) {
	}
}
