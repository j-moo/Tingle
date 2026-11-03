package com.e106.tingle.personality;

import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.survey.SurveyAnswer;
import com.e106.tingle.survey.SurveyAnswerRepository;
import com.e106.tingle.survey.SurveyResponse;
import com.e106.tingle.survey.SurveyResponseRepository;
import com.e106.tingle.user.OnboardingService;
import com.e106.tingle.user.Profile;
import com.e106.tingle.user.ProfileAnalysisApplicationService;
import com.e106.tingle.user.ProfileRepository;
import com.e106.tingle.user.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 저장된 성공 결과를 회원의 적용 결과(profile.active_personality_id)로 바꾼다.
 * 분석 작업기가 결과를 저장한 뒤 부르고, 같은 회차의 분석 재요청도 부른다(서버 재시작 등으로 적용이 빠졌을 때 복구).
 * 이미 적용된 결과는 다시 적용하지 않는다(ProfileAnalysisApplicationService.apply).
 */
@Slf4j
@Component
@RequiredArgsConstructor
class PersonalityActivation {

	// Q097 엄격 제외 항목 → 그 허용 범위를 고르는 문항(설문 파일 보기 설명 기준)
	private static final Map<String, String> STRICT_RANGES = Map.of("smoking", "Q090", "children", "Q093",
		"religion", "Q091", "tattoo", "Q095");

	private final PersonalityRepository personalities;
	private final SurveyResponseRepository surveys;
	private final SurveyAnswerRepository answers;
	private final ProfileRepository profiles;
	private final UserRepository users;
	private final ProfileAnalysisApplicationService applications;
	private final OnboardingService onboardingService;
	private final PersonalAnalysisProperties properties;
	private final TransactionTemplate tx;
	private final JsonMapper jsonMapper;

	/**
	 * 적용을 시도한다. 적용됐거나 거절(더 최근 회차·같은 회차의 다른 버전 등)이면 true,
	 * 거절이 아닌 일시 오류(DB 오류 등)면 false 라 호출한 쪽이 나중에 다시 시도할 수 있다.
	 */
	boolean tryActivate(long resultId) {
		try {
			tx.executeWithoutResult(status -> activateIfLatest(resultId));
			return true;
		} catch (BusinessException e) {
			log.info("성향 분석 결과 적용 거절 resultId={} code={}", resultId, e.getErrorCode());
			return true;
		} catch (RuntimeException e) {
			log.warn("성향 분석 결과 적용 실패 resultId={}", resultId, e);
			return false;
		}
	}

	/**
	 * 현재 분석 규칙 버전이고 회원의 가장 최근 완료 회차 결과이며 Q97·Q98 재확인이 필요 없을 때만 적용한다.
	 * 적용·설정 번호 증가·입력 보존은 ProfileAnalysisApplicationService 가 같은 트랜잭션에서 한다.
	 */
	private void activateIfLatest(long resultId) {
		Personality personality = personalities.findById(resultId).orElse(null);
		if (personality == null || !personality.getAnalysisVersion().equals(properties.analysisVersion())) {
			return;
		}
		SurveyResponse survey = surveys.findById(personality.getSurveyId()).orElse(null);
		if (survey == null) {
			return;
		}
		long userId = survey.getUserId();
		// 같은 회원의 다른 분석 반영·설정 저장과 줄 세운다.
		if (users.findByIdForUpdate(userId).isEmpty()
			|| surveys.existsByUserIdAndCompletedAtAfter(userId, survey.getCompletedAt())) {
			return;
		}
		if (needsReconfirmation(survey.getId())) {
			// SUR-02: 결과는 저장하되 새 응답으로 매칭하지 않는다. 기존 회원은 기존 결과를 유지한다.
			log.info("Q97·Q98 재확인 전이라 성향 결과를 적용하지 않음 resultId={}", personality.getId());
			return;
		}
		Long active = profiles.findById(userId).map(Profile::getActivePersonalityId).orElse(null);
		if (applications.apply(userId, personality.getId(), active)) {
			onboardingService.recordProgress(userId);
		}
	}

	/**
	 * SUR-02 재확인 대상: Q98 미확인, Q97 미확인, Q97 엄격 제외 항목의 허용 범위 미확인.
	 * 나이 제외는 설문이 아니라 희망 조건(matching_preference)의 범위를 쓰므로 보지 않는다.
	 */
	private boolean needsReconfirmation(long surveyId) {
		Map<String, JsonNode> answered = answers.findBySurveyId(surveyId).stream()
			.collect(Collectors.toMap(SurveyAnswer::getQuestionId, answer -> answer.value(jsonMapper)));
		JsonNode strict = answered.get("Q097");
		if (unconfirmed(answered.get("Q098")) || unconfirmed(strict)) {
			return true;
		}
		return strict.isArray() && strict.valueStream().map(JsonNode::asString).map(STRICT_RANGES::get)
			.filter(Objects::nonNull).map(answered::get).anyMatch(range -> unconfirmed(range) || isAny(range));
	}

	private static boolean unconfirmed(JsonNode value) {
		return value == null || value.isNull() || value.isString() && value.asString().equals("unknown");
	}

	private static boolean isAny(JsonNode value) {
		return value.isString() && value.asString().equals("any");
	}
}
