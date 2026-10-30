package com.e106.tingle.encounter;

import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.hasKey;
import static org.mockito.BDDMockito.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.web.DelegatingServletInputStream;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.ErrorResponseException;

import com.e106.tingle.encounter.dto.EncounterSyncRequest;
import com.e106.tingle.encounter.dto.EncounterSyncRequest.DeviceProof;
import com.e106.tingle.encounter.dto.EncounterSyncRequest.Submission;
import com.e106.tingle.encounter.dto.EncounterSyncResponse.Receipt;
import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.global.error.GlobalExceptionHandler;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** 실제 JWT 필터와 PostgreSQL 통합 검증을 대신하지 않는 MVC·서비스 경계 검사다. */
class EncounterControllerTest {
	private static final String PATH = "/api/offline-encounters/sync";
	private static final UUID SUBMISSION = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
	private static final UUID EVENT = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
	private final EncounterAccessRepository access = mock(EncounterAccessRepository.class);
	private final EncounterReceipts receipts = mock(EncounterReceipts.class);
	private final JsonMapper mapper = JsonMapper.builder()
		.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
	private ValidatorFactory validation;
	private MockMvc mvc;
	private boolean trailingTokensBeforeController;

	@BeforeEach
	void prepare() {
		validation = Validation.buildDefaultValidatorFactory();
		trailingTokensBeforeController = mapper.isEnabled(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
		var service = new EncounterSyncService(access, receipts, mapper);
		mvc = MockMvcBuilders.standaloneSetup(new EncounterController(service, mapper, validation.getValidator()))
			.setMessageConverters(new ByteArrayHttpMessageConverter(), new JacksonJsonHttpMessageConverter(mapper))
			.setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
			.setControllerAdvice(new GlobalExceptionHandler()).build();
		Jwt jwt = Jwt.withTokenValue("test-only").header("alg", "none").subject("7").build();
		SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
	}

	@AfterEach
	void clear() {
		SecurityContextHolder.clearContext();
		validation.close();
	}

	@Test
	void preservesCorrelationAndNeverInventsDurableReceiptForUnsupportedProof() throws Exception {
		mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(json()))
			.andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.results[0].submissionId").value(SUBMISSION.toString()))
			.andExpect(jsonPath("$.results[0].eventId").value(EVENT.toString()))
			.andExpect(jsonPath("$.results[0].receiptStatus").value("NOT_STORED"))
			.andExpect(jsonPath("$.results[0]").value(hasKey("receiptId")))
			.andExpect(jsonPath("$.results[0]").value(hasKey("encounterStatus")))
			.andExpect(jsonPath("$.results[0]").value(hasKey("counterpartDeadlineAt")))
			.andExpect(jsonPath("$.results[0].receiptId").isEmpty())
			.andExpect(jsonPath("$.results[0].canDeleteLocalRecord").value(false))
			.andExpect(jsonPath("$.results[0].encounterStatus").isEmpty())
			.andExpect(jsonPath("$.results[0].counterpartDeadlineAt").isEmpty())
			.andExpect(jsonPath("$.results[0].retryable").value(false))
			.andExpect(jsonPath("$.results[0].errorCode").value("POLICY_UNRESOLVED"));
		var order = inOrder(access);
		order.verify(access).requireActiveMember(7);
		order.verify(access).requireOwnedRegisteredDevice(7, 1024);
		order.verifyNoMoreInteractions();
	}

