package com.e106.tingle.safety;

import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.safety.dto.BlockRequest;
import com.e106.tingle.safety.dto.BlockResult;
import com.e106.tingle.safety.dto.LeaveResult;
import com.e106.tingle.safety.dto.ReportRequest;
import com.e106.tingle.safety.dto.ReportResult;
import com.e106.tingle.user.OnboardingStatus;
import com.e106.tingle.user.RequiresOnboarding;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class SafetyController {

	private final SafetyService service;

	@PostMapping("/reports")
	@RequiresOnboarding(OnboardingStatus.AGREEMENT_REQUIRED)
	public ResponseEntity<ReportResult> report(@AuthenticationPrincipal Jwt jwt,
		@RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody ReportRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
			.body(service.report(Long.parseLong(jwt.getSubject()), key, request));
	}

	@PostMapping("/blocks")
	@RequiresOnboarding(OnboardingStatus.AGREEMENT_REQUIRED)
	public ResponseEntity<BlockResult> block(@AuthenticationPrincipal Jwt jwt,
		@RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody BlockRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
			.body(service.block(Long.parseLong(jwt.getSubject()), key, request));
	}

	@PostMapping("/chat-rooms/{chatRoomId}/leave")
	@RequiresOnboarding(OnboardingStatus.AGREEMENT_REQUIRED)
	public ResponseEntity<LeaveResult> leave(@AuthenticationPrincipal Jwt jwt,
		@PathVariable @Positive long chatRoomId, @RequestHeader("Idempotency-Key") UUID key,
		@RequestBody(required = false) byte[] body) {
		if (body != null && body.length > 0) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST);
		}
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
			.body(service.leave(Long.parseLong(jwt.getSubject()), key, chatRoomId));
	}
}
