package com.e106.tingle.icebreaker;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.icebreaker.dto.IcebreakerTopicConsentRequest;
import com.e106.tingle.icebreaker.dto.IcebreakerTopicConsentView;
import com.e106.tingle.user.OnboardingStatus;
import com.e106.tingle.user.RequiresOnboarding;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/users/{userId}/icebreaker-topic-consent")
@RequiredArgsConstructor
public class IcebreakerTopicConsentController {

	private final IcebreakerTopicConsentService service;

	@GetMapping
	@RequiresOnboarding(OnboardingStatus.AGREEMENT_REQUIRED)
	public ResponseEntity<IcebreakerTopicConsentView> get(@AuthenticationPrincipal Jwt jwt,
		@PathVariable @Positive long userId) {
		requireSelf(jwt, userId);
		return ok(service.get(userId));
	}

	@PutMapping
	@RequiresOnboarding(OnboardingStatus.AGREEMENT_REQUIRED)
	public ResponseEntity<IcebreakerTopicConsentView> update(@AuthenticationPrincipal Jwt jwt,
		@PathVariable @Positive long userId, @Valid @RequestBody IcebreakerTopicConsentRequest request) {
		requireSelf(jwt, userId);
		return ok(service.update(userId, request.enabled(), request.noticeVersion()));
	}

	private void requireSelf(Jwt jwt, long userId) {
		if (Long.parseLong(jwt.getSubject()) != userId) {
			throw new BusinessException(ErrorCode.FORBIDDEN);
		}
	}

	private ResponseEntity<IcebreakerTopicConsentView> ok(IcebreakerTopicConsentView view) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(view);
	}
}
