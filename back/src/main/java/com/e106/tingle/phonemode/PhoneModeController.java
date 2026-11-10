package com.e106.tingle.phonemode;

import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.global.error.ErrorLog;
import com.e106.tingle.global.error.ErrorResponse;
import com.e106.tingle.global.i18n.Messages;
import com.e106.tingle.phonemode.PhoneEncounterService.Encounter;
import com.e106.tingle.phonemode.PhoneEncounterService.Registration;
import com.e106.tingle.phonemode.PhoneEncounterService.Token;
import com.e106.tingle.phonemode.dto.PhoneModeRequests.DeviceResponse;
import com.e106.tingle.phonemode.dto.PhoneModeRequests.Issue;
import com.e106.tingle.phonemode.dto.PhoneModeRequests.Register;
import com.e106.tingle.phonemode.dto.PhoneModeRequests.Submit;
import com.e106.tingle.user.OnboardingStatus;
import com.e106.tingle.user.RequiresOnboarding;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/phone-mode")
@Profile("dev & !prod")
@ConditionalOnProperty(name = "app.phone-mode.enabled", havingValue = "true")
@RequiredArgsConstructor
public class PhoneModeController {
	private final PhoneEncounterService service;

	@PostMapping(value = "/devices", consumes = MediaType.APPLICATION_JSON_VALUE)
	@RequiresOnboarding(OnboardingStatus.AGREEMENT_REQUIRED)
	public ResponseEntity<DeviceResponse> register(@AuthenticationPrincipal Jwt jwt,
		@RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody Register request) {
		Registration result = service.register(userId(jwt), key, request.installationId(), request.replaceExisting());
		return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
			.cacheControl(CacheControl.noStore()).body(new DeviceResponse(result.deviceId(), result.status()));
	}

	@PostMapping(value = "/encounter-tokens", consumes = MediaType.APPLICATION_JSON_VALUE)
	@RequiresOnboarding(OnboardingStatus.AGREEMENT_REQUIRED)
	public ResponseEntity<Token> issue(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") UUID key,
		@Valid @RequestBody Issue request) {
		return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
			.body(service.issue(userId(jwt), key, request.deviceId()));
	}

	@PostMapping(value = "/encounters/submissions", consumes = MediaType.APPLICATION_JSON_VALUE)
	@RequiresOnboarding(OnboardingStatus.AGREEMENT_REQUIRED)
	public ResponseEntity<Encounter> submit(@AuthenticationPrincipal Jwt jwt,
		@RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody Submit request) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.submit(userId(jwt), key,
			request.protocolVersion(), request.ownToken(), request.ownNonce(), request.peerToken(),
			request.peerNonce()));
	}

	@GetMapping("/encounters/{encounterId}")
	@RequiresOnboarding(OnboardingStatus.AGREEMENT_REQUIRED)
	public ResponseEntity<Encounter> status(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID encounterId) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.status(userId(jwt), encounterId));
	}

	private static long userId(Jwt jwt) {
		if (jwt == null) {
			throw new BusinessException(ErrorCode.AUTH_REQUIRED);
		}
		return Long.parseLong(jwt.getSubject());
	}

	@ExceptionHandler({TransientDataAccessException.class, DataAccessResourceFailureException.class})
	public ResponseEntity<ErrorResponse> temporaryFailure(DataAccessException exception, HttpServletRequest request) {
		ErrorCode error = ErrorCode.SERVICE_UNAVAILABLE;
		ErrorLog.write(request, HttpStatus.SERVICE_UNAVAILABLE, error, exception);
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
			.body(new ErrorResponse(error.name(), Messages.get("error." + error.name())));
	}
}
