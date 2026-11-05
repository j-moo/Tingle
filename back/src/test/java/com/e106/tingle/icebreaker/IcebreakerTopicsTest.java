package com.e106.tingle.icebreaker;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import com.e106.tingle.survey.SurveyCatalog;
import com.e106.tingle.survey.SurveyCatalog.SurveyDefinition;

import tools.jackson.databind.json.JsonMapper;

class IcebreakerTopicsTest {

	private final JsonMapper mapper = new JsonMapper();
	private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
	private final SurveyCatalog catalog = mock(SurveyCatalog.class);
	private final List<ResultSet> rows = new ArrayList<>();
	private final IcebreakerTopics topics = new IcebreakerTopics(jdbc, catalog, mapper);

	@BeforeEach
	void prepare() throws Exception {
		SurveyCatalog actual = new SurveyCatalog(mapper, "SIS-Q-1.1-100");
		given(catalog.find("SIS-Q-1.1-100")).willReturn(actual.find("SIS-Q-1.1-100"));
		given(jdbc.query(anyString(), any(RowMapper.class), eq("ICEBREAKER_TOPIC"), eq(7L)))
			.willAnswer(invocation -> {
				RowMapper<?> rowMapper = invocation.getArgument(1);
				List<Object> result = new ArrayList<>();
				for (int index = 0; index < rows.size(); index++) {
					result.add(rowMapper.mapRow(rows.get(index), index));
				}
				return result;
			});
		rows.add(participant(11, "[\"talk\",\"rest\"]"));
		rows.add(participant(22, "[\"rest\",\"talk\"]"));
	}

	@Test
	void picksFirstOfficialCommonCodeRegardlessOfAnswerOrderAndKeepsEvidence() {
		IcebreakerTopics.Selection selected = topics.resolve(7).orElseThrow();
		assertThat(selected.key()).isEqualTo("rest");
		assertThat(selected.participants()).containsExactly(
			new IcebreakerTopics.Evidence(11, 111, 211, "SIS-Q-1.1-100"),
			new IcebreakerTopics.Evidence(22, 122, 222, "SIS-Q-1.1-100"));
	}

	@Test
	void followsTheFullDeterministicPriorityOrder() throws Exception {
		List<String> keys = List.of("rest", "talk", "culture", "walk", "active");
		for (int first = 0; first < keys.size(); first++) {
			for (int second = first + 1; second < keys.size(); second++) {
				String answer = mapper.writeValueAsString(List.of(keys.get(second), keys.get(first)));
				given(rows.getFirst().getString("answer_value")).willReturn(answer);
				given(rows.getLast().getString("answer_value")).willReturn(answer);
				assertThat(topics.resolve(7).orElseThrow().key()).isEqualTo(keys.get(first));
			}
		}
	}

	@Test
	void supportsOnlyExactOfficialCodes() {
		for (String key : List.of("rest", "talk", "culture", "walk", "active")) {
			assertThat(IcebreakerTopics.supportedKey(key)).isTrue();
		}
		for (String key : List.of("REST", "unknown", " rest", "rest ", "", "coffee")) {
			assertThat(IcebreakerTopics.supportedKey(key)).isFalse();
		}
		assertThat(IcebreakerTopics.supportedKey(null)).isFalse();
	}

	@Test
	void noIntersectionProducesNoTopic() throws Exception {
		given(rows.getLast().getString("answer_value")).willReturn("[\"walk\",\"active\"]");
		assertThat(topics.resolve(7)).isEmpty();
	}

	@Test
	void rejectsInvalidUnknownMissingOrAmbiguouslyTypedAnswers() throws Exception {
		for (String value : List.of("unknown", "\"unknown\"", "null", "[]", "{}", "[1]", "[\"unknown\"]",
			"[\"rest\",\"rest\"]", "[\"rest\",\"talk\",\"walk\"]", "[\"rest\",\"invalid\"]")) {
			given(rows.getFirst().getString("answer_value")).willReturn(value);
			assertThat(topics.resolve(7)).as(value).isEmpty();
		}
		given(rows.getFirst().getString("answer_value")).willReturn(null);
		assertThat(topics.resolve(7)).isEmpty();
		given(rows.getFirst().getString("answer_value")).willReturn("[\"rest\"]");
		given(rows.getFirst().getString("answer_type")).willReturn("STRING");
		assertThat(topics.resolve(7)).isEmpty();
	}

	@Test
	void missingWithdrawnOrDifferentVersionConsentProducesNoTopic() throws Exception {
		given(rows.getFirst().getBoolean("agreed")).willReturn(false);
		assertThat(topics.resolve(7)).isEmpty();
		given(rows.getFirst().getBoolean("agreed")).willReturn(true);
		given(rows.getFirst().getLong("consent_id")).willReturn(0L);
		assertThat(topics.resolve(7)).isEmpty();
		given(rows.getFirst().getLong("consent_id")).willReturn(111L);
		given(rows.getFirst().getString("document_version")).willReturn("older-consent");
		assertThat(topics.resolve(7)).isEmpty();
	}

	@Test
	void unsupportedMissingOrChangedSurveyDefinitionProducesNoTopic() throws Exception {
		given(rows.getFirst().getString("survey_version")).willReturn("SIS-Q-unknown");
		assertThat(topics.resolve(7)).isEmpty();
		given(rows.getFirst().getString("survey_version")).willReturn("SIS-Q-1.1-100");
		given(catalog.find("SIS-Q-1.1-100")).willReturn(Optional.empty());
		assertThat(topics.resolve(7)).isEmpty();
		SurveyDefinition changed = new SurveyDefinition("SIS-Q-1.1-100", "wording", "hash",
			List.of(mapper.readTree("{\"questionId\":\"Q040\",\"responseSchema\":{\"const\":\"rest\"}}")),
			List.of("Q040"));
		given(catalog.find("SIS-Q-1.1-100")).willReturn(Optional.of(changed));
		assertThat(topics.resolve(7)).isEmpty();
	}

	@Test
	void requiresExactlyTwoDistinctParticipants() throws Exception {
		rows.add(participant(33, "[\"rest\"]"));
		assertThat(topics.resolve(7)).isEmpty();
		rows.removeLast();
		given(rows.getLast().getLong("user_id")).willReturn(11L);
		assertThat(topics.resolve(7)).isEmpty();
		rows.removeLast();
		assertThat(topics.resolve(7)).isEmpty();
		rows.clear();
		assertThat(topics.resolve(7)).isEmpty();
	}

	@Test
	void reconsentProducesDifferentEvidenceEvenWithTheSameAnswer() throws Exception {
		IcebreakerTopics.Selection before = topics.resolve(7).orElseThrow();
		given(rows.getLast().getLong("consent_id")).willReturn(123L);
		IcebreakerTopics.Selection after = topics.resolve(7).orElseThrow();
		assertThat(after.key()).isEqualTo(before.key());
		assertThat(after).isNotEqualTo(before);
	}

	private ResultSet participant(long userId, String answer) throws Exception {
		ResultSet row = mock(ResultSet.class);
		given(row.getLong("user_id")).willReturn(userId);
		given(row.getLong("consent_id")).willReturn(userId + 100);
		given(row.getBoolean("agreed")).willReturn(true);
		given(row.getString("document_version")).willReturn("q040-consent-v1");
		given(row.getLong("survey_id")).willReturn(userId + 200);
		given(row.getString("survey_version")).willReturn("SIS-Q-1.1-100");
		given(row.getString("answer_type")).willReturn("ARRAY");
		given(row.getString("answer_value")).willReturn(answer);
		return row;
	}
}
