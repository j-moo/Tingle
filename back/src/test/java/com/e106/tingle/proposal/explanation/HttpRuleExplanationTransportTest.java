package com.e106.tingle.proposal.explanation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.e106.tingle.global.ai.AiInternalClient;
import com.e106.tingle.proposal.explanation.dto.ExplanationStatus;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** 공통 클라이언트의 대역으로 소비 계약만 검사하며 실제 HTTP·JWT·AI 연결 증거가 아니다. */
class HttpRuleExplanationTransportTest {

	private static final String PATH = "/internal/ai/explanations";
	private static final UUID ATTEMPT = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
	private final JsonMapper mapper = JsonMapper.builder().build();
	private final AiInternalClient client = mock(AiInternalClient.class);
	private final HttpRuleExplanationTransport transport = new HttpRuleExplanationTransport(client);
	private final RuleExplanationTransport.Request request = request(17);

	@Test
	void forwardsTheOriginalFourFieldRequestAndAttemptToTheCommonClient() {
		ObjectNode response = response();
		when(client.post(PATH, request, JsonNode.class, ATTEMPT)).thenReturn(response);

		assertThat(transport.explain(request)).contains(new ExplanationStatus(
			"SUCCEEDED", "RULE_FALLBACK", "연락 방식이 서로의 선호와 맞아요.", List.of(), null));

		InOrder calls = inOrder(client);
		calls.verify(client).requireAvailable();
		calls.verify(client).post(PATH, request, JsonNode.class, ATTEMPT);
		calls.verifyNoMoreInteractions();
	}

	@Test
	void unavailableCommonClientDoesNotSendOrInventAResult() {
		doThrow(new IllegalStateException("Synthetic unavailable client")).when(client).requireAvailable();

		assertThat(transport.explain(request)).isEmpty();

		verify(client).requireAvailable();
		verifyNoMoreInteractions(client);
	}

	@Test
	void commonClientFailureRemainsUnavailable() {
		when(client.post(PATH, request, JsonNode.class, ATTEMPT))
			.thenThrow(new IllegalStateException("Synthetic upstream failure"));
		assertThat(transport.explain(request)).isEmpty();
	}

	@Test
	void missingResponseRemainsUnavailable() {
		when(client.post(PATH, request, JsonNode.class, ATTEMPT)).thenReturn(null);
		assertThat(transport.explain(request)).isEmpty();
	}

	@Test
	void missingOrAdditionalResponseFieldsAreRejected() {
		for (String field : List.of("source", "text", "evidenceKeys", "promptVersion", "encounterId")) {
			ObjectNode response = response();
			response.remove(field);
			assertRejected(response);
		}
		assertRejected(response().put("status", "SUCCEEDED"));
	}

	@Test
	void nonObjectResponsesAreRejected() {
		assertRejected(mapper.readTree("null"));
		assertRejected(mapper.readTree("[]"));
		assertRejected(mapper.readTree("\"RULE_FALLBACK\""));
	}

	@Test
	void generatedContentOrEvidenceAndPromptMetadataAreRejected() {
		assertRejected(response().put("source", "GENERATED"));
		assertRejected(response().putNull("source"));
		assertRejected(response().put("promptVersion", "synthetic-v1"));
		assertRejected(response().put("promptVersion", ""));
		ObjectNode keys = response();
		keys.putArray("evidenceKeys").add("e1");
		assertRejected(keys);
		assertRejected(response().putNull("evidenceKeys"));
		assertRejected(response().put("evidenceKeys", "[]"));
	}

	@Test
	void wrongNonIntegralAndOverflowingEncounterIdsAreRejected() {
		for (String id : List.of("0", "-1", "18", "17.0", "\"17\"", "null", "9223372036854775808")) {
			ObjectNode response = response();
			response.set("encounterId", mapper.readTree(id));
			assertRejected(response);
		}
	}

	@Test
	void matchingPositiveInt64BoundaryIsAcceptedWithoutNarrowing() {
		RuleExplanationTransport.Request large = request(Long.MAX_VALUE);
		ObjectNode response = response().put("encounterId", Long.MAX_VALUE);
		when(client.post(PATH, large, JsonNode.class, ATTEMPT)).thenReturn(response);

		assertThat(transport.explain(large)).isPresent();
	}

	@Test
	void blankWrongTypeAndOverlongTextAreRejectedWithoutTrimming() {
		assertRejected(response().put("text", ""));
		assertRejected(response().put("text", " \t\n"));
		assertRejected(response().putNull("text"));
		assertRejected(response().put("text", 1));
		assertRejected(response().put("text", "가".repeat(601)));
	}

	@Test
	void textLimitCountsUnicodeCharactersAndKeepsOriginalContent() {
		String text = "🚀".repeat(600);
		when(client.post(PATH, request, JsonNode.class, ATTEMPT)).thenReturn(response().put("text", text));

		assertThat(transport.explain(request)).get().extracting(ExplanationStatus::text).isEqualTo(text);
		assertRejected(response().put("text", "🚀".repeat(601)));
	}

	@Test
	void invalidRequestIdentityNeverReachesTheClient() {
		assertThat(transport.explain(null)).isEmpty();
		assertThat(transport.explain(request(0))).isEmpty();
		assertThat(transport.explain(request(-1))).isEmpty();
		assertThat(transport.explain(new RuleExplanationTransport.Request(null, 17,
			mapper.createObjectNode(), mapper.createObjectNode()))).isEmpty();

		verifyNoInteractions(client);
	}

	private void assertRejected(JsonNode response) {
		when(client.post(PATH, request, JsonNode.class, ATTEMPT)).thenReturn(response);
		assertThat(transport.explain(request)).isEmpty();
	}

	private RuleExplanationTransport.Request request(long contextId) {
		return new RuleExplanationTransport.Request(ATTEMPT, contextId,
			mapper.createObjectNode().put("synthetic", "manifest"),
			mapper.createObjectNode().put("synthetic", "bundle"));
	}

	private ObjectNode response() {
		ObjectNode response = mapper.createObjectNode().put("source", "RULE_FALLBACK")
			.put("text", "연락 방식이 서로의 선호와 맞아요.").put("encounterId", 17);
		response.putArray("evidenceKeys");
		response.putNull("promptVersion");
		return response;
	}
}
