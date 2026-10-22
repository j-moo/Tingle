package com.e106.tingle.agreement;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.e106.tingle.agreement.dto.AgreeRequest;
import com.e106.tingle.agreement.dto.AgreementsResponse;
import com.e106.tingle.user.OnboardingService;
import com.e106.tingle.user.OnboardingStatus;
import com.e106.tingle.user.RequiresOnboarding;
import com.e106.tingle.user.dto.OnboardingResponse;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 약관 조회와 동의. 경로는 docs/contracts/api/auth.md 를 따른다.
 */
@RestController
@RequiredArgsConstructor
public class AgreementController {

	private final AgreementService agreementService;
	private final OnboardingService onboardingService;

	@GetMapping("/api/v1/agreements")
	public AgreementsResponse getCurrentAgreements() {
		return AgreementsResponse.of(agreementService.currentItems());
	}

	@PostMapping("/api/v1/users/me/agreements")
	@RequiresOnboarding(OnboardingStatus.AGREEMENT_REQUIRED)
	public OnboardingResponse agree(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody AgreeRequest request) {
		long userId = Long.parseLong(jwt.getSubject());
		agreementService.agree(userId, request.agreements());
		return new OnboardingResponse(onboardingService.recordProgress(userId));
	}
}
