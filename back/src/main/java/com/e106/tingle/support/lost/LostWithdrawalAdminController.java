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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.e106.tingle.support.lost.dto.LostCaseAction;
import com.e106.tingle.support.lost.dto.LostCasePage;
import com.e106.tingle.support.lost.dto.LostCaseView;
import com.e106.tingle.support.lost.dto.LostTargetConfirmation;
import com.e106.tingle.withdrawal.WithdrawalIds;
import com.e106.tingle.withdrawal.dto.WithdrawalView;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/admin/v1/lost-keyring-cases")
@RequiredArgsConstructor
public class LostWithdrawalAdminController {
	private final LostWithdrawalService service;

	@GetMapping
	public ResponseEntity<LostCasePage> list(@AuthenticationPrincipal Jwt jwt,
		@RequestParam(defaultValue = "0") long after) {
		return response(service.operatorCases(member(jwt), session(jwt), after));
	}

	@GetMapping("/{caseId}")
	public ResponseEntity<LostCaseView> get(@AuthenticationPrincipal Jwt jwt, @PathVariable long caseId) {
		return response(service.operatorCase(member(jwt), session(jwt), caseId));
	}

	@PostMapping("/{caseId}/actions")
	public ResponseEntity<LostCaseView> action(@AuthenticationPrincipal Jwt jwt, @PathVariable long caseId,
		@RequestHeader("Idempotency-Key") String key, @Valid @RequestBody LostCaseAction input) {
		return response(service.operatorAction(member(jwt), session(jwt), caseId, WithdrawalIds.parse(key), input));
	}

	@PostMapping("/withdrawals/{withdrawalId}/execute")
	public ResponseEntity<WithdrawalView> complete(@AuthenticationPrincipal Jwt jwt,
		@PathVariable String withdrawalId, @Valid @RequestBody LostTargetConfirmation input) {
		return response(service.complete(member(jwt), session(jwt), WithdrawalIds.parse(withdrawalId), input));
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
