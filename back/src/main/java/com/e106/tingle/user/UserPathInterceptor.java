package com.e106.tingle.user;

import java.util.Map;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 경로의 {userId}가 로그인한 회원(액세스 토큰 sub)과 같은지 확인한다. 다르면 아무것도 하지 않고 403 FORBIDDEN.
 * 회원번호는 순번이라 짐작할 수 있으므로 {userId}가 들어간 모든 API에 한 곳에서 적용한다(auth.md "경로의 회원 번호").
 * 가입 단계 검사(OnboardingInterceptor)보다 먼저 실행해 남의 번호로는 단계 정보도 드러나지 않게 한다.
 */
@Configuration
public class UserPathInterceptor implements HandlerInterceptor, WebMvcConfigurer {

	private static final String USER_ID = "userId";

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(this).addPathPatterns("/api/**").order(Ordered.HIGHEST_PRECEDENCE);
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
		if (!(variables instanceof Map<?, ?> map) || !map.containsKey(USER_ID)) {
			return true;
		}
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (!(authentication instanceof JwtAuthenticationToken token) || !token.getName().equals(map.get(USER_ID))) {
			throw new BusinessException(ErrorCode.FORBIDDEN);
		}
		return true;
	}
}
