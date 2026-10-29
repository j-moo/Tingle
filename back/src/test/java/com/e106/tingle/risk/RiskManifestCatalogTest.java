package com.e106.tingle.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.e106.tingle.risk.RiskAnalysisTypes.Manifest;

import tools.jackson.databind.json.JsonMapper;

/** 모든 descriptor·본문·버전은 테스트 전용이며 운영 릴리스 매핑을 의미하지 않는다. */
class RiskManifestCatalogTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final String ARTIFACT = "artifact.json";
	@TempDir
	private Path directory;
	private Map<String, Object> manifest;
	private Manifest requested;
	private Path descriptor;
	private RiskManifestCatalog catalog;

	@BeforeEach
	void syntheticRelease() throws Exception {
		byte[] bytes = "{\"syntheticRiskRules\":true}".getBytes(StandardCharsets.UTF_8);
		Files.write(directory.resolve(ARTIFACT), bytes);
		manifest = new HashMap<>();
		manifest.put("releaseId", "11111111-1111-4111-8111-111111111111");
		for (String field : List.of("surveyVersion", "wordingVersion", "scoringVersion", "typeModelVersion",
			"analysisVersion", "typeReferenceVersion", "matchingVersion", "productPolicyVersion", "engineVersion")) {
			manifest.put(field, "synthetic-v1");
		}
		manifest.put("contentHash", hash(bytes));
		requested = JSON.convertValue(manifest, Manifest.class);
		descriptor = directory.resolve("release.json");
		writeDescriptor(manifest, ARTIFACT);
		catalog = catalog(descriptor.toString());
	}

	@Test
	void acceptsOnlyTheCompleteRegisteredTupleWithTheActualArtifactHash() {
		assertThat(catalog.supports(requested)).isTrue();
		for (String field : manifest.keySet()) {
			Map<String, Object> different = new HashMap<>(manifest);
			different.put(field, field.equals("releaseId") ? UUID.randomUUID().toString()
				: field.equals("contentHash") ? "0".repeat(64) : "synthetic-v2");
			assertThat(catalog.supports(JSON.convertValue(different, Manifest.class))).as(field).isFalse();
		}
		assertThat(catalog.supports(null)).isFalse();
	}

	@Test
	void emptyAndMissingConfigurationNeverApproveAManifest() {
		assertThat(new RiskReleaseProperties(null).descriptorFile()).isEmpty();
		assertThat(catalog(null).supports(requested)).isFalse();
		assertThat(catalog("").supports(requested)).isFalse();
		assertThat(catalog(" ").supports(requested)).isFalse();
		assertThat(catalog(directory.resolve("missing.json").toString()).supports(requested)).isFalse();
		assertThat(catalog(directory.toString()).supports(requested)).isFalse();
	}

	@Test
	void artifactTamperingOrRemovalIsDetectedOnEveryLookup() throws Exception {
		assertThat(catalog.supports(requested)).isTrue();
		Files.writeString(directory.resolve(ARTIFACT), "changed synthetic rules");
		assertThat(catalog.supports(requested)).isFalse();
		Files.delete(directory.resolve(ARTIFACT));
		assertThat(catalog.supports(requested)).isFalse();
	}

	@Test
	void requiresExactlyTheDescriptorAndManifestFieldsAndTheirStringTypes() throws Exception {
		Map<String, Object> withExtra = new HashMap<>(manifest);
		withExtra.put("unapproved", "synthetic");
		writeDescriptor(withExtra, ARTIFACT);
		assertThat(catalog.supports(requested)).isFalse();
		for (String field : manifest.keySet()) {
			Map<String, Object> missing = new HashMap<>(manifest);
			missing.remove(field);
			writeDescriptor(missing, ARTIFACT);
			assertThat(catalog.supports(requested)).as("missing " + field).isFalse();
			Map<String, Object> wrongType = new HashMap<>(manifest);
			wrongType.put(field, 1);
			writeDescriptor(wrongType, ARTIFACT);
			assertThat(catalog.supports(requested)).as("type " + field).isFalse();
		}
		Files.write(descriptor, JSON.writeValueAsBytes(Map.of("manifest", manifest, "artifact", ARTIFACT,
			"unapproved", true)));
		assertThat(catalog.supports(requested)).isFalse();
		Files.write(descriptor, JSON.writeValueAsBytes(Map.of("manifest", manifest)));
		assertThat(catalog.supports(requested)).isFalse();
		Files.write(descriptor, JSON.writeValueAsBytes(Map.of("manifest", manifest, "artifact", 1)));
		assertThat(catalog.supports(requested)).isFalse();
	}

	@Test
	void rejectsBlankOversizedAndNonStringVersionsEvenWhenTheRequestMatches() throws Exception {
		for (String field : manifest.keySet()) {
			if (!field.endsWith("Version")) {
				continue;
			}
			for (String value : List.of("", " ", "v".repeat(101))) {
				Map<String, Object> invalid = new HashMap<>(manifest);
				invalid.put(field, value);
				writeDescriptor(invalid, ARTIFACT);
				assertThat(catalog.supports(JSON.convertValue(invalid, Manifest.class))).as(field).isFalse();
			}
		}
	}

	@Test
	void requiresCanonicalUuidShapeAndLowercaseSha256() throws Exception {
		for (String value : List.of("1-1-1-1-1", "not-a-uuid", "")) {
			Map<String, Object> invalid = new HashMap<>(manifest);
			invalid.put("releaseId", value);
			writeDescriptor(invalid, ARTIFACT);
			assertThat(catalog.supports(requested)).isFalse();
		}
		for (String value : List.of("A".repeat(64), "g".repeat(64), "a".repeat(63), "")) {
			Map<String, Object> invalid = new HashMap<>(manifest);
			invalid.put("contentHash", value);
			writeDescriptor(invalid, ARTIFACT);
			assertThat(catalog.supports(JSON.convertValue(invalid, Manifest.class))).isFalse();
		}
	}

	@Test
	void rejectsMalformedDuplicateAndTrailingJson() throws Exception {
		for (String invalid : List.of("null", "[]", "{", JSON.writeValueAsString(
			Map.of("manifest", manifest, "artifact", ARTIFACT)) + "{}",
			"{\"manifest\":" + JSON.writeValueAsString(manifest)
				+ ",\"artifact\":\"artifact.json\",\"artifact\":\"artifact.json\"}")) {
			Files.writeString(descriptor, invalid);
			assertThat(catalog.supports(requested)).isFalse();
		}
	}

	@Test
	void rejectsTraversalAbsoluteUrlsAndDirectories() throws Exception {
		for (String invalid : List.of("../artifact.json", "nested/../artifact.json", "./artifact.json",
			directory.resolve(ARTIFACT).toString(), "https://synthetic.example/rules", "file:artifact.json",
			"nested\\artifact.json", "", " ", "missing.json")) {
			writeDescriptor(manifest, invalid);
			assertThat(catalog.supports(requested)).as(invalid).isFalse();
		}
		Files.createDirectory(directory.resolve("nested"));
		writeDescriptor(manifest, "nested");
		assertThat(catalog.supports(requested)).isFalse();
	}

	@Test
	void permitsARegularArtifactInASubdirectory() throws Exception {
		Path nested = Files.createDirectory(directory.resolve("nested"));
		Files.copy(directory.resolve(ARTIFACT), nested.resolve(ARTIFACT));
		writeDescriptor(manifest, "nested/artifact.json");
		assertThat(catalog.supports(requested)).isTrue();
	}

	@Test
	void rejectsSymbolicLinksForTheArtifactAndItsDirectoriesWhenSupported() throws Exception {
		Path actual = Files.createDirectory(directory.resolve("actual"));
		Files.copy(directory.resolve(ARTIFACT), actual.resolve(ARTIFACT));
		try {
			Files.createSymbolicLink(directory.resolve("linked.json"), actual.resolve(ARTIFACT));
			Files.createSymbolicLink(directory.resolve("linked-directory"), actual);
		} catch (IOException | UnsupportedOperationException | SecurityException exception) {
			assumeTrue(false, "This filesystem does not allow this test user to create symbolic links");
		}
		writeDescriptor(manifest, "linked.json");
		assertThat(catalog.supports(requested)).isFalse();
		writeDescriptor(manifest, "linked-directory/artifact.json");
		assertThat(catalog.supports(requested)).isFalse();
	}

	@Test
	void boundsDescriptorAndArtifactReadsBeforeApprovingTheirContent() throws Exception {
		String original = Files.readString(descriptor);
		Files.writeString(descriptor, " ".repeat(64 * 1024) + original);
		assertThat(catalog.supports(requested)).isFalse();
		byte[] oversized = new byte[1024 * 1024 + 1];
		Files.write(directory.resolve(ARTIFACT), oversized);
		manifest.put("contentHash", hash(oversized));
		writeDescriptor(manifest, ARTIFACT);
		assertThat(catalog.supports(JSON.convertValue(manifest, Manifest.class))).isFalse();
	}

	private void writeDescriptor(Map<String, Object> values, String artifact) throws IOException {
		Files.write(descriptor, JSON.writeValueAsBytes(Map.of("manifest", values, "artifact", artifact)));
	}

	private RiskManifestCatalog catalog(String file) {
		return new RiskManifestCatalog(new RiskReleaseProperties(file), JSON);
	}

	private static String hash(byte[] bytes) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
	}
}
