package com.e106.tingle.preference;

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

import java.util.List;

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
import com.e106.tingle.preference.PreferencesService.VersionedPreferences;
import com.e106.tingle.preference.dto.PreferencesRequest;
import com.e106.tingle.preference.dto.PreferencesResponse;
import com.e106.tingle.user.Gender;

/**
 * 실제 MVC 경계 검증. JWT 서명·세션 필터와 A 가입 연동은 이 테스트의 검증 범위가 아니다.
 */
class PreferencesControllerTest {

	private static final String ANY = "{\"desiredAgeMode\":\"ANY\",\"desiredAgeMin\":null,\"desiredAgeMax\":null}";
	private final PreferencesService service = mock(PreferencesService.class);
	private MockMvc mvc;

	@BeforeEach
	void prepare() {
		mvc = MockMvcBuilders.standaloneSetup(new PreferencesController(service))
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
	void getReturnsReadOnlyGenderNullUnknownAndNoStoreEtag() throws Exception {
		given(service.get(7, 7)).willReturn(new VersionedPreferences(
			new PreferencesResponse(List.of(Gender.FEMALE), null, null, null), "\"4\""));
		mvc.perform(get("/api/users/7/preferences"))
			.andExpect(status().isOk()).andExpect(header().string("ETag", "\"4\""))
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.desiredGenders[0]").value("FEMALE"))
			.andExpect(jsonPath("$.desiredAgeMode").doesNotExist());
	}

	@Test
	void putReturnsPersistedResponseAndNewVersion() throws Exception {
		given(service.save(eq(7L), eq(7L), any(PreferencesRequest.class), eq("\"4\"")))
			.willReturn(new VersionedPreferences(new PreferencesResponse(List.of(Gender.FEMALE),
				AgePreferenceMode.ANY, null, null), "\"5\""));
		mvc.perform(put("/api/users/7/preferences").contentType(MediaType.APPLICATION_JSON)
			.content(ANY).header("If-Match", "\"4\""))
			.andExpect(status().isOk()).andExpect(header().string("ETag", "\"5\""))
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.desiredAgeMode").value("ANY"));
	}

	@Test
	void anotherUserNeverReachesService() throws Exception {
		mvc.perform(get("/api/users/8/preferences")).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("FORBIDDEN"));
		mvc.perform(put("/api/users/8/preferences").contentType(MediaType.APPLICATION_JSON).content(ANY))
			.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
		verifyNoInteractions(service);
	}

	@Test
	void genderChoiceIsNotAcceptedOrSilentlyIgnored() throws Exception {
		mvc.perform(put("/api/users/7/preferences").contentType(MediaType.APPLICATION_JSON)
			.content(ANY.replace("}", ",\"desiredGenders\":[\"FEMALE\"]}")))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		verifyNoInteractions(service);
	}

	@Test
	void versionErrorsPreserveTheirHttpCodes() throws Exception {
		given(service.save(eq(7L), eq(7L), any(PreferencesRequest.class), eq(null)))
			.willThrow(new BusinessException(ErrorCode.PRECONDITION_REQUIRED));
		mvc.perform(put("/api/users/7/preferences").contentType(MediaType.APPLICATION_JSON).content(ANY))
			.andExpect(status().isPreconditionRequired()).andExpect(jsonPath("$.code").value("PRECONDITION_REQUIRED"));
		given(service.save(eq(7L), eq(7L), any(PreferencesRequest.class), eq("\"3\"")))
			.willThrow(new BusinessException(ErrorCode.PRECONDITION_FAILED));
		mvc.perform(put("/api/users/7/preferences").contentType(MediaType.APPLICATION_JSON)
			.content(ANY).header("If-Match", "\"3\""))
			.andExpect(status().isPreconditionFailed()).andExpect(jsonPath("$.code").value("PRECONDITION_FAILED"));
	}
}
