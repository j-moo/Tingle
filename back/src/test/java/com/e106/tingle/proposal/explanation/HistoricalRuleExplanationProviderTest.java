package com.e106.tingle.proposal.explanation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.e106.tingle.proposal.explanation.dto.ExplanationStatus;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** 합성 원본 어댑터·classpath reader로 계약 경계를 검증하며 실자료/85 인증/실AI 호출 증거가 아니다. */
class HistoricalRuleExplanationProviderTest {

	private static final String RELEASE = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
	private static final String FINGERPRINT = "b".repeat(64);
	private final JsonMapper mapper = JsonMapper.builder().build();

	@Test
	void verifiedHistoricalSourceProducesExactlyTheFourFieldGatewayInput() {
		Fixture fixture = fixture("original\r\nbytes\n".getBytes(StandardCharsets.UTF_8));
		List<RuleExplanationTransport.Request> calls = new ArrayList<>();
		HistoricalRuleExplanationProvider provider = provider(fixture, fixture.bundle(), request -> {
			calls.add(request);
			return Optional.of(success());
		});
		assertThat(provider.explain(input())).isEqualTo(success());
		assertThat(calls).hasSize(1);
		JsonNode wire = mapper.valueToTree(calls.getFirst());
		assertThat(ExplanationArtifactCatalog.hasFields(wire,
			java.util.Set.of("attemptId", "contextId", "manifest", "contextBundle"))).isTrue();
		assertThat(calls.getFirst().attemptId()).isEqualTo(input().attemptId());
		assertThat(calls.getFirst().contextId()).isEqualTo(17);
		assertThat(calls.getFirst().contextBundle().path("context").path("reference").path("evaluation_ref")
			.asString()).isEqualTo(FINGERPRINT);
	}

	@Test
	void absentHistoricalAdapterOrGatewayCannotCreateAnExplanation() {
		Fixture fixture = fixture(new byte[] {1, 2, 3});
		RuleExplanationTransport forbidden = request -> {
			throw new AssertionError("Missing source must not call gateway");
		};
		HistoricalRuleExplanationProvider absentSource = new HistoricalRuleExplanationProvider(
			(HistoricalExplanationSource)null, fixture.catalog(), forbidden, mapper);
		assertThat(absentSource.explain(input()).status()).isEqualTo("FAILED");
		HistoricalRuleExplanationProvider absentGateway = new HistoricalRuleExplanationProvider(
			ignored -> Optional.empty(), fixture.catalog(), null, mapper);
		assertThat(absentGateway.explain(input()).status()).isEqualTo("FAILED");
		HistoricalRuleExplanationProvider noData = new HistoricalRuleExplanationProvider(
			ignored -> Optional.empty(), fixture.catalog(), forbidden, mapper);
		assertThat(noData.explain(input()).status()).isEqualTo("FAILED");
	}

	@Test
	void anotherViewerHistoricalSourceIsRejectedBeforeTransport() {
		Fixture fixture = fixture(new byte[] {1});
		HistoricalExplanationSource source = ignored -> Optional.of(new HistoricalExplanationSource.Source(
			8, fixture.manifest(), object(fixture.bundle())));
		assertThat(new HistoricalRuleExplanationProvider(source, fixture.catalog(), forbidden(), mapper)
			.explain(input()).status()).isEqualTo("FAILED");
	}

	@Test
	void mismatchedRunFingerprintOrReleaseCannotReachTheGateway() {
		Fixture fixture = fixture(new byte[] {1});
		for (String field : List.of("evaluation_ref", "version_ref")) {
			ObjectNode bundle = fixture.bundle().deepCopy();
			((ObjectNode)bundle.path("context").path("reference")).put(field, "other");
			assertFailed(fixture, bundle);
		}
		ObjectNode wrongId = fixture.bundle().deepCopy();
		((ObjectNode)wrongId.path("context").path("reference")).put("encounter_id", 18);
		assertFailed(fixture, wrongId);
	}

