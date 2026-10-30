package com.e106.tingle.personality;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.personality.Personality.AnalysisStatus;
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
import tools.jackson.databind.node.ObjectNode;

/**
 * 성향 분석 실행. 요청 트랜잭션 안에서 AI를 부르지 않고, 주기 작업이 실행할 줄을 골라 처리한다.
 * <ol>
 *   <li>선점(짧은 트랜잭션): 줄을 잠그고 attempts 를 1 올린 뒤 nextRetryAt = 지금 + stuckAfter 로 둔다(멈춤 복구 기한)</li>
 *   <li>AI 호출(트랜잭션 밖)</li>
 *   <li>반영(트랜잭션): 줄을 다시 잠그고 아직 PENDING 이고 attempts 가 선점 때와 같을 때만 결과를 쓴다.
 *       다르면 그사이 멈춤 복구로 다시 실행된 것이라 늦게 온 이전 결과로 보고 버린다</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
class PersonalAnalysisWorker {

	private static final int BATCH = 20;
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
	private final PersonalAnalysisClient client;
	private final PersonalAnalysisProperties properties;
	private final TransactionTemplate tx;
	private final JsonMapper jsonMapper;
	private final Clock clock;
	// 일시 오류로 적용만 실패한 결과 → 다시 시도할 기한. 다음 주기마다 다시 적용한다.
	// ponytail: 서버 메모리에만 두므로 기한 안에 재시작하면 빠진다. 자주 생기면 적용 대기 표시를 DB에 둔다.
	private final Map<Long, Instant> unapplied = new ConcurrentHashMap<>();

	/** 적용이 밀린 결과를 다시 적용하고, 실행할 분석을 최대 BATCH 개 처리해 처리한 수를 돌려준다. */
	int runDue() {
		for (Map.Entry<Long, Instant> entry : Map.copyOf(unapplied).entrySet()) {
			unapplied.remove(entry.getKey());
			if (now().isBefore(entry.getValue())) {
				activate(entry.getKey(), entry.getValue());
			} else {
				log.warn("성향 분석 결과 적용 재시도 기한 지남 resultId={}", entry.getKey());
			}
		}
		if (!client.ready()) {
			return 0;
		}
		int processed = 0;
		while (processed < BATCH) {
			Claim claim = tx.execute(status -> claim());
			if (claim == null) {
				break;
			}
			processed++;
			if (claim.request() == null) {
				continue;
			}
			Outcome outcome = call(claim);
			boolean succeeded;
			try {
				succeeded = Boolean.TRUE.equals(tx.execute(status -> apply(claim, outcome)));
			} catch (RuntimeException e) {
				// 줄은 PENDING·선점 기한 그대로 남아 stuckAfter 뒤 다시 실행된다.
				log.warn("성향 분석 결과 반영 실패 resultId={} requestId={}", claim.id(), claim.requestId(), e);
				continue;
			}
			if (succeeded) {
				activate(claim.id(), now().plus(properties.stuckAfter()));
			}
		}
		return processed;
	}

	/**
	 * 결과 저장이 끝난 뒤 따로 적용한다. 적용이 거절돼도(더 최근 회차·같은 회차의 다른 버전 등) 저장한 결과는 남고
	 * AI를 다시 부르지 않는다. 거절이 아닌 오류(DB 일시 오류 등)는 retryUntil 까지 다음 주기마다 다시 적용한다.
	 */
	private void activate(long resultId, Instant retryUntil) {
		try {
			tx.executeWithoutResult(status -> activateIfLatest(resultId));
		} catch (BusinessException e) {
			log.info("성향 분석 결과 적용 거절 resultId={} code={}", resultId, e.getErrorCode());
		} catch (RuntimeException e) {
			unapplied.put(resultId, retryUntil);
			log.warn("성향 분석 결과 적용 실패, 다음 주기에 다시 시도 resultId={}", resultId, e);
		}
	}

	private Claim claim() {
		Personality personality = personalities.lockNextDue(now()).orElse(null);
		if (personality == null) {
			return null;
		}
		JsonNode state = state(personality);
		int attempts = state.path("attempts").asInt();
		String lastErrorCode = state.path("lastErrorCode").isString() ? state.get("lastErrorCode").asString() : null;
		if (attempts >= properties.maxAttempts()) {
			// 선점 뒤 멈춘 채 횟수를 다 쓴 줄. 더 실행하지 않는다.
			personality.retryState(AnalysisStatus.FAILED,
				retryState(attempts, lastErrorCode == null ? "AI_STUCK" : lastErrorCode, null));
			return new Claim(personality.getId(), attempts, null, null);
		}
		SurveyResponse survey = surveys.findById(personality.getSurveyId()).orElse(null);
		if (survey == null || !survey.isCompleted()) {
			personality.retryState(AnalysisStatus.FAILED, retryState(attempts, "SURVEY_UNAVAILABLE", null));
			return new Claim(personality.getId(), attempts, null, null);
		}
		int attempt = attempts + 1;
		personality.retryState(AnalysisStatus.PENDING,
			retryState(attempt, lastErrorCode, now().plus(properties.stuckAfter())));
		List<PersonalAnalysisClient.Answer> answered = answers.findBySurveyId(survey.getId()).stream()
			.sorted(Comparator.comparing(SurveyAnswer::getQuestionId))
			.map(answer -> new PersonalAnalysisClient.Answer(answer.getQuestionId(), answer.value(jsonMapper)))
			.toList();
		return new Claim(personality.getId(), attempt, UUID.randomUUID().toString(),
			new PersonalAnalysisClient.Request(personality.getId(), survey.getVersionCode(),
				personality.getAnalysisVersion(), answered));
	}

	private Outcome call(Claim claim) {
		try {
			JsonNode result = client.analyze(claim.request(), claim.requestId());
			String status = text(result, "status");
			// 요청한 버전의 결과이고 점수 묶음이 있어야 저장·적용한다. 아니면 다시 보내도 같으므로 바로 FAILED.
			if (!status.equals("SUCCEEDED") && !status.equals("INSUFFICIENT") || !result.path("scores").isObject()
				|| !text(result, "surveyVersion").equals(claim.request().surveyVersion())
				|| !text(result, "analysisVersion").equals(claim.request().analysisVersion())) {
				return logged(claim, new Outcome(null, null, "AI_INVALID_RESPONSE", false));
			}
			return logged(claim, new Outcome(AnalysisStatus.valueOf(status), result, null, false));
		} catch (PersonalAnalysisClient.Failure e) {
			return logged(claim, new Outcome(null, null, e.code(), e.retryable()));
		} catch (RuntimeException e) {
			log.warn("성향 분석 AI 호출 오류 resultId={} requestId={}", claim.id(), claim.requestId(), e);
			return new Outcome(null, null, "AI_CALL_ERROR", true);
		}
	}

	private static String text(JsonNode node, String field) {
		return node.path(field).isString() ? node.get(field).asString() : "";
	}

	private Outcome logged(Claim claim, Outcome outcome) {
		log.info("성향 분석 AI 호출 resultId={} attempt={} requestId={} result={}", claim.id(), claim.attempt(),
			claim.requestId(), outcome.status() != null ? outcome.status() : outcome.errorCode());
		return outcome;
	}

	/** 결과를 저장하고, 방금 SUCCEEDED 로 저장했으면 true. */
	private boolean apply(Claim claim, Outcome outcome) {
		Personality personality = personalities.findByIdForUpdate(claim.id()).orElse(null);
		if (personality == null || personality.getStatus() != AnalysisStatus.PENDING
			|| state(personality).path("attempts").asInt() != claim.attempt()) {
			log.info("늦게 도착한 성향 분석 결과를 버림 resultId={} requestId={}", claim.id(), claim.requestId());
			return false;
		}
		if (outcome.status() != null) {
			personality.finish(outcome.status(), jsonMapper.writeValueAsString(outcome.result()));
			return outcome.status() == AnalysisStatus.SUCCEEDED;
		}
		boolean retry = outcome.retryable() && claim.attempt() < properties.maxAttempts();
		personality.retryState(AnalysisStatus.FAILED, retryState(claim.attempt(), outcome.errorCode(),
			retry ? now().plus(properties.retryDelay()) : null));
		return false;
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

	private JsonNode state(Personality personality) {
		return personality.getResult() == null ? jsonMapper.createObjectNode()
			: jsonMapper.readTree(personality.getResult());
	}

	private String retryState(int attempts, String lastErrorCode, Instant nextRetryAt) {
		ObjectNode node = jsonMapper.createObjectNode().put("attempts", attempts);
		if (nextRetryAt != null) {
			node.put("nextRetryAt", nextRetryAt.toString());
		}
		if (lastErrorCode != null) {
			node.put("lastErrorCode", lastErrorCode);
		}
		return jsonMapper.writeValueAsString(node);
	}

	private Instant now() {
		return clock.instant();
	}

	private record Claim(long id, int attempt, String requestId, PersonalAnalysisClient.Request request) {
	}

	private record Outcome(AnalysisStatus status, JsonNode result, String errorCode, boolean retryable) {
	}
}
