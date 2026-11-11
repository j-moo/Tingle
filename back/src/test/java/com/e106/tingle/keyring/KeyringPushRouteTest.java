package com.e106.tingle.keyring;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.e106.tingle.auth.AuthController;
import com.e106.tingle.auth.AuthProperties;
import com.e106.tingle.auth.AuthService;
import com.e106.tingle.auth.RefreshTokenRepository;
import com.e106.tingle.auth.SecurityConfig;
import com.e106.tingle.keyring.dto.KeyringView;
import com.e106.tingle.keyring.dto.Keyrings;
import com.e106.tingle.keyring.dto.PurchaseInfo;
import com.e106.tingle.push.PushInstallationController;
import com.e106.tingle.push.PushInstallationService;
import com.e106.tingle.push.dto.PushAcknowledgement;
import com.e106.tingle.user.OnboardingService;
import com.e106.tingle.user.ProfileService;
import com.e106.tingle.user.UnderAgeWithdrawalService;
import com.e106.tingle.user.UserController;
import com.e106.tingle.user.UserPathInterceptor;

@WebMvcTest(controllers = {KeyringReadController.class, PushInstallationController.class,
	AuthController.class, UserController.class},
	properties = "app.auth.jwt-secret=only-for-route-tests-not-a-production-secret-123456")
@Import({SecurityConfig.class, UserPathInterceptor.class})
@EnableConfigurationProperties(AuthProperties.class)
class KeyringPushRouteTest {

	private static final long USER_ID = 41;
	private static final UUID FAMILY_ID = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
	private static final UUID INSTALLATION_ID = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
	private static final String PUSH_BODY = "{\"installationId\":\"" + INSTALLATION_ID
		+ "\",\"platform\":\"ANDROID\",\"token\":\"synthetic-push-token\"}";

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private KeyringReadService keyrings;

	@MockitoBean
	private PushInstallationService installations;

	@MockitoBean
	private OnboardingService onboarding;

	@MockitoBean
	private JwtDecoder decoder;

	@MockitoBean
	private RefreshTokenRepository refreshTokens;

	@MockitoBean
	private AuthService auth;

	@MockitoBean
	private ProfileService profiles;

	@MockitoBean
	private UnderAgeWithdrawalService underAgeWithdrawal;

	@BeforeEach
	void authenticateOwnedSession() {
		when(decoder.decode("valid")).thenReturn(Jwt.withTokenValue("valid").header("alg", "HS256")
			.subject(Long.toString(USER_ID)).claim("sid", FAMILY_ID.toString()).build());
		when(onboarding.canAccess(eq(USER_ID), any())).thenReturn(true);
	}

