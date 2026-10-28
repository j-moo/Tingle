package com.e106.tingle.survey;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.e106.tingle.survey.dto.CreateSessionRequest;
import com.e106.tingle.survey.dto.QuestionnaireResponse;
import com.e106.tingle.survey.dto.SurveySessionResponse;
import com.e106.tingle.user.OnboardingStatus;
import com.e106.tingle.user.RequiresOnboarding;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 설문 회차 API. 회차 응답에는 revision 을 ETag 로도 실어, 응답 저장 때 If-Match 로 돌려받는다.
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
		@RequestHeader("Idempotency-Key") UUID idempotencyKey, @Valid @RequestBody CreateSessionRequest request) {
		SurveySessionResponse session = surveyService.start(userId(jwt), idempotencyKey, request.surveyVersion());
		return withETag(ResponseEntity.status(HttpStatus.CREATED), session);
	}

	@RequiresOnboarding(OnboardingStatus.KEYRING_REQUIRED)
	@GetMapping("/survey-sessions/{sessionId}")
	public ResponseEntity<SurveySessionResponse> get(@AuthenticationPrincipal Jwt jwt, @PathVariable long sessionId) {
		return withETag(ResponseEntity.ok(), surveyService.get(userId(jwt), sessionId));
	}

	@RequiresOnboarding(OnboardingStatus.KEYRING_REQUIRED)
	@GetMapping("/survey-sessions/{sessionId}/questionnaire")
	public QuestionnaireResponse questionnaire(@AuthenticationPrincipal Jwt jwt, @PathVariable long sessionId) {
		return surveyService.questionnaireOf(userId(jwt), sessionId);
	}

	private static ResponseEntity<SurveySessionResponse> withETag(ResponseEntity.BodyBuilder builder,
		SurveySessionResponse session) {
		return builder.eTag(Long.toString(session.revision())).body(session);
	}

	private static long userId(Jwt jwt) {
		return Long.parseLong(jwt.getSubject());
	}
}
