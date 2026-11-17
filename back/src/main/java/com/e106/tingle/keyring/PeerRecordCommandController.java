package com.e106.tingle.keyring;

import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.user.OnboardingStatus;
import com.e106.tingle.user.RequiresOnboarding;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/keyrings/{deviceId}/record-commands")
@RequiredArgsConstructor
public class PeerRecordCommandController {
	private final PeerRecordCommandService service;

	@PostMapping
	@RequiresOnboarding(OnboardingStatus.KEYRING_REQUIRED)
	public ResponseEntity<PeerRecordCommandService.Command> create(@AuthenticationPrincipal Jwt jwt,
		@PathVariable @Positive long deviceId, @RequestHeader("Idempotency-Key") String key,
		@Valid @RequestBody Request request) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.create(
			Long.parseLong(jwt.getSubject()), uuid(jwt.getClaimAsString("sid")), deviceId, uuid(key),
			request.receiptId(), request.command()));
	}

	@GetMapping("/{requestKey}")
	@RequiresOnboarding(OnboardingStatus.KEYRING_REQUIRED)
	public ResponseEntity<PeerRecordCommandService.Command> get(@AuthenticationPrincipal Jwt jwt,
		@PathVariable @Positive long deviceId, @PathVariable String requestKey) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(
			Long.parseLong(jwt.getSubject()), uuid(jwt.getClaimAsString("sid")), deviceId, uuid(requestKey)));
	}

	private static UUID uuid(String value) {
		try {
			UUID parsed = UUID.fromString(value);
			if (!parsed.toString().equalsIgnoreCase(value)) {
				throw new IllegalArgumentException();
			}
			return parsed;
		} catch (IllegalArgumentException | NullPointerException exception) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST);
		}
	}

	public record Request(@NotNull UUID receiptId,
		@NotNull @Pattern(regexp = "RESTORE_ACCOUNT_LOCK|RELEASE_AFTER_DEPARTURE|ERROR_REVIEWED") String command) {
	}
}
