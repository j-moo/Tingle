package com.e106.tingle.proposal.explanation;

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
import com.e106.tingle.proposal.explanation.dto.ExplanationStatus;
import com.e106.tingle.proposal.explanation.dto.ExplanationTask;

import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;

/** 기존 인증·가입 검증을 적용하는 설명 요청·조회 경계. 요청 본문으로 근거를 받지 않는다. */
@RestController
@RequestMapping("/api/match-proposals/{proposalId}")
@RequiredArgsConstructor
public class ExplanationController {
	private final ExplanationService service;

	@GetMapping("/explanation")
	public ResponseEntity<ExplanationStatus> get(@AuthenticationPrincipal Jwt jwt,
		@PathVariable @Positive long proposalId) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
			.body(service.get(proposalId, Long.parseLong(jwt.getSubject())));
	}

	@PostMapping("/explanations")
	public ResponseEntity<ExplanationTask> request(@AuthenticationPrincipal Jwt jwt,
		@PathVariable @Positive long proposalId, @RequestHeader("Idempotency-Key") UUID key,
		@RequestBody(required = false) JsonNode body) {
		if (body != null) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST);
		}
		return ResponseEntity.accepted().cacheControl(CacheControl.noStore())
			.body(service.request(proposalId, Long.parseLong(jwt.getSubject()), key));
	}
}
