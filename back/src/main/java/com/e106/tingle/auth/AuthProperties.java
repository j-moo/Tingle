package com.e106.tingle.auth;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 인증 설정값. 실제 값은 환경변수로 주입한다 (application.properties, .env.example 참고).
 */
@ConfigurationProperties("app.auth")
public record AuthProperties(
	String jwtSecret,
	Duration accessTokenTtl,
	Duration refreshTokenTtl,
	Duration refreshReuseGrace,
	String googleClientId,
	String kakaoAppId,
	String naverClientId,
	String naverClientSecret,
	String naverRedirectUri,
	String issuer,
	String audience
) {
}