	@Test
	void unapprovedTopicsModelTransferOrMismatchedScopeAreRejected() {
		Fixture fixture = fixture(new byte[] {1});
		ObjectNode topic = fixture.bundle().deepCopy();
		((ObjectNode)topic.path("context").path("evidence").get(0).path("evidence")).put("topic", "identity");
		assertFailed(fixture, topic);
		// 모델로 보낼 근거는 화면 표시 근거 안에 있어야 한다. 밖의 ID·중복·배열 아님은 전송 전에 거절한다.
		ObjectNode model = fixture.bundle().deepCopy();
		((ObjectNode)model.path("scope")).putArray("model_evidence_ids").add("e2");
		assertFailed(fixture, model);
		ObjectNode duplicated = fixture.bundle().deepCopy();
		((ObjectNode)duplicated.path("scope")).putArray("model_evidence_ids").add("e1").add("e1");
		assertFailed(fixture, duplicated);
		ObjectNode notArray = fixture.bundle().deepCopy();
		((ObjectNode)notArray.path("scope")).put("model_evidence_ids", "e1");
		assertFailed(fixture, notArray);
		ObjectNode viewer = fixture.bundle().deepCopy();
		((ObjectNode)viewer.path("scope")).put("viewer", "B");
		assertFailed(fixture, viewer);
	}

	@Test
	void duplicateEvidenceOrDisplayKeysAndUnknownScopesAreRejected() {
		Fixture fixture = fixture(new byte[] {1});
		ObjectNode evidence = fixture.bundle().deepCopy();
		((ObjectNode)evidence.path("context")).putArray("evidence")
			.add(fixture.bundle().path("context").path("evidence").get(0)).add(
				fixture.bundle().path("context").path("evidence").get(0));
		assertFailed(fixture, evidence);
		for (List<String> ids : List.of(List.of("e1", "e1"), List.of("e2"))) {
			ObjectNode bundle = fixture.bundle().deepCopy();
			((ObjectNode)bundle.path("scope")).set("display_evidence_ids", mapper.valueToTree(ids));
			assertFailed(fixture, bundle);
		}
	}

	@Test
	void unknownBundleFieldsOrMissingSourceReferenceFailRatherThanInventingFacts() {
		Fixture fixture = fixture(new byte[] {1});
		ObjectNode extra = fixture.bundle().deepCopy();
		extra.put("currentProfile", "must-not-use");
		assertFailed(fixture, extra);
		ObjectNode source = fixture.bundle().deepCopy();
		((ObjectNode)source.path("context").path("evidence").get(0)).remove("source_ref");
		assertFailed(fixture, source);
	}

	@Test
	void unavailableGatewayOrInvalidPublicResultNeverBecomesFakeRuleSuccess() {
		Fixture fixture = fixture(new byte[] {1});
		assertThat(provider(fixture, fixture.bundle(), ignored -> Optional.empty()).explain(input()).status())
			.isEqualTo("FAILED");
		ExplanationStatus internalKeys = new ExplanationStatus("SUCCEEDED", "RULE_FALLBACK", "규칙 설명",
			List.of("e1"), null);
		assertThat(provider(fixture, fixture.bundle(), ignored -> Optional.of(internalKeys))
			.explain(input()).status()).isEqualTo("FAILED");
	}

	@Test
	void descriptorPinsExactArtifactBytesWithoutNewlineOrJsonNormalization() {
		Fixture fixture = fixture("{\r\n\"a\":1\r\n}".getBytes(StandardCharsets.UTF_8));
		assertThat(fixture.catalog().supports(mapper.valueToTree(fixture.manifest()))).isTrue();
		fixture.resources().put("approved/release.bundle", "{\"a\":1}".getBytes(StandardCharsets.UTF_8));
		assertThat(fixture.catalog().supports(mapper.valueToTree(fixture.manifest()))).isFalse();
	}

