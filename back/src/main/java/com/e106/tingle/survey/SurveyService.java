package com.e106.tingle.survey;

import java.util.List;
import java.util.UUID;

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

	// 새 회차의 응답 JSON. 응답은 문항 ID 키로 쌓고, 나이 정보는 비워 둔다.
	private static final String EMPTY_RESPONSE =
		"{\"answers\":{},\"metadata\":{\"ownAge\":null,\"desiredAgeMin\":null,\"desiredAgeMax\":null}}";

	private final SurveyResponseRepository surveyResponseRepository;
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
			return view(previous, definition);
		}
		SurveyResponse draft = surveyResponseRepository
			.findByUserIdAndVersionCodeAndCompletedAtIsNull(userId, surveyVersion).orElse(null);
		if (draft == null) {
			return view(surveyResponseRepository.save(
				SurveyResponse.start(userId, surveyVersion, idempotencyKey, EMPTY_RESPONSE)), definition);
		}
		// 이어가는 회차에 이번 키를 연결해, 완료 뒤 이 키로 재시도해도 같은 회차를 돌려준다.
		// ponytail: 회차당 키 하나(최근 키)만 기억한다. 그 전 키로 완료 뒤 재시도하면 새 초안이 생긴다.
		// 모든 키를 지켜야 하면 키 이력 테이블이 필요하다(ERD 변경).
		surveyResponseRepository.rebindRequest(draft.getId(), idempotencyKey);
		return view(draft, definition);
	}

	@Transactional(readOnly = true)
	public SurveySessionResponse get(long userId, UUID sessionId) {
		SurveyResponse response = owned(userId, sessionId);
		return view(response, definitionOf(response));
	}

	@Transactional(readOnly = true)
	public QuestionnaireResponse questionnaireOf(long userId, UUID sessionId) {
		return questionnaire(definitionOf(owned(userId, sessionId)));
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

	public record Progress(UUID sessionId, int answeredCount, int questionCount, String nextQuestionId) {
	}

	private SurveyResponse owned(long userId, UUID sessionId) {
		// 남의 회차와 없는 회차를 구분하지 않는다(존재 여부를 알려 주지 않기 위해).
		return surveyResponseRepository.findByPublicIdAndUserId(sessionId, userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
	}

	// ponytail: 키링 등록 API(영빈 담당) 전이라 키링 단계와 상관없이 "프로필 등록을 마쳤는지"로만 설문을 연다.
	// 키링 API가 생기면 컨트롤러의 @RequiresOnboarding 을 SURVEY_REQUIRED 로 바꾸고 이 검사는 지운다.
	private void requireRegisteredProfile(long userId) {
		if (!profileRepository.findById(userId).map(Profile::isRegistered).orElse(false)) {
			throw new BusinessException(ErrorCode.ONBOARDING_REQUIRED);
		}
	}

	private SurveyDefinition definitionOf(SurveyResponse response) {
		return catalog.find(response.getVersionCode())
			.orElseThrow(() -> new IllegalStateException("설문 버전 파일이 없습니다: " + response.getVersionCode()));
	}

	private QuestionnaireResponse questionnaire(SurveyDefinition definition) {
		return new QuestionnaireResponse(Manifest.of(definition), definition.questionCount(),
			definition.questions(), catalog.metadataSchema());
	}

	private SurveySessionResponse view(SurveyResponse response, SurveyDefinition definition) {
		JsonNode stored = jsonMapper.readTree(response.getResponseJson());
		JsonNode answers = stored.get("answers");
		List<SurveySessionResponse.Answer> answered = definition.questionIds().stream()
			.filter(answers::has)
			.map(id -> new SurveySessionResponse.Answer(id, answers.get(id)))
			.toList();
		String nextQuestionId = definition.questionIds().stream()
			.filter(id -> !answers.has(id))
			.findFirst()
			.orElse(null);
		return new SurveySessionResponse(response.getPublicId(), Manifest.of(definition),
			response.isCompleted() ? "COMPLETED" : "DRAFT", response.getRevision(), answered.size(),
			definition.questionCount(), nextQuestionId, answered, stored.get("metadata"), response.getCompletedAt());
	}
}
