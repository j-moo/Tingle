package com.e106.tingle.proposal.explanation;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.e106.tingle.global.ai.AiInternalClient;
import com.e106.tingle.proposal.explanation.dto.ExplanationStatus;

import tools.jackson.databind.JsonNode;

/** 공통 내부 AI 클라이언트를 사용하고 규칙 설명과 GMS 생성 설명만 공개 형식으로 투영한다. */
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
			JsonNode version = response.get("promptVersion");
			return Optional.of(new ExplanationStatus("SUCCEEDED", response.get("source").asString(),
				response.get("text").asString(), List.of(), version.isNull() ? null : version.asString()));
		} catch (RuntimeException exception) {
			// 토큰·원응답·내부 실패 사유는 결과나 로그에 복사하지 않는다.
			return Optional.empty();
		}
	}

	private static boolean valid(JsonNode response, long contextId) {
		if (!ExplanationManifest.hasFields(response, RESPONSE_FIELDS)) {
			return false;
		}
		JsonNode id = response.get("encounterId");
		JsonNode source = response.get("source");
		JsonNode text = response.get("text");
		JsonNode keys = response.get("evidenceKeys");
		if (!id.isIntegralNumber() || !id.canConvertToLong() || id.longValue() != contextId
			|| !source.isString() || !text.isString() || text.asString().isBlank()
			|| !keys.isArray() || !keys.isEmpty()) {
			return false;
		}
		// 규칙 설명은 버전이 없고, GMS 생성 설명은 프롬프트 해시(소문자 64자리) 형식이어야 한다. 그 밖의 조합은 버린다.
		JsonNode version = response.get("promptVersion");
		boolean rule = ExplanationStatus.RULE_FALLBACK.equals(source.asString()) && version.isNull();
		boolean generated = ExplanationStatus.GENERATED.equals(source.asString()) && version.isString()
			&& ExplanationStatus.GENERATED_VERSION.matcher(version.asString()).matches();
		if (!rule && !generated) {
			return false;
		}
		String value = text.asString();
		return value.codePointCount(0, value.length()) <= 600;
	}
}
