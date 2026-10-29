package com.e106.tingle.keyring;

import java.io.IOException;
import java.util.UUID;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** 발급 응답은 인증·입력 오류를 포함해 캐시하지 않고 요청별 추적 번호를 제공한다. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RegistrationChallengeHeaders extends OncePerRequestFilter {

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !"POST".equals(request.getMethod())
			|| !request.getRequestURI().equals(request.getContextPath() + "/api/keyrings/registration-challenges");
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
		throws ServletException, IOException {
		response.setHeader("X-Request-Id", UUID.randomUUID().toString());
		response.setHeader("Cache-Control", "no-store");
		chain.doFilter(request, response);
	}
}
