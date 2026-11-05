package com.e106.tingle.safety;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.e106.tingle.global.error.GlobalExceptionHandler;
import com.e106.tingle.safety.dto.BlockRequest;
import com.e106.tingle.safety.dto.BlockResult;
import com.e106.tingle.safety.dto.LeaveResult;
import com.e106.tingle.safety.dto.ReportRequest;
import com.e106.tingle.safety.dto.ReportResult;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** MVC 계약 검사. 실제 JWT·가입·저장 검증은 PostgreSQL 통합 테스트에서 별도로 수행한다. */
class SafetyControllerTest {

	private static final UUID KEY = UUID.fromString("0c9ba4e6-c15e-4fc4-9262-26def4b8c4cd");
	private static final Instant NOW = Instant.parse("2026-10-29T02:00:00Z");
	private final SafetyService service = mock(SafetyService.class);
	private MockMvc mvc;

	@BeforeEach
	void prepare() {
		// standaloneSetup은 application.yml을 읽지 않아 실제 서버의 엄격한 JSON 설정을 명시한다.
		var mapper = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
		mvc = MockMvcBuilders.standaloneSetup(new SafetyController(service))
			.setMessageConverters(new ByteArrayHttpMessageConverter(), new JacksonJsonHttpMessageConverter(mapper))
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
	void reportReturnsCreatedResultAndAuthenticatedActor() throws Exception {
		given(service.report(eq(7L), eq(KEY), any(ReportRequest.class)))
			.willReturn(new ReportResult(11, "RECEIVED", NOW));
		mvc.perform(post("/api/reports").contentType(MediaType.APPLICATION_JSON)
			.content("{\"chatRoomId\":3,\"reason\":\"ABUSE\",\"messageIds\":[4]}")
			.header("Idempotency-Key", KEY))
			.andExpect(status().isCreated()).andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.reportId").value(11)).andExpect(jsonPath("$.status").value("RECEIVED"))
			.andExpect(jsonPath("$.createdAt").value(NOW.toString()));
		verify(service).report(eq(7L), eq(KEY), any(ReportRequest.class));
	}

	@Test
	void blockReturnsNumericRelationAndFirstEffectTime() throws Exception {
		given(service.block(eq(7L), eq(KEY), any(BlockRequest.class))).willReturn(new BlockResult(5, NOW));
		mvc.perform(post("/api/blocks").contentType(MediaType.APPLICATION_JSON)
			.content("{\"chatRoomId\":3}").header("Idempotency-Key", KEY))
			.andExpect(status().isCreated()).andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.proposalId").value(5)).andExpect(jsonPath("$.blockedAt").value(NOW.toString()))
			.andExpect(jsonPath("$.blockId").doesNotExist());
		verify(service).block(eq(7L), eq(KEY), any(BlockRequest.class));
	}

	@Test
	void leaveAcceptsNoBodyAndPreservesAuthenticatedActor() throws Exception {
		given(service.leave(7, KEY, 3)).willReturn(new LeaveResult(3, NOW, true));
		mvc.perform(post("/api/chat-rooms/3/leave").header("Idempotency-Key", KEY))
			.andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.chatRoomId").value(3)).andExpect(jsonPath("$.leftAt").value(NOW.toString()))
			.andExpect(jsonPath("$.rematchingRestricted").value(true));
		verify(service).leave(7, KEY, 3);
	}

	@ParameterizedTest
	@ValueSource(strings = {"/api/reports", "/api/blocks", "/api/chat-rooms/3/leave"})
	void requestKeyIsRequiredAndMustBeUuid(String path) throws Exception {
		mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{}")
			.header("Idempotency-Key", "not-a-uuid"))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		verifyNoInteractions(service);
	}

	@ParameterizedTest
	@ValueSource(strings = {"{}", "null", " ", "{\"userId\":7}"})
	void leaveRejectsEveryNonemptyRequestBody(String body) throws Exception {
		mvc.perform(post("/api/chat-rooms/3/leave").contentType(MediaType.APPLICATION_JSON)
			.content(body).header("Idempotency-Key", KEY))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		verifyNoInteractions(service);
	}

	@ParameterizedTest
	@ValueSource(strings = {"0", "-1", "not-a-number"})
	void leaveRejectsInvalidNumericPath(String id) throws Exception {
		mvc.perform(post("/api/chat-rooms/" + id + "/leave").header("Idempotency-Key", KEY))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		verifyNoInteractions(service);
	}

	@ParameterizedTest
	@ValueSource(strings = {"{}", "{\"proposalId\":1,\"chatRoomId\":2}", "{\"proposalId\":0}",
		"{\"chatRoomId\":-1}", "{\"proposalId\":1,\"userId\":8}",
		"{\"proposalId\":1,\"singleContext\":true}"})
	void invalidBlockContextAndUnknownFieldsNeverReachService(String body) throws Exception {
		mvc.perform(post("/api/blocks").contentType(MediaType.APPLICATION_JSON)
			.content(body).header("Idempotency-Key", KEY))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		verifyNoInteractions(service);
	}

	@ParameterizedTest
	@ValueSource(strings = {"{}", "{\"chatRoomId\":0,\"reason\":\"ABUSE\"}",
		"{\"chatRoomId\":1,\"reason\":\"UNKNOWN\"}",
		"{\"chatRoomId\":1,\"reason\":\"SPAM\",\"messageIds\":[null]}",
		"{\"chatRoomId\":1,\"reason\":\"SPAM\",\"reporterId\":8}"})
	void invalidReportContextReasonAndUnknownFieldsNeverReachService(String body) throws Exception {
		mvc.perform(post("/api/reports").contentType(MediaType.APPLICATION_JSON)
			.content(body).header("Idempotency-Key", KEY))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		verifyNoInteractions(service);
	}
}
