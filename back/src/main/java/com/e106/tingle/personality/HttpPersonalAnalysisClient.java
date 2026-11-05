package com.e106.tingle.personality;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.e106.tingle.global.ai.AiCallException;
import com.e106.tingle.global.ai.AiInternalClient;
import com.e106.tingle.global.error.BusinessException;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;

/**
 * 공통 AI 클라이언트(서비스 토큰·허용 주소·응답 크기 제한)로 개인 분석을 부른다.
 * 400·409·422 등 요청 자체 문제는 다시 보내도 같으므로 재시도하지 않고,
 * 5xx·408·429·시간 초과·연결 실패는 일시 오류로 보고 나중에 다시 실행한다.
 */
@Component
@RequiredArgsConstructor
class HttpPersonalAnalysisClient implements PersonalAnalysisClient {

	static final String PATH = "/internal/ai/personal-analyses";

	private final AiInternalClient client;

	@Override
	public boolean ready() {
		try {
			client.requireAvailable();
			return true;
		} catch (BusinessException e) {
			return false;
		}
	}

	@Override
	public JsonNode analyze(Request request, String requestId) {
		try {
			return client.post(PATH, request, JsonNode.class, UUID.fromString(requestId));
		} catch (AiCallException e) {
			Integer status = e.httpStatus();
			if (status != null) {
				throw new Failure("AI_HTTP_" + status, status >= 500 || status == 408 || status == 429);
			}
			// 시간 초과·연결 실패는 공통 클라이언트가 재시도 가능으로, 응답 크기 초과·해석 실패는 불가로 나눈다.
			throw new Failure("AI_UNAVAILABLE", e.retryable());
		} catch (BusinessException e) {
			// 공통 AI 설정이 꺼져 있는 등 호출 전 거절. 설정이 돌아오면 다시 실행한다.
			throw new Failure("AI_UNAVAILABLE", true);
		}
	}
}
