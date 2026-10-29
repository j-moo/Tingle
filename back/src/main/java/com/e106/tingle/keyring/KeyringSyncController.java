package com.e106.tingle.keyring;

import java.util.UUID;

import org.springframework.http.HttpStatus;
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
import com.e106.tingle.keyring.dto.SyncAckRequest;
import com.e106.tingle.keyring.dto.SyncRunView;
import com.e106.tingle.user.OnboardingStatus;
import com.e106.tingle.user.RequiresOnboarding;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/keyrings/{deviceId}/sync-runs")
@RequiredArgsConstructor
public class KeyringSyncController {
	private final KeyringSyncService service;

	@PostMapping
	@RequiresOnboarding(OnboardingStatus.SYNC_PENDING)
	public ResponseEntity<SyncRunView> create(@AuthenticationPrincipal Jwt jwt,
		@PathVariable @Positive long deviceId, @RequestHeader("Idempotency-Key") String requestKey,
		@RequestBody(required = false) byte[] body) {
		if (body != null && body.length != 0) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST);
		}
		KeyringSyncService.Created result = service.create(userId(jwt), family(jwt), deviceId, uuid(requestKey));
		return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.run());
	}

	@GetMapping("/{syncRunId}")
	@RequiresOnboarding(OnboardingStatus.KEYRING_REQUIRED)
	public SyncRunView get(@AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long deviceId,
		@PathVariable String syncRunId) {
		return service.get(userId(jwt), family(jwt), deviceId, uuid(syncRunId));
	}

	@PostMapping("/{syncRunId}/ack")
	@RequiresOnboarding(OnboardingStatus.SYNC_PENDING)
	public SyncRunView acknowledge(@AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long deviceId,
		@PathVariable String syncRunId, @RequestHeader("Idempotency-Key") String requestKey,
		@Valid @RequestBody SyncAckRequest request) {
		return service.acknowledge(userId(jwt), family(jwt), deviceId, uuid(syncRunId), uuid(requestKey), request);
	}

	private static long userId(Jwt jwt) {
		return Long.parseLong(jwt.getSubject());
	}

	private static UUID family(Jwt jwt) {
		return UUID.fromString(jwt.getClaimAsString("sid"));
	}

	private static UUID uuid(String value) {
		try {
			UUID id = UUID.fromString(value);
			if (!id.toString().equalsIgnoreCase(value)) {
				throw new IllegalArgumentException();
			}
			return id;
		} catch (IllegalArgumentException exception) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST);
		}
	}
}