	@Test
	@DisplayName("새 키링 조회 경로는 JWT 주체와 가입 검사 및 no-store를 유지한다")
	void keyringRoutesUseAuthenticatedOwner() throws Exception {
		when(keyrings.getKeyrings(USER_ID)).thenReturn(new Keyrings(List.of(
			new KeyringView(17, "SYN-17", KeyringStatus.REGISTERED, "synthetic-1"))));
		when(keyrings.getPurchaseInfo(USER_ID)).thenReturn(new PurchaseInfo(null, true));
		mockMvc.perform(get("/api/keyrings").param("userId", "999").header("Authorization", "Bearer valid"))
			.andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].deviceId").value(17))
			.andExpect(jsonPath("$.items[0].status").value("REGISTERED"))
			.andExpect(jsonPath("$.items[0].firmwareVersion").value("synthetic-1"))
			.andExpect(jsonPath("$.items[0].boundAt").doesNotExist());
		mockMvc.perform(get("/api/keyrings/purchase-info").header("Authorization", "Bearer valid"))
			.andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.registrationRequired").value(true));
		verify(keyrings).getKeyrings(USER_ID);
		verify(keyrings).getPurchaseInfo(USER_ID);
	}

	@Test
	@DisplayName("새 푸시 경로는 현재 회원과 로그인 family 및 설치 UUID를 전달한다")
	void pushRoutesPreserveSessionAndInstallationIdentity() throws Exception {
		when(installations.register(eq(USER_ID), eq(FAMILY_ID), any()))
			.thenReturn(new PushAcknowledgement(Instant.parse("2026-10-28T00:00:00Z"), FAMILY_ID));
		mockMvc.perform(put("/api/push-installations").header("Authorization", "Bearer valid")
			.contentType(MediaType.APPLICATION_JSON).content(PUSH_BODY))
			.andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.serverTime").value("2026-10-28T00:00:00Z"))
			.andExpect(jsonPath("$.token").doesNotExist());
		mockMvc.perform(delete("/api/push-installations/" + INSTALLATION_ID)
			.header("Authorization", "Bearer valid"))
			.andExpect(status().isNoContent()).andExpect(header().string("Cache-Control", "no-store"));
		verify(installations).register(eq(USER_ID), eq(FAMILY_ID), any());
		verify(installations).remove(USER_ID, FAMILY_ID, INSTALLATION_ID);
	}

	@Test
	@DisplayName("네 새 경로 모두 Bearer 인증 없이 서비스를 호출하지 않는다")
	void allNewRoutesRequireAuthentication() throws Exception {
		for (MockHttpServletRequestBuilder request : requests()) {
			mockMvc.perform(request).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
		}
		verifyNoInteractions(keyrings, installations);
	}

	@Test
	@DisplayName("네 새 경로 모두 손상된 토큰을 거부한다")
	void allNewRoutesRejectInvalidTokens() throws Exception {
		when(decoder.decode("invalid")).thenThrow(new BadJwtException("synthetic invalid token"));
		for (MockHttpServletRequestBuilder request : requests()) {
			mockMvc.perform(request.header("Authorization", "Bearer invalid"))
				.andExpect(status().isUnauthorized());
		}
		verifyNoInteractions(keyrings, installations);
	}

	@Test
	@DisplayName("새 경로에서도 필수 가입 단계 우회를 막는다")
	void allNewRoutesEnforceOnboarding() throws Exception {
		when(onboarding.canAccess(anyLong(), any())).thenReturn(false);
		for (MockHttpServletRequestBuilder request : requests()) {
			mockMvc.perform(request.header("Authorization", "Bearer valid"))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ONBOARDING_REQUIRED"));
		}
		verifyNoInteractions(keyrings, installations);
	}

	@Test
	@DisplayName("이전 v1 경로는 새 경로의 별칭이나 중복 쓰기 진입점으로 남지 않는다")
	void legacyRoutesAreNotAliases() throws Exception {
		mockMvc.perform(get("/api/v1/keyrings").header("Authorization", "Bearer valid"))
			.andExpect(status().isNotFound());
		mockMvc.perform(get("/api/v1/keyrings/purchase-info").header("Authorization", "Bearer valid"))
			.andExpect(status().isNotFound());
		mockMvc.perform(put("/api/v1/push-installations").header("Authorization", "Bearer valid")
			.contentType(MediaType.APPLICATION_JSON).content(PUSH_BODY)).andExpect(status().isNotFound());
		mockMvc.perform(delete("/api/v1/push-installations/" + INSTALLATION_ID)
			.header("Authorization", "Bearer valid")).andExpect(status().isNotFound());
		verifyNoInteractions(keyrings, installations);
	}

	@Test
	@DisplayName("151 회원 경로 검사는 타인·잘못된 번호를 가입 검사와 조회보다 먼저 거부한다")
	void foreignUserPathIsRejectedBeforeOnboarding() throws Exception {
		for (String id : List.of("999", "invalid")) {
			mockMvc.perform(get("/api/users/" + id + "/profile").header("Authorization", "Bearer valid"))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
		}
		verifyNoInteractions(onboarding, profiles);
	}

	@Test
	@DisplayName("151 본인 회원 경로는 토큰 회원번호를 서비스에 전달한다")
	void ownUserPathPassesTheGlobalInterceptor() throws Exception {
		mockMvc.perform(get("/api/users/" + USER_ID + "/profile").header("Authorization", "Bearer valid"))
			.andExpect(status().isOk());
		verify(profiles).get(USER_ID);
	}

	@Test
	@DisplayName("151 새 로그아웃 경로는 만료된 액세스 토큰 없이 리프레시 증명과 호환 UUID를 전달한다")
	void newLogoutRoutePreservesPublicRefreshProofContract() throws Exception {
		mockMvc.perform(post("/api/auth/logout").contentType(MediaType.APPLICATION_JSON)
			.content("{\"refreshToken\":\"synthetic-proof\",\"releaseInstallationId\":\""
				+ INSTALLATION_ID + "\"}"))
			.andExpect(status().isNoContent());
		verify(auth).logout("synthetic-proof", INSTALLATION_ID);
	}

	private List<MockHttpServletRequestBuilder> requests() {
		return List.of(get("/api/keyrings"), get("/api/keyrings/purchase-info"),
			put("/api/push-installations").contentType(MediaType.APPLICATION_JSON).content(PUSH_BODY),
			delete("/api/push-installations/" + INSTALLATION_ID));
	}
}
