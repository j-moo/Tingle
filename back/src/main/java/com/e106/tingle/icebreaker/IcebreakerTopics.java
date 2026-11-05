package com.e106.tingle.icebreaker;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.e106.tingle.survey.SurveyCatalog;
import com.e106.tingle.survey.SurveyCatalog.SurveyDefinition;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 사용자 행 잠금을 가진 호출자가 공통 주제와 동의·설문 출처를 같은 시점에 확인한다. */
@Component
public class IcebreakerTopics {

	public static final String CONSENT_TYPE = "ICEBREAKER_TOPIC";
	public static final String CONSENT_VERSION = "q040-consent-v1";
	private static final String SURVEY_VERSION = "SIS-Q-1.1-100";
	private static final List<String> KEYS = List.of("rest", "talk", "culture", "walk", "active");
	private static final String RESPONSE_SCHEMA = "{\"oneOf\":[{\"type\":\"array\",\"items\":{\"enum\":"
		+ "[\"rest\",\"talk\",\"culture\",\"walk\",\"active\"]},\"uniqueItems\":true,\"minItems\":1,\"maxItems\":2},"
		+ "{\"const\":\"unknown\"}]}";
	private final JdbcTemplate jdbc;
	private final SurveyCatalog catalog;
	private final ObjectMapper mapper;
	private final JsonNode responseSchema;

	public IcebreakerTopics(JdbcTemplate jdbc, SurveyCatalog catalog, ObjectMapper mapper) {
		this.jdbc = jdbc;
		this.catalog = catalog;
		this.mapper = mapper;
		responseSchema = mapper.readTree(RESPONSE_SCHEMA);
	}

	/** 허용·버전·응답 중 하나라도 해석할 수 없으면 원본 응답을 사용하지 않는다. */
	public Optional<Selection> resolve(long roomId) {
		List<Participant> participants = jdbc.query("select p.user_id,c.consent_id,c.agreed,c.document_version,"
			+ "s.id as survey_id,s.survey_version,a.answer_type,a.answer_value from chat_participant p "
			+ "left join lateral (select consent_id,agreed,document_version from user_consents "
			+ "where user_id=p.user_id and consent_type=? order by consent_id desc limit 1) c on true "
			+ "left join lateral (select id,survey_version from survey where user_id=p.user_id "
			+ "and completed_at is not null order by completed_at desc,id desc limit 1) s on true "
			+ "left join survey_answer a on a.survey_id=s.id and a.question_id='Q040' "
			+ "where p.chat_id=? order by p.user_id",
			(rs, row) -> new Participant(rs.getLong("user_id"), rs.getLong("consent_id"), rs.getBoolean("agreed"),
				rs.getString("document_version"), rs.getLong("survey_id"), rs.getString("survey_version"),
				rs.getString("answer_type"), rs.getString("answer_value")), CONSENT_TYPE, roomId);
		if (participants.size() != 2 || participants.getFirst().userId() == participants.getLast().userId()) {
			return Optional.empty();
		}
		Set<String> first = choices(participants.getFirst());
		Set<String> second = choices(participants.getLast());
		return KEYS.stream().filter(key -> first.contains(key) && second.contains(key)).findFirst()
			.map(key -> new Selection(key, participants.stream().map(Participant::evidence).toList()));
	}

	public static boolean supportedKey(String key) {
		return key != null && KEYS.contains(key);
	}

	private Set<String> choices(Participant participant) {
		if (participant.consentId() <= 0 || !participant.agreed()
			|| !CONSENT_VERSION.equals(participant.consentVersion()) || participant.surveyId() <= 0
			|| !SURVEY_VERSION.equals(participant.surveyVersion()) || !"ARRAY".equals(participant.answerType())
			|| participant.answerValue() == null) {
			return Set.of();
		}
		Optional<SurveyDefinition> definition = catalog.find(participant.surveyVersion());
		if (definition.isEmpty() || !supportedDefinition(definition.get())) {
			return Set.of();
		}
		try {
			JsonNode answer = mapper.readTree(participant.answerValue());
			if (!answer.isArray() || answer.size() < 1 || answer.size() > 2) {
				return Set.of();
			}
			Set<String> choices = new HashSet<>();
			for (JsonNode value : answer) {
				if (!value.isString() || !supportedKey(value.asString()) || !choices.add(value.asString())) {
					return Set.of();
				}
			}
			return choices;
		} catch (RuntimeException invalidAnswer) {
			return Set.of();
		}
	}

	private boolean supportedDefinition(SurveyDefinition definition) {
		List<JsonNode> questions = definition.questions().stream()
			.filter(question -> "Q040".equals(question.path("questionId").asString())).toList();
		return SURVEY_VERSION.equals(definition.surveyVersion()) && questions.size() == 1
			&& responseSchema.equals(questions.getFirst().path("responseSchema"));
	}

	public record Selection(String key, List<Evidence> participants) {
		public Selection {
			participants = List.copyOf(participants);
		}
	}

	public record Evidence(long userId, long consentId, long surveyId, String surveyVersion) {
	}

	private record Participant(long userId, long consentId, boolean agreed, String consentVersion, long surveyId,
		String surveyVersion, String answerType, String answerValue) {
		Evidence evidence() {
			return new Evidence(userId, consentId, surveyId, surveyVersion);
		}
	}
}
