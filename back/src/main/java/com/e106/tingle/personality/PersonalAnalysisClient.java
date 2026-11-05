package com.e106.tingle.personality;

import java.util.List;

import tools.jackson.databind.JsonNode;

/**
 * AI 서버 개인 성향 분석 호출. 실제 구현은 HttpPersonalAnalysisClient, 테스트는 가짜로 바꾼다.
 */
public interface PersonalAnalysisClient {

	/** 호출할 수 있게 설정됐는지. 아니면 분석을 미루고 요청은 PENDING 으로 남는다. */
	boolean ready();

	/**
	 * 분석 결과 JSON(PersonalAnalysisResult)을 그대로 돌려준다.
	 *
	 * @throws Failure 호출 실패. retryable 이면 나중에 다시 실행한다
	 */
	JsonNode analyze(Request request, String requestId);

	/** AI 요청 샘플(succeeded.request.json, 2026-10-30)과 같은 모양. analysisId 는 personality.id. */
	record Request(long analysisId, String surveyVersion, String analysisVersion, List<Answer> answers) {
	}

	record Answer(String questionId, JsonNode value) {
	}

	/** lastErrorCode 로 저장할 짧은 코드. 답·결과 내용은 담지 않는다. */
	class Failure extends RuntimeException {

		private final String code;
		private final boolean retryable;

		public Failure(String code, boolean retryable) {
			super(code);
			this.code = code;
			this.retryable = retryable;
		}

		public String code() {
			return code;
		}

		public boolean retryable() {
			return retryable;
		}
	}
}
