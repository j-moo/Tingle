package com.e106.tingle.icebreaker;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import com.e106.tingle.global.ai.AiCallException;
import com.e106.tingle.global.ai.AiInternalClient;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** 공통 서비스 인증을 사용하며 명시적 활성화와 검증된 불변 manifest를 요구한다. */
@Component
public class HttpIcebreakerGateway implements IcebreakerGateway {
	private static final Set<String> MANIFEST_FIELDS = Set.of("releaseId", "surveyVersion", "wordingVersion",
		"scoringVersion", "typeModelVersion", "analysisVersion", "typeReferenceVersion", "matchingVersion",
		"productPolicyVersion", "engineVersion", "contentHash");
	private final IcebreakerProperties properties;
	private final ResourceLoader resources;
	private final ObjectMapper mapper;
	private final AiInternalClient client;
	private final Clock clock;
	private final Duration maxCallTime;

	@Autowired
	public HttpIcebreakerGateway(IcebreakerProperties properties, ResourceLoader resources, ObjectMapper mapper,
		Clock clock, AiInternalClient client) {
		this(properties, resources, mapper, clock, client, Duration.ofSeconds(10));
	}

	HttpIcebreakerGateway(IcebreakerProperties properties, ResourceLoader resources, ObjectMapper mapper,
		Clock clock, AiInternalClient client, Duration maxCallTime) {
		this.properties = properties;
		this.resources = resources;
		this.mapper = mapper;
		this.clock = clock;
		this.client = client;
		this.maxCallTime = maxCallTime;
	}

	@Override
	public boolean enabled() {
		if (!properties.externalEnabled() || properties.manifestLocation().isBlank()) {
			return false;
		}
		try {
			client.requireAvailable();
			return true;
		} catch (RuntimeException exception) {
			return false;
		}
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
			String release = (String)values.get("releaseId");
			if (!UUID.fromString(release).toString().equals(release)) {
				return Map.of();
			}
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
		return generate(contextId, attemptId, manifest, deadlineAt, null);
	}

	@Override
	public Generated generate(long contextId, UUID attemptId, Map<String, Object> manifest, Instant deadlineAt,
		String commonTopicKey) {
		if (commonTopicKey != null && !IcebreakerTopics.supportedKey(commonTopicKey)) {
			throw new GatewayException(false);
		}
		if (contextId <= 0 || attemptId == null || deadlineAt == null || !enabled()
			|| manifest == null || manifest.isEmpty() || !manifest.equals(manifest())) {
			throw new GatewayException(false);
		}
		Duration remaining = Duration.between(clock.instant(), deadlineAt);
		Duration budget = remaining.compareTo(maxCallTime) < 0 ? remaining : maxCallTime;
		if (budget.isNegative() || budget.isZero()) {
			throw new GatewayException(true);
		}
		try {
			Map<String, Object> request = new HashMap<>(Map.of("contextId", contextId, "attemptId", attemptId,
				"manifest", manifest));
			if (commonTopicKey != null) {
				request.put("commonTopicKey", commonTopicKey);
			}
			Generated result = client.post("/internal/ai/icebreakers",
				request,
				Generated.class, attemptId, budget, 16 * 1024);
			if (!clock.instant().isBefore(deadlineAt)) {
				throw new GatewayException(true);
			}
			return result;
		} catch (AiCallException exception) {
			throw new GatewayException(!Integer.valueOf(503).equals(exception.httpStatus()) && exception.retryable());
		}
	}
}
