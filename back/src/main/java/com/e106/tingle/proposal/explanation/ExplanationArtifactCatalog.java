package com.e106.tingle.proposal.explanation;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 서버가 등록한 고정 descriptor와 같은 디렉터리 artifact의 정확한 원본 bytes를 검증한다. */
@Component
public class ExplanationArtifactCatalog {

	static final Set<String> MANIFEST_FIELDS = Set.of("releaseId", "surveyVersion", "wordingVersion",
		"scoringVersion", "typeModelVersion", "analysisVersion", "typeReferenceVersion", "matchingVersion",
		"productPolicyVersion", "engineVersion", "contentHash");
	private final JsonMapper mapper = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
		.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
	private final Map<String, Registration> registered = new HashMap<>();
	private final ResourceBytes resources;

	@Autowired
	public ExplanationArtifactCatalog(Environment environment) {
		this(Binder.get(environment).bind("app.explanations.descriptors", Bindable.mapOf(String.class, String.class))
			.orElse(Map.of()), resource -> {
				try (InputStream input = new ClassPathResource(resource).getInputStream()) {
					return input.readAllBytes();
				}
			});
	}

	/** 등록 값과 resource reader는 서버 구성 전용이며 요청에서 받지 않는다. */
	public ExplanationArtifactCatalog(Map<String, String> descriptors, ResourceBytes resources) {
		this.resources = resources;
		Set<String> seen = new HashSet<>();
		for (Map.Entry<String, String> entry : descriptors.entrySet()) {
			try {
				String release = UUID.fromString(entry.getKey()).toString();
				if (!seen.add(release)) {
					// 같은 UUID의 표기만 다른 재등록도 모호하므로 양쪽 모두 사용하지 않는다.
					registered.remove(release);
					continue;
				}
				String path = fixedPath(entry.getValue());
				byte[] original = resources.read(path);
				Registration registration = readRegistration(path, original);
				if (release.equals(registration.manifest().path("releaseId").asString())) {
					registered.put(release, registration);
				}
			} catch (IOException | RuntimeException exception) {
				// 등록 실패를 가짜 descriptor로 복구하지 않는다. 원문·경로·개인정보를 로그에 남기지 않는다.
			}
		}
	}

	public boolean supports(JsonNode manifest) {
		try {
			if (!validManifest(manifest)) {
				return false;
			}
			Registration pinned = registered.get(manifest.path("releaseId").asString());
			if (pinned == null || !pinned.manifest().equals(manifest)
				|| !Arrays.equals(pinned.original(), resources.read(pinned.descriptor()))) {
				return false;
			}
			return manifest.path("contentHash").asString().equals(hash(resources.read(pinned.artifact())));
		} catch (IOException | RuntimeException exception) {
			return false;
		}
	}

	private Registration readRegistration(String path, byte[] original) {
		JsonNode descriptor = mapper.readTree(original);
		if (!hasFields(descriptor, Set.of("manifest", "artifact")) || !validManifest(descriptor.get("manifest"))
			|| !descriptor.get("artifact").isString()) {
			throw new IllegalArgumentException("Invalid registered descriptor");
		}
		String artifact = descriptor.get("artifact").asString();
		if (!artifact.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
			throw new IllegalArgumentException("Invalid registered artifact basename");
		}
		String directory = path.substring(0, path.lastIndexOf('/') + 1);
		if (path.equals(directory + artifact)) {
			throw new IllegalArgumentException("Descriptor cannot be its own artifact");
		}
		return new Registration(path, original.clone(), descriptor.get("manifest").deepCopy(), directory + artifact);
	}

	static boolean validManifest(JsonNode manifest) {
		if (!hasFields(manifest, MANIFEST_FIELDS)) {
			return false;
		}
		for (String key : MANIFEST_FIELDS) {
			JsonNode value = manifest.get(key);
			if (!value.isString() || value.asString().isBlank() || value.asString().length() > 100) {
				return false;
			}
		}
		try {
			String release = manifest.get("releaseId").asString();
			return UUID.fromString(release).toString().equals(release)
				&& manifest.get("contentHash").asString().matches("[a-f0-9]{64}");
		} catch (IllegalArgumentException exception) {
			return false;
		}
	}

	static boolean hasFields(JsonNode node, Set<String> fields) {
		if (node == null || !node.isObject()) {
			return false;
		}
		return node.size() == fields.size() && fields.stream().allMatch(node::has);
	}

	private static String fixedPath(String path) {
		if (path == null || !path.matches("[A-Za-z0-9][A-Za-z0-9_./-]{0,300}")) {
			throw new IllegalArgumentException("Invalid fixed resource path");
		}
		for (String part : path.split("/")) {
			if (part.isEmpty() || ".".equals(part) || "..".equals(part)) {
				throw new IllegalArgumentException("Invalid fixed resource segment");
			}
		}
		return path;
	}

	private static String hash(byte[] original) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 unavailable");
		}
	}

	@FunctionalInterface
	public interface ResourceBytes {
		byte[] read(String resource) throws IOException;
	}

	private record Registration(String descriptor, byte[] original, JsonNode manifest, String artifact) {
	}
}