	@Test
	void returnsTheStoredReceiptThroughThePublicHttpBoundary() throws Exception {
		UUID receiptId = UUID.randomUUID();
		Instant deadline = Instant.parse("2026-10-29T02:01:00Z");
		Receipt original = new Receipt(SUBMISSION, EVENT, "STORED", receiptId, true, "WAITING", deadline,
			false, null);
		var saved = mock(EncounterReceipts.StoredReceipt.class);
		given(saved.responseJson()).willReturn(mapper.writeValueAsString(original));
		given(receipts.canonicalHash(request().submissions().getFirst())).willReturn("a".repeat(64));
		given(receipts.find(7, 1024, SUBMISSION, EVENT, "a".repeat(64))).willReturn(Optional.of(saved));
		mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(json()))
			.andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.results[0].receiptId").value(receiptId.toString()))
			.andExpect(jsonPath("$.results[0].receiptStatus").value("STORED"))
			.andExpect(jsonPath("$.results[0].canDeleteLocalRecord").value(true))
			.andExpect(jsonPath("$.results[0].counterpartDeadlineAt").value(deadline.toString()));
		var order = inOrder(access, receipts);
		order.verify(access).requireActiveMember(7);
		order.verify(access).requireOwnedRegisteredDevice(7, 1024);
		order.verify(receipts).canonicalHash(request().submissions().getFirst());
		order.verify(receipts).find(7, 1024, SUBMISSION, EVENT, "a".repeat(64));
		order.verifyNoMoreInteractions();
	}

	@Test
	void rejectsConflictingStoredRequestWithoutReturningDeletePermission() throws Exception {
		given(receipts.canonicalHash(request().submissions().getFirst())).willReturn("a".repeat(64));
		given(receipts.find(7, 1024, SUBMISSION, EVENT, "a".repeat(64)))
			.willThrow(new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT));
		mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(json()))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"))
			.andExpect(jsonPath("$.results").doesNotExist());
	}

	@Test
	void unverifiedRepeatedRequestsRemainNotStoredWithoutAReceipt() {
		var service = new EncounterSyncService(access, receipts, mapper);
		var first = service.sync(7, request());
		var repeat = service.sync(7, request());
		assertThat(repeat).isEqualTo(first);
		assertThat(first.results().getFirst().receiptId()).isNull();
		assertThat(first.results().getFirst().canDeleteLocalRecord()).isFalse();
	}

	@Test
	void rejectsMissingAuthenticationBeforeAccessQueries() throws Exception {
		SecurityContextHolder.clearContext();
		mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(json()))
			.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
		verifyNoInteractions(access);
	}

	@Test
	void rejectsInactiveMemberBeforeInspectingDevice() throws Exception {
		willThrow(new BusinessException(ErrorCode.AUTH_REQUIRED)).given(access).requireActiveMember(7);
		mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(json()))
			.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
		verify(access, never()).requireOwnedRegisteredDevice(anyLong(), anyLong());
	}

	@Test
	void rejectsIncompleteSignupBeforeInspectingDevice() throws Exception {
		willThrow(new BusinessException(ErrorCode.ONBOARDING_REQUIRED)).given(access).requireActiveMember(7);
		mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(json()))
			.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ONBOARDING_REQUIRED"));
		verify(access, never()).requireOwnedRegisteredDevice(anyLong(), anyLong());
	}

	@Test
	void rejectsUnownedOrUnregisteredDeviceWithoutPublishingReceipt() throws Exception {
		willThrow(new BusinessException(ErrorCode.RESOURCE_NOT_FOUND)).given(access)
			.requireOwnedRegisteredDevice(7, 1024);
		mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(json()))
			.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
			.andExpect(jsonPath("$.results").doesNotExist());
	}

	@Test
	void enforcesWholeBodyByteLimitIncludingWhitespace() throws Exception {
		String payload = json();
		mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
			.content(payload + " ".repeat(1_048_576 - payload.length())))
			.andExpect(status().isOk());
		clearInvocations(access);
		mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
			.content(payload + " ".repeat(1_048_577 - payload.length())))
			.andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		verifyNoInteractions(access);
	}

	@Test
	void measuresUtf8BytesRatherThanStringCharacters() throws Exception {
		String multibyte = json().replace("unverified", "가".repeat(350_000));
		assertThat(multibyte.length()).isLessThan(1_048_576);
		mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(multibyte))
			.andExpect(status().isPayloadTooLarge());
		verifyNoInteractions(access);
	}

	@Test
	void boundsUnknownLengthRequestStreamBeforeParsing() throws Exception {
		HttpServletRequest request = mock(HttpServletRequest.class);
		given(request.getContentLengthLong()).willReturn(-1L);
		var bytes = new ByteArrayInputStream(new byte[1_049_576]);
		given(request.getInputStream()).willReturn(new DelegatingServletInputStream(bytes));
		var service = new EncounterSyncService(access, receipts, mapper);
		var controller = new EncounterController(service, mapper, validation.getValidator());
		Jwt actor = Jwt.withTokenValue("test-only").header("alg", "none").subject("7").build();
		assertThatThrownBy(() -> controller.sync(actor, request)).isInstanceOfSatisfying(ErrorResponseException.class,
			error -> assertThat(error.getStatusCode().value()).isEqualTo(413));
		assertThat(bytes.available()).isEqualTo(999);
		verifyNoInteractions(access);
	}

	@Test
	void rejectsTrailingObjectsAndDuplicateCriticalFieldsWithoutMutatingSharedMapper() throws Exception {
		mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(json() + " {}"))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
			.content(json().replace("\"deviceId\":1024", "\"deviceId\":1024,\"deviceId\":1025")))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		assertThat(mapper.isEnabled(DeserializationFeature.FAIL_ON_TRAILING_TOKENS))
			.isEqualTo(trailingTokensBeforeController);
		verifyNoInteractions(access);
	}

	@ParameterizedTest
	@ValueSource(strings = {"{}", "null", "{", "{\"deviceId\":1024,\"submissions\":[]}",
		"{\"deviceId\":1024,\"submissions\":[null]}", "{\"deviceId\":1024,\"submissions\":[{}]}"})
	void rejectsMalformedAndIncompleteBatchesBeforeAccess(String body) throws Exception {
		mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		verifyNoInteractions(access);
	}

	@Test
	void refusesUndeclaredProofFieldsInsteadOfSilentlyIgnoringThem() throws Exception {
		mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
			.content(json().replace("\"payload\":\"unverified\"", "\"payload\":\"unverified\",\"verified\":true")))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		verifyNoInteractions(access);
	}

	@Test
	void requiresJsonContentType() throws Exception {
		mvc.perform(post(PATH).contentType(MediaType.TEXT_PLAIN).content(json()))
			.andExpect(status().isUnsupportedMediaType());
		verifyNoInteractions(access);
	}

	private String json() {
		return mapper.writeValueAsString(request());
	}

	private EncounterSyncRequest request() {
		return new EncounterSyncRequest(1024L, List.of(new Submission(SUBMISSION, EVENT,
			Instant.parse("2026-10-29T02:00:00Z"), "policy-1", "rule-1", SUBMISSION,
			"peer-credential", new DeviceProof("unapproved-protocol", "unverified"))));
	}
}
