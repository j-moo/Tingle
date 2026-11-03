package com.e106.tingle.encounter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

/** Existing four-reference request and thirteen-field response. This is not an execution approval. */
public final class MatchingEvaluationTypes {
	private static final Set<String> RESPONSE_FIELDS = Set.of("encounterId", "manifest", "leftSnapshotId",
		"rightSnapshotId", "directionAB", "directionBA", "coverageAB", "coverageBA", "harmonicScore", "eligible",
		"excluded", "unresolved", "reasonKeys");
	private static final Set<String> MANIFEST_FIELDS = Set.of("releaseId", "surveyVersion", "wordingVersion",
		"scoringVersion", "typeModelVersion", "analysisVersion", "typeReferenceVersion", "matchingVersion",
		"productPolicyVersion", "engineVersion", "contentHash");

	private MatchingEvaluationTypes() {
	}

	public record Request(long encounterId, UUID leftSnapshotId, UUID rightSnapshotId, UUID releaseId) {
		public Request {
			if (encounterId <= 0 || leftSnapshotId == null || rightSnapshotId == null || releaseId == null) {
				throw invalid();
			}
		}
	}

	public record Manifest(UUID releaseId, String surveyVersion, String wordingVersion, String scoringVersion,
		String typeModelVersion, String analysisVersion, String typeReferenceVersion, String matchingVersion,
		String productPolicyVersion, String engineVersion, String contentHash) {
		public Manifest {
			if (releaseId == null || contentHash == null || !contentHash.matches("[a-f0-9]{64}")) {
				throw invalid();
			}
			for (String version : new String[] {surveyVersion, wordingVersion, scoringVersion, typeModelVersion,
				analysisVersion, typeReferenceVersion, matchingVersion, productPolicyVersion, engineVersion}) {
				requireVersion(version);
			}
		}
	}

	public record Evaluation(long encounterId, Manifest manifest, UUID leftSnapshotId, UUID rightSnapshotId,
		BigDecimal directionAB, BigDecimal directionBA, BigDecimal coverageAB, BigDecimal coverageBA,
		BigDecimal harmonicScore, boolean eligible, List<String> excluded, List<String> unresolved,
		List<String> reasonKeys) {
		public Evaluation {
			if (encounterId <= 0 || manifest == null || leftSnapshotId == null || rightSnapshotId == null) {
				throw invalid();
			}
			requireRange(directionAB, 100, true);
			requireRange(directionBA, 100, true);
			requireRange(coverageAB, 1, false);
			requireRange(coverageBA, 1, false);
			requireRange(harmonicScore, 100, true);
			excluded = immutableStrings(excluded);
			unresolved = immutableStrings(unresolved);
			reasonKeys = immutableStrings(reasonKeys);
		}
	}

	/** Validates shape and request binding only; no thresholds, provenance or historical evidence are inferred. */
	public static Evaluation decode(JsonNode response, Request request) {
		if (request == null || !fields(response, RESPONSE_FIELDS)) {
			throw invalid();
		}
		JsonNode manifest = response.get("manifest");
		if (!fields(manifest, MANIFEST_FIELDS)) {
			throw invalid();
		}
		Manifest versions = new Manifest(uuid(manifest.get("releaseId")), text(manifest.get("surveyVersion")),
			text(manifest.get("wordingVersion")), text(manifest.get("scoringVersion")),
			text(manifest.get("typeModelVersion")), text(manifest.get("analysisVersion")),
			text(manifest.get("typeReferenceVersion")), text(manifest.get("matchingVersion")),
			text(manifest.get("productPolicyVersion")), text(manifest.get("engineVersion")),
			text(manifest.get("contentHash")));
		JsonNode id = response.get("encounterId");
		JsonNode eligible = response.get("eligible");
		if (!id.isIntegralNumber() || !id.canConvertToLong() || !eligible.isBoolean()) {
			throw invalid();
		}
		Evaluation result = new Evaluation(id.longValue(), versions, uuid(response.get("leftSnapshotId")),
			uuid(response.get("rightSnapshotId")), number(response.get("directionAB")),
			number(response.get("directionBA")), number(response.get("coverageAB")),
			number(response.get("coverageBA")), number(response.get("harmonicScore")), eligible.booleanValue(),
			strings(response.get("excluded")), strings(response.get("unresolved")),
			strings(response.get("reasonKeys")));
		if (result.encounterId() != request.encounterId() || !result.leftSnapshotId().equals(request.leftSnapshotId())
			|| !result.rightSnapshotId().equals(request.rightSnapshotId())
			|| !result.manifest().releaseId().equals(request.releaseId())) {
			throw invalid();
		}
		return result;
	}

	private static boolean fields(JsonNode node, Set<String> names) {
		return node != null && node.isObject() && node.size() == names.size() && names.stream().allMatch(node::has);
	}

	private static String text(JsonNode node) {
		if (node == null || !node.isString()) {
			throw invalid();
		}
		return node.asString();
	}

	private static UUID uuid(JsonNode node) {
		String value = text(node);
		UUID parsed = UUID.fromString(value);
		if (!parsed.toString().equalsIgnoreCase(value)) {
			throw invalid();
		}
		return parsed;
	}

	private static BigDecimal number(JsonNode node) {
		if (node != null && node.isNull()) {
			return null;
		}
		if (node == null || !node.isNumber()) {
			throw invalid();
		}
		return node.decimalValue();
	}

	private static List<String> strings(JsonNode node) {
		if (node == null || !node.isArray()) {
			throw invalid();
		}
		List<String> values = new ArrayList<>();
		for (JsonNode value : node) {
			values.add(text(value));
		}
		return values;
	}

	private static List<String> immutableStrings(List<String> values) {
		if (values == null || values.stream().anyMatch(java.util.Objects::isNull)) {
			throw invalid();
		}
		return List.copyOf(values);
	}

	private static void requireVersion(String value) {
		if (value == null || value.isEmpty() || value.codePointCount(0, value.length()) > 100) {
			throw invalid();
		}
	}

	private static void requireRange(BigDecimal value, int maximum, boolean nullable) {
		if (value == null ? !nullable : value.signum() < 0 || value.compareTo(BigDecimal.valueOf(maximum)) > 0) {
			throw invalid();
		}
	}

	private static IllegalArgumentException invalid() {
		return new IllegalArgumentException("Invalid matching evaluation contract");
	}
}
