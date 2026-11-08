package com.e106.tingle.user;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.e106.tingle.user.dto.WithdrawalEligibilityResponse;
import com.e106.tingle.withdrawal.MemberWithdrawalService;
import com.e106.tingle.withdrawal.WithdrawalIds;
import com.e106.tingle.withdrawal.dto.WithdrawalPrepared;
import com.e106.tingle.withdrawal.dto.WithdrawalView;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class WithdrawalController {

	private final WithdrawalEligibilityService eligibility;
	private final MemberWithdrawalService withdrawals;

	@GetMapping("/api/users/{userId}/withdrawal-eligibility")
	@RequiresOnboarding(OnboardingStatus.AGREEMENT_REQUIRED)
	public ResponseEntity<WithdrawalEligibilityResponse> eligibility(@AuthenticationPrincipal Jwt jwt) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
			.body(eligibility.check(Long.parseLong(jwt.getSubject())));
	}

	@PostMapping("/api/users/{userId}/withdrawals")
	@RequiresOnboarding(OnboardingStatus.AGREEMENT_REQUIRED)
	public ResponseEntity<WithdrawalPrepared> prepare(@AuthenticationPrincipal Jwt jwt,
		@RequestHeader("Idempotency-Key") String requestKey) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(withdrawals.prepare(
			Long.parseLong(jwt.getSubject()), WithdrawalIds.parse(jwt.getClaimAsString("sid")),
			WithdrawalIds.parse(requestKey), false));
	}

	@PostMapping("/api/users/{userId}/withdrawals/{withdrawalId}/execute")
	@RequiresOnboarding(OnboardingStatus.AGREEMENT_REQUIRED)
	public ResponseEntity<WithdrawalView> execute(@AuthenticationPrincipal Jwt jwt,
		@PathVariable String withdrawalId, @RequestHeader("X-Withdrawal-Receipt") String receiptToken,
		@Valid @RequestBody Confirm request) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(withdrawals.execute(
			Long.parseLong(jwt.getSubject()), WithdrawalIds.parse(jwt.getClaimAsString("sid")),
			WithdrawalIds.parse(withdrawalId), receiptToken));
	}

	@PostMapping("/api/withdrawals/{withdrawalId}/result")
	public ResponseEntity<WithdrawalView> result(@PathVariable String withdrawalId,
		@RequestHeader("X-Withdrawal-Receipt") String receiptToken) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
			.body(withdrawals.result(WithdrawalIds.parse(withdrawalId), receiptToken));
	}

	@DeleteMapping("/api/users/{userId}/withdrawals/{withdrawalId}")
	@RequiresOnboarding(OnboardingStatus.AGREEMENT_REQUIRED)
	public ResponseEntity<Void> cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable String withdrawalId) {
		withdrawals.cancelPrepared(Long.parseLong(jwt.getSubject()), WithdrawalIds.parse(jwt.getClaimAsString("sid")),
			WithdrawalIds.parse(withdrawalId));
		return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
	}

	public record Confirm(@NotNull @AssertTrue Boolean confirmed) {
	}
}
