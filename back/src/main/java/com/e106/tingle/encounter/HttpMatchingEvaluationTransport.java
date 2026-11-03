package com.e106.tingle.encounter;

import java.util.Objects;
import java.util.UUID;

import com.e106.tingle.encounter.MatchingEvaluationTypes.Evaluation;
import com.e106.tingle.encounter.MatchingEvaluationTypes.Request;
import com.e106.tingle.global.ai.AiCallException;
import com.e106.tingle.global.ai.AiInternalClient;

import tools.jackson.databind.JsonNode;

/** Explicit construction only. A valid HTTP response does not establish approved runtime provenance. */
public final class HttpMatchingEvaluationTransport implements MatchingEvaluationTransport {
	private final AiInternalClient client;

	public HttpMatchingEvaluationTransport(AiInternalClient client) {
		this.client = Objects.requireNonNull(client);
	}

	@Override
	public Evaluation evaluate(Request request, UUID idempotencyKey) {
		if (request == null || idempotencyKey == null) {
			throw new AiCallException(false);
		}
		try {
			JsonNode response = client.postMatchingEvaluation(request, JsonNode.class, idempotencyKey);
			return MatchingEvaluationTypes.decode(response, request);
		} catch (AiCallException exception) {
			throw exception;
		} catch (RuntimeException exception) {
			// Malformed or mismatched responses are terminal protocol failures, never synthetic evaluations.
			throw new AiCallException(false);
		}
	}
}
