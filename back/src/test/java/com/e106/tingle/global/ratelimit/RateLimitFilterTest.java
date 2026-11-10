package com.e106.tingle.global.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import tools.jackson.databind.json.JsonMapper;

class RateLimitFilterTest {

	// 분의 한가운데(30초)로 고정해 테스트 도중 1분 구간이 바뀌지 않게 한다
	private final Clock clock = Clock.fixed(Instant.parse("2026-11-10T00:00:30Z"), ZoneOffset.UTC);
	private final RateLimitFilter filter = new RateLimitFilter(clock, JsonMapper.builder().build());

	@AfterEach
	void clearSecurity() {
		SecurityContextHolder.clearContext();
	}

	@Test
	@DisplayName("같은 IP의 로그인은 기준까지 통과하고 넘으면 429 RATE_LIMITED, 다른 IP·재발급은 따로 센다")
	void loginLimitedPerIp() throws Exception {
		for (int i = 0; i < RateLimitFilter.LOGIN_PER_MINUTE; i++) {
			assertThat(call("POST", "/api/auth/kakao", "1.1.1.1").getStatus()).isEqualTo(200);
		}
		MockHttpServletResponse blocked = call("POST", "/api/auth/kakao", "1.1.1.1");
		assertThat(blocked.getStatus()).isEqualTo(429);
		assertThat(blocked.getContentAsString()).contains("\"code\":\"RATE_LIMITED\"");
		assertThat(blocked.getHeader("Retry-After")).isEqualTo("30");

		assertThat(call("POST", "/api/auth/kakao", "2.2.2.2").getStatus()).isEqualTo(200);
		assertThat(call("POST", "/api/auth/token/refresh", "1.1.1.1").getStatus()).isEqualTo(200);
		assertThat(call("POST", "/api/auth/logout", "1.1.1.1").getStatus()).isEqualTo(200);
	}

	@Test
	@DisplayName("닉네임 확인은 회원마다 따로 센다")
	void nicknameLimitedPerMember() throws Exception {
		for (int i = 0; i < RateLimitFilter.NICKNAME_PER_MINUTE; i++) {
			assertThat(nickname("1").getStatus()).isEqualTo(200);
		}
		assertThat(nickname("1").getStatus()).isEqualTo(429);
		assertThat(nickname("2").getStatus()).isEqualTo(200);
	}

	@Test
	@DisplayName("경로를 퍼센트 인코딩하거나 HEAD 로 보내도 같은 횟수에 들어간다")
	void encodedPathAndHeadCounted() throws Exception {
		for (int i = 0; i < RateLimitFilter.NICKNAME_PER_MINUTE - 1; i++) {
			assertThat(nickname("3", "GET", "/api/users/nickname-%61vailability").getStatus()).isEqualTo(200);
		}
		assertThat(nickname("3", "HEAD", "/api/users/nickname-availability").getStatus()).isEqualTo(200);
		assertThat(nickname("3", "GET", "/api/users/nickname-availability").getStatus()).isEqualTo(429);
		for (int i = 0; i < RateLimitFilter.REFRESH_PER_MINUTE; i++) {
			call("POST", "/api/auth/token/%72efresh", "3.3.3.3");
		}
		assertThat(call("POST", "/api/auth/token/refresh", "3.3.3.3").getStatus()).isEqualTo(429);
	}

	private MockHttpServletResponse nickname(String userId) throws Exception {
		return nickname(userId, "GET", "/api/users/nickname-availability");
	}

	private MockHttpServletResponse nickname(String userId, String method, String path) throws Exception {
		Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject(userId).build();
		SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
		return call(method, path, "1.1.1.1");
	}

	private MockHttpServletResponse call(String method, String path, String ip) throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest(method, path);
		request.addHeader("X-Real-IP", ip);
		MockHttpServletResponse response = new MockHttpServletResponse();
		filter.doFilter(request, response, new MockFilterChain());
		return response;
	}
}
