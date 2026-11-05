package com.e106.tingle.proposal.evaluation;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 확정 판정에 연결된 당시 근거. 공개 HTTP DTO나 현재 입력의 대체물이 아니다. */
public record HistoricalEvaluationEvidence(
	int schemaVersion, long matchingId, Instant metAt, String policyVersion,
	Snapshot left, Snapshot right, Map<String, String> manifest, String scopeVersion,
	List<SourceEvidence> sourceEvidence, Map<String, List<String>> displayEvidenceIdsByViewer,
	Map<String, List<String>> conflictingSourceRefsByViewer
) {
	public static final String STORAGE_KEY = "historicalEvidence";
	private static final Set<String> MANIFEST_FIELDS = Set.of("releaseId", "surveyVersion", "wordingVersion",
		"scoringVersion", "typeModelVersion", "analysisVersion", "typeReferenceVersion", "matchingVersion",
		"productPolicyVersion", "engineVersion", "contentHash");
	private static final Set<String> VIEWERS = Set.of("A", "B");

	public HistoricalEvaluationEvidence {
		if (schemaVersion != 1 || matchingId <= 0 || metAt == null || left == null || right == null
			|| left.userId() == right.userId()) {
			throw invalid();
		}
		metAt = metAt.truncatedTo(ChronoUnit.MICROS);
		requireText(policyVersion, 30);
		requireText(scopeVersion, 200);
		manifest = Map.copyOf(manifest);
		if (!manifest.keySet().equals(MANIFEST_FIELDS)) {
			throw invalid();
		}
		for (var entry : manifest.entrySet()) {
			requireText(entry.getValue(), 100);
		}
		String release = manifest.get("releaseId");
		if (!UUID.fromString(release).toString().equalsIgnoreCase(release)
			|| !manifest.get("contentHash").matches("[a-f0-9]{64}")) {
			throw invalid();
		}
		sourceEvidence = List.copyOf(sourceEvidence);
		if (sourceEvidence.size() > 12
			|| sourceEvidence.stream().map(SourceEvidence::id).distinct().count() != sourceEvidence.size()) {
			throw invalid();
		}
		displayEvidenceIdsByViewer = viewers(displayEvidenceIdsByViewer);
		conflictingSourceRefsByViewer = viewers(conflictingSourceRefsByViewer);
		Set<String> ids = sourceEvidence.stream().map(SourceEvidence::id)
			.collect(java.util.stream.Collectors.toUnmodifiableSet());
		for (List<String> display : displayEvidenceIdsByViewer.values()) {
			if (!ids.containsAll(display)) {
				throw invalid();
			}
		}
	}

	public record Snapshot(long userId, UUID snapshotId, String inputHash, Long settingsVersion,
		String inputSourceRef) {
		/** 기존 원본의 세대·출처를 현재 값으로 채우지 않는다. 이 형태는 입력 lookup에서 미검증이다. */
		public Snapshot(long userId, UUID snapshotId, String inputHash) {
			this(userId, snapshotId, inputHash, null, null);
		}

		public Snapshot {
			if (userId <= 0 || snapshotId == null || inputHash == null || !inputHash.matches("[a-f0-9]{64}")) {
				throw invalid();
			}
			if (settingsVersion != null && settingsVersion < 0) {
				throw invalid();
			}
			if (inputSourceRef != null) {
				requireText(inputSourceRef, 200);
			}
		}

		/** 서버가 소유한 저장 위치 참조이며 UUID의 발급·진위를 증명하지 않는다. */
		public static String sourceRef(long userId, long settingsVersion) {
			if (userId <= 0 || settingsVersion < 0) {
				throw invalid();
			}
			return "settings_snapshot:" + userId + ":" + settingsVersion;
		}
	}

	public record SourceEvidence(String id, String direction, String kind, String topic, String fact,
		String sourceRef) {
		public SourceEvidence {
			if (id == null || !id.matches("e[1-9][0-9]{0,2}")
				|| !Set.of("contact", "planning", "activity", "experience").contains(topic)
				|| !Set.of("MUTUAL_PREFERENCE_FIT", "SHARED_REPORTED_BEHAVIOR", "ONE_WAY_PREFERENCE_FIT",
					"DIFFERENCE").contains(kind)) {
				throw invalid();
			}
			boolean oneWay = "ONE_WAY_PREFERENCE_FIT".equals(kind);
			if (oneWay ? !Set.of("A_TO_B", "B_TO_A").contains(direction) : !"BOTH".equals(direction)) {
				throw invalid();
			}
			requireText(fact, 500);
			requireText(sourceRef, 200);
		}
	}

	private static Map<String, List<String>> viewers(Map<String, List<String>> values) {
		if (values == null || !values.keySet().equals(VIEWERS)) {
			throw invalid();
		}
		Map<String, List<String>> result = Map.of("A", List.copyOf(values.get("A")),
			"B", List.copyOf(values.get("B")));
		for (List<String> refs : result.values()) {
			if (refs.size() > 12 || refs.stream().distinct().count() != refs.size()) {
				throw invalid();
			}
			refs.forEach(value -> requireText(value, 200));
		}
		return result;
	}

	private static void requireText(String value, int maximum) {
		if (value == null || value.isBlank() || value.length() > maximum) {
			throw invalid();
		}
	}

	private static IllegalArgumentException invalid() {
		return new IllegalArgumentException("Invalid historical evaluation evidence");
	}
}
