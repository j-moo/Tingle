package com.e106.tingle.rule;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.e106.tingle.global.error.ErrorLog;
import com.e106.tingle.rule.RuleAdminService.InvalidDocumentException;
import com.e106.tingle.rule.dto.ActivationInput;
import com.e106.tingle.rule.dto.ApprovalResponse;
import com.e106.tingle.rule.dto.ArtifactInput;
import com.e106.tingle.rule.dto.ChannelResponse;
import com.e106.tingle.rule.dto.ReleaseConfirmation;
import com.e106.tingle.rule.dto.ReleaseInput;
import com.e106.tingle.rule.dto.ReleaseResponse;
import com.e106.tingle.rule.dto.RuleArtifactResponse;
import com.e106.tingle.rule.dto.ValidationResponse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 운영자용 설문·계산 규칙 관리 API. 회원 로그인 토큰을 그대로 쓰고, 서비스가 요청마다 users.is_operator 를 확인한다
 * (운영자가 아니면 403 FORBIDDEN). 운영자는 회원 가입 단계와 무관하므로 가입 단계 검사(OnboardingInterceptor)에서 뺀다.
 */
@RestController
@RequestMapping("/api/admin/v1")
@RequiredArgsConstructor
public class RuleAdminController {

	private final RuleAdminService service;

	@PostMapping("/rule-artifacts")
	public ResponseEntity<RuleArtifactResponse> createArtifact(@AuthenticationPrincipal Jwt jwt,
		@Valid @RequestBody ArtifactInput input) {
		return withEtag(HttpStatus.CREATED, service.createArtifact(actor(jwt), input));
	}

	@GetMapping("/rule-artifacts/{artifactId}")
	public ResponseEntity<RuleArtifactResponse> getArtifact(@AuthenticationPrincipal Jwt jwt,
		@PathVariable UUID artifactId) {
		return withEtag(HttpStatus.OK, service.getArtifact(actor(jwt), artifactId));
	}

	@PutMapping("/rule-artifacts/{artifactId}")
	public ResponseEntity<RuleArtifactResponse> updateArtifact(@AuthenticationPrincipal Jwt jwt,
		@PathVariable UUID artifactId, @RequestHeader(value = "If-Match", required = false) String ifMatch,
		@Valid @RequestBody ArtifactInput input) {
		return withEtag(HttpStatus.OK, service.updateArtifact(actor(jwt), artifactId, ifMatch, input));
	}

	@PostMapping("/releases")
	@ResponseStatus(HttpStatus.CREATED)
	public ReleaseResponse createRelease(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ReleaseInput input) {
		return service.createRelease(actor(jwt), input);
	}

	@GetMapping("/releases/{releaseId}")
	public ReleaseResponse getRelease(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID releaseId) {
		return service.getRelease(actor(jwt), releaseId);
	}

	@PostMapping("/releases/{releaseId}/validate")
	public ValidationResponse validate(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID releaseId) {
		return service.validate(actor(jwt), releaseId);
	}

	@PostMapping("/releases/{releaseId}/approvals")
	@ResponseStatus(HttpStatus.CREATED)
	public ApprovalResponse approve(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID releaseId,
		@Valid @RequestBody ReleaseConfirmation confirmation) {
		return service.approve(actor(jwt), releaseId, confirmation);
	}

	@PostMapping("/releases/{releaseId}/publish")
	public ReleaseResponse publish(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID releaseId,
		@Valid @RequestBody ReleaseConfirmation confirmation) {
		return service.publish(actor(jwt), releaseId, confirmation);
	}

	@GetMapping("/release-channels/current")
	public ChannelResponse getChannel(@AuthenticationPrincipal Jwt jwt) {
		return service.getChannel(actor(jwt));
	}

	@PutMapping("/release-channels/current")
	public ChannelResponse switchChannel(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ActivationInput input) {
		return service.switchChannel(actor(jwt), input);
	}

	// 형식 오류는 공통 오류 본문에 맞지 않는 칸 위치(fields)를 더해 돌려준다(통합 명세 Error.fields).
	@ExceptionHandler(InvalidDocumentException.class)
	public ResponseEntity<InvalidDocumentBody> invalidDocument(InvalidDocumentException exception,
		HttpServletRequest request) {
		ErrorLog.write(request, exception.getErrorCode().getStatus(), exception.getErrorCode(), exception);
		return ResponseEntity.status(exception.getErrorCode().getStatus()).contentType(MediaType.APPLICATION_JSON)
			.body(new InvalidDocumentBody(exception.getErrorCode().name(), exception.getErrorCode().getMessage(),
				exception.getFields()));
	}

	private static ResponseEntity<RuleArtifactResponse> withEtag(HttpStatus status, RuleArtifactResponse body) {
		return ResponseEntity.status(status).eTag(RuleAdminService.etag(body.revision())).body(body);
	}

	private static long actor(Jwt jwt) {
		return Long.parseLong(jwt.getSubject());
	}

	record InvalidDocumentBody(String code, String message, List<String> fields) {
	}
}
