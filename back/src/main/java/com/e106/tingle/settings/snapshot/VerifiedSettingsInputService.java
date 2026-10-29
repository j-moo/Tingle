package com.e106.tingle.settings.snapshot;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.settings.snapshot.SettingsInputFactsRepository.AnalysisFacts;
import com.e106.tingle.settings.snapshot.SettingsInputFactsRepository.Facts;
import com.e106.tingle.settings.snapshot.SettingsInputFactsRepository.PreferenceFacts;
import com.e106.tingle.settings.snapshot.SettingsInputFactsRepository.ProfileFacts;
import com.e106.tingle.survey.SurveyCatalog;
import com.e106.tingle.survey.SurveyCatalog.SurveyDefinition;
import com.e106.tingle.user.ProfileRules;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** DB 소유·완료·세대와 실제 설문 파일을 확인해 서버 입력 증거를 보존한다. READY를 승인하지 않는다. */
@Service
@RequiredArgsConstructor
public class VerifiedSettingsInputService {

	private static final String FORMAT = "settings-source-evidence-v1";
	private final SettingsInputFactsRepository repository;
	private final SettingsSnapshotService snapshots;
	private final SettingsSnapshotJson json;
	private final JsonMapper mapper;
	private final SurveyCatalog catalog;
	private final Clock clock;

	@Transactional(propagation = Propagation.MANDATORY)
	public SettingsSnapshotView captureCurrent(long userId) {
		if (userId <= 0) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST);
		}
		Facts facts = repository.load(userId);
		if (facts.setting().version() == null || facts.setting().version() < 0
			|| facts.setting().vibrationEnabled() == null || facts.setting().ledEnabled() == null) {
			throw unavailable();
		}
		List<String> missing = new ArrayList<>();
		List<String> review = new ArrayList<>();
		review.add("ANALYSIS_MATCHING_ARTIFACT_MAPPING_UNAPPROVED");
		LocalDate date = clock.instant().atZone(ZoneId.of("Asia/Seoul")).toLocalDate();
		ProfileEvidence profile = profile(facts.profile(), date, missing, review);
		PreferenceEvidence preference = preference(facts.preference(), missing);
		AnalysisEvidence analysis = analysis(userId, facts.analysis(), missing, review);
		Evidence evidence = new Evidence(missing.isEmpty() ? "REVIEW_REQUIRED" : "INCOMPLETE", date.toString(),
			"PROFILE_RULES_SEOUL_FULL_AGE", facts.setting(), profile, preference, analysis,
			List.copyOf(missing), List.copyOf(review));
		return snapshots.capture(userId, facts.setting().version(), FORMAT, mapper.writeValueAsString(evidence));
	}

	private ProfileEvidence profile(ProfileFacts fact, LocalDate date, List<String> missing, List<String> review) {
		if (fact == null) {
			missing.add("PROFILE_MISSING");
			return null;
		}
		if (fact.completedAt() == null || !fact.requiredFieldsPresent()) {
			missing.add("PROFILE_INCOMPLETE");
		}
		Integer age = null;
		if (fact.birthDate() != null) {
			try {
				ProfileRules.validateBirthDate(fact.birthDate(), date);
				age = ProfileRules.age(fact.birthDate(), date);
			} catch (BusinessException exception) {
				review.add("PROFILE_AGE_INVALID");
			}
		}
		return new ProfileEvidence(fact.completedAt() != null, age, fact.gender(), fact.smoking(),
			fact.drinking(), fact.religion(), fact.photoPresent());
	}

	private PreferenceEvidence preference(PreferenceFacts fact, List<String> missing) {
		if (fact == null) {
			missing.add("PREFERENCES_MISSING");
			return null;
		}
		if (fact.matchingEnabled() == null || fact.photoRequired() == null) {
			throw unavailable();
		}
		String mode = null;
		if (fact.rangeSelected() == null) {
			missing.add("PREFERENCES_INCOMPLETE");
		} else if (fact.rangeSelected()) {
			mode = "RANGE";
			if (fact.minAge() == null || fact.minAge() < 19
				|| fact.maxAge() != null && fact.maxAge() < fact.minAge()) {
				throw unavailable();
			}
		} else {
			mode = "ANY";
			if (fact.minAge() != null || fact.maxAge() != null) {
				throw unavailable();
			}
		}
		return new PreferenceEvidence(fact.matchingEnabled(), fact.photoRequired(), mode,
			fact.minAge(), fact.maxAge());
	}

	private AnalysisEvidence analysis(long userId, AnalysisFacts fact, List<String> missing, List<String> review) {
		if (fact.activeResultId() == null) {
			missing.add("ACTIVE_ANALYSIS_MISSING");
			return null;
		}
		if (!fact.activeResultId().equals(fact.resultId()) || fact.surveyId() == null
			|| fact.surveyUserId() == null || fact.surveyUserId() != userId || fact.surveyCompletedAt() == null
			|| !"SUCCEEDED".equals(fact.status()) || !version(fact.surveyVersion())
			|| !version(fact.analysisVersion())) {
			throw unavailable();
		}
		String resultHash = null;
		try {
			String canonical = json.canonicalize(fact.resultJson());
			if (mapper.readTree(canonical).isEmpty()) {
				review.add("ANALYSIS_RESULT_EMPTY");
			} else {
				resultHash = json.hash(canonical);
			}
		} catch (IllegalArgumentException exception) {
			review.add("ANALYSIS_RESULT_INVALID");
		}
		SurveyDefinition definition = catalog.find(fact.surveyVersion()).orElse(null);
		String fileHash = definition == null ? null : definition.contentHash();
		if (definition == null || fileHash == null || !fileHash.matches("[0-9a-f]{64}")) {
			review.add("SURVEY_VERSION_UNSUPPORTED");
			fileHash = null;
		}
		boolean sourceVerified = definition != null && fileHash != null
			&& sameDefinition(fact.definitionSnapshot(), definition);
		if (!sourceVerified) {
			review.add("SURVEY_DEFINITION_UNVERIFIED");
		}
		return new AnalysisEvidence(fact.resultId(), fact.surveyId(), fact.surveyVersion(), fact.analysisVersion(),
			resultHash, fileHash, sourceVerified);
	}

	private boolean sameDefinition(String stored, SurveyDefinition definition) {
		if (stored == null) {
			return false;
		}
		try {
			JsonNode source = mapper.readTree(stored).path("definition");
			return definition.surveyVersion().equals(source.path("surveyVersion").asString())
				&& definition.contentHash().equals(source.path("contentHash").asString());
		} catch (RuntimeException exception) {
			return false;
		}
	}

	private static boolean version(String value) {
		return value != null && !value.isBlank() && value.length() <= 30;
	}

	private static BusinessException unavailable() {
		return new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
	}

	public record Evidence(String evidenceState, String ageReferenceDate, String ageRule,
		SettingsInputFactsRepository.SettingFacts settings, ProfileEvidence profile,
		PreferenceEvidence preferences, AnalysisEvidence analysis, List<String> missingInputs,
		List<String> reviewReasons) {
	}

	public record ProfileEvidence(boolean registered, Integer age, String gender, String smoking,
		String drinking, String religion, boolean photoPresent) {
	}

	public record PreferenceEvidence(boolean matchingEnabled, boolean requirePartnerPhoto,
		String desiredAgeMode, Integer desiredAgeMin, Integer desiredAgeMax) {
	}

	public record AnalysisEvidence(long sourceResultId, long surveyId, String surveyVersion,
		String analysisVersion, String resultHash, String surveyContentHash, boolean sourceDefinitionVerified) {
	}
}
