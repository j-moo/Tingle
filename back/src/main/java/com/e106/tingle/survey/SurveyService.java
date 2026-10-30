package com.e106.tingle.survey;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.survey.SurveyCatalog.MigrationRule;
import com.e106.tingle.survey.SurveyCatalog.SurveyDefinition;
import com.e106.tingle.survey.dto.Manifest;
import com.e106.tingle.survey.dto.MigrationPlanResponse;
import com.e106.tingle.survey.dto.QuestionnaireResponse;
import com.e106.tingle.survey.dto.SurveySessionResponse;
import com.e106.tingle.user.Profile;
import com.e106.tingle.user.ProfileRepository;
import com.e106.tingle.user.UserRepository;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 설문 회차 시작·조회. 회차는 시작할 때의 설문 버전을 고정하고, 그 버전 파일로 문항·진행을 계산한다.
 */
@Service
@RequiredArgsConstructor
public class SurveyService {

	private final SurveyResponseRepository surveyResponseRepository;
	private final SurveyAnswerRepository surveyAnswerRepository;
	private final SurveyCatalog catalog;
	private final ProfileRepository profileRepository;
	private final UserRepository userRepository;
	private final JsonMapper jsonMapper;
	private final Clock clock;

	public QuestionnaireResponse currentQuestionnaire() {
		return questionnaire(catalog.current());
	}

	/**
	 * 설문 회차를 시작한다. 같은 회원·버전의 작성 중 회차가 있으면 새로 만들지 않고 그 회차를 이어간다.
	 */
	@Transactional
	public SurveySessionResponse start(long userId, String surveyVersion) {
		SurveyDefinition definition = catalog.find(surveyVersion)
			.orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
		requireRegisteredProfile(userId);
		return view(draftOf(userId, surveyVersion), definition);
	}

	@Transactional(readOnly = true)
	public SurveySessionResponse get(long userId, long sessionId) {
		SurveyResponse response = owned(userId, sessionId);
		return view(response, definitionOf(response));
	}

	@Transactional(readOnly = true)
	public QuestionnaireResponse questionnaireOf(long userId, long sessionId) {
		return questionnaire(definitionOf(owned(userId, sessionId)));
	}

	/**
	 * 미완료 회차에 문항 하나의 답을 저장한다. 같은 문항을 다시 저장하면 값과 시각을 덮어쓴다.
	 * 값은 회차에 고정된 설문 파일의 responseSchema 로 검사한다.
	 */
	@Transactional
	public SurveySessionResponse saveAnswer(long userId, long sessionId, String questionId, JsonNode value) {
		SurveyResponse response = editable(userId, sessionId);
		SurveyDefinition definition = definitionOf(response);
		SurveyAnswerValidator.validate(definition, questionId, value);
		surveyAnswerRepository.save(SurveyAnswer.of(response.getId(), questionId, value, now()));
		return view(response, definition);
	}

	/**
	 * 미완료 회차에서 문항 하나의 답을 지워 미응답으로 되돌린다. 답이 없던 문항이면 아무것도 바꾸지 않는다.
	 */
	@Transactional
	public SurveySessionResponse deleteAnswer(long userId, long sessionId, String questionId) {
		SurveyResponse response = editable(userId, sessionId);
		SurveyDefinition definition = definitionOf(response);
		if (!definition.questionIds().contains(questionId)) {
			throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
		}
		surveyAnswerRepository.deleteById(new SurveyAnswer.Key(response.getId(), questionId));
		return view(response, definition);
	}

	/**
	 * 모든 문항에 규칙에 맞는 답이 있으면 완료 시각을 남긴다("unknown"도 답). 이미 완료한 회차는 그대로 돌려준다.
	 * 완료는 분석 성공과 별개다(분석 요청은 별도 API).
	 */
	@Transactional
	public SurveySessionResponse complete(long userId, long sessionId) {
		// 성향 분석 결과 적용(최신 완료 회차인지 확인)과 같은 회원 행 잠금으로 줄 세운다.
		userRepository.findByIdForUpdate(userId).orElseThrow(() -> new BusinessException(ErrorCode.AUTH_REQUIRED));
		SurveyResponse response = lockedOwned(userId, sessionId);
		SurveyDefinition definition = definitionOf(response);
		if (response.isCompleted()) {
			return view(response, definition);
		}
		// 저장 때 검사했어도 옛 자료(예: 숫자 문항에 문자열 "4")가 있을 수 있어, 규칙에 맞는 답만 답한 것으로 센다.
		Map<String, SurveyAnswer> answers = surveyAnswerRepository.findBySurveyId(response.getId()).stream()
			.collect(Collectors.toMap(SurveyAnswer::getQuestionId, Function.identity()));
		if (!definition.questionIds().stream()
			.allMatch(id -> answers.containsKey(id) && stillValid(definition, id, answers.get(id)))) {
			throw new BusinessException(ErrorCode.ANSWERS_INCOMPLETE);
		}
		response.complete(now());
		return view(response, definition);
	}

