package com.e106.tingle.icebreaker;

import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.http.CacheControl;
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
import com.e106.tingle.icebreaker.dto.IcebreakerTask;
import com.e106.tingle.icebreaker.dto.IcebreakersStatus;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/chat-rooms/{id}/icebreakers")
@RequiredArgsConstructor
public class IcebreakerController {
	private static final Pattern IDEMPOTENCY_KEY = Pattern.compile(
		"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
	private final IcebreakerService service;

	@PostMapping
	public ResponseEntity<IcebreakerTask> request(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
		@RequestHeader("Idempotency-Key") String rawKey, @RequestBody(required = false) String body) {
		if (!IDEMPOTENCY_KEY.matcher(rawKey).matches() || body != null && !body.isBlank()) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST);
		}
		return ResponseEntity.status(HttpStatus.ACCEPTED).cacheControl(CacheControl.noStore())
			.body(service.request(id, Long.parseLong(jwt.getSubject()), UUID.fromString(rawKey)));
	}

	@GetMapping
	public ResponseEntity<IcebreakersStatus> get(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
			.body(service.get(id, Long.parseLong(jwt.getSubject())));
	}
}
