package com.e106.tingle.settings;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.e106.tingle.global.error.GlobalExceptionHandler;
import com.e106.tingle.settings.SettingsService.VersionedSettings;
import com.e106.tingle.settings.dto.SettingsRequest;
import com.e106.tingle.settings.dto.SettingsResponse;

/** MVC 입력·본인 경계 검사. 실제 인증·가입·DB 검사는 별도 통합 테스트로 확인한다. */
class SettingsControllerTest {

	private static final String OPTIONS = "{\"matchingEnabled\":false,\"requirePartnerPhoto\":true}";
	private final SettingsService service = mock(SettingsService.class);
	private MockMvc mvc;

	@BeforeEach
	void prepare() {
		mvc = MockMvcBuilders.standaloneSetup(new SettingsController(service))
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
	void getReturnsBothStoredFlagsVersionAndNoStore() throws Exception {
		given(service.get(7, 7)).willReturn(new VersionedSettings(new SettingsResponse(true, false, 4), "\"4\""));
		mvc.perform(get("/api/users/7/settings")).andExpect(status().isOk())
			.andExpect(header().string("ETag", "\"4\""))
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.matchingEnabled").value(true))
			.andExpect(jsonPath("$.requirePartnerPhoto").value(false))
			.andExpect(jsonPath("$.settingsVersion").value(4));
	}

	@Test
	void putForwardsStrongEtagAndReturnsUpdatedOptions() throws Exception {
		given(service.save(eq(7L), eq(7L), any(SettingsRequest.class), eq("\"4\"")))
			.willReturn(new VersionedSettings(new SettingsResponse(false, true, 5), "\"5\""));
		mvc.perform(put("/api/users/7/settings").contentType(MediaType.APPLICATION_JSON)
			.content(OPTIONS).header("If-Match", "\"4\""))
			.andExpect(status().isOk()).andExpect(header().string("ETag", "\"5\""))
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.matchingEnabled").value(false))
			.andExpect(jsonPath("$.requirePartnerPhoto").value(true))
			.andExpect(jsonPath("$.settingsVersion").value(5));
	}

	@Test
	void anotherUserIsRejectedBeforeServiceAccess() throws Exception {
		mvc.perform(get("/api/users/8/settings")).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("FORBIDDEN"));
		mvc.perform(put("/api/users/8/settings").contentType(MediaType.APPLICATION_JSON).content(OPTIONS))
			.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
		verifyNoInteractions(service);
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"{}", "null", "[]", "{\"matchingEnabled\":true}",
		"{\"matchingEnabled\":null,\"requirePartnerPhoto\":false}",
		"{\"matchingEnabled\":\"true\",\"requirePartnerPhoto\":false}",
		"{\"matchingEnabled\":1,\"requirePartnerPhoto\":false}",
		"{\"matchingEnabled\":true,\"requirePartnerPhoto\":null}",
		"{\"matchingEnabled\":true,\"requirePartnerPhoto\":\"false\"}",
		"{\"matchingEnabled\":true,\"requirePartnerPhoto\":false,\"ledEnabled\":true}",
		"{\"matchingEnabled\":true,\"requirePartnerPhoto\":false,\"settingsVersion\":4}"
	})
	void invalidBooleansMissingKeysAndUnknownFieldsNeverReachService(String body) throws Exception {
		mvc.perform(put("/api/users/7/settings").contentType(MediaType.APPLICATION_JSON)
			.content(body).header("If-Match", "\"4\""))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		verifyNoInteractions(service);
	}
}
