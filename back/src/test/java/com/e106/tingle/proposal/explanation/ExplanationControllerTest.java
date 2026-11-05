package com.e106.tingle.proposal.explanation;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.global.error.GlobalExceptionHandler;
import com.e106.tingle.proposal.explanation.dto.ExplanationStatus;
import com.e106.tingle.proposal.explanation.dto.ExplanationTask;

/** MVC 형식·서비스 연결 검사. 실제 인증·가입·저장은 별도 PostgreSQL 통합 검사 대상이다. */
class ExplanationControllerTest {
	private static final String ROUTE = "/api/match-proposals/17";
	private static final UUID KEY = UUID.fromString("9bc01cb5-cfc1-4c29-b0a6-2e2be450d51f");
	private final ExplanationService service = mock(ExplanationService.class);
	private MockMvc mvc;

	@BeforeEach
	void prepare() {
		mvc = MockMvcBuilders.standaloneSetup(new ExplanationController(service))
			.setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
			.setControllerAdvice(new GlobalExceptionHandler()).build();
		Jwt jwt = Jwt.withTokenValue("test-only").header("alg", "none").subject("7").build();
		SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
	}

	@AfterEach
	void clearAuthentication() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void getReturnsExactlyFiveFieldsWithRequiredNullVersionAndNoStore() throws Exception {
		given(service.get(17, 7)).willReturn(new ExplanationStatus("SUCCEEDED", "RULE_FALLBACK",
			"검증된 규칙 설명", List.of(), null));
		mvc.perform(get(ROUTE + "/explanation")).andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.length()").value(5))
			.andExpect(jsonPath("$.status").value("SUCCEEDED"))
			.andExpect(jsonPath("$.source").value("RULE_FALLBACK"))
			.andExpect(jsonPath("$.text").value("검증된 규칙 설명"))
			.andExpect(jsonPath("$.evidenceKeys").isEmpty())
			.andExpect(jsonPath("$.promptVersion").hasJsonPath())
			.andExpect(jsonPath("$.promptVersion").isEmpty());
		verify(service).get(17, 7);
	}

	@Test
	void postReturns202WithExactlySevenFieldsAndForwardsJwtViewerAndKey() throws Exception {
		given(service.request(17, 7, KEY)).willReturn(new ExplanationTask(17, "MATCH_EXPLANATION", "SUCCEEDED",
			ROUTE + "/explanation", null, false, 0));
		mvc.perform(post(ROUTE + "/explanations").header("Idempotency-Key", KEY))
			.andExpect(status().isAccepted()).andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.length()").value(7))
			.andExpect(jsonPath("$.proposalId").value(17))
			.andExpect(jsonPath("$.kind").value("MATCH_EXPLANATION"))
			.andExpect(jsonPath("$.status").value("SUCCEEDED"))
			.andExpect(jsonPath("$.statusUrl").value(ROUTE + "/explanation"))
			.andExpect(jsonPath("$.errorCode").hasJsonPath())
			.andExpect(jsonPath("$.errorCode").isEmpty())
			.andExpect(jsonPath("$.retryable").value(false))
			.andExpect(jsonPath("$.pollAfterSeconds").value(0));
		verify(service).request(17, 7, KEY);
	}

	@Test
	void pendingAndFailedGetKeepNullableFieldsAndNeverInventSuccessfulContent() throws Exception {
		for (String state : List.of("PENDING", "FAILED")) {
			given(service.get(17, 7)).willReturn(new ExplanationStatus(state, null, null, List.of(), null));
			mvc.perform(get(ROUTE + "/explanation")).andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.length()").value(5))
				.andExpect(jsonPath("$.status").value(state))
				.andExpect(jsonPath("$.source").hasJsonPath()).andExpect(jsonPath("$.source").isEmpty())
				.andExpect(jsonPath("$.text").hasJsonPath()).andExpect(jsonPath("$.text").isEmpty())
				.andExpect(jsonPath("$.promptVersion").hasJsonPath())
				.andExpect(jsonPath("$.promptVersion").isEmpty())
				.andExpect(jsonPath("$.evidenceKeys").isEmpty());
		}
	}

	@Test
	void missingIdempotencyKeyIs400BeforeServiceAccess() throws Exception {
		mvc.perform(post(ROUTE + "/explanations")).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		verifyNoInteractions(service);
	}

	@Test
	void malformedIdempotencyKeyIs400BeforeServiceAccess() throws Exception {
		mvc.perform(post(ROUTE + "/explanations").header("Idempotency-Key", "invalid"))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		verifyNoInteractions(service);
	}

	@Test
	void nonPositiveAndMalformedProposalIdsNeverReachService() throws Exception {
		for (String id : List.of("0", "-1", "invalid")) {
			mvc.perform(get("/api/match-proposals/" + id + "/explanation")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
			mvc.perform(post("/api/match-proposals/" + id + "/explanations").header("Idempotency-Key", KEY))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		}
		verifyNoInteractions(service);
	}

	@Test
	void everyJsonRequestBodyIncludingNullAndScalarsIsRejected() throws Exception {
		for (String body : List.of("{}", "[]", "null", "true", "0", "\"text\"", "{\"evidence\":[]}")) {
			mvc.perform(post(ROUTE + "/explanations").header("Idempotency-Key", KEY)
				.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		}
		verifyNoInteractions(service);
	}

	@Test
	void malformedJsonIs400BeforeServiceAccess() throws Exception {
		mvc.perform(post(ROUTE + "/explanations").header("Idempotency-Key", KEY)
			.contentType(MediaType.APPLICATION_JSON).content("{"))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		verifyNoInteractions(service);
	}

	@Test
	void forbiddenServiceResultRetains403AndBusinessCode() throws Exception {
		given(service.get(17, 7)).willThrow(new BusinessException(ErrorCode.FORBIDDEN));
		mvc.perform(get(ROUTE + "/explanation")).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("FORBIDDEN"));
	}

	@Test
	void anotherUsersProposalRetains404AndBusinessCode() throws Exception {
		given(service.get(17, 7)).willThrow(new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
		mvc.perform(get(ROUTE + "/explanation")).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
	}

	@Test
	void expiredProposalRetains410AndBusinessCode() throws Exception {
		given(service.get(17, 7)).willThrow(new BusinessException(ErrorCode.PROPOSAL_EXPIRED));
		mvc.perform(get(ROUTE + "/explanation")).andExpect(status().isGone())
			.andExpect(jsonPath("$.code").value("PROPOSAL_EXPIRED"));
	}

	@Test
	void reusedRequestKeyRetains409AndBusinessCode() throws Exception {
		given(service.request(17, 7, KEY)).willThrow(new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT));
		mvc.perform(post(ROUTE + "/explanations").header("Idempotency-Key", KEY))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
	}
}