	/**
	 * 본인 회차를 targetSurveyVersion 으로 옮길 때 옮길 답·다시 답할 문항·옮길 수 없는 답을 미리 보여 준다. DB는 바꾸지 않는다.
	 */
	@Transactional(readOnly = true)
	public MigrationPlanResponse migrationPlan(long userId, long sessionId, String targetSurveyVersion) {
		SurveyResponse source = owned(userId, sessionId);
		return plan(source, migrationRule(source, targetSurveyVersion));
	}

	/**
	 * 원본 회차·분석은 그대로 두고 새 버전의 회차를 만들어, 미리보기에서 옮길 수 있다고 한 답만 복사한다.
	 * 같은 버전의 작성 중 회차가 이미 있으면 새로 만들지 않고 그 회차를 그대로 돌려준다(답은 복사하지 않음).
	 */
	@Transactional
	public SurveySessionResponse migrate(long userId, long sessionId, String targetSurveyVersion, String policyHash) {
		userRepository.findByIdForUpdate(userId).orElseThrow(() -> new BusinessException(ErrorCode.AUTH_REQUIRED));
		// 원본 회차도 잠가서, 미리보기 계산과 답 복사 사이에 원본 답이 저장·삭제로 바뀌지 않게 한다.
		SurveyResponse source = lockedOwned(userId, sessionId);
		MigrationRule rule = migrationRule(source, targetSurveyVersion);
		if (!rule.policyHash().equals(policyHash)) {
			throw new BusinessException(ErrorCode.MIGRATION_POLICY_CHANGED);
		}
		SurveyDefinition target = catalog.find(targetSurveyVersion).orElseThrow();
		SurveyResponse existing = surveyResponseRepository
			.findByUserIdAndVersionCodeAndCompletedAtIsNull(userId, targetSurveyVersion).orElse(null);
		if (existing != null) {
			return view(existing, target);
		}
		SurveyResponse created = surveyResponseRepository.save(SurveyResponse.start(userId, targetSurveyVersion));
		List<String> preserved = plan(source, rule).preservedQuestionIds();
		surveyAnswerRepository.saveAll(surveyAnswerRepository.findBySurveyId(source.getId()).stream()
			.filter(answer -> preserved.contains(answer.getQuestionId()))
			.map(answer -> SurveyAnswer.of(created.getId(), answer.getQuestionId(), answer.value(jsonMapper),
				answer.getAnsweredAt()))
			.toList());
		return view(created, target);
	}

	/**
	 * 가입 단계 조회에 보여 줄 현재 버전 설문의 진행 상황. 작성 중 회차가 없으면 sessionId 는 null.
	 */
	@Transactional(readOnly = true)
	public Progress progress(long userId) {
		SurveyDefinition current = catalog.current();
		return surveyResponseRepository.findByUserIdAndVersionCodeAndCompletedAtIsNull(userId,
				current.surveyVersion())
			.map(draft -> {
				SurveySessionResponse view = view(draft, current);
				return new Progress(view.sessionId(), view.answeredCount(), view.questionCount(),
					view.nextQuestionId());
			})
			.orElseGet(() -> new Progress(null, 0, current.questionCount(), current.questionIds().getFirst()));
	}

	public record Progress(Long sessionId, int answeredCount, int questionCount, String nextQuestionId) {
	}

	// 같은 회원의 시작 요청을 회원 행 잠금으로 줄 세워, 같은 버전의 작성 중 회차가 둘 생기지 않게 한다.
	private SurveyResponse draftOf(long userId, String surveyVersion) {
		userRepository.findByIdForUpdate(userId).orElseThrow(() -> new BusinessException(ErrorCode.AUTH_REQUIRED));
		return surveyResponseRepository.findByUserIdAndVersionCodeAndCompletedAtIsNull(userId, surveyVersion)
			.orElseGet(() -> surveyResponseRepository.save(SurveyResponse.start(userId, surveyVersion)));
	}

