package com.e106.tingle.global.ai;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.stereotype.Component;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** 허용된 AI 주소와 경로에만 새 서비스 토큰을 보낸다. 재시도 예산은 도메인이 소유한다. */
@Component
public class AiInternalClient {

	private static final Set<String> PATHS = Set.of("/internal/ai/personal-analyses", "/internal/ai/explanations",
		"/internal/ai/icebreakers", "/internal/ai/risk-detections");
	private static final int MAX_RESPONSE_BYTES = 1_048_576;
	private final AiServiceTokenProvider tokens;
	private final JsonMapper json;
	private final URI base;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
		.version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER).build();

	public AiInternalClient(AiServiceProperties properties, AiServiceTokenProvider tokens, JsonMapper json) {
		this.tokens = tokens;
		this.json = json.rebuild().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
			.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
		this.base = address(properties);
	}

	public void requireAvailable() {
		tokens.requireAvailable();
		if (base == null) {
			throw unavailable();
		}
	}

	public <T> T post(String path, Object body, Class<T> responseType, UUID requestId) {
		return post(path, body, responseType, requestId, Duration.ofSeconds(10), MAX_RESPONSE_BYTES);
	}

	/** 호출자의 남은 전체 기한을 인증 갱신과 본문 수신에도 동일하게 적용한다. */
	public <T> T post(String path, Object body, Class<T> responseType, UUID requestId,
		Duration budget, int maxResponseBytes) {
		try {
			requireAvailable();
			if (path == null || !PATHS.contains(path) || body == null || responseType == null
				|| budget == null || budget.isNegative() || budget.isZero()
				|| maxResponseBytes < 1 || maxResponseBytes > MAX_RESPONSE_BYTES) {
				throw new AiCallException(false);
			}
			Duration bounded = budget.compareTo(Duration.ofSeconds(10)) > 0 ? Duration.ofSeconds(10) : budget;
			long deadline = System.nanoTime() + bounded.toNanos();
			UUID trace = requestId == null ? UUID.randomUUID() : requestId;
			String serialized = json.writeValueAsString(body);
			for (int attempt = 0; attempt < 2; attempt++) {
				HttpResponse<byte[]> response = send(path, serialized, trace, deadline, maxResponseBytes);
				if (attempt == 0 && expired(response)) {
					continue;
				}
				if (response.statusCode() < 200 || response.statusCode() >= 300) {
					throw new AiCallException(response.statusCode() >= 500 || response.statusCode() == 429,
						response.statusCode());
				}
				T result = json.readValue(response.body(), responseType);
				remaining(deadline);
				if (result == null) {
					throw new AiCallException(false);
				}
				return result;
			}
			throw new AiCallException(false);
		} catch (AiCallException exception) {
			throw exception;
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new AiCallException(true);
		} catch (TimeoutException | IOException exception) {
			throw new AiCallException(true);
		} catch (ExecutionException exception) {
			for (Throwable cause = exception.getCause(); cause != null; cause = cause.getCause()) {
				if (cause instanceof AiCallException failure) {
					throw failure;
				}
			}
			throw new AiCallException(exception.getCause() instanceof IOException);
		} catch (Exception exception) {
			throw new AiCallException(false);
		}
	}

	private HttpResponse<byte[]> send(String path, String body, UUID trace, long deadline,
		int maxResponseBytes) throws Exception {
		remaining(deadline);
		String token = tokens.issue();
		HttpRequest request = HttpRequest.newBuilder(base.resolve(path))
			.timeout(Duration.ofNanos(remaining(deadline))).header("Content-Type", "application/json")
			.header("Accept", "application/json").header("Authorization", "Bearer " + token)
			.header("X-Request-Id", trace.toString())
			.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
		var pending = http.sendAsync(request, info -> new BoundedBody(maxResponseBytes));
		try {
			return pending.get(remaining(deadline), TimeUnit.NANOSECONDS);
		} finally {
			if (!pending.isDone()) {
				pending.cancel(true);
			}
		}
	}

	private boolean expired(HttpResponse<byte[]> response) {
		if (response.statusCode() != 401 || response.body().length > 65_536) {
			return false;
		}
		try {
			var parsed = json.readTree(response.body());
			return parsed != null && parsed.isObject() && parsed.path("code").isString()
				&& "ACCESS_TOKEN_EXPIRED".equals(parsed.path("code").asText());
		} catch (RuntimeException exception) {
			return false;
		}
	}

	private long remaining(long deadline) {
		long nanos = deadline - System.nanoTime();
		if (nanos <= 0) {
			throw new AiCallException(true);
		}
		return nanos;
	}

	private static URI address(AiServiceProperties properties) {
		try {
			URI uri = URI.create(properties.baseUrl());
			boolean loopback = Set.of("localhost", "127.0.0.1", "::1", "[::1]").contains(uri.getHost());
			boolean local = Set.of("local", "test").contains(properties.environment());
			if (!properties.enabled() || uri.getHost() == null || uri.getRawUserInfo() != null
				|| uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getPort() == 0
				|| uri.getPort() > 65_535 || !(uri.getRawPath().isEmpty() || "/".equals(uri.getRawPath()))
				|| !("https".equals(uri.getScheme()) || (local && loopback && "http".equals(uri.getScheme())))) {
				return null;
			}
			return uri;
		} catch (RuntimeException exception) {
			return null;
		}
	}

	private static BusinessException unavailable() {
		return new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
	}

	private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {

		private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
		private Flow.Subscription subscription;
		private long received;
		private final int limit;

		private BoundedBody(int limit) {
			this.limit = limit;
		}

		@Override
		public CompletionStage<byte[]> getBody() {
			return delegate.getBody();
		}

		@Override
		public void onSubscribe(Flow.Subscription value) {
			subscription = value;
			delegate.onSubscribe(value);
		}

		@Override
		public void onNext(List<ByteBuffer> buffers) {
			for (ByteBuffer buffer : buffers) {
				received += buffer.remaining();
				if (received > limit) {
					subscription.cancel();
					delegate.onError(new AiCallException(false));
					return;
				}
			}
			delegate.onNext(buffers);
		}

		@Override
		public void onError(Throwable error) {
			delegate.onError(error);
		}

		@Override
		public void onComplete() {
			delegate.onComplete();
		}
	}
}
