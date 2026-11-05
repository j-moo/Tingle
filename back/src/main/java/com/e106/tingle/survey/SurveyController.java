package com.e106.tingle.survey;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.e106.tingle.survey.dto.CreateSessionRequest;
import com.e106.tingle.survey.dto.MigrationPlanResponse;
import com.e106.tingle.survey.dto.MigrationRequest;
import com.e106.tingle.survey.dto.QuestionnaireResponse;
import com.e106.tingle.survey.dto.SurveySessionResponse;
import com.e106.tingle.user.OnboardingStatus;
import com.e106.tingle.user.RequiresOnboarding;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;

/**
 * 설문 회차 API. sessionId 는 survey.id 숫자이고, 남의 회차와 없는 회차는 똑같이 404 다.
 * ponytail: 키링 등록 API 전이라 KEYRING_REQUIRED 단계부터 열고, 회차 시작은 서비스에서 프로필 등록 여부를 본다.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class SurveyController {

	private final SurveyService surveyService;

	@RequiresOnboarding(OnboardingStatus.KEYRING_REQUIRED)
	@GetMapping("/surveys/current")
	public QuestionnaireResponse current() {
		return surveyService.currentQuestionnaire();
	}

	@RequiresOnboarding(OnboardingStatus.KEYRING_REQUIRED)
	@PostMapping("/survey-sessions")
	public ResponseEntity<SurveySessionResponse> start(@AuthenticationPrincipal Jwt jwt,
		@Valid @RequestBody CreateSessionRequest request) {
		return tagged(ResponseEntity.status(HttpStatus.CREATED), surveyService.start(userId(jwt),
			request.surveyVersion()));
	}

	@RequiresOnboarding(OnboardingStatus.KEYRING_REQUIRED)
	@GetMapping("/survey-sessions/{sessionId}")
	public ResponseEntity<SurveySessionResponse> get(@AuthenticationPrincipal Jwt jwt,
		@PathVariable @Positive long sessionId) {
		return tagged(ResponseEntity.ok(), surveyService.get(userId(jwt), sessionId));
	}

	/**
	 * 설문 중 고른 선택 나이 정보 저장. 본문 {"ownAge", "desiredAgeMin", "desiredAgeMax"}(각 19~100 정수 또는 null),
	 * If-Match 는 직전 조회의 ETag. 프로필 생년월일·희망 조건은 바꾸지 않는다.
	 */
	@RequiresOnboarding(OnboardingStatus.KEYRING_REQUIRED)
	@PutMapping("/survey-sessions/{sessionId}/metadata")
	public ResponseEntity<SurveySessionResponse> saveMetadata(@AuthenticationPrincipal Jwt jwt,
		@PathVariable @Positive long sessionId, @RequestBody JsonNode body,
		@RequestHeader(value = "If-Match", required = false) String ifMatch) {
		return tagged(ResponseEntity.ok(), surveyService.saveMetadata(userId(jwt), sessionId, body, ifMatch));
	}

	@RequiresOnboarding(OnboardingStatus.KEYRING_REQUIRED)
	@GetMapping("/survey-sessions/{sessionId}/questionnaire")
	public QuestionnaireResponse questionnaire(@AuthenticationPrincipal Jwt jwt,
		@PathVariable @Positive long sessionId) {
		return surveyService.questionnaireOf(userId(jwt), sessionId);
	}

	/**
	 * 본문 {"value": 답}. value 가 없거나 숫자·문자열·문자열 배열이 아니면 400, 문항 규칙에 맞지 않으면 422.
	 */
	@RequiresOnboarding(OnboardingStatus.KEYRING_REQUIRED)
	@PutMapping("/survey-sessions/{sessionId}/answers/{questionId}")
	public SurveySessionResponse saveAnswer(@AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long sessionId,
		@PathVariable String questionId, @RequestBody JsonNode body) {
		return surveyService.saveAnswer(userId(jwt), sessionId, questionId, body.get("value"));
	}

	@RequiresOnboarding(OnboardingStatus.KEYRING_REQUIRED)
	@DeleteMapping("/survey-sessions/{sessionId}/answers/{questionId}")
	public SurveySessionResponse deleteAnswer(@AuthenticationPrincipal Jwt jwt,
		@PathVariable @Positive long sessionId, @PathVariable String questionId) {
		return surveyService.deleteAnswer(userId(jwt), sessionId, questionId);
	}

	@RequiresOnboarding(OnboardingStatus.KEYRING_REQUIRED)
	@PostMapping("/survey-sessions/{sessionId}/complete")
	public SurveySessionResponse complete(@AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long sessionId) {
		return surveyService.complete(userId(jwt), sessionId);
	}

	@RequiresOnboarding(OnboardingStatus.KEYRING_REQUIRED)
	@GetMapping("/survey-sessions/{sessionId}/migration-plan")
	public MigrationPlanResponse migrationPlan(@AuthenticationPrincipal Jwt jwt,
		@PathVariable @Positive long sessionId, @RequestParam @NotBlank @Size(max = 30) String targetSurveyVersion) {
		return surveyService.migrationPlan(userId(jwt), sessionId, targetSurveyVersion);
	}

	@RequiresOnboarding(OnboardingStatus.KEYRING_REQUIRED)
	@PostMapping("/survey-sessions/{sessionId}/migrations")
	@ResponseStatus(HttpStatus.CREATED)
	public SurveySessionResponse migrate(@AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long sessionId,
		@Valid @RequestBody MigrationRequest request) {
		return surveyService.migrate(userId(jwt), sessionId, request.targetSurveyVersion(), request.policyHash());
	}

	// ETag 는 따옴표로 감싼 revision 이다(metadata 저장의 If-Match 로 그대로 보낸다).
	private static ResponseEntity<SurveySessionResponse> tagged(ResponseEntity.BodyBuilder builder,
		SurveySessionResponse session) {
		return builder.eTag("\"" + session.revision() + "\"").body(session);
	}

	private static long userId(Jwt jwt) {
		return Long.parseLong(jwt.getSubject());
	}
}
