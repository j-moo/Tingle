package com.e106.tingle.encounter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.e106.tingle.global.ai.AiCallException;
import com.e106.tingle.global.ai.AiInternalClient;
import com.e106.tingle.global.ai.AiServiceProperties;
import com.e106.tingle.global.ai.AiServiceTokenProvider;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.node.ObjectNode;

/** Real loopback HTTP, synthetic response and synthetic service token. No official matching runtime is invoked. */
class MatchingEvaluationHttpContractTest {
	private final AiServiceTokenProvider tokens = mock(AiServiceTokenProvider.class);
	private final AtomicInteger calls = new AtomicInteger();
	private final AtomicInteger status = new AtomicInteger(200);
	private final AtomicReference<String> response = new AtomicReference<>();
	private final List<String> bodies = new CopyOnWriteArrayList<>();
	private final List<String> keys = new CopyOnWriteArrayList<>();
	private final List<String> authorizations = new CopyOnWriteArrayList<>();
	private final UUID key = UUID.randomUUID();
	private HttpServer server;
	private HttpMatchingEvaluationTransport transport;
	private boolean expireFirstToken;

	@BeforeEach
	void startSyntheticLoopback() throws Exception {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/internal/matching/evaluations", this::respond);
		server.start();
		given(tokens.issueMatchingEvaluation()).willReturn("synthetic-matching-token");
		String address = "http://127.0.0.1:" + server.getAddress().getPort();
		AiServiceProperties properties = new AiServiceProperties(true, "test", "synthetic", "unused", address);
		transport = new HttpMatchingEvaluationTransport(new AiInternalClient(properties, tokens,
			MatchingEvaluationFixture.JSON));
		response.set(MatchingEvaluationFixture.JSON.writeValueAsString(MatchingEvaluationFixture.response()));
	}

	@AfterEach
	void stopSyntheticLoopback() {
		server.stop(0);
	}

	@Test
	void sendsFourReferenceBodyWithMatchingServiceTokenAndCallerIdempotencyKey() {
		var result = transport.evaluate(MatchingEvaluationFixture.REQUEST, key);
		assertThat(result.encounterId()).isEqualTo(1001);
		assertThat(result.eligible()).isTrue();
		assertThat(bodies).containsExactly(MatchingEvaluationFixture.JSON.writeValueAsString(
			MatchingEvaluationFixture.REQUEST));
		assertThat(keys).containsExactly(key.toString());
		assertThat(authorizations).containsExactly("Bearer synthetic-matching-token");
		assertThat(calls.get()).isEqualTo(1);
		verify(tokens).issueMatchingEvaluation();
		verify(tokens, never()).issue();
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("malformedResponses")
	void rejectsMalformedOrUnboundHttpResultsWithoutInventingAnEvaluation(String name, String body) {
		response.set(body);
		assertThatThrownBy(() -> transport.evaluate(MatchingEvaluationFixture.REQUEST, key))
			.as(name).isInstanceOfSatisfying(AiCallException.class, failure -> {
				assertThat(failure.retryable()).isFalse();
				assertThat(failure.getMessage()).doesNotContain("synthetic-policy", "9999");
			});
		assertThat(calls.get()).isEqualTo(1);
	}

	@ParameterizedTest
	@ValueSource(ints = {409, 503})
	void preservesTerminalConflictAndRetryableUnavailableOverActualHttp(int code) {
		status.set(code);
		response.set("{\"code\":\"" + (code == 409 ? "IDEMPOTENCY_CONFLICT" : "SERVICE_UNAVAILABLE") + "\"}");
		assertThatThrownBy(() -> transport.evaluate(MatchingEvaluationFixture.REQUEST, key))
			.isInstanceOfSatisfying(AiCallException.class, failure -> {
				assertThat(failure.httpStatus()).isEqualTo(code);
				assertThat(failure.retryable()).isEqualTo(code == 503);
			});
		assertThat(calls.get()).isEqualTo(1);
		assertThat(keys).containsExactly(key.toString());
	}

	@ParameterizedTest
	@ValueSource(ints = {201, 202})
	void onlyTheContractual200CanReturnAnEvaluationEvenWhenAnotherSuccessHasAValidBody(int code) {
		status.set(code);
		assertThatThrownBy(() -> transport.evaluate(MatchingEvaluationFixture.REQUEST, key))
			.isInstanceOfSatisfying(AiCallException.class, failure -> {
				assertThat(failure.httpStatus()).isEqualTo(code);
				assertThat(failure.retryable()).isFalse();
			});
		assertThat(calls.get()).isEqualTo(1);
	}

	@Test
	void tokenExpiryRetriesOnceWithTheSameBodyAndIdempotencyKey() {
		expireFirstToken = true;
		given(tokens.issueMatchingEvaluation()).willReturn("synthetic-old-token", "synthetic-renewed-token");
		assertThat(transport.evaluate(MatchingEvaluationFixture.REQUEST, key).encounterId()).isEqualTo(1001);
		assertThat(calls.get()).isEqualTo(2);
		assertThat(keys).containsExactly(key.toString(), key.toString());
		assertThat(bodies).hasSize(2);
		assertThat(bodies.getFirst()).isEqualTo(bodies.getLast());
		assertThat(authorizations).containsExactly("Bearer synthetic-old-token", "Bearer synthetic-renewed-token");
		verify(tokens, times(2)).issueMatchingEvaluation();
		verify(tokens, never()).issue();
	}

	private void respond(HttpExchange exchange) throws IOException {
		int current = calls.incrementAndGet();
		bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
		keys.add(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
		authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
		boolean expired = expireFirstToken && current == 1;
		byte[] bytes = (expired ? "{\"code\":\"ACCESS_TOKEN_EXPIRED\"}" : response.get())
			.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(expired ? 401 : status.get(), bytes.length);
		exchange.getResponseBody().write(bytes);
		exchange.close();
	}

	private static Stream<Arguments> malformedResponses() {
		String valid = MatchingEvaluationFixture.JSON.writeValueAsString(MatchingEvaluationFixture.response());
		ObjectNode wrongBinding = MatchingEvaluationFixture.response();
		wrongBinding.put("encounterId", 9999);
		ObjectNode injectedEvidence = MatchingEvaluationFixture.response();
		injectedEvidence.put("historicalEvidence", "must not be accepted");
		return Stream.of(Arguments.of("non JSON", "not JSON"), Arguments.of("null", "null"),
			Arguments.of("duplicate field", valid.replace("\"eligible\":true", "\"eligible\":true,\"eligible\":false")),
			Arguments.of("trailing document", valid + "{}"), Arguments.of("missing fields", "{}"),
			Arguments.of("wrong request binding", MatchingEvaluationFixture.JSON.writeValueAsString(wrongBinding)),
			Arguments.of("injected historical evidence",
				MatchingEvaluationFixture.JSON.writeValueAsString(injectedEvidence)));
	}
}
