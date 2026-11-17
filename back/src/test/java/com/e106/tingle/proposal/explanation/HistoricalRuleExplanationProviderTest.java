package com.e106.tingle.proposal.explanation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.e106.tingle.proposal.explanation.dto.ExplanationStatus;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** 합성 원본 어댑터로 계약 경계를 검증하며 실자료/85 인증/실AI 호출 증거가 아니다. */
class HistoricalRuleExplanationProviderTest {

	private static final String RELEASE = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
	private static final String FINGERPRINT = "b".repeat(64);
	private final JsonMapper mapper = JsonMapper.builder().build();

	@Test
	void verifiedHistoricalSourceProducesExactlyTheFourFieldGatewayInput() {
		Fixture fixture = fixture();
		List<RuleExplanationTransport.Request> calls = new ArrayList<>();
		HistoricalRuleExplanationProvider provider = provider(fixture, fixture.bundle(), request -> {
			calls.add(request);
			return Optional.of(success());
		});
		assertThat(provider.explain(input())).isEqualTo(success());
		assertThat(calls).hasSize(1);
		JsonNode wire = mapper.valueToTree(calls.getFirst());
		assertThat(ExplanationManifest.hasFields(wire,
			java.util.Set.of("attemptId", "contextId", "manifest", "contextBundle"))).isTrue();
		assertThat(calls.getFirst().attemptId()).isEqualTo(input().attemptId());
		assertThat(calls.getFirst().contextId()).isEqualTo(17);
		assertThat(calls.getFirst().contextBundle().path("context").path("reference").path("evaluation_ref")
			.asString()).isEqualTo(FINGERPRINT);
	}

	@Test
	void absentHistoricalAdapterOrGatewayCannotCreateAnExplanation() {
		Fixture fixture = fixture();
		RuleExplanationTransport forbidden = request -> {
			throw new AssertionError("Missing source must not call gateway");
		};
		HistoricalRuleExplanationProvider absentSource = new HistoricalRuleExplanationProvider(
			(HistoricalExplanationSource)null, forbidden, mapper);
		assertThat(absentSource.explain(input()).status()).isEqualTo("FAILED");
		HistoricalRuleExplanationProvider absentGateway = new HistoricalRuleExplanationProvider(
			ignored -> Optional.empty(), null, mapper);
		assertThat(absentGateway.explain(input()).status()).isEqualTo("FAILED");
		HistoricalRuleExplanationProvider noData = new HistoricalRuleExplanationProvider(
			ignored -> Optional.empty(), forbidden, mapper);
		assertThat(noData.explain(input()).status()).isEqualTo("FAILED");
	}

	@Test
	void anotherViewerHistoricalSourceIsRejectedBeforeTransport() {
		Fixture fixture = fixture();
		HistoricalExplanationSource source = ignored -> Optional.of(new HistoricalExplanationSource.Source(
			8, fixture.manifest(), object(fixture.bundle())));
		assertThat(new HistoricalRuleExplanationProvider(source, forbidden(), mapper)
			.explain(input()).status()).isEqualTo("FAILED");
	}

	@Test
	void mismatchedRunFingerprintOrReleaseCannotReachTheGateway() {
		Fixture fixture = fixture();
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
		Fixture fixture = fixture();
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
		Fixture fixture = fixture();
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
		Fixture fixture = fixture();
		ObjectNode extra = fixture.bundle().deepCopy();
		extra.put("currentProfile", "must-not-use");
		assertFailed(fixture, extra);
		ObjectNode source = fixture.bundle().deepCopy();
		((ObjectNode)source.path("context").path("evidence").get(0)).remove("source_ref");
		assertFailed(fixture, source);
	}

	@Test
	void unavailableGatewayOrInvalidPublicResultNeverBecomesFakeRuleSuccess() {
		Fixture fixture = fixture();
		assertThat(provider(fixture, fixture.bundle(), ignored -> Optional.empty()).explain(input()).status())
			.isEqualTo("FAILED");
		ExplanationStatus internalKeys = new ExplanationStatus("SUCCEEDED", "RULE_FALLBACK", "규칙 설명",
			List.of("e1"), null);
		assertThat(provider(fixture, fixture.bundle(), ignored -> Optional.of(internalKeys))
			.explain(input()).status()).isEqualTo("FAILED");
	}

	@Test
	void malformedManifestIsRejectedBeforeTransportWithoutAnyRegisteredFile() {
		Fixture fixture = fixture();
		for (String field : List.of("releaseId", "contentHash", "matchingVersion")) {
			Map<String, Object> manifest = new HashMap<>(fixture.manifest());
			manifest.put(field, "releaseId".equals(field) ? "not-a-uuid" : "contentHash".equals(field) ? "short" : " ");
			assertFailed(manifest, fixture.bundle());
		}
		Map<String, Object> missing = new HashMap<>(fixture.manifest());
		missing.remove("engineVersion");
		assertFailed(missing, fixture.bundle());
		Map<String, Object> extra = new HashMap<>(fixture.manifest());
		extra.put("unknown", "value");
		assertFailed(extra, fixture.bundle());
	}

	@Test
	void displayedEvidenceMayGoToTheModelAndGeneratedResultsArePublishedWithTheirVersion() {
		Fixture fixture = fixture();
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
		assertFailed(fixture.manifest(), bundle);
	}

	private void assertFailed(Map<String, Object> manifest, ObjectNode bundle) {
		HistoricalExplanationSource source = ignored -> Optional.of(new HistoricalExplanationSource.Source(
			7, manifest, object(bundle)));
		assertThat(new HistoricalRuleExplanationProvider(source, forbidden(), mapper).explain(input()).status())
			.isEqualTo("FAILED");
	}

	private HistoricalRuleExplanationProvider provider(Fixture fixture, ObjectNode bundle,
		RuleExplanationTransport transport) {
		HistoricalExplanationSource source = ignored -> Optional.of(new HistoricalExplanationSource.Source(
			7, fixture.manifest(), object(bundle)));
		return new HistoricalRuleExplanationProvider(source, transport, mapper);
	}

	private Fixture fixture() {
		Map<String, Object> manifest = new HashMap<>();
		for (String field : ExplanationManifest.MANIFEST_FIELDS) {
			manifest.put(field, "synthetic-v1");
		}
		manifest.put("releaseId", RELEASE);
		manifest.put("contentHash", "c".repeat(64));
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
		return new Fixture(manifest, bundle);
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

	private record Fixture(Map<String, Object> manifest, ObjectNode bundle) {
	}
}
