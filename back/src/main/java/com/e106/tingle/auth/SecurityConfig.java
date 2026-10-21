package com.e106.tingle.auth;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;

import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.global.error.ErrorResponse;
import com.nimbusds.jose.jwk.source.ImmutableSecret;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/**
 * 서비스 액세스 토큰(JWT, HS256) 검증과 공개 API 설정.
 * 액세스 토큰은 서명·만료에 더해 로그인 세션(sid)이 폐기되지 않았는지 요청마다 DB에서 확인한다.
 */
@Configuration
public class SecurityConfig {

	private static final int MIN_SECRET_BYTES = 32;
	private static final String TOKEN_EXPIRED = "token_expired";
	private static final String SESSION_REVOKED = "session_revoked";
	// POST /api/v1/auth/{provider} (소셜 로그인)와 /api/v1/auth/logout.
	// 지원하지 않는 provider 는 컨트롤러가 400 INVALID_PROVIDER 로 응답한다.
	private static final Pattern SOCIAL_LOGIN = Pattern.compile("^/api/v1/auth/[^/]+$");

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, JsonMapper jsonMapper) throws Exception {
		http
			.csrf(AbstractHttpConfigurer::disable)
			.httpBasic(AbstractHttpConfigurer::disable)
			.formLogin(AbstractHttpConfigurer::disable)
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests(auth -> auth
				.requestMatchers(SecurityConfig::isPublic).permitAll()
				.requestMatchers("/error").permitAll()
				.anyRequest().authenticated())
			.oauth2ResourceServer(resourceServer -> resourceServer
				.jwt(Customizer.withDefaults())
				.bearerTokenResolver(bearerTokenResolver())
				.authenticationEntryPoint((request, response, exception) ->
					writeError(response, jsonMapper, errorOf(exception))))
			.exceptionHandling(exceptions -> exceptions
				.authenticationEntryPoint((request, response, exception) ->
					writeError(response, jsonMapper, ErrorCode.AUTH_REQUIRED)));
		return http.build();
	}

	/**
	 * 인증 없이 호출하는 API: 약관 조회, 소셜 로그인, 토큰 재발급, 로그아웃(리프레시 토큰으로 본인 확인).
	 */
	static boolean isPublic(HttpServletRequest request) {
		String path = request.getRequestURI();
		String method = request.getMethod();
		if (HttpMethod.GET.matches(method)) {
			return path.equals("/api/v1/agreements");
		}
		return HttpMethod.POST.matches(method)
			&& (path.equals("/api/v1/auth/token/refresh") || SOCIAL_LOGIN.matcher(path).matches());
	}

	/**
	 * 공개 API에서는 Authorization 헤더를 읽지 않는다.
	 * 앱이 만료된 액세스 토큰을 헤더에 남긴 채 재발급·로그인을 요청해도 401로 막히지 않게 하기 위해서다.
	 */
	private static BearerTokenResolver bearerTokenResolver() {
		DefaultBearerTokenResolver delegate = new DefaultBearerTokenResolver();
		return request -> isPublic(request) ? null : delegate.resolve(request);
	}

	@Bean
	SecretKey jwtSecretKey(AuthProperties properties) {
		String secret = properties.jwtSecret();
		byte[] bytes = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
		if (bytes.length < MIN_SECRET_BYTES) {
			throw new IllegalStateException("JWT_SECRET 환경변수에 32바이트 이상의 값을 넣어 주세요 (.env.example 참고).");
		}
		return new SecretKeySpec(bytes, "HmacSHA256");
	}

	@Bean
	JwtEncoder jwtEncoder(SecretKey jwtSecretKey) {
		return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSecretKey));
	}

	@Bean
	JwtDecoder jwtDecoder(SecretKey jwtSecretKey, RefreshTokenRepository refreshTokenRepository, Clock clock,
		AuthProperties properties) {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSecretKey)
			.macAlgorithm(MacAlgorithm.HS256)
			.build();
		decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
			claimsValidator(clock, properties), activeSessionValidator(refreshTokenRepository)));
		return decoder;
	}

	/**
	 * 401 을 앱이 처리 방법을 고를 수 있게 나눈다.
	 * 서명이 맞는 토큰만 검증기(만료·세션)까지 오므로, 검증기 오류가 없으면 위조·손상된 토큰이다.
	 */
	static ErrorCode errorOf(AuthenticationException exception) {
		if (!(exception instanceof OAuth2AuthenticationException)) {
			return ErrorCode.AUTH_REQUIRED;
		}
		for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
			if (cause instanceof JwtValidationException invalid) {
				List<String> codes = invalid.getErrors().stream().map(OAuth2Error::getErrorCode).toList();
				// 끝난 세션은 재발급해도 실패하므로 만료보다 먼저 본다.
				if (codes.contains(SESSION_REVOKED)) {
					return ErrorCode.SESSION_REVOKED;
				}
				if (codes.contains(TOKEN_EXPIRED)) {
					return ErrorCode.ACCESS_TOKEN_EXPIRED;
				}
			}
		}
		return ErrorCode.ACCESS_TOKEN_INVALID;
	}

	/**
	 * 만료(exp 필수)·사용 시작(nbf)·발급자(iss)·대상(aud)을 서비스 Clock 기준으로 검사한다.
	 * 우리가 발급하는 토큰에는 iss·aud 가 없고, 있으면 설정값과 같아야 한다. 만료만 따로 표시해 앱이 재발급할 수 있게 한다.
	 */
	private static OAuth2TokenValidator<Jwt> claimsValidator(Clock clock, AuthProperties properties) {
		OAuth2TokenValidatorResult invalid = OAuth2TokenValidatorResult.failure(
			new OAuth2Error("invalid_token", "액세스 토큰이 올바르지 않습니다.", null));
		OAuth2TokenValidatorResult expired = OAuth2TokenValidatorResult.failure(
			new OAuth2Error(TOKEN_EXPIRED, "액세스 토큰이 만료되었습니다.", null));
		return jwt -> {
			Instant now = clock.instant();
			if (jwt.getExpiresAt() == null
				|| (jwt.getNotBefore() != null && jwt.getNotBefore().isAfter(now))
				|| (jwt.hasClaim("iss") && !properties.issuer().equals(jwt.getClaimAsString("iss")))
				|| (jwt.getAudience() != null && !jwt.getAudience().isEmpty()
					&& !jwt.getAudience().contains(properties.audience()))) {
				return invalid;
			}
			return now.isBefore(jwt.getExpiresAt()) ? OAuth2TokenValidatorResult.success() : expired;
		};
	}

	// 로그아웃·탈취 감지로 끝난 세션(리프레시 토큰 family)과 정지·탈퇴 회원의 토큰은 만료 전이라도 거절한다. sid 가 없는 토큰도 거절한다.
	private static OAuth2TokenValidator<Jwt> activeSessionValidator(RefreshTokenRepository refreshTokenRepository) {
		OAuth2TokenValidatorResult revoked = OAuth2TokenValidatorResult.failure(
			new OAuth2Error(SESSION_REVOKED, "로그인 세션이 종료되었습니다.", null));
		return jwt -> {
			try {
				UUID familyId = UUID.fromString(jwt.getClaimAsString(TokenService.SESSION_CLAIM));
				long userId = Long.parseLong(jwt.getSubject());
				return refreshTokenRepository.isSessionActive(familyId, userId)
					? OAuth2TokenValidatorResult.success() : revoked;
			} catch (IllegalArgumentException | NullPointerException e) {
				return revoked;
			}
		};
	}

	private static void writeError(HttpServletResponse response, JsonMapper jsonMapper, ErrorCode errorCode)
		throws java.io.IOException {
		response.setStatus(errorCode.getStatus().value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		jsonMapper.writeValue(response.getOutputStream(), ErrorResponse.from(errorCode));
	}
}