	@Test
	void descriptorMutationMissingArtifactAndUnknownReleaseCannotBeRecovered() {
		Fixture fixture = fixture(new byte[] {1});
		byte[] descriptor = fixture.resources().get("approved/descriptor.json");
		fixture.resources().put("approved/descriptor.json", (new String(descriptor, StandardCharsets.UTF_8) + " ")
			.getBytes(StandardCharsets.UTF_8));
		assertThat(fixture.catalog().supports(mapper.valueToTree(fixture.manifest()))).isFalse();
		fixture.resources().put("approved/descriptor.json", descriptor);
		fixture.resources().remove("approved/release.bundle");
		assertThat(fixture.catalog().supports(mapper.valueToTree(fixture.manifest()))).isFalse();
		assertThat(new ExplanationArtifactCatalog(Map.of(), path -> {
			throw new AssertionError("Unknown release must not read a request-derived path");
		}).supports(mapper.valueToTree(fixture.manifest()))).isFalse();
	}

	@Test
	void requestedManifestChangesAndArtifactPathsOutsideTheRegisteredDirectoryAreRejected() {
		Fixture fixture = fixture(new byte[] {1});
		Map<String, Object> changed = new LinkedHashMap<>(fixture.manifest());
		changed.put("matchingVersion", "different");
		assertThat(fixture.catalog().supports(mapper.valueToTree(changed))).isFalse();
		for (String artifact : List.of("../outside", "https://other/file", "/absolute", "dir/file", "dir\\file")) {
			byte[] descriptor = mapper.writeValueAsBytes(Map.of("manifest", fixture.manifest(), "artifact", artifact));
			ExplanationArtifactCatalog catalog = new ExplanationArtifactCatalog(
				Map.of(RELEASE, "approved/descriptor.json"), path -> descriptor);
			assertThat(catalog.supports(mapper.valueToTree(fixture.manifest()))).isFalse();
		}
	}

	@Test
	void canonicalUuidAliasesCannotOverrideEitherRegisteredDescriptor() {
		Fixture fixture = fixture(new byte[] {1});
		fixture.resources().put("second/descriptor.json", fixture.resources().get("approved/descriptor.json"));
		fixture.resources().put("second/release.bundle", fixture.resources().get("approved/release.bundle"));
		for (boolean reversed : List.of(false, true)) {
			Map<String, String> registrations = new LinkedHashMap<>();
			registrations.put(reversed ? RELEASE.toUpperCase(Locale.ROOT) : RELEASE, "approved/descriptor.json");
			registrations.put(reversed ? RELEASE : RELEASE.toUpperCase(Locale.ROOT), "second/descriptor.json");
			ExplanationArtifactCatalog catalog = new ExplanationArtifactCatalog(registrations,
				path -> fixture.resources().get(path));
			assertThat(catalog.supports(mapper.valueToTree(fixture.manifest()))).isFalse();
		}
	}

	@Test
	void descriptorItselfIsRejectedBeforeAnyArtifactHashRead() {
		Fixture fixture = fixture(new byte[] {1});
		byte[] self = mapper.writeValueAsBytes(Map.of("manifest", fixture.manifest(), "artifact", "descriptor.json"));
		List<String> reads = new ArrayList<>();
		ExplanationArtifactCatalog catalog = new ExplanationArtifactCatalog(Map.of(RELEASE, "approved/descriptor.json"),
			path -> {
				reads.add(path);
				return self;
			});
		assertThat(catalog.supports(mapper.valueToTree(fixture.manifest()))).isFalse();
		assertThat(reads).containsExactly("approved/descriptor.json");
	}

	@Test
	void displayedEvidenceMayGoToTheModelAndGeneratedResultsArePublishedWithTheirVersion() {
		Fixture fixture = fixture(new byte[] {1});
		ObjectNode bundle = fixture.bundle().deepCopy();
		((ObjectNode)bundle.path("scope")).putArray("model_evidence_ids").add("e1");
		ExplanationStatus generated = new ExplanationStatus("SUCCEEDED", "GENERATED", "두 분 다 자주 연락하는 게 좋대요.",
			List.of(), "7bc00713".repeat(8));
		assertThat(provider(fixture, bundle, request -> Optional.of(generated)).explain(input()))
			.isEqualTo(generated);
		// 버전이 붙은 규칙 설명은 공개하지 않는다.
		ExplanationStatus versionedRule = new ExplanationStatus("SUCCEEDED", "RULE_FALLBACK", "설명", List.of(),
			"rule-v1");
		assertThat(provider(fixture, bundle, request -> Optional.of(versionedRule)).explain(input()).status())
			.isEqualTo("FAILED");
	}

