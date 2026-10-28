package com.e106.tingle.survey;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.survey.SurveyCatalog.SurveyDefinition;
import com.e106.tingle.survey.dto.Manifest;
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

	// 현재 API의 기존 metadata 응답만 보존한다. 문항 답은 survey_answer에만 저장한다.
	private static final String EMPTY_CONTEXT =
		"{\"metadata\":{\"ownAge\":null,\"desiredAgeMin\":null,\"desiredAgeMax\":null}}";

	private final SurveyResponseRepository surveyResponseRepository;
	private final SurveyAnswerRepository surveyAnswerRepository;
	private final SurveyCatalog catalog;
	private final ProfileRepository profileRepository;
	private final UserRepository userRepository;
	private final JsonMapper jsonMapper;

	public QuestionnaireResponse currentQuestionnaire() {
		return questionnaire(catalog.current());
	}

	/**
	 * 설문 회차를 시작한다. 같은 회원·버전의 작성 중 회차가 있으면 그 회차를 이어간다.
	 * 같은 Idempotency-Key 로 다시 오면 처음 돌려준 회차를 다시 준다(다른 버전이면 IDEMPOTENCY_CONFLICT).
	 */
	@Transactional
	public SurveySessionResponse start(long userId, UUID idempotencyKey, String surveyVersion) {
		SurveyDefinition definition = catalog.find(surveyVersion)
			.orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
		requireRegisteredProfile(userId);
		// 같은 회원의 시작 요청을 줄 세워 작성 중 회차가 둘 생기지 않게 한다.
		userRepository.findByIdForUpdate(userId).orElseThrow(() -> new BusinessException(ErrorCode.AUTH_REQUIRED));

		SurveyResponse previous = surveyResponseRepository.findByUserIdAndClientRequestId(userId, idempotencyKey)
			.orElse(null);
		if (previous != null) {
			if (!previous.getVersionCode().equals(surveyVersion)) {
				throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
			}
			return view(previous, definitionOf(previous).definition());
		}
		SurveyResponse draft = surveyResponseRepository
			.findByUserIdAndVersionCodeAndCompletedAtIsNull(userId, surveyVersion).orElse(null);
		if (draft == null) {
			String snapshot = jsonMapper.writeValueAsString(new FrozenDefinition(definition, catalog.metadataSchema()));
			return view(surveyResponseRepository.save(
				SurveyResponse.start(userId, surveyVersion, idempotencyKey, EMPTY_CONTEXT, snapshot)), definition);
		}
		// 이어가는 회차에 이번 키를 연결해, 완료 뒤 이 키로 재시도해도 같은 회차를 돌려준다.
		// ponytail: 회차당 키 하나(최근 키)만 기억한다. 그 전 키로 완료 뒤 재시도하면 새 초안이 생긴다.
		// 모든 키를 지켜야 하면 키 이력 테이블이 필요하다(ERD 변경).
		surveyResponseRepository.rebindRequest(draft.getId(), idempotencyKey);
		return view(draft, definitionOf(draft).definition());
	}

	@Transactional(readOnly = true)
	public SurveySessionResponse get(long userId, long sessionId) {
		SurveyResponse response = owned(userId, sessionId);
		return view(response, definitionOf(response).definition());
	}

	@Transactional(readOnly = true)
	public QuestionnaireResponse questionnaireOf(long userId, long sessionId) {
		FrozenDefinition stored = definitionOf(owned(userId, sessionId));
		return new QuestionnaireResponse(Manifest.of(stored.definition()), stored.definition().questionCount(),
			stored.definition().questions(), stored.metadataSchema());
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
				SurveySessionResponse view = view(draft, definitionOf(draft).definition());
				return new Progress(view.sessionId(), view.answeredCount(), view.questionCount(),
					view.nextQuestionId());
			})
			.orElseGet(() -> new Progress(null, 0, current.questionCount(), current.questionIds().getFirst()));
	}

	public record Progress(Long sessionId, int answeredCount, int questionCount, String nextQuestionId) {
	}

	private SurveyResponse owned(long userId, long sessionId) {
		// 남의 회차와 없는 회차를 구분하지 않는다(존재 여부를 알려 주지 않기 위해).
		return surveyResponseRepository.findByIdAndUserId(sessionId, userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
	}

	// ponytail: 키링 등록 API(영빈 담당) 전이라 키링 단계와 상관없이 "프로필 등록을 마쳤는지"로만 설문을 연다.
	// 키링 API가 생기면 컨트롤러의 @RequiresOnboarding 을 SURVEY_REQUIRED 로 바꾸고 이 검사는 지운다.
	private void requireRegisteredProfile(long userId) {
		if (!profileRepository.findById(userId).map(Profile::isRegistered).orElse(false)) {
			throw new BusinessException(ErrorCode.ONBOARDING_REQUIRED);
		}
	}

	private FrozenDefinition definitionOf(SurveyResponse response) {
		if (response.getDefinitionSnapshot() != null) {
			FrozenDefinition stored;
			try {
				stored = jsonMapper.readValue(response.getDefinitionSnapshot(), FrozenDefinition.class);
			} catch (RuntimeException exception) {
				throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
			}
			if (stored == null || stored.definition() == null || stored.metadataSchema() == null
				|| !response.getVersionCode().equals(stored.definition().surveyVersion())
				|| stored.definition().contentHash() == null || stored.definition().questions() == null
				|| stored.definition().questionIds() == null) {
				throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
			}
			return stored;
		}
		// 과거 파일의 해시/문항을 새 snapshot으로 역추정하지 않는다. 없는 버전은 복구 전까지 차단한다.
		SurveyDefinition definition = catalog.find(response.getVersionCode())
			.orElseThrow(() -> new BusinessException(ErrorCode.SERVICE_UNAVAILABLE));
		return new FrozenDefinition(definition, catalog.metadataSchema());
	}

	private QuestionnaireResponse questionnaire(SurveyDefinition definition) {
		return new QuestionnaireResponse(Manifest.of(definition), definition.questionCount(),
			definition.questions(), catalog.metadataSchema());
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
		JsonNode context = response.getResponseContext() == null ? null
			: jsonMapper.readTree(response.getResponseContext());
		return new SurveySessionResponse(response.getId(), Manifest.of(definition),
			response.isCompleted() ? "COMPLETED" : "DRAFT", response.getRevision(), answered.size(),
			definition.questionCount(), nextQuestionId, answered, context == null ? null : context.get("metadata"),
			response.getCompletedAt());
	}

	public record FrozenDefinition(SurveyDefinition definition, JsonNode metadataSchema) {
	}
}