	private SurveyResponse owned(long userId, long sessionId) {
		// 남의 회차와 없는 회차를 구분하지 않는다(존재 여부를 알려 주지 않기 위해).
		return surveyResponseRepository.findByIdAndUserId(sessionId, userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
	}

	// 같은 버전이거나 두 버전 사이의 이관 규칙 파일이 없으면 옮기지 않는다.
	private MigrationRule migrationRule(SurveyResponse source, String targetSurveyVersion) {
		return catalog.migration(source.getVersionCode(), targetSurveyVersion)
			.orElseThrow(() -> new BusinessException(ErrorCode.MIGRATION_UNSUPPORTED));
	}

	// 규칙의 preserve 문항이라도 새 버전 규칙으로 다시 검사해 맞지 않으면 옮기지 않는다(옮길 수 없는 답).
	// 다시 답할 문항(reconfirm)은 원본 답이 있어도 옮기지 않으며, 그 밖에 답한 문항은 옮길 수 없는 답이다.
	private MigrationPlanResponse plan(SurveyResponse source, MigrationRule rule) {
		SurveyDefinition from = definitionOf(source);
		SurveyDefinition to = catalog.find(rule.toSurveyVersion()).orElseThrow();
		Map<String, SurveyAnswer> answers = surveyAnswerRepository.findBySurveyId(source.getId()).stream()
			.collect(Collectors.toMap(SurveyAnswer::getQuestionId, Function.identity()));
		List<String> answered = from.questionIds().stream().filter(answers::containsKey).toList();
		List<String> preserved = answered.stream()
			.filter(id -> rule.preserve().contains(id) && stillValid(to, id, answers.get(id)))
			.toList();
		List<String> incompatible = answered.stream()
			.filter(id -> !preserved.contains(id) && !rule.reconfirm().contains(id))
			.toList();
		return new MigrationPlanResponse(source.getId(), rule.toSurveyVersion(), preserved, rule.reconfirm(),
			incompatible, rule.policyHash());
	}

	private boolean stillValid(SurveyDefinition target, String questionId, SurveyAnswer answer) {
		try {
			SurveyAnswerValidator.validate(target, questionId, answer.value(jsonMapper));
			return true;
		} catch (BusinessException invalid) {
			return false;
		}
	}

	// 저장·삭제·완료는 회차 행을 잠근 뒤 처리한다. 남의·없는 회차는 404, 완료한 회차는 409.
	private SurveyResponse lockedOwned(long userId, long sessionId) {
		return surveyResponseRepository.findOwnedForUpdate(sessionId, userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
	}

	private SurveyResponse editable(long userId, long sessionId) {
		SurveyResponse response = lockedOwned(userId, sessionId);
		if (response.isCompleted()) {
			throw new BusinessException(ErrorCode.SESSION_COMPLETED);
		}
		return response;
	}

	// DB(timestamptz)가 마이크로초까지 저장하므로 응답과 다시 조회한 값이 같도록 맞춘다.
	private Instant now() {
		return clock.instant().truncatedTo(ChronoUnit.MICROS);
	}

	// ponytail: 키링 등록 API(영빈 담당) 전이라 키링 단계와 상관없이 "프로필 등록을 마쳤는지"로만 설문을 연다.
	// 키링 API가 생기면 컨트롤러의 @RequiresOnboarding 을 SURVEY_REQUIRED 로 바꾸고 이 검사는 지운다.
	private void requireRegisteredProfile(long userId) {
		if (!profileRepository.findById(userId).map(Profile::isRegistered).orElse(false)) {
			throw new BusinessException(ErrorCode.ONBOARDING_REQUIRED);
		}
	}

	// 회차에 고정된 버전의 설문 파일. 서버에서 그 파일이 빠졌으면 다른 버전으로 추정하지 않고 막는다.
	private SurveyDefinition definitionOf(SurveyResponse response) {
		return catalog.find(response.getVersionCode())
			.orElseThrow(() -> new BusinessException(ErrorCode.SERVICE_UNAVAILABLE));
	}

	private static QuestionnaireResponse questionnaire(SurveyDefinition definition) {
		return new QuestionnaireResponse(Manifest.of(definition), definition.questionCount(), definition.questions());
	}

	private SurveySessionResponse view(SurveyResponse response, SurveyDefinition definition) {
		Map<String, SurveyAnswer> answers = surveyAnswerRepository.findBySurveyId(response.getId()).stream()
			.collect(Collectors.toMap(SurveyAnswer::getQuestionId, Function.identity()));
		if (!definition.questionIds().containsAll(answers.keySet())) {
			throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
		}
		List<SurveySessionResponse.Answer> answered = definition.questionIds().stream()
			.filter(answers::containsKey)
			.map(id -> new SurveySessionResponse.Answer(id, answers.get(id).value(jsonMapper)))
			.toList();
		String nextQuestionId = definition.questionIds().stream()
			.filter(id -> !answers.containsKey(id))
			.findFirst()
			.orElse(null);
		return new SurveySessionResponse(response.getId(), Manifest.of(definition),
			response.isCompleted() ? "COMPLETED" : "DRAFT", answered.size(), definition.questionCount(),
			nextQuestionId, answered, response.getCompletedAt());
	}
}
