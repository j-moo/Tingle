package com.e106.tingle.auth;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

@Component
public class SessionJwtConverter implements Converter<Jwt, AbstractAuthenticationToken> {
	private final MemberAccessRepository members;
	private final Clock clock;

	public SessionJwtConverter(MemberAccessRepository members, Clock clock) {
		this.members = members;
		this.clock = clock;
	}

	@Override
	public AbstractAuthenticationToken convert(Jwt jwt) {
		long userId;
		UUID familyId;
		try {
			userId = Long.parseLong(jwt.getSubject());
			familyId = UUID.fromString(jwt.getClaimAsString("sid"));
			if (userId <= 0) {
				throw new IllegalArgumentException();
			}
		} catch (IllegalArgumentException | NullPointerException exception) {
			throw invalid("ACCESS_TOKEN_INVALID");
		}
		if (!members.activeSession(userId, familyId, clock.instant())) {
			throw invalid("SESSION_REVOKED");
		}
		String role = members.ready(userId, clock.instant()) ? "ROLE_MEMBER" : "ROLE_ONBOARDING";
		return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority(role)));
	}

	private OAuth2AuthenticationException invalid(String code) {
		return new OAuth2AuthenticationException(new OAuth2Error(code));
	}
}
