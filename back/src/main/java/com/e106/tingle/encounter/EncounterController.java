package com.e106.tingle.encounter;

import java.io.IOException;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.e106.tingle.encounter.dto.EncounterSyncRequest;
import com.e106.tingle.encounter.dto.EncounterSyncResponse;
import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Validator;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/offline-encounters")
public class EncounterController {
	private static final int MAX_BODY_BYTES = 1_048_576;
	private final EncounterSyncService service;
	private final JsonMapper mapper;
	private final Validator validator;

	public EncounterController(EncounterSyncService service, JsonMapper mapper, Validator validator) {
		this.service = service;
		this.mapper = mapper.rebuild().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
			.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
		this.validator = validator;
	}

	@PostMapping(value = "/sync", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<EncounterSyncResponse> sync(@AuthenticationPrincipal Jwt jwt, HttpServletRequest httpRequest)
		throws IOException {
		long userId = userId(jwt);
		if (httpRequest.getContentLengthLong() > MAX_BODY_BYTES) {
			throw new ErrorResponseException(HttpStatus.CONTENT_TOO_LARGE);
		}
		byte[] body = httpRequest.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
		if (body.length > MAX_BODY_BYTES) {
			throw new ErrorResponseException(HttpStatus.CONTENT_TOO_LARGE);
		}
		EncounterSyncRequest request;
		try {
			request = mapper.readValue(body, EncounterSyncRequest.class);
		} catch (JacksonException | IllegalArgumentException exception) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST);
		}
		if (request == null || !validator.validate(request).isEmpty()) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST);
		}
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.sync(userId, request));
	}

	private long userId(Jwt jwt) {
		try {
			long userId = jwt == null ? 0 : Long.parseLong(jwt.getSubject());
			if (userId > 0) {
				return userId;
			}
		} catch (NumberFormatException exception) {
			throw new BusinessException(ErrorCode.AUTH_REQUIRED);
		}
		throw new BusinessException(ErrorCode.AUTH_REQUIRED);
	}
}
