package com.e106.tingle.auth.dto;

import java.time.Instant;

import com.e106.tingle.user.OnboardingStatus;

/**
 * 로그인·토큰 재발급 응답 (통합 OpenAPI TokenPair, 6개 필드 모두 필수).
 */
public record TokenResponse(
	String accessToken,
	String refreshToken,
	String tokenType,
	Instant accessTokenExpiresAt,
	Instant refreshTokenExpiresAt,
	OnboardingStatus onboardingStatus
) {
}
