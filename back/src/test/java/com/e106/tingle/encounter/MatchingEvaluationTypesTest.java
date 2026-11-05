package com.e106.tingle.encounter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.e106.tingle.encounter.MatchingEvaluationTypes.Evaluation;
import com.e106.tingle.encounter.MatchingEvaluationTypes.Request;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

class MatchingEvaluationTypesTest {
	@Test
	void preservesTheExistingFourReferenceWireRequest() {
		JsonNode encoded = MatchingEvaluationFixture.JSON.readTree(
			MatchingEvaluationFixture.JSON.writeValueAsString(MatchingEvaluationFixture.REQUEST));
		assertThat(encoded.size()).isEqualTo(4);
		assertThat(encoded.get("encounterId").isIntegralNumber()).isTrue();
		assertThat(encoded.get("encounterId").longValue()).isEqualTo(1001);
		for (String key : List.of("leftSnapshotId", "rightSnapshotId", "releaseId")) {
			assertThat(encoded.get(key).isString()).isTrue();
			assertThatCode(() -> UUID.fromString(encoded.get(key).asString())).doesNotThrowAnyException();
		}
	}

	@Test
	void acceptsAllRangeBoundariesWithoutInferringThresholdsOrAddingHistoricalEvidence() {
		Evaluation result = MatchingEvaluationTypes.decode(MatchingEvaluationFixture.response(),
			MatchingEvaluationFixture.REQUEST);
		assertThat(result.directionAB()).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(result.directionBA()).isEqualByComparingTo(BigDecimal.valueOf(100));
		assertThat(result.coverageAB()).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(result.coverageBA()).isEqualByComparingTo(BigDecimal.ONE);
		assertThat(result.eligible()).isTrue();
		assertThatThrownBy(() -> result.reasonKeys().add("injected")).isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void preservesExplicitlyNullableScoresAsNullInsteadOfZero() {
		ObjectNode response = MatchingEvaluationFixture.response();
		response.putNull("directionAB");
		response.putNull("directionBA");
		response.putNull("harmonicScore");
		Evaluation result = MatchingEvaluationTypes.decode(response, MatchingEvaluationFixture.REQUEST);
		assertThat(result.directionAB()).isNull();
		assertThat(result.directionBA()).isNull();
		assertThat(result.harmonicScore()).isNull();
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("invalidResponses")
	void rejectsInvalidShapeTypesAndNulls(String name, JsonNode response) {
		assertThatThrownBy(() -> MatchingEvaluationTypes.decode(response, MatchingEvaluationFixture.REQUEST))
			.as(name).isInstanceOf(IllegalArgumentException.class);
	}

	@ParameterizedTest
	@CsvSource({"directionAB,-0.01", "directionAB,100.01", "directionBA,-1", "directionBA,101",
		"harmonicScore,-1", "harmonicScore,101", "coverageAB,-0.001", "coverageAB,1.001",
		"coverageBA,-1", "coverageBA,2"})
	void rejectsOutOfRangeNumbers(String field, String number) {
		ObjectNode response = MatchingEvaluationFixture.response();
		response.set(field, MatchingEvaluationFixture.JSON.readTree(number));
		assertThatThrownBy(() -> MatchingEvaluationTypes.decode(response, MatchingEvaluationFixture.REQUEST))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@ParameterizedTest
	@ValueSource(strings = {"leftSnapshotId", "rightSnapshotId", "releaseId", "encounterId"})
	void rejectsResponsesBoundToAnotherRequest(String field) {
		ObjectNode response = MatchingEvaluationFixture.response();
		if (field.equals("encounterId")) {
			response.put(field, 1002);
		} else {
			ObjectNode target = field.equals("releaseId") ? (ObjectNode)response.get("manifest") : response;
			target.put(field, UUID.randomUUID().toString());
		}
		assertThatThrownBy(() -> MatchingEvaluationTypes.decode(response, MatchingEvaluationFixture.REQUEST))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void invalidRequestReferencesAreRejectedBeforeTheyCanBeSerialized() {
		Request request = MatchingEvaluationFixture.REQUEST;
		assertThatThrownBy(() -> new Request(0, request.leftSnapshotId(), request.rightSnapshotId(),
			request.releaseId()))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new Request(1001, null, request.rightSnapshotId(), request.releaseId()))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new Request(1001, request.leftSnapshotId(), null, request.releaseId()))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new Request(1001, request.leftSnapshotId(), request.rightSnapshotId(), null))
			.isInstanceOf(IllegalArgumentException.class);
	}

	private static Stream<Arguments> invalidResponses() {
		List<Arguments> cases = new ArrayList<>();
		cases.add(Arguments.of("null document", (JsonNode)null));
		cases.add(Arguments.of("array document", MatchingEvaluationFixture.JSON.readTree("[]")));
		for (String field : List.of("encounterId", "manifest", "leftSnapshotId", "rightSnapshotId", "directionAB",
			"directionBA", "coverageAB", "coverageBA", "harmonicScore", "eligible", "excluded", "unresolved",
			"reasonKeys")) {
			ObjectNode missing = MatchingEvaluationFixture.response();
			missing.remove(field);
			cases.add(Arguments.of("missing " + field, missing));
		}
		for (String field : List.of("releaseId", "surveyVersion", "wordingVersion", "scoringVersion",
			"typeModelVersion",
			"analysisVersion", "typeReferenceVersion", "matchingVersion", "productPolicyVersion", "engineVersion",
			"contentHash")) {
			ObjectNode missing = MatchingEvaluationFixture.response();
			((ObjectNode)missing.get("manifest")).remove(field);
			cases.add(Arguments.of("missing manifest " + field, missing));
		}
		for (String field : List.of("encounterId", "manifest", "leftSnapshotId", "rightSnapshotId", "coverageAB",
			"coverageBA", "eligible", "excluded", "unresolved", "reasonKeys")) {
			ObjectNode nulled = MatchingEvaluationFixture.response();
			nulled.putNull(field);
			cases.add(Arguments.of("null " + field, nulled));
		}
		for (String[] item : new String[][] {{"encounterId", "1.5"}, {"encounterId", "0"},
			{"encounterId", "9223372036854775808"}, {"eligible", "1"}, {"eligible", "\"true\""},
			{"directionAB", "\"50\""}, {"coverageBA", "true"}, {"excluded", "{}"},
			{"excluded", "[null]"}, {"unresolved", "[12]"}, {"reasonKeys", "[{}]"},
			{"leftSnapshotId", "\"1-1-1-1-1\""}, {"rightSnapshotId", "15"}}) {
			ObjectNode changed = MatchingEvaluationFixture.response();
			changed.set(item[0], MatchingEvaluationFixture.JSON.readTree(item[1]));
			cases.add(Arguments.of("invalid " + item[0] + " " + item[1], changed));
		}
		for (String field : List.of("historicalEvidence", "source", "extra")) {
			ObjectNode unknown = MatchingEvaluationFixture.response();
			unknown.put(field, "must not be accepted");
			cases.add(Arguments.of("unknown response " + field, unknown));
		}
		for (String[] item : new String[][] {{"releaseId", "\"1-1-1-1-1\""}, {"surveyVersion", "null"},
			{"surveyVersion", "\"\""}, {"matchingVersion", "4"}, {"contentHash", "\"ABCDEF\""},
			{"contentHash", "null"}}) {
			ObjectNode changed = MatchingEvaluationFixture.response();
			((ObjectNode)changed.get("manifest")).set(item[0], MatchingEvaluationFixture.JSON.readTree(item[1]));
			cases.add(Arguments.of("invalid manifest " + item[0] + " " + item[1], changed));
		}
		ObjectNode longVersion = MatchingEvaluationFixture.response();
		((ObjectNode)longVersion.get("manifest")).put("matchingVersion", "v".repeat(101));
		cases.add(Arguments.of("manifest version too long", longVersion));
		ObjectNode unknownManifest = MatchingEvaluationFixture.response();
		((ObjectNode)unknownManifest.get("manifest")).put("extra", "not allowed");
		cases.add(Arguments.of("unknown manifest field", unknownManifest));
		return cases.stream();
	}
}
