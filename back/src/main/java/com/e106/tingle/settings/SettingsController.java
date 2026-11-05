package com.e106.tingle.settings;

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

import com.e106.tingle.settings.SettingsService.VersionedSettings;
import com.e106.tingle.settings.dto.SettingsResponse;
import com.e106.tingle.user.OnboardingStatus;
import com.e106.tingle.user.RequiresOnboarding;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;

/** 본인 경로 확인 후 서비스에서 현재 가입 단계와 설정 버전을 검사한다. */
@RestController
@RequestMapping("/api/users/{userId}/settings")
@RequiredArgsConstructor
public class SettingsController {

	private final SettingsService service;

	@GetMapping
	@RequiresOnboarding(OnboardingStatus.AGREEMENT_REQUIRED)
	public ResponseEntity<SettingsResponse> get(@AuthenticationPrincipal Jwt jwt, @PathVariable long userId) {
		long authenticatedUserId = Long.parseLong(jwt.getSubject());
		SettingsRules.requireOwner(authenticatedUserId, userId);
		return response(service.get(authenticatedUserId, userId));
	}

	@PutMapping
	@RequiresOnboarding(OnboardingStatus.AGREEMENT_REQUIRED)
	public ResponseEntity<SettingsResponse> save(@AuthenticationPrincipal Jwt jwt, @PathVariable long userId,
		@RequestBody JsonNode body, @RequestHeader(value = "If-Match", required = false) String ifMatch) {
		long authenticatedUserId = Long.parseLong(jwt.getSubject());
		SettingsRules.requireOwner(authenticatedUserId, userId);
		return response(service.save(authenticatedUserId, userId, SettingsRules.parse(body), ifMatch));
	}

	private static ResponseEntity<SettingsResponse> response(VersionedSettings result) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag(result.etag()).body(result.body());
	}
}
