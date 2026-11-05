package com.e106.tingle.risk;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.e106.tingle.risk.RiskAnalysisTypes.Manifest;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** 명시적으로 설정한 전체 manifest와 같은 디렉터리의 실제 불변 파일만 검증한다. */
@Component
public class RiskManifestCatalog {

	private static final int MAX_DESCRIPTOR_BYTES = 64 * 1024;
	private static final int MAX_ARTIFACT_BYTES = 1024 * 1024;
	private static final Set<String> VERSION_FIELDS = Set.of("surveyVersion", "wordingVersion", "scoringVersion",
		"typeModelVersion", "analysisVersion", "typeReferenceVersion", "matchingVersion", "productPolicyVersion",
		"engineVersion");
	private static final Set<String> MANIFEST_FIELDS = Set.of("releaseId", "surveyVersion", "wordingVersion",
		"scoringVersion", "typeModelVersion", "analysisVersion", "typeReferenceVersion", "matchingVersion",
		"productPolicyVersion", "engineVersion", "contentHash");
	private final RiskReleaseProperties properties;
	private final JsonMapper mapper;

	public RiskManifestCatalog(RiskReleaseProperties properties, JsonMapper mapper) {
		this.properties = properties;
		this.mapper = mapper.rebuild().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
			.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
	}

	public boolean supports(Manifest manifest) {
		return manifest != null && current().filter(manifest::equals).isPresent();
	}

	/** 현재 등록본도 매번 파일과 해시를 검증하며 무효한 설정을 캐시하지 않는다. */
	public Optional<Manifest> current() {
		if (properties.descriptorFile().isBlank()) {
			return Optional.empty();
		}
		try {
			Path configured = Path.of(properties.descriptorFile()).toAbsolutePath().normalize();
			Path parent = configured.getParent().toRealPath();
			Path descriptorFile = parent.resolve(configured.getFileName());
			Map<String, Object> descriptor = mapper.readValue(read(descriptorFile, MAX_DESCRIPTOR_BYTES),
				new TypeReference<>() { });
			if (!descriptor.keySet().equals(Set.of("manifest", "artifact"))
				|| !(descriptor.get("manifest") instanceof Map<?, ?> values) || !valid(values)
				|| !(descriptor.get("artifact") instanceof String artifact)) {
				return Optional.empty();
			}
			Manifest registered = mapper.convertValue(values, Manifest.class);
			Path artifactFile = artifact(parent, artifact);
			if (artifactFile == null) {
				return Optional.empty();
			}
			byte[] bytes = read(artifactFile, MAX_ARTIFACT_BYTES);
			String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
			return MessageDigest.isEqual(actual.getBytes(StandardCharsets.US_ASCII),
				registered.contentHash().getBytes(StandardCharsets.US_ASCII))
				? Optional.of(registered) : Optional.empty();
		} catch (IOException | NoSuchAlgorithmException | RuntimeException exception) {
			// 파일 경로·내용·예외를 로그/응답에 복사하지 않고 미지원으로 처리한다.
			return Optional.empty();
		}
	}

	private boolean valid(Map<?, ?> values) {
		if (!values.keySet().equals(MANIFEST_FIELDS)) {
			return false;
		}
		for (String field : VERSION_FIELDS) {
			if (!(values.get(field) instanceof String version) || version.isBlank() || version.length() > 100) {
				return false;
			}
		}
		if (!(values.get("releaseId") instanceof String release)
			|| !release.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
			return false;
		}
		UUID.fromString(release);
		return values.get("contentHash") instanceof String hash && hash.matches("[0-9a-f]{64}");
	}

	private Path artifact(Path parent, String value) throws IOException {
		if (value.isBlank() || value.contains("\\") || value.contains(":")) {
			return null;
		}
		Path relative = Path.of(value);
		if (relative.isAbsolute()) {
			return null;
		}
		Path file = parent;
		for (Path part : relative) {
			if ("..".equals(part.toString()) || ".".equals(part.toString())) {
				return null;
			}
			file = file.resolve(part);
			if (Files.isSymbolicLink(file)) {
				return null;
			}
		}
		return file.toRealPath().startsWith(parent) ? file : null;
	}

	private byte[] read(Path file, int maximum) throws IOException {
		if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > maximum) {
			throw new IOException("Unusable risk release file");
		}
		try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
			byte[] bytes = input.readNBytes(maximum + 1);
			if (bytes.length > maximum) {
				throw new IOException("Unusable risk release file");
			}
			return bytes;
		}
	}
}
