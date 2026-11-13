package com.e106.tingle.proposal.explanation.dto;

import static org.assertj.core.api.Assertions.*;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ExplanationDtoTest {
	@Test
	void retainsExactlyThePublicTaskAndStatusFields() {
		assertThat(ExplanationTask.class.getRecordComponents()).extracting(RecordComponent::getName)
			.containsExactly("proposalId", "kind", "status", "statusUrl", "errorCode", "retryable", "pollAfterSeconds");
		assertThat(ExplanationStatus.class.getRecordComponents()).extracting(RecordComponent::getName)
			.containsExactly("status", "source", "text", "evidenceKeys", "promptVersion");
		ExplanationTask task = new ExplanationTask(17, "MATCH_EXPLANATION", "FAILED",
			"/api/match-proposals/17/explanation", "EXPLANATION_UNAVAILABLE", false, 0);
		assertThat(task.proposalId()).isEqualTo(17);
		assertThat(task.errorCode()).isEqualTo("EXPLANATION_UNAVAILABLE");
		assertThat(task.retryable()).isFalse();
	}

	@Test
	void rejectsInvalidTaskIdentityStateRouteAndPollingInterval() {
		assertThatThrownBy(() -> task(0, "MATCH_EXPLANATION", "PENDING", "/api/match-proposals/0/explanation", 1))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> task(17, "ICEBREAKERS", "PENDING", "/api/match-proposals/17/explanation", 1))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> task(17, "MATCH_EXPLANATION", "READY", "/api/match-proposals/17/explanation", 1))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> task(17, "MATCH_EXPLANATION", "PENDING", "/api/match-proposals/18/explanation", 1))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> task(17, "MATCH_EXPLANATION", "PENDING", "/api/match-proposals/17/explanation", -1))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void acceptsVerifiedRuleContentWithEmptyPublicEvidenceKeys() {
		ExplanationStatus result = new ExplanationStatus("SUCCEEDED", "RULE_FALLBACK", "검증된 규칙 설명",
			List.of(), "rule-v1");
		assertThat(result.status()).isEqualTo("SUCCEEDED");
		assertThat(result.source()).isEqualTo("RULE_FALLBACK");
		assertThat(result.text()).isEqualTo("검증된 규칙 설명");
		assertThat(result.promptVersion()).isEqualTo("rule-v1");
		assertThat(result.evidenceKeys()).isEmpty();
	}

	@Test
	void acceptsRuleFallbackWithoutPromptVersionAndRetainsRequiredNullJsonFields() {
		ExplanationStatus result = new ExplanationStatus("SUCCEEDED", "RULE_FALLBACK", "검증된 규칙 설명",
			List.of(), null);
		assertThat(result.promptVersion()).isNull();
		JsonMapper mapper = JsonMapper.builder().build();
		JsonNode status = mapper.readTree(mapper.writeValueAsString(result));
		assertThat(status.size()).isEqualTo(5);
		assertThat(status.has("promptVersion")).isTrue();
		assertThat(status.get("promptVersion").isNull()).isTrue();
		ExplanationTask task = task(17, "MATCH_EXPLANATION", "SUCCEEDED", "/api/match-proposals/17/explanation", 0);
		JsonNode request = mapper.readTree(mapper.writeValueAsString(task));
		assertThat(request.size()).isEqualTo(7);
		assertThat(request.has("errorCode")).isTrue();
		assertThat(request.get("errorCode").isNull()).isTrue();
	}

	@Test
	void rejectsGeneratedUnknownOrMissingSuccessfulSources() {
		for (String source : new String[] {"GENERATED", "OTHER", "", null}) {
			assertThatThrownBy(() -> new ExplanationStatus("SUCCEEDED", source, "설명", List.of(), "rule-v1"))
				.isInstanceOf(IllegalArgumentException.class);
		}
	}

	@Test
	void acceptsGeneratedContentOnlyWithAHashPromptVersion() {
		ExplanationStatus result = new ExplanationStatus("SUCCEEDED", "GENERATED", "생성된 설명", List.of(),
			"a".repeat(64));
		assertThat(result.source()).isEqualTo("GENERATED");
		for (String version : new String[] {null, "", "rule-v1", "A".repeat(64), "a".repeat(63)}) {
			assertThatThrownBy(() -> new ExplanationStatus("SUCCEEDED", "GENERATED", "설명", List.of(), version))
				.isInstanceOf(IllegalArgumentException.class);
		}
		assertThatThrownBy(() -> new ExplanationStatus("SUCCEEDED", "OTHER", "설명", List.of(), null))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rejectsMissingOrBlankSuccessfulTextAndInvalidVersion() {
		for (String text : new String[] {null, "", " \n\t"}) {
			assertThatThrownBy(() -> new ExplanationStatus("SUCCEEDED", "RULE_FALLBACK", text, List.of(), "rule-v1"))
				.isInstanceOf(IllegalArgumentException.class);
		}
		for (String version : new String[] {"", " \n\t", "v".repeat(101)}) {
			assertThatThrownBy(() -> new ExplanationStatus("SUCCEEDED", "RULE_FALLBACK", "설명", List.of(), version))
				.isInstanceOf(IllegalArgumentException.class);
		}
	}

	@Test
	void keepsPendingAndFailedResultsFreeOfSuccessfulContent() {
		for (String status : List.of("PENDING", "FAILED")) {
			ExplanationStatus result = new ExplanationStatus(status, null, null, List.of(), null);
			assertThat(result.source()).isNull();
			assertThat(result.text()).isNull();
			assertThat(result.promptVersion()).isNull();
			assertThat(result.evidenceKeys()).isEmpty();
			assertThatThrownBy(() -> new ExplanationStatus(status, "RULE_FALLBACK", null, List.of(), null))
				.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> new ExplanationStatus(status, null, "설명", List.of(), null))
				.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> new ExplanationStatus(status, null, null, List.of(), "rule-v1"))
				.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> new ExplanationStatus(status, null, null, List.of("public-key"), null))
				.isInstanceOf(IllegalArgumentException.class);
		}
	}

	@Test
	void rejectsUnknownStateAndMissingEvidenceCollection() {
		assertThatThrownBy(() -> new ExplanationStatus("READY", null, null, List.of(), null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ExplanationStatus(null, null, null, List.of(), null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ExplanationStatus("PENDING", null, null, null, null))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void copiesEvidenceKeysAndExposesAnImmutableCollection() {
		List<String> keys = new ArrayList<>(List.of("approved-key"));
		ExplanationStatus result = new ExplanationStatus("SUCCEEDED", "RULE_FALLBACK", "설명", keys, "rule-v1");
		keys.clear();
		assertThat(result.evidenceKeys()).containsExactly("approved-key");
		assertThatThrownBy(() -> result.evidenceKeys().add("another-key"))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	private static ExplanationTask task(long proposalId, String kind, String status, String statusUrl, int pollAfter) {
		return new ExplanationTask(proposalId, kind, status, statusUrl, null, false, pollAfter);
	}
}
