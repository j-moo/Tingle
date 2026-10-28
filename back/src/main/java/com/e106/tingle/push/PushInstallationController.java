package com.e106.tingle.push;

import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.e106.tingle.push.dto.PushAcknowledgement;
import com.e106.tingle.push.dto.RegisterPushRequest;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/push-installations")
@RequiredArgsConstructor
public class PushInstallationController {

	private final PushInstallationService service;

	@PutMapping
	public ResponseEntity<PushAcknowledgement> register(@AuthenticationPrincipal Jwt jwt,
		@Valid @RequestBody RegisterPushRequest request) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
			.body(service.register(Long.parseLong(jwt.getSubject()),
				UUID.fromString(jwt.getClaimAsString("sid")), request));
	}

	@DeleteMapping("/{installationId}")
	public ResponseEntity<Void> remove(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID installationId) {
		service.remove(Long.parseLong(jwt.getSubject()), UUID.fromString(jwt.getClaimAsString("sid")), installationId);
		return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
	}
}
