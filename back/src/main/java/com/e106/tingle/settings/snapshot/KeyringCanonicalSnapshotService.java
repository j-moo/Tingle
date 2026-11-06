package com.e106.tingle.settings.snapshot;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.keyring.KeyringProfileStorageEncoder;
import com.e106.tingle.settings.snapshot.SettingsInputFactsRepository.AnalysisFacts;
import com.e106.tingle.settings.snapshot.SettingsInputFactsRepository.Facts;
import com.e106.tingle.settings.snapshot.SettingsInputFactsRepository.PreferenceFacts;
import com.e106.tingle.settings.snapshot.SettingsInputFactsRepository.ProfileFacts;
import com.e106.tingle.settings.snapshot.SettingsInputFactsRepository.SettingFacts;
import com.e106.tingle.survey.SurveyAnswer;
import com.e106.tingle.survey.SurveyAnswerRepository;
import com.e106.tingle.survey.SurveyAnswerValidator;
import com.e106.tingle.survey.SurveyCatalog;
import com.e106.tingle.survey.SurveyCatalog.SurveyDefinition;
import com.e106.tingle.user.ProfileRules;

import lombok.RequiredArgsConstructor;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** 현재 활성 분석의 설문 원응답을 키링용 논리 스냅샷으로 투영한다. */
@Service
@RequiredArgsConstructor
public class KeyringCanonicalSnapshotService {

	private static final String SURVEY_VERSION = "SIS-Q-1.1-100";
	private static final String SURVEY_HASH = "d1199ef59596e78e2c2d3df8884a72a2b97c63099e443da7b9103f3d1e7bcb6e";
	private static final String ANALYSIS_VERSION = "SIS-A-1.0";

	private final SettingsInputFactsRepository facts;
	private final SurveyAnswerRepository answers;
	private final SurveyCatalog catalog;
	private final JsonMapper mapper;
	private final SettingsSnapshotJson json;
	private final Clock clock;

	@Transactional(readOnly = true)
	public String generate(long userId) {
		Facts source = load(userId);
		return json.canonicalize(logicalSnapshot(userId, source.analysis()).toString());
	}

