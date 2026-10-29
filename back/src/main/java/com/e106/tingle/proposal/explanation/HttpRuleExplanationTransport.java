package com.e106.tingle.proposal.explanation;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.e106.tingle.global.ai.AiInternalClient;
import com.e106.tingle.proposal.explanation.dto.ExplanationStatus;

import tools.jackson.databind.JsonNode;

/** 공통 내부 AI 클라이언트를 사용하고 규칙 설명 응답만 공개 형식으로 투영한다. */
@Component
public class HttpRuleExplanationTransport implements RuleExplanationTransport {

	private static final String PATH = "/internal/ai/explanations";
	private static final Set<String> RESPONSE_FIELDS = Set.of("source", "text", "evidenceKeys", "promptVersion",
		"encounterId");
	private final AiInternalClient client;

	public HttpRuleExplanationTransport(AiInternalClient client) {
		this.client = client;
	}

	@Override
	public Optional<ExplanationStatus> explain(Request request) {
		try {
			if (request == null || request.attemptId() == null || request.contextId() <= 0) {
				return Optional.empty();
			}
			client.requireAvailable();
			JsonNode response = client.post(PATH, request, JsonNode.class, request.attemptId());
			if (!valid(response, request.contextId())) {
				return Optional.empty();
			}
			return Optional.of(new ExplanationStatus("SUCCEEDED", "RULE_FALLBACK",
				response.get("text").asString(), List.of(), null));
		} catch (RuntimeException exception) {
			// 토큰·원응답·내부 실패 사유는 결과나 로그에 복사하지 않는다.
			return Optional.empty();
		}
	}

	private static boolean valid(JsonNode response, long contextId) {
		if (!ExplanationArtifactCatalog.hasFields(response, RESPONSE_FIELDS)) {
			return false;
		}
		JsonNode id = response.get("encounterId");
		JsonNode source = response.get("source");
		JsonNode text = response.get("text");
		JsonNode keys = response.get("evidenceKeys");
		if (!id.isIntegralNumber() || !id.canConvertToLong() || id.longValue() != contextId
			|| !source.isString() || !"RULE_FALLBACK".equals(source.asString())
			|| !text.isString() || text.asString().isBlank()
			|| !keys.isArray() || !keys.isEmpty() || !response.get("promptVersion").isNull()) {
			return false;
		}
		String value = text.asString();
		return value.codePointCount(0, value.length()) <= 600;
	}
}
