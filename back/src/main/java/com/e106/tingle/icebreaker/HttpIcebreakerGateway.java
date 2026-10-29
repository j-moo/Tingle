package com.e106.tingle.icebreaker;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.core.io.ResourceLoader;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Bounded transport retained for verification; production uses PendingIcebreakerGateway until common auth is wired. */
public class HttpIcebreakerGateway implements IcebreakerGateway {
	private static final Set<String> MANIFEST_FIELDS = Set.of("releaseId", "surveyVersion", "wordingVersion",
		"scoringVersion", "typeModelVersion", "analysisVersion", "typeReferenceVersion", "matchingVersion",
		"productPolicyVersion", "engineVersion", "contentHash");
	private final IcebreakerProperties properties;
	private final ResourceLoader resources;
	private final ObjectMapper mapper;
	private final HttpClient client;
	private final Clock clock;
	private final Duration maxCallTime;

	public HttpIcebreakerGateway(IcebreakerProperties properties, ResourceLoader resources, ObjectMapper mapper,
		Clock clock) {
		this(properties, resources, mapper, clock, Duration.ofSeconds(10));
	}

	HttpIcebreakerGateway(IcebreakerProperties properties, ResourceLoader resources, ObjectMapper mapper,
		Clock clock, Duration maxCallTime) {
		this.properties = properties;
		this.resources = resources;
		this.mapper = mapper;
		this.clock = clock;
		this.maxCallTime = maxCallTime;
		client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
			.version(HttpClient.Version.HTTP_1_1).build();
	}

	@Override
	public boolean enabled() {
		return properties.externalEnabled() && !properties.endpoint().isBlank()
			&& !properties.serviceToken().isBlank() && !properties.manifestLocation().isBlank();
	}

	@Override
	public Map<String, Object> manifest() {
		if (!enabled()) {
			return Map.of();
		}
		try (InputStream input = resources.getResource(properties.manifestLocation()).getInputStream()) {
			Map<String, Object> descriptor = mapper.readValue(input, new TypeReference<>() { });
			Object raw = descriptor.get("manifest");
			if (!(raw instanceof Map<?, ?> values) || !values.keySet().equals(MANIFEST_FIELDS)
				|| !(descriptor.get("artifact") instanceof String artifact)) {
				return Map.of();
			}
			for (String field : MANIFEST_FIELDS) {
				if (!(values.get(field) instanceof String text) || text.isBlank() || text.length() > 100) {
					return Map.of();
				}
			}
			UUID.fromString((String)values.get("releaseId"));
			String expected = (String)values.get("contentHash");
			if (!expected.matches("[0-9a-f]{64}")) {
				return Map.of();
			}
			try (InputStream bytes = resources.getResource(artifact).getInputStream()) {
				MessageDigest digest = MessageDigest.getInstance("SHA-256");
				byte[] buffer = new byte[8192];
				int count;
				while ((count = bytes.read(buffer)) != -1) {
					digest.update(buffer, 0, count);
				}
				String actual = HexFormat.of().formatHex(digest.digest());
				if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
					actual.getBytes(StandardCharsets.US_ASCII))) {
					return Map.of();
				}
			}
			return mapper.convertValue(values, new TypeReference<>() { });
		} catch (IOException | NoSuchAlgorithmException | RuntimeException exception) {
			return Map.of();
		}
	}

	@Override
	public Generated generate(long contextId, UUID attemptId, Map<String, Object> manifest, Instant deadlineAt) {
		if (!clock.instant().isBefore(deadlineAt)) {
			throw new GatewayException(true);
		}
		if (!enabled() || manifest.isEmpty() || !manifest.equals(manifest())) {
			throw new GatewayException(false);
		}
		Duration remaining = Duration.between(clock.instant(), deadlineAt);
		Duration budget = remaining.compareTo(maxCallTime) < 0 ? remaining : maxCallTime;
		if (budget.isNegative() || budget.isZero()) {
			throw new GatewayException(true);
		}
		long started = System.nanoTime();
		CompletableFuture<HttpResponse<byte[]>> response = null;
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(properties.endpoint())).timeout(budget)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.serviceToken())
				.header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
				.POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(
					Map.of("contextId", contextId, "attemptId", attemptId, "manifest", manifest)))).build();
			response = client.sendAsync(request, info -> new LimitedBody());
			// The future includes the entire body, not only response headers or an individual socket read.
			long nanos = budget.toNanos() - (System.nanoTime() - started);
			if (nanos <= 0) {
				throw new GatewayException(true);
			}
			HttpResponse<byte[]> received = response.get(nanos, TimeUnit.NANOSECONDS);
			if (received.statusCode() < 200 || received.statusCode() >= 300) {
				throw new GatewayException(received.statusCode() >= 500 || received.statusCode() == 429);
			}
			Generated result = mapper.readValue(received.body(), Generated.class);
			if (!clock.instant().isBefore(deadlineAt) || System.nanoTime() - started >= budget.toNanos()) {
				throw new GatewayException(true);
			}
			return result;
		} catch (TimeoutException exception) {
			throw new GatewayException(true);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new GatewayException(true);
		} catch (ExecutionException exception) {
			Throwable cause = exception.getCause();
			for (Throwable current = cause; current != null; current = current.getCause()) {
				if (current instanceof GatewayException failure) {
					throw failure;
				}
			}
			throw new GatewayException(cause instanceof IOException);
		} catch (GatewayException exception) {
			throw exception;
		} catch (RuntimeException exception) {
			throw new GatewayException(false);
		} finally {
			if (response != null && !response.isDone()) {
				response.cancel(true);
			}
		}
	}

	private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
		// Internal transport guard; the public three-question contract is unchanged.
		private static final int MAX_BYTES = 16 * 1024;
		private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		private final CompletableFuture<byte[]> body = new CompletableFuture<>();
		private Flow.Subscription subscription;

		@Override
		public CompletionStage<byte[]> getBody() {
			return body;
		}

		@Override
		public void onSubscribe(Flow.Subscription incoming) {
			subscription = incoming;
			subscription.request(1);
		}

		@Override
		public void onNext(List<ByteBuffer> buffers) {
			for (ByteBuffer buffer : buffers) {
				if (buffer.remaining() > MAX_BYTES - bytes.size()) {
					body.completeExceptionally(new GatewayException(false));
					subscription.cancel();
					return;
				}
				byte[] chunk = new byte[buffer.remaining()];
				buffer.get(chunk);
				bytes.writeBytes(chunk);
			}
			subscription.request(1);
		}

		@Override
		public void onError(Throwable failure) {
			body.completeExceptionally(failure);
		}

		@Override
		public void onComplete() {
			body.complete(bytes.toByteArray());
		}
	}
}