	@Transactional(readOnly = true)
	public byte[] generateStoragePayload(long userId) {
		if (userId <= 0) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST);
		}
		try {
			Facts source = facts.load(userId);
			return KeyringProfileStorageEncoder.encode(logicalSnapshot(userId, source.analysis()), personal(source));
		} catch (BusinessException | IllegalArgumentException | JacksonException exception) {
			throw new BusinessException(ErrorCode.POLICY_UNRESOLVED);
		}
	}

	private Facts load(long userId) {
		if (userId <= 0) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST);
		}
		return facts.load(userId);
	}

	private ObjectNode logicalSnapshot(long userId, AnalysisFacts analysis) {
		SurveyDefinition definition = requireSupportedSurvey(userId, analysis);
		Map<String, JsonNode> values = new HashMap<>();
		for (SurveyAnswer answer : answers.findBySurveyId(analysis.surveyId())) {
			String id = answer.getQuestionId();
			JsonNode value = answer.value(mapper);
			SurveyAnswerValidator.validate(definition, id, value);
			values.put(id, value);
		}

		ObjectNode snapshot = mapper.createObjectNode();
		snapshot.put("surveyVersion", SURVEY_VERSION);
		ObjectNode actual = snapshot.putObject("actual");
		// SIS-Q-1.1-100 engine100: 앞/뒤 위치는 정채점/역채점 문항을 나타낸다.
		actual.set("E", scale(values, 2, 1, 11, 6, 16));
		actual.set("A", scale(values, 2, 2, 12, 7, 17));
		actual.set("C", scale(values, 2, 3, 13, 8, 18));
		actual.set("I", scale(values, 1, 5, 10, 15, 20));
		for (int ordinal = 30; ordinal <= 60; ordinal++) {
			actual.set("q" + ordinal, cell(values.get(questionId(ordinal))));
		}
		ObjectNode wishes = snapshot.putObject("wishes");
		for (int ordinal = 61; ordinal <= 95; ordinal++) {
			wishes.set(Integer.toString(ordinal), cell(values.get(questionId(ordinal))));
		}
		snapshot.set("priority", cell(values.get("Q096")));
		snapshot.set("strict", cell(values.get("Q097")));
		snapshot.set("tolerance", cell(values.get("Q098")));
		return snapshot;
	}

	private ObjectNode personal(Facts source) {
		ProfileFacts profile = source.profile();
		PreferenceFacts preference = source.preference();
		SettingFacts setting = source.setting();
		if (profile == null || profile.completedAt() == null || !profile.requiredFieldsPresent()
			|| profile.birthDate() == null || profile.smoking() == null || profile.drinking() == null
			|| preference == null || preference.matchingEnabled() == null || preference.photoRequired() == null
			|| setting == null || setting.vibrationEnabled() == null || setting.ledEnabled() == null) {
			throw unavailable();
		}
		String desiredGender = switch (profile.gender() == null ? "" : profile.gender()) {
			case "MALE" -> "FEMALE";
			case "FEMALE" -> "MALE";
			default -> throw unavailable();
		};
		LocalDate today = clock.instant().atZone(ZoneId.of("Asia/Seoul")).toLocalDate();
		ProfileRules.validateBirthDate(profile.birthDate(), today);

		ObjectNode personal = mapper.createObjectNode();
		personal.putObject("actualAge").put("state", "value")
			.put("value", ProfileRules.age(profile.birthDate(), today));
		personal.putObject("actualGender").put("state", "value").put("value", profile.gender());
		personal.putObject("desiredGender").put("state", "value").putArray("value").add(desiredGender);
		personal.putObject("hasProfilePhoto").put("state", "value").put("value", profile.photoPresent());
		personal.putObject("requirePartnerPhoto").put("state", "value").put("value", preference.photoRequired());
		personal.putObject("smoking").put("state", "value").put("value", profile.smoking());
		personal.putObject("drinking").put("state", "value").put("value", profile.drinking());
		ObjectNode religion = personal.putObject("religion");
		if (profile.religion() == null) {
			religion.put("state", "missing");
		} else {
			religion.put("state", "value").put("value", profile.religion());
		}
		personal.set("desiredAge", desiredAge(preference));
		personal.putObject("deviceOptions").put("matchingEnabled", preference.matchingEnabled())
			.put("vibrationEnabled", setting.vibrationEnabled()).put("ledEnabled", setting.ledEnabled());
		return personal;
	}

	private ObjectNode desiredAge(PreferenceFacts preference) {
		ObjectNode age = mapper.createObjectNode();
		if (Boolean.TRUE.equals(preference.rangeSelected())) {
			if (preference.minAge() == null) {
				throw unavailable();
			}
			age.put("mode", "range").put("min", preference.minAge());
			age.put("max", preference.maxAge());
			return age;
		}
		if (preference.minAge() != null || preference.maxAge() != null) {
			throw unavailable();
		}
		return age.put("mode", preference.rangeSelected() == null ? "missing" : "any");
	}

	private SurveyDefinition requireSupportedSurvey(long userId, AnalysisFacts analysis) {
		if (analysis.activeResultId() == null || !analysis.activeResultId().equals(analysis.resultId())
			|| analysis.surveyId() == null || analysis.surveyUserId() == null || analysis.surveyUserId() != userId
			|| analysis.surveyCompletedAt() == null || !"SUCCEEDED".equals(analysis.status())
			|| !SURVEY_VERSION.equals(analysis.surveyVersion())
			|| !ANALYSIS_VERSION.equals(analysis.analysisVersion())) {
			throw unavailable();
		}
		SurveyDefinition definition = catalog.find(SURVEY_VERSION)
			.filter(candidate -> SURVEY_HASH.equals(candidate.contentHash()))
			.orElseThrow(KeyringCanonicalSnapshotService::unavailable);
		// 일반 회차는 survey_version으로 정의를 고정하며 레거시 사본 칼럼은 비어 있다.
		if (analysis.definitionSnapshot() != null) {
			JsonNode stored = mapper.readTree(analysis.definitionSnapshot()).path("definition");
			if (!SURVEY_VERSION.equals(stored.path("surveyVersion").asString(""))
				|| !definition.contentHash().equals(stored.path("contentHash").asString(""))) {
				throw unavailable();
			}
		}
		return definition;
	}

	private ObjectNode scale(Map<String, JsonNode> values, int forwardCount, int... ordinals) {
		int sum = 0;
		for (int index = 0; index < ordinals.length; index++) {
			JsonNode value = values.get(questionId(ordinals[index]));
			// 미측정 척도를 IoT의 unknown 또는 missing으로 치환하는 계약은 아직 없다.
			if (value == null || !value.isNumber()) {
				throw unavailable();
			}
			int score = value.asInt();
			sum += index < forwardCount ? score : 6 - score;
		}
		return mapper.createObjectNode().put("state", "value").put("value", (sum / 4.0 - 1) * 25);
	}

	private ObjectNode cell(JsonNode value) {
		ObjectNode cell = mapper.createObjectNode();
		if (value == null) {
			return cell.put("state", "missing");
		}
		if (value.isString() && ("unknown".equals(value.asString()) || "any".equals(value.asString()))) {
			return cell.put("state", value.asString());
		}
		return cell.put("state", "value").set("value", value);
	}

	private static String questionId(int ordinal) {
		return (ordinal < 10 ? "Q00" : "Q0") + ordinal;
	}

	private static BusinessException unavailable() {
		return new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
	}
}
