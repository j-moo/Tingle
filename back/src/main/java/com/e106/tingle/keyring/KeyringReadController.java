package com.e106.tingle.keyring;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.e106.tingle.keyring.dto.Keyrings;
import com.e106.tingle.keyring.dto.PurchaseInfo;
import com.e106.tingle.user.OnboardingStatus;
import com.e106.tingle.user.RequiresOnboarding;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/keyrings")
@RequiredArgsConstructor
public class KeyringReadController {

	private final KeyringReadService service;

	@GetMapping
	@RequiresOnboarding(OnboardingStatus.KEYRING_REQUIRED)
	public ResponseEntity<Keyrings> getKeyrings(@AuthenticationPrincipal Jwt jwt) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
			.body(service.getKeyrings(Long.parseLong(jwt.getSubject())));
	}

	@GetMapping("/purchase-info")
	@RequiresOnboarding(OnboardingStatus.KEYRING_REQUIRED)
	public ResponseEntity<PurchaseInfo> getPurchaseInfo(@AuthenticationPrincipal Jwt jwt) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
			.body(service.getPurchaseInfo(Long.parseLong(jwt.getSubject())));
	}
}
