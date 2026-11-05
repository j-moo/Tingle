package com.e106.tingle.settings.snapshot;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;

import lombok.RequiredArgsConstructor;

/** 현재 저장 사실만 읽는다. 호출자는 설정 행 잠금과 대기 중인 JPA 변경 flush를 먼저 수행한다. */
@Repository
@RequiredArgsConstructor
public class SettingsInputFactsRepository {

	private final JdbcTemplate jdbc;

	public Facts load(long userId) {
		List<String> states = jdbc.query("select status from users where id=?",
			(result, row) -> result.getString(1), userId);
		if (states.size() != 1 || !"ACTIVE".equals(states.getFirst())) {
			throw unavailable();
		}
		List<Facts> facts = jdbc.query("""
			select st.settings_version, st.vibration_enabled, st.led_enabled,
			mp.user_id as preference_user, mp.matching_enabled, mp.photo_required,
			mp.age_preference_mode, mp.min_age, mp.max_age,
			p.user_id as profile_user, p.birth_date, p.gender::text as gender,
			p.smoking_status::text as smoking, p.drinking_frequency::text as drinking,
			p.religion::text as religion, p.completed_at as profile_completed,
			p.photo_url is not null and length(trim(p.photo_url))>0 as photo_present,
			p.nickname is not null and p.birth_date is not null and p.gender is not null
			and p.smoking_status is not null and p.drinking_frequency is not null as profile_required,
			p.active_personality_id, a.id as result_id, a.survey_id, a.analysis_version,
			a.status::text as analysis_status, a.result::text as result_json,
			s.user_id as survey_user, s.survey_version, s.completed_at as survey_completed,
			s.definition_snapshot::text as definition_snapshot
			from user_setting st
			left join matching_preference mp on mp.user_id=st.user_id
			left join profile p on p.user_id=st.user_id
			left join personality a on a.id=p.active_personality_id
			left join survey s on s.id=a.survey_id
			where st.user_id=?
			""", this::map, userId);
		if (facts.size() != 1) {
			throw unavailable();
		}
		return facts.getFirst();
	}

	private Facts map(ResultSet result, int row) throws SQLException {
		SettingFacts setting = new SettingFacts(result.getObject("settings_version", Long.class),
			result.getObject("vibration_enabled", Boolean.class), result.getObject("led_enabled", Boolean.class));
		PreferenceFacts preference = result.getObject("preference_user") == null ? null
			: new PreferenceFacts(result.getObject("matching_enabled", Boolean.class),
				result.getObject("photo_required", Boolean.class),
				result.getObject("age_preference_mode", Boolean.class),
				result.getObject("min_age", Integer.class), result.getObject("max_age", Integer.class));
		ProfileFacts profile = result.getObject("profile_user") == null ? null
			: new ProfileFacts(result.getObject("birth_date", LocalDate.class), result.getString("gender"),
				result.getString("smoking"), result.getString("drinking"), result.getString("religion"),
				instant(result, "profile_completed"), result.getBoolean("profile_required"),
				result.getBoolean("photo_present"));
		AnalysisFacts analysis = new AnalysisFacts(result.getObject("active_personality_id", Long.class),
			result.getObject("result_id", Long.class), result.getObject("survey_id", Long.class),
			result.getObject("survey_user", Long.class), result.getString("survey_version"),
			result.getString("analysis_version"), result.getString("analysis_status"),
			instant(result, "survey_completed"), result.getString("result_json"),
			result.getString("definition_snapshot"));
		return new Facts(setting, preference, profile, analysis);
	}

	private static Instant instant(ResultSet result, String column) throws SQLException {
		Timestamp timestamp = result.getTimestamp(column);
		return timestamp == null ? null : timestamp.toInstant();
	}

	private static BusinessException unavailable() {
		return new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
	}

	public record Facts(SettingFacts setting, PreferenceFacts preference, ProfileFacts profile,
		AnalysisFacts analysis) {
	}

	public record SettingFacts(Long version, Boolean vibrationEnabled, Boolean ledEnabled) {
	}

	public record PreferenceFacts(Boolean matchingEnabled, Boolean photoRequired, Boolean rangeSelected,
		Integer minAge, Integer maxAge) {
	}

	public record ProfileFacts(LocalDate birthDate, String gender, String smoking, String drinking,
		String religion, Instant completedAt, boolean requiredFieldsPresent, boolean photoPresent) {
	}

	public record AnalysisFacts(Long activeResultId, Long resultId, Long surveyId, Long surveyUserId,
		String surveyVersion, String analysisVersion, String status, Instant surveyCompletedAt,
		String resultJson, String definitionSnapshot) {
	}
}
