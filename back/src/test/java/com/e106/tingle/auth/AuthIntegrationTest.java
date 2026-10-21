package com.e106.tingle.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

import com.e106.tingle.IntegrationTestSupport;
import com.e106.tingle.TestProtectedController;
import com.e106.tingle.TestcontainersConfiguration;
import com.e106.tingle.auth.SocialVerifier.SocialAccount;
import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;

import jakarta.servlet.RequestDispatcher;
import tools.jackson.databind.JsonNode;

/**
 * 소셜 로그인, 토큰 재발급, 로그인 세션과 로그아웃, 앱 전용 응답 형식.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "erd"})
@Import({TestcontainersConfiguration.class, TestProtectedController.class})
class AuthIntegrationTest extends IntegrationTestSupport {

	private static final String ONBOARDING = "/api/v1/users/me/onboarding";

	// ---------- 소셜 로그인 ----------

	@Test
	@DisplayName("처음 로그인하면 회원이 만들어지고 약관 동의 단계부터 시작한다")
	void firstLoginStartsAtAgreementStep() throws Exception {
		JsonNode tokens = login("kakao", newSocialId());

		assertThat(tokens.path("onboardingStatus").asString()).isEqualTo("AGREEMENT_REQUIRED");
		assertThat(tokens.path("accessToken").asString()).isNotBlank();
		assertThat(tokens.path("refreshToken").asString()).isNotBlank();
		assertThat(tokens.path("tokenType").asString()).isEqualTo("Bearer");
		// 두 만료 시각은 같은 발급 시각 기준이다 (액세스 30분, 리프레시 14일)
		Instant accessExpiresAt = Instant.parse(tokens.path("accessTokenExpiresAt").asString());
		Instant refreshExpiresAt = Instant.parse(tokens.path("refreshTokenExpiresAt").asString());
		assertThat(Duration.between(accessExpiresAt, refreshExpiresAt))
			.isEqualTo(Duration.ofDays(14).minusMinutes(30));
		assertThat(tokens.path("accessTokenExpiresAt").asString()).endsWith("Z");
		// 통합 명세 TokenPair 외의 필드는 보내지 않는다
		assertThat(tokens.propertyNames()).containsExactlyInAnyOrder("accessToken", "refreshToken", "tokenType",
			"accessTokenExpiresAt", "refreshTokenExpiresAt", "onboardingStatus");
	}

	@Test
	@DisplayName("401은 원인별 코드로 나뉜다: 토큰 없음·만료·위조·세션 종료")
	void unauthorizedCodesTellAppWhatToDo() throws Exception {
		mockMvc.perform(get(ONBOARDING)).andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
		mockMvc.perform(get(ONBOARDING).header("Authorization", "Bearer forged.token.value"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("ACCESS_TOKEN_INVALID"));

		JsonNode tokens = login("kakao", newSocialId());
		timeShift.set(Duration.ofMinutes(31));
		perform(get(ONBOARDING), tokens, null).andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("ACCESS_TOKEN_EXPIRED"));

		// 만료와 세션 종료가 겹치면 재발급해도 소용없으므로 세션 종료로 알린다
		logout(tokens.path("refreshToken").asString()).andExpect(status().isNoContent());
		perform(get(ONBOARDING), tokens, null).andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("SESSION_REVOKED"));
	}

	@Test
	@DisplayName("가입을 마친 소셜 계정으로 다시 로그인하면 READY")
	void loginAgainAfterOnboardingReturnsReady() throws Exception {
		String socialId = newSocialId();
		completeOnboarding(login("google", socialId));

		assertThat(login("google", socialId).path("onboardingStatus").asString()).isEqualTo("READY");
	}

	@Test
	@DisplayName("지원하지 않는 로그인 방식은 400 INVALID_PROVIDER")
	void unsupportedProviderReturns400InvalidProvider() throws Exception {
		mockMvc.perform(post("/api/v1/auth/apple").contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"t\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_PROVIDER"));
	}

	@Test
	@DisplayName("소셜 토큰 검증에 실패하면 401 INVALID_SOCIAL_TOKEN")
	void socialTokenVerificationFailureReturns401() throws Exception {
		given(socialVerifier.verify(eq(SocialProvider.NAVER), anyString()))
			.willThrow(new BusinessException(ErrorCode.INVALID_SOCIAL_TOKEN));

		mockMvc.perform(post("/api/v1/auth/naver").contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"t\"}"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("INVALID_SOCIAL_TOKEN"));
	}

	@Test
	@DisplayName("만료된 액세스 토큰이 헤더에 있어도 로그인은 막히지 않는다")
	void loginIsNotBlockedByExpiredAccessTokenHeader() throws Exception {
		given(socialVerifier.verify(eq(SocialProvider.KAKAO), anyString()))
			.willReturn(new SocialAccount(newSocialId(), null));

		mockMvc.perform(post("/api/v1/auth/kakao")
				.header("Authorization", "Bearer expired.or.broken")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"token\":\"t\"}"))
			.andExpect(status().isOk());
	}

	// ---------- 약관 조회 ----------

	@Test
	@DisplayName("약관 조회는 인증 없이 현재 버전을 돌려준다")
	void agreementsReturnCurrentVersionsWithoutAuth() throws Exception {
		mockMvc.perform(get("/api/v1/agreements"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.agreements[0].type").value("TERMS"))
			.andExpect(jsonPath("$.agreements[0].version").value(VERSION))
			.andExpect(jsonPath("$.agreements[0].title").value("서비스 이용약관"))
			.andExpect(jsonPath("$.agreements[0].contentUrl").isNotEmpty())
			.andExpect(jsonPath("$.agreements[1].type").value("PRIVACY"));
	}

	// ---------- 로그인 세션과 로그아웃 ----------

	@Test
	@DisplayName("로그아웃하면 그 세션의 액세스·리프레시 토큰이 바로 막히고 다른 기기 세션은 유지된다")
	void logoutRevokesOnlyThatSession() throws Exception {
		String socialId = newSocialId();
		JsonNode phone = login("kakao", socialId);
		JsonNode tablet = login("kakao", socialId);

		logout(phone.path("refreshToken").asString()).andExpect(status().isNoContent());

		perform(get(ONBOARDING), phone, null).andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("SESSION_REVOKED"));
		refresh(phone.path("refreshToken").asString()).andExpect(status().isUnauthorized());
		perform(get(ONBOARDING), tablet, null).andExpect(status().isOk());
		refresh(tablet.path("refreshToken").asString()).andExpect(status().isOk());
	}

	@Test
	@DisplayName("같은 토큰으로 다시 로그아웃해도 204, 모르는 토큰은 401 INVALID_REFRESH_TOKEN")
	void logoutIsRepeatableAndRejectsUnknownToken() throws Exception {
		String refreshToken = login("kakao", newSocialId()).path("refreshToken").asString();

		logout(refreshToken).andExpect(status().isNoContent());
		logout(refreshToken).andExpect(status().isNoContent());
		logout("unknown-token").andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
	}

	@Test
	@DisplayName("액세스 토큰이 만료돼도 리프레시 토큰으로 로그아웃할 수 있다")
	void logoutWorksAfterAccessTokenExpires() throws Exception {
		JsonNode tokens = login("kakao", newSocialId());
		timeShift.set(Duration.ofMinutes(31));

		mockMvc.perform(post("/api/v1/auth/logout")
				.header("Authorization", "Bearer " + tokens.path("accessToken").asString())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"refreshToken\":\"" + tokens.path("refreshToken").asString() + "\"}"))
			.andExpect(status().isNoContent());
	}

	@Test
	@DisplayName("로그아웃한 세션의 리프레시 토큰을 다시 써도 다른 기기 세션까지 폐기하지 않는다")
	void refreshWithLoggedOutSessionDoesNotRevokeOtherSessions() throws Exception {
		String socialId = newSocialId();
		JsonNode phone = login("kakao", socialId);
		JsonNode tablet = login("kakao", socialId);
		logout(phone.path("refreshToken").asString()).andExpect(status().isNoContent());

		refresh(phone.path("refreshToken").asString()).andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));

		refresh(tablet.path("refreshToken").asString()).andExpect(status().isOk());
	}

	@Test
	@DisplayName("탈취로 판단해 전체 폐기하면 그 회원의 액세스 토큰도 막힌다")
	void theftDetectionAlsoBlocksAccessTokens() throws Exception {
		JsonNode tokens = login("kakao", newSocialId());
		String original = tokens.path("refreshToken").asString();
		refresh(original).andExpect(status().isOk());
		timeShift.set(Duration.ofSeconds(31));

		refresh(original).andExpect(status().isUnauthorized());

		perform(get(ONBOARDING), tokens, null).andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("재발급해도 같은 세션이라 로그아웃하면 새로 받은 액세스 토큰도 막힌다")
	void rotatedTokensStayInSameSession() throws Exception {
		JsonNode first = login("kakao", newSocialId());
		JsonNode second = refresh(first.path("refreshToken").asString()).andExpect(status().isOk()).andReturnJson();

		logout(second.path("refreshToken").asString()).andExpect(status().isNoContent());

		perform(get(ONBOARDING), second, null).andExpect(status().isUnauthorized());
		perform(get(ONBOARDING), first, null).andExpect(status().isUnauthorized());
	}

	// ---------- 토큰 재발급 ----------

	@Test
	@DisplayName("재발급하면 새 토큰을 받고 새 토큰으로 다시 재발급할 수 있다")
	void refreshIssuesNewTokenThatCanRefreshAgain() throws Exception {
		JsonNode first = login("kakao", newSocialId());

		JsonNode second = refresh(first.path("refreshToken").asString()).andExpect(status().isOk()).andReturnJson();
		assertThat(second.path("refreshToken").asString()).isNotEqualTo(first.path("refreshToken").asString());
		assertThat(second.path("onboardingStatus").asString()).isEqualTo("AGREEMENT_REQUIRED");

		refresh(second.path("refreshToken").asString()).andExpect(status().isOk());
	}

	@Test
	@DisplayName("만료된 액세스 토큰이 헤더에 있어도 재발급은 된다")
	void refreshWorksWithExpiredAccessTokenHeader() throws Exception {
		JsonNode tokens = login("kakao", newSocialId());

		mockMvc.perform(post("/api/v1/auth/token/refresh")
				.header("Authorization", "Bearer expired.or.broken")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"refreshToken\":\"" + tokens.path("refreshToken").asString() + "\"}"))
			.andExpect(status().isOk());
	}

	@Test
	@DisplayName("가입 미완료 회원의 유효한 액세스 토큰이 헤더에 있어도 로그인·재발급은 가입 검사에 막히지 않는다")
	void publicAuthApisIgnoreOnboardingCheckEvenWithValidToken() throws Exception {
		String socialId = newSocialId();
		JsonNode tokens = login("kakao", socialId);
		String bearer = "Bearer " + tokens.path("accessToken").asString();

		mockMvc.perform(post("/api/v1/auth/kakao")
				.header("Authorization", bearer)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"token\":\"social-" + socialId + "\"}"))
			.andExpect(status().isOk());
		mockMvc.perform(post("/api/v1/auth/token/refresh")
				.header("Authorization", bearer)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"refreshToken\":\"" + tokens.path("refreshToken").asString() + "\"}"))
			.andExpect(status().isOk());
	}

	@Test
	@DisplayName("응답 유실로 30초 안에 이전 토큰을 다시 보내면 새로 발급하고 받지 못한 토큰은 폐기한다")
	void lostResponseRetryWithinGraceReissuesAndRevokesLostToken() throws Exception {
		String original = login("kakao", newSocialId()).path("refreshToken").asString();
		refresh(original).andExpect(status().isOk());

		timeShift.set(Duration.ofSeconds(10));
		String retried = refresh(original).andExpect(status().isOk()).andReturnJson().path("refreshToken").asString();

		refresh(retried).andExpect(status().isOk());
	}

	@Test
	@DisplayName("앱이 받지 못해 폐기된 토큰이 나중에 쓰이면 탈취로 보고 모든 토큰을 폐기한다")
	void reuseOfRevokedLostTokenRevokesAllTokens() throws Exception {
		String original = login("kakao", newSocialId()).path("refreshToken").asString();
		String lost = refresh(original).andExpect(status().isOk()).andReturnJson().path("refreshToken").asString();
		String retried = refresh(original).andExpect(status().isOk()).andReturnJson().path("refreshToken").asString();

		refresh(lost).andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));

		refresh(retried).andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("만료된 이전 토큰이 재사용돼도 탈취로 보고 모든 토큰을 폐기한다")
	void reuseOfExpiredRotatedTokenRevokesAllTokens() throws Exception {
		String original = login("kakao", newSocialId()).path("refreshToken").asString();
		timeShift.set(Duration.ofDays(14).minusSeconds(10));
		String latest = refresh(original).andExpect(status().isOk()).andReturnJson().path("refreshToken").asString();

		timeShift.set(Duration.ofDays(14).plusSeconds(60));
		refresh(original).andExpect(status().isUnauthorized());

		refresh(latest).andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("이전 토큰 재시도와 새 토큰 교체가 동시에 와도 유효한 토큰이 갈라지지 않는다")
	void concurrentRetryAndRotationDoNotForkTokens() throws Exception {
		String original = login("kakao", newSocialId()).path("refreshToken").asString();
		String second = refresh(original).andExpect(status().isOk()).andReturnJson().path("refreshToken").asString();

		List<RefreshOutcome> outcomes = runConcurrently(
			() -> refreshForToken(original),
			() -> refreshForToken(second));

		// 한 번에 하나씩 처리되므로 먼저 처리된 요청은 성공하고, 나중 요청은 탈취로 401이 된다.
		assertThat(outcomes).extracting(RefreshOutcome::status).containsExactlyInAnyOrder(200, 401);
		// 탈취 판단으로 전체가 폐기되어, 성공한 요청이 받은 토큰도 더는 쓸 수 없어야 한다.
		for (RefreshOutcome outcome : outcomes) {
			if (outcome.status() == 200) {
				refresh(outcome.refreshToken()).andExpect(status().isUnauthorized());
			}
		}
	}

	@Test
	@DisplayName("만료 직전에 교체된 토큰의 응답 유실 재시도는 받아 준다")
	void lostResponseRetryAcceptedEvenAfterOldTokenExpires() throws Exception {
		String original = login("kakao", newSocialId()).path("refreshToken").asString();
		timeShift.set(Duration.ofDays(14).minusSeconds(5));
		refresh(original).andExpect(status().isOk());

		timeShift.set(Duration.ofDays(14).plusSeconds(5));
		refresh(original).andExpect(status().isOk());
	}

	@Test
	@DisplayName("교체된 지 30초가 지난 토큰이 오면 탈취로 보고 모든 토큰을 폐기한다")
	void reuseAfterGraceRevokesAllTokens() throws Exception {
		String original = login("kakao", newSocialId()).path("refreshToken").asString();
		String latest = refresh(original).andExpect(status().isOk()).andReturnJson().path("refreshToken").asString();

		timeShift.set(Duration.ofSeconds(31));
		refresh(original).andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));

		refresh(latest).andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("이미 사용된 새 토큰이 있으면 30초 안이라도 이전 토큰 재사용은 탈취로 본다")
	void reuseWithinGraceAfterSuccessorUsedRevokesAll() throws Exception {
		String original = login("kakao", newSocialId()).path("refreshToken").asString();
		String second = refresh(original).andExpect(status().isOk()).andReturnJson().path("refreshToken").asString();
		String third = refresh(second).andExpect(status().isOk()).andReturnJson().path("refreshToken").asString();

		refresh(original).andExpect(status().isUnauthorized());
		refresh(third).andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("존재하지 않는 리프레시 토큰은 401 INVALID_REFRESH_TOKEN")
	void unknownRefreshTokenReturns401() throws Exception {
		refresh("unknown-token").andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
	}

	// ---------- 앱 전용 API ----------

	@Test
	@DisplayName("브라우저처럼 HTML을 요청해도 없는 경로는 JSON 404 NOT_FOUND")
	void unknownPathReturnsJsonEvenForHtmlRequests() throws Exception {
		JsonNode tokens = login("kakao", newSocialId());
		completeOnboarding(tokens);

		mockMvc.perform(get("/index.html")
				.header("Authorization", "Bearer " + tokens.path("accessToken").asString())
				.accept(MediaType.TEXT_HTML))
			.andExpect(status().isNotFound())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}

	@Test
	@DisplayName("서블릿 단계 오류가 넘어오는 /error 경로도 HTML 대신 JSON으로 응답한다")
	void errorPathReturnsJson() throws Exception {
		mockMvc.perform(get("/error")
				.requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 400)
				.accept(MediaType.TEXT_HTML))
			.andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
	}

	@Test
	@DisplayName("세션 쿠키를 만들지 않는다")
	void doesNotCreateSessionCookie() throws Exception {
		given(socialVerifier.verify(eq(SocialProvider.KAKAO), anyString()))
			.willReturn(new SocialAccount(newSocialId(), null));

		mockMvc.perform(post("/api/v1/auth/kakao").contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"t\"}"))
			.andExpect(status().isOk())
			.andExpect(header().doesNotExist("Set-Cookie"));
	}

	private RefreshOutcome refreshForToken(String refreshToken) throws Exception {
		JsonResult result = refresh(refreshToken);
		String newToken = result.status() == 200 ? result.andReturnJson().path("refreshToken").asString() : null;
		return new RefreshOutcome(result.status(), newToken);
	}

	private record RefreshOutcome(int status, String refreshToken) {
	}
}
