package com.e106.tingle.keyring;

import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.keyring.dto.KeyringView;
import com.e106.tingle.keyring.dto.RegisterKeyringRequest;
import com.e106.tingle.user.OnboardingStatus;
import com.e106.tingle.user.RequiresOnboarding;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/keyrings")
@RequiredArgsConstructor
public class KeyringRegistrationController {

	private final KeyringRegistrationService service;

	@PostMapping
	@RequiresOnboarding(OnboardingStatus.KEYRING_REQUIRED)
	public ResponseEntity<KeyringView> register(@AuthenticationPrincipal Jwt jwt,
		@RequestHeader("Idempotency-Key") String requestKey, @Valid @RequestBody RegisterKeyringRequest request) {
		UUID key;
		try {
			key = UUID.fromString(requestKey);
			if (!key.toString().equalsIgnoreCase(requestKey)) {
				throw new IllegalArgumentException();
			}
		} catch (IllegalArgumentException exception) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST);
		}
		return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
			.body(service.register(Long.parseLong(jwt.getSubject()), UUID.fromString(jwt.getClaimAsString("sid")),
				key, request));
	}
}
