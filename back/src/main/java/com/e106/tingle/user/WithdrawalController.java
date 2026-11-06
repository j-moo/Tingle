package com.e106.tingle.user;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.e106.tingle.user.dto.WithdrawalEligibilityResponse;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class WithdrawalController {

	private final WithdrawalEligibilityService eligibility;

	@GetMapping("/api/users/{userId}/withdrawal-eligibility")
	@RequiresOnboarding(OnboardingStatus.AGREEMENT_REQUIRED)
	public ResponseEntity<WithdrawalEligibilityResponse> eligibility(@AuthenticationPrincipal Jwt jwt) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
			.body(eligibility.check(Long.parseLong(jwt.getSubject())));
	}
}
