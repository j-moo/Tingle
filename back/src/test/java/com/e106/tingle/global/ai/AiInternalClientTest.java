package com.e106.tingle.global.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.risk.HttpRiskAnalysisGateway;
import com.e106.tingle.risk.RiskAnalysisTypes;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

class AiInternalClientTest {

	private final AiServiceTokenProvider tokens = mock(AiServiceTokenProvider.class);
	private final JsonMapper json = JsonMapper.builder().build();
	private final AtomicInteger calls = new AtomicInteger();
	private HttpServer server;
	private String base;

	@BeforeEach
	void loopbackOnly() throws Exception {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.start();
		base = "http://127.0.0.1:" + server.getAddress().getPort();
		when(tokens.issue()).thenReturn("synthetic-service-jwt");
	}

	@AfterEach
	void stopLoopback() {
		server.stop(0);
	}

	@Test
	void postsTheExactBodyWithOnlyServiceAuthorizationAndTheSameRequestId() {
		AtomicReference<String> body = new AtomicReference<>();
		AtomicReference<String> authorization = new AtomicReference<>();
		AtomicReference<String> requestId = new AtomicReference<>();
		server.createContext("/internal/ai/risk-detections", exchange -> {
			calls.incrementAndGet();
			body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
			requestId.set(exchange.getRequestHeaders().getFirst("X-Request-Id"));
			byte[] response = "{\"status\":\"SUCCEEDED\"}".getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, response.length);
			exchange.getResponseBody().write(response);
			exchange.close();
		});
		UUID id = UUID.randomUUID();
		Map<String, String> payload = Map.of("attemptId", UUID.randomUUID().toString());
		var response = client("test", base).post("/internal/ai/risk-detections", payload, Map.class, id);
		assertThat(response.get("status")).isEqualTo("SUCCEEDED");
		assertThat(body.get()).isEqualTo(json.writeValueAsString(payload));
		assertThat(authorization.get()).isEqualTo("Bearer synthetic-service-jwt");
		assertThat(requestId.get()).isEqualTo(id.toString());
		assertThat(calls.get()).isEqualTo(1);
		verify(tokens).issue();
	}

	@Test
	void realRiskResponseRecordsDeserializeStrictlyAndSurviveTheBoundedHttpSubscriber() {
		String version = "synthetic-v1";
		var manifest = new RiskAnalysisTypes.Manifest(UUID.randomUUID(), version, version, version,
			version, version, version, version, version, version, "0".repeat(64));
		var verdict = new RiskAnalysisTypes.Verdict(RiskAnalysisTypes.Kind.NORMAL, List.of(), 0.9,
			"synthetic-model-v1", "synthetic-prompt-v1");
		var expected = new HttpRiskAnalysisGateway.WireResponse(UUID.randomUUID(), manifest, verdict);
		byte[] payload = json.writeValueAsBytes(expected);
		JsonMapper strict = json.rebuild().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
			.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
		// 이 직접 역직렬화가 실패하면 가상 응답만 포함한 원인을 테스트에서 확인할 수 있다.
		assertThat(strict.readValue(payload, HttpRiskAnalysisGateway.WireResponse.class)).isEqualTo(expected);
		server.createContext("/internal/ai/risk-detections", exchange -> {
			calls.incrementAndGet();
			exchange.getRequestBody().readAllBytes();
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, payload.length);
			exchange.getResponseBody().write(payload);
			exchange.close();
		});
		var request = new HttpRiskAnalysisGateway.WireRequest(expected.attemptId(), 7, manifest,
			List.of(new RiskAnalysisTypes.Message(1, "A", "가상 대화: 합성 응답 검증", true, false)));
		assertThat(client("test", base).post("/internal/ai/risk-detections", request,
			HttpRiskAnalysisGateway.WireResponse.class, expected.attemptId())).isEqualTo(expected);
		assertThat(calls.get()).isEqualTo(1);
	}

	@Test
	void rejectsNonAllowlistedPathsWithoutIssuingOrSendingAToken() {
		AiInternalClient client = client("test", base);
		for (String path : List.of("/api/v1/users", "/internal/matching/evaluations",
			"/internal/ai/risk-detections?other=1", "https://example.invalid/internal/ai/risk-detections")) {
			assertUnavailable(() -> client.post(path, Map.of(), Map.class, UUID.randomUUID()));
		}
		assertThat(calls.get()).isZero();
		verify(tokens, org.mockito.Mockito.never()).issue();
	}

	@Test
	void rejectsInsecureRemoteHttpAndAddressComponents() {
		for (String address : List.of("http://ai:8000", "http://127.0.0.1.evil.invalid", base + "/api/v1",
			base + "?token=value", base + "#fragment", "http://user:secret@127.0.0.1", "file:///tmp/ai")) {
			assertUnavailable(() -> client("test", address).requireAvailable());
		}
		assertUnavailable(() -> client("prod", base).requireAvailable());
		assertUnavailable(() -> client("dev", base).requireAvailable());
	}

	@Test
	void redirectsInvalidTokensForbiddenAndUnavailableResponsesNeverRepeatOrForwardTheToken() {
		server.createContext("/target", exchange -> {
			calls.incrementAndGet();
			exchange.sendResponseHeaders(500, -1);
			exchange.close();
		});
		for (int status : List.of(302, 307, 401, 403, 503)) {
			server.createContext("/internal/ai/risk-detections", exchange -> {
				calls.incrementAndGet();
				exchange.getResponseHeaders().set("Location", base + "/target");
				String code = status == 401 ? "ACCESS_TOKEN_INVALID" : "ACCESS_TOKEN_EXPIRED";
				byte[] content = ("{\"code\":\"" + code + "\"}").getBytes(StandardCharsets.UTF_8);
				exchange.sendResponseHeaders(status, content.length);
				exchange.getResponseBody().write(content);
				exchange.close();
			});
			assertUnavailable(() -> client("test", base)
				.post("/internal/ai/risk-detections", Map.of(), Map.class, UUID.randomUUID()));
			server.removeContext("/internal/ai/risk-detections");
		}
		assertThat(calls.get()).isEqualTo(5);
	}

	@Test
	void onlyExplicitExpiryRetriesOnceWithANewTokenAndTheIdenticalBodyAndRequestId() {
		List<String> bodies = new CopyOnWriteArrayList<>();
		List<String> traces = new CopyOnWriteArrayList<>();
		List<String> authorizations = new CopyOnWriteArrayList<>();
		when(tokens.issue()).thenReturn("synthetic-first-jwt", "synthetic-second-jwt");
		server.createContext("/internal/ai/risk-detections", exchange -> {
			int current = calls.incrementAndGet();
			bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			traces.add(exchange.getRequestHeaders().getFirst("X-Request-Id"));
			authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
			String content = current == 1 ? "{\"code\":\"ACCESS_TOKEN_EXPIRED\"}" : "{\"status\":\"SUCCEEDED\"}";
			byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(current == 1 ? 401 : 200, bytes.length);
			exchange.getResponseBody().write(bytes);
			exchange.close();
		});
		UUID requestId = UUID.randomUUID();
		Map<String, String> body = Map.of("attemptId", UUID.randomUUID().toString());
		var response = client("test", base).post("/internal/ai/risk-detections", body, Map.class, requestId);
		assertThat(response.get("status")).isEqualTo("SUCCEEDED");
		assertThat(calls.get()).isEqualTo(2);
		assertThat(bodies).containsExactly(json.writeValueAsString(body), json.writeValueAsString(body));
		assertThat(traces).containsExactly(requestId.toString(), requestId.toString());
		assertThat(authorizations).containsExactly("Bearer synthetic-first-jwt", "Bearer synthetic-second-jwt");
		verify(tokens, org.mockito.Mockito.times(2)).issue();
	}

	@Test
	void secondExplicitExpiryEndsTheRequestWithoutAThirdTransmission() {
		server.createContext("/internal/ai/risk-detections", exchange -> {
			calls.incrementAndGet();
			byte[] bytes = "{\"code\":\"ACCESS_TOKEN_EXPIRED\"}".getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(401, bytes.length);
			exchange.getResponseBody().write(bytes);
			exchange.close();
		});
		assertUnavailable(() -> client("test", base)
			.post("/internal/ai/risk-detections", Map.of(), Map.class, UUID.randomUUID()));
		assertThat(calls.get()).isEqualTo(2);
		verify(tokens, org.mockito.Mockito.times(2)).issue();
	}

	@Test
	void ambiguousOrMalformedUnauthorizedBodiesNeverCauseAuthenticationRetries() {
		List<String> bodies = List.of("not JSON", "{}", "{\"code\":[\"ACCESS_TOKEN_EXPIRED\"]}",
			"{\"code\":\"ACCESS_TOKEN_EXPIRED\",\"code\":\"ACCESS_TOKEN_INVALID\"}",
			"{\"code\":\"ACCESS_TOKEN_EXPIRED\"}{}");
		for (String body : bodies) {
			server.createContext("/internal/ai/risk-detections", exchange -> {
				calls.incrementAndGet();
				byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
				exchange.sendResponseHeaders(401, bytes.length);
				exchange.getResponseBody().write(bytes);
				exchange.close();
			});
			assertUnavailable(() -> client("test", base)
				.post("/internal/ai/risk-detections", Map.of(), Map.class, UUID.randomUUID()));
			server.removeContext("/internal/ai/risk-detections");
		}
		assertThat(calls.get()).isEqualTo(bodies.size());
	}

	@Test
	void disabledAddressCannotIssueATokenEvenIfTheProviderIsMockedAsAvailable() {
		AiInternalClient client = new AiInternalClient(new AiServiceProperties(false, null, null, null, base),
			tokens, json);
		assertUnavailable(() -> client.post("/internal/ai/risk-detections", Map.of(), Map.class, null));
		verify(tokens, org.mockito.Mockito.never()).issue();
	}

	@Test
	void malformedSuccessfulJsonBecomesAnUnavailableServiceWithoutAnErrorBodyLeak() {
		server.createContext("/internal/ai/risk-detections", exchange -> {
			calls.incrementAndGet();
			byte[] content = "synthetic raw upstream response".getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, content.length);
			exchange.getResponseBody().write(content);
			exchange.close();
		});
		assertUnavailable(() -> client("test", base)
			.post("/internal/ai/risk-detections", Map.of(), Map.class, UUID.randomUUID()));
		assertThat(calls.get()).isEqualTo(1);
	}

	private AiInternalClient client(String environment, String address) {
		return new AiInternalClient(new AiServiceProperties(true, environment, "synthetic", "", address),
			tokens, json);
	}

	private void assertUnavailable(Runnable operation) {
		assertThatThrownBy(operation::run).isInstanceOfSatisfying(BusinessException.class,
			exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
	}
}
