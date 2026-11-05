package com.e106.tingle.keyring.reset;

import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

@RestController
@ConditionalOnProperty(name = "app.keyring-reset.enabled", havingValue = "true")
@RequestMapping("/api/keyrings/{deviceId}/reset-runs")
@RequiredArgsConstructor
public class KeyringResetController {
	private final KeyringResetService service;

	@PostMapping
	@RequiresOnboarding(OnboardingStatus.KEYRING_REQUIRED)
	public ResponseEntity<KeyringResetService.View> create(@AuthenticationPrincipal Jwt jwt,
		@PathVariable @Positive long deviceId, @RequestHeader("Idempotency-Key") String key,
		@Valid @RequestBody Start request) {
		var result = service.create(user(jwt), family(jwt), deviceId, uuid(key), request.discardUnsentConfirmed(),
			request.deviceChallenge());
		return ResponseEntity.status(result.created() ? 201 : 200).cacheControl(CacheControl.noStore())
			.body(result.run());
	}

	@GetMapping("/{resetRunId}")
	@RequiresOnboarding(OnboardingStatus.KEYRING_REQUIRED)
	public ResponseEntity<KeyringResetService.View> get(@AuthenticationPrincipal Jwt jwt,
		@PathVariable @Positive long deviceId, @PathVariable String resetRunId) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
			.body(service.get(user(jwt), family(jwt), deviceId, uuid(resetRunId)));
	}

	@PostMapping("/{resetRunId}/ack")
	@RequiresOnboarding(OnboardingStatus.KEYRING_REQUIRED)
	public ResponseEntity<KeyringResetService.View> complete(@AuthenticationPrincipal Jwt jwt,
		@PathVariable @Positive long deviceId, @PathVariable String resetRunId,
		@Valid @RequestBody Complete request) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
			.body(service.complete(user(jwt), family(jwt), deviceId, uuid(resetRunId), request.proof()));
	}

	@PostMapping("/{resetRunId}/command")
	@RequiresOnboarding(OnboardingStatus.KEYRING_REQUIRED)
	public ResponseEntity<KeyringResetService.View> refresh(@AuthenticationPrincipal Jwt jwt,
		@PathVariable @Positive long deviceId, @PathVariable String resetRunId,
		@Valid @RequestBody(required = false) Refresh request) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
			.body(service.refreshCommand(user(jwt), family(jwt), deviceId, uuid(resetRunId),
				request == null ? null : request.deviceChallenge()));
	}

	private static long user(Jwt jwt) {
		return Long.parseLong(jwt.getSubject());
	}

	private static UUID family(Jwt jwt) {
		return uuid(jwt.getClaimAsString("sid"));
	}

	private static UUID uuid(String value) {
		try {
			UUID id = UUID.fromString(value);
			if (!id.toString().equalsIgnoreCase(value)) {
				throw new IllegalArgumentException();
			}
			return id;
		} catch (IllegalArgumentException | NullPointerException exception) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST);
		}
	}

	public record Start(@NotNull Boolean discardUnsentConfirmed, @Valid ResetChallenge deviceChallenge) {
	}

	public record Refresh(@Valid ResetChallenge deviceChallenge) {
	}

	public record Complete(@NotBlank @Size(max = 2048) String proof) {
	}
}
