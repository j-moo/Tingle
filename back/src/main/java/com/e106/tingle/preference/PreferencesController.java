package com.e106.tingle.preference;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.e106.tingle.preference.PreferencesService.VersionedPreferences;
import com.e106.tingle.preference.dto.PreferencesResponse;
import com.e106.tingle.user.OnboardingStatus;
import com.e106.tingle.user.RequiresOnboarding;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;

/**
 * 본인 경로를 먼저 확인하고 서비스에서 실제 가입 단계를 검사한다.
 */
@RestController
@RequestMapping("/api/users/{userId}/preferences")
@RequiredArgsConstructor
public class PreferencesController {

	private final PreferencesService service;

	@GetMapping
	@RequiresOnboarding(OnboardingStatus.AGREEMENT_REQUIRED)
	public ResponseEntity<PreferencesResponse> get(@AuthenticationPrincipal Jwt jwt, @PathVariable long userId) {
		long authenticatedUserId = Long.parseLong(jwt.getSubject());
		PreferencesRules.requireOwner(authenticatedUserId, userId);
		return response(service.get(authenticatedUserId, userId));
	}

	@PutMapping
	@RequiresOnboarding(OnboardingStatus.AGREEMENT_REQUIRED)
	public ResponseEntity<PreferencesResponse> save(@AuthenticationPrincipal Jwt jwt, @PathVariable long userId,
		@RequestBody JsonNode body, @RequestHeader(value = "If-Match", required = false) String ifMatch) {
		long authenticatedUserId = Long.parseLong(jwt.getSubject());
		PreferencesRules.requireOwner(authenticatedUserId, userId);
		return response(service.save(authenticatedUserId, userId, PreferencesRules.parse(body), ifMatch));
	}

	private static ResponseEntity<PreferencesResponse> response(VersionedPreferences result) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag(result.etag()).body(result.body());
	}
}
