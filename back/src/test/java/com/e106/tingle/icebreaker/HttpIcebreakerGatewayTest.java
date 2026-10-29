package com.e106.tingle.icebreaker;

import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class HttpIcebreakerGatewayTest {
	@TempDir
	private Path directory;
	private final ObjectMapper mapper = new ObjectMapper();
	private final Clock clock = Clock.systemUTC();
	private final AtomicInteger requests = new AtomicInteger();
	private final AtomicReference<CapturedRequest> captured = new AtomicReference<>();
	private HttpServer server;

	@AfterEach
	void stopServer() {
		if (server != null) {
			server.stop(0);
		}
	}

	@Test
	void disabledGatewayHasNoInventedManifest() {
		HttpIcebreakerGateway gateway = gateway(false, "");
		assertThat(gateway.enabled()).isFalse();
		assertThat(gateway.manifest()).isEmpty();
		assertThatThrownBy(() -> gateway.generate(1, UUID.randomUUID(), Map.of(), deadline()))
			.isInstanceOf(IcebreakerGateway.GatewayException.class);
	}

	@Test
	void readsOnlyACompleteManifestWhoseArtifactHashMatches() throws Exception {
		Path artifact = directory.resolve("artifact.json");
		byte[] content = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
		Files.write(artifact, content);
		Map<String, Object> manifest = manifest(HexFormat.of().formatHex(
			MessageDigest.getInstance("SHA-256").digest(content)));
		Path descriptor = writeDescriptor(artifact, manifest);
		assertThat(gateway(true, descriptor.toUri().toString()).manifest()).isEqualTo(manifest);
		Files.writeString(artifact, "changed");
		assertThat(gateway(true, descriptor.toUri().toString()).manifest()).isEmpty();
	}

	@Test
	void rejectsMissingFieldsAndInvalidReleaseIds() throws Exception {
		Path artifact = directory.resolve("artifact.json");
		Files.writeString(artifact, "{}");
		Map<String, Object> manifest = manifest("0".repeat(64));
		manifest.remove("releaseId");
		assertThat(gateway(true, writeDescriptor(artifact, manifest).toUri().toString()).manifest()).isEmpty();
		manifest.put("releaseId", "not-a-uuid");
		assertThat(gateway(true, writeDescriptor(artifact, manifest).toUri().toString()).manifest()).isEmpty();
	}

	@Test
	void sendsOnlyTheCorrelatedContextAndManifestWithServiceAuthentication() throws Exception {
		startServer(200, successfulResponse());
		HttpIcebreakerGateway gateway = gateway(true, validDescriptor().toUri().toString(), endpoint());
		Map<String, Object> manifest = gateway.manifest();
		UUID attempt = UUID.randomUUID();
		IcebreakerGateway.Generated response = gateway.generate(2048, attempt, manifest, deadline());
		CapturedRequest request = captured.get();
		assertThat(request.method()).isEqualTo("POST");
		assertThat(request.path()).isEqualTo("/internal/ai/icebreakers");
		assertThat(request.authorization()).isEqualTo("Bearer synthetic-service-token");
		assertThat(request.contentType()).startsWith("application/json");
		assertThat(request.body().size()).isEqualTo(3);
		assertThat(request.body().path("contextId").asLong()).isEqualTo(2048);
		assertThat(request.body().path("attemptId").asString()).isEqualTo(attempt.toString());
		assertThat(request.body().path("manifest")).isEqualTo(mapper.valueToTree(manifest));
		assertThat(request.body().has("userId")).isFalse();
		assertThat(request.body().has("profile")).isFalse();
		assertThat(request.body().has("messages")).isFalse();
		assertThat(response.questions()).containsExactly("첫 질문", "두 번째 질문", "세 번째 질문");
		assertThat(response.source()).isEqualTo("GENERATED");
		assertThat(response.promptVersion()).isEqualTo("synthetic-prompt-v1");
		assertThat(requests.get()).isEqualTo(1);
	}

	@Test
	void disabledOrUnverifiedConfigurationNeverSendsAnHttpRequest() throws Exception {
		startServer(200, successfulResponse());
		HttpIcebreakerGateway disabled = gateway(false, validDescriptor().toUri().toString(), endpoint());
		assertThatThrownBy(() -> disabled.generate(2048, UUID.randomUUID(), Map.of(), deadline()))
			.isInstanceOfSatisfying(IcebreakerGateway.GatewayException.class,
				exception -> assertThat(exception.retryable()).isFalse());
		Path invalid = directory.resolve("unverified.json");
		Files.writeString(invalid, "{\"manifest\":{}}");
		HttpIcebreakerGateway unverified = gateway(true, invalid.toUri().toString(), endpoint());
		assertThatThrownBy(() -> unverified.generate(2048, UUID.randomUUID(),
			Map.of("releaseId", "invented"), deadline()))
			.isInstanceOfSatisfying(IcebreakerGateway.GatewayException.class,
				exception -> assertThat(exception.retryable()).isFalse());
		assertThat(requests.get()).isZero();
	}

	@Test
	void artifactTamperingAfterTheSnapshotPreventsHttpTransmission() throws Exception {
		startServer(200, successfulResponse());
		HttpIcebreakerGateway gateway = gateway(true, validDescriptor().toUri().toString(), endpoint());
		Map<String, Object> snapshot = gateway.manifest();
		assertThat(snapshot).hasSize(11);
		Files.writeString(directory.resolve("artifact.json"), "changed-after-snapshot");
		assertThatThrownBy(() -> gateway.generate(2048, UUID.randomUUID(), snapshot, deadline()))
			.isInstanceOfSatisfying(IcebreakerGateway.GatewayException.class,
				exception -> assertThat(exception.retryable()).isFalse());
		assertThat(requests.get()).isZero();
	}

	@Test
	void rateLimitedAndServerResponsesAreRetryableWithoutExposingProviderBodies() throws Exception {
		for (int status : new int[] {429, 503}) {
			startServer(status, "{\"detail\":\"synthetic-provider-private-detail\"}");
			HttpIcebreakerGateway gateway = gateway(true, validDescriptor().toUri().toString(), endpoint());
			assertThatThrownBy(() -> gateway.generate(2048, UUID.randomUUID(), gateway.manifest(), deadline()))
				.isInstanceOfSatisfying(IcebreakerGateway.GatewayException.class, exception -> {
					assertThat(exception.retryable()).isTrue();
					assertThat(exception.getMessage()).doesNotContain("synthetic-provider-private-detail");
				});
			server.stop(0);
		}
		assertThat(requests.get()).isEqualTo(2);
	}

	@Test
	void permanentProviderRejectionsAreNotRetried() throws Exception {
		for (int status : new int[] {400, 403}) {
			startServer(status, "{\"detail\":\"rejected\"}");
			HttpIcebreakerGateway gateway = gateway(true, validDescriptor().toUri().toString(), endpoint());
			assertThatThrownBy(() -> gateway.generate(2048, UUID.randomUUID(), gateway.manifest(), deadline()))
				.isInstanceOfSatisfying(IcebreakerGateway.GatewayException.class,
					exception -> assertThat(exception.retryable()).isFalse());
			server.stop(0);
		}
		assertThat(requests.get()).isEqualTo(2);
	}

	@Test
	void malformedProviderJsonIsAnUnretryableProtocolFailure() throws Exception {
		startServer(200, "{broken-json");
		HttpIcebreakerGateway gateway = gateway(true, validDescriptor().toUri().toString(), endpoint());
		assertThatThrownBy(() -> gateway.generate(2048, UUID.randomUUID(), gateway.manifest(), deadline()))
			.isInstanceOfSatisfying(IcebreakerGateway.GatewayException.class,
				exception -> assertThat(exception.retryable()).isFalse());
		assertThat(requests.get()).isEqualTo(1);
	}

	@Test
	void connectionFailuresRemainRetryable() throws Exception {
		startServer(200, successfulResponse());
		String unavailableEndpoint = endpoint();
		server.stop(0);
		HttpIcebreakerGateway gateway = gateway(true, validDescriptor().toUri().toString(), unavailableEndpoint);
		assertThatThrownBy(() -> gateway.generate(2048, UUID.randomUUID(), gateway.manifest(), deadline()))
			.isInstanceOfSatisfying(IcebreakerGateway.GatewayException.class,
				exception -> assertThat(exception.retryable()).isTrue());
		assertThat(requests.get()).isZero();
	}

	@Test
	void decodedButIncompleteResponsesFailQuestionValidation() throws Exception {
		startServer(200, "{\"questions\":[\"하나\",\"둘\",\"셋\"],\"source\":\"GENERATED\"}");
		HttpIcebreakerGateway gateway = gateway(true, validDescriptor().toUri().toString(), endpoint());
		IcebreakerGateway.Generated response = gateway.generate(2048, UUID.randomUUID(),
			gateway.manifest(), deadline());
		IcebreakerQuestions validator = new IcebreakerQuestions(new DefaultResourceLoader(), mapper,
			new IcebreakerProperties(false, "", "", "", "classpath:icebreakers/general-ko-v1.json"));
		assertThat(validator.validate(response)).isNull();
		assertThat(requests.get()).isEqualTo(1);
	}

	@Test
	void slowBodyCannotExtendTheOverallCallBudgetBySendingFrequentChunks() throws Exception {
		startSlowServer();
		HttpIcebreakerGateway gateway = gateway(validDescriptor().toUri().toString(), Duration.ofMillis(400));
		long started = System.nanoTime();
		assertThatThrownBy(() -> gateway.generate(2048, UUID.randomUUID(), gateway.manifest(), deadline()))
			.isInstanceOfSatisfying(IcebreakerGateway.GatewayException.class,
				exception -> assertThat(exception.retryable()).isTrue());
		assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
		assertThat(requests.get()).isEqualTo(1);
	}

	@Test
	void remainingGenerationDeadlineTakesPrecedenceOverTheLongerCallBudget() throws Exception {
		startSlowServer();
		HttpIcebreakerGateway gateway = gateway(validDescriptor().toUri().toString(), Duration.ofSeconds(10));
		Map<String, Object> manifest = gateway.manifest();
		Instant deadline = clock.instant().plusMillis(400);
		long started = System.nanoTime();
		assertThatThrownBy(() -> gateway.generate(2048, UUID.randomUUID(), manifest, deadline))
			.isInstanceOfSatisfying(IcebreakerGateway.GatewayException.class,
				exception -> assertThat(exception.retryable()).isTrue());
		assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
		assertThat(requests.get()).isEqualTo(1);
	}

	@Test
	void expiredGenerationDeadlineDoesNotTransmitAnyRequest() throws Exception {
		startServer(200, successfulResponse());
		HttpIcebreakerGateway gateway = gateway(true, validDescriptor().toUri().toString(), endpoint());
		assertThatThrownBy(() -> gateway.generate(2048, UUID.randomUUID(), gateway.manifest(), clock.instant()))
			.isInstanceOfSatisfying(IcebreakerGateway.GatewayException.class,
				exception -> assertThat(exception.retryable()).isTrue());
		assertThat(requests.get()).isZero();
	}

	@Test
	void oversizedFixedLengthAndChunkedBodiesAreUnretryableProtocolFailures() throws Exception {
		String oversized = successfulResponse().replace("첫 질문", "x".repeat(20 * 1024));
		for (boolean chunked : new boolean[] {false, true}) {
			server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.createContext("/internal/ai/icebreakers", exchange -> {
				requests.incrementAndGet();
				byte[] body = oversized.getBytes(StandardCharsets.UTF_8);
				try (exchange) {
					exchange.getRequestBody().readAllBytes();
					exchange.getResponseHeaders().set("Content-Type", "application/json");
					exchange.sendResponseHeaders(200, chunked ? 0 : body.length);
					exchange.getResponseBody().write(body);
				} catch (IOException ignored) {
					// 본문 상한에 도달한 클라이언트는 남은 응답 수신을 취소한다.
				}
			});
			server.start();
			HttpIcebreakerGateway gateway = gateway(true, validDescriptor().toUri().toString(), endpoint());
			assertThatThrownBy(() -> gateway.generate(2048, UUID.randomUUID(), gateway.manifest(), deadline()))
				.isInstanceOfSatisfying(IcebreakerGateway.GatewayException.class,
					exception -> assertThat(exception.retryable()).isFalse());
			server.stop(0);
		}
		assertThat(requests.get()).isEqualTo(2);
	}

	private void startSlowServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/internal/ai/icebreakers", exchange -> {
			requests.incrementAndGet();
			try (exchange) {
				exchange.getRequestBody().readAllBytes();
				exchange.getResponseHeaders().set("Content-Type", "application/json");
				exchange.sendResponseHeaders(200, 0);
				exchange.getResponseBody().write('{');
				for (int i = 0; i < 100; i++) {
					exchange.getResponseBody().write(' ');
					exchange.getResponseBody().flush();
					Thread.sleep(40);
				}
			} catch (IOException ignored) {
				// 전체 호출 기한으로 취소되면 서버 측 쓰기도 종료된다.
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
			}
		});
		server.start();
	}

	private Instant deadline() {
		return clock.instant().plus(Duration.ofMinutes(5));
	}

	private HttpIcebreakerGateway gateway(String location, Duration maximumCallTime) {
		return new HttpIcebreakerGateway(new IcebreakerProperties(true, endpoint(),
			"synthetic-service-token", location, "classpath:icebreakers/general-ko-v1.json"),
			new DefaultResourceLoader(), mapper, clock, maximumCallTime);
	}

	private Path validDescriptor() throws Exception {
		Path artifact = directory.resolve("artifact.json");
		byte[] content = "{}".getBytes(StandardCharsets.UTF_8);
		Files.write(artifact, content);
		return writeDescriptor(artifact, manifest(HexFormat.of().formatHex(
			MessageDigest.getInstance("SHA-256").digest(content))));
	}

	private void startServer(int status, String response) throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/internal/ai/icebreakers", exchange -> respond(exchange, status, response));
		server.start();
	}

	private void respond(HttpExchange exchange, int status, String response) throws IOException {
		try {
			requests.incrementAndGet();
			captured.set(new CapturedRequest(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
				exchange.getRequestHeaders().getFirst("Authorization"),
				exchange.getRequestHeaders().getFirst("Content-Type"), mapper.readTree(exchange.getRequestBody())));
			byte[] body = response.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(status, body.length);
			exchange.getResponseBody().write(body);
		} finally {
			exchange.close();
		}
	}

	private String endpoint() {
		return "http://127.0.0.1:" + server.getAddress().getPort() + "/internal/ai/icebreakers";
	}

	private String successfulResponse() {
		return "{\"questions\":[\"첫 질문\",\"두 번째 질문\",\"세 번째 질문\"],\"source\":\"GENERATED\","
			+ "\"promptVersion\":\"synthetic-prompt-v1\"}";
	}

	private Path writeDescriptor(Path artifact, Map<String, Object> manifest) throws Exception {
		Path descriptor = directory.resolve("manifest.json");
		Files.writeString(descriptor, mapper.writeValueAsString(Map.of("manifest", manifest,
			"artifact", artifact.toUri().toString())));
		return descriptor;
	}

	private Map<String, Object> manifest(String hash) {
		Map<String, Object> values = new HashMap<>();
		values.put("releaseId", UUID.randomUUID().toString());
		for (String name : java.util.List.of("surveyVersion", "wordingVersion", "scoringVersion", "typeModelVersion",
			"analysisVersion", "typeReferenceVersion", "matchingVersion", "productPolicyVersion", "engineVersion")) {
			values.put(name, "v1");
		}
		values.put("contentHash", hash);
		return values;
	}

	private HttpIcebreakerGateway gateway(boolean enabled, String location) {
		return gateway(enabled, location, "http://localhost/internal/ai/icebreakers");
	}

	private HttpIcebreakerGateway gateway(boolean enabled, String location, String endpoint) {
		return new HttpIcebreakerGateway(new IcebreakerProperties(enabled, endpoint,
			"synthetic-service-token", location, "classpath:icebreakers/general-ko-v1.json"),
			new DefaultResourceLoader(), mapper, clock);
	}

	private record CapturedRequest(String method, String path, String authorization, String contentType,
		JsonNode body) {
	}
}
