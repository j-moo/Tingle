package com.e106.tingle.encounter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.e106.tingle.global.ai.AiCallException;
import com.e106.tingle.global.ai.AiInternalClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

class HttpMatchingEvaluationTransportTest {
	private AiInternalClient client;
	private HttpMatchingEvaluationTransport transport;
	private final UUID key = UUID.randomUUID();

	@BeforeEach
	void prepareTransport() {
		client = mock(AiInternalClient.class);
		transport = new HttpMatchingEvaluationTransport(client);
	}

	@Test
	void delegatesTheSameFourReferenceRequestAndCallerIdempotencyKey() {
		given(client.postMatchingEvaluation(MatchingEvaluationFixture.REQUEST, JsonNode.class, key))
			.willReturn(MatchingEvaluationFixture.response());
		var result = transport.evaluate(MatchingEvaluationFixture.REQUEST, key);
		assertThat(result.encounterId()).isEqualTo(MatchingEvaluationFixture.REQUEST.encounterId());
		assertThat(result.eligible()).isTrue();
		verify(client).postMatchingEvaluation(MatchingEvaluationFixture.REQUEST, JsonNode.class, key);
		verifyNoMoreInteractions(client);
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void preservesRetryableClassificationAndOriginalHttpStatus(boolean retryable) {
		AiCallException failure = new AiCallException(retryable, retryable ? 503 : 409);
		given(client.postMatchingEvaluation(MatchingEvaluationFixture.REQUEST, JsonNode.class, key)).willThrow(failure);
		assertThatThrownBy(() -> transport.evaluate(MatchingEvaluationFixture.REQUEST, key)).isSameAs(failure);
	}

	@Test
	void malformedOrUnboundResultIsTerminalWithoutExposingItsPayload() {
		ObjectNode response = MatchingEvaluationFixture.response();
		response.put("encounterId", 9999);
		given(client.postMatchingEvaluation(MatchingEvaluationFixture.REQUEST, JsonNode.class, key))
			.willReturn(response);
		assertThatThrownBy(() -> transport.evaluate(MatchingEvaluationFixture.REQUEST, key))
			.isInstanceOfSatisfying(AiCallException.class, failure -> {
				assertThat(failure.retryable()).isFalse();
				assertThat(failure.getMessage()).doesNotContain("9999", "synthetic-survey");
			});
	}

	@Test
	void nullResultAndMissingCallerKeyCannotProduceAnEvaluation() {
		given(client.postMatchingEvaluation(MatchingEvaluationFixture.REQUEST, JsonNode.class, key)).willReturn(null);
		assertThatThrownBy(() -> transport.evaluate(MatchingEvaluationFixture.REQUEST, key))
			.isInstanceOfSatisfying(AiCallException.class, failure -> assertThat(failure.retryable()).isFalse());
		clearInvocations(client);
		assertThatThrownBy(() -> transport.evaluate(MatchingEvaluationFixture.REQUEST, null))
			.isInstanceOfSatisfying(AiCallException.class, failure -> assertThat(failure.retryable()).isFalse());
		assertThatThrownBy(() -> transport.evaluate(null, key))
			.isInstanceOfSatisfying(AiCallException.class, failure -> assertThat(failure.retryable()).isFalse());
		verifyNoInteractions(client);
	}
}
