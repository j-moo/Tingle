package com.e106.tingle.support.lost;

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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.e106.tingle.support.lost.dto.LostCaseInput;
import com.e106.tingle.support.lost.dto.LostCasePage;
import com.e106.tingle.support.lost.dto.LostCaseReply;
import com.e106.tingle.support.lost.dto.LostCaseView;
import com.e106.tingle.support.lost.dto.LostReauthenticationInput;
import com.e106.tingle.support.lost.dto.LostReauthenticationReference;
import com.e106.tingle.support.lost.dto.LostReauthenticationView;
import com.e106.tingle.support.lost.dto.LostTargetConfirmation;
import com.e106.tingle.support.lost.dto.LostTargetPage;
import com.e106.tingle.user.OnboardingStatus;
import com.e106.tingle.user.RequiresOnboarding;
import com.e106.tingle.withdrawal.WithdrawalIds;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class LostWithdrawalController {
	private final LostWithdrawalService service;

	@GetMapping("/api/users/{userId}/lost-keyring-targets")
	@RequiresOnboarding(OnboardingStatus.AGREEMENT_REQUIRED)
	public ResponseEntity<LostTargetPage> targets(@AuthenticationPrincipal Jwt jwt) {
		return response(service.targets(member(jwt), session(jwt)));
	}

	@PostMapping("/api/users/{userId}/lost-keyring-reauthentications/{provider}")
	@RequiresOnboarding(OnboardingStatus.AGREEMENT_REQUIRED)
	public ResponseEntity<LostReauthenticationView> reauthenticate(@AuthenticationPrincipal Jwt jwt,
		@PathVariable String provider, @RequestHeader("Idempotency-Key") String key,
		@Valid @RequestBody LostReauthenticationInput input) {
		return response(service.reauthenticate(member(jwt), session(jwt), WithdrawalIds.parse(key), provider, input));
	}

	@PostMapping("/api/users/{userId}/lost-keyring-cases")
	@RequiresOnboarding(OnboardingStatus.AGREEMENT_REQUIRED)
	public ResponseEntity<LostCaseView> create(@AuthenticationPrincipal Jwt jwt,
		@RequestHeader("Idempotency-Key") String key, @Valid @RequestBody LostCaseInput input) {
		return response(service.create(member(jwt), session(jwt), WithdrawalIds.parse(key), input));
	}

	@GetMapping("/api/users/{userId}/lost-keyring-cases")
	@RequiresOnboarding(OnboardingStatus.AGREEMENT_REQUIRED)
	public ResponseEntity<LostCasePage> list(@AuthenticationPrincipal Jwt jwt,
		@RequestParam(defaultValue = "0") long after) {
		return response(service.memberCases(member(jwt), session(jwt), after));
	}

	@GetMapping("/api/users/{userId}/lost-keyring-cases/{caseId}")
	@RequiresOnboarding(OnboardingStatus.AGREEMENT_REQUIRED)
	public ResponseEntity<LostCaseView> get(@AuthenticationPrincipal Jwt jwt, @PathVariable long caseId) {
		return response(service.memberCase(member(jwt), session(jwt), caseId));
	}

	@PostMapping("/api/users/{userId}/lost-keyring-cases/{caseId}/reauthentication")
	@RequiresOnboarding(OnboardingStatus.AGREEMENT_REQUIRED)
	public ResponseEntity<LostCaseView> refresh(@AuthenticationPrincipal Jwt jwt, @PathVariable long caseId,
		@Valid @RequestBody LostReauthenticationReference input) {
		return response(service.refreshReauthentication(member(jwt), session(jwt), caseId, input.reauthenticationId()));
	}

	@PostMapping("/api/users/{userId}/lost-keyring-cases/{caseId}/confirmation")
	@RequiresOnboarding(OnboardingStatus.AGREEMENT_REQUIRED)
	public ResponseEntity<LostCaseView> confirm(@AuthenticationPrincipal Jwt jwt, @PathVariable long caseId,
		@RequestHeader("Idempotency-Key") String key, @Valid @RequestBody LostTargetConfirmation input) {
		return response(service.confirm(member(jwt), session(jwt), caseId, WithdrawalIds.parse(key), input));
	}

	@PostMapping("/api/users/{userId}/lost-keyring-cases/{caseId}/info-requests/{infoRequestId}/acknowledgement")
	@RequiresOnboarding(OnboardingStatus.AGREEMENT_REQUIRED)
	public ResponseEntity<LostCaseView> acknowledge(@AuthenticationPrincipal Jwt jwt, @PathVariable long caseId,
		@PathVariable long infoRequestId) {
		return response(service.acknowledgeInfo(member(jwt), session(jwt), caseId, infoRequestId));
	}

	@PostMapping("/api/users/{userId}/lost-keyring-cases/{caseId}/replies")
	@RequiresOnboarding(OnboardingStatus.AGREEMENT_REQUIRED)
	public ResponseEntity<LostCaseView> reply(@AuthenticationPrincipal Jwt jwt, @PathVariable long caseId,
		@RequestHeader("Idempotency-Key") String key, @Valid @RequestBody LostCaseReply input) {
		return response(service.reply(member(jwt), session(jwt), caseId, WithdrawalIds.parse(key), input));
	}

	@PostMapping("/api/users/{userId}/lost-keyring-cases/{caseId}/cancel")
	@RequiresOnboarding(OnboardingStatus.AGREEMENT_REQUIRED)
	public ResponseEntity<LostCaseView> cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable long caseId) {
		return response(service.cancel(member(jwt), session(jwt), caseId));
	}

	private static long member(Jwt jwt) {
		return Long.parseLong(jwt.getSubject());
	}

	private static UUID session(Jwt jwt) {
		return WithdrawalIds.parse(jwt.getClaimAsString("sid"));
	}

	private static <T> ResponseEntity<T> response(T body) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
	}
}
