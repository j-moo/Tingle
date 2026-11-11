package com.e106.tingle.keyring;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.e106.tingle.keyring.dto.KeyringView;
import com.e106.tingle.keyring.dto.Keyrings;
import com.e106.tingle.keyring.dto.PurchaseInfo;
import com.e106.tingle.keyring.dto.UpdateKeyringLabelRequest;
import com.e106.tingle.user.OnboardingStatus;
import com.e106.tingle.user.RequiresOnboarding;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/keyrings")
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

	@PatchMapping("/{deviceId}")
	@RequiresOnboarding(OnboardingStatus.KEYRING_REQUIRED)
	public ResponseEntity<KeyringView> updateLabel(@AuthenticationPrincipal Jwt jwt, @PathVariable long deviceId,
		@RequestBody UpdateKeyringLabelRequest request) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
			.body(service.updateLabel(Long.parseLong(jwt.getSubject()), deviceId, request.label()));
	}
}
