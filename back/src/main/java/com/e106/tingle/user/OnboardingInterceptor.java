package com.e106.tingle.user;

import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * 아직 도달하지 않은 가입 단계의 API를 호출하면 403 ONBOARDING_REQUIRED로 막는다.
 * API마다 @RequiresOnboarding 으로 호출 가능한 가장 이른 단계를 정하고, 붙이지 않으면 가입 완료(READY)가 필요하다.
 * 판단 규칙은 OnboardingService.canAccess 한 곳에 있다.
 * 공개 API(소셜 로그인·재발급·로그아웃·약관 조회)는 인증 객체가 없어 검사하지 않는다.
 */
@Configuration
@RequiredArgsConstructor
public class OnboardingInterceptor implements HandlerInterceptor, WebMvcConfigurer {

	private final OnboardingService onboardingService;

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		// 운영자 API(/api/admin/**)는 회원 가입 단계와 무관하다. 인증·운영자 확인은 보안 설정과 서비스가 한다.
		registry.addInterceptor(this).addPathPatterns("/api/**").excludePathPatterns("/api/admin/**");
	}

	// ponytail: 요청마다 약관·프로필 조회 2건. 트래픽이 커지면 단계를 캐시하거나 저장해 둔다.
	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (!(handler instanceof HandlerMethod method) || !(authentication instanceof JwtAuthenticationToken token)) {
			return true;
		}
		RequiresOnboarding annotation = method.getMethodAnnotation(RequiresOnboarding.class);
		OnboardingStatus required = annotation == null ? OnboardingStatus.READY : annotation.value();
		if (required == OnboardingStatus.AGREEMENT_REQUIRED) {
			return true;
		}
		if (!onboardingService.canAccess(Long.parseLong(token.getName()), required)) {
			throw new BusinessException(ErrorCode.ONBOARDING_REQUIRED);
		}
		return true;
	}
}
