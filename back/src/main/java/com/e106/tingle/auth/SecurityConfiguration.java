package com.e106.tingle.auth;

import java.nio.charset.StandardCharsets;
import java.time.Clock;

import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

import com.e106.tingle.global.error.ErrorResponse;

import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class SecurityConfiguration {
	@Bean
	public JwtDecoder jwtDecoder(@Value("${app.auth.jwt-secret}") String secret,
		@Value("${app.auth.issuer}") String issuer, @Value("${app.auth.audience}") String audience, Clock clock) {
		byte[] key = secret.getBytes(StandardCharsets.UTF_8);
		if (key.length < 32) {
			throw new IllegalStateException("JWT_SECRET must contain at least 32 bytes");
		}
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(new SecretKeySpec(key, "HmacSHA256"))
			.macAlgorithm(MacAlgorithm.HS256).build();
		decoder.setJwtValidator(jwt -> {
			if (jwt.getExpiresAt() == null
				|| (jwt.getIssuer() != null && !issuer.equals(jwt.getClaimAsString("iss")))
				|| (jwt.getAudience() != null && !jwt.getAudience().isEmpty() && !jwt.getAudience().contains(audience))
				|| (jwt.getNotBefore() != null && jwt.getNotBefore().isAfter(clock.instant()))) {
				return OAuth2TokenValidatorResult.failure(new OAuth2Error("ACCESS_TOKEN_INVALID"));
			}
			if (!clock.instant().isBefore(jwt.getExpiresAt())) {
				return OAuth2TokenValidatorResult.failure(new OAuth2Error("ACCESS_TOKEN_EXPIRED"));
			}
			return OAuth2TokenValidatorResult.success();
		});
		return decoder;
	}

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtDecoder decoder,
		SessionJwtConverter converter, ObjectMapper mapper) throws Exception {
		http.csrf(AbstractHttpConfigurer::disable)
			.formLogin(AbstractHttpConfigurer::disable)
			.httpBasic(AbstractHttpConfigurer::disable)
			.requestCache(AbstractHttpConfigurer::disable)
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests(auth -> auth
				.requestMatchers("/api/v1/match-proposals/**").hasRole("MEMBER")
				.anyRequest().denyAll())
			.exceptionHandling(errors -> errors
				.authenticationEntryPoint((request, response, exception) ->
					writeError(response, mapper, 401, "AUTH_REQUIRED", "로그인이 필요해요."))
				.accessDeniedHandler((request, response, exception) ->
					writeError(response, mapper, 403, "ONBOARDING_REQUIRED", "가입을 완료해 주세요.")))
			.oauth2ResourceServer(oauth -> oauth
				.jwt(jwt -> jwt.decoder(decoder).jwtAuthenticationConverter(converter))
				.authenticationEntryPoint((request, response, exception) ->
					writeError(response, mapper, 401, authenticationCode(exception), "로그인 상태를 확인해 주세요.")));
		return http.build();
	}

	private static String authenticationCode(Throwable failure) {
		for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
			if (cause instanceof JwtValidationException validation
				&& validation.getErrors().stream()
					.anyMatch(error -> "ACCESS_TOKEN_EXPIRED".equals(error.getErrorCode()))) {
				return "ACCESS_TOKEN_EXPIRED";
			}
			if (cause instanceof OAuth2AuthenticationException oauth
				&& "SESSION_REVOKED".equals(oauth.getError().getErrorCode())) {
				return "SESSION_REVOKED";
			}
		}
		return "ACCESS_TOKEN_INVALID";
	}

	private static void writeError(HttpServletResponse response, ObjectMapper mapper,
		int status, String code, String message) throws java.io.IOException {
		response.setStatus(status);
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		mapper.writeValue(response.getOutputStream(), new ErrorResponse(code, message));
	}
}