	private void assertFailed(Fixture fixture, ObjectNode bundle) {
		assertThat(provider(fixture, bundle, forbidden()).explain(input()).status()).isEqualTo("FAILED");
	}

	private HistoricalRuleExplanationProvider provider(Fixture fixture, ObjectNode bundle,
		RuleExplanationTransport transport) {
		HistoricalExplanationSource source = ignored -> Optional.of(new HistoricalExplanationSource.Source(
			7, fixture.manifest(), object(bundle)));
		return new HistoricalRuleExplanationProvider(source, fixture.catalog(), transport, mapper);
	}

	private Fixture fixture(byte[] artifact) {
		Map<String, Object> manifest = new HashMap<>();
		for (String field : ExplanationArtifactCatalog.MANIFEST_FIELDS) {
			manifest.put(field, "synthetic-v1");
		}
		manifest.put("releaseId", RELEASE);
		manifest.put("contentHash", hash(artifact));
		Map<String, byte[]> resources = new HashMap<>();
		resources.put("approved/descriptor.json", mapper.writeValueAsBytes(
			Map.of("manifest", manifest, "artifact", "release.bundle")));
		resources.put("approved/release.bundle", artifact.clone());
		ExplanationArtifactCatalog catalog = new ExplanationArtifactCatalog(Map.of(RELEASE, "approved/descriptor.json"),
			path -> {
				if (!resources.containsKey(path)) {
					throw new IOException("Synthetic resource missing");
				}
				return resources.get(path);
			});
		Map<String, Object> reference = Map.of("encounter_id", 17,
			"evaluation_ref", FINGERPRINT, "version_ref", RELEASE);
		Map<String, Object> evidence = Map.of("id", "e1", "kind", "ONE_WAY_PREFERENCE_FIT", "topic", "contact",
			"direction", "A_TO_B", "fact", "B의 실제 연락 빈도가 A의 허용 범위에 들어간다.");
		ObjectNode bundle = (ObjectNode)mapper.valueToTree(Map.of(
			"context", Map.of("reference", reference, "viewer", "A", "conflicting_source_refs", List.of(),
				"evidence", List.of(Map.of("reference", reference, "source_ref", "synthetic-past-comparison",
					"evidence", evidence))),
			"scope", Map.of("reference", reference, "viewer", "A", "version", "synthetic-approved-policy",
				"model_evidence_ids", List.of(), "display_evidence_ids", List.of("e1"))));
		return new Fixture(catalog, manifest, bundle, resources);
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> object(JsonNode value) {
		return mapper.convertValue(value, Map.class);
	}

	private static RuleExplanationProvider.Input input() {
		return new RuleExplanationProvider.Input(17, 7, UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
			FINGERPRINT, Map.of("historicalEvaluation", "synthetic-verifiable-reference"));
	}

	private static RuleExplanationTransport forbidden() {
		return ignored -> {
			throw new AssertionError("Rejected source must not call gateway");
		};
	}

	private static ExplanationStatus success() {
		return new ExplanationStatus("SUCCEEDED", "RULE_FALLBACK", "상대의 연락 방식이 당신의 선호와 맞아요.",
			List.of(), null);
	}

	private static String hash(byte[] value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
		} catch (NoSuchAlgorithmException exception) {
			throw new AssertionError(exception);
		}
	}

	private record Fixture(ExplanationArtifactCatalog catalog, Map<String, Object> manifest, ObjectNode bundle,
		Map<String, byte[]> resources) {
	}
}
