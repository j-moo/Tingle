package com.e106.tingle.proposal;

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

import com.e106.tingle.proposal.dto.AcceptProposalRequest;
import com.e106.tingle.proposal.dto.PartnerProfileView;
import com.e106.tingle.proposal.dto.ProposalPage;
import com.e106.tingle.proposal.dto.ProposalView;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/match-proposals")
public class ProposalController {
	private final ProposalService service;

	public ProposalController(ProposalService service) {
		this.service = service;
	}

	@GetMapping
	public ProposalPage list(@AuthenticationPrincipal Jwt jwt,
		@RequestParam(required = false) @Size(max = 256) String cursor,
		@RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
		return service.list(Long.parseLong(jwt.getSubject()), cursor, limit);
	}

	@GetMapping("/{id}")
	public ProposalView get(@AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long id) {
		return service.get(id, Long.parseLong(jwt.getSubject()));
	}

	@GetMapping("/{id}/partner-profile")
	public ResponseEntity<PartnerProfileView> profile(@AuthenticationPrincipal Jwt jwt,
		@PathVariable @Positive long id) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
			.body(service.profile(id, Long.parseLong(jwt.getSubject())));
	}

	@PostMapping("/{id}/accept")
	public ProposalView accept(@AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long id,
		@RequestHeader("Idempotency-Key") UUID idempotencyKey, @Valid @RequestBody AcceptProposalRequest request) {
		return service.decide(id, Long.parseLong(jwt.getSubject()), MatchProposal.Decision.ACCEPT,
			idempotencyKey, request.profileVersion());
	}

	@PostMapping("/{id}/decline")
	public ProposalView decline(@AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long id,
		@RequestHeader("Idempotency-Key") UUID idempotencyKey) {
		return service.decide(id, Long.parseLong(jwt.getSubject()), MatchProposal.Decision.DECLINE,
			idempotencyKey, null);
	}

}
