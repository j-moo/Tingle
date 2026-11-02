package com.e106.tingle.agreement;

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

import com.e106.tingle.agreement.dto.OptionalConsentRequest;
import com.e106.tingle.agreement.dto.OptionalConsentView;
import com.e106.tingle.agreement.dto.OptionalConsentsResponse;
import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.user.OnboardingStatus;
import com.e106.tingle.user.RequiresOnboarding;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;

/** 선택 정보(사진·자기소개·종교) 동의 조회·변경. 가입 중·가입 후 모두 쓴다. */
@RestController
@RequestMapping("/api/users/{userId}/optional-consents")
@RequiredArgsConstructor
public class OptionalConsentController {

	private final OptionalConsentService service;

	@GetMapping
	@RequiresOnboarding(OnboardingStatus.AGREEMENT_REQUIRED)
	public ResponseEntity<OptionalConsentsResponse> get(@AuthenticationPrincipal Jwt jwt,
		@PathVariable @Positive long userId) {
		requireSelf(jwt, userId);
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
			.body(new OptionalConsentsResponse(service.get(userId)));
	}

	@PutMapping("/{type}")
	@RequiresOnboarding(OnboardingStatus.AGREEMENT_REQUIRED)
	public ResponseEntity<OptionalConsentView> update(@AuthenticationPrincipal Jwt jwt,
		@PathVariable @Positive long userId, @PathVariable OptionalConsent type,
		@Valid @RequestBody OptionalConsentRequest request) {
		requireSelf(jwt, userId);
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
			.body(service.update(userId, type, request.agreed(), request.version()));
	}

	private void requireSelf(Jwt jwt, long userId) {
		if (Long.parseLong(jwt.getSubject()) != userId) {
			throw new BusinessException(ErrorCode.FORBIDDEN);
		}
	}
}
